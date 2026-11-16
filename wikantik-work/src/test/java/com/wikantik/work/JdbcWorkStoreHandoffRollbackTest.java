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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A hand-off is all-or-nothing: a failing event insert leaves status, queue, version and claim as they were. */
@RequiresPostgres
class JdbcWorkStoreHandoffRollbackTest {

    private DataSource ds;
    private JdbcWorkStore store;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
    }

    @Test
    void handoffRollsBackWhenReleasedInsertFails() throws SQLException {
        final Actor lena = Actor.human( "lena", Set.of(), false );
        final Project plat = store.createProject( "PLAT", "Platform", "" );
        WorkAgentFixtures.queue( ds, "implementer" );
        WorkAgentFixtures.queue( ds, "reviewer" );
        final long agent = WorkAgentFixtures.agent( ds, "codex" );
        final Item d = JdbcWorkStoreItemsPostgresTest.draft( "x" );
        final Item draft = new Item( 0, 0, null, d.level(), null, null, d.kind(), "x", "b", WorkStatus.IN_PROGRESS,
            3, 0, null, null, null, null, null, null, 0, null, null, null, "implementer", null, null, null, null );
        final Item created = store.createItem( plat, draft, lena );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claimed_by_agent = " + agent
            + ", claimed_at = NOW(), claim_expires_at = NOW() + INTERVAL '1 hour' WHERE id = " + created.id() );
        final Item current = store.itemById( created.id() ).orElseThrow();
        final int events = store.itemEvents( created.id() ).size();
        final Item next = new Item( current.id(), current.projectId(), current.key(), current.level(), null, null,
            current.kind(), current.title(), current.body(), WorkStatus.REVIEW, current.priority(), current.rank(),
            null, null, current.reporter(), null, null, null, current.version(), null, null, null, "reviewer",
            current.claimedByAgent(), current.claimedAt(), current.claimExpiresAt(), null );
        // statements: 1 update item, 2 insert handoff event, 3 insert released event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 3, new RuntimeException( "boom: released insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        assertThrows( RuntimeException.class, () -> faultyStore.handoff( current, next, lena, "ready" ) );
        assertEquals( 1, faulty.rollbacks() );
        final Item after = store.itemById( created.id() ).orElseThrow();
        assertEquals( WorkStatus.IN_PROGRESS, after.status() );
        assertEquals( "implementer", after.queue() );
        assertEquals( current.version(), after.version() );
        assertEquals( agent, after.claimedByAgent() );
        assertEquals( current.claimExpiresAt(), after.claimExpiresAt() );
        assertEquals( events, store.itemEvents( created.id() ).size() );
    }
}
