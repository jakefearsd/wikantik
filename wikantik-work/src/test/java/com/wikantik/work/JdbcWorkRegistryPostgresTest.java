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
        final int key = newKey();
        final Agent a = store.registerAgent( "Builder", "acme", "cli", key, List.of( "reviewer", "implementer" ) );
        assertEquals( "builder", a.name() );
        assertEquals( key, a.apiKeyId() );
        assertTrue( a.active() );
        assertNotNull( a.createdAt() );
        final List< Agent > all = store.agents();
        assertEquals( 1, all.size() );
        assertEquals( List.of( "implementer", "reviewer" ), all.get( 0 ).queues() );
        assertEquals( a, store.agent( a.id() ).orElseThrow() );
    }

    @Test
    void agentsListedByNameEachWithItsQueues() throws SQLException {
        store.registerAgent( "zed", "", "", newKey(), List.of( "reviewer" ) );
        store.registerAgent( "amy", "", "", newKey(), List.of() );
        final List< Agent > all = store.agents();
        assertEquals( List.of( "amy", "zed" ), all.stream().map( Agent::name ).toList() );
        assertEquals( List.of(), all.get( 0 ).queues() );
        assertEquals( List.of( "reviewer" ), all.get( 1 ).queues() );
    }

    @Test
    void agentByApiKeyFindsRetiredAgentToo() throws SQLException {
        final int key = newKey();
        final Agent a = store.registerAgent( "old", "", "", key, List.of() );
        WorkAgentFixtures.exec( ds, "UPDATE work_agent SET retired_at = now() WHERE id = " + a.id() );
        final Agent found = store.agentByApiKey( key ).orElseThrow();
        assertEquals( a.id(), found.id() );
        assertFalse( found.active() );
        assertTrue( store.agentByApiKey( newKey() ).isEmpty() );
    }

    @Test
    void agentByNameIgnoresCase() throws SQLException {
        final Agent a = store.registerAgent( "builder", "", "", newKey(), List.of() );
        assertEquals( a.id(), store.agentByName( "BUILDER" ).orElseThrow().id() );
        assertTrue( store.agentByName( "other" ).isEmpty() );
    }

    @Test
    void updateAgentReplacesQueues() throws SQLException {
        final Agent a = store.registerAgent( "builder", "v", "r", newKey(), List.of( "implementer" ) );
        store.updateAgent( a.id(), "Builder2", "v2", "r2", List.of( "reviewer" ) );
        final Agent u = store.agent( a.id() ).orElseThrow();
        assertEquals( "builder2", u.name() );
        assertEquals( "v2", u.vendor() );
        assertEquals( "r2", u.runtime() );
        assertEquals( List.of( "reviewer" ), u.queues() );
        assertEquals( a.apiKeyId(), u.apiKeyId() );
    }

    @Test
    void renameOntoExistingNameRejected() throws SQLException {
        store.registerAgent( "one", "", "", newKey(), List.of() );
        final Agent two = store.registerAgent( "two", "", "", newKey(), List.of() );
        final SQLException e = assertThrows( SQLException.class,
            () -> store.updateAgent( two.id(), "one", "", "", List.of() ) );
        assertEquals( "23505", e.getSQLState() );
    }

    @Test
    void rebindAgentKeyMovesBinding() throws SQLException {
        final int oldKey = newKey();
        final int newKey = newKey();
        final Agent a = store.registerAgent( "builder", "", "", oldKey, List.of() );
        store.rebindAgentKey( a.id(), newKey );
        assertTrue( store.agentByApiKey( oldKey ).isEmpty() );
        assertEquals( a.id(), store.agentByApiKey( newKey ).orElseThrow().id() );
    }

    @Test
    void sameKeyCannotBindTwoAgents() throws SQLException {
        final int key = newKey();
        store.registerAgent( "one", "", "", key, List.of() );
        final SQLException e = assertThrows( SQLException.class,
            () -> store.registerAgent( "two", "", "", key, List.of() ) );
        assertEquals( "23505", e.getSQLState() );
        final Agent other = store.registerAgent( "three", "", "", newKey(), List.of() );
        final SQLException e2 = assertThrows( SQLException.class, () -> store.rebindAgentKey( other.id(), key ) );
        assertEquals( "23505", e2.getSQLState() );
        // The refused rebind left the other agent bound to its own key.
        assertEquals( other.id(), store.agentByApiKey( other.apiKeyId() ).orElseThrow().id() );
        assertEquals( other.apiKeyId(), store.agent( other.id() ).orElseThrow().apiKeyId() );
    }

    @Test
    void renameDiffersOnlyInCaseIsRejected() throws SQLException {
        store.registerAgent( "codex@x", "", "", newKey(), List.of() );
        final Agent two = store.registerAgent( "other", "", "", newKey(), List.of() );
        final SQLException e = assertThrows( SQLException.class,
            () -> store.updateAgent( two.id(), "Codex@x", "", "", List.of() ) );
        assertEquals( "23505", e.getSQLState() );
        assertEquals( "other", store.agent( two.id() ).orElseThrow().name() );
    }

    @Test
    void rebindRecordsTheOldKeyInHistory() throws SQLException {
        final int first = newKey();
        final int second = newKey();
        final Agent a = store.registerAgent( "a", "", "", first, List.of() );
        assertFalse( store.wasEverAgentKey( first ), "a bound key is not history yet" );
        store.rebindAgentKey( a.id(), second );
        assertTrue( store.wasEverAgentKey( first ) );
        assertFalse( store.wasEverAgentKey( second ) );
        assertEquals( second, store.agent( a.id() ).orElseThrow().apiKeyId() );
        assertFalse( store.wasEverAgentKey( newKey() ), "a key never bound to an agent" );
    }

    @Test
    void rebindToAKeyBoundElsewhereLeavesNoHistory() throws SQLException {
        final int first = newKey();
        final int other = newKey();
        final Agent a = store.registerAgent( "a", "", "", first, List.of() );
        store.registerAgent( "b", "", "", other, List.of() );
        final SQLException e = assertThrows( SQLException.class, () -> store.rebindAgentKey( a.id(), other ) );
        assertEquals( "23505", e.getSQLState() );
        assertFalse( store.wasEverAgentKey( first ) );
        assertEquals( first, store.agent( a.id() ).orElseThrow().apiKeyId() );
    }

    @Test
    void agentEverBoundToFindsTheCurrentAndARotatedAwayKey() throws SQLException {
        final int first = newKey();
        final int second = newKey();
        final Agent a = store.registerAgent( "codex", "", "", first, List.of() );
        assertEquals( "codex", store.agentEverBoundTo( first ).orElseThrow().name(), "bound now" );
        store.rebindAgentKey( a.id(), second );
        assertEquals( "codex", store.agentEverBoundTo( first ).orElseThrow().name(), "rotated away" );
        assertEquals( "codex", store.agentEverBoundTo( second ).orElseThrow().name() );
        store.claims().retireAgent( a.id(), Instant.parse( "2026-11-16T10:00:00Z" ),
            Actor.human( "admin", java.util.Set.of(), true ) );
        assertEquals( "codex", store.agentEverBoundTo( second ).orElseThrow().name(), "a retired agent's key" );
        assertTrue( store.agentEverBoundTo( newKey() ).isEmpty(), "a key never bound to an agent" );
    }
}
