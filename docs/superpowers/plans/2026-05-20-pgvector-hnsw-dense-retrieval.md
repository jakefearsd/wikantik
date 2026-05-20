# pgvector + HNSW Dense Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the brute-force `InMemoryChunkVectorIndex` scan with a pgvector HNSW lookup, behind a runtime backend flag, with dual-write cutover and rollback by flag flip.

**Architecture:** Add an `embedding vector(1024)` column + HNSW index to `content_chunk_embeddings`; introduce a `PgVectorChunkVectorIndex` implementing the existing `ChunkVectorIndex` interface; select between in-memory and pgvector via `wikantik.search.dense.backend`; dual-write through the soak so rollback is a flag flip away.

**Tech Stack:** Java 21, PostgreSQL 18 + pgvector, Vector API (already in for the legacy backend), Maven, JUnit 5, Testcontainers (PostgresTestContainer pattern already in this repo).

**Spec:** `docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md`

---

## File Structure

**New files:**
- `bin/db/migrations/V032__chunk_embeddings_pgvector_hnsw.sql` — DDL: column + HNSW index
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java` — new `ChunkVectorIndex` impl
- `wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexTest.java` — unit tests
- `wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexPgTest.java` — pgvector container IT
- `wikantik-main/src/main/java/com/wikantik/search/embedding/PgVectorBackfillCli.java` — Java CLI that decodes BYTEA → writes pgvector literal
- `wikantik-main/src/test/java/com/wikantik/search/embedding/PgVectorBackfillCliTest.java` — unit + container test for the CLI
- `bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh` — idempotent shell wrapper around the CLI

**Modified files:**
- `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemFactory.java` — backend selection on `wikantik.search.dense.backend`
- `wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingIndexService.java` — UPSERT becomes dual-write
- `wikantik-main/src/main/java/com/wikantik/search/embedding/AsyncEmbeddingIndexListener.java` — pgvector branch is a no-op for the in-memory reload step
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridMetricsBridge.java` — size-gauge becomes type-conditional
- `wikantik-main/src/test/java/com/wikantik/knowledge/eval/RetrievalQualitySmokeTest.java` — extend to parity-check both backends
- `wikantik-main/src/main/resources/ini/wikantik.properties` (or template) — register the two new properties with documented defaults
- `CLAUDE.md` — one line under "Hybrid Retrieval" pointing at the flag

---

### Task 1: Migration V032 — add column and HNSW index

**Files:**
- Create: `bin/db/migrations/V032__chunk_embeddings_pgvector_hnsw.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V032: pgvector HNSW index for chunk dense retrieval.
-- Adds the pgvector column and HNSW index alongside the legacy BYTEA `vec`
-- column. The BYTEA column stays in place during the dual-write cutover so
-- rollback to the in-memory backend works without a data migration. A later
-- migration drops the legacy columns once the new path has soaked in
-- production. Per the no-data-in-migrations rule, the backfill itself is a
-- one-shot script run by the operator before the flag flip — not part of
-- this migration.

ALTER TABLE content_chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding vector(1024);

CREATE INDEX IF NOT EXISTS content_chunk_embeddings_hnsw_idx
    ON content_chunk_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

- [ ] **Step 2: Verify migration runs against a fresh container**

Run: `bin/container.sh smoke-test`
Expected: migration log line `V032__chunk_embeddings_pgvector_hnsw applied`; container reports healthy.

Verify directly: `bin/container.sh psql -- -c "\d content_chunk_embeddings"`
Expected: `embedding` column listed with type `vector(1024)`; `content_chunk_embeddings_hnsw_idx` listed as an `hnsw` index.

- [ ] **Step 3: Verify idempotence (re-running the migrate.sh is a no-op)**

Run: `bin/container.sh exec wikantik /opt/wikantik/db/migrate.sh --status`
Expected: V032 listed as applied, no errors.

Run: `bin/container.sh exec wikantik /opt/wikantik/db/migrate.sh`
Expected: no DDL re-applied; exits cleanly.

- [ ] **Step 4: Commit**

```bash
git add bin/db/migrations/V032__chunk_embeddings_pgvector_hnsw.sql
git commit -m "db: V032 add pgvector embedding column + HNSW index"
```

---

### Task 2: `PgVectorChunkVectorIndex` skeleton — interface impl + format/parse helpers

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java`
- Create: `wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexTest.java`

- [ ] **Step 1: Write failing test for `formatVector`**

```java
@Test
void formatVector_emitsPgvectorLiteral() {
    final float[] v = { 0.1f, -0.2f, 0.3f };
    assertEquals("[0.1,-0.2,0.3]", PgVectorChunkVectorIndex.formatVector(v));
}

@Test
void formatVector_emptyVector_emitsEmptyLiteral() {
    assertEquals("[]", PgVectorChunkVectorIndex.formatVector(new float[0]));
}

@Test
void formatVector_nullVector_throws() {
    assertThrows(IllegalArgumentException.class,
        () -> PgVectorChunkVectorIndex.formatVector(null));
}
```

