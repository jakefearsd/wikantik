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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every claim ending is all-or-nothing: a failing event insert leaves the item, the claim, the agent and the log
 * exactly as they were. Statement counts are per store call on the faulty source (the fixtures use a clean one).
 */
@RequiresPostgres
class JdbcWorkClaimsRollbackTest {

    private static final Duration HOUR = Duration.ofHours( 1 );

    private WorkClaimFixtures fx;
    private FaultInjectingDataSource faulty;
    private JdbcWorkStore faultyStore;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final var ds = PostgresTestDb.createDataSource();
        fx = new WorkClaimFixtures( ds );
        faulty = new FaultInjectingDataSource( ds );
        faultyStore = new JdbcWorkStore( faulty );
    }

    private long eventCount() throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event" );
    }

    @Test
    void claimRollsBackWhenEventInsertFails() throws SQLException {
        final Item item = fx.ready( "x" );
        final long events = eventCount();
        // statements: 1 share-lock the agent row, 2 share-lock the member row, 3 the claim UPDATE, 4 read the item
        // back, 5 insert the claimed event (fails)
        faulty.failOn( 5, new RuntimeException( "boom: claimed insert" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.claims().claim( item, fx.actorA, HOUR ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( item, fx.reload( item ) );
        assertEquals( events, eventCount() );
    }

    @Test
    void renewalRollsBackWhenEventInsertFails() throws SQLException {
        final Item held = fx.store.claims().claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final long events = eventCount();
        // statements as for a claim: 1 agent lock, 2 member lock, 3 UPDATE, 4 read back, 5 insert claim_renewed (fails)
        faulty.failOn( 5, new RuntimeException( "boom: renewed insert" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.claims().claim( held, fx.actorA, Duration.ofDays( 1 ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( held, fx.reload( held ) );
        assertEquals( events, eventCount() );
    }

    @Test
    void releaseRollsBackWhenEventInsertFails() throws SQLException {
        final Item held = fx.store.claims().claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final long events = eventCount();
        // statements: 1 lock-and-update CTE, 2 insert the released event (fails)
        faulty.failOn( 2, new RuntimeException( "boom: released insert" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.claims().release( held, fx.actorA, "n" ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( held, fx.reload( held ) );
        assertEquals( events, eventCount() );
    }

    @Test
    void sweepRollsBackWhenEventInsertFails() throws SQLException {
        final Item one = fx.store.claims().claim( fx.ready( "one" ), fx.actorA, HOUR ).orElseThrow();
        final Item two = fx.store.claims().claim( fx.ready( "two" ), fx.actorB, HOUR ).orElseThrow();
        fx.expireNow( one );
        fx.expireNow( two );
        final Item oneBefore = fx.reload( one );
        final Item twoBefore = fx.reload( two );
        final long events = eventCount();
        // statements: 1 the sweep CTE (both rows), 2 first claim_expired insert, 3 second insert (fails), so the
        // first event is rolled back with the rows
        faulty.failOn( 3, new RuntimeException( "boom: expired insert" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.claims().sweepExpiredClaims( 10 ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( oneBefore, fx.reload( one ) );
        assertEquals( twoBefore, fx.reload( two ) );
        assertEquals( events, eventCount() );
    }

    @Test
    void retireRollsBackWhenEventInsertFails() throws SQLException {
        final Item held = fx.store.claims().claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final long events = eventCount();
        // statements: 1 retire the agent, 2 release CTE, 3 insert the released event (fails)
        faulty.failOn( 3, new RuntimeException( "boom: retire release insert" ) );
        assertThrows( RuntimeException.class,
            () -> faultyStore.claims().retireAgent( fx.a.id(), Instant.now(), WorkClaimFixtures.LENA ) );
        assertEquals( 1, faulty.rollbacks() );
        assertNull( fx.store.agent( fx.a.id() ).orElseThrow().retiredAt() );
        assertEquals( held, fx.reload( held ) );
        assertEquals( events, eventCount() );
    }

    @Test
    void removeMemberRollsBackWhenReleaseInsertFails() throws SQLException {
        final Item held = fx.store.claims().claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final long events = eventCount();
        // statements: 1 delete the member, 2 member event, 3 release CTE, 4 released event insert (fails)
        faulty.failOn( 4, new RuntimeException( "boom: access-loss release insert" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.removeMember( fx.plat.id(),
            String.valueOf( fx.a.id() ), "agent", WorkClaimFixtures.LENA ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( fx.store.members( fx.plat.id() ).stream()
            .anyMatch( m -> m.principal().equals( String.valueOf( fx.a.id() ) ) ) );
        assertEquals( held, fx.reload( held ) );
        assertEquals( events, eventCount() );
    }

    @Test
    void demotionRollsBackWhenReleaseInsertFails() throws SQLException {
        final Item held = fx.store.claims().claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final long events = eventCount();
        // statements: 1 lock the member row, 2 upsert the role, 3 member event, 4 release CTE, 5 released event (fails)
        faulty.failOn( 5, new RuntimeException( "boom: demotion release insert" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.putMember( fx.plat.id(),
            String.valueOf( fx.a.id() ), "agent", WorkRole.VIEWER, WorkClaimFixtures.LENA ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( WorkRole.MEMBER, fx.store.members( fx.plat.id() ).stream()
            .filter( m -> m.principal().equals( String.valueOf( fx.a.id() ) ) ).findFirst().orElseThrow().role() );
        assertEquals( held, fx.reload( held ) );
        assertEquals( events, eventCount() );
    }
}
