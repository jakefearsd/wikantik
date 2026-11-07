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

import com.wikantik.collab.testing.ConcurrencyHarness.Report;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7: real threads, for locking only (E38, E40). Tagged {@code stress}; the default build runs the short form
 * ({@value ConcurrencyHarness#DEFAULT_RUNS} runs), {@code -Dcollab.stress.runs=20000} is the soak.
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

    @Test
    void e38_parallelPushesToOneVersionAcceptExactlyOne() {
        final int runs = ConcurrencyHarness.runs();
        final Report r = new ConcurrencyHarness( 16, runs, RUN_TIMEOUT, STUB ).pushRace( 38 );
        assertEquals( runs, r.runs() );
        assertTrue( r.rounds() > 0 );
        assertEquals( r.rounds(), r.accepted(), "exactly one push per contested version: " + r );
        assertEquals( r.rounds() * 15L, r.stale(), "every other push is a stale-base 409: " + r );
    }

    @Test
    void e40_mixedTrafficOnRealThreadsHoldsTheInvariants() {
        final int runs = ConcurrencyHarness.runs();
        final Report r = new ConcurrencyHarness( 12, runs, RUN_TIMEOUT, STUB ).run( 40 );
        assertEquals( runs, r.runs() );
        // the mix really happened (the harness itself asserted contiguity, ring == log, waiters and invariants)
        assertTrue( r.accepted() > 0, r.toString() );
        assertTrue( r.stale() > 0, r.toString() );
        assertTrue( r.parked() > 0, r.toString() );
        assertTrue( r.wakeups() == r.parked(), r.toString() );
        assertTrue( r.publishes() > 0, r.toString() );
        assertTrue( r.leaves() > 0, r.toString() );
        assertTrue( r.joins() > r.runs() * 12L, r.toString() );
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
        public Joined join( final String authorKey ) {
            return d.join( authorKey );
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
        public void leave( final String participantId ) {
            d.leave( participantId );
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
    }
}
