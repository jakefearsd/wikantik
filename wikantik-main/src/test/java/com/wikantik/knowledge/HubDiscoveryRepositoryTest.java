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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubDiscoveryRepositoryTest {

    private static DataSource dataSource;
    private HubDiscoveryRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_discovery_proposals" );
        }
        repo = new HubDiscoveryRepository( dataSource );
    }

    @Test
    void insert_thenList_returnsRow() {
        final int id = repo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "O'Brien's Scala" ), 0.84 );
        assertTrue( id > 0 );
        final List< HubDiscoveryRepository.HubDiscoveryProposal > all = repo.list( 50, 0 );
        assertEquals( 1, all.size() );
        final var row = all.get( 0 );
        assertEquals( "JavaHub", row.suggestedName() );
        assertEquals( "Java", row.exemplarPage() );
        assertEquals( List.of( "Java", "Kotlin", "O'Brien's Scala" ), row.memberPages() );
        assertEquals( 0.84, row.coherenceScore(), 1e-9 );
        assertNotNull( row.created() );
    }

    @Test
    void findById_returnsRowOrNull() {
        final int id = repo.insert( "TechHub", "Java", List.of( "Java", "Python" ), 0.7 );
        final var found = repo.findById( id );
        assertNotNull( found );
        assertEquals( id, found.id() );
        assertNull( repo.findById( id + 9999 ) );
    }

    @Test
    void delete_removesRow() {
        final int id = repo.insert( "H", "A", List.of( "A", "B", "C" ), 0.5 );
        assertTrue( repo.delete( id ) );
        assertNull( repo.findById( id ) );
    }

    @Test
    void delete_missingId_returnsFalse() {
        assertFalse( repo.delete( 99999 ) );
    }

    @Test
    void list_orderedByCreatedDesc() throws Exception {
        repo.insert( "First", "A", List.of( "A", "B", "C" ), 0.5 );
        Thread.sleep( 10 );
        repo.insert( "Second", "D", List.of( "D", "E", "F" ), 0.6 );
        Thread.sleep( 10 );
        repo.insert( "Third", "G", List.of( "G", "H", "I" ), 0.7 );
        final var all = repo.list( 50, 0 );
        assertEquals( 3, all.size() );
        assertEquals( "Third", all.get( 0 ).suggestedName() );
        assertEquals( "First", all.get( 2 ).suggestedName() );
    }

    @Test
    void list_respectsLimit() {
        for ( int i = 0; i < 5; i++ ) {
            repo.insert( "H" + i, "A", List.of( "A", "B", "C" ), 0.5 );
        }
        assertEquals( 2, repo.list( 2, 0 ).size() );
        assertEquals( 3, repo.list( 3, 0 ).size() );
    }

    @Test
    void count_returnsTotal() {
        assertEquals( 0, repo.count() );
        repo.insert( "H1", "A", List.of( "A", "B", "C" ), 0.5 );
        repo.insert( "H2", "D", List.of( "D", "E", "F" ), 0.6 );
        assertEquals( 2, repo.count() );
    }
}
