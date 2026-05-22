# Lucene HNSW Dense-Retrieval Backend — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-process Lucene HNSW approximate-nearest-neighbour backend for dense chunk retrieval, selectable via `wikantik.search.dense.backend=lucene-hnsw`, to replace the brute-force in-memory scan that consumes ~60% of CPU under load.

**Architecture:** A new `LuceneHnswChunkVectorIndex implements ChunkVectorIndex` holds a RAM-resident Lucene index (`ByteBuffersDirectory`), one document per chunk carrying a `KnnFloatVectorField`. It is built at boot from `content_chunk_embeddings` and kept current via the existing `AsyncEmbeddingIndexListener` post-index callback. Queries run `KnnFloatVectorQuery`. The class plugs into the same backend switch (`SearchSubsystemFactory`) and live wiring (`SearchWiringHelper`) the `inmemory`/`pgvector` backends already use. No DB migration, no new Maven dependency (Lucene 10.4 already present).

**Tech Stack:** Java 21, Lucene 10.4 (`Lucene104Codec`, `Lucene99HnswVectorsFormat`, `KnnFloatVectorField`, `KnnFloatVectorQuery`, `SearcherManager`, `ByteBuffersDirectory`), PostgreSQL (`content_chunk_embeddings`), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-05-22-lucene-hnsw-dense-retrieval-design.md`

---

## File Structure

| File | Responsibility |
|------|----------------|
| `wikantik-main/.../search/hybrid/ChunkVectorBytes.java` (new) | Shared little-endian `byte[]→float[]` decode; one home for the codec used by every backend |
| `wikantik-main/.../search/hybrid/HnswParams.java` (new) | Value record for `m` / `ef_construction` / `ef_search`, parsed from `Properties` |
| `wikantik-main/.../search/hybrid/LuceneHnswChunkVectorIndex.java` (new) | The backend: RAM Lucene index, build/upsert/delete/query, score mapping, fail-closed |
| `wikantik-main/.../search/subsystem/SearchSubsystemFactory.java` (modify) | Add `lucene-hnsw` switch case + widen the unknown-backend message |
| `wikantik-main/.../search/subsystem/SearchWiringHelper.java` (modify) | Live wiring: construct index, reload hook, upsert callback, metrics |
| `wikantik-main/.../search/hybrid/InMemoryChunkVectorIndex.java` (modify) | Delegate its private `decodeVector` to `ChunkVectorBytes` (DRY) |
| `ini/wikantik.properties` (modify) | Documented defaults for the three new keys |
| `docs/wikantik-pages/HybridRetrieval.md` (modify) | Document the `lucene-hnsw` backend + knobs |
| `wikantik-main/.../test/.../hybrid/ChunkVectorBytesTest.java` (new) | Decode helper unit tests |
| `wikantik-main/.../test/.../hybrid/HnswParamsTest.java` (new) | Params parsing unit tests |
| `wikantik-main/.../test/.../hybrid/LuceneHnswChunkVectorIndexTest.java` (new) | Core index unit tests (no DB) + DB-load test (mocked DataSource) |
| `wikantik-main/.../test/.../subsystem/SearchSubsystemFactoryTest.java` (modify) | `lucene-hnsw` selection test |

---

## Task 1: Extract the shared vector-decode helper

DRY: `InMemoryChunkVectorIndex.decodeVector` is the only little-endian `bytea→float[]` decoder today; the Lucene backend needs the identical logic. Extract it so both share one implementation.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/hybrid/ChunkVectorBytes.java`
- Create: `wikantik-main/src/test/java/com/wikantik/search/hybrid/ChunkVectorBytesTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/InMemoryChunkVectorIndex.java:398-417`

- [ ] **Step 1: Write the failing test**

`ChunkVectorBytesTest.java`:
```java
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChunkVectorBytesTest {

    private static byte[] encodeLE( final float[] v ) {
        final ByteBuffer bb = ByteBuffer.allocate( v.length * Float.BYTES ).order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : v ) bb.putFloat( f );
        return bb.array();
    }

    @Test
    void roundTripsLittleEndianFloats() {
        final float[] expected = { 1.0f, -2.5f, 3.25f, 0.0f };
        final float[] out = ChunkVectorBytes.decode( UUID.randomUUID(), encodeLE( expected ), 4 );
        assertArrayEquals( expected, out, 0.0f );
    }

    @Test
    void returnsNullForNullBytes() {
        assertNull( ChunkVectorBytes.decode( UUID.randomUUID(), null, 4 ) );
    }

    @Test
    void throwsOnByteLengthMismatch() {
        final byte[] tooShort = new byte[ 3 * Float.BYTES ];
        assertThrows( IllegalStateException.class,
            () -> ChunkVectorBytes.decode( UUID.randomUUID(), tooShort, 4 ) );
    }
}
```

- [ ] **Step 2: Run it to confirm it fails (class missing)**

Run: `mvn -q -pl wikantik-main test -Dtest=ChunkVectorBytesTest`
Expected: compile failure — `ChunkVectorBytes` does not exist.

- [ ] **Step 3: Create the helper with the exact logic moved from `InMemoryChunkVectorIndex`**

`ChunkVectorBytes.java`:
```java
package com.wikantik.search.hybrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.UUID;

/**
 * Decodes a chunk embedding stored as a little-endian float32 {@code bytea}
 * ({@code content_chunk_embeddings.vec}) into a {@code float[]}. Shared by every
 * {@link ChunkVectorIndex} backend so the on-disk codec lives in exactly one place.
 */
public final class ChunkVectorBytes {

    private static final Logger LOG = LogManager.getLogger( ChunkVectorBytes.class );

    private ChunkVectorBytes() {}

    /**
     * @param id  chunk id, for log context only
     * @param raw little-endian float32 bytes ({@code dim * 4} long), or {@code null}
     * @param dim declared vector dimension
     * @return decoded vector, or {@code null} if {@code raw} was {@code null}
     * @throws IllegalStateException if {@code raw.length != dim * 4}
     */
    public static float[] decode( final UUID id, final byte[] raw, final int dim ) {
        if ( raw == null ) {
            LOG.warn( "ChunkVectorBytes: chunk {} has null vec, skipping", id );
            return null;
        }
        if ( raw.length != dim * Float.BYTES ) {
            LOG.warn( "ChunkVectorBytes: chunk {} vec bytes={} expected {} (dim={})",
                id, raw.length, dim * Float.BYTES, dim );
            throw new IllegalStateException( "Corrupt vector bytes for chunk " + id
                + ": got " + raw.length + " bytes, expected " + ( dim * Float.BYTES ) );
        }
        final float[] out = new float[ dim ];
        final FloatBuffer fb = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer();
        fb.get( out );
        return out;
    }
}
```

