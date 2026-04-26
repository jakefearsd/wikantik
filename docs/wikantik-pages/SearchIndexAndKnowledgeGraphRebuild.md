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

# Nuclear option: PURGE every KG and hub table including human-authored
# content. Prompts you to type "PURGE" exactly to confirm. Use when
# starting fresh for a schema-design experiment or comparing extractor
# models without ANY prior bias.
bin/kg-rebuild.sh --purge-kg -- \
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

#### Chunker tuning notes

The chunker has two configurable knobs but they're not equally useful in practice. Empirical sweep against the 979-page corpus (using `bin/kg-extract.sh --chunker-stats-only`):

| `merge_forward_tokens` | Total chunks | Mean tokens | p90 |
|---|---|---|---|
| 150 (default) | 21,406 | 180 | 276 |
| 250 | 18,843 | 204 | 338 |
| 350 | 17,535 | 219 | 416 |

`max_tokens` (the ceiling) barely moves the needle on this corpus — sweeping 512 → 1024 → 2048 gives nearly identical numbers because no prose blocks are large enough to bump the ceiling. The lever that works is `merge_forward_tokens`: each +100 of merge floor cuts chunk count by ~12%, which directly translates to ~12% fewer LLM calls in phase 4.

**Atomic blocks bypass both knobs.** Fenced code, lists, and tables emit as their own chunks regardless of size — even a 30-token code fence becomes its own chunk. That's why the prefilter's `pure_code` rule exists: it removes the small atomic chunks the chunker can't merge away.

Use `bin/kg-extract.sh --chunker-stats-only --chunker-merge-forward-tokens N` to sweep candidate values in seconds before committing to a phase 1 rebuild.

### Phase 2 — Chunk embedding reindex

Triggered by `POST /admin/content/reindex-embeddings`. Polls the same status endpoint for `embeddings.bootstrap.state`. Terminal states: `COMPLETED`, `SKIPPED_ALREADY_POPULATED`, `SKIPPED_NO_CHUNKS` (treated as success), `FAILED`, `DISABLED` (treated as fatal).

What it does:

- Walks every row in `kg_content_chunks`.
- Calls the configured embedder (default Ollama `bge-m3`) for each chunk's text.
- Writes vectors to `kg_content_chunk_embeddings`.

Typical wall time: 5-15 minutes for ~17-22K chunks against a local Ollama embedder.

If hybrid retrieval is disabled (`wikantik.search.hybrid.enabled=false`), the script logs a warning and skips this phase rather than failing. That makes it safe to use the same script in a deploy where hybrid is intentionally off.

### Phase 3 — Knowledge graph reset or purge *(opt-in)*

Skipped by default. Enable with **either** `--reset-kg` (selective prune) or `--purge-kg` (full destructive wipe). The two are mutually exclusive — if both are passed, purge wins and the script logs the override.

#### `--reset-kg` — selective prune

Prompts for `[y/N]` confirmation unless `--yes` is also passed.

What it does:

```sql
DELETE FROM kg_proposals WHERE status='pending';
DELETE FROM kg_nodes     WHERE provenance='ai-inferred';
```

The `ai-inferred` deletion cascades to `kg_edges` and (already-empty post-phase-1) `chunk_entity_mentions`. Two provenance classes are preserved:

- `human-authored` — manually curated entities and edges.
- `ai-reviewed` — AI-proposed entities that a human has approved (status moves through `kg_proposals` and the surviving rows get re-tagged).

Use the reset variant when you want to compare two extraction runs against a clean automation baseline while keeping your manual curation work intact.

#### `--purge-kg` — full destructive wipe

Prompts you to **type `PURGE` (uppercase, exact)** before proceeding. `--yes` bypasses the prompt for scripted workflows; use carefully.

What it does — single TRUNCATE across the entire KG layer:

```sql
TRUNCATE TABLE
    kg_nodes, kg_edges,
    kg_proposals, kg_rejections,
    kg_embeddings, kg_content_embeddings,
    chunk_entity_mentions,
    hub_centroids, hub_proposals, hub_discovery_proposals
RESTART IDENTITY CASCADE;
```

