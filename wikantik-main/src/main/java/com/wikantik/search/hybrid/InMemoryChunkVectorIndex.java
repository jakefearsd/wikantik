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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory dense-vector index backed by the {@code content_chunk_embeddings}
 * table. Loads every row for a single {@code model_code} on construction (and
 * on {@link #reload()}) into three parallel primitive arrays — chunk ids,
 * owning page names, and a single flat float32 buffer of length {@code N*dim} —
 * so top-k queries can brute-force dot-product the whole corpus without any
 * per-row boxing or per-row pointer chasing. Storing every vector contiguously
 * gives the JIT a tight loop and keeps the L1/L2 prefetcher happy as we sweep
 * row by row.
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

    private static final String LOAD_BY_IDS_SQL =
        "SELECT e.chunk_id, c.page_name, e.dim, e.vec "
      + "FROM content_chunk_embeddings e "
      + "JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + "WHERE e.model_code = ? AND e.chunk_id = ANY (?)";

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

    /**
     * Apply an incremental change to the snapshot covering the given chunk ids.
     * Loads only those chunks' rows from the database and merges into the existing
     * snapshot:
     *
     * <ul>
     *   <li>chunk id present in DB and present in snapshot → vector replaced</li>
     *   <li>chunk id present in DB but missing from snapshot → row appended</li>
     *   <li>chunk id missing from DB but present in snapshot → row removed</li>
     * </ul>
     *
     * <p>Cheaper than {@link #reload()} when only a handful of chunks changed —
     * a page edit typically touches a few dozen chunks rather than the full
     * corpus. Falls back to {@link #reload()} on any SQL failure so the index
     * stays internally consistent.</p>
     */
    public void upsertChunks( final Collection< UUID > chunkIds ) {
        if ( chunkIds == null || chunkIds.isEmpty() ) {
            return;
        }
        final Set< UUID > targetIds = new HashSet<>( chunkIds );
        final Map< UUID, float[] > loadedVecs;
        final Map< UUID, String > loadedPages;
        final int loadedDim;
        try {
            final LoadedRows rows = loadRowsByIds( targetIds );
            loadedVecs = rows.vecs;
            loadedPages = rows.pages;
            loadedDim = rows.dim;
        } catch( final SQLException e ) {
            LOG.warn( "ChunkVectorIndex incremental upsert failed (model={}, ids={}); "
                + "falling back to full reload: {}", modelCode, targetIds.size(), e.getMessage(), e );
            reload();
            return;
        }

        final Snapshot prev = this.snapshot;
        final int prevDim = prev.dim;
        // Only enforce dim consistency when both sides have rows; an empty snapshot is fine.
        if ( prevDim != 0 && loadedDim != 0 && prevDim != loadedDim ) {
            LOG.warn( "ChunkVectorIndex incremental upsert dim mismatch (prev={}, loaded={}); "
                + "falling back to full reload", prevDim, loadedDim );
            reload();
            return;
        }
        final int dim = loadedDim != 0 ? loadedDim : prevDim;
        if ( dim == 0 ) {
            // both empty — nothing to publish
            return;
        }

        // Build the new snapshot with exactly one corpus-sized allocation: bulk-copy
        // retained rows straight from the previous flat buffer, then append the
        // freshly loaded rows. (The old path boxed every retained row into a
        // List<float[]>, re-flattened, and then cloned all three arrays again in
        // the Snapshot constructor — ~3 full corpus copies per page save.)
        final int prevSize = prev.size();
        final boolean[] keep = new boolean[ prevSize ];
        int retained = 0;
        for( int i = 0; i < prevSize; i++ ) {
            keep[ i ] = !targetIds.contains( prev.chunkIds[ i ] );
            if ( keep[ i ] ) retained++;
        }
        final int n = retained + loadedVecs.size();
        final UUID[] idArr = new UUID[ n ];
        final String[] pageArr = new String[ n ];
        final float[] flat = new float[ n * dim ];
        int w = 0;
        for( int i = 0; i < prevSize; i++ ) {
            if ( !keep[ i ] ) continue;
            idArr[ w ] = prev.chunkIds[ i ];
            pageArr[ w ] = prev.pageNames[ i ];
            System.arraycopy( prev.flatVectors, i * prevDim, flat, w * dim, dim );
            w++;
        }
        for( final Map.Entry< UUID, float[] > e : loadedVecs.entrySet() ) {
            idArr[ w ] = e.getKey();
            pageArr[ w ] = loadedPages.get( e.getKey() );
            System.arraycopy( e.getValue(), 0, flat, w * dim, dim );
            w++;
        }
        this.snapshot = new Snapshot( idArr, pageArr, flat, dim );
        this.lastRefreshMillis = System.currentTimeMillis();
        LOG.debug( "ChunkVectorIndex upserted: model={} touched={} rows={} dim={}",
            modelCode, targetIds.size(), n, dim );
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

        final int n = s.size();
        final int dim = s.dim;
        final float[] flat = s.flatVectors;
        final int effectiveK = Math.min( k, n );

        // Manual k-sized binary min-heap with parallel score/idx arrays. We compute
        // the dot product once per row inline and only retain the top-k entries —
        // no per-query double[N] allocation, no boxing.
        final double[] heapScore = new double[ effectiveK ];
        final int[] heapIdx = new int[ effectiveK ];
        int heapSize = 0;

        for( int i = 0; i < n; i++ ) {
            final double score = dotAt( flat, i * dim, queryVec, dim );
            if ( heapSize < effectiveK ) {
                heapScore[ heapSize ] = score;
                heapIdx[ heapSize ] = i;
                heapSize++;
                siftUp( heapScore, heapIdx, heapSize - 1 );
            } else if ( score > heapScore[ 0 ] ) {
                heapScore[ 0 ] = score;
                heapIdx[ 0 ] = i;
                siftDown( heapScore, heapIdx, heapSize );
            }
        }

        // Pop smallest-first into a buffer, then reverse so callers see best-first.
        final ScoredChunk[] buf = new ScoredChunk[ heapSize ];
        for( int popped = 0; heapSize > 0; popped++ ) {
            final int rowIdx = heapIdx[ 0 ];
            buf[ popped ] = new ScoredChunk( s.chunkIds[ rowIdx ], s.pageNames[ rowIdx ], heapScore[ 0 ] );
            heapSize--;
            if ( heapSize > 0 ) {
                heapScore[ 0 ] = heapScore[ heapSize ];
                heapIdx[ 0 ] = heapIdx[ heapSize ];
                siftDown( heapScore, heapIdx, heapSize );
            }
        }
        final List< ScoredChunk > out = new ArrayList<>( buf.length );
        for( int j = buf.length - 1; j >= 0; j-- ) {
            out.add( buf[ j ] );
        }
        return out;
    }

    private static void siftUp( final double[] score, final int[] idx, final int from ) {
        int i = from;
        while( i > 0 ) {
            final int parent = ( i - 1 ) >>> 1;
            if ( score[ i ] < score[ parent ] ) {
                swap( score, idx, i, parent );
                i = parent;
            } else {
                break;
            }
        }
    }

    private static void siftDown( final double[] score, final int[] idx, final int size ) {
        int i = 0;
        while( true ) {
            final int left = ( i << 1 ) + 1;
            final int right = left + 1;
            int smallest = i;
            if ( left < size && score[ left ] < score[ smallest ] ) smallest = left;
            if ( right < size && score[ right ] < score[ smallest ] ) smallest = right;
            if ( smallest == i ) break;
            swap( score, idx, i, smallest );
            i = smallest;
        }
    }

    private static void swap( final double[] score, final int[] idx, final int a, final int b ) {
        final double sTmp = score[ a ]; score[ a ] = score[ b ]; score[ b ] = sTmp;
        final int iTmp = idx[ a ]; idx[ a ] = idx[ b ]; idx[ b ] = iTmp;
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

        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( LOAD_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try( ResultSet rs = ps.executeQuery() ) {
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
        final UUID[] idArr = ids.toArray( new UUID[ 0 ] );
        final String[] pageArr = pages.toArray( new String[ 0 ] );
        // Flatten N row vectors into one contiguous float[] of length N*dim. The
        // hot path then walks consecutive memory and the JIT can keep the dot
        // product entirely in registers without per-row pointer-chase overhead.
        final float[] flat = new float[ n * dim ];
        for( int i = 0; i < n; i++ ) {
            System.arraycopy( vecs.get( i ), 0, flat, i * dim, dim );
        }
        return new Snapshot( idArr, pageArr, flat, dim );
    }

    private LoadedRows loadRowsByIds( final Set< UUID > ids ) throws SQLException {
        final Map< UUID, float[] > vecs = new HashMap<>( ids.size() * 2 );
        final Map< UUID, String > pages = new HashMap<>( ids.size() * 2 );
        int dim = 0;
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( LOAD_BY_IDS_SQL ) ) {
            ps.setString( 1, modelCode );
            // PostgreSQL ANY(?) array binding — UUID maps to the postgres uuid[] type.
            final UUID[] arr = ids.toArray( new UUID[ 0 ] );
            ps.setArray( 2, c.createArrayOf( "uuid", arr ) );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final UUID id = rs.getObject( 1, UUID.class );
                    final String page = rs.getString( 2 );
                    final int rowDim = rs.getInt( 3 );
                    final byte[] raw = rs.getBytes( 4 );
                    final float[] v = decodeVector( id, raw, rowDim );
                    if ( v == null ) continue;
                    if ( dim == 0 ) dim = rowDim;
                    else if ( rowDim != dim ) {
                        LOG.warn( "ChunkVectorIndex upsert: chunk {} dim={} differs from batch dim={}, skipping",
                            id, rowDim, dim );
                        continue;
                    }
                    normalizeInPlace( v );
                    vecs.put( id, v );
                    pages.put( id, page );
                }
            }
        }
        return new LoadedRows( vecs, pages, dim );
    }

    private static final class LoadedRows {
        final Map< UUID, float[] > vecs;
        final Map< UUID, String > pages;
        final int dim;
        LoadedRows( final Map< UUID, float[] > vecs, final Map< UUID, String > pages, final int dim ) {
            this.vecs = vecs;
            this.pages = pages;
            this.dim = dim;
        }
    }

    private static float[] decodeVector( final UUID id, final byte[] raw, final int dim ) {
        return ChunkVectorBytes.decode( id, raw, dim );
    }

    private static void normalizeInPlace( final float[] v ) {
        double sumSq = 0.0;
        for( final float f : v ) sumSq += (double) f * (double) f;
        if ( sumSq <= 0.0 ) return;
        final double inv = 1.0 / Math.sqrt( sumSq );
        for( int i = 0; i < v.length; i++ ) v[ i ] = (float) ( v[ i ] * inv );
    }

    /**
     * Vector species used for the dot product. {@code SPECIES_PREFERRED} picks the
     * widest float vector the JVM can map to native SIMD on this host (AVX-512 → 16,
     * AVX2 → 8, NEON → 4). Captured once as a static so the JIT can constant-fold it.
     */
    private static final jdk.incubator.vector.VectorSpecies< Float > SPECIES =
        jdk.incubator.vector.FloatVector.SPECIES_PREFERRED;

    /**
     * Dot product of {@code dim} consecutive floats in the flat storage starting at
     * {@code offset}, against {@code queryVec}. Uses {@link jdk.incubator.vector.FloatVector}
     * fused multiply-add (FMA) lanes so the inner loop maps to native SIMD; the JIT
     * collapses each iteration to a single FMA instruction on modern x86 / NEON. The
     * tail loop handles any leftover when {@code dim} is not a multiple of the
     * species length — for the 1024-dim embeddings we ship, the tail is zero
     * iterations on every common architecture.
     *
     * <p>The accumulator is a {@code FloatVector} rather than a {@code double},
     * which is the only behavioural difference from the previous scalar path:
     * precision drops from double to float. Cosine-similarity ranking against
     * other floats (and pgvector itself, which is float-native) does not care
     * about the lost sub-{@code 1e-7} bits; ordering is stable.</p>
     */
    // package-private for direct unit-test parity coverage; no other call site.
    static double dotAt( final float[] flat, final int offset, final float[] queryVec, final int dim ) {
        final int upperBound = SPECIES.loopBound( dim );
        jdk.incubator.vector.FloatVector accum = jdk.incubator.vector.FloatVector.zero( SPECIES );
        int j = 0;
        for( ; j < upperBound; j += SPECIES.length() ) {
            final jdk.incubator.vector.FloatVector a =
                jdk.incubator.vector.FloatVector.fromArray( SPECIES, flat, offset + j );
            final jdk.incubator.vector.FloatVector b =
                jdk.incubator.vector.FloatVector.fromArray( SPECIES, queryVec, j );
            accum = a.fma( b, accum );
        }
        float sum = accum.reduceLanes( jdk.incubator.vector.VectorOperators.ADD );
        // Tail (almost never runs for the 1024-dim embeddings we ship — kept so
        // dim values that aren't a multiple of SPECIES.length() are still correct).
        for( ; j < dim; j++ ) {
            sum += flat[ offset + j ] * queryVec[ j ];
        }
        return sum;
    }

    /**
     * Immutable snapshot published via a single volatile write. Vector storage is
     * a single flat {@code float[]} of length {@code N*dim}; row {@code i} starts
     * at {@code i*dim}. Replacing the previous {@code float[][]} with a flat buffer
     * removes one level of indirection per row in the hot path.
     */
    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot( new UUID[ 0 ], new String[ 0 ], new float[ 0 ], 0 );

        final UUID[] chunkIds;
        final String[] pageNames;
        final float[] flatVectors;
        final int dim;

        /** Takes ownership of the arrays — callers must pass freshly-built, never-shared arrays. */
        Snapshot( final UUID[] chunkIds, final String[] pageNames,
                  final float[] flatVectors, final int dim ) {
            this.chunkIds = chunkIds;
            this.pageNames = pageNames;
            this.flatVectors = flatVectors;
            this.dim = dim;
        }

        int size() { return chunkIds.length; }
    }
}
