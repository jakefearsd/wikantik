---
title: Search Index and Knowledge Graph Rebuild
date: '2026-04-26'
cluster: wikantik-development
status: active
type: article
canonical_id: 01KQE8VJHXHV6AGEBV8934FX6F
audience:
- humans
related:
- WikantikKnowledgeGraphAdmin
summary: Operator runbook for the four-phase end-to-end rebuild of chunks, Lucene,
  embeddings, and the entity-extraction layer. Covers the bin/kg-rebuild.sh...
tags:
- administration
- operations
- search
- knowledge-graph
- embeddings
- chunking
- entity-extraction
---
# Search Index and Knowledge Graph Rebuild

This page documents the operator runbook for rebuilding every derived index Wikantik maintains over its page corpus. Use it when:

- The chunker config has changed and existing chunks need to be regenerated.
- The embedding model has changed and existing vectors no longer match the runtime embedder.
- A new entity-extraction model needs to be evaluated against the whole corpus.
- The knowledge graph has accumulated noise from earlier extraction runs and you want a clean slate.
- You're sweeping chunker / extractor-model parameters and need a reproducible reset between trials.

The rebuild is implemented as a four-phase pipeline orchestrated by `bin/kg-rebuild.sh`. Each phase is independently skippable, so a typical experiment iteration only re-runs the cheap phases — you don't re-chunk every time you swap an extractor model.

`bin/kg-rebuild.sh` and `bin/kg-extract.sh` are local-development tools. They log in with the `testbot` admin account from `test.properties`, read the database credentials from the local `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`, connect with `psql` to `localhost`, and need `curl`, `jq`, `java` and `psql` on `PATH` plus a running Tomcat (`TOMCAT_URL`, default `http://localhost:8080`). Against a remote host, call the underlying admin endpoints yourself (`POST /admin/content/rebuild-indexes`, `POST /admin/content/reindex-embeddings`, `POST /admin/knowledge-graph/extract-mentions`). For a Lucene-only rebuild see `bin/trigger-rebuild-indexes.sh` and `docs/admin/IndexRebuild.md` in the repository; the operator handbook is [Wikantik Operations](WikantikOperations).

## What gets rebuilt

The pipeline touches four derived data layers, in order:

1. **Chunks** (`kg_content_chunks`) and **Lucene full-text index** — wiped and rebuilt from page markdown on disk by `ContentIndexRebuildService`.
2. **Chunk embeddings** (`content_chunk_embeddings`) — wiped (via `ON DELETE CASCADE` from phase 1) and repopulated by `BootstrapEmbeddingIndexer` against the configured embedder model.
3. **Knowledge graph** (`kg_nodes`, `kg_edges`, `kg_proposals`) — *optional, opt-in only*. Removes pending proposals and AI-inferred nodes; keeps `human-authored` and `ai-reviewed` rows. Skipped by default.
4. **Proposals and entity mentions** (`kg_proposals`, `chunk_entity_mentions`) — repopulated by `bin/kg-extract.sh`, which runs the per-page entity-extraction pipeline: one LLM extraction call per page, consolidated into proposals, then mention attribution for accepted names that already exist as Knowledge Graph nodes.

Source-of-truth markdown in `docs/wikantik-pages/` is never touched. Page metadata (frontmatter, canonical IDs, structural spine) is preserved.

### Cascade behaviour worth knowing

Because `chunk_entity_mentions.chunk_id` and `content_chunk_embeddings.chunk_id` both reference `kg_content_chunks(id)` with `ON DELETE CASCADE`, **phase 1 implicitly wipes both downstream tables**. Phase 2 fully repopulates embeddings, and phase 4 re-attributes mentions, so the cascade is a feature: you can't end up with stale rows pointing at chunks that no longer exist. (If you run phase 1 with `--skip-extract`, mentions stay empty until a later phase 4 run.)

## Sample commands

A starter set covering the workflows you'll use most. Anything after `--` (or any unrecognised arg) is forwarded verbatim to `bin/kg-extract.sh`; an argument that script does not know makes it exit with `unknown argument` (see the [extractor CLI flag reference](#extractor-cli-flag-reference)).

### Plan and preview before committing