**This destroys human-authored content too.** Use it when you genuinely want a from-scratch KG — fresh schema design experiments, comparing extractor models without any prior bias from earlier runs, or recovering from a contaminated graph state where it's faster to start over than to clean up.

Both reset and purge print row counts before and after so you can see exactly what got removed.

What survives a `--purge-kg`:

- The wiki page markdown on disk (untouched).
- All page metadata: frontmatter, canonical IDs, the structural spine.
- Chunks (`kg_content_chunks`) and chunk embeddings (`kg_content_chunk_embeddings`) — unless phase 1 also runs, which it does in a default `bin/kg-rebuild.sh --purge-kg` invocation.
- User accounts, groups, ACLs, API keys, retrieval-quality history, page verification metadata.

After a purge, phase 4 will re-populate `kg_nodes`, `kg_proposals`, and `chunk_entity_mentions` from extraction. Hub clustering and structural-KG embeddings (`kg_embeddings`, `hub_centroids`) need to be rebuilt by their respective subsystems separately — typically the next page-save cycle or an explicit hub-discovery run.

### Phase 4 — Entity extraction

Forwards to `bin/kg-extract.sh` with whatever args you passed to `bin/kg-rebuild.sh`. The extractor walks every chunk, runs the configured prefilter, calls the LLM for survivors, parses the JSON response, and writes mentions and proposals.

This is the only phase that takes hours rather than minutes (depending on model size, concurrency, prefilter aggressiveness, and host load).

See `bin/kg-extract.sh --help` for the complete flag list. The most common knobs:

| Flag | Purpose |
|---|---|
| `--ollama-model <tag>` | Switch model without touching config |
| `--concurrency <N>` | 1-10; raise for small models on a fast GPU. Cap was 4 prior to commit `965da952b` and was raised because small (1-3B) models leave plenty of GPU headroom. |
| `--prefilter` | Enable the chunk prefilter |
| `--prefilter-min-tokens <N>` | Drop chunks below N estimated tokens (default 20) |
| `--force` | Clear prior mentions per chunk before re-extracting |
| `--max-pages <N>` | Cap to first N pages — for smoke tests |

#### The prefilter

Three predicates, evaluated in this order so the most-specific reason for skipping is the one reported:

1. **`pure_code`** — chunk text is a single fenced code block with no surrounding prose. Regex: `(?s)\A\s*```[^\n]*\n.*?\n```\s*\z`. Wipes the small atomic code chunks the chunker can't merge into surrounding prose. On a typical technical-content corpus this catches ~7% of chunks on its own.

2. **`no_proper_noun`** — chunk text contains no token matching `\b\w*[A-Z]\w{2,}\b`. The leading `\w*` is what makes mixed-case names like `iPad`, `gRPC`, `eBay` match. Excludes pure-lowercase identifiers (`kubectl`, `npm`) and short caps like `Of` / `On`. Catches ~0.4% on a technical corpus.

3. **`too_short`** — chunk's estimated token count (chars / 4) is below `--prefilter-min-tokens` (default 20). Tiny chunks rarely give the LLM enough context. Catches ~0.5% at the default threshold; raising to 50-100 finds where the curve bends.

**Per-rule sub-flags** let you isolate behaviour: `--no-prefilter-skip-code`, `--no-prefilter-skip-nopn`, `--no-prefilter-skip-short`. All require `--prefilter` (or `--stats-only` / `--chunker-stats-only`) to be meaningful.

**Dry-run** (`--prefilter-dry-run`) evaluates the predicates but never actually skips — chunks still go to the extractor, but the would-skip reason is logged. Useful when you want to validate predicate behaviour against a representative slice of pages.

#### Model selection and lineage

The `extractor` column on `chunk_entity_mentions` carries the **model tag**, not the backend code. As of commit `3f9c7b672`, `OllamaEntityExtractor.code()` returns the configured `ollama.model` value with `:latest` stripped. So:

