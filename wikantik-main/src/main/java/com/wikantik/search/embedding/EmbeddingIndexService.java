/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.search.embedding;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Production data-layer for dense-vector embeddings of
 * {@code kg_content_chunks} rows. Writes to {@code content_chunk_embeddings}
 * (V009) via idempotent upserts so both full rebuilds and incremental page
 * saves converge on the same invariant.
 *
 * <p>The batch-with-per-item-fallback pattern is lifted directly from
 * {@code ExperimentIndexer} (retrieval-experiment harness, test scope): when a
 * batch call to the embedding backend fails (the concrete reason we saw in the
 * offline harness was bge-m3 returning HTTP 500 with NaN in the response for
 * specific inputs — deterministic per input), we retry item-by-item. Items that
 * still fail individually are <em>skipped</em> rather than failed: a poisoned
 * chunk must not cap the indexer at that point. The skip is logged.</p>
 *
 * <p>Construction takes a {@link DataSource} and a concrete
 * {@link TextEmbeddingClient} — no static wiring, no factory lookups. This
 * makes the class trivially testable with a mock client and a live JDBC
 * container.</p>
 *
 * <p>All database access goes through {@link Jdbc}. The full-corpus rebuild
 * ({@link #indexAll}) and stale-reconcile ({@link #indexStale}) paths stream
 * the candidate set on one long-lived read transaction ({@link
 * Jdbc#forEachRow} run inside {@link Jdbc#inTransaction} — PostgreSQL only
 * honours a server-side cursor when auto-commit is off) while writes are
 * committed periodically: every {@value #DEFAULT_COMMIT_BATCH_SIZE} embedded
 * rows are flushed through their own short-lived {@link Jdbc#inTransaction}
 * call (its own connection, its own commit) so an interrupted run keeps
 * whatever it already finished. {@link #indexChunks} re-embeds a small,
 * caller-supplied chunk set and commits once, atomically, on a single
 * connection — no incremental durability needed for a page-save-sized batch.</p>
 */
public class EmbeddingIndexService {

    private static final Logger LOG = LogManager.getLogger( EmbeddingIndexService.class );

    /** Default batch size matches {@code EmbeddingConfig.DEFAULT_BATCH_SIZE}. */
    public static final int DEFAULT_BATCH_SIZE = 32;

    /**
     * Rows committed per transaction during a backfill. Default 256 — eight
     * {@link #DEFAULT_BATCH_SIZE} batches, so commits land on batch boundaries.
     *
     * <p>Sized against inference cost, not commit cost: on a CPU-only embedder a
     * chunk takes on the order of half a second, so 256 rows is minutes of work
     * while the commit itself is sub-millisecond. That bounds what an interrupted
     * run loses to a few minutes instead of the entire corpus, and the commit
     * overhead stays four orders of magnitude below the embedding time.</p>
     */
    public static final int DEFAULT_COMMIT_BATCH_SIZE = 256;

    private static final String SELECT_ALL_SQL =
        "SELECT id, text, heading_path, page_name FROM kg_content_chunks ORDER BY page_name, chunk_index";

    private static final String SELECT_BY_IDS_SQL =
        "SELECT id, text, heading_path, page_name FROM kg_content_chunks WHERE id = ANY( ? ) "
      + "ORDER BY page_name, chunk_index";

    /**
     * Stale-chunk selection: chunks with no embedding row for the given model yet,
     * OR whose {@code content_chunk_embeddings.updated} timestamp predates the chunk's
     * own {@code kg_content_chunks.modified} timestamp. Used by {@link #indexStale(String)}
     * to heal gaps left by in-memory queue loss across restarts and by bulk-save cascades
     * (e.g. {@code HubSyncFilter} secondary saves).
     */
    private static final String SELECT_STALE_SQL =
        "SELECT c.id, c.text, c.heading_path, c.page_name "
      + "FROM kg_content_chunks c "
      + "LEFT JOIN content_chunk_embeddings e ON e.chunk_id = c.id AND e.model_code = ? "
      + "WHERE e.chunk_id IS NULL OR e.updated < c.modified "
      + "ORDER BY c.page_name, c.chunk_index";

    /**
     * Upsert so both full rebuilds and incremental page-save calls converge.
     * Intentionally touches {@code updated} so operators can see when a row
     * was last refreshed even when dim/vec are equal across runs.
     *
     * <p>Dual-write: the legacy {@code vec BYTEA} column and the new
     * {@code embedding vector(1024)} column (added in V032) are both
     * populated on every upsert. This keeps both the in-memory BYTEA backend
     * and the pgvector HNSW backend correct during the cutover window and
     * ensures a safe rollback to the BYTEA path if needed.</p>
     */
    private static final String UPSERT_SQL =
        "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec, embedding) "
      + "VALUES (?, ?, ?, ?, ?::vector) "
      + "ON CONFLICT (chunk_id, model_code) DO UPDATE SET "
      + "  vec = EXCLUDED.vec, dim = EXCLUDED.dim, "
      + "  embedding = EXCLUDED.embedding, updated = NOW()";

    private static final String DELETE_BY_MODEL_SQL =
        "DELETE FROM content_chunk_embeddings WHERE model_code = ?";

    /**
     * Snapshot row surfaced by {@link #status(String)} so admin endpoints can
     * report indexing progress for the live model.
     *
     * @param modelCode   the model the snapshot was taken for
     * @param dim         the vector dimension on record (or {@code 0} when the
     *                    model has no rows yet)
     * @param rowCount    total {@code (chunk_id, model_code)} rows for this model
     * @param lastUpdated most recent {@code updated} timestamp, or {@code null}
     *                    if no rows exist
     */
    public record Status( String modelCode, int dim, int rowCount, Instant lastUpdated ) {}

    /**
     * Allows tests to replace {@link Thread#sleep} with a no-op so transient-retry
     * backoff does not slow unit/integration tests. Package-private so the same
     * package's test class can implement it as a lambda.
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep( long millis ) throws InterruptedException;
    }

    private final Jdbc jdbc;
    private final TextEmbeddingClient client;
    private final int batchSize;
    /** page_name → frontmatter context for contextual document embeddings; never null. */
    private final java.util.function.Function< String, EmbeddingTextBuilder.PageContext > contextResolver;
    /** Rows to accumulate before committing; see {@link #DEFAULT_COMMIT_BATCH_SIZE}. */
    private final int commitBatchSize;

    /**
     * Maximum number of whole-batch retries on a transient embedding failure
     * before the reconcile is aborted. Default 3. Configurable for tests.
     */
    private int maxTransientRetries = 3;

    /** Sleeper used for exponential backoff between transient-retry attempts. Tests inject a no-op. */
    private Sleeper sleeper = Thread::sleep;

    public EmbeddingIndexService( final DataSource dataSource, final TextEmbeddingClient client ) {
        this( dataSource, client, DEFAULT_BATCH_SIZE );
    }

    public EmbeddingIndexService( final DataSource dataSource, final TextEmbeddingClient client,
                                  final int batchSize ) {
        this( dataSource, client, batchSize, null );
    }

    /**
     * Adds a per-page context resolver (frontmatter title/cluster/summary). The indexer
     * prepends this structured context to each chunk's body via
     * {@link EmbeddingTextBuilder#forDocument(EmbeddingTextBuilder.PageContext, java.util.List, String)}
     * — the 2026-06-14 contextual-embedding win (section recall@12 ~0.60 → ~0.74). A null
     * resolver degrades to {@link EmbeddingTextBuilder.PageContext#EMPTY} (heading-path only).
     */
    public EmbeddingIndexService( final DataSource dataSource, final TextEmbeddingClient client,
                                  final int batchSize,
                                  final java.util.function.Function< String, EmbeddingTextBuilder.PageContext > contextResolver ) {
        this( dataSource, client, batchSize, contextResolver, DEFAULT_COMMIT_BATCH_SIZE );
    }

    /**
     * Adds an explicit commit interval. A full backfill is hours of CPU inference;
     * committing only at the end means any interruption discards all of it, so
     * completed work is committed every {@code commitBatchSize} rows.
     *
     * @param commitBatchSize rows to accumulate before committing; must be positive
     */
    public EmbeddingIndexService( final DataSource dataSource, final TextEmbeddingClient client,
                                  final int batchSize,
                                  final java.util.function.Function< String, EmbeddingTextBuilder.PageContext > contextResolver,
                                  final int commitBatchSize ) {
        if ( commitBatchSize <= 0 ) {
            throw new IllegalArgumentException( "commitBatchSize must be positive, got " + commitBatchSize );
        }
        this.commitBatchSize = commitBatchSize;
        if ( dataSource == null ) {
            throw new IllegalArgumentException( "dataSource must not be null" );
        }
        if ( client == null ) {
            throw new IllegalArgumentException( "client must not be null" );
        }
        if ( batchSize <= 0 ) {
            throw new IllegalArgumentException( "batchSize must be positive, got " + batchSize );
        }
        this.jdbc = new Jdbc( dataSource );
        this.client = client;
        this.batchSize = batchSize;
        this.contextResolver = contextResolver != null
            ? contextResolver : pageName -> EmbeddingTextBuilder.PageContext.EMPTY;
    }

    /**
     * Overrides retry behaviour for unit/integration tests. Injects a no-op
     * sleeper so exponential backoff does not add wall-clock delay to the test
     * suite, and a reduced retry count to keep tests fast.
     *
     * <p><strong>Not for production use.</strong> Only intended to be called
     * from test code in the same package before the first indexing call.</p>
     */
    void configureTransientRetryForTest( final int retries, final Sleeper sleeper ) {
        this.maxTransientRetries = retries;
        this.sleeper = sleeper;
    }

    /** Batch size this service uses when fanning out to the embedding backend. */
    public int batchSize() { return batchSize; }

    /**
     * Full-corpus rebuild. Embeds every chunk regardless of whether an
     * embedding already exists for {@code modelCode}; callers who only want
     * missing rows should delete first via {@link #deleteByModel(String)}.
     *
     * @param modelCode the model identifier written into {@code model_code}
     * @return number of rows successfully upserted (poisoned chunks excluded)
     */
    public int indexAll( final String modelCode ) {
        return indexAll( modelCode, null );
    }

    /**
     * Full-corpus rebuild with batch-level progress callback. Identical to
     * {@link #indexAll(String)} but invokes {@code onBatchFlushed} after every
     * successful flush (each full mid-drain {@link #batchSize} chunk and the
     * final partial one) with the running upserted total. The callback exists
     * because commits only land periodically (every {@link #commitBatchSize}
     * rows), so external observers polling {@code content_chunk_embeddings.row_count}
     * don't see incremental progress — the rebuild orchestrator needs an
     * in-process hook to drive the admin progress bar.
     *
     * <p>Any exception thrown by the callback is logged at WARN and swallowed —
     * progress reporting is best-effort and must not abort the indexing run.</p>
     *
     * @param modelCode       the model identifier written into {@code model_code}
     * @param onBatchFlushed  receives the running upserted count after each
     *                        flushed batch; may be {@code null} for no callback
     * @return number of rows successfully upserted (poisoned chunks excluded)
     */
    public int indexAll( final String modelCode, final IntConsumer onBatchFlushed ) {
        requireModelCode( modelCode );
        return indexRows( modelCode, SELECT_ALL_SQL, SqlBinder.NONE, onBatchFlushed,
            "indexAll", "embedding indexer" );
    }

    /**
     * Startup reconciliation: embeds only the chunks that are missing or stale
     * (embedding {@code updated} &lt; chunk {@code modified}). Called by
     * {@link BootstrapEmbeddingIndexer#startIfNeeded()} on every restart so gaps
     * left by in-memory queue loss or bulk-save cascades are healed without
     * triggering a full reindex.
     *
     * @param modelCode the model identifier written into {@code model_code}
     * @return number of rows successfully upserted (already-current rows skipped,
     *         poisoned chunks excluded)
     */
    public int indexStale( final String modelCode ) {
        requireModelCode( modelCode );
        return indexRows( modelCode, SELECT_STALE_SQL, ps -> ps.setString( 1, modelCode ), null,
            "indexStale", "embedding reconcile" );
    }

    /**
     * Shared drain loop for {@link #indexAll} and {@link #indexStale}: streams
     * {@code selectSql} on one long-lived read transaction (autocommit off is
     * what lets PostgreSQL honour the server-side cursor — see {@link Jdbc#forEachRow}),
     * embeds in {@link #batchSize}-sized chunks, and periodically commits the
     * embedded rows through their own short-lived {@link Jdbc#inTransaction}
     * call every {@link #commitBatchSize} rows (plus a final flush for whatever
     * remains) so an interrupted run keeps its completed work. Two connections
     * are in play at any moment — the read transaction never writes, and each
     * write transaction opens (and closes) its own connection — for exactly the
     * reason the two-connection design existed before this migration: a write
     * commit must never invalidate the read cursor.
     */
    private int indexRows( final String modelCode, final String selectSql, final SqlBinder selectBinder,
                           final IntConsumer onBatchFlushed, final String opName, final String progressLabel ) {
        final int[] upserted = { 0 };
        try {
            jdbc.inTransaction( readConn -> {
                final List< UUID > batchIds = new ArrayList<>( batchSize );
                final List< String > batchTexts = new ArrayList<>( batchSize );
                final Map< String, EmbeddingTextBuilder.PageContext > ctxMemo = new HashMap<>();
                final List< UUID > pendingIds = new ArrayList<>( commitBatchSize );
                final List< float[] > pendingVectors = new ArrayList<>( commitBatchSize );
                final int[] progressThreshold = { 200 };

                jdbc.forEachRow( readConn, selectSql, selectBinder, 500, rs -> {
                    batchIds.add( rs.getObject( 1, UUID.class ) );
                    final EmbeddingTextBuilder.PageContext ctx =
                        ctxMemo.computeIfAbsent( rs.getString( 4 ), contextResolver );
                    batchTexts.add( EmbeddingTextBuilder.forDocument(
                        ctx, readHeadingPath( rs, 3 ), rs.getString( 2 ) ) );
                    if ( batchIds.size() >= batchSize ) {
                        upserted[ 0 ] += stageEmbeddings( batchIds, batchTexts, pendingIds, pendingVectors );
                        batchIds.clear();
                        batchTexts.clear();
                        fireProgress( onBatchFlushed, upserted[ 0 ] );
                        maybeLogProgress( progressLabel, modelCode, upserted[ 0 ], progressThreshold );
                        if ( pendingIds.size() >= commitBatchSize ) {
                            commitPending( modelCode, pendingIds, pendingVectors );
                        }
                    }
                } );
                if ( !batchIds.isEmpty() ) {
                    upserted[ 0 ] += stageEmbeddings( batchIds, batchTexts, pendingIds, pendingVectors );
                    fireProgress( onBatchFlushed, upserted[ 0 ] );
                    maybeLogProgress( progressLabel, modelCode, upserted[ 0 ], progressThreshold );
                }
                if ( !pendingIds.isEmpty() ) {
                    commitPending( modelCode, pendingIds, pendingVectors );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "{} rolled back uncommitted tail (model={}, committed={}): {}",
                opName, modelCode, upserted[ 0 ], e.getMessage(), e );
            throw new RuntimeException( opName + " failed for " + modelCode, e );
        } catch ( final RuntimeException e ) {
            LOG.warn( "{} rolled back uncommitted tail on runtime error (model={}, committed={}): {}",
                opName, modelCode, upserted[ 0 ], e.getMessage(), e );
            throw e;
        }
        LOG.info( "Embedding {} complete: model={} upserted={}", opName, modelCode, upserted[ 0 ] );
        return upserted[ 0 ];
    }

    private static void fireProgress( final IntConsumer cb, final int upserted ) {
        if ( cb == null ) {
            return;
        }
        try {
            cb.accept( upserted );
        } catch( final RuntimeException e ) {
            LOG.warn( "indexAll progress callback failed (upserted={}): {}", upserted, e.getMessage(), e );
        }
    }

    private static void maybeLogProgress( final String progressLabel, final String modelCode,
                                          final int upserted, final int[] progressThreshold ) {
        if ( upserted >= progressThreshold[ 0 ] ) {
            LOG.info( "  {}: {} rows committed so far (model={})", progressLabel, upserted, modelCode );
            progressThreshold[ 0 ] += 200;
        }
    }

    /**
     * Incremental re-index of a specific chunk ID set. Used by the page-save
     * listener after {@code ChunkProjector} rewrites a page's chunks.
     *
     * @param chunkIds  the chunk rows to re-embed; empty/null collection is a no-op
     * @param modelCode the model identifier written into {@code model_code}
     * @return number of rows successfully upserted (poisoned chunks excluded)
     */
    public int indexChunks( final Collection< UUID > chunkIds, final String modelCode ) {
        requireModelCode( modelCode );
        if ( chunkIds == null || chunkIds.isEmpty() ) {
            return 0;
        }
        final int[] upserted = { 0 };
        try {
            jdbc.inTransaction( conn -> {
                final UUID[] idArray = chunkIds.toArray( UUID[]::new );
                final List< UUID > batchIds = new ArrayList<>( batchSize );
                final List< String > batchTexts = new ArrayList<>( batchSize );
                final Map< String, EmbeddingTextBuilder.PageContext > ctxMemo = new HashMap<>();
                final List< UUID > pendingIds = new ArrayList<>();
                final List< float[] > pendingVectors = new ArrayList<>();

                // null writeConn-equivalent = no mid-drain commits: this path re-embeds one
                // page's chunks after a save; all-or-nothing is the right semantic and the
                // set is far too small to need incremental durability.
                jdbc.forEachRow( conn, SELECT_BY_IDS_SQL,
                    ps -> ps.setArray( 1, conn.createArrayOf( "uuid", idArray ) ), 500, rs -> {
                    batchIds.add( rs.getObject( 1, UUID.class ) );
                    final EmbeddingTextBuilder.PageContext ctx =
                        ctxMemo.computeIfAbsent( rs.getString( 4 ), contextResolver );
                    batchTexts.add( EmbeddingTextBuilder.forDocument(
                        ctx, readHeadingPath( rs, 3 ), rs.getString( 2 ) ) );
                    if ( batchIds.size() >= batchSize ) {
                        upserted[ 0 ] += stageEmbeddings( batchIds, batchTexts, pendingIds, pendingVectors );
                        batchIds.clear();
                        batchTexts.clear();
                    }
                } );
                if ( !batchIds.isEmpty() ) {
                    upserted[ 0 ] += stageEmbeddings( batchIds, batchTexts, pendingIds, pendingVectors );
                }
                if ( !pendingIds.isEmpty() ) {
                    jdbc.batch( conn, UPSERT_SQL, buildUpsertBinders( modelCode, pendingIds, pendingVectors ) );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "indexChunks rolled back (model={}, ids={}): {}",
                modelCode, chunkIds.size(), e.getMessage(), e );
            throw new RuntimeException( "indexChunks failed for " + modelCode, e );
        } catch ( final RuntimeException e ) {
            LOG.warn( "indexChunks rolled back on runtime error (model={}, ids={}): {}",
                modelCode, chunkIds.size(), e.getMessage(), e );
            throw e;
        }
        return upserted[ 0 ];
    }

    /**
     * Removes every row for {@code modelCode}. Used when swapping the
     * production embedding model so stale vectors aren't read back on the
     * query path.
     *
     * @return number of rows deleted
     */
    public int deleteByModel( final String modelCode ) {
        requireModelCode( modelCode );
        try {
            final int removed = jdbc.update( DELETE_BY_MODEL_SQL, ps -> ps.setString( 1, modelCode ) );
            LOG.info( "Embedding deleteByModel removed {} rows (model={})", removed, modelCode );
            return removed;
        } catch( final SQLException e ) {
            LOG.warn( "deleteByModel failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "deleteByModel failed for " + modelCode, e );
        }
    }

    /**
     * Returns the current indexing status for {@code modelCode} so admin
     * endpoints can surface progress without inventing their own SQL. Returns
     * a zero/null-filled snapshot when the model has no rows yet.
     */
    public Status status( final String modelCode ) {
        requireModelCode( modelCode );
        final String sql = "SELECT COUNT(*), MAX(dim), MAX(updated) "
                         + "FROM content_chunk_embeddings WHERE model_code = ?";
        try {
            return jdbc.queryOne( sql, ps -> ps.setString( 1, modelCode ), rs -> {
                final int rows = rs.getInt( 1 );
                final int dim = rs.getInt( 2 );
                final Timestamp ts = rs.getTimestamp( 3 );
                return new Status( modelCode, dim, rows, ts == null ? null : ts.toInstant() );
            } ).orElseGet( () -> new Status( modelCode, 0, 0, null ) );
        } catch( final SQLException e ) {
            LOG.warn( "embedding status query failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "status failed for " + modelCode, e );
        }
    }

    // ---- internals ----

    /**
     * Embeds one {@link #batchSize}-sized chunk of {@code ids}/{@code texts} and stages the
     * successfully-embedded rows (poisoned ones excluded) into {@code pendingIds}/{@code
     * pendingVectors} for a later {@link #commitPending} or direct {@link Jdbc#batch} call.
     *
     * @return count of rows staged (poisoned chunks excluded)
     */
    private int stageEmbeddings( final List< UUID > ids, final List< String > texts,
                                 final List< UUID > pendingIds, final List< float[] > pendingVectors ) {
        final List< float[] > vectors = embedBatchSkippingPoisoned( ids, texts );
        int staged = 0;
        for( int i = 0; i < ids.size(); i++ ) {
            final float[] v = vectors.get( i );
            if ( v == null ) continue;       // poisoned — upstream already logged the cause
            pendingIds.add( ids.get( i ) );
            pendingVectors.add( v );
            staged++;
        }
        return staged;
    }

    /**
     * Commits {@code pendingIds}/{@code pendingVectors} in one short-lived transaction — its
     * own connection, opened and committed by this call alone — then clears both lists. This
     * is the "one {@link Jdbc#inTransaction} per commit batch" that gives {@link #indexRows}
     * its periodic-commit durability: the read transaction stays open on a separate connection
     * throughout, unaffected by this write transaction's commit.
     */
    private void commitPending( final String modelCode,
                                final List< UUID > pendingIds, final List< float[] > pendingVectors )
            throws SQLException {
        final List< SqlBinder > binders = buildUpsertBinders( modelCode, pendingIds, pendingVectors );
        jdbc.inTransaction( conn -> jdbc.batch( conn, UPSERT_SQL, binders ) );
        pendingIds.clear();
        pendingVectors.clear();
    }

    private static List< SqlBinder > buildUpsertBinders( final String modelCode,
                                                          final List< UUID > ids, final List< float[] > vectors ) {
        final List< SqlBinder > binders = new ArrayList<>( ids.size() );
        for( int i = 0; i < ids.size(); i++ ) {
            final UUID id = ids.get( i );
            final float[] v = vectors.get( i );
            binders.add( ps -> {
                ps.setObject( 1, id );
                ps.setString( 2, modelCode );
                ps.setInt( 3, v.length );
                ps.setBytes( 4, encodeVector( v ) );
                ps.setString( 5, PgVectorChunkVectorIndex.formatVector( v ) );
            } );
        }
        return binders;
    }

    /**
     * Embeds a batch with transient-failure awareness.
     *
     * <p><b>Transient backend failures</b> (HTTP 503/429/5xx, connection errors —
     * flagged via {@link EmbeddingException#isTransient()}): the whole batch is
     * retried up to {@link #maxTransientRetries} times with exponential backoff
     * (500 ms × 2<sup>attempt</sup>). If the backend is still unavailable after
     * all retries, a transient {@link EmbeddingException} is rethrown — the
     * reconcile aborts, chunks stay un-embedded (not poisoned), and the next
     * reconcile cycle picks them up.</p>
     *
     * <p><b>Permanent / poison-pill failures</b> (non-transient
     * {@link EmbeddingException} or any other {@link RuntimeException}): falls
     * through to per-item retry. Items that still fail individually are returned
     * as {@code null} so the caller can skip them without aborting the batch.
     * If a per-item call throws a <em>transient</em> exception, it is rethrown
     * immediately (the backend is down, not the chunk).</p>
     */
    private List< float[] > embedBatchSkippingPoisoned( final List< UUID > ids,
                                                        final List< String > texts ) {
        // Batch call with transient-retry loop.
        for( int attempt = 0; attempt <= maxTransientRetries; attempt++ ) {
            try {
                return new ArrayList<>( client.embed( texts, EmbeddingKind.DOCUMENT ) );
            } catch( final EmbeddingException batchFailed ) {
                if( !batchFailed.isTransient() ) {
                    // Non-transient batch failure: fall through to per-item retry.
                    LOG.warn( "Embedding batch of {} failed (non-transient), retrying per-item: {}",
                        texts.size(), batchFailed.getMessage() );
                    break;
                }
                // Transient: retry if we have attempts left.
                if( attempt < maxTransientRetries ) {
                    LOG.warn( "Embedding batch of {} failed (transient attempt {}/{}): {}",
                        texts.size(), attempt + 1, maxTransientRetries, batchFailed.getMessage() );
                    try {
                        sleeper.sleep( 500L << attempt );   // 500 ms, 1000 ms, 2000 ms, …
                    } catch( final InterruptedException ie ) {
                        Thread.currentThread().interrupt();
                        throw new EmbeddingException(
                            "Embedding backend retry interrupted — aborting reconcile, will retry next cycle",
                            ie, true );
                    }
                } else {
                    // All retries exhausted — abort; chunks remain stale for next reconcile.
                    throw new EmbeddingException(
                        "Embedding backend unavailable after " + maxTransientRetries
                        + " retries — aborting reconcile, will retry next cycle",
                        batchFailed, true );
                }
            } catch( final RuntimeException batchFailed ) {
                // Non-EmbeddingException (legacy or third-party exception): treat as non-transient,
                // fall through to per-item retry (preserves existing behaviour for bge-m3 NaN cases).
                LOG.warn( "Embedding batch of {} failed, retrying per-item: {}",
                    texts.size(), batchFailed.getMessage() );
                break;
            }
        }

        // Per-item fallback (reached only for non-transient batch failures).
        final List< float[] > out = new ArrayList<>( texts.size() );
        int poisoned = 0;
        for( int i = 0; i < texts.size(); i++ ) {
            try {
                out.add( client.embed( List.of( texts.get( i ) ), EmbeddingKind.DOCUMENT ).get( 0 ) );
            } catch( final EmbeddingException perItem ) {
                if( perItem.isTransient() ) {
                    // Backend went down mid-fallback — propagate immediately; do NOT poison the chunk.
                    throw perItem;
                }
                poisoned++;
                LOG.warn( "Skipping chunk id={} len={} — embedding failed: {}",
                    ids.get( i ), texts.get( i ) == null ? 0 : texts.get( i ).length(),
                    perItem.getMessage() );
                out.add( null );
            } catch( final RuntimeException perItem ) {
                // Non-EmbeddingException per-item failure: mark poisoned (existing behaviour).
                poisoned++;
                LOG.warn( "Skipping chunk id={} len={} — embedding failed: {}",
                    ids.get( i ), texts.get( i ) == null ? 0 : texts.get( i ).length(),
                    perItem.getMessage() );
                out.add( null );
            }
        }
        if ( poisoned > 0 ) {
            LOG.info( "Per-item fallback: {} succeeded, {} poisoned/skipped",
                texts.size() - poisoned, poisoned );
        }
        return out;
    }

    /**
     * Reads a PostgreSQL {@code text[]} column as an immutable {@link List}.
     * Treats null columns as empty so root-level chunks (no heading context)
     * fall through to body-only embedding via {@link EmbeddingTextBuilder}.
     */
    private static List< String > readHeadingPath( final ResultSet rs, final int col ) throws SQLException {
        final Array arr = rs.getArray( col );
        if ( arr == null ) return List.of();
        final String[] raw = (String[]) arr.getArray();
        return raw == null ? List.of() : List.of( raw );
    }

    /**
     * Little-endian float32 byte stream — matches {@code VectorCodec.encode}
     * in the experiment package and pgvector's wire format so rows can
     * migrate across without re-embedding.
     */
    private static byte[] encodeVector( final float[] vec ) {
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES )
            .order( ByteOrder.LITTLE_ENDIAN );
        for( final float f : vec ) buf.putFloat( f );
        return buf.array();
    }

    private static void requireModelCode( final String modelCode ) {
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
    }
}