```bash
# Print the four-phase plan, exit 0. Touches nothing.
bin/kg-rebuild.sh --dry-run -- \
    --ollama-model qwen2.5:1.5b-instruct --concurrency 4

# Walk every page on disk, run the chunker in memory at the given config,
# print the size distribution. A couple of seconds. No DB, no Tomcat, no LLM.
bin/kg-chunker-stats.sh --chunker-merge-forward-tokens 300
```

### End-to-end rebuilds

```bash
# Vanilla full rebuild using whatever's in wikantik-custom.properties
# for chunker and embedder, and the extractor CLI's own defaults.
# Useful as a "reset to defaults".
bin/kg-rebuild.sh

# Experiment iteration: small fast model, concurrency 6.
bin/kg-rebuild.sh -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6

# Tabula rasa: also wipes pending proposals and ai-inferred KG nodes
# before re-extracting. Use when comparing two models on the same pages
# and you want a guaranteed clean baseline.
bin/kg-rebuild.sh --reset-kg -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6

# Nuclear option: PURGE every KG and hub table including human-authored
# content. Prompts you to type "PURGE" exactly to confirm. Use when
# starting fresh for a schema-design experiment or comparing extractor
# models without ANY prior bias.
bin/kg-rebuild.sh --purge-kg -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6
```

### Inner-loop iteration *(after the first full rebuild)*

```bash
# Just re-extract: the chunks and embeddings from the previous run are
# still good. Cheapest way to A/B two extractor models.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6

# Same, but with a clean KG between trials so mentions/proposals from
# the prior run don't muddy the comparison.
bin/kg-rebuild.sh --reset-kg --skip-chunks --skip-embeddings -- \
    --ollama-model qwen3:8b \
    --concurrency 4
```

### Smoke tests

```bash
# 5 pages end-to-end. Use after any structural change to confirm the
# whole pipeline still wires up before committing to a long run.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 4 \
    --max-pages 5

# Same but with a dry-run extraction: pages are extracted and consolidated,
# but nothing is upserted into kg_proposals.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 4 --dry-run \
    --max-pages 5
```

### Targeted rebuilds

```bash
# I changed wikantik.chunker.* and restarted Tomcat — re-chunk and
# re-embed but defer extraction until I've checked chunk shape.
bin/kg-rebuild.sh --skip-extract

# I changed the embedding model — re-embed only. Chunks stay; extraction
# does not depend on vectors, so it is unaffected.
bin/kg-rebuild.sh --skip-chunks --skip-extract

# I want to wipe the KG without re-extracting, e.g. before manual
# curation work. Phases 1, 2, 4 skipped; phase 3 runs alone.
bin/kg-rebuild.sh --reset-kg --skip-chunks --skip-embeddings --skip-extract
```

### Backwards-compatible (extractor only, no orchestration)

`bin/kg-extract.sh` is still callable directly when you don't need the chunker/embedder rebuild — for example, to extend an extraction run to pages that have no proposals yet.

