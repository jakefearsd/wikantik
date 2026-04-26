---
title: Search Index and Knowledge Graph Rebuild
type: article
tags: [administration, operations, search, knowledge-graph, embeddings, chunking, entity-extraction]
date: 2026-04-26
status: active
summary: Operator runbook for the four-phase end-to-end rebuild of chunks, Lucene, embeddings, and the entity-extraction layer. Covers the bin/kg-rebuild.sh orchestration script, the optional KG reset step, and how to use the rebuild as an experiment harness for tuning chunker config, prefilter thresholds, and extractor models.
related: [WikantikKnowledgeGraphAdmin]
audience: [humans]
---

# Search Index and Knowledge Graph Rebuild

This page documents the operator runbook for rebuilding every derived index Wikantik maintains over its page corpus. Use it when:

- The chunker config has changed and existing chunks need to be regenerated.
- The embedding model has changed and existing vectors no longer match the runtime embedder.
- A new entity-extraction model needs to be evaluated against the whole corpus.
- The knowledge graph has accumulated noise from earlier extraction runs and you want a clean slate.
- You're sweeping prefilter / chunker / model parameters and need a reproducible reset between trials.

The rebuild is implemented as a four-phase pipeline orchestrated by `bin/kg-rebuild.sh`. Each phase is independently skippable, so a typical experiment iteration only re-runs the cheap phases — you don't re-chunk every time you swap an extractor model.

## What gets rebuilt

The pipeline touches four derived data layers, in order:

1. **Chunks** (`kg_content_chunks`) and **Lucene full-text index** — wiped and rebuilt from page markdown on disk by `ContentIndexRebuildService`.
2. **Chunk embeddings** (`kg_content_chunk_embeddings`) — wiped (via `ON DELETE CASCADE` from phase 1) and repopulated by `BootstrapEmbeddingIndexer` against the configured embedder model.
3. **Knowledge graph** (`kg_nodes`, `kg_edges`, `kg_proposals`) — *optional, opt-in only*. Removes pending proposals and AI-inferred nodes; keeps `human-authored` and `ai-reviewed` rows. Skipped by default.
4. **Entity mentions and proposals** (`chunk_entity_mentions`, `kg_proposals`) — repopulated by `bin/kg-extract.sh` calling the configured extractor model against every (filtered) chunk.

Source-of-truth markdown in `docs/wikantik-pages/` is never touched. Page metadata (frontmatter, canonical IDs, structural spine) is preserved.

### Cascade behaviour worth knowing

Because `chunk_entity_mentions.chunk_id` and `kg_content_chunk_embeddings.chunk_id` both reference `kg_content_chunks(id)` with `ON DELETE CASCADE`, **phase 1 implicitly wipes both downstream tables**. Phase 2 fully repopulates embeddings, and phase 4 fully repopulates mentions, so the cascade is a feature: you can't end up with stale rows pointing at chunks that no longer exist.

## Sample commands

A starter set covering the workflows you'll use most. Anything after `--` (or any unrecognised arg) is forwarded verbatim to `bin/kg-extract.sh` — see the [extractor CLI help](#extractor-cli-pass-through) for the full list.

### Plan and preview before committing

```bash
# Print the four-phase plan, exit 0. Touches nothing.
bin/kg-rebuild.sh --dry-run -- \
    --ollama-model qwen2.5:1.5b-instruct --concurrency 6 --prefilter

# Walk every page on disk, run the chunker in memory at given config,
# print size distribution + how many chunks the prefilter would drop.
# ~2 seconds. No DB, no Tomcat, no LLM.
bin/kg-extract.sh --chunker-stats-only --chunker-merge-forward-tokens 300

# Walk the live chunks in the DB, evaluate the prefilter, print
# reason-by-reason skip counts. ~15 seconds. No LLM.
bin/kg-extract.sh --stats-only --prefilter-min-tokens 50
```

### End-to-end rebuilds

```bash
# Vanilla full rebuild using whatever's in wikantik-custom.properties
# for chunker, embedder, and extractor. Useful as a "reset to defaults".
bin/kg-rebuild.sh

# Experiment iteration: small fast model + prefilter at threshold 30,
# concurrency 6, --force so the chunks are re-extracted from scratch.
# Wall time on a 4060Ti with ~22K chunks: ~60-90 minutes.
bin/kg-rebuild.sh -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6 \
    --prefilter --prefilter-min-tokens 30 \
    --force

# Tabula rasa: also wipes pending proposals and ai-inferred KG nodes
# before re-extracting. Use when comparing two models on the same chunks
# and you want a guaranteed clean baseline.
bin/kg-rebuild.sh --reset-kg -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6 --prefilter --force
```

