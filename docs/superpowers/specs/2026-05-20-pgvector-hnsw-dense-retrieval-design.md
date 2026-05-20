# pgvector + HNSW for dense retrieval — design

**Date:** 2026-05-20
**Status:** Design approved, plan pending
**Author:** Jake Fear

## Goal

Replace the in-memory brute-force dense-vector scan
(`InMemoryChunkVectorIndex.topKChunks`) with a pgvector HNSW lookup so search
compute can live on PostgreSQL, freeing ~50 MB of Tomcat heap and removing
the top remaining JFR hotspot (`InMemoryChunkVectorIndex.dotAt`, 744 samples
in Sweep #9). Lock the change behind a backend flag so a recall or latency
regression rolls back in minutes.

## Background

After the search-path optimisation series (Vector API SIMD, listener-mutex
fix, Highlighter flag, SessionEventDispatcher, GC sweep), the dense-vector
brute-force scan remains the top hotspot in every JFR capture at 300 VUs.
The fundamental problem is algorithmic, not micro-optimisable: the index
scans every one of ~12 K chunks per query, dot-products it against a
1024-dim query vector, and heap-maintains a top-K. Vector API trimmed
per-row cost but the work is still O(N × dim) per query.

**Existing exemplar.** `kg_node_embeddings` (V021, 2026-04) already uses
pgvector: `embedding vector(1024)` with `ivfflat (vector_cosine_ops)` and a
`<=>` cosine query in `KgNodeEmbeddingRepository.findTopKByPageEmbedding`.
The pattern works; we extend it to chunks with HNSW instead of IVFFlat
because HNSW has better recall/latency at the 12 K → 300 K row scale we
care about.

**Why not just bigger Tomcat / more cores?** Brute-force scales linearly
with corpus size. At 100 K pages (~300 K chunks), per-query cost is ~25×
today; replicating that workload across N Tomcats just spreads linear cost
N ways. ANN is log-linear and changes the curve shape, not just the
constant. See "Future scale crossover" below.

## Architecture

```
                                            Tomcat (app tier)
   ┌─────────────────────────────────────────────────────────┐
   │  DenseRetriever                                          │
   │    └── ChunkVectorIndex.topKChunks(queryVec, k)          │
   │           ├── InMemoryChunkVectorIndex   (legacy path)   │
   │           └── PgVectorChunkVectorIndex   (new, default   │
   │                  │                          after flag   │
   │                  │                          flip)        │
   └──────────────────┼──────────────────────────────────────┘
                      │  SELECT chunk_id, page_name,
                      │  1 - (embedding <=> ?::vector) AS score
                      │  FROM content_chunk_embeddings ...
                      │  ORDER BY embedding <=> ?::vector LIMIT ?
                      ▼
                  PostgreSQL 18 + pgvector
                      └── content_chunk_embeddings
                              · vec       BYTEA          (legacy, kept during cutover)
                              · embedding vector(1024)   (new)
                              · INDEX hnsw (embedding vector_cosine_ops)
                                       WITH (m=16, ef_construction=64)
```

`ChunkVectorIndex` is already an interface; `InMemoryChunkVectorIndex` is
the sole implementation. Adding `PgVectorChunkVectorIndex` is a drop-in.
`DenseRetriever` is unchanged.

The choice between implementations is driven by a runtime property,
`wikantik.search.dense.backend ∈ {inmemory, pgvector}`. Default ships as
`inmemory` so a fresh deploy carries no risk; ops flips to `pgvector` after
the backfill + smoke gate are green.

## Schema changes

### Migration V032 — add the column and the HNSW index

```sql
-- V032: pgvector HNSW index for chunk dense retrieval.
-- Adds the pgvector column and the HNSW index. The legacy BYTEA `vec`
-- column stays in place during the cutover; it gets dropped in a later
-- migration once the new path has soaked in production. The backfill
-- itself is NOT in this migration (per the no-data-in-migrations rule);
-- a one-shot script populates the column before the flag flips.

ALTER TABLE content_chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding vector(1024);

CREATE INDEX IF NOT EXISTS content_chunk_embeddings_hnsw_idx
    ON content_chunk_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

Creating the HNSW index on an empty column is instant; the index grows
incrementally as the backfill INSERTs/UPDATEs land. HNSW's incremental
build cost is comparable to IVFFlat at our row count.

### One-shot backfill — `bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh`

Reads `vec BYTEA` (little-endian float32) per `(chunk_id, model_code)`,
L2-normalises the float array in Java via a CLI helper, and writes it
back to `embedding vector(1024)` via the pgvector string literal codec
(`"[v1,v2,…]"::vector`). Idempotent — skips rows where `embedding IS NOT NULL`
unless `--force` is passed.

Why a Java helper rather than pure SQL: the BYTEA codec is shared with
`VectorCodec` in the app, and we want a single point that decodes our
little-endian payload. The helper is a 60-line CLI under
`wikantik-extract-cli` (or a one-shot main method, depending on what the
implementation plan finds cleaner).

Runtime: 12 K rows × ~4 KB read/decode/write ≈ under a minute. Backfill
runs offline against the local DB before flag flip; in production it runs
against the live DB while the in-memory backend is still serving queries
(no read/write contention because HNSW writes only land on the `embedding`
column).

### Migration V033 (deferred — runs after the soak)

```sql
-- V033 — to be created after a successful soak of the pgvector backend.
-- Drops the legacy BYTEA columns and tightens the NOT NULL invariant.
ALTER TABLE content_chunk_embeddings
    DROP COLUMN IF EXISTS vec,
    DROP COLUMN IF EXISTS dim,
    ALTER COLUMN embedding SET NOT NULL;
```

V033 is **not** part of the initial implementation. It lands after we've
run with the pgvector backend in production for at least one full ramp
cycle (~one week at the current usage profile) without recall regression
or operational surprise.

## Code changes

### `wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java` (new)

Implements `ChunkVectorIndex`. Stateless beyond `DataSource` + `modelCode`
— no in-memory snapshot, no listener wiring.

```java
public List<ScoredChunk> topKChunks(float[] queryVec, int k) {
    final String sql = """
        SELECT e.chunk_id, c.page_name,
               1.0 - (e.embedding <=> ?::vector) AS score
        FROM content_chunk_embeddings e
        JOIN kg_content_chunks c ON c.id = e.chunk_id
        WHERE e.model_code = ?
        ORDER BY e.embedding <=> ?::vector
        LIMIT ?
        """;
    // try-with-resources Connection + PreparedStatement; bind formatVector(query)
    // once, reuse; build ArrayList<ScoredChunk> from result set.
}
```

Score conversion: pgvector's `<=>` returns `1 - cosine_similarity` (smaller
is closer). We return `1 - <=>` so the existing "larger is better" contract
held by `DenseRetriever` and `HybridFuser` carries over unchanged.

Query-time recall knob: `hnsw.ef_search` is set per-session via
`SET LOCAL hnsw.ef_search = ?` issued on the same connection just before
the SELECT. Defaults to **100**; configurable via
`wikantik.search.dense.pgvector.ef_search`.

Connection holding strategy: try-with-resources, single round-trip per
query. No batching across queries (each search request gets its own
connection from DBCP and releases it within the request lifecycle).

`isReady()`: returns true if a `SELECT 1 FROM content_chunk_embeddings
WHERE model_code = ? AND embedding IS NOT NULL LIMIT 1` returns a row. The
check runs once at startup and caches the result; flips back to "not
ready" only on DataSource failure (which `SearchSubsystem` already treats
as fail-closed to BM25 fallback).

`dimension()`: returns 1024 (hard-coded; we don't run multi-dim).

`size()`: optional metric helper; uses a cached count refreshed every
5 minutes so the metrics scrape doesn't hammer PG. Bridge it into
`HybridMetricsBridge` so `wikantik_search.vector_index.size` keeps
reporting.

### `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemFactory.java`

Chooses between backends based on the property:

```java
final ChunkVectorIndex chunkVectorIndex;
final String backend = props.getProperty(
    "wikantik.search.dense.backend", "inmemory").toLowerCase(Locale.ROOT);
switch (backend) {
    case "pgvector" -> {
        chunkVectorIndex = new PgVectorChunkVectorIndex(dataSource, modelCode, efSearch);
        LOG.info("Dense retrieval backend: pgvector HNSW (ef_search={})", efSearch);
    }
    case "inmemory" -> {
        chunkVectorIndex = engine.getManager(InMemoryChunkVectorIndex.class);
        LOG.info("Dense retrieval backend: in-memory brute-force");
    }
    default -> throw new IllegalArgumentException(
        "wikantik.search.dense.backend must be inmemory|pgvector, got: " + backend);
}
```

`HybridMetricsBridge` already accepts the interface; the size-gauge
registration becomes type-conditional rather than `instanceof
InMemoryChunkVectorIndex` only.

### `wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingIndexService.java`

The UPSERT path becomes a **dual write** during cutover:

```sql
INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec, embedding)
VALUES (?, ?, ?, ?, ?::vector)
ON CONFLICT (chunk_id, model_code) DO UPDATE SET
    vec       = EXCLUDED.vec,
    dim       = EXCLUDED.dim,
    embedding = EXCLUDED.embedding,
    updated   = NOW();
