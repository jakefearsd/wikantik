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

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC-backed tests for {@link InMemoryGraphNeighborIndex}. Uses the shared
 * {@link PostgresTestContainer} so we exercise the real {@code kg_edges}
 * query path and catch encoding / binding regressions.
 */
@Testcontainers( disabledWithoutDocker = true )
class InMemoryGraphNeighborIndexTest {

    private static DataSource dataSource;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void clearGraph() throws Exception {
        try( Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void emptyEdgeTableLoadsReadyFalse() {
        final InMemoryGraphNeighborIndex idx = new InMemoryGraphNeighborIndex( dataSource, 10_000 );
        assertFalse( idx.isReady() );
        assertEquals( 0, idx.nodeCount() );
        assertEquals( Set.of(), idx.neighbors( UUID.randomUUID() ) );
    }

    @Test
    void edgesAreTreatedAsUndirected() {
        final UUID a = insertNode( "A" );
        final UUID b = insertNode( "B" );
        insertEdge( a, b, "links_to" );

        final InMemoryGraphNeighborIndex idx = new InMemoryGraphNeighborIndex( dataSource, 10_000 );
        assertTrue( idx.isReady() );
        assertEquals( Set.of( b ), idx.neighbors( a ) );
        assertEquals( Set.of( a ), idx.neighbors( b ) );
    }

    @Test
    void multiHopAdjacency() {
        final UUID a = insertNode( "A" );
        final UUID b = insertNode( "B" );
        final UUID c = insertNode( "C" );
        final UUID d = insertNode( "D" );
        insertEdge( a, b, "links_to" );
        insertEdge( b, c, "mentions" );
        insertEdge( c, d, "mentions" );

        final InMemoryGraphNeighborIndex idx = new InMemoryGraphNeighborIndex( dataSource, 10_000 );
        assertEquals( Set.of( b ),       idx.neighbors( a ) );
        assertEquals( Set.of( a, c ),    idx.neighbors( b ) );
        assertEquals( Set.of( b, d ),    idx.neighbors( c ) );
        assertEquals( Set.of( c ),       idx.neighbors( d ) );
        assertEquals( 4, idx.nodeCount() );
    }

    @Test
    void reloadPicksUpNewEdges() {
        final UUID a = insertNode( "A" );
        final UUID b = insertNode( "B" );
        final InMemoryGraphNeighborIndex idx = new InMemoryGraphNeighborIndex( dataSource, 10_000 );
        assertFalse( idx.isReady() );

        insertEdge( a, b, "links_to" );
        idx.reload();
        assertTrue( idx.isReady() );
        assertEquals( Set.of( b ), idx.neighbors( a ) );
    }

    @Test
    void edgeCapDisablesIndex() {
        final UUID a = insertNode( "A" );
        final UUID b = insertNode( "B" );
        final UUID c = insertNode( "C" );
        insertEdge( a, b, "links_to" );
        insertEdge( b, c, "links_to" );

        // 2 edges, cap at 1 — the index must refuse to load.
        final InMemoryGraphNeighborIndex idx = new InMemoryGraphNeighborIndex( dataSource, 1 );
        assertFalse( idx.isReady() );
        assertEquals( 0, idx.nodeCount() );
        assertEquals( Set.of(), idx.neighbors( a ) );
    }

    @Test
    void nullNodeYieldsEmptySet() {
        final UUID a = insertNode( "A" );
        final UUID b = insertNode( "B" );
        insertEdge( a, b, "links_to" );
        final InMemoryGraphNeighborIndex idx = new InMemoryGraphNeighborIndex( dataSource, 10_000 );
        assertEquals( Set.of(), idx.neighbors( null ) );
    }

    // ---- fixture helpers ----

    private UUID insertNode( final String name ) {
        final UUID id = UUID.randomUUID();
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_nodes (id, name, node_type, provenance) VALUES (?, ?, 'entity', 'human-authored')" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, name );
            ps.executeUpdate();
        } catch( final Exception e ) {
            throw new RuntimeException( "insertNode failed for " + name, e );
        }
        return id;
    }

    private void insertEdge( final UUID src, final UUID tgt, final String rel ) {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_edges (source_id, target_id, relationship_type, provenance) "
               + "VALUES (?, ?, ?, 'human-authored')" ) ) {
            ps.setObject( 1, src );
            ps.setObject( 2, tgt );
            ps.setString( 3, rel );
            ps.executeUpdate();
        } catch( final Exception e ) {
            throw new RuntimeException( "insertEdge failed", e );
        }
    }
}
