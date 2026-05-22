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
package com.wikantik.search.hybrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /**
     * Dimension of the {@code content_chunk_embeddings.embedding} column
     * (pgvector type {@code vector(1024)}). Referenced by {@link #dimension()},
     * the query-vector length check in {@link #topKChunks} (Task 3), and the
     * dual-write UPSERT in {@code EmbeddingIndexService} (Task 7). One constant
     * so a future model-dimension swap touches one line. Public so sibling
     * backends (e.g. the Lucene HNSW index) and the subsystem wiring in another
     * package can share this single source of truth rather than re-hardcoding 1024.
     */
    public static final int EMBEDDING_DIM = 1024;

    private static final long SIZE_CACHE_MILLIS = 5L * 60L * 1000L;

    private final DataSource dataSource;
    private final String modelCode;
    private final int efSearch;

    private volatile long sizeCachedAt;
    private volatile int sizeCachedValue;

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
        if ( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
        if ( k <= 0 ) throw new IllegalArgumentException( "k must be positive, got " + k );
        if ( queryVec.length != EMBEDDING_DIM ) {
            throw new IllegalStateException( "queryVec length " + queryVec.length
                + " does not match index dimension " + EMBEDDING_DIM );
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
            try {
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
                        return out;
                    }
                }
            } catch ( final SQLException inner ) {
                try {
                    conn.rollback();
                } catch ( final SQLException rb ) {
                    LOG.warn( "PgVectorChunkVectorIndex.topKChunks rollback failed (model={}, k={}): {}",
                        modelCode, k, rb.getMessage(), rb );
                }
                throw inner;
            } finally {
                try {
                    conn.setAutoCommit( prevAutoCommit );
                } catch ( final SQLException acRestore ) {
                    LOG.warn( "PgVectorChunkVectorIndex.topKChunks autoCommit restore failed (model={}): {}",
                        modelCode, acRestore.getMessage(), acRestore );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "PgVectorChunkVectorIndex.topKChunks failed (model={}, k={}): {}",
                modelCode, k, e.getMessage(), e );
            throw new RuntimeException( "PgVector dense retrieval failed", e );
        }
    }

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
     * Row count of non-NULL embeddings for {@link #modelCode}. Cached for
     * {@value #SIZE_CACHE_MILLIS} ms (5 minutes) so Prometheus metric scrapes
     * don't fan out a COUNT query every scrape interval. On SQL failure, returns
     * the last-known cached value (stale but best-effort) rather than throwing —
     * this is a metric path, not a correctness path.
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

    @Override
    public int dimension() {
        return EMBEDDING_DIM;
    }

    /**
     * The embedding model code this index filters on. Exposed for tests and
     * introspection — production code should not gate behaviour on the model
     * code directly.
     */
    public String modelCode() { return modelCode; }

    /**
     * Format {@code v} as a pgvector literal: {@code "[v1,v2,...]"}. Matches the
     * codec used by {@link com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository}.
     *
     * <p>Visible as {@code public static} so the backfill CLI in
     * {@code com.wikantik.search.embedding} (a sibling package) can reuse it
     * without duplicating the encoding logic.</p>
     */
    public static String formatVector( final float[] v ) {
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
