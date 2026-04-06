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
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.*;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KnowledgeMcpToolsTest {

    private static DataSource dataSource;
    private KnowledgeGraphService service;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() {
        service = new DefaultKnowledgeGraphService( new JdbcKnowledgeRepository( dataSource ) );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void discoverSchema_returnsSchemaDescription() {
        service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final DiscoverSchemaTool tool = new DiscoverSchemaTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "domain-model" ) );
        assertTrue( text.contains( "billing" ) );
    }

    @Test
    void queryNodes_filtersCorrectly() {
        service.upsertNode( "Order", "domain-model", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "PaymentGW", "service", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final QueryNodesTool tool = new QueryNodesTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "filters", Map.of( "node_type", "domain-model" ),
            "limit", 50
        ) );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Order" ) );
        assertFalse( text.contains( "PaymentGW" ) );
    }

    @Test
    void traverse_returnsSubgraph() {
        final KgNode order = service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraverseTool tool = new TraverseTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "start_node", "Order",
            "direction", "outbound",
            "max_depth", 3
        ) );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Order" ) );
        assertTrue( text.contains( "Customer" ) );
        assertTrue( text.contains( "depends-on" ) );
    }

    @Test
    void getNode_returnsFullDetail() {
        final KgNode order = service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        final GetNodeTool tool = new GetNodeTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "node", "Order" ) );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Order" ) );
        assertTrue( text.contains( "domain-model" ) );
        assertTrue( text.contains( "depends-on" ) );
    }

    @Test
    void searchKnowledge_findsByName() {
        service.upsertNode( "OrderProcessing", "service", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final SearchKnowledgeTool tool = new SearchKnowledgeTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "query", "order",
            "limit", 10
        ) );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "OrderProcessing" ) );
    }
}
