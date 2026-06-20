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

import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Production data-layer for dense-vector embeddings of
 * {@code kg_content_chunks} rows. Writes to {@code content_chunk_embeddings}
 * (V009) via idempotent upserts so both full rebuilds and incremental page
 * saves converge on the same invariant.
 *
 * <p>The batch-with-per-item-fallback pattern is lifted directly from
 * {@link com.wikantik.search.embedding.experiment.ExperimentIndexer}: when a
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
 */
public class EmbeddingIndexService {

    private static final Logger LOG = LogManager.getLogger( EmbeddingIndexService.class );

    /** Default batch size matches {@code EmbeddingConfig.DEFAULT_BATCH_SIZE}. */
    public static final int DEFAULT_BATCH_SIZE = 32;

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

    private final DataSource dataSource;
    private final TextEmbeddingClient client;
    private final int batchSize;
    /** page_name → frontmatter context for contextual document embeddings; never null. */
    private final java.util.function.Function< String, EmbeddingTextBuilder.PageContext > contextResolver;

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
        if ( dataSource == null ) {
            throw new IllegalArgumentException( "dataSource must not be null" );
        }
        if ( client == null ) {
            throw new IllegalArgumentException( "client must not be null" );
        }
        if ( batchSize <= 0 ) {
            throw new IllegalArgumentException( "batchSize must be positive, got " + batchSize );
        }
        this.dataSource = dataSource;
        this.client = client;
        this.batchSize = batchSize;
        this.contextResolver = contextResolver != null
            ? contextResolver : pageName -> EmbeddingTextBuilder.PageContext.EMPTY;
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
     * successful batch with the running upserted total. The callback exists
     * because {@code conn.commit()} only fires once at the end, so external
     * observers polling {@code content_chunk_embeddings.row_count} don't see
     * incremental progress — the rebuild orchestrator needs an in-process hook
     * to drive the admin progress bar.
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
        int upserted = 0;
        int progress = 200;
        try( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try( PreparedStatement sel = conn.prepareStatement( SELECT_ALL_SQL );
                 PreparedStatement ins = conn.prepareStatement( UPSERT_SQL ) ) {
                sel.setFetchSize( 500 );
                try( ResultSet rs = sel.executeQuery() ) {
                    final List< UUID > batchIds = new ArrayList<>( batchSize );
                    final List< String > batchTexts = new ArrayList<>( batchSize );
                    final java.util.Map< String, EmbeddingTextBuilder.PageContext > ctxMemo =
                        new java.util.HashMap<>();
                    while( rs.next() ) {
                        batchIds.add( rs.getObject( 1, UUID.class ) );
                        final EmbeddingTextBuilder.PageContext ctx =
                            ctxMemo.computeIfAbsent( rs.getString( 4 ), contextResolver );
                        batchTexts.add( EmbeddingTextBuilder.forDocument(
                            ctx, readHeadingPath( rs, 3 ), rs.getString( 2 ) ) );
                        if ( batchIds.size() >= batchSize ) {
                            upserted += flushBatch( ins, modelCode, batchIds, batchTexts );
                            batchIds.clear();
                            batchTexts.clear();
                            fireProgress( onBatchFlushed, upserted );
                            if ( upserted >= progress ) {
                                LOG.info( "  embedding indexer: {} rows upserted so far (model={})",
                                    upserted, modelCode );
                                progress += 200;
                            }
                        }
                    }
                    if ( !batchIds.isEmpty() ) {
                        upserted += flushBatch( ins, modelCode, batchIds, batchTexts );
                        fireProgress( onBatchFlushed, upserted );
                    }
                }
                conn.commit();
            } catch( final SQLException e ) {
                conn.rollback();
                LOG.warn( "indexAll rolled back (model={}): {}", modelCode, e.getMessage(), e );
                throw new RuntimeException( "indexAll failed for " + modelCode, e );
            }
        } catch( final SQLException e ) {
            LOG.warn( "indexAll connection failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "indexAll failed for " + modelCode, e );
        }
        LOG.info( "Embedding indexAll complete: model={} upserted={}", modelCode, upserted );
        return upserted;
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
        int upserted = 0;
        try( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try( PreparedStatement sel = conn.prepareStatement( SELECT_STALE_SQL );
                 PreparedStatement ins = conn.prepareStatement( UPSERT_SQL ) ) {
                sel.setString( 1, modelCode );
                sel.setFetchSize( 500 );
                try( ResultSet rs = sel.executeQuery() ) {
                    final List< UUID > batchIds = new ArrayList<>( batchSize );
                    final List< String > batchTexts = new ArrayList<>( batchSize );
                    final java.util.Map< String, EmbeddingTextBuilder.PageContext > ctxMemo =
                        new java.util.HashMap<>();
                    while( rs.next() ) {
                        batchIds.add( rs.getObject( 1, UUID.class ) );
                        final EmbeddingTextBuilder.PageContext ctx =
                            ctxMemo.computeIfAbsent( rs.getString( 4 ), contextResolver );
                        batchTexts.add( EmbeddingTextBuilder.forDocument(
                            ctx, readHeadingPath( rs, 3 ), rs.getString( 2 ) ) );
                        if ( batchIds.size() >= batchSize ) {
                            upserted += flushBatch( ins, modelCode, batchIds, batchTexts );
                            batchIds.clear();
                            batchTexts.clear();
                        }
                    }
                    if ( !batchIds.isEmpty() ) {
                        upserted += flushBatch( ins, modelCode, batchIds, batchTexts );
                    }
                }
                conn.commit();
            } catch( final SQLException e ) {
                conn.rollback();
                LOG.warn( "indexStale rolled back (model={}): {}", modelCode, e.getMessage(), e );
                throw new RuntimeException( "indexStale failed for " + modelCode, e );
            }
        } catch( final SQLException e ) {
            LOG.warn( "indexStale connection failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "indexStale failed for " + modelCode, e );
        }
        LOG.info( "Embedding indexStale complete: model={} upserted={}", modelCode, upserted );
        return upserted;
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
        int upserted = 0;
        try( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try( PreparedStatement sel = conn.prepareStatement( SELECT_BY_IDS_SQL );
                 PreparedStatement ins = conn.prepareStatement( UPSERT_SQL ) ) {
                final UUID[] idArray = chunkIds.toArray( UUID[]::new );
                sel.setArray( 1, conn.createArrayOf( "uuid", idArray ) );
                try( ResultSet rs = sel.executeQuery() ) {
                    final List< UUID > batchIds = new ArrayList<>( batchSize );
                    final List< String > batchTexts = new ArrayList<>( batchSize );
                    final java.util.Map< String, EmbeddingTextBuilder.PageContext > ctxMemo =
                        new java.util.HashMap<>();
                    while( rs.next() ) {
                        batchIds.add( rs.getObject( 1, UUID.class ) );
                        final EmbeddingTextBuilder.PageContext ctx =
                            ctxMemo.computeIfAbsent( rs.getString( 4 ), contextResolver );
                        batchTexts.add( EmbeddingTextBuilder.forDocument(
                            ctx, readHeadingPath( rs, 3 ), rs.getString( 2 ) ) );
                        if ( batchIds.size() >= batchSize ) {
                            upserted += flushBatch( ins, modelCode, batchIds, batchTexts );
                            batchIds.clear();
                            batchTexts.clear();
                        }
                    }
                    if ( !batchIds.isEmpty() ) {
                        upserted += flushBatch( ins, modelCode, batchIds, batchTexts );
                    }
                }
                conn.commit();
            } catch( final SQLException e ) {
                conn.rollback();
                LOG.warn( "indexChunks rolled back (model={}, ids={}): {}",
                    modelCode, chunkIds.size(), e.getMessage(), e );
                throw new RuntimeException( "indexChunks failed for " + modelCode, e );
            }
        } catch( final SQLException e ) {
            LOG.warn( "indexChunks connection failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "indexChunks failed for " + modelCode, e );
        }
        return upserted;
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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( DELETE_BY_MODEL_SQL ) ) {
            ps.setString( 1, modelCode );
            final int removed = ps.executeUpdate();
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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, modelCode );
            try( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) {
                    return new Status( modelCode, 0, 0, null );
                }
                final int rows = rs.getInt( 1 );
                final int dim = rs.getInt( 2 );
                final Timestamp ts = rs.getTimestamp( 3 );
                return new Status( modelCode, dim, rows, ts == null ? null : ts.toInstant() );
            }
        } catch( final SQLException e ) {
            LOG.warn( "embedding status query failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "status failed for " + modelCode, e );
        }
    }