- [ ] **Step 4: Make `InMemoryChunkVectorIndex` delegate**

In `InMemoryChunkVectorIndex.java`, replace the body of the private `decodeVector` method (lines ~398-417) with a delegation, keeping its existing call sites unchanged:
```java
    private static float[] decodeVector( final UUID id, final byte[] raw, final int dim ) {
        return ChunkVectorBytes.decode( id, raw, dim );
    }
```

- [ ] **Step 5: Run helper + InMemory tests**

Run: `mvn -q -pl wikantik-main test -Dtest=ChunkVectorBytesTest,InMemoryChunkVectorIndexTest,InMemoryChunkVectorIndexVectorDotTest`
Expected: PASS (helper green; InMemory unchanged behaviour).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/ChunkVectorBytes.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/ChunkVectorBytesTest.java \
        wikantik-main/src/main/java/com/wikantik/search/hybrid/InMemoryChunkVectorIndex.java
git commit -m "refactor: extract ChunkVectorBytes little-endian vector decoder"
```

---

## Task 2: HNSW parameters record

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/hybrid/HnswParams.java`
- Create: `wikantik-main/src/test/java/com/wikantik/search/hybrid/HnswParamsTest.java`

- [ ] **Step 1: Write the failing test**

`HnswParamsTest.java`:
```java
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class HnswParamsTest {

    @Test
    void usesDefaultsWhenAbsent() {
        final HnswParams p = HnswParams.fromProperties( new Properties() );
        assertEquals( 16, p.m() );
        assertEquals( 64, p.efConstruction() );
        assertEquals( 100, p.efSearch() );
    }

    @Test
    void readsOverrides() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.lucene.m", "32" );
        props.setProperty( "wikantik.search.dense.lucene.ef_construction", "128" );
        props.setProperty( "wikantik.search.dense.lucene.ef_search", "200" );
        final HnswParams p = HnswParams.fromProperties( props );
        assertEquals( 32, p.m() );
        assertEquals( 128, p.efConstruction() );
        assertEquals( 200, p.efSearch() );
    }

    @Test
    void rejectsNonPositive() {
        assertThrows( IllegalArgumentException.class, () -> new HnswParams( 0, 64, 100 ) );
        assertThrows( IllegalArgumentException.class, () -> new HnswParams( 16, 0, 100 ) );
        assertThrows( IllegalArgumentException.class, () -> new HnswParams( 16, 64, 0 ) );
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=HnswParamsTest`
Expected: compile failure — `HnswParams` missing.

- [ ] **Step 3: Implement**

`HnswParams.java`:
```java
package com.wikantik.search.hybrid;

import java.util.Properties;

/**
 * Tuning parameters for the Lucene HNSW dense backend. Defaults mirror the
 * pgvector backend's HNSW index ({@code m=16}, {@code ef_construction=64}) and
 * its query-time {@code ef_search=100}.
 */
public record HnswParams( int m, int efConstruction, int efSearch ) {

    public HnswParams {
        if ( m <= 0 ) throw new IllegalArgumentException( "m must be positive, got " + m );
        if ( efConstruction <= 0 ) {
            throw new IllegalArgumentException( "efConstruction must be positive, got " + efConstruction );
        }
        if ( efSearch <= 0 ) throw new IllegalArgumentException( "efSearch must be positive, got " + efSearch );
    }

    public static HnswParams fromProperties( final Properties props ) {
        return new HnswParams(
            intProp( props, "wikantik.search.dense.lucene.m", 16 ),
            intProp( props, "wikantik.search.dense.lucene.ef_construction", 64 ),
            intProp( props, "wikantik.search.dense.lucene.ef_search", 100 ) );
    }

    private static int intProp( final Properties props, final String key, final int dflt ) {
        final String raw = props == null ? null : props.getProperty( key );
        if ( raw == null || raw.isBlank() ) return dflt;
        return Integer.parseInt( raw.trim() );
    }
}
```

- [ ] **Step 4: Run the test to confirm pass**

Run: `mvn -q -pl wikantik-main test -Dtest=HnswParamsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/HnswParams.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/HnswParamsTest.java
git commit -m "feat: HnswParams record for Lucene HNSW dense backend tuning"
```

---

## Task 3: `LuceneHnswChunkVectorIndex` core (in-memory, no DB)