### Inner-loop iteration *(after the first full rebuild)*

```bash
# Just re-extract: the chunks and embeddings from the previous run are
# still good. Cheapest way to A/B two extractor models or prefilter
# settings.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6 --prefilter --force

# Same, but with a clean KG between trials so mentions/proposals from
# the prior run don't muddy the comparison.
bin/kg-rebuild.sh --reset-kg --skip-chunks --skip-embeddings -- \
    --ollama-model phi3.5:3.8b \
    --concurrency 4 --prefilter --force
```

### Smoke tests

```bash
# 5 pages end-to-end. Use after any structural change to confirm the
# whole pipeline still wires up before committing to a multi-hour run.
# Wall time: ~5-10 minutes depending on model and host load.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 4 --prefilter --force \
    --max-pages 5

# Same but with a dry-run extraction (filter still evaluates and logs,
# but extractor receives every chunk). Useful when validating the
# prefilter's reasoning on representative pages.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 4 --prefilter-dry-run --force \
    --max-pages 5
```

### Targeted rebuilds

```bash
# I changed wikantik.chunker.* and restarted Tomcat — re-chunk and
# re-embed but defer extraction until I've checked chunk shape.
bin/kg-rebuild.sh --skip-extract

# I changed the embedding model — re-embed only. Chunks stay; the
# extraction layer is unaffected because mentions don't depend on
# vectors.
bin/kg-rebuild.sh --skip-chunks --skip-extract

# I want to wipe the KG without re-extracting, e.g. before manual
# curation work. Phases 1, 2, 4 skipped; phase 3 runs alone.
bin/kg-rebuild.sh --reset-kg --skip-chunks --skip-embeddings --skip-extract
```

### Backwards-compatible (extractor only, no orchestration)

`bin/kg-extract.sh` is still callable directly when you don't need the chunker/embedder rebuild — for example, when extending an existing extraction run with new pages added since the last rebuild.

```bash
# Run with whatever's in wikantik-custom.properties
bin/kg-extract.sh

# Override at the command line, no Tomcat dance needed
bin/kg-extract.sh --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6 --prefilter --force
```

## The four phases in detail

### Phase 1 — Chunk and Lucene rebuild

Triggered by `POST /admin/content/rebuild-indexes`. Polls `GET /admin/content/index-status` for the `rebuild.state` field, returning to `IDLE` when complete.

What it does:

- Truncates `kg_content_chunks` (cascading to embeddings and mentions).
- Clears the Lucene index.
- Reads every page from the configured `PageProvider` (filesystem in default deploys).
- Runs `ContentChunker` with the properties currently set in `wikantik-custom.properties`:
  - `wikantik.chunker.max_tokens` (default `512`)
  - `wikantik.chunker.merge_forward_tokens` (default `150`)
- Writes new chunks and queues the page for Lucene reindexing.

Typical wall time: ~1-2 minutes for ~1000 pages.

To change chunker behaviour: edit `tomcat/tomcat-11/lib/wikantik-custom.properties`, restart Tomcat, then run phase 1. The rebuild service uses whatever chunker was constructed at engine startup.

### Phase 2 — Chunk embedding reindex

Triggered by `POST /admin/content/reindex-embeddings`. Polls the same status endpoint for `embeddings.bootstrap.state`. Terminal states: `COMPLETED`, `SKIPPED_ALREADY_POPULATED`, `SKIPPED_NO_CHUNKS` (treated as success), `FAILED`, `DISABLED` (treated as fatal).

What it does:

- Walks every row in `kg_content_chunks`.
- Calls the configured embedder (default Ollama `bge-m3`) for each chunk's text.
- Writes vectors to `kg_content_chunk_embeddings`.

Typical wall time: 5-15 minutes for ~17-22K chunks against a local Ollama embedder.

If hybrid retrieval is disabled (`wikantik.search.hybrid.enabled=false`), the script logs a warning and skips this phase rather than failing. That makes it safe to use the same script in a deploy where hybrid is intentionally off.

### Phase 3 — Knowledge graph reset *(opt-in)*

Skipped by default. Enable with `--reset-kg`. Prompts for confirmation before running unless `--yes` is also passed.

What it does:

```sql
DELETE FROM kg_proposals WHERE status='pending';
DELETE FROM kg_nodes     WHERE provenance='ai-inferred';
```

The `ai-inferred` deletion cascades to `kg_edges` and (already-empty post-phase-1) `chunk_entity_mentions`. Two provenance classes are preserved:

- `human-authored` — manually curated entities and edges.
- `ai-reviewed` — AI-proposed entities that a human has approved (status moves through `kg_proposals` and the surviving rows get re-tagged).

