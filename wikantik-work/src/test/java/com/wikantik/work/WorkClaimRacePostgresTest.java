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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races between what a service read and what the store finds under lock (rulings P25, P26): the store, not the
 * service's earlier read, decides whether a claim was taken or renewed, whether a write ended a claim and whose, and
 * whether an agent's write is fenced off by another agent's claim. Each race is made deterministic: a hook runs the
 * competing change just before the store call, after the service's own read, or a held row lock makes the writer wait.
 */
@RequiresPostgres
class WorkClaimRacePostgresTest {

    private static final Duration HOUR = Duration.ofHours( 1 );

    /** A change to run once, just before the store's next item write or claim. */
    @FunctionalInterface
    private interface Hook {
        void run() throws SQLException;
    }

    private WorkClaimFixtures fx;
    private final List< ClaimOutcome > heard = new ArrayList<>();
    private Hook hook;
    private WorkService svc;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        fx.store.putMember( fx.plat.id(), "lena", "user", WorkRole.LEAD, WorkClaimFixtures.LENA );
        svc = new WorkService( racing( fx.store ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, AgentKeys.NONE, Clock.systemUTC(), WorkSettings.DEFAULTS, new WorkListener() {
            @Override public void claim( final ClaimOutcome o ) { heard.add( o ); }
        } );
    }

    /** The store behind a proxy that runs {@link #hook} (once) before {@code writeItem} and {@code claimWithOutcome}. */
    private WorkStore racing( final JdbcWorkStore real ) {
        final WorkClaimStore realClaims = real.claims();
        final WorkClaimStore claims = ( WorkClaimStore ) Proxy.newProxyInstance( getClass().getClassLoader(),
            new Class< ? >[] { WorkClaimStore.class }, ( p, m, args ) -> {
                if ( "claimWithOutcome".equals( m.getName() ) ) {
                    runHook();
                }
                return call( m, realClaims, args );
            } );
        return ( WorkStore ) Proxy.newProxyInstance( getClass().getClassLoader(), new Class< ? >[] { WorkStore.class },
            ( p, m, args ) -> {
                if ( "claims".equals( m.getName() ) ) {
                    return claims;
                }
                if ( "writeItem".equals( m.getName() ) ) {
                    runHook();
                }
                return call( m, real, args );
            } );
    }

    private void runHook() throws SQLException {
        final Hook h = hook;
        hook = null;
        if ( h != null ) {
            h.run();
        }
    }

    private static Object call( final Method m, final Object target, final Object[] args ) throws Throwable {
        try {
            return m.invoke( target, args );
        } catch ( final InvocationTargetException e ) {
            throw e.getCause();
        }
    }

