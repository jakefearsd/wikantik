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
package com.wikantik.collab.testing;

import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.adapter.CollabPageEventListener;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.testing.ConcurrencyHarness.Report;
import com.wikantik.event.WikiPageEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7: real threads, for locking only (E38, E40, and E33 at the registry). Tagged {@code stress}; the default build runs the short form
 * ({@value ConcurrencyHarness#DEFAULT_RUNS} runs), run with {@code -Dcollab.stress.runs=20000} for a soak.
 */
@Tag( "stress" )
class ConcurrencyHarnessTest {

    private static final Duration RUN_TIMEOUT = Duration.ofSeconds( 30 );

    /** A fresh stub over fresh doubles; the save filter rewrites so publishes append server updates under contention. */
    static final Supplier< SimServer > STUB = () -> {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( "Stress", "seed text  \nsecond line\n", Map.of() );
        writer.rewriteOnSave( SimEnv.rewrite( "strip-trailing-spaces" ) );
        return new StubCollabAuthority( "Stress", new InMemoryCollabStore(), writer,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ), 1 << 20, 4 );
    };

    /**
     * The real session over the same doubles, with a snapshot every 16 updates so cadence snapshots and the truncation
     * behind them run under contention.
     */
    static final Supplier< SimServer > SESSION = () -> {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( "Stress", "seed text  \nsecond line\n", Map.of() );
        writer.rewriteOnSave( SimEnv.rewrite( "strip-trailing-spaces" ) );
        return CollabSessionSimServer.create( "Stress", new InMemoryCollabStore(), writer,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ), 1 << 20, 4, 16 );
    };

    /**
     * The real session opened by a registry, its outside saves delivered as the engine delivers them: both save events
     * of every save, through {@link CollabPageEventListener} (Phase 2 Task 5).
     */
    static final Supplier< SimServer > LISTENER = () -> {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( "Stress", "seed text  \nsecond line\n", Map.of() );
        writer.rewriteOnSave( SimEnv.rewrite( "strip-trailing-spaces" ) );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionRegistry registry = new CollabSessionRegistry( store, writer, CollabSessionSimServer.ALLOW_ALL,
            CollabSessionSimServer.IDENTITY, new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
            CollabSessionSimServer.config( 4, 16 ), 1 << 20 );
        final CollabSessionRegistry.Joined opener = (CollabSessionRegistry.Joined) registry.join( "Stress",
            JoinRequest.of( "opener", "opener", "Opener" ) );
        opener.session().leave( opener.response().participantId(), "opener" );
        return new ListenerDriven( CollabSessionSimServer.wrap( opener.session(), store, writer ),
            new CollabPageEventListener( registry, writer ) );
    };

    static Stream< Arguments > servers() {
        return Stream.of( Arguments.of( "stub", STUB ), Arguments.of( "session", SESSION ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void e38_parallelPushesToOneVersionAcceptExactlyOne( final String name, final Supplier< SimServer > server ) {
        final int runs = ConcurrencyHarness.runs();
        final Report r = new ConcurrencyHarness( 16, runs, RUN_TIMEOUT, server ).pushRace( 38 );
        assertEquals( runs, r.runs() );
        assertTrue( r.rounds() > 0 );
        assertEquals( r.rounds(), r.accepted(), "exactly one push per contested version: " + r );
        assertEquals( r.rounds() * 15L, r.stale(), "every other push is a stale-base 409: " + r );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void e40_mixedTrafficOnRealThreadsHoldsTheInvariants( final String name, final Supplier< SimServer > server ) {
        final int runs = ConcurrencyHarness.runs();
        final Report r = new ConcurrencyHarness( 12, runs, RUN_TIMEOUT, server ).run( 40 );
        assertEquals( runs, r.runs() );
        // the mix really happened (the harness itself asserted contiguity, ring == log, waiters and invariants)
        assertTrue( r.accepted() > 0, r.toString() );
        assertTrue( r.stale() > 0, r.toString() );
        assertTrue( r.parked() > 0, r.toString() );
        assertTrue( r.wakeups() == r.parked(), r.toString() );
        assertTrue( r.publishes() > 0, r.toString() );
        assertTrue( r.leaves() > 0, r.toString() );
        assertTrue( r.joins() > r.runs() * 12L, r.toString() );
        // the session merges outside saves on the workers' threads (the harness also asserted no publish was left in flight)
        assertEquals( "session".equals( name ), r.externalSaves() > 0, r.toString() );
    }

    @Test
    void listenerDrivenMergesKeepTheHarnessInvariants() {
        final int runs = ConcurrencyHarness.runs();
        final Report r = new ConcurrencyHarness( 12, runs, RUN_TIMEOUT, LISTENER ).run( 41 );
        assertEquals( runs, r.runs() );
        assertTrue( r.externalSaves() > 0, r.toString() );
        assertTrue( r.publishes() > 0, r.toString() );
        assertTrue( r.accepted() > 0, r.toString() );
    }

    @Test
    void e33_idleEvictionRacingJoinsLosesNoDraftAndNoJoinLandsInADeadSession() {
        final int runs = Math.max( 1, ConcurrencyHarness.runs() / 5 );
        int evictions = 0;
        for( int run = 0; run < runs; run++ ) {
            final RegistryRace.Report r = new RegistryRace( 4, 25, RUN_TIMEOUT ).run();
            assertEquals( 4 * 25 * 2, r.joins(), r.toString() );
            assertEquals( 4 * 25, r.accepted(), r.toString() );
            evictions += r.evictionsSeen();
        }
        assertTrue( evictions > 0, "idle eviction really raced the joins" );
    }

    @Test
    void aDeadlockIsReportedWithEveryThreadsStack() {
        final CountDownLatch never = new CountDownLatch( 1 );
        final Supplier< SimServer > stuck = () -> new Forwarding( STUB.get() ) {
            @Override
            public PushResult push( final String participantId, final String authorKey, final int version,
                                    final List< Update > updates ) {
                try {
                    never.await();
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException( "interrupted while stuck", e );
                }
                return super.push( participantId, authorKey, version, updates );
            }
        };
        final AssertionError e = assertThrows( AssertionError.class,
            () -> new ConcurrencyHarness( 4, 1, Duration.ofMillis( 500 ), stuck ).pushRace( 1 ) );
        assertTrue( e.getMessage().contains( "timed out" ), e.getMessage() );
        assertTrue( e.getMessage().contains( "collab-stress-" ), e.getMessage() );
        assertTrue( e.getMessage().contains( "CountDownLatch" ), e.getMessage() );
    }

    /** Answers 200 to a stale push without applying it: the version accounting must notice. */
    private static final Supplier< SimServer > LIAR = () -> new Forwarding( STUB.get() ) {
        @Override
        public PushResult push( final String participantId, final String authorKey, final int version,
                                final List< Update > updates ) {
            final PushResult r = super.push( participantId, authorKey, version, updates );
            return "stale-base".equals( r.reason() ) ? new PushResult( 200, version + updates.size(), null ) : r;
        }
    };

    @Test
    void aServerThatAcceptsTwoPushesForOneVersionIsCaughtByTheRace() {
        final AssertionError e = assertThrows( AssertionError.class,
            () -> new ConcurrencyHarness( 8, 3, RUN_TIMEOUT, LIAR ).pushRace( 2 ) );
        assertTrue( e.getMessage().contains( "accepted" ), e.getMessage() );
    }

    @Test
    void aServerThatAcceptsTwoPushesForOneVersionIsCaughtByTheVersionAccounting() {
        final AssertionError e = assertThrows( AssertionError.class,
            () -> new ConcurrencyHarness( 8, 5, RUN_TIMEOUT, LIAR ).run( 3 ) );
        assertTrue( e.getMessage().contains( "version" ), e.getMessage() );
    }

    @Test
    void aLoggedClientUpdateThatNoPushClaimedIsCaught() {
        // one accepted push is answered as stale: its update is in the log, but no push holds the acknowledgement
        final AtomicBoolean lied = new AtomicBoolean();
        final Supplier< SimServer > hider = () -> new Forwarding( STUB.get() ) {
            @Override
            public PushResult push( final String participantId, final String authorKey, final int version,
                                    final List< Update > updates ) {
                final PushResult r = super.push( participantId, authorKey, version, updates );
                if( r.status() == 200 && !updates.isEmpty() && lied.compareAndSet( false, true ) ) {
                    return new PushResult( 409, r.version(), "stale-base" );
                }
                return r;
            }
        };
        final AssertionError e = assertThrows( AssertionError.class,
            () -> new ConcurrencyHarness( 4, 1, RUN_TIMEOUT, hider ).run( 5 ) );
        assertTrue( e.getMessage().contains( "no push claimed" ), e.getMessage() );
    }

    @Test
    void runsDefaultsAndCanBeOverridden() {
        final String old = System.getProperty( "collab.stress.runs" );
        try {
            System.clearProperty( "collab.stress.runs" );
            assertEquals( ConcurrencyHarness.DEFAULT_RUNS, ConcurrencyHarness.runs() );
            System.setProperty( "collab.stress.runs", "7" );
            assertEquals( 7, ConcurrencyHarness.runs() );
        } finally {
            if( old == null ) {
                System.clearProperty( "collab.stress.runs" );
            } else {
                System.setProperty( "collab.stress.runs", old );
            }
        }
    }

    /** Delegates everything. */
    private static class Forwarding implements SimServer {
        private final SimServer d;

        Forwarding( final SimServer d ) {
            this.d = d;
        }

        @Override
        public Joined join( final String authorKey, final String priorClientId, final String initialText,
                            final Map< String, Object > initialMeta ) {
            return d.join( authorKey, priorClientId, initialText, initialMeta );
        }

        @Override
        public PushResult push( final String participantId, final String authorKey, final int version, final List< Update > updates ) {
            return d.push( participantId, authorKey, version, updates );
        }

        @Override
        public PullResult pull( final String participantId, final String authorKey, final int since ) {
            return d.pull( participantId, authorKey, since );
        }

        @Override
        public PublishResult publish( final String participantId, final String authorKey, final int version, final String note ) {
            return d.publish( participantId, authorKey, version, note );
        }

        @Override
        public Left leave( final String participantId, final String authorKey ) {
            return d.leave( participantId, authorKey );
        }

        @Override
        public void evict( final String participantId ) {
            d.evict( participantId );
        }

        @Override
        public List< Wakeup > drainWakeups() {
            return d.drainWakeups();
        }

        @Override
        public InvariantView view() {
            return d.view();
        }

        @Override
        public void tick() {
            d.tick();
        }

        @Override
        public void truncate() {
            d.truncate();
        }
    }

    /** Delivers each outside save as the engine does: POST_SAVE_BEGIN and POST_SAVE_END through the listener. */
    private static final class ListenerDriven extends Forwarding {
        private final SimServer session;
        private final CollabPageEventListener listener;

        ListenerDriven( final SimServer session, final CollabPageEventListener listener ) {
            super( session );
            this.session = session;
            this.listener = listener;
        }

        @Override
        public void externalSaved( final int version ) {
            final String page = session.view().pageName();
            listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_BEGIN, page ) );
            listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, page ) );
        }

        @Override
        public boolean reconciled() {
            return session.reconciled();
        }

        /**
         * The quiescence settle goes through the listener too, never straight to the session; afterwards the draft's
         * base must be the page's latest text, so a listener that dropped the events cannot pass unnoticed.
         */
        @Override
        public void settle() {
            final String page = session.view().pageName();
            listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, page ) );
            final InvariantView v = session.view();
            final String latest = v.writer().latest( page ).map( p -> CollabText.toLf( p.body() ) ).orElse( "" );
            if( !v.publishPending() && !v.baseBody().equals( latest ) ) {
                throw new AssertionError( "the listener-driven merges missed an outside save: base " + v.baseBody().length()
                    + " chars, page " + latest.length() + " chars" );
            }
        }

        @Override
        public boolean acceptsExternalSaves() {
            return true;
        }

        @Override
        public PublishStart beginPublish( final String participantId, final String authorKey, final int version,
                                          final String note ) {
            return session.beginPublish( participantId, authorKey, version, note );
        }

        @Override
        public PublishResult releasePublish( final Object pending ) {
            return session.releasePublish( pending );
        }

        @Override
        public void abandonPublish( final Object pending, final boolean persist ) {
            session.abandonPublish( pending, persist );
        }
    }
}
