---
canonical_id: 01KQ0P44QZYNK9BZ0FV5NQ9CF8
title: Hybrid Retrieval
cluster: wikantik-development
type: article
tags:
- search
- retrieval
- embeddings
- hybrid
- operations
- configuration
summary: Operator reference for Wikantik's BM25 + dense hybrid retrieval — how it is wired, how it fails, how to configure it, and which Prometheus metrics it publishes.
related:
- WikantikSearchRefinement
- AiPoweredSearch
- WikantikDevelopment
---

# Hybrid Retrieval

Wikantik's default search path fuses Lucene BM25 with dense embedding cosine similarity using weighted [Reciprocal Rank Fusion](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf). BM25 remains the safety net — whenever the dense side is unavailable, `/api/search` returns the unmodified BM25 ordering so search never goes dark because of an embedding outage.

This page is the operator reference. For the *design story* of semantic retrieval, see [AiPoweredSearch](); for the quality measurement harness, see [WikantikSearchRefinement]().

## Wiring

The retrieval stack is assembled during engine init by `WikiEngine.wireHybridRetrieval()`:

1. **EmbeddingConfig** — resolves backend + model from `wikantik.properties`. If the master flag is off, nothing else wires up.
2. **TextEmbeddingClient** — backend adapter (currently Ollama). Used by both index-time and query-time paths.
3. **EmbeddingIndexService** + **AsyncEmbeddingIndexListener** — hook into `ChunkProjector` so every page save re-embeds the new chunks.
4. **InMemoryChunkVectorIndex** — loads the embeddings table at construction and reloads after each batch so queries always see the latest corpus without hitting Postgres.
5. **QueryEmbedder** — wraps the client with a Caffeine cache, a timeout budget, and a hand-rolled circuit breaker (`CLOSED → HALF_OPEN → OPEN`).
6. **DenseRetriever** + **HybridFuser** + **HybridSearchService** — the query-time orchestrator. Registered as a manager so `SearchResource` can use it transparently.
7. **BootstrapEmbeddingIndexer** — one-shot state machine that backfills the embeddings table if it is empty for the current model. Runs async; exposed via the admin index-status panel.
8. **HybridMetricsBridge** — publishes embedder, bootstrap, and vector-index counters to the process-wide Micrometer registry.

## Fail-closed behaviour

`HybridSearchService.rerank()` is the single choke point that guarantees BM25-only is always a valid fallback. Every abnormal path returns the input BM25 list unchanged:

| Trigger                                    | Result                                  |
|--------------------------------------------|-----------------------------------------|
| `wikantik.search.hybrid.enabled = false`   | BM25 verbatim                           |
| Query null / blank                         | BM25 verbatim                           |
| `QueryEmbedder.embed()` throws             | BM25 verbatim, WARN logged              |
| Embedder returns `Optional.empty()`        | BM25 verbatim, DEBUG logged             |
| `DenseRetriever.retrieve()` throws         | BM25 verbatim, WARN logged              |
| Dense result set empty                     | BM25 verbatim                           |
| Vector index not ready                     | BM25 verbatim (dense returns empty)     |
| Circuit breaker OPEN                       | BM25 verbatim (embedder returns empty)  |

A fused response is a superset of BM25: pages that appeared only in dense results are appended after the fused block as `DenseOnlySearchResult` entries (score 0, no context snippets).

## Configuration

All keys live in `wikantik.properties`; override in `wikantik-custom.properties` or set from the environment.

### Master flag

```
wikantik.search.hybrid.enabled = true
```

Default in the shipped properties file is `true`. Setting `false` disables both the dense index build and the query-time rerank — no embedding client is instantiated.

### Fuser

```
wikantik.search.hybrid.rrf.k            = 60
wikantik.search.hybrid.rrf.bm25-weight  = 1.0
wikantik.search.hybrid.rrf.dense-weight = 1.5
wikantik.search.hybrid.rrf.truncate     = 20
wikantik.search.hybrid.page-aggregation = SUM_TOP_3
```

These are the eval-winning defaults from the `retrieval-eval-baseline` run. `rrf.truncate` caps the fused window; pages outside both top-20 lists fall out — `HybridSearchService` appends any BM25 tail back to the end of the output so reorder-not-remove stays true.

### Dense retrieval

```
wikantik.search.hybrid.dense.chunk-top = 500
wikantik.search.hybrid.dense.page-top  = 100
```

`chunk-top` is the cosine top-K from the in-memory index; `page-top` caps how many pages survive after `PageAggregation.SUM_TOP_3` collapses their chunk scores.

### Dense backend selection

```
wikantik.search.dense.backend = inmemory
```

Three backends are available:

| Value | Description |
|-------|-------------|
| `inmemory` | Brute-force float[] cosine scan over all chunks. Simple and exact, but O(N) per query — CPU cost grows linearly with corpus size. Default. |
| `pgvector` | Delegates to PostgreSQL's HNSW index on `content_chunk_embeddings.embedding` (V032). Offloads CPU to the DB; requires the backfill one-shot. |
| `lucene-hnsw` | In-process Lucene HNSW approximate nearest-neighbour index. Held in RAM; rebuilt on boot from `content_chunk_embeddings`. See below. |