The testable heart of the backend: RAM Lucene index, document add/replace/delete, KNN query, score mapping, fail-closed. No DB dependency — built directly from supplied vectors so it unit-tests without a database.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java`
- Create: `wikantik-main/src/test/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndexTest.java`

- [ ] **Step 1: Write the failing test (core behaviour, no DB)**

`LuceneHnswChunkVectorIndexTest.java`:
```java
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LuceneHnswChunkVectorIndexTest {

    private static float[] unit( final float... v ) { return v; } // already unit-ish for the test

    @Test
    void ranksNearestVectorFirstAndMapsCosineScore() {
        final HnswParams params = new HnswParams( 16, 64, 100 );
        final LuceneHnswChunkVectorIndex idx = LuceneHnswChunkVectorIndex.forTesting( 3, params );

        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        idx.addOrReplace( a, "PageA", unit( 1f, 0f, 0f ) );
        idx.addOrReplace( b, "PageB", unit( 0f, 1f, 0f ) );
        idx.commitAndRefresh();

        assertTrue( idx.isReady() );
        assertEquals( 2, idx.size() );
        assertEquals( 3, idx.dimension() );

        final List< ScoredChunk > top = idx.topKChunks( unit( 1f, 0f, 0f ), 2 );
        assertEquals( a, top.get( 0 ).chunkId(), "exact match ranks first" );
        assertEquals( "PageA", top.get( 0 ).pageName() );
        // cosine(query, a) == 1.0 → mapped raw cosine ~ 1.0
        assertEquals( 1.0, top.get( 0 ).score(), 1e-4 );
        // cosine(query, b) == 0.0 → mapped raw cosine ~ 0.0
        assertEquals( 0.0, top.get( 1 ).score(), 1e-4 );
    }

    @Test
    void upsertReplacesVectorForSameChunkId() {
        final LuceneHnswChunkVectorIndex idx =
            LuceneHnswChunkVectorIndex.forTesting( 3, new HnswParams( 16, 64, 100 ) );
        final UUID a = UUID.randomUUID();
        idx.addOrReplace( a, "PageA", unit( 1f, 0f, 0f ) );
        idx.commitAndRefresh();
        idx.addOrReplace( a, "PageA", unit( 0f, 0f, 1f ) ); // move A onto the z axis
        idx.commitAndRefresh();

        assertEquals( 1, idx.size(), "replace must not duplicate the chunk" );
        final List< ScoredChunk > top = idx.topKChunks( unit( 0f, 0f, 1f ), 1 );
        assertEquals( a, top.get( 0 ).chunkId() );
        assertEquals( 1.0, top.get( 0 ).score(), 1e-4 );
    }

    @Test
    void deleteRemovesChunk() {
        final LuceneHnswChunkVectorIndex idx =
            LuceneHnswChunkVectorIndex.forTesting( 3, new HnswParams( 16, 64, 100 ) );
        final UUID a = UUID.randomUUID();
        idx.addOrReplace( a, "PageA", unit( 1f, 0f, 0f ) );
        idx.commitAndRefresh();
        idx.delete( a );
        idx.commitAndRefresh();
        assertEquals( 0, idx.size() );
        assertFalse( idx.isReady() );
    }

    @Test
    void emptyIndexFailsClosed() {
        final LuceneHnswChunkVectorIndex idx =
            LuceneHnswChunkVectorIndex.forTesting( 3, new HnswParams( 16, 64, 100 ) );
        assertFalse( idx.isReady() );
        assertTrue( idx.topKChunks( unit( 1f, 0f, 0f ), 5 ).isEmpty(),
            "querying an empty index returns empty, never throws" );
    }
}
```

> Note on test vectors: Lucene's `VectorSimilarityFunction.COSINE` normalises internally, so `(1,0,0)` and `(0,1,0)` give cosine 1.0 and 0.0 respectively against query `(1,0,0)`. The score-mapping assertions therefore hold without pre-normalising.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=LuceneHnswChunkVectorIndexTest`
Expected: compile failure — class missing.

- [ ] **Step 3: Implement the core class**

`LuceneHnswChunkVectorIndex.java`:
```java
package com.wikantik.search.hybrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.StoredFields;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-process Lucene HNSW dense-retrieval backend. Holds a RAM-resident
 * ({@link ByteBuffersDirectory}) Lucene index with one document per chunk
 * carrying a {@link KnnFloatVectorField}. Built at boot from
 * {@code content_chunk_embeddings} (see the DataSource constructor) and kept
 * current via {@link #upsertChunks}.
 *
 * <p>Score: Lucene's {@link VectorSimilarityFunction#COSINE} score is
 * {@code (1 + cos) / 2}; we map back to raw cosine ({@code 2*score - 1}) so the
 * "larger is better" scale matches {@link InMemoryChunkVectorIndex}.</p>
 *
 * <p>Fail-closed: query/refresh failures log {@code WARN} and yield an empty
 * result or {@code isReady()==false} so hybrid retrieval degrades to BM25.</p>
 *
 * <p>See {@code docs/superpowers/specs/2026-05-22-lucene-hnsw-dense-retrieval-design.md}.</p>
 */
public final class LuceneHnswChunkVectorIndex implements ChunkVectorIndex {

    private static final Logger LOG = LogManager.getLogger( LuceneHnswChunkVectorIndex.class );

    static final String FIELD_VEC      = "vec";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_PAGE     = "page_name";

    private final int dim;
    private final int efSearch;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private volatile long lastRebuildMillis;

    /** Test seam: build an empty index of the given dimension, no DB load. */
    static LuceneHnswChunkVectorIndex forTesting( final int dim, final HnswParams params ) {
        return new LuceneHnswChunkVectorIndex( dim, params );
    }

    LuceneHnswChunkVectorIndex( final int dim, final HnswParams params ) {
        if ( dim <= 0 ) throw new IllegalArgumentException( "dim must be positive, got " + dim );
        this.dim = dim;
        this.efSearch = params.efSearch();
        try {
            this.directory = new ByteBuffersDirectory();
            final IndexWriterConfig cfg = new IndexWriterConfig().setCodec( hnswCodec( params ) );
            this.writer = new IndexWriter( directory, cfg );
            this.writer.commit(); // create an initial empty segment so the SearcherManager can open
            this.searcherManager = new SearcherManager( directory, null );
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Failed to initialise Lucene HNSW index", e );
        }
    }

    /** Codec that forces our HNSW build params on the vector field. */
    private static Codec hnswCodec( final HnswParams params ) {
        return new Lucene104Codec() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField( final String field ) {
                return new Lucene99HnswVectorsFormat( params.m(), params.efConstruction() );
            }
        };
    }

    /** Add or replace the vector for a chunk. Caller must {@link #commitAndRefresh()} to publish. */
    public void addOrReplace( final UUID chunkId, final String pageName, final float[] vec ) {
        if ( chunkId == null || vec == null ) {
            throw new IllegalArgumentException( "chunkId and vec must not be null" );
        }
        if ( vec.length != dim ) {
            LOG.warn( "Lucene HNSW: chunk {} dim={} differs from index dim={}, skipping",
                chunkId, vec.length, dim );
            return;
        }
        try {
            final Document doc = new Document();
            doc.add( new KnnFloatVectorField( FIELD_VEC, vec, VectorSimilarityFunction.COSINE ) );
            doc.add( new StringField( FIELD_CHUNK_ID, chunkId.toString(), Field.Store.YES ) );
            doc.add( new StoredField( FIELD_PAGE, pageName == null ? "" : pageName ) );
            writer.updateDocument( new Term( FIELD_CHUNK_ID, chunkId.toString() ), doc );
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW addOrReplace failed for chunk {}: {}", chunkId, e.getMessage(), e );
        }
    }

    /** Remove a chunk. Caller must {@link #commitAndRefresh()} to publish. */
    public void delete( final UUID chunkId ) {
        if ( chunkId == null ) return;
        try {
            writer.deleteDocuments( new Term( FIELD_CHUNK_ID, chunkId.toString() ) );
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW delete failed for chunk {}: {}", chunkId, e.getMessage(), e );
        }
    }

    /** Commit pending writes and refresh the searcher so queries see them. */
    public void commitAndRefresh() {
        try {
            writer.commit();
            searcherManager.maybeRefresh();
            lastRebuildMillis = System.currentTimeMillis();
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW commit/refresh failed: {}", e.getMessage(), e );
        }
    }

    @Override
    public List< ScoredChunk > topKChunks( final float[] queryVec, final int k ) {
        if ( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
        if ( k <= 0 ) throw new IllegalArgumentException( "k must be positive, got " + k );
        if ( queryVec.length != dim ) {
            throw new IllegalStateException( "queryVec length " + queryVec.length
                + " does not match index dimension " + dim );
        }
        final int fetch = Math.max( k, efSearch );
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            if ( searcher.getIndexReader().numDocs() == 0 ) return List.of();
            final KnnFloatVectorQuery query = new KnnFloatVectorQuery( FIELD_VEC, queryVec, fetch );
            final TopDocs hits = searcher.search( query, fetch );
            final StoredFields stored = searcher.storedFields();
            final List< ScoredChunk > out = new ArrayList<>( Math.min( k, hits.scoreDocs.length ) );
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                if ( out.size() >= k ) break;
                final Document doc = stored.document( sd.doc );
                final String idStr = doc.get( FIELD_CHUNK_ID );
                final String page = doc.get( FIELD_PAGE );
                if ( idStr == null ) continue;
                // Lucene COSINE score = (1 + cos)/2; map back to raw cosine for scale parity.
                final double cosine = 2.0 * sd.score - 1.0;
                out.add( new ScoredChunk( UUID.fromString( idStr ), page, cosine ) );
            }
            return out;
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW topKChunks failed (k={}): {}", k, e.getMessage(), e );
            return List.of();
        } finally {
            if ( searcher != null ) {
                try {
                    searcherManager.release( searcher );
                } catch ( final IOException e ) {
                    LOG.warn( "Lucene HNSW searcher release failed: {}", e.getMessage(), e );
                }
            }
        }
    }

    @Override
    public boolean isReady() {
        return size() > 0;
    }

    @Override
    public int dimension() {
        return dim;
    }

    /** Live document count. Intended for metrics/admin. */
    public int size() {
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            return searcher.getIndexReader().numDocs();
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW size() failed: {}", e.getMessage(), e );
            return 0;
        } finally {
            if ( searcher != null ) {
                try {
                    searcherManager.release( searcher );
                } catch ( final IOException e ) {
                    LOG.warn( "Lucene HNSW searcher release failed: {}", e.getMessage(), e );
                }
            }
        }
    }

    /** Wall-clock millis of the most recent successful {@link #commitAndRefresh()}. */
    public long lastRebuildMillis() {
        return lastRebuildMillis;
    }
}
```

