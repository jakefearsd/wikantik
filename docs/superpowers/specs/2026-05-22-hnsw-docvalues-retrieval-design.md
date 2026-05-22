# Lucene HNSW — DocValues Metadata Retrieval (optimization)

**Date:** 2026-05-22
**Status:** Approved, pending implementation
**Author:** Jake Fear (with Claude)
**Builds on:** [2026-05-22-lucene-hnsw-dense-retrieval-design.md](2026-05-22-lucene-hnsw-dense-retrieval-design.md)

## Goal

Eliminate the stored-fields retrieval cost that is currently negating the Lucene
HNSW backend's vector-math savings, by retrieving each candidate's `chunk_id`
and `page_name` via **DocValues** instead of Lucene **stored fields**.

## Motivation (measured)

After deploying the `lucene-hnsw` backend to prod and re-profiling at N=350 and
N=650 (JFR over 540s steady state), the brute-force dot product
(`InMemoryChunkVectorIndex.dotAt`, ~49% CPU) was successfully replaced by HNSW
search (`PanamaVectorUtilSupport.cosineBody` + `HnswGraphSearcher.searchLevel` +
heap ≈ **13%** CPU). But a new cost dominated: reading `chunk_id`/`page_name`
back as Lucene **stored fields** in `topKChunks` decompresses an LZ4 stored-field
block per candidate — `ByteBuffersDataInput.readBytes` (23.7%) + `LZ4.decompress`
(10.1%) + `BufferedIndexInput.readBytes` (7.6%) ≈ **~44% of CPU**.

Net effect at the load ceiling:

| Metric | inmemory | lucene-hnsw (stored fields) |
|--------|---------:|----------------------------:|
| N=650 throughput | 480 req/s | 445 req/s (−7%) |
| N=650 avg latency | 348 ms | 290 ms (−17%) |
| N=650 p95 | 1.25 s | 1.12 s (−10%) |
| N=650 max (tail) | 27.96 s | 8.84 s (−68%) |

The latency tail improved markedly, but peak throughput regressed because the
stored-fields decompression replaced the dot-product CPU rather than removing it.

## Design

Stored fields are the wrong codec for per-hit random-access retrieval of a few
small attributes. The canonical Lucene idiom is **DocValues** — columnar,
memory-resident for a RAM `ByteBuffersDirectory`, no per-read block
decompression.

### Index-time (in `addOrReplace`)

Each chunk document carries:

- `KnnFloatVectorField( "vec", vec, COSINE )` — unchanged.
- `StringField( "chunk_id", chunkId.toString(), Field.Store.NO )` — kept
  **indexed only** so `updateDocument`/`deleteDocuments` by `Term("chunk_id", …)`
  still works for upsert/delete. No longer stored.
- `SortedDocValuesField( "chunk_id", new BytesRef( chunkId.toString() ) )` — for
  retrieval.
- `SortedDocValuesField( "page_name", new BytesRef( pageName ) )` — for retrieval.

No field uses `Field.Store.YES`. The stored-fields reader is therefore never
exercised on the query path.

> A Lucene field may carry both an indexed `StringField` and a
> `SortedDocValuesField` under the same name (`"chunk_id"`) — postings drive the
> delete term; docvalues drive retrieval.

### Query-time (in `topKChunks`)

After `searcher.search( knnQuery, fetch )` returns global doc ids, resolve each
hit's metadata through DocValues rather than `storedFields.document()`:

```
leaves = searcher.getIndexReader().leaves()
for each ScoreDoc sd (until k collected):
    leafIdx  = ReaderUtil.subIndex( sd.doc, leaves )
    ctx      = leaves.get( leafIdx )
    localDoc = sd.doc - ctx.docBase
    cidDv    = ctx.reader().getSortedDocValues( "chunk_id" )
    pgDv     = ctx.reader().getSortedDocValues( "page_name" )
    if cidDv.advanceExact( localDoc ):
        chunkId = cidDv.lookupOrd( cidDv.ordValue() ).utf8ToString()
    if pgDv.advanceExact( localDoc ):
        pageName = pgDv.lookupOrd( pgDv.ordValue() ).utf8ToString()
    score = 2*sd.score - 1   // unchanged COSINE→cosine mapping
    out.add( new ScoredChunk( UUID.fromString( chunkId ), pageName, score ) )
```

Per-leaf DocValues handles are cheap to acquire; acquiring them once per leaf and
reusing across hits in that leaf is a fine micro-optimization but not required at
k ≤ ~100. APIs (verified against Lucene 10.4.0): `SortedDocValuesField(String,
BytesRef)`, `ReaderUtil.subIndex(int, List<LeafReaderContext>)`,
`LeafReaderContext.docBase` / `.reader()`, `SortedDocValues.advanceExact(int)` /
`.ordValue()` / `.lookupOrd(int)`.

### Unchanged

Backend selection, wiring, metrics, `HnswParams`, score mapping, fail-closed
discipline, the DB load/upsert SQL, and the bytea source of truth are all
unchanged. This is purely a swap of *how two attributes are stored and read back*
inside `LuceneHnswChunkVectorIndex`.

## Error handling

If a DocValues lookup fails or `advanceExact` returns false for a hit (should not
happen for a doc the KNN query just returned), skip that hit with a `LOG.warn`
(per repo rule — no silent skips) and continue; never throw out of `topKChunks`.
The existing IOException fail-closed-to-empty behavior is retained.

## Testing

- The existing `LuceneHnswChunkVectorIndexTest` cases (ranking, score scale,
  upsert-replace, delete, empty fail-closed, DB build, corrupt-row) must all
  still pass unchanged — they assert on `chunkId`/`pageName`/`score`, which the
  DocValues path must reproduce identically.
- The `RetrievalQualitySmokeTest` parity gate (lucene-hnsw within 0.02 nDCG@5 of
  brute-force, real pgvector container) must still pass — retrieval correctness
  is unchanged.
- No new test shape is required; the change is validated by the existing
  behavioral tests plus a re-profile.

## Validation / rollout

1. Unit + parity tests green.
2. Full IT reactor green.
3. Redeploy to docker1 (prod already on `lucene-hnsw`; this is an image swap, no
   config change).
4. Re-profile at N=350 and N=650 — expect the `lucene_storedfields` /
   `LZ4.decompress` theme to collapse from ~44% to near-zero, and N=650
   throughput to rise above the 480 req/s brute-force baseline while retaining
   the improved latency tail.

Rollback remains the one-line `WIKANTIK_DENSE_BACKEND=inmemory` flip.

## Risks

| Risk | Mitigation |
|------|------------|
| DocValues lookup returns wrong value vs stored fields | Existing behavioral tests assert exact chunkId/pageName/score; parity gate confirms ranking |
| `chunk_id` as both StringField + SortedDocValuesField misbehaves | Standard Lucene support; delete-by-term test (`deleteRemovesChunk`) covers the postings side, retrieval tests cover the docvalues side |
| Per-leaf DocValues acquisition overhead | Negligible at k ≤ ~100; single RAM segment in practice |
