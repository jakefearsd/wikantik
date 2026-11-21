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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision table of {@link ThroughputGradientPolicy}. Each test fills the per-concurrency response-time
 * buckets with synthetic samples, then asks for one decision. Throughput at concurrency {@code c} is
 * {@code c / R(c)}; the step to {@code c} "pays" when it buys at least {@code minGain} more throughput
 * than {@code c - 1}, and "does not pay" below half of that.
 */
class ThroughputGradientPolicyTest {

    private static final double MIN_GAIN = 0.10;

    private long nowMs = 1_000_000L;

    private static long ms( final double v ) {
        return (long) ( v * 1_000_000L );
    }

    private ThroughputGradientPolicy policy( final int initial, final int min, final int max ) {
        return new ThroughputGradientPolicy( initial, min, max, MIN_GAIN );
    }

    /** Feeds {@code n} unsaturated samples at {@code concurrency}; decisions are ignored (setup only). */
    private void fill( final ThroughputGradientPolicy p, final int concurrency, final double rttMs, final int n ) {
        for( int i = 0; i < n; i++ ) {
            nowMs += 10;
            p.onSample( concurrency, concurrency, ms( rttMs ), false, false, TimeUnit.MILLISECONDS.toNanos( nowMs ) );
        }
    }

    private int decide( final ThroughputGradientPolicy p, final int limit, final int concurrency,
                        final double rttMs, final boolean saturated ) {
        nowMs += 10;
        return p.onSample( limit, concurrency, ms( rttMs ), false, saturated, TimeUnit.MILLISECONDS.toNanos( nowMs ) );
    }

    @Test
    void startsAtTheInitialLimit() {
        assertEquals( 4, policy( 4, 1, 32 ).initialLimit() );
    }

    @Test
    void neverClimbsWhileNothingIsShed() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 20 );   // the step to 4 pays (4/3 the throughput) ...
        assertEquals( 4, decide( p, 4, 4, 60, false ), "... but with no shedding there is no demand to climb for" );
    }

    @Test
    void climbsWhileOneMoreCallStillBuysThroughput() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 20 );   // no queueing yet: 4 in flight give 4/3 the throughput of 3
        assertEquals( 5, decide( p, 4, 4, 60, true ) );
    }

    @Test
    void stepsDownWhenTheTopStepBuysNothing() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );   // 50 per second
        fill( p, 4, 80, 20 );   // still 50 per second: the 4th call only queues
        assertEquals( 3, decide( p, 4, 4, 80, false ), "stepping down needs no shedding" );
    }

    @Test
    void holdsInsideTheHysteresisBand() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 100, 20 );
        final double r4 = 4.0 / ( 0.03 * 1.075 );   // the step to 4 buys 7.5%: between minGain/2 and minGain
        fill( p, 4, r4, 20 );
        assertEquals( 4, decide( p, 4, 4, r4, true ) );
    }

    @Test
    void probesOneStepDownWhenTheLevelBelowHasNoFreshData() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 4, 60, 20 );
        assertEquals( 3, decide( p, 4, 4, 60, true ), "without R(3) the step to 4 cannot be judged" );
    }

    @Test
    void waitsForDataAtANewLevelBeforeMovingAgain() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 20 );
        fill( p, 5, 60, 3 );    // just climbed to 5: too few samples to judge it
        assertEquals( 5, decide( p, 5, 5, 60, true ) );
    }

    @Test
    void goesBackUpWhenTheLevelAboveIsKnownToPay() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 20 );   // probed down from 4 to 3: R(2) is unknown, but 4 is known to pay over 3
        assertEquals( 4, decide( p, 3, 3, 60, true ), "no cascade down to the floor" );
    }

    @Test
    void holdsBelowALevelKnownNotToPayUntilThatDataGoesStale() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 20 );
        fill( p, 5, 75, 20 );   // the step to 5 buys nothing
        assertEquals( 4, decide( p, 4, 4, 60, true ) );

        nowMs += ThroughputGradientPolicy.STALE_AFTER_MS + 1;   // R(5) goes stale while 3 and 4 stay fresh
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 20 );
        assertEquals( 5, decide( p, 4, 4, 60, true ), "stale data is re-probed: the embedder may have changed" );
    }

    @Test
    void aTimeoutHalvesTheLimitWithinTheFloor() {
        final ThroughputGradientPolicy p = policy( 4, 2, 32 );
        assertEquals( 4, p.onSample( 8, 8, ms( 2_000 ), true, true, 1L ) );
        assertEquals( 2, p.onSample( 3, 3, ms( 2_000 ), true, true, 2L ) );
    }

    @Test
    void staysWithinTheConfiguredBounds() {
        final ThroughputGradientPolicy capped = policy( 4, 1, 4 );
        fill( capped, 3, 60, 20 );
        fill( capped, 4, 60, 20 );
        assertEquals( 4, decide( capped, 4, 4, 60, true ), "never above max" );

        final ThroughputGradientPolicy floored = policy( 2, 2, 32 );
        fill( floored, 1, 60, 20 );
        fill( floored, 2, 120, 20 );   // the step to 2 buys nothing ...
        assertEquals( 2, decide( floored, 2, 2, 120, false ), "... but never below min" );
    }

    @Test
    void oneSlowOutlierCannotDragTheLimitDown() {
        final ThroughputGradientPolicy p = policy( 4, 1, 32 );
        fill( p, 3, 60, 20 );
        fill( p, 4, 60, 19 );
        // A cold model load: one call takes 10 s. Unclamped it would make R(4) look like ~560 ms.
        final int after = decide( p, 4, 4, 10_000, true );
        assertTrue( after >= 4, "outlier clamped, got " + after );
    }

    @Test
    void ignoresSamplesOutsideTheTrackedRange() {
        final ThroughputGradientPolicy p = policy( 4, 1, 8 );
        assertEquals( 4, p.onSample( 4, 0, ms( 60 ), false, false, 1L ) );
        assertEquals( 4, p.onSample( 4, 99, ms( 60 ), false, false, 2L ) );
    }
}
