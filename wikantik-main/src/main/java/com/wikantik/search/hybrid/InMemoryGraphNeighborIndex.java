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

import com.wikantik.kgpolicy.KgInclusionFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory adjacency index over {@code kg_edges}. Loads every edge once at
 * construction (and on {@link #reload()}) and exposes an undirected neighbor
 * lookup for Phase 3 graph-aware rerank. Fits the same "load once, keep warm,
 * snapshot reload" shape as {@link InMemoryChunkVectorIndex} — a single
 * volatile snapshot makes reads lock-free while a reload is in progress.
 *
 * <p>When the edge count exceeds {@code maxEdges}, the index reports
 * {@link #isReady()} = {@code false} and returns an empty neighbor set for
 * every node, effectively disabling the graph rerank step. This is
 * deliberate: for multi-million-edge graphs the rerank cost becomes
 * non-trivial and the feature should be rolled back (or the cap raised)
 * explicitly, not allowed to silently blow through the latency budget.</p>
 */
public final class InMemoryGraphNeighborIndex implements GraphNeighborIndex {

    private static final Logger LOG = LogManager.getLogger( InMemoryGraphNeighborIndex.class );

    private static final String LOAD_SQL =
            "SELECT e.source_id, e.target_id, e.tier FROM kg_edges e"
            + KgInclusionFilter.EDGE_FILTER_JOIN
            + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE;
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM kg_edges";

    private final DataSource dataSource;
    private final int maxEdges;
    private final Map< String, Double > tierWeights;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long lastRefreshMillis;

    /**
     * Construct an unweighted index — every edge gets weight {@code 1.0}, so
     * the weighted scorer collapses to the unweighted BFS variant. The
     * tier column is still loaded so callers can flip on weighting without a
     * reload by switching to {@link #InMemoryGraphNeighborIndex(DataSource, int, Map)}.
     */
    public InMemoryGraphNeighborIndex( final DataSource dataSource, final int maxEdges ) {
        this( dataSource, maxEdges, Map.of() );
    }

    /**
     * Construct a weighted index. {@code tierWeights} maps {@code kg_edges.tier}
     * values (typically {@code "human"} and {@code "machine"}) to a multiplier
     * in {@code (0, 1]}. Edges whose tier is not in the map fall back to
     * {@code 1.0}. The map is consulted once per edge at load time and the
     * resulting per-edge weight is baked into the snapshot, so subsequent
     * reads are O(1).
     */
    public InMemoryGraphNeighborIndex( final DataSource dataSource,
                                       final int maxEdges,
                                       final Map< String, Double > tierWeights ) {
        if( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if( maxEdges < 1 ) throw new IllegalArgumentException( "maxEdges must be >= 1, got: " + maxEdges );
        if( tierWeights == null ) throw new IllegalArgumentException( "tierWeights must not be null (use Map.of() for unweighted)" );
        this.dataSource = dataSource;
        this.maxEdges = maxEdges;
        this.tierWeights = Map.copyOf( tierWeights );
        reload();
    }

    /**
     * Rebuild the in-memory adjacency snapshot from {@code kg_edges}. Called
     * at construction and on demand when the caller knows edges have changed
     * (e.g. from a KG edge-mutation event listener).
     */
    public void reload() {
        final int edgeCount;
        try {
            edgeCount = countEdges();
        } catch( final SQLException e ) {
            LOG.warn( "InMemoryGraphNeighborIndex.reload: count query failed, leaving snapshot unchanged: {}",
                e.getMessage(), e );
            return;
        }
        if( edgeCount > maxEdges ) {
            LOG.warn( "InMemoryGraphNeighborIndex: edge count {} exceeds cap {}; graph rerank will be disabled",
                edgeCount, maxEdges );
            this.snapshot = Snapshot.EMPTY;
            this.lastRefreshMillis = System.currentTimeMillis();
            return;
        }
        try {
            this.snapshot = loadSnapshot();
            this.lastRefreshMillis = System.currentTimeMillis();
            LOG.info( "InMemoryGraphNeighborIndex loaded: nodes={} edges={}",
                snapshot.nodeCount(), edgeCount );
        } catch( final SQLException e ) {
            LOG.warn( "InMemoryGraphNeighborIndex load failed; snapshot unchanged: {}", e.getMessage(), e );
        }
    }

    @Override
    public Set< UUID > neighbors( final UUID nodeId ) {
        if( nodeId == null ) return Set.of();
        final Set< UUID > s = snapshot.adjacency.get( nodeId );
        return s != null ? s : Set.of();
    }

    @Override
    public boolean isReady() {
        return snapshot.nodeCount() > 0;
    }

    @Override
    public int nodeCount() {
        return snapshot.nodeCount();
    }

    @Override
    public double edgeWeight( final UUID src, final UUID tgt ) {
        if( src == null || tgt == null ) return 1.0;
        final Map< UUID, Double > row = snapshot.weights.get( src );
        if( row == null ) return 1.0;
        final Double w = row.get( tgt );
        return w == null ? 1.0 : w;
    }

    /** Wall-clock millis of the most recent {@link #reload()}. */
    public long lastRefreshMillis() {
        return lastRefreshMillis;
    }

    // ---- internals ----

    private int countEdges() throws SQLException {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( COUNT_SQL );
             ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getInt( 1 ) : 0;
        }
    }

    private Snapshot loadSnapshot() throws SQLException {
        final Map< UUID, Set< UUID > > adj = new HashMap<>();
        final Map< UUID, Map< UUID, Double > > weights = new HashMap<>();
        final boolean weighted = !tierWeights.isEmpty();
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( LOAD_SQL ) ) {
            ps.setFetchSize( 1_000 );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final UUID src = rs.getObject( 1, UUID.class );
                    final UUID tgt = rs.getObject( 2, UUID.class );
                    final String tier = rs.getString( 3 );
                    if( src == null || tgt == null || src.equals( tgt ) ) continue;
                    adj.computeIfAbsent( src, k -> new HashSet<>() ).add( tgt );
                    adj.computeIfAbsent( tgt, k -> new HashSet<>() ).add( src );
                    if( weighted ) {
                        final double w = tierWeights.getOrDefault( tier, 1.0 );
                        weights.computeIfAbsent( src, k -> new HashMap<>() ).put( tgt, w );
                        weights.computeIfAbsent( tgt, k -> new HashMap<>() ).put( src, w );
                    }
                }
            }
        }
        // Freeze each neighbor set so callers cannot mutate shared state.
        final Map< UUID, Set< UUID > > frozenAdj = new HashMap<>( adj.size() * 2 );
        for( final Map.Entry< UUID, Set< UUID > > e : adj.entrySet() ) {
            frozenAdj.put( e.getKey(), Set.copyOf( e.getValue() ) );
        }
        final Map< UUID, Map< UUID, Double > > frozenWeights;
        if( weighted ) {
            frozenWeights = new HashMap<>( weights.size() * 2 );
            for( final Map.Entry< UUID, Map< UUID, Double > > e : weights.entrySet() ) {
                frozenWeights.put( e.getKey(), Map.copyOf( e.getValue() ) );
            }
        } else {
            frozenWeights = Map.of();
        }
        return new Snapshot( frozenAdj, frozenWeights );
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot( Map.of(), Map.of() );

        final Map< UUID, Set< UUID > > adjacency;
        final Map< UUID, Map< UUID, Double > > weights;

        Snapshot( final Map< UUID, Set< UUID > > adjacency,
                  final Map< UUID, Map< UUID, Double > > weights ) {
            this.adjacency = adjacency;
            this.weights = weights;
        }

        int nodeCount() { return adjacency.size(); }
    }
}