```bash
# Run with the CLI defaults
bin/kg-extract.sh

# Override at the command line, no Tomcat dance needed; only pages with no
# kg_proposals rows yet
bin/kg-extract.sh --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6 --only-missing
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
  - `wikantik.chunker.fragment_floor_tokens` (default `24`)
  - `wikantik.chunker.overlap_tokens` (default `40`)
- Writes new chunks and queues the page for Lucene reindexing.

Typical wall time: ~1-2 minutes for ~1000 pages.

To change chunker behaviour: edit `tomcat/tomcat-11/lib/wikantik-custom.properties`, restart Tomcat, then run phase 1. The rebuild service uses whatever chunker was constructed at engine startup.

#### Chunker tuning notes

The chunker has four tuning knobs (`max_tokens`, `merge_forward_tokens`, `fragment_floor_tokens`, `overlap_tokens`) plus a `wikantik.chunker.enabled` kill-switch for save-time chunking, and they're not equally useful in practice. `fragment_floor_tokens` merges a sub-floor section forward so it adopts the next section's heading, and `overlap_tokens` prepends each chunk's tail to the next chunk under the same heading; changing any of them invalidates chunk content, so run a full rebuild afterwards. The sweep below is an April 2026 measurement of `merge_forward_tokens` against the 979-page corpus of that time (measured with the chunker-stats tool, now `bin/kg-chunker-stats.sh`); the two newer knobs shift the absolute numbers:

| `merge_forward_tokens` | Total chunks | Mean tokens | p90 |
|---|---|---|---|
| 150 (default) | 21,406 | 180 | 276 |
| 250 | 18,843 | 204 | 338 |
| 350 | 17,535 | 219 | 416 |

`max_tokens` (the ceiling) barely moves the needle on this corpus — sweeping 512 → 1024 → 2048 gives nearly identical numbers because no prose blocks are large enough to bump the ceiling. The lever that works is `merge_forward_tokens`: each +100 of merge floor cuts chunk count by ~12%, which directly translates to ~12% fewer chunks to embed in phase 2.

**Atomic blocks bypass the size knobs.** Fenced code, lists, and tables emit as their own chunks regardless of size — even a 30-token code fence becomes its own chunk. That's why the save-time extraction prefilter has a `pure_code` rule: it skips the small atomic chunks the chunker can't merge away.

Use `bin/kg-chunker-stats.sh --chunker-merge-forward-tokens N` to sweep candidate values in seconds before committing to a phase 1 rebuild.

### Phase 2 — Chunk embedding reindex

Triggered by `POST /admin/content/reindex-embeddings`. Polls the same status endpoint for `embeddings.bootstrap.state`. Terminal states: `COMPLETED`, `SKIPPED_ALREADY_POPULATED`, `SKIPPED_NO_CHUNKS` (treated as success), `FAILED`, `DISABLED` (treated as fatal).

What it does:

- Walks every row in `kg_content_chunks`.
- Calls the configured embedder (default Ollama `qwen3-embedding-0.6b`, 1024 dimensions; see `wikantik.search.embedding.*`) for each chunk's text.
- Writes vectors to `content_chunk_embeddings`.

Duration depends almost entirely on the embedder host: minutes against a local GPU, much longer on a CPU-only embedder (batch size and timeout are coupled; see the embedding backfill section of `docs/admin/IndexRebuild.md`).

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
    chunk_entity_mentions,
    hub_centroids, hub_proposals, hub_discovery_proposals
RESTART IDENTITY CASCADE;
```

**This destroys human-authored content too.** Use it when you genuinely want a from-scratch KG — fresh schema design experiments, comparing extractor models without any prior bias from earlier runs, or recovering from a contaminated graph state where it's faster to start over than to clean up.

Both reset and purge print row counts before and after so you can see exactly what got removed.

What survives a `--purge-kg`:

- The wiki page markdown on disk (untouched).
- All page metadata: frontmatter, canonical IDs, the structural spine.
- Chunks (`kg_content_chunks`) and chunk embeddings (`content_chunk_embeddings`) — unless phase 1 also runs, which it does in a default `bin/kg-rebuild.sh --purge-kg` invocation.
- User accounts, groups, ACLs, API keys, retrieval-quality history, page verification metadata.

After a purge, phase 4 will re-populate `kg_nodes`, `kg_proposals`, and `chunk_entity_mentions` from extraction. Hub clustering (`hub_centroids`) needs to be rebuilt by its subsystem separately — typically the next page-save cycle or an explicit hub-discovery run.

### Phase 4 — Entity extraction

Forwards to `bin/kg-extract.sh` with whatever args you passed to `bin/kg-rebuild.sh`. `kg-extract.sh` is a thin launcher for the standalone `wikantik-extract-cli` jar (built with Maven on demand when the jar is missing or stale). It takes the JDBC URL, user and password from the local `ROOT.xml` (falling back to `PG_JDBC_URL`, `PG_USER` and `PG_PASSWORD`) and runs the same per-page pipeline as the admin batch `POST /admin/knowledge-graph/extract-mentions`:

1. Warm the `kg_node_embeddings` cache.
2. For each page (concurrently across a worker pool), stitch the body from its chunks, look up a top-K dictionary of existing nodes, and make **one** LLM extraction call.
3. Consolidate the per-page results into one proposal per logical claim and, if a judge is enabled (`--judge`, default `none`), review each one.
4. Upsert the accepted proposals into `kg_proposals` (skipped with `--dry-run`).
5. For every accepted name that already maps to a `kg_nodes` row, attribute it to the chunks that mention it and write `chunk_entity_mentions`.