Use phase 3 when you want to compare two extraction runs against a clean baseline. Without it, a second extraction run accumulates on top of the first — fine for incremental tuning, confusing for A/B comparisons.

### Phase 4 — Entity extraction

Forwards to `bin/kg-extract.sh` with whatever args you passed to `bin/kg-rebuild.sh`. The extractor walks every chunk, runs the configured prefilter, calls the LLM for survivors, parses the JSON response, and writes mentions and proposals.

This is the only phase that takes hours rather than minutes (depending on model size, concurrency, prefilter aggressiveness, and host load).

See `bin/kg-extract.sh --help` for the complete flag list. The most common knobs:

| Flag | Purpose |
|---|---|
| `--ollama-model <tag>` | Switch model without touching config |
| `--concurrency <N>` | 1-10; raise for small models on a fast GPU |
| `--prefilter` | Enable the chunk prefilter |
| `--prefilter-min-tokens <N>` | Drop chunks below N estimated tokens (default 20) |
| `--force` | Clear prior mentions per chunk before re-extracting |
| `--max-pages <N>` | Cap to first N pages — for smoke tests |

## Skipping phases

Each phase has a `--skip-*` flag, useful when you've already done the expensive work and want to iterate on a downstream phase:

```bash
# I just changed the extractor model — re-extract only, keep chunks/embeddings.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct --concurrency 6 --prefilter --force

# I want to inspect chunks first; defer extraction.
bin/kg-rebuild.sh --skip-extract

# I'm testing a new chunker config; embeddings still match for vector quality
# but the per-page chunk count will change. Re-do chunks + embeddings, defer
# extraction until I'm happy with chunk shape.
bin/kg-rebuild.sh --skip-extract
```

Combined with `--dry-run`, the planner output makes it easy to confirm what will happen before committing to a long run.

## Experiment workflow

A typical tuning loop looks like this:

1. **Survey the chunker** without writing anything:

    ```bash
    bin/kg-extract.sh --chunker-stats-only \
        --chunker-merge-forward-tokens 300
    ```

    Reads markdown from disk, runs the chunker in memory, prints the size distribution. Takes ~1.5 seconds. Does not require Tomcat or a DB.

2. **Survey the prefilter** against the live chunks:

    ```bash
    bin/kg-extract.sh --stats-only --prefilter-min-tokens 50
    ```

    Walks `kg_content_chunks`, evaluates the prefilter, prints reason-by-reason skip counts. Takes ~15 seconds. Does not call the LLM.

3. **Decide on a chunker config**, edit `wikantik-custom.properties`, restart Tomcat, then commit to phase 1 + phase 2:

    ```bash
    bin/kg-rebuild.sh --skip-extract
    ```

4. **Sweep extractor models / prefilter thresholds** by re-running phase 4 only:

    ```bash
    bin/kg-rebuild.sh --skip-chunks --skip-embeddings --reset-kg -- \
        --ollama-model qwen2.5:1.5b-instruct \
        --concurrency 6 --prefilter --prefilter-min-tokens 30 \
        --max-pages 20 --force
    ```

    `--reset-kg` between trials gives you a clean slate; `--max-pages 20` keeps each trial under ten minutes.

5. **When satisfied**, drop `--max-pages` and run for real.

## Inspection queries

After a run, these psql snippets confirm the pipeline produced sane data. Pull credentials the same way the rebuild script does:

```bash
PGPASS=$(grep -oE 'password="[^"]+"' tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml \
         | head -1 | sed -E 's/password="([^"]+)"/\1/')
PSQL="env PGPASSWORD=$PGPASS psql -h localhost -U jspwiki jspwiki"
```

```sql
-- 1. Chunks per page (post-phase-1 sanity)
SELECT page_name, COUNT(*) FROM kg_content_chunks GROUP BY page_name
 ORDER BY COUNT(*) DESC LIMIT 20;

-- 2. Embedding coverage (post-phase-2 sanity)
SELECT COUNT(*) AS chunks, (SELECT COUNT(*) FROM kg_content_chunk_embeddings) AS embeddings
  FROM kg_content_chunks;

-- 3. Mentions per extractor (post-phase-4: distinguishes models because
--    OllamaEntityExtractor.code() returns the configured model tag)
SELECT extractor, COUNT(*) AS n_mentions FROM chunk_entity_mentions
 GROUP BY extractor ORDER BY n_mentions DESC;

-- 4. Proposals queue depth and lineage
SELECT proposal_type, status, COUNT(*) FROM kg_proposals
 GROUP BY proposal_type, status ORDER BY 1, 2;

-- 5. Pages with zero mentions (might be 100% prefiltered)
SELECT cc.page_name, COUNT(cc.id) AS chunks,
       COUNT(m.chunk_id) AS mentions
  FROM kg_content_chunks cc
  LEFT JOIN chunk_entity_mentions m ON m.chunk_id = cc.id
 GROUP BY cc.page_name HAVING COUNT(m.chunk_id) = 0
 ORDER BY 2 DESC LIMIT 20;
```

