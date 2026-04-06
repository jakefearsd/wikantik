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
package com.wikantik.plugin;

import com.wikantik.PostgresTestContainer;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.spi.Wiki;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class RelationshipsPluginTest {

    private static DataSource dataSource;
    private static TestEngine engine;
    private static KnowledgeGraphService service;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = PostgresTestContainer.createDataSource();

        engine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        service = new DefaultKnowledgeGraphService( repo );
        engine.setManager( KnowledgeGraphService.class, service );
    }

    @BeforeEach
    void cleanUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void rendersOutboundRelationships() throws Exception {
        final var a = service.upsertNode( "GraphProjector", "article", "GraphProjector.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var b = service.upsertNode( "KnowledgeGraphCore", "article", "KnowledgeGraphCore.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "part-of", Provenance.HUMAN_AUTHORED, Map.of() );

        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "GraphProjector" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );

        assertTrue( result.contains( "KnowledgeGraphCore" ), "Should contain target name" );
        assertTrue( result.contains( "Part of" ), "Should contain relationship label" );
        assertTrue( result.contains( "href" ), "Should contain a link" );
    }

    @Test
    void rendersInboundRelationshipsWithInvertedLabels() throws Exception {
        final var a = service.upsertNode( "KnowledgeGraphCore", "article", "KnowledgeGraphCore.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var b = service.upsertNode( "GraphProjector", "article", "GraphProjector.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( b.id(), a.id(), "part-of", Provenance.HUMAN_AUTHORED, Map.of() );

        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "KnowledgeGraphCore" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );

        assertTrue( result.contains( "GraphProjector" ), "Should contain source name" );
        assertTrue( result.contains( "Parts" ), "Should contain inverted label" );
    }

    @Test
    void returnsEmptyStringWhenNoNodeExists() throws Exception {
        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "NonExistentPage" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );
        assertEquals( "", result );
    }

    @Test
    void returnsEmptyStringWhenNoEdgesExist() throws Exception {
        service.upsertNode( "LonelyNode", "article", "LonelyNode.md",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "LonelyNode" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );
        assertEquals( "", result );
    }
}