- [ ] **Step 4: Run the core tests**

Run: `mvn -q -pl wikantik-main test -Dtest=LuceneHnswChunkVectorIndexTest`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndexTest.java
git commit -m "feat: Lucene HNSW dense backend core (RAM index, KNN query, fail-closed)"
```

---

## Task 4: DB load + incremental upsert on the backend

Give the class a production constructor that loads from `content_chunk_embeddings` (fail-closed to an empty index), plus `reload()` (full rebuild) and `upsertChunks(Collection<UUID>)` (incremental), reusing `ChunkVectorBytes` and the same SQL the in-memory backend uses.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndexTest.java`

- [ ] **Step 1: Write the failing DB-load test (mocked DataSource)**

Add to `LuceneHnswChunkVectorIndexTest.java` (add imports for `javax.sql.*`, `java.sql.*`, `org.mockito.*`, `java.nio.*`):
```java
    private static byte[] le( final float... v ) {
        final java.nio.ByteBuffer bb =
            java.nio.ByteBuffer.allocate( v.length * Float.BYTES ).order( java.nio.ByteOrder.LITTLE_ENDIAN );
        for ( final float f : v ) bb.putFloat( f );
        return bb.array();
    }

    @Test
    void buildsFromDatabaseRows() throws Exception {
        final java.util.UUID a = java.util.UUID.randomUUID();
        final java.sql.ResultSet rs = org.mockito.Mockito.mock( java.sql.ResultSet.class );
        org.mockito.Mockito.when( rs.next() ).thenReturn( true, false );
        org.mockito.Mockito.when( rs.getObject( 1, java.util.UUID.class ) ).thenReturn( a );
        org.mockito.Mockito.when( rs.getString( 2 ) ).thenReturn( "PageA" );
        org.mockito.Mockito.when( rs.getInt( 3 ) ).thenReturn( 3 );
        org.mockito.Mockito.when( rs.getBytes( 4 ) ).thenReturn( le( 1f, 0f, 0f ) );

        final java.sql.PreparedStatement ps = org.mockito.Mockito.mock( java.sql.PreparedStatement.class );
        org.mockito.Mockito.when( ps.executeQuery() ).thenReturn( rs );
        final java.sql.Connection conn = org.mockito.Mockito.mock( java.sql.Connection.class );
        org.mockito.Mockito.when( conn.prepareStatement( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( ps );
        final javax.sql.DataSource ds = org.mockito.Mockito.mock( javax.sql.DataSource.class );
        org.mockito.Mockito.when( ds.getConnection() ).thenReturn( conn );

        final LuceneHnswChunkVectorIndex idx =
            new LuceneHnswChunkVectorIndex( ds, "qwen3-embedding-0.6b", 3, new HnswParams( 16, 64, 100 ) );

        assertEquals( 1, idx.size() );
        assertTrue( idx.isReady() );
        assertEquals( a, idx.topKChunks( new float[]{ 1f, 0f, 0f }, 1 ).get( 0 ).chunkId() );
    }

    @Test
    void constructorFailsClosedOnDbError() throws Exception {
        final javax.sql.DataSource ds = org.mockito.Mockito.mock( javax.sql.DataSource.class );
        org.mockito.Mockito.when( ds.getConnection() ).thenThrow( new java.sql.SQLException( "boom" ) );
        final LuceneHnswChunkVectorIndex idx =
            new LuceneHnswChunkVectorIndex( ds, "qwen3-embedding-0.6b", 3, new HnswParams( 16, 64, 100 ) );
        assertFalse( idx.isReady(), "DB failure leaves an empty, queryable index (BM25 fallback)" );
        assertTrue( idx.topKChunks( new float[]{ 1f, 0f, 0f }, 1 ).isEmpty() );
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -q -pl wikantik-main test -Dtest=LuceneHnswChunkVectorIndexTest#buildsFromDatabaseRows+constructorFailsClosedOnDbError`
Expected: compile failure — the `(DataSource, String, int, HnswParams)` constructor does not exist yet.

