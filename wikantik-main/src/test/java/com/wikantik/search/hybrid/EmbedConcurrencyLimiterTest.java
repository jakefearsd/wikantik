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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedConcurrencyLimiterTest {

    private final AtomicLong now = new AtomicLong( 1_000_000_000L );

    /** Records every sample and moves the limit to whatever {@link #next} says. */
    private static final class RecordingPolicy implements ConcurrencyLimitPolicy {
        record Seen( int limit, double concurrency, long rttNanos, boolean timedOut, boolean saturated ) { }
        final List< Seen > seen = new ArrayList<>();
        int next = -1;

        @Override public int initialLimit() { return 2; }

        @Override public String mode() { return "adaptive"; }

        @Override public int onSample( final int limit, final double concurrency, final long rttNanos,
                                       final boolean timedOut, final boolean saturated, final long nowNanos ) {
            seen.add( new Seen( limit, concurrency, rttNanos, timedOut, saturated ) );
            return next < 0 ? limit : next;
        }
    }

    @Test
    void aFixedLimiterShedsOverTheCapAndNeverMoves() {
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( ConcurrencyLimitPolicy.fixed( 2 ), now::get );
        final EmbedConcurrencyLimiter.Permit a = l.tryAcquire();
        final EmbedConcurrencyLimiter.Permit b = l.tryAcquire();
        assertNotNull( a );
        assertNotNull( b );
        assertNull( l.tryAcquire(), "third call is over the cap" );
        assertEquals( 2, l.inFlight() );

        l.release( a, EmbedConcurrencyLimiter.Outcome.TIMED_OUT );
        l.release( b, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertEquals( 0, l.inFlight() );
        assertEquals( 2, l.limit(), "a fixed cap ignores timeouts and samples" );
        assertEquals( "fixed", l.mode() );
    }

    @Test
    void permitsCarryTheirConcurrencyAndCompletedCallsReportTheirResponseTime() {
        final RecordingPolicy p = new RecordingPolicy();
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( p, now::get );
        final EmbedConcurrencyLimiter.Permit first = l.tryAcquire();
        final EmbedConcurrencyLimiter.Permit second = l.tryAcquire();
        assertEquals( 1, first.concurrency() );
        assertEquals( 2, second.concurrency() );

        now.addAndGet( TimeUnit.MILLISECONDS.toNanos( 75 ) );
        l.release( second, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertEquals( 1, p.seen.size() );
        final RecordingPolicy.Seen s = p.seen.get( 0 );
        assertEquals( 2.0, s.concurrency(), 1e-9 );
        assertEquals( TimeUnit.MILLISECONDS.toNanos( 75 ), s.rttNanos() );
        assertFalse( s.timedOut() );
        assertFalse( s.saturated(), "nothing was shed" );
        assertEquals( "adaptive", l.mode() );
    }

    @Test
    void aCallIsFiledUnderTheConcurrencyItExperiencedNotTheCountAtItsStart() {
        final RecordingPolicy p = new RecordingPolicy();
        p.next = 8;
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( p, now::get );
        l.release( l.tryAcquire(), EmbedConcurrencyLimiter.Outcome.DROPPED );   // nothing learned; limit stays 2
        final EmbedConcurrencyLimiter.Permit lone = l.tryAcquire();             // starts alone ...
        assertEquals( 1, lone.concurrency() );
        now.addAndGet( 10_000_000L );
        final EmbedConcurrencyLimiter.Permit other = l.tryAcquire();            // ... after 10 ms a second call joins
        now.addAndGet( 30_000_000L );                                           // ... and both run 30 ms more
        l.release( lone, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        // (10 ms x 1 + 30 ms x 2) / 40 ms = 1.75 in flight on average
        assertEquals( 1.75, p.seen.get( 0 ).concurrency(), 1e-9 );
        l.release( other, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertEquals( 2.0, p.seen.get( 1 ).concurrency(), 1e-9, "two in flight for its whole life" );
    }

    @Test
    void droppedCallsTeachThePolicyNothing() {
        final RecordingPolicy p = new RecordingPolicy();
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( p, now::get );
        l.release( l.tryAcquire(), EmbedConcurrencyLimiter.Outcome.DROPPED );
        assertTrue( p.seen.isEmpty(), "a breaker rejection or an error says nothing about the embedder's speed" );
        assertEquals( 0, l.inFlight(), "but the slot still comes back" );
    }

    @Test
    void timeoutsAreReportedAsSuch() {
        final RecordingPolicy p = new RecordingPolicy();
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( p, now::get );
        l.release( l.tryAcquire(), EmbedConcurrencyLimiter.Outcome.TIMED_OUT );
        assertTrue( p.seen.get( 0 ).timedOut() );
    }

    @Test
    void callsTimingOutInTheSameStallHalveTheLimitOnce() {
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter(
                new ThroughputGradientPolicy( 8, 1, 32, 0.10 ), now::get );
        final EmbedConcurrencyLimiter.Permit a = l.tryAcquire();
        final EmbedConcurrencyLimiter.Permit b = l.tryAcquire();
        final EmbedConcurrencyLimiter.Permit c = l.tryAcquire();
        now.addAndGet( TimeUnit.SECONDS.toNanos( 2 ) );
        l.release( a, EmbedConcurrencyLimiter.Outcome.TIMED_OUT );
        l.release( b, EmbedConcurrencyLimiter.Outcome.TIMED_OUT );
        l.release( c, EmbedConcurrencyLimiter.Outcome.TIMED_OUT );
        assertEquals( 4, l.limit(), "one stall is one congestion event: 8 -> 4, not 8 -> 1" );

        final EmbedConcurrencyLimiter.Permit later = l.tryAcquire();   // started after the cut
        now.addAndGet( TimeUnit.SECONDS.toNanos( 2 ) );
        l.release( later, EmbedConcurrencyLimiter.Outcome.TIMED_OUT );
        assertEquals( 2, l.limit(), "a call admitted after the cut that still times out is a new event" );
    }

    @Test
    void saturationMeansACallWasShedWithinTheLastSecond() {
        final RecordingPolicy p = new RecordingPolicy();
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( p, now::get );
        final EmbedConcurrencyLimiter.Permit a = l.tryAcquire();
        final EmbedConcurrencyLimiter.Permit b = l.tryAcquire();
        assertNull( l.tryAcquire() );                       // shed: demand is above the limit

        now.addAndGet( TimeUnit.MILLISECONDS.toNanos( 500 ) );
        l.release( a, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertTrue( p.seen.get( 0 ).saturated() );

        now.addAndGet( TimeUnit.MILLISECONDS.toNanos( 1_500 ) );
        l.release( b, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertFalse( p.seen.get( 1 ).saturated(), "the shed is too old to count" );
    }

    @Test
    void aLoweredLimitShedsUntilTheExcessDrains() {
        final RecordingPolicy p = new RecordingPolicy();
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( p, now::get );
        final EmbedConcurrencyLimiter.Permit a = l.tryAcquire();
        final EmbedConcurrencyLimiter.Permit b = l.tryAcquire();
        p.next = 1;
        l.release( a, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertEquals( 1, l.limit() );
        assertNull( l.tryAcquire(), "one call still in flight fills a limit of one" );
        l.release( b, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertNotNull( l.tryAcquire() );
    }

    @Test
    void aNullPermitIsIgnored() {
        final EmbedConcurrencyLimiter l = new EmbedConcurrencyLimiter( ConcurrencyLimitPolicy.fixed( 1 ), now::get );
        l.release( null, EmbedConcurrencyLimiter.Outcome.COMPLETED );
        assertEquals( 0, l.inFlight() );
    }
}