## Configuration reference

Properties read at engine startup, set in `tomcat/tomcat-11/lib/wikantik-custom.properties`. Restart Tomcat after editing.

| Property | Default | Affects |
|---|---|---|
| `wikantik.chunker.max_tokens` | `512` | Phase 1: hard ceiling on a non-atomic chunk |
| `wikantik.chunker.merge_forward_tokens` | `150` | Phase 1: floor below which a chunk merges into the next section |
| `wikantik.search.hybrid.enabled` | (deploy-specific) | Phase 2: false skips embedding reindex with a warning |
| `wikantik.knowledge.extractor.backend` | `disabled` | Phase 4: `ollama` / `claude` / `disabled` |
| `wikantik.knowledge.extractor.ollama.model` | `gemma4-assist:latest` | Phase 4: which Ollama model to call |
| `wikantik.knowledge.extractor.prefilter.enabled` | `false` | Phase 4: master switch for the chunk prefilter |
| `wikantik.knowledge.extractor.prefilter.min_tokens` | `20` | Phase 4: too-short threshold |

CLI flags on `bin/kg-extract.sh` override these properties for a single run, which is what makes the experiment loop fast — you don't have to restart Tomcat between trials of model or prefilter changes.

## Script options reference

| Flag | Effect |
|---|---|
| `--reset-kg` | Enable phase 3. Prompts unless `--yes` is also given. |
| `--skip-chunks` | Skip phase 1 |
| `--skip-embeddings` | Skip phase 2 |
| `--skip-extract` | Skip phase 4 |
| `--dry-run` | Print the plan, exit 0 |
| `--yes` / `-y` | Skip the `--reset-kg` confirmation prompt |
| `--help` / `-h` | Print this script's header comment and exit |
| `--` | Everything after this is forwarded to `bin/kg-extract.sh` |

Environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `TOMCAT_URL` | `http://localhost:8080` | Where the script POSTs the rebuild triggers |
| `POLL_SECONDS` | `10` | Status-poll cadence during phases 1 and 2 |

## Troubleshooting

**"unreachable — is Tomcat running and credentials valid?"** — confirm Tomcat is up (`tomcat/tomcat-11/bin/startup.sh`) and that `test.properties` carries the `testbot` admin credentials. The script can't proceed without a valid admin session for the `/admin/content/*` endpoints.

**Phase 1 returns 409** — a previous rebuild is still running. The script will wait for it to finish and proceed; if it doesn't, check the snapshot for an `ERROR` state and look at `errors[]` in the JSON for per-page failures.

**Phase 2 returns 503** — hybrid retrieval is disabled. The script skips phase 2 with a warning and continues. To re-enable, set `wikantik.search.hybrid.enabled=true` and restart Tomcat.

**Phase 4 progresses but proposals/mentions stay at zero** — confirm `wikantik.knowledge.extractor.backend=ollama` (or `claude`) is set and the configured Ollama model is loaded on the inference host. Run `bin/kg-extract.sh --stats-only` to confirm the prefilter isn't dropping every chunk.

**The run aborts mid-phase-4 and you want to resume** — re-run with `--skip-chunks --skip-embeddings`. Without `--force` the extractor will upsert mentions on the existing primary key, so chunks that already produced mentions are effectively re-runs against the same node IDs (cheap, idempotent if the model output is stable).

## Extractor CLI pass-through

For the complete extractor CLI flag list, run `bin/kg-extract.sh --help` directly. Every flag is forwarded by `bin/kg-rebuild.sh` after the optional `--` separator (or as the first unrecognised arg). The most useful ones for experimentation:

- `--ollama-model <tag>` and `--ollama-url <url>` — model selection
- `--concurrency <1..10>` — number of in-flight LLM calls
- `--prefilter` / `--prefilter-dry-run` — enable filter / preview only
- `--prefilter-min-tokens <N>` / `--no-prefilter-skip-*` — tune predicates
- `--max-pages <N>` — limit to first N pages (alphabetical) for smoke tests
- `--force` — clear prior mentions per chunk before re-extracting
- `--stats-only` / `--chunker-stats-only` — non-destructive previews that exit before doing real work
