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
import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class DefaultKnowledgeGraphServiceTest {

    private static DataSource dataSource;
    private DefaultKnowledgeGraphService service;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        service = new DefaultKnowledgeGraphService( new JdbcKnowledgeRepository( dataSource ) );
    }

    @Test
    void discoverSchema_returnsCorrectCounts() {
        service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        service.upsertNode( "Customer", "domain-model", "Customer.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final SchemaDescription schema = service.discoverSchema();
        assertTrue( schema.nodeTypes().contains( "domain-model" ) );
        assertEquals( 2, schema.stats().nodes() );
    }

    @Test
    void traverse_findsConnectedNodes() {
        final KgNode order = service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode address = service.upsertNode( "Address", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( customer.id(), address.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraversalResult result = service.traverse( "Order", "outbound",
                Set.of(), 3, Set.of( Provenance.HUMAN_AUTHORED ) );
        assertEquals( 3, result.nodes().size() );
        assertEquals( 2, result.edges().size() );
    }

    @Test
    void traverse_respectsMaxDepth() {
        final KgNode a = service.upsertNode( "A", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = service.upsertNode( "B", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = service.upsertNode( "C", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "r", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( b.id(), c.id(), "r", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraversalResult result = service.traverse( "A", "outbound", Set.of(), 1, null );
        assertEquals( 2, result.nodes().size() ); // A and B only
        assertEquals( 1, result.edges().size() );
    }

    @Test
    void traverse_filtersRelationshipTypes() {
        final KgNode a = service.upsertNode( "A", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = service.upsertNode( "B", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = service.upsertNode( "C", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraversalResult result = service.traverse( "A", "outbound",
                Set.of( "depends-on" ), 5, null );
        assertEquals( 2, result.nodes().size() ); // A and B only
    }

    @Test
    void submitProposal_rejectedIfPreviouslyRejected() {
        service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "Inventory", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        // First proposal, then reject it
        final KgProposal first = service.submitProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
            0.7, "test" );
        service.rejectProposal( first.id(), "admin", "Not real" );
        // Second proposal for same relationship should return null
        assertNull( service.submitProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
            0.8, "test again" ) );
    }
}
