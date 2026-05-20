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

    /**
     * Dimension of the {@code content_chunk_embeddings.embedding} column
     * (pgvector type {@code vector(1024)}). Referenced by {@link #dimension()},
     * the query-vector length check in {@link #topKChunks} (Task 3), and the
     * dual-write UPSERT in {@code EmbeddingIndexService} (Task 7). One constant
     * so a future model-dimension swap touches one line.
     */
    static final int EMBEDDING_DIM = 1024;

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
        return EMBEDDING_DIM;
    }

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
