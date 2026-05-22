# Lucene HNSW Dense-Retrieval Backend — Design

**Date:** 2026-05-22
**Status:** Approved, pending implementation
**Author:** Jake Fear (with Claude)

## Goal

Replace the in-memory brute-force dense vector scan — which JFR profiling at
N=350 showed consuming **~60% of all CPU under load**
(`InMemoryChunkVectorIndex.dotAt` 49%, `topKChunks` 6.7%, heap sift 3.8%) — with
an in-process **Lucene HNSW** (Hierarchical Navigable Small World) approximate
nearest-neighbour index. This recovers roughly half the box's CPU and is
expected to roughly double sustainable throughput for the search-heavy mix,
with negligible recall impact (hybrid retrieval re-ranks dense candidates with
BM25 + the Knowledge Graph anyway).

Corpus today: **12,252 chunks × 1024-dim**, single model `qwen3-embedding-0.6b`,
across 1,165 pages (identical in local and docker1 prod). Prod currently runs
the `inmemory` backend (the default), which is exactly the hot path profiled.

## Why HNSW / ANN

The dense search is a nearest-neighbour problem: turn the query into a 1024-dim
vector and find the chunk vectors closest to it (cosine similarity = dot product
of L2-normalised vectors). The current backend is **exact**: it scores every one
of the 12,252 chunks per query — O(N) and the dominant CPU cost.

**ANN (Approximate Nearest Neighbour)** trades a sliver of accuracy for a large
speedup: visit only a few hundred *promising* candidates instead of all N.
**HNSW** is the specific algorithm — a layered ("hierarchical") proximity graph
where each node links to its ~M nearest neighbours ("small world"); a query
enters at a sparse top layer, takes long hops to the right region, then descends
to refine. Per-query distance computations drop from ~12,252 to a few hundred
(~10–30×), at >95% recall. Lucene 10.4 ships a battle-tested HNSW implementation,
so we reuse it rather than writing graph code.

## Architecture

The dense backend is already a swappable seam. Every backend implements
`ChunkVectorIndex`:

```java
List< ScoredChunk > topKChunks( float[] queryVec, int k );  // larger score = better
boolean isReady();
int dimension();
```

`SearchSubsystemFactory` selects one off `wikantik.search.dense.backend`
(`inmemory` | `pgvector`). This design adds a **third value, `lucene-hnsw`**, and
a new class `LuceneHnswChunkVectorIndex implements ChunkVectorIndex`. Nothing
upstream changes — the hybrid fuser, BM25 fail-closed fallback, graph rerank, and
the `/api/search` path see only the interface.

```
query text ─► QueryEmbedder ─► float[1024] ─┐
                                             ▼
            ChunkVectorIndex.topKChunks(vec,k)   ◄── backend switch
              ├── InMemoryChunkVectorIndex   (exact brute-force, legacy default)
              ├── PgVectorChunkVectorIndex   (pgvector HNSW)
              └── LuceneHnswChunkVectorIndex (NEW — in-process Lucene HNSW)
                                             ▼
                          List<ScoredChunk>  ─► DenseRetriever ─► HybridFuser
```

### Where the vectors live

Chunk vectors get their **own** Lucene index, separate from the existing Lucene
text index. Rationale: the text index is one document per *page* (1,165 docs);
embeddings are one per *chunk* (12,252). Each vector doc carries:

- `KnnFloatVectorField("vec", float[1024], VectorSimilarityFunction.COSINE)`
- a stored/indexed `chunk_id` keyword field (for delete-by-term on upsert)
- a stored `page_name` field (returned in `ScoredChunk`)

The index is held in a **RAM directory (`ByteBuffersDirectory`), rebuilt on
boot** from `content_chunk_embeddings` (the bytea `vec` column remains the source
of truth). ~50 MB resident, sub-second build for 12k vectors. No on-disk path,
staleness, or corruption handling. This mirrors the in-memory backend's
lifecycle, swapping flat arrays for an HNSW graph.

> **Future note (scaling trigger).** At tens to hundreds of thousands of pages,
> the boot-time rebuild and 1024-dim × N RAM footprint may justify moving this
> index to an **on-disk persistent Lucene directory** (surviving restarts without
> a rebuild, like the text index). Not warranted at the current ~12k-chunk scale
> — revisit when chunk count approaches ~10⁵.