This is the only phase that takes hours rather than minutes (depending on model size, concurrency, and host load). See `bin/kg-extract.sh --help` for the launcher's header and `bin/kg-extract.sh --jar-help` for the CLI's complete flag list (reproduced in the [extractor CLI flag reference](#extractor-cli-flag-reference) below). The most common knobs:

| Flag | Purpose |
|---|---|
| `--ollama-model <tag>` | Extractor model (default `gemma4-assist:latest`) |
| `--concurrency <N>` | 1-6, default 2 (the CLI clamps at 6; the admin batch clamps at 10). Raise for small models on a fast GPU |
| `--max-pages <N>` | Cap to the first N pages — for smoke tests |
| `--page-pattern <glob>` | Limit to page names matching a glob (`*` and `?`) |
| `--only-missing` | Skip pages that already have `kg_proposals` rows |
| `--dry-run` | Run extraction but skip the `kg_proposals` upsert |
| `--judge none\|ollama\|claude` | Opt-in proposal review (default `none`: accept everything verbatim) |
| `--report <path>` | Write the final status as JSON when the run completes |

#### The save-time prefilter (not used by this phase)

Earlier, chunk-level extraction ran through a chunk prefilter controlled by `--prefilter`, `--force`, `--stats-only` and `--chunker-stats-only` on `kg-extract.sh`. Those flags are gone: `kg-extract.sh` rejects them as unknown arguments, and the per-page pipeline does not read the prefilter. The `wikantik.knowledge.extractor.prefilter.*` properties still exist, but only the **save-time** chunk extractor (the listener that runs when a page is saved) consults them. Three predicates, evaluated in this order so the most-specific reason for skipping is the one reported:

1. **`pure_code`** — chunk text is a single fenced code block with no surrounding prose.
2. **`no_proper_noun`** — chunk text contains no token matching `\b\w*[A-Z]\w{2,}\b` (the leading `\w*` lets mixed-case names like `iPad`, `gRPC`, `eBay` match; pure-lowercase identifiers like `kubectl` and short caps like `Of` do not).
3. **`too_short`** — chunk's estimated token count (chars / 4) is below `wikantik.knowledge.extractor.prefilter.min_tokens` (default 20).

It is off by default (`wikantik.knowledge.extractor.prefilter.enabled = false`); `prefilter.dry_run` logs what it would skip without skipping.

#### Model selection and lineage

The `extractor` column on `chunk_entity_mentions` records the extractor's code. For the per-page Ollama extractor that is `ollama:` plus the configured model tag with `:latest` stripped (for example `ollama:gemma4-assist`, `ollama:qwen2.5:1.5b-instruct`); the save-time chunk extractor writes the bare model tag instead (for example `gemma4-graph:12b`). So you can A/B compare two models against overlapping page sets and tell their mentions apart at SQL inspection time. See [Inspection queries](#inspection-queries) below.

Pre-existing rows tagged with the literal `'ollama'` (from before the lineage change) are left as-is. To backfill them to a known historical model, run a one-shot UPDATE — but per project convention, that goes in psql by hand, not in a Vxxx migration.

## Non-destructive stats mode

`bin/kg-chunker-stats.sh` (split out of `kg-extract.sh` when extraction moved to the per-page pipeline) walks data and reports without writing anything. It exits 0 on completion. Use it in tight tuning loops where a real run would be wasteful.

It reads markdown directly from `--pages-dir` (default `docs/wikantik-pages`), runs `ContentChunker` in memory at the supplied overrides, and prints the chunk count, the tokens-per-chunk distribution (min, mean, p50, p90, p99, max), a size histogram, and what the save-time prefilter would skip among the re-chunked chunks. **It does not need Tomcat, the database or an LLM.**

```bash
# Default config
bin/kg-chunker-stats.sh

# Sweep merge_forward_tokens to find the right floor
bin/kg-chunker-stats.sh --chunker-merge-forward-tokens 300
bin/kg-chunker-stats.sh --chunker-merge-forward-tokens 500
```

Options: `--pages-dir`, `--chunker-max-tokens`, `--chunker-merge-forward-tokens` (must be <= max tokens), `--chunker-fragment-floor-tokens` (must be <= merge-forward tokens) and `--chunker-overlap-tokens`. Exit codes: 0 stats printed, 1 bad `--pages-dir`, 2 bad arguments. The run takes a couple of seconds for a thousand pages.

## Skipping phases

Each phase has a `--skip-*` flag, useful when you've already done the expensive work and want to iterate on a downstream phase:

```bash
# I just changed the extractor model — re-extract only, keep chunks/embeddings.
bin/kg-rebuild.sh --skip-chunks --skip-embeddings -- \
    --ollama-model qwen2.5:1.5b-instruct --concurrency 6

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
    bin/kg-chunker-stats.sh --chunker-merge-forward-tokens 300
    ```

    Reads markdown from disk, runs the chunker in memory, prints the size distribution. Takes a couple of seconds. Does not require Tomcat or a DB.

2. **Decide on a chunker config**, edit `wikantik-custom.properties`, restart Tomcat, then commit to phase 1 + phase 2:

    ```bash
    bin/kg-rebuild.sh --skip-extract
    ```

3. **Sweep extractor models** by re-running phase 4 only:

    ```bash
    bin/kg-rebuild.sh --skip-chunks --skip-embeddings --reset-kg -- \
        --ollama-model qwen2.5:1.5b-instruct \
        --concurrency 6 --max-pages 20
    ```

    `--reset-kg` between trials gives you a clean slate; `--max-pages 20` keeps each trial short.

4. **When satisfied**, drop `--max-pages` and run for real.

## Inspection queries

After a run, these psql snippets confirm the pipeline produced sane data. Pull credentials the same way the rebuild script does (the database and role names below are the defaults; use the ones in your `ROOT.xml`):

```bash
PGPASS=$(grep -oE 'password="[^"]+"' tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml \
         | head -1 | sed -E 's/password="([^"]+)"/\1/')
PSQL="env PGPASSWORD=$PGPASS psql -h localhost -U wikantik wikantik"
```

```sql
-- 1. Chunks per page (post-phase-1 sanity)
SELECT page_name, COUNT(*) FROM kg_content_chunks GROUP BY page_name
 ORDER BY COUNT(*) DESC LIMIT 20;

-- 2. Embedding coverage (post-phase-2 sanity)
SELECT COUNT(*) AS chunks, (SELECT COUNT(*) FROM content_chunk_embeddings) AS embeddings
  FROM kg_content_chunks;

-- 3. Mentions per extractor (post-phase-4: distinguishes models because
--    the extractor code carries the model tag: ollama:<model> for the per-page pipeline)
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

-- 5. Pages with zero mentions (no accepted name mapped to an existing node)
SELECT cc.page_name, COUNT(cc.id) AS chunks,
       COUNT(m.chunk_id) AS mentions
  FROM kg_content_chunks cc
  LEFT JOIN chunk_entity_mentions m ON m.chunk_id = cc.id
 GROUP BY cc.page_name HAVING COUNT(m.chunk_id) = 0
 ORDER BY 2 DESC LIMIT 20;
```

## Configuration reference

Properties read at engine startup, set in `tomcat/tomcat-11/lib/wikantik-custom.properties`. Restart Tomcat after editing. Defaults are those of `wikantik-main/src/main/resources/ini/wikantik.properties` (the generated [Wikantik Configuration Reference](WikantikConfigurationReference) is authoritative).

| Property | Default | Affects |
|---|---|---|
| **Chunker** | | |
| `wikantik.chunker.max_tokens` | `512` | Phase 1: hard ceiling on a non-atomic chunk |
| `wikantik.chunker.merge_forward_tokens` | `150` | Phase 1: floor below which a chunk merges into the next section |
| `wikantik.chunker.fragment_floor_tokens` | `24` | Phase 1: a whole section below this merges forward and adopts the next heading (must be <= `merge_forward_tokens`; 0 disables) |
| `wikantik.chunker.overlap_tokens` | `40` | Phase 1: tail of each chunk is prepended to the next chunk under the same heading (0 disables) |
| `wikantik.chunker.enabled` | `true` | Kill-switch for save-time chunking |
| **Embedding** | | |
| `wikantik.search.hybrid.enabled` | `true` | Phase 2: false skips embedding reindex with a warning |
| `wikantik.search.embedding.model` | `qwen3-embedding-0.6b` | Phase 2: embedder model (the dense index is fixed at its dimension) |
| **Extractor backend (server side: save-time extractor and the admin batch)** | | |
| `wikantik.knowledge.extractor.backend` | `ollama` | `ollama` / `claude` / `disabled` |
| `wikantik.knowledge.extractor.ollama.model` | `gemma4-graph:12b` | Ollama model tag (`:latest` is stripped before being recorded in the `extractor` column); `bin/kg-extract.sh` does not read it and defaults to `gemma4-assist:latest` |
| `wikantik.knowledge.extractor.ollama.base_url` | `http://inference.jakefear.com:11434` | Ollama HTTP endpoint (the bundled CPU embedder in the container image has no chat model) |
| `wikantik.knowledge.extractor.claude.model` | `claude-haiku-4-5` | Anthropic model id when backend=`claude` |
| `wikantik.knowledge.extractor.timeout_ms` | `120000` | Per-call LLM timeout |
| `wikantik.knowledge.extractor.confidence_threshold` | `0.6` | Proposals below this are dropped, not filed |
| `wikantik.knowledge.extractor.max_existing_nodes` | `200` | Cap on the existing-nodes dictionary included in the prompt |
| `wikantik.knowledge.extractor.concurrency` | `2` | In-flight LLM calls for the admin batch. Hard-clamped to `[1, 10]` |
| `wikantik.knowledge.extractor.per_page_min_interval_ms` | `5000` | Save-time path only: rate limit between extractions of the same page on rapid edits |
| **Save-time prefilter (the batch does not use it)** | | |
| `wikantik.knowledge.extractor.prefilter.enabled` | `false` | Master switch for the chunk prefilter |
| `wikantik.knowledge.extractor.prefilter.dry_run` | `false` | Log decisions only — no chunks actually skipped |
| `wikantik.knowledge.extractor.prefilter.skip_pure_code` | `true` | Enable the pure-code-block predicate |
| `wikantik.knowledge.extractor.prefilter.skip_no_proper_noun` | `true` | Enable the proper-noun-absence predicate |
| `wikantik.knowledge.extractor.prefilter.skip_too_short` | `true` | Enable the too-short predicate |
| `wikantik.knowledge.extractor.prefilter.min_tokens` | `20` | Too-short threshold (chars/4 estimate) |

CLI flags on `bin/kg-extract.sh` override these settings for a single run, which is what makes the experiment loop fast — you don't have to restart Tomcat between trials of model changes.

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
| `--` | Everything after this is forwarded to `bin/kg-extract.sh` (so every forwarded flag must be one the CLI knows) |

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

Phase 4's progress comes from `bin/kg-extract.sh`'s own log4j2 lines, not the orchestrator. The CLI logs an `Extract-CLI progress:` summary every `--poll-seconds` (default 30) and an `Extract-CLI final:` line at the end.

## Troubleshooting

**"unreachable — is Tomcat running and credentials valid?"** — confirm Tomcat is up (`tomcat/tomcat-11/bin/startup.sh`) and that `test.properties` carries the `testbot` admin credentials. The script can't proceed without a valid admin session for the `/admin/content/*` endpoints.

**Phase 1 returns 409** — a previous rebuild is still running. The script will wait for it to finish and proceed; if it doesn't, check the snapshot for an `ERROR` state and look at `errors[]` in the JSON for per-page failures.

**Phase 2 returns 503** — hybrid retrieval is disabled. The script skips phase 2 with a warning and continues. To re-enable, set `wikantik.search.hybrid.enabled=true` and restart Tomcat.

**Phase 4 exits immediately with `unknown argument: --prefilter` (or `--force`, `--stats-only`, ...)** — those flags belonged to the retired chunk-level extractor. Drop them; see the [extractor CLI flag reference](#extractor-cli-flag-reference).

**Phase 4 progresses but proposals/mentions stay at zero** — confirm the Ollama model you named is loaded on the inference host and that `--ollama-url` (default `http://inference.jakefear.com:11434`) is reachable from where the script runs. Try `bin/kg-extract.sh --max-pages 3 --dry-run` and read the `Extract-CLI progress:` lines. Mentions are only written for accepted names that already map to a `kg_nodes` row, so a purged KG shows proposals first and mentions only after approvals.

**The run aborts mid-phase-4 and you want to resume** — re-run with `--skip-chunks --skip-embeddings`, and add `--only-missing` to skip pages that already have `kg_proposals` rows (pages whose extraction filed nothing are retried).

**Phase 3 `--purge-kg` confirmation rejected even though I typed yes** — the purge specifically requires `PURGE` (uppercase, exact). It's deliberately stricter than the `[y/N]` of `--reset-kg` so you don't fat-finger your way into a destructive run. Use `--yes` to bypass entirely in scripted contexts.

**Embedding phase shows `state=DISABLED`** — `wikantik.search.hybrid.enabled=false` in your config. The rebuild script logs a warning and skips the phase rather than failing. To re-enable, flip the property and restart Tomcat.

**Hub clustering empty after `--purge-kg`** — by design. The hub-clustering subsystem writes its own data; phase 4's extraction doesn't repopulate it. It will fill back up on the next page-save event or when the hub-discovery service runs.

## Extractor CLI flag reference

`bin/kg-extract.sh` has its own `--help` (the launcher header) and `--jar-help` (the CLI's usage). Every flag is also forwarded by `bin/kg-rebuild.sh` after the optional `--` separator (or as the first unrecognised arg). An unknown flag aborts the run with `unknown argument` and exit code 2. Grouped by purpose:

**Database connection** *(the launcher fills these in from the local `ROOT.xml`)*

- `--jdbc-url <url>` — CLI default `jdbc:postgresql://localhost:5432/jspwiki`
- `--jdbc-user <name>` — CLI default `jspwiki`
- `--jdbc-password <value>` — literal (not recommended; appears in process listing)
- `--jdbc-password-env <VAR>` — read password from env var (preferred; `bin/kg-extract.sh` itself uses this internally with `WIKANTIK_EXTRACT_PG_PASSWORD`)

**Extractor backend selection**

- `--extractor ollama|claude` — default `ollama`
- `--ollama-url <url>` — default `http://inference.jakefear.com:11434`
- `--ollama-model <tag>` — default `gemma4-assist:latest`. Drives the `extractor` column lineage.
- `--extractor-model <id>` — Claude model id (Claude only; default `claude-sonnet-4-6`). Using `--extractor claude` also requires `-Dwikantik.kg.extractor.allow_claude=true` and the key in the env var named by `--anthropic-key-env`.
- `--timeout-ms <ms>` — per-page extractor timeout, default 120000

**Judge** *(opt-in proposal review)*

- `--judge none|ollama|claude` — default `none` (accept everything verbatim)
- `--judge-model <tag/id>` — model used by the judge
- `--anthropic-key-env <VAR>` — env var holding the Anthropic API key (Claude only); `--judge claude` additionally requires `-Dwikantik.kg.judge.allow_claude=true`

**Pipeline tuning**

- `--concurrency <1..6>` — default 2; clamped to the band
- `--confidence-threshold <0..1>` — judge cutoff, default 0.55
- `--dictionary-top-k <N>` — top-K existing nodes injected per page, default 12 (0 = off)
- `--max-entities-per-page <N>` — hard cap per LLM response, default 12
- `--max-relations-per-page <N>` — hard cap per LLM response, default 8
- `--node-embedding-model <tag>` — model for the `kg_node_embeddings` cache, default `qwen3-embedding:0.6b`
- `--embedding-profile cpu|gpu` — batch size for node-embedding warmup, default `cpu` (a batch a CPU embedder can finish inside the timeout); `gpu` uses the measured knee of 64
- `--rebuild-node-embeddings` — TRUNCATE the node-embedding cache before warmup
- `--max-pages <N>` — stop after the first N pages; 0 = unlimited
- `--page-pattern <glob>` — limit to page names matching the glob (`*` and `?`)
- `--only-missing` — skip pages that already have `kg_proposals` rows
- `--dry-run` — skip the `kg_proposals` upsert (smoke runs)
- `--report <path>` — on completion, write the final status as JSON
- `--poll-seconds <N>` — progress-log cadence, default 30

**Misc**

- `-h`, `--help` — show the CLI usage and exit (via `bin/kg-extract.sh --jar-help`)
- Exit codes: 0 completed, 1 errored or refused to start, 2 bad arguments

Chunk-size statistics moved to a separate tool, `bin/kg-chunker-stats.sh` (see above).
