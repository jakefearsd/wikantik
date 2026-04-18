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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * In-memory dense-vector index backed by the {@code content_chunk_embeddings}
 * table. Loads every row for a single {@code model_code} on construction (and
 * on {@link #reload()}) into three parallel primitive arrays — chunk ids,
 * owning page names, and float32 vectors — so top-k queries can brute-force
 * dot-product the whole corpus without any per-row boxing.
 *
 * <p>Vectors are L2-normalized at load time. With unit-length stored vectors
 * and a unit-length query, cosine similarity collapses to a plain dot product,
 * eliminating a per-row sqrt from the hot path. Callers that pass a non-unit
 * query still get valid ranking (scale is monotone) but the absolute scores
 * won't be bounded in [-1, 1].</p>
 *
 * <p>Thread-safety: {@link #topKChunks} reads a volatile snapshot of the
 * triple-array state, so a concurrent {@link #reload()} either publishes the
 * new arrays atomically or the caller sees the previous complete generation.
 * No lock is taken on the query path.</p>
 */
public final class InMemoryChunkVectorIndex implements ChunkVectorIndex {

    private static final Logger LOG = LogManager.getLogger( InMemoryChunkVectorIndex.class );

    private static final String LOAD_SQL =
        "SELECT e.chunk_id, c.page_name, e.dim, e.vec "
      + "FROM content_chunk_embeddings e "
      + "JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + "WHERE e.model_code = ?";

    private final DataSource dataSource;
    private final String modelCode;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long lastRefreshMillis;

    public InMemoryChunkVectorIndex( final DataSource dataSource, final String modelCode ) {
        if ( dataSource == null ) {
            throw new IllegalArgumentException( "dataSource must not be null" );
        }
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        this.dataSource = dataSource;
        this.modelCode = modelCode;
        reload();
    }

    /**
     * Rebuild the in-memory snapshot from the current database state. Intended
     * to be called after {@code EmbeddingIndexService} writes a batch — the
     * async reindex listener hooks this to keep the query-path index warm.
     */
    public void reload() {
        final Snapshot loaded = loadFromDatabase();
        this.snapshot = loaded;
        this.lastRefreshMillis = System.currentTimeMillis();
        LOG.info( "ChunkVectorIndex loaded: model={} rows={} dim={}",
            modelCode, loaded.size(), loaded.dim );
    }

    @Override
    public List< ScoredChunk > topKChunks( final float[] queryVec, final int k ) {
        if ( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
        if ( k <= 0 ) throw new IllegalArgumentException( "k must be positive, got " + k );

        final Snapshot s = this.snapshot;
        if ( s.size() == 0 ) return List.of();
        if ( queryVec.length != s.dim ) {
            throw new IllegalStateException( "queryVec length " + queryVec.length
                + " does not match index dimension " + s.dim );
        }

        final int effectiveK = Math.min( k, s.size() );
        // Precompute dot products once so the heap comparator is cheap.
        final double[] scores = new double[ s.size() ];
        for( int i = 0; i < s.size(); i++ ) {
            scores[ i ] = dot( s.vectors[ i ], queryVec );
        }
        // Min-heap over row indices keyed by score: popping the smallest keeps the k largest.
        final PriorityQueue< Integer > idxHeap = new PriorityQueue<>( effectiveK,
            ( a, b ) -> Double.compare( scores[ a ], scores[ b ] ) );
        for( int i = 0; i < s.size(); i++ ) {
            if ( idxHeap.size() < effectiveK ) {
                idxHeap.add( i );
            } else if ( scores[ i ] > scores[ idxHeap.peek() ] ) {
                idxHeap.poll();
                idxHeap.add( i );
            }
        }
        final List< ScoredChunk > out = new ArrayList<>( idxHeap.size() );
        while( !idxHeap.isEmpty() ) {
            final int i = idxHeap.poll();
            out.add( new ScoredChunk( s.chunkIds[ i ], s.pageNames[ i ], scores[ i ] ) );
        }
        Collections.reverse( out );
        return out;
    }

    @Override
    public boolean isReady() {
        return snapshot.size() > 0;
    }

    @Override
    public int dimension() {
        return snapshot.dim;
    }

    /** Count of vectors currently loaded. Intended for metrics/admin. */
    public int size() {
        return snapshot.size();
    }

    /** Wall-clock millis of the most recent successful {@link #reload()}. */
    public long lastRefreshMillis() {
        return lastRefreshMillis;
    }

    // ---- internals ----

    private Snapshot loadFromDatabase() {
        final List< UUID > ids = new ArrayList<>();
        final List< String > pages = new ArrayList<>();
        final List< float[] > vecs = new ArrayList<>();
        int dim = 0;

        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement( LOAD_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try( final ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final UUID id = rs.getObject( 1, UUID.class );
                    final String page = rs.getString( 2 );
                    final int rowDim = rs.getInt( 3 );
                    final byte[] raw = rs.getBytes( 4 );
                    final float[] v = decodeVector( id, raw, rowDim );
                    if ( v == null ) continue;
                    if ( dim == 0 ) dim = rowDim;
                    else if ( rowDim != dim ) {
                        LOG.warn( "ChunkVectorIndex: chunk {} dim={} differs from index dim={}, skipping",
                            id, rowDim, dim );
                        continue;
                    }
                    normalizeInPlace( v );
                    ids.add( id );
                    pages.add( page );
                    vecs.add( v );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "ChunkVectorIndex load failed (model={}): {}", modelCode, e.getMessage(), e );
            throw new RuntimeException( "ChunkVectorIndex load failed for " + modelCode, e );
        }

        final int n = ids.size();
        final UUID[] idArr = ids.toArray( new UUID[ n ] );
        final String[] pageArr = pages.toArray( new String[ n ] );
        final float[][] vecArr = vecs.toArray( new float[ n ][] );
        return new Snapshot( idArr, pageArr, vecArr, dim );
    }

    private static float[] decodeVector( final UUID id, final byte[] raw, final int dim ) {
        if ( raw == null ) {
            LOG.warn( "ChunkVectorIndex: chunk {} has null vec, skipping", id );
            return null;
        }
        if ( raw.length != dim * Float.BYTES ) {
            LOG.warn( "ChunkVectorIndex: chunk {} vec bytes={} expected {} (dim={}), skipping",
                id, raw.length, dim * Float.BYTES, dim );
            throw new IllegalStateException( "Corrupt vector bytes for chunk " + id
                + ": got " + raw.length + " bytes, expected " + ( dim * Float.BYTES ) );
        }
        final float[] out = new float[ dim ];
        final FloatBuffer fb = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer();
        fb.get( out );
        if ( out.length != dim ) {
            throw new IllegalStateException( "Decoded vector length " + out.length
                + " != declared dim " + dim + " for chunk " + id );
        }
        return out;
    }

    private static void normalizeInPlace( final float[] v ) {
        double sumSq = 0.0;
        for( final float f : v ) sumSq += (double) f * (double) f;
        if ( sumSq <= 0.0 ) return;
        final double inv = 1.0 / Math.sqrt( sumSq );
        for( int i = 0; i < v.length; i++ ) v[ i ] = (float) ( v[ i ] * inv );
    }

    private static double dot( final float[] a, final float[] b ) {
        double acc = 0.0;
        for( int i = 0; i < a.length; i++ ) acc += (double) a[ i ] * (double) b[ i ];
        return acc;
    }

    /** Immutable triple-array snapshot published via a single volatile write. */
    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot( new UUID[ 0 ], new String[ 0 ], new float[ 0 ][], 0 );

        final UUID[] chunkIds;
        final String[] pageNames;
        final float[][] vectors;
        final int dim;

        Snapshot( final UUID[] chunkIds, final String[] pageNames,
                  final float[][] vectors, final int dim ) {
            this.chunkIds = chunkIds;
            this.pageNames = pageNames;
            this.vectors = vectors;
            this.dim = dim;
        }

        int size() { return chunkIds.length; }
    }
}