### Lifecycle & incremental updates

- **Boot:** `BootstrapEmbeddingIndexer`'s reload hook triggers a full rebuild —
  stream all rows for the model code, add one vector doc each, commit.
- **Page save:** `AsyncEmbeddingIndexListener` already fires a `postIndexCallback`
  after each chunk's embedding is (re)written. Wire that callback to the Lucene
  index's **upsert**: delete-by-`chunk_id`-term, add the new doc(s), refresh via
  `SearcherManager` (near-real-time). `SearchWiringHelper` gets a `lucene-hnsw`
  branch parallel to its existing `inmemory` branch.

### Tuning knobs (defaults mirror pgvector)

A small custom `Codec` (delegating to `Lucene104Codec`, overriding the KNN format
with `Lucene99HnswVectorsFormat(maxConn, beamWidth)`) sets build params:

| Property | Default | Meaning |
|----------|--------:|---------|
| `wikantik.search.dense.lucene.m` | 16 | HNSW graph degree (`maxConn`) |
| `wikantik.search.dense.lucene.ef_construction` | 64 | build beam width (`beamWidth`) |
| `wikantik.search.dense.lucene.ef_search` | 100 | query candidate pool |

Query: run `KnnFloatVectorQuery("vec", queryVec, max(k, ef_search))`, take the top
`k`. Score mapping: Lucene's COSINE score is `(1 + cos) / 2`; map back to raw
cosine via `2·score − 1` so the fused-score scale matches `InMemoryChunkVectorIndex`.

### Error handling (fail-closed)

If the index isn't ready or a query throws, `isReady()` returns false / the query
returns an empty list, and hybrid retrieval degrades to BM25 — never a 500. Every
catch logs `LOG.warn` with context (per repo rule; no empty catches).

## Components / files

- **New:** `wikantik-main/.../search/hybrid/LuceneHnswChunkVectorIndex.java` —
  the backend (RAM directory, build, upsert, query, score mapping, fail-closed).
- **New:** a small `Codec` subclass (inner class or sibling) setting HNSW M /
  ef_construction.
- **Modify:** `SearchSubsystemFactory.java` — add the `lucene-hnsw` switch case
  reading the three new properties.
- **Modify:** `SearchWiringHelper.java` — `lucene-hnsw` branch wiring the
  bootstrap reload hook and the `postIndexCallback` upsert.
- **Tests:** new `LuceneHnswChunkVectorIndexTest`; new cases in
  `SearchSubsystemFactoryTest`; `RetrievalQualitySmokeTest` as the quality gate.

No DB migration (bytea column exists). No new Maven dependency (Lucene 10.4 is in).

## Cutover & rollback (pgvector's playbook)

1. Implement + unit/switch tests green.
2. Validate locally via `RetrievalQualitySmokeTest` — nDCG@5 within **±0.02** of
   the in-memory baseline.
3. Full IT reactor green (`mvn clean install -Pintegration-tests -fae`).
4. Deploy; flip prod `wikantik.search.dense.backend=lucene-hnsw`.
5. Re-profile at N=350 — confirm the dense scan collapses from ~60% CPU.
6. Soak; later flip the code default from `inmemory` to `lucene-hnsw`.

**Rollback:** one-line config flip back to `inmemory` (which rebuilds from the
same bytea column). No schema or data changes to unwind.

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Approximate recall drops result quality | Smoke gate (nDCG@5 ±0.02); hybrid BM25+graph rerank cushions dense recall loss; ef_search tunable |
| Score-scale mismatch confuses the fuser | Map Lucene COSINE score back to raw cosine; gate catches regressions |
| Boot rebuild slows startup | ~12k vectors build sub-second; rebuild runs on the existing bootstrap background executor |
| Index/query failure | Fail-closed to BM25; `LOG.warn` with context |

## Observability

Reuse the existing `wikantik.search.hybrid.*` metrics and the dense-backend
readiness/size gauges already published for the in-memory path; register the
Lucene index's size and last-rebuild timestamp under the same scheme.
