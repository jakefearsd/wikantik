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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim is one statement, so of N simultaneous claimers exactly one wins, and the expiry sweep is safe from
 * several nodes at once. Every thread has its own connection and starts on one latch; every wait is bounded so a
 * deadlock fails the build instead of hanging it.
 */
@RequiresPostgres
class JdbcWorkClaimsConcurrencyPostgresTest {

    private static final Duration HOUR = Duration.ofHours( 1 );

    private WorkClaimFixtures fx;
    private WorkClaimStore claims;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        claims = fx.store.claims();
    }

    /** Runs the tasks on their own threads released together; fails on any thread exception or a missed deadline. */
    private static < T > List< T > together( final List< Callable< T > > tasks ) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool( tasks.size() );
        final CountDownLatch ready = new CountDownLatch( tasks.size() );
        final CountDownLatch go = new CountDownLatch( 1 );
        final CountDownLatch done = new CountDownLatch( tasks.size() );
        final Queue< T > results = new ConcurrentLinkedQueue<>();
        final Queue< Throwable > failures = new ConcurrentLinkedQueue<>();
        try {
            for ( final Callable< T > task : tasks ) {
                pool.submit( () -> {
                    try {
                        ready.countDown();
                        go.await();
                        results.add( task.call() );
                    } catch ( final Throwable t ) {
                        failures.add( t );
                    } finally {
                        done.countDown();
                    }
                } );
            }
            assertTrue( ready.await( 30, TimeUnit.SECONDS ) );
            go.countDown();
            assertTrue( done.await( 30, TimeUnit.SECONDS ), "threads did not finish: a deadlock" );
        } finally {
            pool.shutdownNow();
        }
        assertTrue( failures.isEmpty(), "thread failures: " + failures );
        return new ArrayList<>( results );
    }

    @Test
    void concurrentClaimsYieldExactlyOneWinner() throws Exception {
        final List< Actor > agents = new ArrayList<>( List.of( fx.actorA, fx.actorB ) );
        for ( int i = 0; i < 6; i++ ) {
            final Agent extra = fx.agent( "extra-" + i, List.of( "implementer" ) );
            fx.member( extra );
            agents.add( Actor.agent( extra.id(), extra.name() ) );
        }
        for ( int round = 0; round < 3; round++ ) {
            final Item item = fx.ready( "contested " + round );
            final List< Callable< Optional< Item > > > tasks = new ArrayList<>();
            for ( final Actor agent : agents ) {
                tasks.add( () -> claims.claim( item, agent, HOUR ) );
            }
            final List< Optional< Item > > results = together( tasks );
            final List< Item > won = results.stream().flatMap( Optional::stream ).toList();
            assertEquals( 1, won.size(), "winners in round " + round );
            final Item after = fx.reload( item );
            assertEquals( won.get( 0 ).claimedByAgent(), after.claimedByAgent() );
            assertEquals( WorkStatus.IN_PROGRESS, after.status() );
            assertEquals( item.version() + 1, after.version() );
            final List< WorkEvent > claimed = fx.events( item, WorkEvent.CLAIMED );
            assertEquals( 1, claimed.size() );
            assertEquals( after.claimedByAgent(), claimed.get( 0 ).agentId() );
            assertTrue( fx.events( item, WorkEvent.CLAIM_RENEWED ).isEmpty() );
        }
    }

    @Test
    void concurrentSweepsWriteOneEventPerClaim() throws Exception {
        final List< Item > items = new ArrayList<>();
        for ( int i = 0; i < 20; i++ ) {
            final Item held = claims.claim( fx.ready( "t" + i ), fx.actorA, HOUR ).orElseThrow();
            fx.expireNow( held );
            items.add( held );
        }
        final List< Callable< List< Item > > > tasks = new ArrayList<>();
        for ( int t = 0; t < 2; t++ ) {
            tasks.add( () -> {
                final List< Item > mine = new ArrayList<>();
                List< Item > pass;
                do {
                    pass = claims.sweepExpiredClaims( 3 );
                    mine.addAll( pass );
                } while ( !pass.isEmpty() );
                return mine;
            } );
        }
        final List< List< Item > > swept = together( tasks );
        assertEquals( 20, swept.stream().mapToInt( List::size ).sum() );
        assertEquals( 20, swept.stream().flatMap( List::stream ).map( Item::id ).distinct().count() );
        assertEquals( 20L, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event "
            + "WHERE type = 'claim_expired' AND agent_id = " + fx.a.id() ) );
        for ( final Item i : items ) {
            assertEquals( 1, fx.events( i, WorkEvent.CLAIM_EXPIRED ).size() );
            assertEquals( WorkStatus.READY, fx.reload( i ).status() );
            assertEquals( i.version() + 1, fx.reload( i ).version() );
        }
    }

    @Test
    void concurrentRenewalsByTheHolderAllSucceedWithoutDuplicateTakes() throws Exception {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final List< Callable< Optional< Item > > > tasks = new ArrayList<>();
        for ( int i = 0; i < 6; i++ ) {
            tasks.add( () -> claims.claim( held, fx.actorA, HOUR ) );
        }
        assertTrue( together( tasks ).stream().allMatch( Optional::isPresent ) );
        assertEquals( 1, fx.events( held, WorkEvent.CLAIMED ).size() );
        assertEquals( 6, fx.events( held, WorkEvent.CLAIM_RENEWED ).size() );
        assertEquals( held.version(), fx.reload( held ).version() );
    }

    /**
     * Review Focus 1, the renewal that loses to a sweep: the holder's claim waits on the row lock a sweep holds, and
     * when the sweep commits it re-checks its WHERE against the swept row, which is now a fresh take.
     */
    @Test
    void claimThatWaitedOnAnEndingBecomesAFreshTake() throws Exception {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        try ( Connection holder = fx.ds.getConnection() ) {
            holder.setAutoCommit( false );
            final long holderPid = backendPid( holder );
            try ( Statement s = holder.createStatement() ) {
                s.execute( "SELECT 1 FROM work_item WHERE id = " + held.id() + " FOR UPDATE" );
            }
            final ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                final var waiting = pool.submit( () -> claims.claim( held, fx.actorA, HOUR ) );
                WorkClaimFixtures.awaitBlockedBy( fx.ds, holderPid );
                try ( Statement s = holder.createStatement() ) {
                    s.execute( "UPDATE work_item SET claimed_by_agent = NULL, claimed_at = NULL, "
                        + "claim_expires_at = NULL, status = 'ready', version = version + 1 WHERE id = " + held.id() );
                }
                holder.commit();
                final Item got = waiting.get( 30, TimeUnit.SECONDS ).orElseThrow();
                assertNotEquals( held.claimedAt(), got.claimedAt() );
                assertEquals( WorkStatus.IN_PROGRESS, got.status() );
                assertEquals( held.version() + 2, got.version() );
                assertEquals( 2, fx.events( held, WorkEvent.CLAIMED ).size() );
                assertTrue( fx.events( held, WorkEvent.CLAIM_RENEWED ).isEmpty() );
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static long backendPid( final Connection c ) throws SQLException {
        try ( Statement st = c.createStatement(); var rs = st.executeQuery( "SELECT pg_backend_pid()" ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }
}