- [ ] **Step 3: Add the DB-loading members to `LuceneHnswChunkVectorIndex`**

Add these fields next to the existing ones:
```java
    private final javax.sql.DataSource dataSource;   // null in the test-only constructor
    private final String modelCode;                  // null in the test-only constructor

    private static final String LOAD_SQL =
        "SELECT e.chunk_id, c.page_name, e.dim, e.vec "
      + "FROM content_chunk_embeddings e "
      + "JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + "WHERE e.model_code = ?";

    private static final String LOAD_BY_IDS_SQL =
        "SELECT e.chunk_id, c.page_name, e.dim, e.vec "
      + "FROM content_chunk_embeddings e "
      + "JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + "WHERE e.model_code = ? AND e.chunk_id = ANY (?)";
```

Update the existing test-only constructor to set them null:
```java
    LuceneHnswChunkVectorIndex( final int dim, final HnswParams params ) {
        this.dataSource = null;
        this.modelCode = null;
        initMachinery( dim, params );  // see Step 4
    }
```

Add the production constructor:
```java
    /**
     * Production constructor: builds the index from {@code content_chunk_embeddings}.
     * Fail-closed — a DB error during the initial load logs {@code WARN} and leaves
     * an empty index so hybrid retrieval degrades to BM25 rather than blocking boot.
     */
    public LuceneHnswChunkVectorIndex( final javax.sql.DataSource dataSource,
                                       final String modelCode,
                                       final int dim,
                                       final HnswParams params ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        this.dataSource = dataSource;
        this.modelCode = modelCode;
        initMachinery( dim, params );
        reload();
    }

    /** Public accessor for the configured model code (used by the factory test + metrics). */
    public String modelCode() {
        return modelCode;
    }
```

- [ ] **Step 4: Refactor machinery init out of the constructor and add load methods**

Replace the body that builds `directory`/`writer`/`searcherManager` with a shared `initMachinery`, and convert the now-blank-final fields (`dim`, `efSearch`, `directory`, `writer`, `searcherManager`) to non-final assigned in `initMachinery`:
```java
    private int dim;
    private int efSearch;
    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    private void initMachinery( final int dim, final HnswParams params ) {
        if ( dim <= 0 ) throw new IllegalArgumentException( "dim must be positive, got " + dim );
        this.dim = dim;
        this.efSearch = params.efSearch();
        try {
            this.directory = new ByteBuffersDirectory();
            final IndexWriterConfig cfg = new IndexWriterConfig().setCodec( hnswCodec( params ) );
            this.writer = new IndexWriter( directory, cfg );
            this.writer.commit();
            this.searcherManager = new SearcherManager( directory, null );
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Failed to initialise Lucene HNSW index", e );
        }
    }

    /** Full rebuild from the database. Fail-closed: SQL errors leave the prior contents. */
    public void reload() {
        if ( dataSource == null ) return; // test-only instance
        try ( java.sql.Connection c = dataSource.getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement( LOAD_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try ( java.sql.ResultSet rs = ps.executeQuery() ) {
                writer.deleteAll();
                int loaded = 0;
                while ( rs.next() ) {
                    if ( addRow( rs ) ) loaded++;
                }
                commitAndRefresh();
                LOG.info( "Lucene HNSW index loaded: model={} rows={} dim={}", modelCode, loaded, dim );
            }
        } catch ( final java.sql.SQLException e ) {
            LOG.warn( "Lucene HNSW reload failed (model={}); index left empty: {}",
                modelCode, e.getMessage(), e );
        }
    }

    /** Incremental update for a handful of changed chunks (called from the async index listener). */
    public void upsertChunks( final java.util.Collection< UUID > chunkIds ) {
        if ( dataSource == null || chunkIds == null || chunkIds.isEmpty() ) return;
        final java.util.Set< UUID > targets = new java.util.HashSet<>( chunkIds );
        try ( java.sql.Connection c = dataSource.getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement( LOAD_BY_IDS_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setArray( 2, c.createArrayOf( "uuid", targets.toArray( new UUID[ 0 ] ) ) );
            final java.util.Set< UUID > seen = new java.util.HashSet<>();
            try ( java.sql.ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final UUID id = rs.getObject( 1, UUID.class );
                    if ( addRow( rs ) ) seen.add( id );
                }
            }
            // Any target id not returned by the DB was deleted upstream — drop it.
            for ( final UUID id : targets ) {
                if ( !seen.contains( id ) ) delete( id );
            }
            commitAndRefresh();
        } catch ( final java.sql.SQLException e ) {
            LOG.warn( "Lucene HNSW upsert failed (model={}, ids={}); falling back to reload: {}",
                modelCode, targets.size(), e.getMessage(), e );
            reload();
        }
    }

    /** Decode one result-set row and stage an addOrReplace. Returns false if the row was skipped. */
    private boolean addRow( final java.sql.ResultSet rs ) throws java.sql.SQLException {
        final UUID id = rs.getObject( 1, UUID.class );
        final String page = rs.getString( 2 );
        final int rowDim = rs.getInt( 3 );
        final byte[] raw = rs.getBytes( 4 );
        if ( rowDim != dim ) {
            LOG.warn( "Lucene HNSW: chunk {} dim={} differs from index dim={}, skipping", id, rowDim, dim );
            return false;
        }
        final float[] v = ChunkVectorBytes.decode( id, raw, rowDim );
        if ( v == null ) return false;
        addOrReplace( id, page, v );
        return true;
    }
```