```

Writes carry both representations so either backend stays correct during
the soak. After V033 the BYTEA columns disappear and the INSERT shrinks
accordingly.

Vectors continue to be written **un-normalised** to the new column;
pgvector's `vector_cosine_ops` handles normalisation internally. (We
explicitly skip the `vector_ip_ops` optimisation — it would require
write-side normalisation, an invariant easy to break, and at our scale the
cosine-op cost is negligible.)

### `wikantik-main/src/main/java/com/wikantik/search/embedding/AsyncEmbeddingIndexListener.java`

When the pgvector backend is selected, the listener **no longer calls
`upsertChunks` on an in-memory index** — there is no in-memory index. The
INSERT in `EmbeddingIndexService` is the only update path needed, because
PG indexes the new row immediately. The listener becomes a no-op for the
post-write reload step under `pgvector`; the embedding write itself stays.

(Under `inmemory`, listener behaviour is unchanged — we keep the path
intact for rollback.)

## Cutover plan

1. **Implement** — code lands behind the flag, default `inmemory`. Full IT
   reactor green. Commit.
2. **Migrate dev DB** — run V032 locally, run the backfill script, confirm
   `embedding` column populated and HNSW index exists. Smoke `psql` an
   `ORDER BY embedding <=> '[…]'::vector LIMIT 200` against a known query
   and eyeball the top results.
3. **Recall smoke gate** — flip flag to `pgvector` in dev, run
   `RetrievalQualitySmokeTest`. Require nDCG@5 within 0.02 of the in-memory
   baseline. If it fails, bump `ef_search` (100 → 200 → 400) and re-run;
   if still failing past 400, the design's premise is wrong and we
   investigate before going further.
4. **Production migration** — apply V032 on docker1 PG; run the backfill
   one-shot script while the in-memory backend keeps serving queries
   (writes go to both columns once code is deployed). Confirm
   `embedding IS NULL` count is zero.
5. **Flag flip in production** — update `.env.prod`, redeploy. Monitor
   `wikantik_search_*` Prometheus metrics + p95 latency for 24 hours.
6. **Soak** — leave it running for ~one week. If green, schedule V033 to
   drop the legacy columns. If not green, roll back (next section).

## Rollback

Two layers of rollback, in increasing severity:

1. **Flag flip back to `inmemory`.** Restart Tomcat. The legacy in-memory
   index reloads from the BYTEA column on startup (still present, still
   dual-written) and resumes serving. ~30 seconds of search latency during
   the index warm-up, then identical pre-change behaviour. **No data
   change required**, no migration to reverse.
2. **Drop V032.** Only needed if pgvector itself misbehaves operationally
   (lock contention, replication lag from the HNSW index, runaway disk
   growth). Sequence: flag back to `inmemory`, `DROP INDEX
   content_chunk_embeddings_hnsw_idx`, `ALTER TABLE … DROP COLUMN
   embedding`. The BYTEA column is untouched throughout.

The dual-write window is the rollback insurance. We pay it back when we
ship V033.

## Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| HNSW recall regression vs brute-force baseline | Medium | `RetrievalQualitySmokeTest` gate before flag flip; tune `ef_search` |
| Round-trip latency dominates at low corpus sizes | Low at LAN, possible if DB moves remote | Measure p50/p95 dense latency via JFR + Prometheus; if RTT > 5 ms, revisit |
| DBCP connection saturation under load | Low at today's scale, possible at 5 K RPS | Monitor `pg_stat_database_numbackends` + DBCP metrics during ramp; bump `maxTotal` if needed |
| HNSW index disk growth | Low | ~7 MB at 12 K rows, ~175 MB at 300 K — well within budget |
| HNSW build stalls a long write batch | Low | Backfill runs in a single off-peak session; subsequent writes are single-row, indexed incrementally |
| pgvector version mismatch (we run pg18 + pgvector but the prod image needs the extension installed) | Low — pgvector is already in the image per V021 | Verified via `\dx` in production |

## Observability

Add three Prometheus metrics during this work:

- `wikantik_search_dense_backend{backend="inmemory|pgvector"}` — gauge,
  always 1 for the active backend, 0 for the inactive. Lets dashboards
  show which path served the request set.
- `wikantik_search_dense_latency_seconds{backend, phase="query|fetch"}` —
  histogram. Replaces the implicit "InMemoryChunkVectorIndex.dotAt JFR
  sample count" we've been using as a proxy.
- `wikantik_search_dense_recall_at_5{backend}` — gauge published by the
  `DefaultRetrievalQualityRunner` nightly run, scoped per backend so we
  can see drift over time.

## Future scale crossover

At today's scale (12 K chunks, 357 RPS peak), pgvector HNSW shifts ~2.5
cores of CPU from Tomcat to PG with a ~3× efficiency win. Modest.

At the hypothetical 100 K pages / 5 K concurrent users scale (~300 K
chunks):

- Brute-force in-memory: ~50 ms/query, ~250 cores of dot-product CPU at
  peak. Replicating the index across N Tomcats spreads but doesn't reduce
  this linear cost.
- HNSW in-memory (Lucene / JVector): ~5 ms/query, ~25 cores cluster-wide.
  Memory cost: 1.5 GB per Tomcat × N nodes; listener fan-out for updates;
  warm-up cost on new instance launch.
- **HNSW on pgvector: ~5 ms/query + 1–3 ms LAN RTT, ~25 cores on PG.**
  Stateless Tomcats; single source of truth for index updates; horizontal
  scaling via PG read replicas. The replicated-in-memory option becomes
  preferable if the RTT becomes visible in p99 (sub-10 ms human-search
  SLO) or if PG read-replica saturation appears as the new bottleneck.

We commit to pgvector now because (a) RAG/agent latency floors are
hundreds of ms (RTT is invisible), (b) editing is write-heavy enough that
listener-driven cross-Tomcat fan-out is brittle, and (c) "add a Tomcat
instantly" matters for ops simplicity. The `ChunkVectorIndex` interface
seam keeps a future `LuceneHnswChunkVectorIndex` viable if those
assumptions flip.

## Out of scope

- Approximate **graph** rerank (`InMemoryGraphNeighborIndex`). Same
  in-memory snapshot pattern as the chunk index, but smaller (per-page
  neighbour lists, not 1024-dim vectors); not a current hotspot.
- Multi-model A/B at the row level. The flag is single-backend single-model
  for v1. Future work could route per-query to a specific `model_code`.
- pgvector `vector_ip_ops` + write-side L2 normalisation. Negligible win
  at our scale, invariant easy to break. Reconsider if HNSW becomes a
  hotspot in its own right.

## Implementation surface (preview for the plan)

- **New code**: `PgVectorChunkVectorIndex.java` + unit + IT against pg18 +
  pgvector container.
- **Modified code**: `SearchSubsystemFactory.java`,
  `EmbeddingIndexService.java` (UPSERT dual-write), `AsyncEmbeddingIndexListener.java`
  (pgvector branch is a no-op), `HybridMetricsBridge.java` (size-gauge
  type guard).
- **New migration**: `V032__chunk_embeddings_pgvector_hnsw.sql`.
- **New script**: `bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh`
  with a Java decoder helper.
- **New config**: `wikantik.search.dense.backend` (default `inmemory`),
  `wikantik.search.dense.pgvector.ef_search` (default `100`).
- **Tests**: unit tests for `PgVectorChunkVectorIndex` ordering +
  score-conversion + ef_search wiring; IT against pgvector verifying
  parity with `InMemoryChunkVectorIndex` on a seeded corpus;
  `RetrievalQualitySmokeTest` extended to run both backends and assert
  parity.
- **Docs**: `CLAUDE.md` mention under "Hybrid Retrieval"; this spec
  referenced.

## Self-review notes

- **Placeholder scan**: no TBDs. Migration numbers, parameter values,
  metric names, threshold (nDCG@5 ± 0.02) all concrete.
- **Internal consistency**: backend flag default `inmemory` → cutover
  flips → V033 drops legacy columns. Rollback returns to `inmemory` and
  legacy columns are still there. Consistent end-to-end.
- **Scope check**: single subsystem (dense retrieval). One migration plus
  one deferred follow-up. Suitable for a single implementation plan.
- **Ambiguity check**: `ef_search` knob is explicit; recall threshold is
  explicit; score-direction convention is documented. No ambiguity that
  could yield two implementations.