- A run with `--ollama-model gemma4-assist:latest` writes `extractor='gemma4-assist'`
- A run with `--ollama-model qwen2.5:1.5b-instruct` writes `extractor='qwen2.5:1.5b-instruct'`

This means you can A/B compare two models against overlapping page sets and tell their mentions apart at SQL inspection time. See [Inspection queries](#inspection-queries) below for example queries that exploit this.

Pre-existing rows tagged with the literal `'ollama'` (from before the lineage change) are left as-is. To backfill them to a known historical model, run a one-shot UPDATE — but per project convention, that goes in psql by hand, not in a Vxxx migration.

## Non-destructive stats modes

`bin/kg-extract.sh` has two modes that walk data and report without writing anything. Both exit 0 on completion. Use them in tight tuning loops where a real run would be wasteful.

#### `--stats-only` — prefilter sizing

Walks every chunk in `kg_content_chunks`, evaluates the configured prefilter, and prints reason-by-reason skip counts. Requires the database to be reachable. Does not call the LLM.

```bash
# Default-thresholds preview against the live corpus
bin/kg-extract.sh --stats-only

# Tighter floor — see how many more chunks get pruned at min=50
bin/kg-extract.sh --stats-only --prefilter-min-tokens 50
```

Output:

```
Stats-only: total=22209 kept=20502 skipped=1707 (7.7%) in 14667ms
  reason=no_proper_noun count=78 (0.4%)
  reason=pure_code count=1528 (6.9%)
  reason=too_short count=101 (0.5%)
```

Wall time: ~15 seconds for ~22K chunks.

#### `--chunker-stats-only` — chunker sweeps

Reads markdown directly from `--pages-dir` (default `docs/wikantik-pages`), runs `ContentChunker` in memory at the supplied `--chunker-*` overrides, and prints the chunk-size distribution + the prefilter's effect on the new chunks. **Does not need Tomcat or the database.**

```bash
# Default config
bin/kg-extract.sh --chunker-stats-only

# Sweep merge_forward_tokens to find the right floor
bin/kg-extract.sh --chunker-stats-only --chunker-merge-forward-tokens 300
bin/kg-extract.sh --chunker-stats-only --chunker-merge-forward-tokens 500
```

Output:

```
Chunker-stats: pages=979 chunks=21406 elapsedMs=1399
Tokens per chunk: min=1 mean=180 p50=162 p90=276 p99=462 max=250000
Distribution:
    0-50  : 1375 (6.4%)
   51-150 : 7414 (34.6%)
  151-300 : 11088 (51.8%)
  301-500 : 1435 (6.7%)
  501-1k  : 89 (0.4%)
  1001+   : 5 (0.0%)
Prefilter on these chunks: kept=19729 skipped=1677 (7.8%)
```

Wall time: ~1.5 seconds for the 979-page corpus.

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

-- 3a. Model-by-model A/B comparison: how many distinct nodes did each
--     model touch on the same chunks? (Higher recall ≠ better — could
--     just mean the model is more verbose.)
SELECT extractor, COUNT(DISTINCT chunk_id) AS chunks_with_mentions,
       COUNT(DISTINCT node_id) AS distinct_nodes, COUNT(*) AS total_mentions
  FROM chunk_entity_mentions
 GROUP BY extractor ORDER BY extractor;

-- 3b. Mentions emitted by one model but not another for the same chunk —
--     useful for spot-checking quality differences. Replace the literals
--     with two model tags actually present in your data.
SELECT chunk_id,
       array_agg(DISTINCT extractor ORDER BY extractor) AS extractors,
       COUNT(DISTINCT node_id) FILTER (WHERE extractor='gemma4-assist') AS gemma_nodes,
       COUNT(DISTINCT node_id) FILTER (WHERE extractor='qwen2.5:1.5b-instruct') AS qwen_nodes
  FROM chunk_entity_mentions
 WHERE extractor IN ('gemma4-assist','qwen2.5:1.5b-instruct')
 GROUP BY chunk_id
HAVING COUNT(DISTINCT extractor) > 1
   AND COUNT(DISTINCT node_id) FILTER (WHERE extractor='gemma4-assist')
       <> COUNT(DISTINCT node_id) FILTER (WHERE extractor='qwen2.5:1.5b-instruct')
 ORDER BY ABS(COUNT(DISTINCT node_id) FILTER (WHERE extractor='gemma4-assist')
            - COUNT(DISTINCT node_id) FILTER (WHERE extractor='qwen2.5:1.5b-instruct')) DESC
 LIMIT 20;

-- 3c. Extraction lineage timeline — when did each model get used?
SELECT extractor, MIN(extracted_at) AS first_seen, MAX(extracted_at) AS last_seen,
       COUNT(*) AS mentions
  FROM chunk_entity_mentions GROUP BY extractor ORDER BY first_seen;

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
| **Chunker** | | |
| `wikantik.chunker.max_tokens` | `512` | Phase 1: hard ceiling on a non-atomic chunk |
| `wikantik.chunker.merge_forward_tokens` | `150` | Phase 1: floor below which a chunk merges into the next section |
| **Embedding** | | |
| `wikantik.search.hybrid.enabled` | (deploy-specific) | Phase 2: false skips embedding reindex with a warning |
| **Extractor backend** | | |
| `wikantik.knowledge.extractor.backend` | `disabled` | Phase 4: `ollama` / `claude` / `disabled` |
| `wikantik.knowledge.extractor.ollama.model` | `gemma4-assist:latest` | Phase 4: Ollama model tag (`:latest` is stripped before being recorded in the `extractor` column) |
| `wikantik.knowledge.extractor.ollama.base_url` | `http://inference.jakefear.com:11434` | Phase 4: Ollama HTTP endpoint |
| `wikantik.knowledge.extractor.claude.model` | `claude-haiku-4-5` | Phase 4: Anthropic model id when backend=`claude` |
| `wikantik.knowledge.extractor.timeout_ms` | `120000` | Phase 4: per-chunk LLM call timeout |
| `wikantik.knowledge.extractor.confidence_threshold` | `0.6` | Phase 4: proposals below this are dropped, not filed |
| `wikantik.knowledge.extractor.max_existing_nodes` | `200` | Phase 4: cap on existing-nodes dictionary included in the prompt |
| `wikantik.knowledge.extractor.concurrency` | `2` | Phase 4: in-flight LLM calls. Hard-clamped to `[1, 10]` (was 4 prior to commit `965da952b`). |
| `wikantik.knowledge.extractor.per_page_min_interval_ms` | `5000` | Save-time path only: rate limit between extractions of the same page on rapid edits |
| **Prefilter** | | |
| `wikantik.knowledge.extractor.prefilter.enabled` | `false` | Phase 4: master switch for the chunk prefilter |
| `wikantik.knowledge.extractor.prefilter.dry_run` | `false` | Phase 4: log decisions only — no chunks actually skipped |
| `wikantik.knowledge.extractor.prefilter.skip_pure_code` | `true` | Phase 4: enable the pure-code-block predicate |
| `wikantik.knowledge.extractor.prefilter.skip_no_proper_noun` | `true` | Phase 4: enable the proper-noun-absence predicate |
| `wikantik.knowledge.extractor.prefilter.skip_too_short` | `true` | Phase 4: enable the too-short predicate |
| `wikantik.knowledge.extractor.prefilter.min_tokens` | `20` | Phase 4: too-short threshold (chars/4 estimate) |

CLI flags on `bin/kg-extract.sh` override these properties for a single run, which is what makes the experiment loop fast — you don't have to restart Tomcat between trials of model or prefilter changes.

## Script options reference

| Flag | Effect |
|---|---|
| `--reset-kg` | Enable phase 3 in **selective-prune mode** — deletes pending proposals and `ai-inferred` nodes, preserves `human-authored` and `ai-reviewed`. `[y/N]` prompt unless `--yes`. |
| `--purge-kg` | Enable phase 3 in **full-purge mode** — TRUNCATEs every kg_*/hub_* table including human-authored content. Requires typing `PURGE` exactly to confirm; `--yes` bypasses. Wins over `--reset-kg` if both passed. |
| `--skip-chunks` | Skip phase 1 |
| `--skip-embeddings` | Skip phase 2 |
| `--skip-extract` | Skip phase 4 |
| `--dry-run` | Print the plan, exit 0 |
| `--yes` / `-y` | Skip the `--reset-kg` `[y/N]` prompt and the `--purge-kg` "type PURGE" prompt. Use only in scripted contexts you trust. |
| `--help` / `-h` | Print this script's header comment and exit |
| `--` | Everything after this is forwarded to `bin/kg-extract.sh` |

Environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `TOMCAT_URL` | `http://localhost:8080` | Where the script POSTs the rebuild triggers |
| `POLL_SECONDS` | `10` | Status-poll cadence during phases 1 and 2 |
| `PROGRESS_SECONDS` | `30` | How often a periodic progress line (count + rate + ETA) is emitted while a phase is in `RUNNING` state. Decoupled from `POLL_SECONDS` so state transitions still surface promptly. |

### Periodic progress feedback

While phases 1 and 2 are in their `RUNNING` states, the script emits a periodic progress line every `PROGRESS_SECONDS` (30 by default) so a long-running phase doesn't go silent between state transitions:

```
[kg-rebuild] embedding reindex: state=RUNNING (chunks 0 / 21367)
[kg-rebuild] embedding reindex: 1247 / 21367 (5.8%) — rate=41 chunks/s, ETA 0h08m
[kg-rebuild] embedding reindex: 2533 / 21367 (11.9%) — rate=42 chunks/s, ETA 0h07m
…
[kg-rebuild] embedding reindex: state=COMPLETED (chunks 21367 / 21367)
```

The rate / ETA tail is suppressed early in a run (when no work has happened yet) and at completion (when there's nothing left to estimate), so you won't see misleading `0/s, ETA 0h00m` noise. To get more frequent updates: `PROGRESS_SECONDS=10 bin/kg-rebuild.sh …`.

Phase 4's per-chunk progress comes from `bin/kg-extract.sh`'s own log4j2 lines, not the orchestrator. The CLI logs an `Extract-CLI progress:` summary every `--poll-seconds` (default 30) plus per-chunk INFO lines from the indexer.

## Troubleshooting

**"unreachable — is Tomcat running and credentials valid?"** — confirm Tomcat is up (`tomcat/tomcat-11/bin/startup.sh`) and that `test.properties` carries the `testbot` admin credentials. The script can't proceed without a valid admin session for the `/admin/content/*` endpoints.

**Phase 1 returns 409** — a previous rebuild is still running. The script will wait for it to finish and proceed; if it doesn't, check the snapshot for an `ERROR` state and look at `errors[]` in the JSON for per-page failures.

**Phase 2 returns 503** — hybrid retrieval is disabled. The script skips phase 2 with a warning and continues. To re-enable, set `wikantik.search.hybrid.enabled=true` and restart Tomcat.

**Phase 4 progresses but proposals/mentions stay at zero** — confirm `wikantik.knowledge.extractor.backend=ollama` (or `claude`) is set and the configured Ollama model is loaded on the inference host. Run `bin/kg-extract.sh --stats-only` to confirm the prefilter isn't dropping every chunk.

**The run aborts mid-phase-4 and you want to resume** — re-run with `--skip-chunks --skip-embeddings`. Without `--force` the extractor will upsert mentions on the existing primary key, so chunks that already produced mentions are effectively re-runs against the same node IDs (cheap, idempotent if the model output is stable).

**Phase 3 `--purge-kg` confirmation rejected even though I typed yes** — the purge specifically requires `PURGE` (uppercase, exact). It's deliberately stricter than the `[y/N]` of `--reset-kg` so you don't fat-finger your way into a destructive run. Use `--yes` to bypass entirely in scripted contexts.

**`--no-prefilter-skip-*` or `--prefilter-min-tokens` rejected as "no effect"** — the orphan-flag guard requires you to also pass `--prefilter`, `--prefilter-dry-run`, `--stats-only`, or `--chunker-stats-only` so the sub-flag isn't silently ignored. Add the master switch.

**Embedding phase shows `state=DISABLED`** — `wikantik.search.hybrid.enabled=false` in your config. The rebuild script logs a warning and skips the phase rather than failing. To re-enable, flip the property and restart Tomcat.

**Hub clustering / `kg_embeddings` table empty after `--purge-kg`** — by design. The KG-embedding and hub-clustering subsystems write their own data; phase 4's extraction doesn't repopulate them. They'll fill back up on the next page-save event or when the hub-discovery service runs.

## Extractor CLI flag reference

`bin/kg-extract.sh` has its own complete `--help`. Every flag is also forwarded by `bin/kg-rebuild.sh` after the optional `--` separator (or as the first unrecognised arg). Grouped by purpose:

**Database connection** *(skipped automatically when `--chunker-stats-only` is set)*

- `--jdbc-url <url>` — default `jdbc:postgresql://localhost:5432/jspwiki`
- `--jdbc-user <name>` — default `jspwiki`
- `--jdbc-password <value>` — literal (not recommended; appears in process listing)
- `--jdbc-password-env <VAR>` — read password from env var (preferred; `bin/kg-extract.sh` itself uses this internally with `WIKANTIK_EXTRACT_PG_PASSWORD`)

**Extractor backend selection**

- `--backend ollama|claude|disabled` — default `ollama`
- `--ollama-url <url>` — default `http://inference.jakefear.com:11434`
- `--ollama-model <tag>` — default `gemma4-assist:latest`. Drives the `extractor` column lineage.
- `--claude-model <id>` — default `claude-haiku-4-5` (Claude only)
- `--anthropic-key-env <VAR>` — env var holding `ANTHROPIC_API_KEY` (Claude only)

**Run tuning**

- `--concurrency <1..10>` — default 2; clamped to band. Hard-capped at 10 since commit `965da952b` (was 4 before).
- `--confidence-threshold <0..1>` — default 0.6
- `--max-existing-nodes <N>` — default 200 (cap on the prompt's "known entities" dictionary)
- `--timeout-ms <ms>` — default 120000
- `--force` — clear prior mentions per chunk before re-extracting
- `--max-pages <N>` — cap to first N pages alphabetically; 0 = unlimited
- `--poll-seconds <N>` — progress-log cadence (default 30)

**Prefilter** *(opt-in)*

- `--prefilter` — enable
- `--prefilter-dry-run` — log decisions, never actually skip
- `--no-prefilter-skip-code` — disable pure-code-block predicate
- `--no-prefilter-skip-nopn` — disable proper-noun-absence predicate
- `--no-prefilter-skip-short` — disable too-short predicate
- `--prefilter-min-tokens <N>` — too-short threshold (default 20)

**Non-destructive stats modes** *(exit 0 before doing real work)*

- `--stats-only` — walk live chunks, evaluate prefilter, print skip counts. Needs DB.
- `--chunker-stats-only` — read pages from disk, re-chunk in memory at the supplied overrides, print distribution + prefilter effect. Does not need DB or Tomcat.
- `--chunker-max-tokens <N>` — chunker ceiling override (default 512); only meaningful with `--chunker-stats-only`
- `--chunker-merge-forward-tokens <N>` — chunker floor override (default 150); only meaningful with `--chunker-stats-only`
- `--pages-dir <path>` — markdown source root for `--chunker-stats-only` (default `docs/wikantik-pages`)

**Misc**

- `-h`, `--help` — show the canonical help and exit
