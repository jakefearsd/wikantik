# HNSW DocValues Metadata Retrieval — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Replace the Lucene stored-fields retrieval of `chunk_id`/`page_name` in `LuceneHnswChunkVectorIndex` with DocValues, eliminating the ~44%-CPU LZ4 block decompression on the query hot path.

**Architecture:** Index `chunk_id` as an indexed `StringField(Store.NO)` (delete term) plus a `SortedDocValuesField`, and `page_name` as a `SortedDocValuesField`. Retrieve both at query time via per-leaf `SortedDocValues` lookups (`ReaderUtil.subIndex` → `LeafReaderContext` → `advanceExact`/`lookupOrd`) instead of `storedFields.document()`. No stored fields remain.

**Tech Stack:** Java 21, Lucene 10.4 (`SortedDocValuesField`, `SortedDocValues`, `ReaderUtil`, `LeafReaderContext`, `BytesRef`).

**Spec:** `docs/superpowers/specs/2026-05-22-hnsw-docvalues-retrieval-design.md`

---

## Task 1: Swap stored fields → DocValues in `LuceneHnswChunkVectorIndex`

**File:** `wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java`
**Tests:** `wikantik-main/src/test/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndexTest.java` (existing — must keep passing unchanged)

This is a refactor with an existing behavioral safety net: the 7 existing tests assert exact `chunkId`/`pageName`/`score` and upsert/delete/fail-closed behavior. They must all keep passing with no edits to the test file. TDD here = run the existing tests red→green around the change (they should stay green; if any goes red, the DocValues path differs behaviorally and must be fixed).

- [ ] **Step 1: Confirm the existing tests pass before the change (baseline)**

Run: `mvn -q -pl wikantik-main test -Dtest=LuceneHnswChunkVectorIndexTest`
Then: `grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' wikantik-main/target/surefire-reports/TEST-*LuceneHnswChunkVectorIndexTest.xml`
Expected: `tests="7" ... failures="0" errors="0"`.

- [ ] **Step 2: Update imports**

In the import block, ADD:
```java
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.util.BytesRef;
```
REMOVE (no longer used after this change):
```java
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.StoredFields;
```
Keep `Document`, `Field`, `StringField`, `KnnFloatVectorField`, `IndexSearcher`, `KnnFloatVectorQuery`, `ScoreDoc`, `TopDocs`, `List`, `ArrayList`, `UUID`, etc. (After editing, verify no other code references `StoredField`/`StoredFields` with grep before removing the imports.)

- [ ] **Step 3: Change `addOrReplace` document construction**

Replace this block (currently ~lines 171-175):
```java
            final Document doc = new Document();
            doc.add( new KnnFloatVectorField( FIELD_VEC, vec, VectorSimilarityFunction.COSINE ) );
            doc.add( new StringField( FIELD_CHUNK_ID, chunkId.toString(), Field.Store.YES ) );
            doc.add( new StoredField( FIELD_PAGE, pageName == null ? "" : pageName ) );
            writer.updateDocument( new Term( FIELD_CHUNK_ID, chunkId.toString() ), doc );
```
with:
```java
            final String safePage = pageName == null ? "" : pageName;
            final Document doc = new Document();
            doc.add( new KnnFloatVectorField( FIELD_VEC, vec, VectorSimilarityFunction.COSINE ) );
            // Indexed (NOT stored) so updateDocument/deleteDocuments by the chunk_id
            // term still works for upsert/delete.
            doc.add( new StringField( FIELD_CHUNK_ID, chunkId.toString(), Field.Store.NO ) );
            // DocValues for cheap per-hit retrieval — columnar, no stored-field LZ4
            // block decompression on the query path.
            doc.add( new SortedDocValuesField( FIELD_CHUNK_ID, new BytesRef( chunkId.toString() ) ) );
            doc.add( new SortedDocValuesField( FIELD_PAGE, new BytesRef( safePage ) ) );
            writer.updateDocument( new Term( FIELD_CHUNK_ID, chunkId.toString() ), doc );
```

- [ ] **Step 4: Change `topKChunks` retrieval loop to read DocValues**

Replace this block (currently ~lines 226-238):
```java
            final TopDocs hits = searcher.search( query, fetch );
            final StoredFields stored = searcher.storedFields();
            final List< ScoredChunk > out = new ArrayList<>( Math.min( k, hits.scoreDocs.length ) );
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                if ( out.size() >= k ) break;
                final Document doc = stored.document( sd.doc );
                final String idStr = doc.get( FIELD_CHUNK_ID );
                final String page = doc.get( FIELD_PAGE );
                if ( idStr == null ) continue;
                final double cosine = 2.0 * sd.score - 1.0;
                out.add( new ScoredChunk( UUID.fromString( idStr ), page, cosine ) );
            }
            return out;
```
with:
```java
            final TopDocs hits = searcher.search( query, fetch );
            final List< LeafReaderContext > leaves = searcher.getIndexReader().leaves();
            final List< ScoredChunk > out = new ArrayList<>( Math.min( k, hits.scoreDocs.length ) );
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                if ( out.size() >= k ) break;
                final LeafReaderContext ctx = leaves.get( ReaderUtil.subIndex( sd.doc, leaves ) );
                final int localDoc = sd.doc - ctx.docBase;
                // Fresh SortedDocValues per hit: KNN hits are score-ordered, not
                // docid-ordered, and advanceExact only seeks forward — a per-hit
                // iterator avoids "went backwards" errors across non-monotonic docids.
                final SortedDocValues cidDv = ctx.reader().getSortedDocValues( FIELD_CHUNK_ID );
                if ( cidDv == null || !cidDv.advanceExact( localDoc ) ) {
                    LOG.warn( "Lucene HNSW: no chunk_id docvalue for doc {}, skipping", sd.doc );
                    continue;
                }
                final String idStr = cidDv.lookupOrd( cidDv.ordValue() ).utf8ToString();
                final SortedDocValues pgDv = ctx.reader().getSortedDocValues( FIELD_PAGE );
                final String page = ( pgDv != null && pgDv.advanceExact( localDoc ) )
                    ? pgDv.lookupOrd( pgDv.ordValue() ).utf8ToString()
                    : "";
                final double cosine = 2.0 * sd.score - 1.0;
                out.add( new ScoredChunk( UUID.fromString( idStr ), page, cosine ) );
            }
            return out;
```
Note: `advanceExact` throws `IOException`, which the method's existing `catch ( final IOException e )` already handles (fail-closed to empty). No new catch needed.

