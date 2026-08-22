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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class JdbcKnowledgeRepositoryTierReadsTest {

    private DataSource ds;
    private KgNodeRepository nodes;
    private KgEdgeRepository edgeRepo;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestDb.createDataSource();
        nodes    = new KgNodeRepository( ds );
        edgeRepo = new KgEdgeRepository( ds );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void getAllNodes_filters_by_tier() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
                "VALUES ('Human', 'concept', null, 'human-authored', '{}'::jsonb, 'human', NOW())" );
            c.createStatement().execute(
                "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
                "VALUES ('Machine', 'concept', null, 'human-authored', '{}'::jsonb, 'machine', NOW())" );
        }

        final List< KgNode > humanOnly = nodes.getAllNodes( Tier.HUMAN );
        assertTrue( humanOnly.stream().anyMatch( n -> n.name().equals( "Human" ) ) );
        assertFalse( humanOnly.stream().anyMatch( n -> n.name().equals( "Machine" ) ) );

        final List< KgNode > both = nodes.getAllNodes( Tier.MACHINE );
        assertTrue( both.stream().anyMatch( n -> n.name().equals( "Human" ) ) );
        assertTrue( both.stream().anyMatch( n -> n.name().equals( "Machine" ) ) );
    }

    @Test
    void getAllEdges_filters_by_tier() throws Exception {
        final KgNode a = nodes.upsertNode( "A", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = nodes.upsertNode( "B", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( String.format(
                "INSERT INTO kg_edges (source_id, target_id, relationship_type, provenance, properties, tier, modified) " +
                "VALUES ('%s'::uuid, '%s'::uuid, 'related_to', 'human-authored', '{}'::jsonb, 'human', NOW())",
                a.id(), b.id() ) );
            c.createStatement().execute( String.format(
                "INSERT INTO kg_edges (source_id, target_id, relationship_type, provenance, properties, tier, modified) " +
                "VALUES ('%s'::uuid, '%s'::uuid, 'requires', 'human-authored', '{}'::jsonb, 'machine', NOW())",
                a.id(), b.id() ) );
        }

        final List< KgEdge > humanOnly = edgeRepo.getAllEdges( Tier.HUMAN );
        assertTrue( humanOnly.stream().anyMatch( e -> e.relationshipType().equals( "related_to" ) ) );
        assertFalse( humanOnly.stream().anyMatch( e -> e.relationshipType().equals( "requires" ) ) );

        final List< KgEdge > both = edgeRepo.getAllEdges( Tier.MACHINE );
        assertTrue( both.stream().anyMatch( e -> e.relationshipType().equals( "related_to" ) ) );
        assertTrue( both.stream().anyMatch( e -> e.relationshipType().equals( "requires" ) ) );
    }
}