#### `lucene-hnsw` — in-process HNSW

The Lucene HNSW backend is the third selectable value of `wikantik.search.dense.backend` and is **the docker1 production default** (it replaced the brute-force `inmemory` scan that profiling found at ~60% of search CPU). It builds a [Hierarchical Navigable Small World](https://arxiv.org/abs/1603.09320) approximate nearest-neighbour index inside the JVM process using a Lucene `ByteBuffersDirectory` (pure RAM; no disk I/O). It is rebuilt on every boot by scanning `content_chunk_embeddings` and is updated incrementally on page save via `AsyncEmbeddingIndexListener`.

**Metadata retrieval — DocValues, not stored fields.** Each candidate's `chunk_id` and `page_name` are retrieved at query time via Lucene **DocValues** (columnar, memory-resident, no per-read decompression), not stored fields. An earlier cut used stored fields and spent ~44% of CPU under load decompressing an LZ4 block per hit; the DocValues path eliminated it. See `docs/superpowers/specs/2026-05-22-hnsw-docvalues-retrieval-design.md`.

**When to choose it over `inmemory`:** the brute-force scan is O(N) over every chunk for every query. At a few thousand chunks the cost is negligible, but as the corpus grows the query CPU bill compounds. The HNSW graph visits only a few hundred candidates per query regardless of corpus size (~95%+ recall), recovering CPU without sacrificing meaningful relevance — hybrid BM25 + graph rerank further cushions the approximation gap.

**When to choose `pgvector` instead:** if you want to offload the ANN computation to Postgres entirely (e.g. the JVM heap is the bottleneck, or you want the pg stats), use `pgvector`. The `lucene-hnsw` backend keeps the index in the JVM heap, so it increases heap pressure in proportion to `(num_chunks × embedding_dimension × 4 bytes)`.

**Tuning knobs** — defaults mirror the pgvector HNSW index:

| Property | Default | Meaning |
|----------|---------|---------|
| `wikantik.search.dense.lucene.m` | `16` | Graph degree — max edges per node. Higher = better recall, more RAM and build time. |
| `wikantik.search.dense.lucene.ef_construction` | `64` | Build beam width — candidate pool during index construction. Higher = better graph quality, slower boot. |
| `wikantik.search.dense.lucene.ef_search` | `100` | Query candidate pool. Higher = better recall, more CPU per query. Tune like `pgvector.ef_search`. |

All three are commented out in `wikantik.properties`; the code defaults in `HnswParams` apply unless an operator overrides them.

**Lifecycle:** the bytea embedding column in `content_chunk_embeddings` is always the source of truth. The in-RAM index is a derived cache — if the process restarts, it rebuilds. The rebuild is async; dense retrieval degrades to BM25-only until the index is ready (same behaviour as `inmemory` during bootstrap).

**Scale note:** at the current ~12k-chunk scale this backend is well within reason. At tens to hundreds of thousands of chunks the boot-time rebuild and heap footprint may justify switching to a persistent on-disk Lucene directory; revisit when chunk count approaches ~10⁵.

Design spec: `docs/superpowers/specs/2026-05-22-lucene-hnsw-dense-retrieval-design.md`.

### Embedding backend

```
wikantik.search.embedding.backend  = ollama
wikantik.search.embedding.base-url = http://localhost:11434
wikantik.search.embedding.model    = qwen3-embedding-0.6b
wikantik.search.embedding.batch-size = 32
```

Model code must match the one that actually wrote the `page_chunk_embeddings` rows — the vector index is keyed by model_code, so a mid-run change means an empty index until a rebuild.

### Query embedder

```
wikantik.search.hybrid.query.cache-size     = 1024
wikantik.search.hybrid.query.timeout-ms     = 400
wikantik.search.hybrid.query.breaker.trip   = 5
wikantik.search.hybrid.query.breaker.reset-ms = 30000
```

The breaker trips OPEN after `trip` consecutive failures. After `reset-ms` it transitions to HALF_OPEN and lets a single probe call through; success closes the breaker, failure re-opens it.

## Chunking, contextual embeddings & the context bundle

A 2026-06-14 recall-lever sweep (measure-upstream-first; full record in `eval/bundle-corpus/baseline-notes.md`) moved section-level retrieval recall substantially, all with local models — no premium LLMs, no reranking, no answer synthesis.

**Chunking** (`ContentChunker`). A heading-fidelity fix: a chunk's `heading_path` always matches the section its content came from. The old merge-forward logic let a short preamble carry the *first* section's heading onto later sections' content, so first-`##` sections were unfindable by their own heading and their citations were mis-anchored. Knobs:

```
wikantik.chunker.max_tokens            = 512   # hard ceiling
wikantik.chunker.merge_forward_tokens  = 150   # hold short within-section blocks together
wikantik.chunker.fragment_floor_tokens = 24    # sub-floor sections merge forward, adopt the destination heading
wikantik.chunker.overlap_tokens        = 40    # prepend each chunk's tail to the next same-section chunk
```

**Contextual document embeddings.** `EmbeddingTextBuilder.forDocument` prepends each chunk's frontmatter context — `Page: {title} | Cluster: {cluster} | Section: {heading-path}` + the page summary — before embedding. The query side is unchanged (it keeps its instruction prefix). This structured context is the disambiguation that was missing from raw chunk embeddings and is the single largest lever (global section recall@12 ≈ 0.60 → 0.74). **Changing chunker knobs or the context format requires a full content rebuild** (`POST /admin/content/rebuild-indexes`) — it invalidates `content_hash` on every chunk.

**Context bundle** (`/api/bundle?q=`, `assemble_bundle` MCP). Assembles retrieval into a ranked, de-duplicated, version-pinned-cited set of sections (no synthesis). Default candidate source is **global dense-chunk** retrieval — the top-K chunks across the whole corpus, grouped to sections — which beats the page-gated hybrid path because it doesn't drop sections whose page ranks outside the page pre-select:

```
wikantik.bundle.dense.enabled     = true   # false → page-gated (hybrid) candidate source
wikantik.bundle.dense.top_k       = 300    # global chunk fan-out before grouping to sections
wikantik.bundle.sections_per_page = 20     # per-page cap (page-gated path only)
wikantik.bundle.reranker.enabled  = false  # the listwise LLM reranker is a poor judge — leave OFF
```

**Operational note:** with `dense.backend = inmemory`, the in-memory vector index must be reloaded (a restart) after a content rebuild before the dense-chunk bundle can hydrate; `lucene-hnsw` and `pgvector` read from the database and are not affected.

## Admin UI

`/admin/content/index-status` on the Indexes tab shows:

- **Embeddings stat card** — row count, model code, dimension.
- **Embedding Bootstrap** progress bar — live when the one-shot backfill is running.
- **Reindex Embeddings** button — POSTs `/admin/content/reindex-embeddings`:
  - `202 Accepted` on dispatch (includes `state`, `model_code`)
  - `409 Conflict` when a bootstrap is already in flight
  - `503 Service Unavailable` when `wikantik.search.hybrid.enabled = false`
- **Embedder metrics panel** — circuit state (colored), cache hit rate, call success / failure / timeout, breaker open / close / half-open probe, calls rejected.

## Prometheus metrics

Scraped at `/observability/metrics`. All names prefixed `wikantik.search.hybrid`:

| Meter                                          | Type              | Tags                          | Meaning                                               |
|------------------------------------------------|-------------------|-------------------------------|-------------------------------------------------------|
| `.embedder.calls`                              | function counter  | `result=success\|failure\|timeout` | Embed calls by terminal outcome                  |
| `.embedder.cache`                              | function counter  | `result=hit\|miss`             | Caffeine cache outcome                                |
| `.embedder.breaker.transitions`                | function counter  | `to=open\|close\|half_open`    | Breaker state transitions                             |
| `.embedder.breaker.rejected`                   | function counter  | —                             | Calls short-circuited by OPEN breaker                 |
| `.embedder.circuit_state`                      | gauge             | —                             | `0=CLOSED, 1=HALF_OPEN, 2=OPEN`                       |
| `.vector_index.size`                           | gauge             | —                             | Chunks in the in-memory vector index                  |
| `.bootstrap.state`                             | gauge             | —                             | `0=IDLE, 1=SKIPPED_ALREADY, 2=SKIPPED_NO_CHUNKS, 3=RUNNING, 4=COMPLETED, 5=FAILED` |
| `.bootstrap.chunks_total`                      | gauge             | —                             | Planned chunk count for the current bootstrap run     |

Counter rates over time are the primary signal for dashboards; the state gauges are useful as alert inputs (e.g. `circuit_state > 0` sustained for 5m).

## Runbook snippets

### Dense search is silent, BM25 looks fine

Check, in order:

1. `wikantik.search.hybrid.circuit_state` gauge — if it is pegged at 2 (OPEN), the embedder is failing upstream. Look at the backend (`curl $EMBEDDING_BASE_URL/api/embeddings ...`).
2. `wikantik.search.hybrid.vector_index.size` — if 0, the bootstrap never succeeded. Check `bootstrap.state`; if `FAILED`, check `error_message` in index-status JSON or WARN logs for the stack.
3. `wikantik.search.hybrid.bootstrap.state` — if stuck at `RUNNING` for longer than the corpus size warrants, watch `chunks_total` vs the live `embeddings.row_count` in index-status; if they're not moving, the backend is rate-limiting or wedged.

### Post-model-swap corpus mismatch

The vector index is keyed by `model_code`. If you change `wikantik.search.embedding.model`:

1. Engine restart will rebuild the InMemoryChunkVectorIndex against the new code — it will be empty until a bootstrap runs.
2. `BootstrapEmbeddingIndexer` detects the empty state and dispatches async.
3. Dense retrieval degrades gracefully to BM25-only until `size > 0`.

Force a re-embed explicitly via the admin "Reindex Embeddings" button if you want determinism rather than drift.
