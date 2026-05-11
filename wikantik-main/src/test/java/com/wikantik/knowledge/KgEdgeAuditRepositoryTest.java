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
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KgEdgeAuditRepositoryTest {

    private static DataSource dataSource;
    private KgEdgeAuditRepository repo;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestContainer.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edge_audit" );
        }
        repo = new KgEdgeAuditRepository( dataSource );
    }

    @Test
    void insertAndFindByEdgeIdOrdersNewestFirst() throws Exception {
        final UUID edgeId = UUID.randomUUID();
        repo.insert( edgeId, "CREATE", null,
                Map.of( "id", edgeId.toString(), "relationship_type", "related" ),
                "alice", null );
        Thread.sleep( 5 );
        repo.insert( edgeId, "UPDATE",
                Map.of( "relationship_type", "related" ),
                Map.of( "relationship_type", "depends_on" ),
                "bob", "type rewrite" );
        Thread.sleep( 5 );
        repo.insert( edgeId, "DELETE",
                Map.of( "relationship_type", "depends_on" ),
                null, "carol", "wrong direction" );

        final List< Map< String, Object > > rows = repo.findByEdgeId( edgeId, 10 );

        assertEquals( 3, rows.size() );
        assertEquals( "DELETE", rows.get( 0 ).get( "action" ) );
        assertEquals( "UPDATE", rows.get( 1 ).get( "action" ) );
        assertEquals( "CREATE", rows.get( 2 ).get( "action" ) );
        assertEquals( "carol", rows.get( 0 ).get( "actor" ) );
        assertEquals( "wrong direction", rows.get( 0 ).get( "reason" ) );
    }

    @Test
    void findByEdgeIdRespectsLimit() {
        final UUID edgeId = UUID.randomUUID();
        for ( int i = 0; i < 5; i++ ) {
            repo.insert( edgeId, "UPDATE", Map.of(), Map.of( "i", i ), "alice", null );
        }
        assertEquals( 2, repo.findByEdgeId( edgeId, 2 ).size() );
    }

    @Test
    void findByMissingEdgeReturnsEmptyList() {
        assertTrue( repo.findByEdgeId( UUID.randomUUID(), 10 ).isEmpty() );
    }

    @Test
    void auditInsertFailureDoesNotThrow() {
        // Use a clearly invalid action — the CHECK constraint will reject it. Insert must
        // log-and-continue per the design's fidelity-not-correctness rule.
        assertDoesNotThrow( () -> repo.insert( UUID.randomUUID(), "BOGUS", null, null, "alice", null ) );
    }
}