    private long logged( final String type ) throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event WHERE type = '" + type + "'" );
    }

    private List< String > claimEvents( final Item item ) throws SQLException {
        return fx.store.itemEvents( item.id() ).stream().map( WorkEvent::type )
            .filter( t -> t.startsWith( "claim" ) || t.equals( WorkEvent.RELEASED ) ).toList();
    }

    // ---- P26: the store reports what happened

    @Test
    void aRenewalThatLostToTheSweepIsAFreshTake() throws SQLException {
        final Item held = svc.claims().claim( fx.actorA, fx.ready( "x" ).key() ).view().item();
        fx.expireNow( held );
        heard.clear();
        // The service reads the item still held by A, then the sweep ends the lapsed claim before the claim statement.
        hook = () -> assertEquals( 1, fx.store.claims().sweepExpiredClaims( 10 ).size() );

        final WorkClaimsService.ClaimResult r = svc.claims().claim( fx.actorA, held.key() );

        assertFalse( r.renewed(), "the statement took the item afresh" );
        assertEquals( List.of( ClaimOutcome.CLAIMED ), heard );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.CLAIM_EXPIRED, WorkEvent.CLAIMED ), claimEvents( held ) );
    }

    @Test
    void aClaimThatLandsAfterTheServiceReadIsReportedWhenAPersonsMoveEndsIt() throws SQLException {
        final Item free = fx.ready( "x" );
        final Item read = fx.reload( free );
        // The claim lands between the service's read and the store's lock; the person's move then ends it.
        hook = () -> fx.store.claims().claim( read, fx.actorA, HOUR ).orElseThrow();

        svc.updateItem( WorkClaimFixtures.LENA, free.key(), new ItemPatch( read.version() + 1,
            Map.of( "status", "refining" ), null ) );

        assertNull( fx.reload( free ).claimedByAgent() );
        assertEquals( List.of( ClaimOutcome.RELEASED ), heard, "the listener heard the release the log records" );
        assertEquals( logged( WorkEvent.RELEASED ), heard.stream().filter( o -> o == ClaimOutcome.RELEASED ).count() );
    }

    @Test
    void aHandoffThatEndsAClaimTheServiceNeverSawIsReported() throws SQLException {
        final Item review = fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" );
        // A claim on a review item does not move the version, so the person's copy stays current.
        hook = () -> fx.store.claims().claim( review, fx.actorA, HOUR ).orElseThrow();

        svc.claims().handoff( WorkClaimFixtures.LENA, review.key(), new WorkClaimsService.Handoff( review.version(),
            WorkStatus.READY, null, "reviewer", "to review" ) );

        assertEquals( List.of( ClaimOutcome.RELEASED ), heard );
        assertEquals( 1, logged( WorkEvent.RELEASED ) );
    }

    // ---- P25: a non-holder agent's write is fenced under lock

    @Test
    void anAgentsWriteIsRefusedWhenAnotherAgentClaimedAfterItsRead() throws SQLException {
        final Item review = fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" );
        hook = () -> fx.store.claims().claim( review, fx.actorA, HOUR ).orElseThrow();
        final long events = logged( WorkEvent.HANDOFF ) + logged( WorkEvent.STATUS ) + logged( WorkEvent.RELEASED );

        final WorkDeniedException denied = assertThrows( WorkDeniedException.class, () -> svc.claims().handoff(
            fx.actorB, review.key(), new WorkClaimsService.Handoff( review.version(), WorkStatus.READY, null,
                "reviewer", "mine now" ) ) );

        assertEquals( "This item is claimed by another agent.", denied.reason() );
        final Item now = fx.reload( review );
        assertEquals( fx.a.id(), now.claimedByAgent(), "A keeps its claim" );
        assertEquals( "implementer", now.queue() );
        assertEquals( events, logged( WorkEvent.HANDOFF ) + logged( WorkEvent.STATUS ) + logged( WorkEvent.RELEASED ) );
        assertTrue( heard.isEmpty() );
    }

    @Test
    void theStoreFencesANonHolderAgentButNeverAPersonOrTheHolder() throws SQLException {
        final Item stale = fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" );
        fx.store.claims().claim( stale, fx.actorA, HOUR ).orElseThrow();
        final Item next = withTitle( stale, "B's edit" );

        assertThrows( ClaimFenceException.class, () -> fx.store.updateItem( stale, next, fx.actorB, null ) );
        assertEquals( "r", fx.reload( stale ).title(), "nothing written" );

        assertEquals( "A's edit", fx.store.updateItem( stale, withTitle( stale, "A's edit" ), fx.actorA, null )
            .title(), "the holder writes" );
        final Item current = fx.reload( stale );
        assertEquals( "Lena's edit", fx.store.updateItem( current, withTitle( current, "Lena's edit" ),
            WorkClaimFixtures.LENA, null ).title(), "a person is never locked out" );
    }

    @Test
    void anAgentWriteThatWaitedOnAClaimInFlightIsRefused() throws Exception {
        final Item review = fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" );
        try ( Connection holder = fx.ds.getConnection() ) {
            holder.setAutoCommit( false );
            final long holderPid;
            try ( Statement s = holder.createStatement(); var rs = s.executeQuery( "SELECT pg_backend_pid()" ) ) {
                rs.next();
                holderPid = rs.getLong( 1 );
            }
            try ( Statement s = holder.createStatement() ) {
                s.execute( "SELECT 1 FROM work_item WHERE id = " + review.id() + " FOR UPDATE" );
            }
            final ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                final var waiting = pool.submit( () -> fx.store.handoff( review, withQueue( review, "reviewer" ),
                    fx.actorB, "mine now" ) );
                WorkClaimFixtures.awaitBlockedBy( fx.ds, holderPid );
                try ( Statement s = holder.createStatement() ) {
                    // A's claim on a review item: the version does not move.
                    s.execute( "UPDATE work_item SET claimed_by_agent = " + fx.a.id() + ", claimed_at = now(), "
                        + "claim_expires_at = now() + INTERVAL '1 hour' WHERE id = " + review.id() );
                }
                holder.commit();
                final var e = assertThrows( java.util.concurrent.ExecutionException.class,
                    () -> waiting.get( 30, TimeUnit.SECONDS ) );
                assertInstanceOf( ClaimFenceException.class, e.getCause() );
            } finally {
                pool.shutdownNow();
            }
        }
        final Item now = fx.reload( review );
        assertEquals( fx.a.id(), now.claimedByAgent() );
        assertEquals( "implementer", now.queue() );
        assertTrue( fx.events( review, WorkEvent.HANDOFF ).isEmpty() );
    }

    private static Item withTitle( final Item i, final String title ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), title,
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
            i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId() );
    }

    private static Item withQueue( final Item i, final String queue ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), null, i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), queue,
            i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId() );
    }
}
