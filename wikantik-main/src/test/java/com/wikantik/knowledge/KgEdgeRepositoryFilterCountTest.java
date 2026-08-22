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
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class KgEdgeRepositoryFilterCountTest {

    private static DataSource dataSource;
    private KgEdgeRepository edges;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        edges = new KgEdgeRepository( dataSource );
        nodes = new KgNodeRepository( dataSource );
    }

    @Test
    void countMatchesFilteredQuery() {
        final UUID a = nodes.upsertNode( "NodeA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "NodeB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID c = nodes.upsertNode( "NodeC", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( a, c, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( b, c, "requires", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( 2L, edges.countEdgesWithFilter( "related_to", null ) );
        assertEquals( 1L, edges.countEdgesWithFilter( "requires", null ) );
        assertEquals( 3L, edges.countEdgesWithFilter( null, null ) );
        assertEquals( 2L, edges.countEdgesWithFilter( null, "NodeA" ) ); // matches source OR target
    }

    @Test
    void countReturnsZeroForUnknownType() {
        assertEquals( 0L, edges.countEdgesWithFilter( "no_such_type", null ) );
    }

    @Test
    void countIsCaseInsensitiveOnNameSearch() {
        final UUID a = nodes.upsertNode( "CamelCase", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "OtherNode", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( 1L, edges.countEdgesWithFilter( null, "camelcase" ) );
        assertEquals( 1L, edges.countEdgesWithFilter( null, "CAMEL" ) );
    }
}