> Note: the existing `hnswCodec`, `addOrReplace`, `delete`, `commitAndRefresh`, `topKChunks`, `isReady`, `dimension`, `size`, `lastRebuildMillis`, and field-name constants from Task 3 are unchanged. `addOrReplace` already normalises nothing (Lucene COSINE handles magnitude), so raw DB vectors are fine.

- [ ] **Step 5: Run the DB-path + core tests**

Run: `mvn -q -pl wikantik-main test -Dtest=LuceneHnswChunkVectorIndexTest`
Expected: PASS (six cases: four core + two DB).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndex.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/LuceneHnswChunkVectorIndexTest.java
git commit -m "feat: Lucene HNSW backend DB load + incremental upsert (fail-closed)"
```

---

## Task 5: Backend switch in `SearchSubsystemFactory`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemFactory.java:105-128`
- Modify: `wikantik-main/src/test/java/com/wikantik/search/subsystem/SearchSubsystemFactoryTest.java`

- [ ] **Step 1: Write the failing selection test**

Add to `SearchSubsystemFactoryTest.java` after `selectsPgvectorBackendWhenPropertySet` (import `LuceneHnswChunkVectorIndex` + `HnswParams` are in the same `com.wikantik.search.hybrid` package — add `import com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex;`):
```java
    @Test
    void selectsLuceneHnswBackendWhenPropertySet() throws Exception {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "lucene-hnsw" );
        props.setProperty( EmbeddingConfig.PROP_MODEL, "qwen3-embedding-0.6b" );

        // Stub the DataSource to throw so the index constructor's reload() fails
        // closed to an empty (but valid) index — we only assert the type here.
        final DataSource dataSource = mock( DataSource.class );
        when( dataSource.getConnection() ).thenThrow( new java.sql.SQLException( "no db in unit test" ) );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( props );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                dataSource,
                mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                null, null, null, engine ) );

        assertInstanceOf( LuceneHnswChunkVectorIndex.class, services.chunkVectorIndex() );
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -q -pl wikantik-main test -Dtest=SearchSubsystemFactoryTest#selectsLuceneHnswBackendWhenPropertySet`
Expected: FAIL — factory throws `IllegalArgumentException` for the unknown `lucene-hnsw` value.

- [ ] **Step 3: Add the switch case + widen the error message**

In `SearchSubsystemFactory.java`, add a case before `default` (after the `inmemory` case at line ~124) and update the default message:
```java
            case "lucene-hnsw" -> {
                final DataSource dataSource = deps.dataSource();
                if ( dataSource == null ) {
                    throw new IllegalStateException(
                        "wikantik.search.dense.backend=lucene-hnsw but no DataSource is available; "
                      + "check that wikantik.datasource is configured" );
                }
                final String modelCode = wikiProps.getProperty(
                    EmbeddingConfig.PROP_MODEL, EmbeddingConfig.DEFAULT_MODEL_CODE );
                final com.wikantik.search.hybrid.HnswParams params =
                    com.wikantik.search.hybrid.HnswParams.fromProperties( wikiProps );
                chunkVectorIndex = new com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex(
                    dataSource, modelCode, com.wikantik.search.hybrid.PgVectorChunkVectorIndex.EMBEDDING_DIM, params );
                LOG.info( "Dense retrieval backend: Lucene HNSW (model={}, m={}, ef_construction={}, ef_search={})",
                    modelCode, params.m(), params.efConstruction(), params.efSearch() );
            }
            case "inmemory" -> {
                chunkVectorIndex = engine.getManager( InMemoryChunkVectorIndex.class );
                LOG.info( "Dense retrieval backend: in-memory brute-force" );
            }
            default -> throw new IllegalArgumentException(
                "wikantik.search.dense.backend must be 'inmemory', 'pgvector', or 'lucene-hnsw', got: '"
              + backend + "'" );
```

> `PgVectorChunkVectorIndex.EMBEDDING_DIM` (=1024) is the existing single source of truth for the embedding dimension; reuse it rather than introducing a second constant.

- [ ] **Step 4: Run the factory backend tests**

Run: `mvn -q -pl wikantik-main test -Dtest=SearchSubsystemFactoryTest`
Expected: PASS (new case + the existing `rejectsUnknownBackendValue` still rejects `elasticsearch`).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemFactory.java \
        wikantik-main/src/test/java/com/wikantik/search/subsystem/SearchSubsystemFactoryTest.java
git commit -m "feat: select lucene-hnsw dense backend in SearchSubsystemFactory"
```

---

## Task 6: Live wiring in `SearchWiringHelper`

This is the path production actually boots. Add a `lucene-hnsw` branch parallel to `inmemory`: build the index, set the bootstrap reload hook to `reload`, and wire the async listener's post-index callback to `upsertChunks`. Register size + last-rebuild metrics.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java:135-184`

- [ ] **Step 1: Add the branch**

In `SearchWiringHelper.java`, the dense-backend block currently handles `pgvector` then `inmemory` then throws. The `inMemoryForListener` variable is typed `InMemoryChunkVectorIndex` and used only to gate the callback. Generalise the callback wiring to also cover the Lucene backend by introducing a `java.util.function.Consumer<java.util.Collection<UUID>> upsertCallback` (null for pgvector). Replace lines ~138-182 with:

```java
        final com.wikantik.search.hybrid.ChunkVectorIndex vectorIndex;
        final Runnable indexReloadHook;
        // Must match AsyncEmbeddingIndexListener.setPostIndexCallback's parameter
        // type, which is Consumer<List<UUID>>. The upsertChunks(Collection<UUID>)
        // method refs adapt to it because List<UUID> is-a Collection<UUID>.
        final java.util.function.Consumer< java.util.List< java.util.UUID > > upsertCallback;
        try {
            if ( "pgvector".equals( denseBackend ) ) {
                final int efSearch = Integer.parseInt( props.getProperty(
                    "wikantik.search.dense.pgvector.ef_search", "100" ) );
                final com.wikantik.search.hybrid.PgVectorChunkVectorIndex pgIndex =
                    new com.wikantik.search.hybrid.PgVectorChunkVectorIndex( ds, modelCode, efSearch );
                vectorIndex = pgIndex;
                upsertCallback = null;
                indexReloadHook = () -> {};
                LOG.info( "Dense retrieval backend: pgvector HNSW (model={}, ef_search={})",
                    modelCode, efSearch );
            } else if ( "lucene-hnsw".equals( denseBackend ) ) {
                final com.wikantik.search.hybrid.HnswParams params =
                    com.wikantik.search.hybrid.HnswParams.fromProperties( props );
                final com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex hnswIndex =
                    new com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex(
                        ds, modelCode,
                        com.wikantik.search.hybrid.PgVectorChunkVectorIndex.EMBEDDING_DIM, params );
                vectorIndex = hnswIndex;
                upsertCallback = hnswIndex::upsertChunks;
                indexReloadHook = hnswIndex::reload;
                LOG.info( "Dense retrieval backend: Lucene HNSW (model={}, m={}, ef_construction={}, ef_search={}, size={})",
                    modelCode, params.m(), params.efConstruction(), params.efSearch(), hnswIndex.size() );
            } else if ( "inmemory".equals( denseBackend ) ) {
                final InMemoryChunkVectorIndex memIndex = new InMemoryChunkVectorIndex( ds, modelCode );
                vectorIndex = memIndex;
                upsertCallback = memIndex::upsertChunks;
                indexReloadHook = memIndex::reload;
                engine.registerChunkVectorIndex( memIndex );
                LOG.info( "Dense retrieval backend: in-memory brute-force (model={}, size={})",
                    modelCode, memIndex.size() );
            } else {
                throw new IllegalArgumentException(
                    "wikantik.search.dense.backend must be 'inmemory', 'pgvector', or 'lucene-hnsw', got: '"
                    + denseBackend + "'" );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Failed to initialize {} ChunkVectorIndex (model={}); "
                + "hybrid retrieval disabled: {}", denseBackend, modelCode, e.getMessage(), e );
            return;
        }

        final AsyncEmbeddingIndexListener listener =
            new AsyncEmbeddingIndexListener( indexService, modelCode );
        // For backends with an in-process index (inmemory, lucene-hnsw), refresh it
        // incrementally after each embedding write. pgvector dual-writes the embedding
        // column in EmbeddingIndexService, so it needs no listener-side callback.
        if ( upsertCallback != null ) {
            listener.setPostIndexCallback( upsertCallback );
        }
        chunkProjector.setPostChunkSink( listener );
        engine.setHybridIndexListener( listener );
```

> Confirmed: `AsyncEmbeddingIndexListener.setPostIndexCallback` is typed `Consumer<List<UUID>>` (`AsyncEmbeddingIndexListener.java:106`). Both `InMemoryChunkVectorIndex::upsertChunks` and `LuceneHnswChunkVectorIndex::upsertChunks` take `Collection<UUID>`, which a `Consumer<List<UUID>>` satisfies (the callback hands them a `List`). No setter change needed.

- [ ] **Step 2: Register metrics for the Lucene backend**

In the metrics block further down `wireHybridRetrieval` (where `meterRegistry` is obtained, ~line 224), register two gauges when the backend is Lucene. Add after the `meterRegistry` is resolved and non-null:
```java
        if ( meterRegistry != null
                && vectorIndex instanceof com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex hnsw ) {
            meterRegistry.gauge( "wikantik_search_dense_index_size",
                java.util.List.of( io.micrometer.core.instrument.Tag.of( "backend", "lucene-hnsw" ) ),
                hnsw, com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex::size );
            meterRegistry.gauge( "wikantik_search_dense_index_last_rebuild_millis",
                java.util.List.of( io.micrometer.core.instrument.Tag.of( "backend", "lucene-hnsw" ) ),
                hnsw, com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex::lastRebuildMillis );
        }
```

> If an equivalent gauge for the in-memory backend already exists nearby, mirror its exact registration style (tag names, metric prefix) instead of the above, to keep one convention. Inspect the surrounding lines before writing.

- [ ] **Step 3: Compile the module**

Run: `mvn -q -pl wikantik-main test-compile`
Expected: BUILD SUCCESS (no compile errors; signature changes to the listener caught here).

- [ ] **Step 4: Run the search-subsystem test packages**

