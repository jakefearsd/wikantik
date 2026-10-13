---
cluster: wikantik-development
type: article
related:
- WikantikSearchRefinement
- AiPoweredSearch
- WikantikDevelopment
title: Hybrid Retrieval
tags:
- search
- retrieval
- embeddings
- hybrid
- operations
- configuration
canonical_id: 01KQ0P44QZYNK9BZ0FV5NQ9CF8
summary: Operator reference for Wikantik's BM25 + dense hybrid retrieval — how it
  is wired, how it fails, how to configure it, and which Prometheus metrics it publishes.
---
# Hybrid Retrieval

> 🌐 **Product overview:** [Hybrid retrieval on wikantik.com](https://www.wikantik.com/platform/hybrid-retrieval.html) — a plain-language walkthrough for readers and AI agents.


Wikantik's default search path fuses Lucene BM25 with dense embedding cosine similarity using weighted [Reciprocal Rank Fusion](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf). BM25 remains the safety net — whenever the dense side is unavailable, `/api/search` returns the unmodified BM25 ordering so search never goes dark because of an embedding outage.

Two retrieval paths share this infrastructure. `GET /api/search` and the `retrieve_context` MCP tool fuse BM25 and dense at the **page** level (the fuser, aggregation and `wikantik.search.hybrid.*` keys below). The **context bundle** (`GET /api/bundle`, `assemble_bundle`) fuses at the **chunk** level with its own `wikantik.bundle.*` keys — see [Context bundle candidate source](#context-bundle-candidate-source).

This page is the operator reference. For the *design story* of semantic retrieval, see [AiPoweredSearch](); for the quality measurement harness, see [WikantikSearchRefinement]().

## Wiring

The retrieval stack is assembled during engine init by `SearchWiringHelper.wireHybridRetrieval()` (called from `WikiEngine`):

1. **EmbeddingConfig** — resolves backend + model from `wikantik.properties`. If `wikantik.search.hybrid.enabled` is `false`, or `wikantik.genai.mode` is `none`, no embedding client is built and nothing dense wires up (the bundle's BM25 chunk index still does, so `/api/bundle` keeps serving lexical results).
2. **TextEmbeddingClient** — backend adapter (Ollama is the only backend). Used by both index-time and query-time paths.
3. **EmbeddingIndexService** + **AsyncEmbeddingIndexListener** — hook into `ChunkProjector` so every page save re-embeds the new chunks, on a single daemon thread. A failed embed is logged at WARN and the listener does **not** requeue it: the chunk stays unembedded until the next startup reconcile or a Reindex Embeddings run (step 7).
4. **Dense vector index** — `LuceneHnswChunkVectorIndex` (default), `PgVectorChunkVectorIndex` or `InMemoryChunkVectorIndex`, chosen by `wikantik.search.dense.backend` (see [Dense backend selection](#dense-backend-selection)). The two in-process backends load `content_chunk_embeddings` at construction and are updated incrementally after each page-save embed; pgvector queries the table directly.
5. **QueryEmbedder** — wraps the client with a Caffeine cache, a timeout budget, and a hand-rolled circuit breaker (`CLOSED → OPEN → HALF_OPEN` probe → `CLOSED`).
6. **DenseRetriever** + **HybridFuser** + **HybridSearchService** — the query-time orchestrator. Registered as a manager; `DefaultContextRetrievalService` (behind `GET /api/search` and `retrieve_context`) uses it transparently.
7. **BootstrapEmbeddingIndexer** — at startup runs a background *stale reconcile* (`indexStale`): it embeds only the chunks that have no embedding for the current model, or whose embedding is older than the chunk. It retries up to 4 times, 45 s apart, while the embedding backend warms. When a run ends COMPLETED it fires the dense index's reload hook. The admin **Reindex Embeddings** action runs `forceStart()` instead: delete every row for the model, then re-embed everything. Progress shows on the admin index-status panel.
8. **HybridMetricsBridge** — publishes embedder, bootstrap, and vector-index meters to the process-wide Micrometer registry.
9. **Bundle candidate sources** — `DenseChunkSectionSource`, plus `HybridChunkSectionSource` over a RAM BM25 chunk index (`LuceneBm25ChunkIndex`) when `wikantik.bundle.bm25.enabled` is on, built from the same embedder and vector index.

## Fail-closed behaviour

`HybridSearchService` (`rerankWithChunks` / `rerankWith`) is the single choke point that guarantees BM25-only is always a valid fallback. Every abnormal path returns the input BM25 list unchanged:

| Trigger                                    | Result                                  |
|--------------------------------------------|-----------------------------------------|
| `wikantik.search.hybrid.enabled = false`   | BM25 verbatim                           |
| Query null / blank                         | BM25 verbatim                           |
| `QueryEmbedder.embed()` throws             | BM25 verbatim, WARN logged              |
| Embedder returns `Optional.empty()`        | BM25 verbatim, DEBUG logged             |
| `DenseRetriever.retrieve()` throws         | BM25 verbatim, WARN logged              |
| Dense result set empty                     | BM25 verbatim                           |
| Vector index not ready (zero vectors loaded) | BM25 verbatim (dense returns empty)   |
| Query-vector dimension ≠ index dimension   | BM25 verbatim, WARN logged              |
| Circuit breaker OPEN                       | BM25 verbatim (embedder returns empty)  |

A fused response keeps every BM25 hit: the fuser's window (`rrf.truncate`) can push BM25 hits out of the fused block, and `HybridSearchService` appends them back after it. Pages that only the dense side found are wrapped as `DenseOnlySearchResult` entries (score 0, no context snippets) and sit at their fused position.

## Configuration

Every key is declared, with its default and a description, in `ini/wikantik.properties` (`wikantik-main/src/main/resources/ini/`); override in `wikantik-custom.properties` or set from the environment. The generated [WikantikConfigurationReference](WikantikConfigurationReference) lists them all.

### Master flag

```
wikantik.search.hybrid.enabled = true
```

Default in the shipped properties file is `true`. Setting `false` disables both the dense index build and the query-time rerank — no embedding client is instantiated. `wikantik.genai.mode = none` forces the same state; `embeddings-only` and `full` (the default) leave it on.

### Fuser

```
wikantik.search.hybrid.rrf.k            = 60
wikantik.search.hybrid.rrf.bm25-weight  = 1.5
wikantik.search.hybrid.rrf.dense-weight = 1.5
wikantik.search.hybrid.rrf.truncate     = 20
wikantik.search.hybrid.page-aggregation = mean_top_3
```

`rrf.truncate` caps the fused window: only the first 20 of each list contribute, so pages outside both top-20 lists fall out of the fused block — `HybridSearchService` appends any BM25 tail back to the end of the output so reorder-not-remove stays true.

`page-aggregation` is one of `max`, `mean_top_3` (default), `sum_top_3`, `sum_top_5`. Since 2.4.23 the default is `mean_top_3`: `sum_top_3` (the earlier default) made chunk count a score multiplier, so short pages were structurally unreachable, and switching took nDCG@5 on `core-agent-queries` from 0.4528 to 0.8984 (0.9297 paired with the 1.5 BM25 weight). Keep `bm25-weight / dense-weight > (rrf.k + 1) / (rrf.k + rrf.truncate)` (0.7625 at the defaults) or a page found *only* by BM25 can never enter the result window; `HybridFuserTest` guards the invariant.

### Dense retrieval

```
wikantik.search.hybrid.dense.chunk-top = 500
wikantik.search.hybrid.dense.page-top  = 100
```

`chunk-top` is the cosine top-K from the dense index; `page-top` caps how many pages survive after the page-aggregation strategy collapses their chunk scores.

### Dense backend selection

```
wikantik.search.dense.backend = lucene-hnsw
```

Three backends are available. The shipped properties file defaults to `lucene-hnsw` — also what docker1 production runs — and the production wiring path (`SearchWiringHelper.resolveDenseBackend`) falls back to it when the key is absent. Only the no-DataSource fallback path in `SearchSubsystemFactory` defaults to `inmemory`, because it is the one backend that needs no database. `pgvector` and `lucene-hnsw` are fixed at **1024 dimensions** (`PgVectorChunkVectorIndex.EMBEDDING_DIM`, matching V032's `vector(1024)`), so the embedding model must produce 1024-dim vectors.

| Value | Description |
|-------|-------------|
| `inmemory` | Brute-force float[] cosine scan over all chunks. Simple and exact, but O(N) per query and per save — CPU cost grows linearly with corpus size. Dev / small-corpus fallback only. |
| `pgvector` | Delegates to PostgreSQL's HNSW index on `content_chunk_embeddings.embedding` (V032, `wikantik.search.dense.pgvector.ef_search` default 100). Offloads CPU to the DB; needs the one-shot backfill `bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh` for rows embedded before that column existed. The **only** backend that reads through to the database. |
| `lucene-hnsw` | In-process Lucene HNSW approximate nearest-neighbour index. Held in RAM; rebuilt on boot from `content_chunk_embeddings`. **The default.** See below. |

**Index freshness gotcha.** `inmemory` and `lucene-hnsw` build a separate in-process index. It is updated incrementally after each page-save embed and fully reloaded by the bootstrap's reload hook — which fires **only when the bootstrap run ends COMPLETED**. A run that ends FAILED leaves the dense index exactly as stale as it was at boot, and hybrid stays BM25-only for those chunks until the next successful run (or a restart). A long backfill does not improve retrieval progressively on these two backends: rows committed at hour 1 are not queryable until the run completes. `pgvector` rows are queryable the instant they commit, so retrieval improves as the backfill proceeds. A `topSimilarity` of `-1.0` with `confidence: unknown` in a bundle's coverage block is the tell.

#### `lucene-hnsw` — in-process HNSW

The Lucene HNSW backend is the third selectable value of `wikantik.search.dense.backend` and is **the default** — it is what docker1 production runs (it replaced the brute-force `inmemory` scan that profiling found at ~60% of search CPU). It builds a [Hierarchical Navigable Small World](https://arxiv.org/abs/1603.09320) approximate nearest-neighbour index inside the JVM process using a Lucene `ByteBuffersDirectory` (pure RAM; no disk I/O). It is rebuilt on every boot by scanning `content_chunk_embeddings` and is updated incrementally on page save via `AsyncEmbeddingIndexListener`.

**Metadata retrieval — DocValues, not stored fields.** Each candidate's `chunk_id` and `page_name` are retrieved at query time via Lucene **DocValues** (columnar, memory-resident, no per-read decompression), not stored fields. An earlier cut used stored fields and spent ~44% of CPU under load decompressing an LZ4 block per hit; the DocValues path eliminated it.

**When to choose it over `inmemory`:** the brute-force scan is O(N) over every chunk for every query. At a few thousand chunks the cost is negligible, but as the corpus grows the query CPU bill compounds. The HNSW graph visits only a few hundred candidates per query regardless of corpus size (~95%+ recall), recovering CPU without sacrificing meaningful relevance — hybrid BM25 + dense fusion cushions the approximation gap.

**When to choose `pgvector` instead:** if you want to offload the ANN computation to Postgres entirely (e.g. the JVM heap is the bottleneck, or you want the pg stats), use `pgvector`. The `lucene-hnsw` backend keeps the index in the JVM heap, so it increases heap pressure in proportion to `(num_chunks × embedding_dimension × 4 bytes)`.

**Tuning knobs** — defaults mirror the pgvector HNSW index:

| Property | Default | Meaning |
|----------|---------|---------|
| `wikantik.search.dense.lucene.m` | `16` | Graph degree — max edges per node. Higher = better recall, more RAM and build time. |
| `wikantik.search.dense.lucene.ef_construction` | `64` | Build beam width — candidate pool during index construction. Higher = better graph quality, slower boot. |
| `wikantik.search.dense.lucene.ef_search` | `100` | Query candidate pool. Higher = better recall, more CPU per query. In practice inert today: the query fetches `max(k, ef_search)` and every dense caller asks for far more (the bundle 300 chunks, hybrid page retrieval 500). |
| `wikantik.search.dense.lucene.quantization` | `none` | `none` keeps raw float32 vectors; `seven_bit`, `unsigned_byte` (~4x smaller reads) and `packed_nibble` (~8x) trade some recall for CPU — profiling found the vector read, not the cosine math, dominated `topKChunks`. Opt-in until measured against the retrieval parity gate; an unrecognised value falls back to `none`. No migration needed — the index is rebuilt on boot. |

All of them are declared in `wikantik.properties` at the defaults shown (parsed by `HnswParams`).

**Lifecycle:** the bytea `vec` column in `content_chunk_embeddings` is always the source of truth. The in-RAM index is a derived cache — if the process restarts, it rebuilds. The initial load runs in the index constructor during engine wiring; a database error there logs WARN and leaves an empty index. While the index holds zero vectors `isReady()` is false and dense retrieval degrades to BM25-only (same behaviour as `inmemory` during bootstrap).

**Scale note:** at the corpus's current scale (on the order of 10⁴ chunks) this backend is well within reason. At tens to hundreds of thousands of chunks the boot-time rebuild and heap footprint may justify switching to a persistent on-disk Lucene directory; revisit when chunk count approaches ~10⁵.

Implementation and rationale: the `LuceneHnswChunkVectorIndex` class Javadoc (`wikantik-main/src/main/java/com/wikantik/search/hybrid/`).

### Embedding backend

```
wikantik.search.embedding.backend    = ollama
wikantik.search.embedding.base-url   = http://inference.jakefear.com:11434
wikantik.search.embedding.model      = qwen3-embedding-0.6b
wikantik.search.embedding.timeout-ms = 30000
wikantik.search.embedding.batch-size = 10
```

Those are the shipped defaults. `ollama` is the only backend. The model is `qwen3-embedding-0.6b` (Ollama tag `qwen3-embedding:0.6b`, 1024 dimensions; the other recognised codes are `bge-m3` and `nomic-embed-v1.5`, the latter 768-dim and therefore unusable with the `pgvector` / `lucene-hnsw` backends). Point `base-url` at whatever serves that model — the Docker deployment uses its bundled CPU `ollama` sidecar (`WIKANTIK_EMBEDDING_BASE_URL`), and the dense index is fixed at that model's dimension. `wikantik.search.embedding.profile = cpu|gpu` only supplies the `batch-size` default (`cpu` → 10, `gpu` → 64); an explicit `batch-size` wins.

`batch-size` is bounded by `timeout-ms`, not by throughput: Ollama serves a batch of N as N *sequential* inferences inside one HTTP request, and a batch that cannot finish inside the timeout is retried and then abandoned. On the CPU embedder one inference is ~1.9 s, so the old default of 32 (~61 s) could never complete in 30 s. `commit-batch-size` (default 256) bounds how many backfilled rows one transaction commits, so an interrupted multi-hour backfill loses at most one window.

Model code must match the one that actually wrote the `content_chunk_embeddings` rows — the vector index is keyed by `model_code`, so a mid-run change means an empty index until a re-embed.

### Query embedder

All tunables for `QueryEmbedder` live under the `wikantik.search.hybrid.embedder.`
prefix (`QueryEmbedderConfig`):

```
wikantik.search.hybrid.embedder.timeout-ms              = 2000
wikantik.search.hybrid.embedder.cache.ttl-seconds        = 14400
wikantik.search.hybrid.embedder.cache.max-entries        = 1000
wikantik.search.hybrid.embedder.breaker.window-size      = 20
wikantik.search.hybrid.embedder.breaker.min-calls        = 10
wikantik.search.hybrid.embedder.breaker.failure-rate     = 0.5
wikantik.search.hybrid.embedder.breaker.cooldown-ms      = 30000
```

`timeout-ms` is the per-call wall-clock budget for the embed request. `cache.*`
sizes the Caffeine cache of resolved query vectors. The breaker is a rolling
window, not a consecutive-failure counter: it tracks the last `breaker.window-size`
calls in CLOSED state and trips OPEN once at least `breaker.min-calls` of them
have been observed and the failure ratio reaches `breaker.failure-rate`. After
`breaker.cooldown-ms` it transitions to HALF_OPEN and admits exactly one probe
call; success closes the breaker, failure re-opens it.

## Admin UI

The **Index Status** tab of Admin → Content (`/admin/content`, backed by `GET /admin/content/index-status`) shows:

- **Embeddings stat card** — row count, model code, dimension.
- **Embedding Bootstrap** progress bar — live when the one-shot backfill is running.
- **Reindex Embeddings** button — POSTs `/admin/content/reindex-embeddings`:
  - `202 Accepted` on dispatch (includes `state`, `model_code`)
  - `409 Conflict` when a bootstrap is already in flight
  - `503 Service Unavailable` when `wikantik.search.hybrid.enabled = false`
- **Embedder metrics panel** — circuit state (colored), cache hit rate, call success / failure / timeout, breaker open / close / half-open probe, calls rejected.

## Prometheus metrics

Scraped at `/metrics` (Prometheus text format). The table gives the Micrometer names, all prefixed `wikantik.search.hybrid`; in the scrape dots become underscores and counters gain `_total` (e.g. `wikantik_search_hybrid_embedder_calls_total{result="success"}`):

| Meter                                          | Type              | Tags                          | Meaning                                               |
|------------------------------------------------|-------------------|-------------------------------|-------------------------------------------------------|
| `.embedder.calls`                              | function counter  | `result=success\|failure\|timeout` | Embed calls by terminal outcome                  |
| `.embedder.cache`                              | function counter  | `result=hit\|miss`             | Caffeine cache outcome                                |
| `.embedder.breaker.transitions`                | function counter  | `to=open\|close\|half_open`    | Breaker state transitions                             |
| `.embedder.breaker.rejected`                   | function counter  | —                             | Calls short-circuited by OPEN breaker                 |
| `.embedder.circuit_state`                      | gauge             | —                             | `0=CLOSED, 1=HALF_OPEN, 2=OPEN`                       |
| `.embedder.latency`                            | timer             | —                             | Embedding-client call latency (percentile histogram)  |
| `.vector_index.size`                           | gauge             | —                             | Chunks in the active vector index (any backend)       |
| `.vector_index.last_rebuild_millis`            | gauge             | —                             | `lucene-hnsw` only: epoch millis of the last index commit/refresh |
| `.bootstrap.state`                             | gauge             | —                             | `0=IDLE, 1=SKIPPED_ALREADY, 2=SKIPPED_NO_CHUNKS, 3=RUNNING, 4=COMPLETED, 5=FAILED` |
| `.bootstrap.chunks_total`                      | gauge             | —                             | Planned chunk count for the current bootstrap run     |

Counter rates over time are the primary signal for dashboards; the state gauges are useful as alert inputs (e.g. `embedder.circuit_state > 0` sustained for 5m).

Retrieval *quality* is published separately by the nightly runner as `wikantik_retrieval_ndcg_at_5{set,mode}` and friends — see [RunningTheRetrievalQualityHarness](RunningTheRetrievalQualityHarness).

## Runbook snippets

### Dense search is silent, BM25 looks fine

Check, in order:

1. `wikantik.search.hybrid.embedder.circuit_state` gauge — if it is pegged at 2 (OPEN), the embedder is failing upstream. Look at the backend (`curl <wikantik.search.embedding.base-url>/api/tags` should list `qwen3-embedding:0.6b`). See [HandlingEmbeddingServiceOutages](HandlingEmbeddingServiceOutages).
2. `wikantik.search.hybrid.vector_index.size` — if 0, the bootstrap never succeeded. Check `bootstrap.state`; if `FAILED`, check `error_message` in index-status JSON or WARN logs for the stack.
3. `wikantik.search.hybrid.bootstrap.state` — if stuck at `RUNNING` for longer than the corpus size warrants, watch `chunks_total` vs the live `embeddings.row_count` in index-status; if they're not moving, the backend is rate-limiting or wedged. A `FAILED` run on `inmemory` / `lucene-hnsw` means the dense index was **not** reloaded (see the freshness gotcha above).

### Post-model-swap corpus mismatch

The vector index is keyed by `model_code`. If you change `wikantik.search.embedding.model`:

1. On restart the dense index loads the rows for the new `model_code` — there are none, so it is empty.
2. `BootstrapEmbeddingIndexer.startIfNeeded()` finds every chunk without an embedding for that model and re-embeds them in the background.
3. Dense retrieval degrades gracefully to BM25-only until `size > 0` (on `inmemory` / `lucene-hnsw`, until the run completes and the reload hook fires; on `pgvector`, progressively).

Force a re-embed explicitly via the admin "Reindex Embeddings" button if you want determinism rather than drift — it deletes the model's existing rows from the database first. On `pgvector` the dense side therefore starts empty and refills as rows stream back in; the in-process `inmemory` / `lucene-hnsw` indexes keep serving their current vectors until the run completes and the reload hook swaps in the new ones. `bin/kg-rebuild.sh` drives the same endpoint as part of a full pipeline rebuild (see `docs/admin/IndexRebuild.md`).

## Context bundle candidate source

`GET /api/bundle` and `assemble_bundle` do not use the page-level fusion above. By default they retrieve the top-K chunks **globally** (no page pre-select — a page-gated source drops sections whose page ranks outside the top-N), fuse a RAM BM25 chunk index with the dense ranking, and group chunks to sections. The `mode` argument selects `hybrid` (default), `dense` or `lexical`. These keys are independent of the `wikantik.search.hybrid.rrf.*` knobs:

| Key | Default | Meaning |
|-----|---------|---------|
| `wikantik.bundle.dense.enabled` | `true` | Global dense-chunk source (`false` falls back to the page-gated path) |
| `wikantik.bundle.dense.top_k` | `300` | Chunk fan-out per query |
| `wikantik.bundle.sections_per_page` | `20` | Sections per page entering the candidate pool |
| `wikantik.bundle.bm25.enabled` | `true` | Chunk-level BM25 arm; degrades to dense-only if its build fails |
| `wikantik.bundle.bm25.bm25_weight` / `.dense_weight` | `0.5` / `1.5` | Bundle RRF weights (dense-tilted 3:1) |
| `wikantik.bundle.bm25.rrf_k` / `.truncate` | `20` / `20` | Bundle RRF constant and window |
| `wikantik.bundle.bm25.analyzer` | `standard` | `code` splits camelCase/snake_case but collapses natural-language recall under BM25-heavy fusion |
| `wikantik.bundle.reranker.enabled` | `false` | LLM listwise reranker — measured to reorder without raising recall |
| `wikantik.bundle.decomposition.enabled` | `false` | LLM query decomposition — measured and rejected |

Measured and rejected levers (`eval/bundle-corpus/baseline-notes.md`): the LLM listwise reranker, HyDE, doc2query, query decomposition and the Knowledge Graph page-level rerank (deleted in 2026-07 — see [KnowledgeGraphRerank](KnowledgeGraphRerank)). Operator reference for the bundle: `docs/admin/RagContextBundle.md`.

## Section recall levers

Two changes moved global **section recall@12 from 0.60 to 0.74** (measured in
`eval/bundle-corpus/baseline-notes.md`):

### Chunker heading-fidelity fix

`ContentChunker` force-emits the merge-forward buffer **at each heading
boundary**, so early sections and the first H2 keep their own `heading_path`.
Before the fix they were mis-attributed to the *previous* heading — and
therefore mis-cited. Two config knobs tune fragment merging:

- `wikantik.chunker.fragment_floor_tokens` (default **24**) — fragments below
  this token count merge **forward** into the next chunk, adopting the
  destination heading.
- `wikantik.chunker.overlap_tokens` (default **40**) — token overlap carried
  between adjacent chunks.

The other chunker keys are `wikantik.chunker.max_tokens` (512),
`wikantik.chunker.merge_forward_tokens` (150) and the `wikantik.chunker.enabled`
kill switch (true). Changing any of them changes chunk content hashes, so follow
the edit with a full rebuild (`POST /admin/content/rebuild-indexes`, then Reindex
Embeddings).

### Contextual document embeddings

`EmbeddingTextBuilder.forDocument` prepends a context line before embedding
each section:

    Page: {title} | Cluster: {cluster} | Section: {heading}

followed by a `Summary: {summary}` line from the frontmatter. This gives each section's vector its
page/cluster/heading context. The **query** side keeps its own instruction
prefix (the two prefixes are deliberately different).

## Bundle coverage signal

Every context bundle (`assemble_bundle` MCP, `GET /api/bundle`) carries a
`coverage` block so an agent can tell whether it has enough grounding or should
refine/escalate, instead of re-querying blindly:

    "coverage": { "sectionCount": 12, "distinctPageCount": 5,
                  "topSimilarity": 0.71, "confidence": "strong" }

`topSimilarity` is the **true top dense cosine** for the query (threaded out of
the candidate source — the hybrid path's per-section `score` is a rank proxy and
is *not* usable for this). `confidence` is derived from that cosine plus the
section count:

- `topSimilarity` unavailable (`-1`) → `unknown`
- `≥ strong_similarity` **and** `sectionCount ≥ 3` → `strong`
- `≥ partial_similarity` → `partial`
- otherwise → `weak`

After a view-ACL gate drops sections, the counts are recomputed and the
retrieval-derived `topSimilarity` is preserved (cosine is a property of
retrieval, not of who may read the result). The confidence label is
re-evaluated: a `strong` bundle thinned below 3 viewable sections steps down to
`partial`, and an emptied bundle becomes `weak` (or `unknown` when
`topSimilarity` is `-1`).

Thresholds (provisional — calibrate against the
`eval/agent-grounding` top-cosine distribution):

- `wikantik.bundle.coverage.strong_similarity` (default **0.55**)
- `wikantik.bundle.coverage.partial_similarity` (default **0.40**)
