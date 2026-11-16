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
package com.wikantik.work;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class JdbcWorkRegistryPostgresTest {

    private DataSource ds;
    private JdbcWorkStore store;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        store.createQueue( "implementer", "Implementer", "" );
        store.createQueue( "reviewer", "Reviewer", "" );
    }

    private int newKey() throws SQLException {
        return ( int ) WorkAgentFixtures.apiKey( ds, "work" );
    }

    @Test
    void queueRoundTrip() throws SQLException {
        final Queue q = store.createQueue( "ops", "Ops", "on call" );
        assertEquals( new Queue( "ops", "Ops", "on call", null ), q );
        assertEquals( q, store.queue( "ops" ).orElseThrow() );
        assertTrue( store.queue( "nope" ).isEmpty() );
        store.updateQueue( "ops", "Operations", "24x7" );
        assertEquals( "Operations", store.queue( "ops" ).orElseThrow().name() );
        assertEquals( "24x7", store.queue( "ops" ).orElseThrow().description() );
        assertEquals( List.of( "implementer", "ops", "reviewer" ),
            store.queues().stream().map( Queue::key ).toList() );
    }

    @Test
    void duplicateQueueRejected() throws SQLException {
        store.createQueue( "ops", "a", "" );
        final SQLException e = assertThrows( SQLException.class, () -> store.createQueue( "ops", "b", "" ) );
        assertEquals( "23505", e.getSQLState() );
    }

    @Test
    void retiredQueueStillListedWithRetiredAt() throws SQLException {
        final Instant at = Instant.parse( "2026-11-16T10:00:00Z" );
        store.retireQueue( "reviewer", at );
        final Queue q = store.queues().stream().filter( x -> x.key().equals( "reviewer" ) ).findFirst().orElseThrow();
        assertEquals( at, q.retiredAt() );
        assertNull( store.queue( "implementer" ).orElseThrow().retiredAt() );
    }

    @Test
    void registerAgentWithQueues() throws SQLException {
        final Agent a = store.registerAgent( "Builder", "acme", "cli", List.of( "reviewer", "implementer" ), "sponsor-root" );
        assertEquals( "builder", a.name() );
        assertTrue( a.active() );
        assertNotNull( a.createdAt() );
        final List< Agent > all = store.agents();
        assertEquals( 1, all.size() );
        assertEquals( List.of( "implementer", "reviewer" ), all.get( 0 ).queues() );
        assertEquals( a, store.agent( a.id() ).orElseThrow() );
    }

    @Test
    void agentsListedByNameEachWithItsQueues() throws SQLException {
        store.registerAgent( "zed", "", "", List.of( "reviewer" ), "sponsor-root" );
        store.registerAgent( "amy", "", "", List.of(), "sponsor-root" );
        final List< Agent > all = store.agents();
        assertEquals( List.of( "amy", "zed" ), all.stream().map( Agent::name ).toList() );
        assertEquals( List.of(), all.get( 0 ).queues() );
        assertEquals( List.of( "reviewer" ), all.get( 1 ).queues() );
    }


    @Test
    void agentByNameIgnoresCase() throws SQLException {
        final Agent a = store.registerAgent( "builder", "", "", List.of(), "sponsor-root" );
        assertEquals( a.id(), store.agentByName( "BUILDER" ).orElseThrow().id() );
        assertTrue( store.agentByName( "other" ).isEmpty() );
    }

    @Test
    void updateAgentReplacesQueues() throws SQLException {
        final Agent a = store.registerAgent( "builder", "v", "r", List.of( "implementer" ), "sponsor-root" );
        store.updateAgent( a.id(), "Builder2", "v2", "r2", List.of( "reviewer" ) );
        final Agent u = store.agent( a.id() ).orElseThrow();
        assertEquals( "builder2", u.name() );
        assertEquals( "v2", u.vendor() );
        assertEquals( "r2", u.runtime() );
        assertEquals( List.of( "reviewer" ), u.queues() );
    }

    @Test
    void renameOntoExistingNameRejected() throws SQLException {
        store.registerAgent( "one", "", "", List.of(), "sponsor-root" );
        final Agent two = store.registerAgent( "two", "", "", List.of(), "sponsor-root" );
        final SQLException e = assertThrows( SQLException.class,
            () -> store.updateAgent( two.id(), "one", "", "", List.of() ) );
        assertEquals( "23505", e.getSQLState() );
    }



    @Test
    void renameDiffersOnlyInCaseIsRejected() throws SQLException {
        store.registerAgent( "codex@x", "", "", List.of(), "sponsor-root" );
        final Agent two = store.registerAgent( "other", "", "", List.of(), "sponsor-root" );
        final SQLException e = assertThrows( SQLException.class,
            () -> store.updateAgent( two.id(), "Codex@x", "", "", List.of() ) );
        assertEquals( "23505", e.getSQLState() );
        assertEquals( "other", store.agent( two.id() ).orElseThrow().name() );
    }




    @Test
    void registerRecordsTheSponsorAndTheFirstHistoryRow() throws SQLException {
        final Agent a = store.registerAgent( "codex", "acme", "cli", List.of( "implementer" ), "ana" );
        assertEquals( "ana", a.sponsor() );
        assertEquals( "ana", store.agent( a.id() ).orElseThrow().sponsor(), "agentRowCarriesSponsor" );
        final List< WorkRegistryStore.SponsorChange > history = store.sponsorHistory( a.id() );
        assertEquals( 1, history.size() );
        assertEquals( "ana", history.get( 0 ).sponsor() );
        assertEquals( "ana", history.get( 0 ).changedBy() );
    }

    @Test
    void setSponsorWritesHistoryInOrder() throws SQLException {
        final Agent a = store.registerAgent( "codex", "", "", List.of(), "ana" );
        store.setSponsor( a.id(), null, "ana" );
        assertNull( store.agent( a.id() ).orElseThrow().sponsor() );
        store.setSponsor( a.id(), "bo", "bo" );
        assertEquals( "bo", store.agent( a.id() ).orElseThrow().sponsor() );
        final List< WorkRegistryStore.SponsorChange > history = store.sponsorHistory( a.id() );
        assertEquals( java.util.Arrays.asList( "bo", null, "ana" ),
            history.stream().map( WorkRegistryStore.SponsorChange::sponsor ).toList(), "newest first" );
        assertEquals( List.of( "bo", "ana", "ana" ),
            history.stream().map( WorkRegistryStore.SponsorChange::changedBy ).toList() );
    }
}
