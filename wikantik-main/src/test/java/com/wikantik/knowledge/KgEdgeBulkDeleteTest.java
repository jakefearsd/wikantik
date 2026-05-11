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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KgEdgeBulkDeleteTest {

    private static DataSource dataSource;
    private KgEdgeRepository edges;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        edges = new KgEdgeRepository( dataSource );
        nodes = new KgNodeRepository( dataSource );
    }

    @Test
    void bulkDeleteByFilterRemovesMatchingEdgesOnly() {
        final UUID a = nodes.upsertNode( "BulkA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "BulkB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID c = nodes.upsertNode( "BulkC", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( a, c, "related", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( b, c, "depends_on", Provenance.HUMAN_CURATED, Map.of() );

        final int deleted = edges.bulkDeleteByFilter( "related", null );

        assertEquals( 2, deleted );
        assertEquals( 1L, edges.countEdgesWithFilter( null, null ) );
        assertEquals( 1L, edges.countEdgesWithFilter( "depends_on", null ) );
    }

    @Test
    void deleteEdgeAndRecordRejectionInsertsRejectionRow() throws Exception {
        final UUID a = nodes.upsertNode( "RejA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "RejB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        final UUID edgeId = queryEdgeId( a, b, "related" );

        edges.deleteEdgeAndRecordRejection( edgeId, "carol", "bad inference" );

        // Edge gone
        assertEquals( 0L, edges.countEdgesWithFilter( "related", null ) );
        // Rejection inserted with correct fields
        try ( Connection conn = dataSource.getConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(
                  "SELECT proposed_source, proposed_target, proposed_relationship, rejected_by, reason "
                + "FROM kg_rejections WHERE proposed_relationship = 'related'" ) ) {
            assertTrue( rs.next() );
            assertEquals( "RejA", rs.getString( 1 ) );
            assertEquals( "RejB", rs.getString( 2 ) );
            assertEquals( "related", rs.getString( 3 ) );
            assertEquals( "carol", rs.getString( 4 ) );
            assertEquals( "bad inference", rs.getString( 5 ) );
        }
    }

    @Test
    void deleteEdgeAndRecordRejectionIsIdempotentOnReinsert() throws Exception {
        final UUID a = nodes.upsertNode( "IdemA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "IdemB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        final UUID edgeId = queryEdgeId( a, b, "related" );
        edges.deleteEdgeAndRecordRejection( edgeId, "carol", "first" );

        // Recreate the edge — admin can override rejection — and reject again
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        final UUID edgeId2 = queryEdgeId( a, b, "related" );
        assertDoesNotThrow( () -> edges.deleteEdgeAndRecordRejection( edgeId2, "dave", "second" ) );

        // Still exactly one rejection row for this triple
        try ( Connection conn = dataSource.getConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(
                  "SELECT COUNT(*) FROM kg_rejections WHERE proposed_relationship = 'related'" ) ) {
            assertTrue( rs.next() );
            assertEquals( 1L, rs.getLong( 1 ) );
        }
    }

    private UUID queryEdgeId( final UUID source, final UUID target, final String rel ) throws Exception {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement(
                  "SELECT id FROM kg_edges WHERE source_id = ? AND target_id = ? AND relationship_type = ?" ) ) {
            ps.setObject( 1, source );
            ps.setObject( 2, target );
            ps.setString( 3, rel );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next() );
                return rs.getObject( 1, UUID.class );
            }
        }
    }
}
