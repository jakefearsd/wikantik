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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class GraphProjectorTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository repo;
    private DefaultKnowledgeGraphService service;
    private GraphProjector projector;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        repo = new JdbcKnowledgeRepository( dataSource );
        service = new DefaultKnowledgeGraphService( repo );
        projector = new GraphProjector( service, null );
    }

    @Test
    void projectPage_createsNodeAndEdges() {
        final Map< String, Object > frontmatter = Map.of(
            "type", "domain-model",
            "domain", "billing",
            "depends-on", List.of( "Customer", "Product" ),
            "tags", List.of( "core" )
        );
        projector.projectPage( "Order", frontmatter, "" );
        final KgNode order = service.getNodeByName( "Order" );
        assertNotNull( order );
        assertEquals( "domain-model", order.nodeType() );
        assertEquals( "Order", order.sourcePage() );
        assertEquals( "billing", order.properties().get( "domain" ) );
        // Customer and Product should exist as stubs
        assertNotNull( service.getNodeByName( "Customer" ) );
        assertTrue( service.getNodeByName( "Customer" ).isStub() );
        // Two outbound edges from Order
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 2, edges.size() );
    }

    @Test
    void projectPage_removesStaleEdges() {
        projector.projectPage( "Order", Map.of(
            "type", "domain-model",
            "depends-on", List.of( "Customer", "Product" )
        ), "" );
        // Second save: only depends on Customer now
        projector.projectPage( "Order", Map.of(
            "type", "domain-model",
            "depends-on", List.of( "Customer" )
        ), "" );
        final KgNode order = service.getNodeByName( "Order" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 1, edges.size() );
        assertEquals( service.getNodeByName( "Customer" ).id(), edges.get( 0 ).targetId() );
    }

    @Test
    void projectPage_preservesAiEdges() {
        projector.projectPage( "Order", Map.of( "depends-on", List.of( "Customer" ) ), "" );
        // AI adds an edge
        final KgNode order = service.getNodeByName( "Order" );
        final KgNode inv = service.upsertNode( "Inventory", "service", null,
                Provenance.AI_INFERRED, Map.of() );
        service.upsertEdge( order.id(), inv.id(), "calls", Provenance.AI_INFERRED, Map.of() );
        // Human re-saves — AI edge should survive
        projector.projectPage( "Order", Map.of( "depends-on", List.of( "Customer" ) ), "" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 2, edges.size() );
    }

    @Test
    void projectPage_hydratesStubNode() {
        projector.projectPage( "Order", Map.of( "depends-on", List.of( "Customer" ) ), "" );
        assertTrue( service.getNodeByName( "Customer" ).isStub() );
        // Now Customer gets its own page
        projector.projectPage( "Customer", Map.of(
            "type", "domain-model",
            "domain", "crm"
        ), "" );
        final KgNode customer = service.getNodeByName( "Customer" );
        assertFalse( customer.isStub() );
        assertEquals( "domain-model", customer.nodeType() );
        assertEquals( "Customer", customer.sourcePage() );
    }

    @Test
    void projectPage_createsLinksToEdgesFromBody() {
        final String body = "See [Customer]() for details and also check [text](Product).";
        projector.projectPage( "Order", Map.of(), body );

        final KgNode order = service.getNodeByName( "Order" );
        assertNotNull( order );
        assertNotNull( service.getNodeByName( "Customer" ) );
        assertNotNull( service.getNodeByName( "Product" ) );

        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 2, edges.size() );
        assertTrue( edges.stream().allMatch( e -> "links_to".equals( e.relationshipType() ) ) );
    }

    @Test
    void projectPage_combinesFrontmatterAndBodyLinks() {
        final Map< String, Object > fm = Map.of( "depends-on", List.of( "Customer" ) );
        final String body = "Also see [Product]() for pricing.";
        projector.projectPage( "Order", fm, body );

        final KgNode order = service.getNodeByName( "Order" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 2, edges.size() );

        final KgNode customer = service.getNodeByName( "Customer" );
        final KgNode product = service.getNodeByName( "Product" );
        assertTrue( edges.stream().anyMatch( e ->
            e.targetId().equals( customer.id() ) && "depends-on".equals( e.relationshipType() ) ) );
        assertTrue( edges.stream().anyMatch( e ->
            e.targetId().equals( product.id() ) && "links_to".equals( e.relationshipType() ) ) );
    }

    @Test
    void projectPage_removesStaleBodyLinks() {
        projector.projectPage( "Order", Map.of(), "See [Customer]() and [Product]()." );
        // Second save: only links to Customer now
        projector.projectPage( "Order", Map.of(), "See [Customer]()." );

        final KgNode order = service.getNodeByName( "Order" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 1, edges.size() );
        assertEquals( service.getNodeByName( "Customer" ).id(), edges.get( 0 ).targetId() );
    }

    @Test
    void projectPage_skipsSelfLinks() {
        projector.projectPage( "Order", Map.of(), "See [Order]() for more." );

        final KgNode order = service.getNodeByName( "Order" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertTrue( edges.isEmpty() );
    }

    @Test
    void projectPage_bodyLinkAndFrontmatterToSameTarget() {
        final Map< String, Object > fm = Map.of( "depends-on", List.of( "Customer" ) );
        final String body = "See also [Customer]() for details.";
        projector.projectPage( "Order", fm, body );

        final KgNode order = service.getNodeByName( "Order" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        // Two distinct edges: depends-on and links_to, both targeting Customer
        assertEquals( 2, edges.size() );
        assertTrue( edges.stream().anyMatch( e -> "depends-on".equals( e.relationshipType() ) ) );
        assertTrue( edges.stream().anyMatch( e -> "links_to".equals( e.relationshipType() ) ) );
    }

    @Test
    void projectPage_noFrontmatterNoLinksCreatesNodeOnly() {
        projector.projectPage( "LonelyPage", Map.of(), "" );

        final KgNode node = service.getNodeByName( "LonelyPage" );
        assertNotNull( node );
        assertEquals( "LonelyPage", node.sourcePage() );
        final List< KgEdge > edges = service.getEdgesForNode( node.id(), "outbound" );
        assertTrue( edges.isEmpty() );
    }
}