Run: `mvn -q -pl wikantik-main test -Dtest='com.wikantik.search.**'`
Expected: PASS — no regressions in the search/hybrid/embedding suites.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java
git commit -m "feat: wire lucene-hnsw dense backend (reload hook, upsert callback, metrics)"
```

---

## Task 7: Configuration defaults + documentation

**Files:**
- Modify: `ini/wikantik.properties` (search for the existing `wikantik.search.dense.backend` / `wikantik.search.dense.pgvector.ef_search` keys and add the new ones beside them)
- Modify: `docs/wikantik-pages/HybridRetrieval.md`

- [ ] **Step 1: Add documented defaults to `ini/wikantik.properties`**

Locate the existing dense-backend keys (grep: `grep -n "wikantik.search.dense" ini/wikantik.properties`) and add directly below them:
```properties
# Lucene HNSW dense backend (wikantik.search.dense.backend=lucene-hnsw).
# In-process approximate nearest-neighbour index held in RAM, rebuilt on boot
# from content_chunk_embeddings. Defaults match the pgvector HNSW index.
#wikantik.search.dense.lucene.m=16
#wikantik.search.dense.lucene.ef_construction=64
#wikantik.search.dense.lucene.ef_search=100
```
Also update the comment on `wikantik.search.dense.backend` to list `lucene-hnsw` as an accepted value.

- [ ] **Step 2: Document in `HybridRetrieval.md`**

Add a subsection under the dense-backend configuration describing `lucene-hnsw`: what it is (in-process Lucene HNSW ANN), when to choose it (single-host CPU relief vs. brute-force `inmemory`), the three knobs and defaults, the RAM-rebuilt-on-boot lifecycle, and the future on-disk note for ~10⁵-chunk scale. Reference the spec at `docs/superpowers/specs/2026-05-22-lucene-hnsw-dense-retrieval-design.md`.

- [ ] **Step 3: RAT/license + Javadoc sanity (no test needed)**

Run: `mvn -q -pl wikantik-main apache-rat:check`
Expected: BUILD SUCCESS (new `.java` files carry the license header — copy it from any sibling in `com/wikantik/search/hybrid/`).

- [ ] **Step 4: Commit**

```bash
git add ini/wikantik.properties docs/wikantik-pages/HybridRetrieval.md
git commit -m "docs: document the lucene-hnsw dense backend and its knobs"
```

---

## Task 8: Retrieval-quality smoke gate

Confirm the new backend holds search quality within the established tolerance before any cutover. `RetrievalQualitySmokeTest` is the pre-merge gate (nDCG@5 ≥ 0.5 absolute; for a backend swap, also compare to the in-memory baseline within ±0.02).

**Files:** none (verification task). If the smoke test is not yet parametrised by backend, add a backend-parametrised variant mirroring its existing structure.

- [ ] **Step 1: Run the existing smoke gate (in-memory baseline)**

Run: `mvn -q -pl wikantik-main test -Dtest=RetrievalQualitySmokeTest`
Expected: PASS. Record the reported nDCG@5 as the baseline.

- [ ] **Step 2: Inspect how the smoke test selects a backend**

Read `wikantik-main/src/test/java/com/wikantik/knowledge/eval/RetrievalQualitySmokeTest.java` and its `DefaultRetrievalQualityRunner` seam. Determine whether the `Retriever` it drives can be backed by a `LuceneHnswChunkVectorIndex` built from the same fixture vectors the in-memory baseline uses.

- [ ] **Step 3: Add a Lucene-HNSW smoke assertion**

Add a test that builds a `LuceneHnswChunkVectorIndex` (via the `forTesting` seam + `addOrReplace` over the same fixture vectors), runs the curated `core-agent-queries` set through the same runner, and asserts nDCG@5 is within 0.02 of the in-memory baseline captured in Step 1:
```java
    @Test
    void luceneHnswStaysWithinToleranceOfBruteForce() {
        // Build both indexes from the identical fixture vectors, run the curated
        // query set through each, assert |ndcg5(hnsw) - ndcg5(inmemory)| <= 0.02.
        // Mirror the existing smoke-test fixture loading and runner invocation.
    }
```
Fill the body using the exact fixture-loading and runner-invocation helpers already present in `RetrievalQualitySmokeTest` (do not invent new helpers — reuse the file's existing ones).

- [ ] **Step 4: Run it**

Run: `mvn -q -pl wikantik-main test -Dtest=RetrievalQualitySmokeTest`
Expected: PASS — Lucene HNSW within ±0.02 of brute force.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/eval/RetrievalQualitySmokeTest.java
git commit -m "test: retrieval-quality gate for the lucene-hnsw backend (nDCG@5 within 0.02)"
```

---

## Task 9: Full verification, deploy, re-profile

**Files:** none (verification + ops).

- [ ] **Step 1: Full unit build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Full integration reactor (sequential, fail-at-end)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS, 0 failures across all IT submodules.

- [ ] **Step 3: Deploy to docker1 with the new backend enabled**

Set `wikantik.search.dense.backend=lucene-hnsw` in the prod env (`.env.prod` / the property surfaced through `docker/entrypoint.sh`), then:
```bash
bin/remote.sh deploy
```
Expected: `Deploy healthy: http://docker1:8080/api/health returned 200.`
Confirm the boot log shows `Dense retrieval backend: Lucene HNSW (model=qwen3-embedding-0.6b, m=16, ef_construction=64, ef_search=100, size=12252)`.

- [ ] **Step 4: Warm + extended N=350 profile (same methodology as the #1–3 run)**

```bash
BASE_URL=http://docker1:8080 bin/loadtest.sh load --vus 8 --duration 2m       # warmup
# then the 10-min steady N=350 run + a 540s JFR over steady state (see /tmp/profile-run.sh pattern)
```
Expected: in the fresh JFR, `InMemoryChunkVectorIndex.dotAt` is gone and the dense-scan theme drops from ~60% toward ~7–8%; throughput headroom rises. Capture the comparison for the record.

- [ ] **Step 5: Push**

```bash
git push origin main
```

---

## Self-Review

**Spec coverage:**
- New `lucene-hnsw` backend value + class → Tasks 3–6. ✓
- RAM `ByteBuffersDirectory` rebuilt on boot → Task 3 (machinery) + Task 4 (`reload`). ✓
- One doc per chunk, `KnnFloatVectorField` COSINE + stored chunk_id/page_name → Task 3. ✓
- Incremental upsert via `AsyncEmbeddingIndexListener` callback → Task 4 (`upsertChunks`) + Task 6 (wiring). ✓
- Boot rebuild via `BootstrapEmbeddingIndexer` reload hook → Task 6 (`indexReloadHook = reload`). ✓
- Knobs `m`/`ef_construction`/`ef_search` w/ pgvector defaults via custom codec → Task 2 + Task 3 (`hnswCodec`). ✓
- Score map `2·score − 1` → Task 3. ✓
- Fail-closed to BM25, `LOG.warn` everywhere → Tasks 3 & 4. ✓
- Backend switch (factory + live wiring), widened error message → Tasks 5 & 6. ✓
- Metrics (size + last-rebuild) → Task 6. ✓
- No migration / no new dependency → confirmed (Lucene 10.4 present). ✓
- Quality gate nDCG@5 ±0.02 → Task 8. ✓
- Cutover/rollback (flag flip) → Task 9 + spec. ✓
- Future on-disk note → Task 7 docs (spec already carries it). ✓

**Placeholder scan:** Task 8 Step 3 intentionally defers the test body to the file's existing fixtures (the runner's helpers can't be reproduced verbatim without reading the file at implementation time); all other steps carry complete, compilable code.

**Type consistency:** `ChunkVectorIndex.topKChunks(float[],int)→List<ScoredChunk>`, `ScoredChunk(UUID,String,double)`, `HnswParams(int,int,int)` with `m()/efConstruction()/efSearch()`, `LuceneHnswChunkVectorIndex` ctors `(int,HnswParams)` test-only and `(DataSource,String,int,HnswParams)` production, field constants `FIELD_VEC/FIELD_CHUNK_ID/FIELD_PAGE`, dim sourced from `PgVectorChunkVectorIndex.EMBEDDING_DIM` — all consistent across tasks.
