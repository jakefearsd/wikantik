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
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeRepositorySearchTierTest {

    private DataSource ds;
    private KgNodeRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        repo = new KgNodeRepository( ds );
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
    void searchNodes_filters_by_tier() throws Exception {
        // Human-tier seed via repo (defaults tier='human').
        repo.upsertNode( "AppleTree", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() );
        // Machine-tier seed via direct INSERT.
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
                "VALUES ('AppleSeed', 'concept', null, 'ai-inferred', '{}'::jsonb, 'machine', NOW())" );
        }

        final List< KgNode > strict = repo.searchNodes( "Apple",
            Set.of( Provenance.HUMAN_AUTHORED, Provenance.AI_INFERRED ), 10, Tier.HUMAN );
        assertTrue( strict.stream().anyMatch( n -> n.name().equals( "AppleTree" ) ) );
        assertFalse( strict.stream().anyMatch( n -> n.name().equals( "AppleSeed" ) ) );

        final List< KgNode > broad = repo.searchNodes( "Apple",
            Set.of( Provenance.HUMAN_AUTHORED, Provenance.AI_INFERRED ), 10, Tier.MACHINE );
        assertTrue( broad.stream().anyMatch( n -> n.name().equals( "AppleTree" ) ) );
        assertTrue( broad.stream().anyMatch( n -> n.name().equals( "AppleSeed" ) ) );
    }

    @Test
    void searchNodes_tier_overload_with_no_provenance_filter() throws Exception {
        repo.upsertNode( "Banana", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
                "VALUES ('Bandana', 'concept', null, 'ai-inferred', '{}'::jsonb, 'machine', NOW())" );
        }

        final List< KgNode > broad = repo.searchNodes( "Ban", null, 10, Tier.MACHINE );
        assertEquals( 2, broad.size(), "no provenance filter + MACHINE tier returns both" );
    }
}