- [ ] **Step 2: Run test to confirm compilation failure**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexTest -q`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the class skeleton + `formatVector`**

```java
package com.wikantik.search.hybrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.List;

/**
 * Dense-vector index backed by pgvector's HNSW index on the
 * {@code content_chunk_embeddings.embedding} column. Stateless beyond the
 * supplied {@link DataSource} and {@code modelCode} — every {@link #topKChunks}
 * call dispatches a single SELECT to PostgreSQL with a {@code SET LOCAL
 * hnsw.ef_search} guard so the recall/latency knob is per-query.
 *
 * <p>Score conversion: pgvector's {@code <=>} operator returns
 * {@code 1 - cosine_similarity} (smaller is closer). We invert at SELECT time
 * so the {@code "larger is better"} contract held by {@link DenseRetriever}
 * and {@link HybridFuser} carries over unchanged.</p>
 *
 * <p>See {@code docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md}.</p>
 */
public final class PgVectorChunkVectorIndex implements ChunkVectorIndex {

    private static final Logger LOG = LogManager.getLogger( PgVectorChunkVectorIndex.class );

    private final DataSource dataSource;
    private final String modelCode;
    private final int efSearch;

    public PgVectorChunkVectorIndex( final DataSource dataSource,
                                      final String modelCode,
                                      final int efSearch ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        if ( efSearch <= 0 ) throw new IllegalArgumentException( "efSearch must be positive, got " + efSearch );
        this.dataSource = dataSource;
        this.modelCode = modelCode;
        this.efSearch = efSearch;
    }

    @Override
    public List< ScoredChunk > topKChunks( final float[] queryVec, final int k ) {
        throw new UnsupportedOperationException( "implemented in Task 3" );
    }

    @Override
    public boolean isReady() {
        return false; // implemented in Task 4
    }

    @Override
    public int dimension() {
        return 1024;
    }

