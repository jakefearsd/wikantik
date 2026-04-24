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
import com.wikantik.knowledge.MentionIndex;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        service = new DefaultKnowledgeGraphService(
            new JdbcKnowledgeRepository( dataSource ),
            null,
            new MentionIndex( dataSource ) );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
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
    void traverse_returnsSubgraph() throws Exception {
        final UUID nodeA = service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID nodeB = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID chunk = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunk + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'h0')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunk + "', '" + nodeA + "', 0.9, 't', NOW()), "
              + "('" + chunk + "', '" + nodeB + "', 0.9, 't', NOW())" );
        }
        final TraverseTool tool = new TraverseTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "start_node", "Order",
            "max_depth", 1
        ) );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Order" ) );
        assertTrue( text.contains( "Customer" ) );
        assertTrue( text.contains( "co-mentions" ) );
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

    @Test
    void queryNodes_filtersToMentionedNodesOnly() throws Exception {
        final UUID mentioned = service.upsertNode( "QNMentioned", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        service.upsertNode( "QNUnmentioned", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() );

        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunkId + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'h2')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunkId + "', '" + mentioned + "', 0.9, 't', NOW())" );
        }

        final MentionIndex idx = new MentionIndex( dataSource );
        final QueryNodesTool tool = new QueryNodesTool( service, idx );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "filters", Map.of( "node_type", "kind" ),
            "limit", 50 ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();

        assertTrue( text.contains( "QNMentioned" ) );
        assertFalse( text.contains( "QNUnmentioned" ) );
    }

    @Test
    void searchKnowledge_filtersToMentionedNodesOnly() throws Exception {
        final UUID mentioned = service.upsertNode( "MentionedNode", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        service.upsertNode( "UnmentionedNode", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() );

        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunkId + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'h1')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunkId + "', '" + mentioned + "', 0.9, 't', NOW())" );
        }

        final MentionIndex idx = new MentionIndex( dataSource );
        final SearchKnowledgeTool tool = new SearchKnowledgeTool( service, idx );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "query", "Node" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();

        assertTrue( text.contains( "MentionedNode" ) );
        assertFalse( text.contains( "UnmentionedNode" ) );
    }

    @Test
    void traverse_walksCoMentionGraph() throws Exception {
        final UUID alpha = service.upsertNode( "TrvAlpha", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID beta = service.upsertNode( "TrvBeta", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID chunk = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunk + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'ht')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunk + "', '" + alpha + "', 0.9, 't', NOW()), "
              + "('" + chunk + "', '" + beta  + "', 0.9, 't', NOW())" );
        }

        final TraverseTool tool = new TraverseTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "start_node", "TrvAlpha",
            "max_depth", 1 ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "TrvBeta" ) );
        assertTrue( text.contains( "co-mentions" ) );
    }

    @Test
    void discoverSchema_reportsMentionCoverageStats() throws Exception {
        final UUID mentioned = service.upsertNode( "SchAlpha", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        service.upsertNode( "SchBeta", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() );

        final UUID chunk = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunk + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'hs')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunk + "', '" + mentioned + "', 0.9, 't', NOW())" );
        }

        final MentionIndex idx = new MentionIndex( dataSource );
        final DiscoverSchemaTool tool = new DiscoverSchemaTool( service, idx );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "mentionedNodeCount" ) );
        assertTrue( text.contains( "\"mentionedNodeCount\":1" ) );
    }
}