- [ ] **Step 5: Run the existing test suite — must stay green unchanged**

Run: `mvn -q -pl wikantik-main test -Dtest=LuceneHnswChunkVectorIndexTest`
Then: `grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' wikantik-main/target/surefire-reports/TEST-*LuceneHnswChunkVectorIndexTest.xml`
Expected: `tests="7" ... failures="0" errors="0"`. The `ranksNearestVectorFirstAndMapsCosineScore`, `upsertReplacesVectorForSameChunkId`, `deleteRemovesChunk`, `buildsFromDatabaseRows`, and `corruptRowIsSkipped...` cases all exercise the new retrieval + the delete term.

- [ ] **Step 6: Verify no stored-fields references remain**

Run: `grep -nE "StoredField|storedFields|Store.YES" wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java`
Expected: no output (all stored-field usage removed; the field is now `Store.NO` + DocValues).

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java
git commit -m "perf: retrieve HNSW chunk metadata via DocValues, not stored fields

Reading chunk_id/page_name back as Lucene stored fields decompressed an LZ4
block per candidate (~44% CPU under load). Store them as SortedDocValues
(columnar, no decompression) and read via per-leaf SortedDocValues lookups;
keep chunk_id as an indexed StringField(Store.NO) only for the delete term."
```

---

## Task 2: Parity gate + full IT verification

**Files:** none (verification).

- [ ] **Step 1: Re-run the retrieval-quality parity gate**

Run: `mvn -q -pl wikantik-main test -Dtest=RetrievalQualitySmokeTest`
Then: `grep -oE 'tests="[0-9]+" failures="[0-9]+" errors="[0-9]+"' wikantik-main/target/surefire-reports/TEST-*RetrievalQualitySmokeTest.xml`
Expected: `tests="6" failures="0" errors="0"` — lucene-hnsw still within 0.02 nDCG@5 of brute-force (retrieval correctness unchanged by the storage swap).

- [ ] **Step 2: Full IT reactor (sequential, fail-at-end)**

First clear any stale pgvector container (port 55432):
```bash
docker ps -a --filter "publish=55432" -q | xargs -r docker rm -f
```
Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS, 0 failures across all IT submodules.

---

## Task 3: Redeploy + re-profile (controller-run ops)

**Files:** none.

- [ ] **Step 1: Redeploy to docker1**

Prod is already on `lucene-hnsw`; this is an image swap, no config change.
```bash
bin/remote.sh deploy
```
Expected: `Deploy healthy: http://docker1:8080/api/health returned 200.` Boot log still shows `Dense retrieval backend: Lucene HNSW (... size=12252)`.

- [ ] **Step 2: Warm + N=350 profile**

```bash
BASE_URL=http://docker1:8080 bin/loadtest.sh load --vus 8 --duration 2m        # warmup
# then the 10-min steady N=350 run + a 540s JFR over steady state
```
Expected in the JFR: the `lucene_storedfields` / `LZ4.decompress` theme collapses from ~44% to near-zero; the dense-retriever CPU drops toward the ~13% HNSW-search floor.

- [ ] **Step 3: Comparative N=650 run**

```bash
BASE_URL=http://docker1:8080 bin/loadtest.sh load --vus 650 --duration 10m
```
Expected: throughput rises above the 480 req/s brute-force baseline while retaining the improved latency tail (prior HNSW: max 8.84s vs brute-force 27.96s).

- [ ] **Step 4: Push** (after the user reviews the re-profile result and confirms)

```bash
git push origin main
```

---

## Self-Review

**Spec coverage:** DocValues for both fields (Task 1 Step 3); per-leaf retrieval via ReaderUtil.subIndex/advanceExact (Step 4); chunk_id kept indexed Store.NO for delete term (Step 3); no stored fields remain (Step 6); existing behavioral tests + parity gate as the safety net (Task 1 Step 5, Task 2); re-profile validation (Task 3). All covered.

**Placeholder scan:** none — every code step shows exact before/after.

**Type consistency:** `FIELD_CHUNK_ID`/`FIELD_PAGE` constants reused for both the StringField/Term and the SortedDocValuesField names; `SortedDocValues.advanceExact(int)`/`ordValue()`/`lookupOrd(int)` and `ReaderUtil.subIndex(int, List<LeafReaderContext>)` match the verified Lucene 10.4 API; `ScoredChunk(UUID, String, double)` unchanged.