    /**
     * Format {@code v} as a pgvector literal: {@code "[v1,v2,...]"}. Matches the
     * codec used by {@link com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository}.
     */
    static String formatVector( final float[] v ) {
        if ( v == null ) throw new IllegalArgumentException( "v must not be null" );
        final StringBuilder sb = new StringBuilder( v.length * 8 );
        sb.append( '[' );
        for ( int i = 0; i < v.length; i++ ) {
            if ( i > 0 ) sb.append( ',' );
            sb.append( v[ i ] );
        }
        sb.append( ']' );
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexTest -q`
Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexTest.java
git commit -m "search: scaffold PgVectorChunkVectorIndex + formatVector helper"
```

---

### Task 3: `PgVectorChunkVectorIndex.topKChunks` — pgvector container IT

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java`
- Create: `wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexPgTest.java`

- [ ] **Step 1: Write failing IT against a pgvector container**

Use the existing `PostgresTestContainer` pattern (see how `KgNodeEmbeddingRepository` IT or `PageExtractionEndToEndPgTest` does it). The test:

1. Starts a pg18+pgvector container.
2. Runs the migration set including V032.
3. Inserts three known chunks with known `kg_content_chunks` rows and three known unit-length 1024-dim vectors, plus a fourth deliberately further away.
4. Calls `topKChunks(query, 3)` with a query vector identical to chunk[0]'s.
5. Asserts the result list is length 3, chunk[0] is first with score ≈ 1.0, and the "far" vector is excluded.

```java
@Test
void topKChunks_returnsHighestScoredChunksFirst() {
    final DataSource ds = container.dataSource();
    seedChunks(ds, /* 3 close, 1 far */);

    final PgVectorChunkVectorIndex idx =
        new PgVectorChunkVectorIndex(ds, "bge-m3", 100);

    final float[] query = idx_test_fixture_close_to_chunk_0();
    final List<ScoredChunk> top = idx.topKChunks(query, 3);

    assertEquals(3, top.size());
    assertEquals(CHUNK_0_ID, top.get(0).chunkId());
    assertTrue(top.get(0).score() > 0.99,
        "self-similarity should be ≈ 1.0, was " + top.get(0).score());
    assertTrue(top.stream().noneMatch(s -> s.chunkId().equals(FAR_CHUNK_ID)),
        "far chunk should not appear in top-3");
}
```

- [ ] **Step 2: Run IT to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexPgTest -q`
Expected: FAIL — `topKChunks` throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `topKChunks`**

Replace the `UnsupportedOperationException` body with:

```java
@Override
public List< ScoredChunk > topKChunks( final float[] queryVec, final int k ) {
    if ( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
    if ( k <= 0 ) throw new IllegalArgumentException( "k must be positive, got " + k );
    if ( queryVec.length != 1024 ) {
        throw new IllegalStateException( "queryVec length " + queryVec.length
            + " does not match index dimension 1024" );
    }

    final String setEf = "SET LOCAL hnsw.ef_search = " + efSearch;
    final String sql = """
        SELECT e.chunk_id, c.page_name,
               1.0 - (e.embedding <=> ?::vector) AS score
        FROM content_chunk_embeddings e
        JOIN kg_content_chunks c ON c.id = e.chunk_id
        WHERE e.model_code = ?
        ORDER BY e.embedding <=> ?::vector
        LIMIT ?
        """;

    final String literal = formatVector( queryVec );
    try ( Connection conn = dataSource.getConnection() ) {
        final boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit( false );
        try ( Statement st = conn.createStatement() ) {
            st.execute( setEf );
        }
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, literal );
            ps.setString( 2, modelCode );
            ps.setString( 3, literal );
            ps.setInt( 4, k );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< ScoredChunk > out = new ArrayList<>( k );
                while ( rs.next() ) {
                    out.add( new ScoredChunk(
                        rs.getObject( 1, UUID.class ),
                        rs.getString( 2 ),
                        rs.getDouble( 3 ) ) );
                }
                conn.commit();
                conn.setAutoCommit( prevAutoCommit );
                return out;
            }
        }
    } catch ( final SQLException e ) {
        LOG.warn( "PgVectorChunkVectorIndex.topKChunks failed (model={}, k={}): {}",
            modelCode, k, e.getMessage(), e );
        throw new RuntimeException( "PgVector dense retrieval failed", e );
    }
}
```

Note the explicit transaction: `SET LOCAL` only applies within a transaction, so `setAutoCommit(false)` is intentional. The `commit()` releases the session-scope override.

- [ ] **Step 4: Run IT to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexPgTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexPgTest.java
git commit -m "search: PgVectorChunkVectorIndex.topKChunks via HNSW + ef_search guard"
```

---

### Task 4: `isReady()`, `size()`, `dimension()` — readiness + cached size

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexPgTest.java`

- [ ] **Step 1: Add failing IT cases**

```java
@Test
void isReady_trueWhenAtLeastOneEmbeddingExists() {
    seedChunks(/* 1 chunk with embedding */);
    final PgVectorChunkVectorIndex idx =
        new PgVectorChunkVectorIndex(dataSource, "bge-m3", 100);
    assertTrue(idx.isReady());
}

@Test
void isReady_falseWhenColumnAllNull() {
    seedChunksWithoutEmbeddings();
    final PgVectorChunkVectorIndex idx =
        new PgVectorChunkVectorIndex(dataSource, "bge-m3", 100);
    assertFalse(idx.isReady());
}

@Test
void size_returnsRowCountForModel() {
    seedChunks(/* 5 chunks for bge-m3 */);
    final PgVectorChunkVectorIndex idx =
        new PgVectorChunkVectorIndex(dataSource, "bge-m3", 100);
    assertEquals(5, idx.size());
}
```

- [ ] **Step 2: Run IT to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexPgTest -q`
Expected: 3 NEW failures (the existing `topKChunks` test still passes).

- [ ] **Step 3: Implement `isReady` + `size` with 5-minute caching**

Add to `PgVectorChunkVectorIndex`:

```java
private static final long SIZE_CACHE_MILLIS = 5 * 60 * 1000L;
private volatile long sizeCachedAt;
private volatile int sizeCachedValue;

@Override
public boolean isReady() {
    final String sql = "SELECT 1 FROM content_chunk_embeddings "
                     + "WHERE model_code = ? AND embedding IS NOT NULL LIMIT 1";
    try ( Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setString( 1, modelCode );
        try ( ResultSet rs = ps.executeQuery() ) {
            return rs.next();
        }
    } catch ( final SQLException e ) {
        LOG.warn( "PgVectorChunkVectorIndex.isReady probe failed (model={}): {}",
            modelCode, e.getMessage(), e );
        return false;
    }
}

/**
 * Row count for {@link #modelCode}. Cached for 5 minutes so metric scrapes
 * don't fan out a count query every scrape interval.
 */
public int size() {
    final long now = System.currentTimeMillis();
    if ( now - sizeCachedAt < SIZE_CACHE_MILLIS ) return sizeCachedValue;
    final String sql = "SELECT COUNT(*) FROM content_chunk_embeddings "
                     + "WHERE model_code = ? AND embedding IS NOT NULL";
    try ( Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setString( 1, modelCode );
        try ( ResultSet rs = ps.executeQuery() ) {
            if ( rs.next() ) {
                sizeCachedValue = rs.getInt( 1 );
                sizeCachedAt    = now;
            }
            return sizeCachedValue;
        }
    } catch ( final SQLException e ) {
        LOG.warn( "PgVectorChunkVectorIndex.size query failed (model={}): {}",
            modelCode, e.getMessage(), e );
        return sizeCachedValue; // stale-but-best-known
    }
}
```

- [ ] **Step 4: Run IT to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexPgTest -q`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndex.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/PgVectorChunkVectorIndexPgTest.java
git commit -m "search: PgVectorChunkVectorIndex isReady/size with cached count"
```

---

### Task 5: `HybridMetricsBridge` — type-conditional size gauge

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridMetricsBridge.java`
- Modify (or create): `wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridMetricsBridgeTest.java`

- [ ] **Step 1: Write failing test asserting the gauge fires for both impls**

```java
@Test
void registers_size_gauge_for_pgvector_backend() {
    final SimpleMeterRegistry reg = new SimpleMeterRegistry();
    final PgVectorChunkVectorIndex fake = mock(PgVectorChunkVectorIndex.class);
    when(fake.size()).thenReturn(12345);
    new HybridMetricsBridge(reg, /* deps... */, fake).register();

    final Gauge g = reg.find("wikantik_search.vector_index.size").gauge();
    assertNotNull(g, "size gauge should be registered for pgvector backend");
    assertEquals(12345.0, g.value(), 0.0001);
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=HybridMetricsBridgeTest -q`
Expected: FAIL.

- [ ] **Step 3: Make the gauge type-conditional**

Replace the `if ( vectorIndex instanceof InMemoryChunkVectorIndex mem ) { … }` block with:

```java
if ( vectorIndex instanceof InMemoryChunkVectorIndex mem ) {
    Gauge.builder( PFX + ".vector_index.size", mem, InMemoryChunkVectorIndex::size )
        .description( "Number of chunk vectors held in the in-memory index" )
        .register( registry );
} else if ( vectorIndex instanceof PgVectorChunkVectorIndex pg ) {
    Gauge.builder( PFX + ".vector_index.size", pg, PgVectorChunkVectorIndex::size )
        .description( "Number of chunk vectors in pgvector for the active model" )
        .register( registry );
}
```

- [ ] **Step 4: Run test to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=HybridMetricsBridgeTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridMetricsBridge.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridMetricsBridgeTest.java
git commit -m "metrics: HybridMetricsBridge size gauge handles both vector backends"
```

---

### Task 6: `SearchSubsystemFactory` — backend selection on property

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemFactory.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/search/subsystem/SearchSubsystemFactoryTest.java`

- [ ] **Step 1: Write failing test for the switch**

```java
@Test
void selects_pgvector_backend_when_property_set() {
    final Properties props = new Properties();
    props.setProperty("wikantik.search.dense.backend", "pgvector");
    props.setProperty("wikantik.search.dense.pgvector.ef_search", "150");
    // Build a SearchSubsystemFactory with a stub engine that returns nulls for the
    // legacy in-memory index. Wire the rest as the existing factory tests do.

    final SearchSubsystem.Services svc = factory.build(deps.withProperties(props));

    assertTrue(svc.chunkVectorIndex() instanceof PgVectorChunkVectorIndex,
        "expected PgVectorChunkVectorIndex, got " + svc.chunkVectorIndex().getClass());
}

@Test
void defaults_to_inmemory_when_property_absent() {
    final Properties props = new Properties();
    final SearchSubsystem.Services svc = factory.build(deps.withProperties(props));
    assertTrue(svc.chunkVectorIndex() instanceof InMemoryChunkVectorIndex);
}

@Test
void rejects_unknown_backend_value() {
    final Properties props = new Properties();
    props.setProperty("wikantik.search.dense.backend", "elasticsearch");
    assertThrows(IllegalArgumentException.class,
        () -> factory.build(deps.withProperties(props)));
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=SearchSubsystemFactoryTest -q`
Expected: 3 NEW failures.

- [ ] **Step 3: Add the switch to the factory**

Replace the `final InMemoryChunkVectorIndex chunkVectorIndex = engine.getManager(...);` block with:

```java
final ChunkVectorIndex chunkVectorIndex;
final String backend = props.getProperty(
    "wikantik.search.dense.backend", "inmemory" ).toLowerCase( Locale.ROOT );
switch ( backend ) {
    case "pgvector" -> {
        final DataSource dataSource = engine.getManager( DataSource.class );
        final String modelCode = props.getProperty(
            "wikantik.search.embedding.model_code", "bge-m3" );
        final int efSearch = Integer.parseInt( props.getProperty(
            "wikantik.search.dense.pgvector.ef_search", "100" ) );
        chunkVectorIndex = new PgVectorChunkVectorIndex( dataSource, modelCode, efSearch );
        LOG.info( "Dense retrieval backend: pgvector HNSW (model={}, ef_search={})",
            modelCode, efSearch );
    }
    case "inmemory" -> {
        chunkVectorIndex = engine.getManager( InMemoryChunkVectorIndex.class );
        LOG.info( "Dense retrieval backend: in-memory brute-force" );
    }
    default -> throw new IllegalArgumentException(
        "wikantik.search.dense.backend must be inmemory|pgvector, got: " + backend );
}
```

Adjust the `SearchSubsystem.Services` constructor and any downstream references that were typed to `InMemoryChunkVectorIndex` — they should accept the `ChunkVectorIndex` interface.

- [ ] **Step 4: Compile-check the module**

Run: `mvn -pl wikantik-main compile test-compile -q`
Expected: BUILD SUCCESS (per the memory: `test-compile` catches signature breakage `compile` skips).

- [ ] **Step 5: Run the factory tests**

Run: `mvn -pl wikantik-main test -Dtest=SearchSubsystemFactoryTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemFactory.java \
        wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystem.java \
        wikantik-main/src/test/java/com/wikantik/search/subsystem/SearchSubsystemFactoryTest.java
git commit -m "search: select chunk vector backend via wikantik.search.dense.backend"
```

---

### Task 7: `EmbeddingIndexService` — dual-write UPSERT

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingIndexService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/search/embedding/EmbeddingIndexServiceTest.java` (or the existing PG IT for this class)

- [ ] **Step 1: Write failing IT asserting both columns are populated**

```java
@Test
void upsert_populates_both_vec_and_embedding_columns() {
    final EmbeddingIndexService svc = new EmbeddingIndexService(dataSource, fakeClient);
    svc.indexAll("bge-m3");

    try (Connection c = dataSource.getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
            "SELECT vec IS NOT NULL, embedding IS NOT NULL "
          + "FROM content_chunk_embeddings WHERE model_code = 'bge-m3'")) {
        while (rs.next()) {
            assertTrue(rs.getBoolean(1), "vec should be populated");
            assertTrue(rs.getBoolean(2), "embedding should be populated");
        }
    }
}
```

- [ ] **Step 2: Run IT to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=EmbeddingIndexServicePgTest -q`
Expected: FAIL — embedding column NULL.

- [ ] **Step 3: Extend the UPSERT to dual-write**

Replace `UPSERT_SQL`:

```java
private static final String UPSERT_SQL =
    "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec, embedding) "
  + "VALUES (?, ?, ?, ?, ?::vector) "
  + "ON CONFLICT (chunk_id, model_code) DO UPDATE SET "
  + "  vec = EXCLUDED.vec, dim = EXCLUDED.dim, "
  + "  embedding = EXCLUDED.embedding, updated = NOW()";
```

In the bind block (`flushBatch` or wherever the UPSERT is executed per row), add:

```java
ps.setString( 5, PgVectorChunkVectorIndex.formatVector( vec ) );
```

- [ ] **Step 4: Run IT to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=EmbeddingIndexServicePgTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingIndexService.java \
        wikantik-main/src/test/java/com/wikantik/search/embedding/EmbeddingIndexServicePgTest.java
git commit -m "embedding: dual-write BYTEA + pgvector during cutover"
```

---

### Task 8: `AsyncEmbeddingIndexListener` — pgvector branch skips the in-memory reload

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/embedding/AsyncEmbeddingIndexListener.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/search/embedding/AsyncEmbeddingIndexListenerTest.java`

- [ ] **Step 1: Write failing test**

```java
@Test
void does_not_call_upsertChunks_on_pgvector_backend() {
    final PgVectorChunkVectorIndex pgIndex = mock(PgVectorChunkVectorIndex.class);
    final AsyncEmbeddingIndexListener listener =
        new AsyncEmbeddingIndexListener(/* deps */, pgIndex);
    listener.actionPerformed(new PageContentChangedEvent("Foo"));
    // PgVectorChunkVectorIndex has no upsertChunks; the listener must not try to
    // dynamic-cast it back to InMemoryChunkVectorIndex either.
    verifyNoInteractions(pgIndex);
}

@Test
void calls_upsertChunks_on_inmemory_backend() {
    final InMemoryChunkVectorIndex memIndex = mock(InMemoryChunkVectorIndex.class);
    final AsyncEmbeddingIndexListener listener =
        new AsyncEmbeddingIndexListener(/* deps */, memIndex);
    listener.actionPerformed(new PageContentChangedEvent("Foo"));
    verify(memIndex).upsertChunks(anyCollection());
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=AsyncEmbeddingIndexListenerTest -q`
Expected: FAIL.

- [ ] **Step 3: Type the listener's index field as the interface and guard the reload call**

Change the field type:

```java
private final ChunkVectorIndex chunkVectorIndex;
```

In the reload step (search for `upsertChunks`):

```java
if ( chunkVectorIndex instanceof InMemoryChunkVectorIndex mem ) {
    mem.upsertChunks( chunkIds );
}
// PgVectorChunkVectorIndex has no per-listener reload step — the INSERT in
// EmbeddingIndexService already keeps the HNSW index in sync.
```

- [ ] **Step 4: Run test to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=AsyncEmbeddingIndexListenerTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/embedding/AsyncEmbeddingIndexListener.java \
        wikantik-main/src/test/java/com/wikantik/search/embedding/AsyncEmbeddingIndexListenerTest.java
git commit -m "embedding: listener skips in-memory reload on pgvector backend"
```

---

### Task 9: Backfill CLI — decode BYTEA, write pgvector literal

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/embedding/PgVectorBackfillCli.java`
- Create: `wikantik-main/src/test/java/com/wikantik/search/embedding/PgVectorBackfillCliTest.java`

- [ ] **Step 1: Write failing IT**

```java
@Test
void backfill_writes_embedding_for_rows_with_null_embedding() {
    seedRowsWithByteaOnly(/* 3 rows, embedding NULL */);
    final PgVectorBackfillCli cli = new PgVectorBackfillCli(dataSource);
    final int written = cli.run("bge-m3", /* force= */ false);
    assertEquals(3, written);

    // Verify all three now have embedding populated and the float values round-trip.
    try (Connection c = dataSource.getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
            "SELECT chunk_id, dim, vec, embedding::text FROM content_chunk_embeddings "
          + "WHERE model_code = 'bge-m3'")) {
        while (rs.next()) {
            final float[] fromBytea = decodeBytea(rs.getBytes(3), rs.getInt(2));
            final float[] fromVector = KgNodeEmbeddingRepository.parseVector(rs.getString(4));
            assertArrayEquals(fromBytea, fromVector, 1e-6f);
        }
    }
}

@Test
void backfill_skips_rows_with_existing_embedding_unless_force() {
    seedRowsWithBoth(/* 2 rows, both columns populated */);
    final PgVectorBackfillCli cli = new PgVectorBackfillCli(dataSource);
    assertEquals(0, cli.run("bge-m3", false));
    assertEquals(2, cli.run("bge-m3", true));
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorBackfillCliTest -q`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the CLI**

```java
package com.wikantik.search.embedding;

import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * One-shot backfill: decodes BYTEA from {@code content_chunk_embeddings.vec},
 * writes the same vector to {@code embedding vector(1024)} via the pgvector
 * string-literal codec. Idempotent on rerun — rows with {@code embedding IS NOT
 * NULL} are skipped unless {@code force} is true.
 *
 * <p>Invoked from {@code bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh}.</p>
 */
public final class PgVectorBackfillCli {

    private static final Logger LOG = LogManager.getLogger( PgVectorBackfillCli.class );

    private final DataSource dataSource;

    public PgVectorBackfillCli( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /**
     * Backfill all rows for {@code modelCode}. Returns the number of rows written.
     * When {@code force} is true, overwrites existing embedding values; otherwise
     * skips rows where {@code embedding IS NOT NULL}.
     */
    public int run( final String modelCode, final boolean force ) {
        final String selectSql = force
            ? "SELECT chunk_id, dim, vec FROM content_chunk_embeddings WHERE model_code = ?"
            : "SELECT chunk_id, dim, vec FROM content_chunk_embeddings "
            + "WHERE model_code = ? AND embedding IS NULL";

        final String updateSql =
            "UPDATE content_chunk_embeddings SET embedding = ?::vector "
          + "WHERE chunk_id = ? AND model_code = ?";

        int written = 0;
        try ( Connection c = dataSource.getConnection();
              PreparedStatement sel = c.prepareStatement( selectSql );
              PreparedStatement upd = c.prepareStatement( updateSql ) ) {
            c.setAutoCommit( false );
            sel.setString( 1, modelCode );
            sel.setFetchSize( 500 );
            try ( ResultSet rs = sel.executeQuery() ) {
                while ( rs.next() ) {
                    final java.util.UUID id = rs.getObject( 1, java.util.UUID.class );
                    final int dim = rs.getInt( 2 );
                    final byte[] raw = rs.getBytes( 3 );
                    final float[] decoded = decode( id, raw, dim );
                    if ( decoded == null ) continue;
                    upd.setString( 1, PgVectorChunkVectorIndex.formatVector( decoded ) );
                    upd.setObject( 2, id );
                    upd.setString( 3, modelCode );
                    upd.executeUpdate();
                    written++;
                }
            }
            c.commit();
        } catch ( final SQLException e ) {
            LOG.warn( "PgVectorBackfillCli failed (model={}, force={}, written-so-far={}): {}",
                modelCode, force, written, e.getMessage(), e );
            throw new RuntimeException( "Backfill failed for model " + modelCode, e );
        }
        LOG.info( "PgVectorBackfillCli: wrote {} rows for model={} (force={})",
            written, modelCode, force );
        return written;
    }

    private static float[] decode( final java.util.UUID id, final byte[] raw, final int dim ) {
        if ( raw == null ) {
            LOG.warn( "Backfill: chunk {} has null vec, skipping", id );
            return null;
        }
        if ( raw.length != dim * Float.BYTES ) {
            LOG.warn( "Backfill: chunk {} vec bytes={} expected {} (dim={}), skipping",
                id, raw.length, dim * Float.BYTES, dim );
            return null;
        }
        final float[] out = new float[ dim ];
        final FloatBuffer fb = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer();
        fb.get( out );
        return out;
    }

    /** Main entrypoint for the shell wrapper. */
    public static void main( final String[] args ) {
        if ( args.length < 1 ) {
            System.err.println( "Usage: PgVectorBackfillCli <modelCode> [--force]" );
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final boolean force = args.length > 1 && "--force".equals( args[ 1 ] );
        final DataSource ds = JndiDataSourceResolver.resolve( "java:comp/env/jdbc/wikantik" );
        final int written = new PgVectorBackfillCli( ds ).run( modelCode, force );
        System.out.println( "backfill wrote " + written + " rows" );
    }
}
```

- [ ] **Step 4: Run IT to confirm pass**

Run: `mvn -pl wikantik-main test -Dtest=PgVectorBackfillCliTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/embedding/PgVectorBackfillCli.java \
        wikantik-main/src/test/java/com/wikantik/search/embedding/PgVectorBackfillCliTest.java
git commit -m "embedding: PgVectorBackfillCli decodes BYTEA into pgvector column"
```

---

### Task 10: Backfill shell wrapper

**Files:**
- Create: `bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
#
# 2026-05-20: One-shot backfill — copy content_chunk_embeddings.vec (BYTEA) into
# the new embedding vector(1024) column added by V032. Idempotent: rows with
# embedding IS NOT NULL are skipped unless --force is given.
#
# Reads DB connection from the standard env (PGHOST/PGUSER/PGPASSWORD/DB_NAME).
# Delegates the per-row decode to PgVectorBackfillCli (Java, wikantik-main jar)
# so the BYTEA codec stays in one place.

set -euo pipefail

show_help() {
    cat <<'EOF'
Usage: 2026-05-20-backfill-chunk-embeddings.sh [--force] [--model bge-m3]

  --force         Overwrite existing embedding values (default: skip non-null).
  --model NAME    Model code to backfill (default: bge-m3).
  -h, --help      Show this help.

Environment:
  DB_NAME, PGHOST, PGUSER, PGPASSWORD — read by the JNDI fallback in the CLI.

Idempotent — re-running without --force is a no-op once the backfill has
completed for a given model. Run once per model code in use.
EOF
}

MODEL="bge-m3"
FORCE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)    FORCE="--force"; shift ;;
        --model)    MODEL="$2"; shift 2 ;;
        -h|--help)  show_help; exit 0 ;;
        *)          echo "unknown arg: $1" >&2; show_help; exit 2 ;;
    esac
done

JAR="$(find "$(dirname "$0")/../../../wikantik-main/target" -maxdepth 1 -name 'wikantik-main-*.jar' | head -1)"
if [[ -z "$JAR" ]]; then
    echo "wikantik-main jar not found; run 'mvn package -pl wikantik-main -am' first." >&2
    exit 1
fi

java -cp "$JAR" com.wikantik.search.embedding.PgVectorBackfillCli "$MODEL" $FORCE
```

- [ ] **Step 2: Make executable**

Run: `chmod +x bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh`

- [ ] **Step 3: Smoke run against the local DB**

Run: `bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh --help`
Expected: help text prints, exit 0.

Run (against local PG with V032 applied + BYTEA rows present):
`bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh --model bge-m3`
Expected: prints `backfill wrote N rows` with N matching the row count for `bge-m3`. Re-run prints `backfill wrote 0 rows`.

- [ ] **Step 4: Commit**

```bash
git add bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh
git commit -m "db: one-shot backfill chunk embeddings into pgvector column"
```

---

### Task 11: Property registration + defaults

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (the canonical defaults file — find the right path with `find . -name 'wikantik.properties'` first)

- [ ] **Step 1: Add the two new properties with comments**

```
# Dense retrieval backend. One of:
#   inmemory  — in-process brute-force float[] scan (legacy, default)
#   pgvector  — pgvector HNSW lookup against content_chunk_embeddings.embedding.
# Flip to 'pgvector' after running the V032 migration AND the one-shot backfill
# script in bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh.
wikantik.search.dense.backend = inmemory

# pgvector HNSW ef_search recall/latency knob. Higher = better recall, more CPU.
# Default 100 matches the design doc; 200-400 trade latency for recall on
# corpora where smoke parity drifts.
wikantik.search.dense.pgvector.ef_search = 100
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl wikantik-main compile -q`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "config: register wikantik.search.dense.backend + ef_search defaults"
```

---

### Task 12: `RetrievalQualitySmokeTest` — parity check across both backends

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/RetrievalQualitySmokeTest.java`

- [ ] **Step 1: Add a parameterised parity test**

```java
@ParameterizedTest
@ValueSource(strings = { "inmemory", "pgvector" })
void core_agent_queries_meet_ndcg_threshold(final String backend) {
    final Properties props = baseProps();
    props.setProperty("wikantik.search.dense.backend", backend);
    final RetrievalQualityRunner runner = buildRunnerWith(props);

    final RetrievalQualityReport report = runner.run("core-agent-queries", "HYBRID");

    assertTrue(report.ndcgAt5() >= 0.5,
        backend + " nDCG@5 = " + report.ndcgAt5() + " < smoke gate 0.5");
}

@Test
void inmemory_and_pgvector_within_recall_epsilon() {
    final RetrievalQualityReport mem  = runWith("inmemory");
    final RetrievalQualityReport pgv  = runWith("pgvector");
    final double delta = Math.abs(mem.ndcgAt5() - pgv.ndcgAt5());
    assertTrue(delta <= 0.02,
        "pgvector nDCG@5 (" + pgv.ndcgAt5() + ") differs from in-memory ("
        + mem.ndcgAt5() + ") by " + delta + " — exceeds 0.02 parity gate");
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main test -Dtest=RetrievalQualitySmokeTest -q`
Expected: PASS for both backends; parity assertion passes.

If the parity assertion fails, do NOT loosen the threshold. Bump `ef_search` (set the second property in `baseProps()` to 200, then 400) until parity holds; if it doesn't, the design's premise is wrong — STOP and escalate.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/eval/RetrievalQualitySmokeTest.java
git commit -m "retrieval-quality: parity-gate pgvector against in-memory backend"
```

---

### Task 13: Full IT reactor

- [ ] **Step 1: Run the whole IT suite to catch cross-module breakage**

Per `feedback_full_it_after_targeted_fix`: targeted `-Dtest=` runs miss cross-module breakage; every prod-code change gates on the full IT reactor.

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Fix anything that broke**

If any IT fails, fix it before moving on. Common suspects:
- `wikantik-it-tests` may have a Cargo-launched test asserting the old in-memory-only manager registration.
- The DataSource manager registration may need a one-line tweak so the new `PgVectorChunkVectorIndex` path can `engine.getManager(DataSource.class)`.

- [ ] **Step 3: Commit any fixes individually**

One commit per IT module touched, with a message naming the failure mode.

---

### Task 14: CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a one-line note under "Hybrid Retrieval"**

Locate the `**[docs/wikantik-pages/HybridRetrieval.md]**` bullet in the "Active Design Documents" section. After its existing line, append:

```
  Backend is selectable via `wikantik.search.dense.backend = inmemory | pgvector`
  (default `inmemory`). The pgvector path uses an HNSW index on
  `content_chunk_embeddings.embedding`; see
  [docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md].
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: point CLAUDE.md at the pgvector dense-retrieval flag"
```

---

## Self-Review

**Spec coverage:**
- ✅ V032 DDL → Task 1
- ✅ One-shot backfill → Tasks 9 + 10
- ✅ `PgVectorChunkVectorIndex` impl → Tasks 2, 3, 4
- ✅ Backend selection flag → Task 6
- ✅ Dual-write → Task 7
- ✅ Listener no-op on pgvector → Task 8
- ✅ Metrics gauge bridge → Task 5
- ✅ Property defaults → Task 11
- ✅ Recall smoke gate → Task 12
- ✅ Full IT reactor before commit → Task 13
- ✅ CLAUDE.md pointer → Task 14
- ⏳ V033 (drop legacy columns) — deferred; not in this plan, lands after the soak.

**Placeholder scan:** No "TBD", "TODO", or "implement later" in any task. Migration content, SQL strings, Java code, ef_search default (100), parity ε (0.02), smoke gate (nDCG@5 ≥ 0.5) all concrete.

**Type consistency:** `ChunkVectorIndex` interface used consistently across `SearchSubsystem.Services` (Task 6), `AsyncEmbeddingIndexListener` (Task 8), `HybridMetricsBridge` (Task 5). `PgVectorChunkVectorIndex.formatVector` static is used by both the CLI (Task 9) and the dual-write UPSERT (Task 7) — single codec source.

**Risk reminder:** If the parity test in Task 12 fails after bumping `ef_search` to 400, STOP. The design's recall assumption is wrong and warrants escalation, not threshold relaxation.