    // ---- internals ----

    private int flushBatch( final PreparedStatement ins, final String modelCode,
                            final List< UUID > ids, final List< String > texts ) throws SQLException {
        final List< float[] > vectors = embedBatchSkippingPoisoned( ids, texts );
        int staged = 0;
        for( int i = 0; i < ids.size(); i++ ) {
            final float[] v = vectors.get( i );
            if ( v == null ) continue;       // poisoned — upstream already logged the cause
            ins.setObject( 1, ids.get( i ) );
            ins.setString( 2, modelCode );
            ins.setInt( 3, v.length );
            ins.setBytes( 4, encodeVector( v ) );
            ins.setString( 5, PgVectorChunkVectorIndex.formatVector( v ) );
            ins.addBatch();
            staged++;
        }
        if ( staged > 0 ) ins.executeBatch();
        return staged;
    }

    /**
     * Embeds a batch, falling back to per-item calls when the batch-level
     * call fails. Items that still fail per-item are returned as {@code null}
     * so the caller can skip them without aborting the surrounding batch.
     */
    private List< float[] > embedBatchSkippingPoisoned( final List< UUID > ids,
                                                        final List< String > texts ) {
        try {
            return new ArrayList<>( client.embed( texts, EmbeddingKind.DOCUMENT ) );
        } catch( final RuntimeException batchFailed ) {
            LOG.warn( "Embedding batch of {} failed, retrying per-item: {}",
                texts.size(), batchFailed.getMessage() );
        }
        final List< float[] > out = new ArrayList<>( texts.size() );
        int poisoned = 0;
        for( int i = 0; i < texts.size(); i++ ) {
            try {
                out.add( client.embed( List.of( texts.get( i ) ), EmbeddingKind.DOCUMENT ).get( 0 ) );
            } catch( final RuntimeException perItem ) {
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
