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

    private static final String LOAD_SQL = "SELECT source_id, target_id FROM kg_edges";
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM kg_edges";

    private final DataSource dataSource;
    private final int maxEdges;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long lastRefreshMillis;

    public InMemoryGraphNeighborIndex( final DataSource dataSource, final int maxEdges ) {
        if( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if( maxEdges < 1 ) throw new IllegalArgumentException( "maxEdges must be >= 1, got: " + maxEdges );
        this.dataSource = dataSource;
        this.maxEdges = maxEdges;
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
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( LOAD_SQL ) ) {
            ps.setFetchSize( 1_000 );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final UUID src = rs.getObject( 1, UUID.class );
                    final UUID tgt = rs.getObject( 2, UUID.class );
                    if( src == null || tgt == null || src.equals( tgt ) ) continue;
                    adj.computeIfAbsent( src, k -> new HashSet<>() ).add( tgt );
                    adj.computeIfAbsent( tgt, k -> new HashSet<>() ).add( src );
                }
            }
        }
        // Freeze each neighbor set so callers cannot mutate shared state.
        final Map< UUID, Set< UUID > > frozen = new HashMap<>( adj.size() * 2 );
        for( final Map.Entry< UUID, Set< UUID > > e : adj.entrySet() ) {
            frozen.put( e.getKey(), Set.copyOf( e.getValue() ) );
        }
        return new Snapshot( frozen );
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot( Map.of() );

        final Map< UUID, Set< UUID > > adjacency;

        Snapshot( final Map< UUID, Set< UUID > > adjacency ) {
            this.adjacency = adjacency;
        }

        int nodeCount() { return adjacency.size(); }
    }
}
