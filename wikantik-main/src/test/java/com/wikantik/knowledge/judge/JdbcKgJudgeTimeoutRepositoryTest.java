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
package com.wikantik.knowledge.judge;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class JdbcKgJudgeTimeoutRepositoryTest {

    private static DataSource ds;
    private JdbcKgJudgeTimeoutRepository repo;

    @BeforeAll
    static void init() { ds = PostgresTestContainer.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_judge_timeouts" );
        }
        repo = new JdbcKgJudgeTimeoutRepository( ds );
    }

    @Test
    void recordTimeout_inserts_then_increments_on_conflict() {
        final UUID id = UUID.randomUUID();

        repo.recordTimeout( id, "sha-a", "PageX", "new-edge", "gemma:latest",
            1234, "read timed out", 120 );
        Optional< KgJudgeTimeoutRepository.TimeoutRow > row1 = repo.find( id );
        assertTrue( row1.isPresent() );
        assertEquals( 1, row1.get().timeoutCount() );
        assertEquals( "sha-a", row1.get().contentSha256() );
        assertEquals( "PageX", row1.get().sourcePage() );
        assertEquals( "gemma:latest", row1.get().modelName() );
        assertEquals( 1234, row1.get().contentBytes() );
        assertEquals( "read timed out", row1.get().lastErrorExcerpt() );
        assertEquals( 120, row1.get().baseTimeoutSeconds() );
        assertNotNull( row1.get().firstSeen() );
        assertNotNull( row1.get().lastSeen() );

        repo.recordTimeout( id, "sha-a", "PageX", "new-edge", "gemma:v2",
            1300, "read timed out v2", 120 );
        Optional< KgJudgeTimeoutRepository.TimeoutRow > row2 = repo.find( id );
        assertEquals( 2, row2.get().timeoutCount() );
        assertEquals( "gemma:v2", row2.get().modelName() );
        assertEquals( 1300, row2.get().contentBytes() );
        assertEquals( "read timed out v2", row2.get().lastErrorExcerpt() );
        assertEquals( row1.get().firstSeen(), row2.get().firstSeen(),
            "first_seen must be preserved across upserts" );
    }

    @Test
    void clear_deletes_existing_row() {
        final UUID id = UUID.randomUUID();
        repo.recordTimeout( id, "sha", "P", "new-edge", "m", 1, "e", 60 );
        assertTrue( repo.find( id ).isPresent() );

        repo.clear( id );
        assertTrue( repo.find( id ).isEmpty() );
    }

    @Test
    void clear_is_no_op_when_row_absent() {
        assertDoesNotThrow( () -> repo.clear( UUID.randomUUID() ) );
    }

    @Test
    void listTopChronic_returns_rows_sorted_by_count_desc() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final UUID c = UUID.randomUUID();
        repo.recordTimeout( a, "sa", "Pa", "t", "m", 1, "e", 60 );
        repo.recordTimeout( b, "sb", "Pb", "t", "m", 1, "e", 60 );
        repo.recordTimeout( b, "sb", "Pb", "t", "m", 1, "e", 60 );
        repo.recordTimeout( b, "sb", "Pb", "t", "m", 1, "e", 60 );
        repo.recordTimeout( c, "sc", "Pc", "t", "m", 1, "e", 60 );
        repo.recordTimeout( c, "sc", "Pc", "t", "m", 1, "e", 60 );

        final List< KgJudgeTimeoutRepository.TimeoutRow > rows = repo.listTopChronic( 10 );

        assertEquals( 3, rows.size() );
        assertEquals( b, rows.get( 0 ).proposalId() );
        assertEquals( 3, rows.get( 0 ).timeoutCount() );
        assertEquals( c, rows.get( 1 ).proposalId() );
        assertEquals( 2, rows.get( 1 ).timeoutCount() );
        assertEquals( a, rows.get( 2 ).proposalId() );
        assertEquals( 1, rows.get( 2 ).timeoutCount() );
    }

    @Test
    void listTopChronic_honours_limit() {
        for ( int i = 0; i < 5; i++ ) {
            repo.recordTimeout( UUID.randomUUID(), "s", "P", "t", "m", 1, "e", 60 );
        }
        assertEquals( 2, repo.listTopChronic( 2 ).size() );
    }
}
