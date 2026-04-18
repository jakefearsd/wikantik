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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakerTest {

    private QueryEmbedderConfig config;
    private FakeClock clock;
    private Breaker breaker;
    private List< CircuitState > opens;
    private List< CircuitState > closes;

    @BeforeEach
    void setUp() {
        // small window, fast cooldown, for deterministic tests
        config = new QueryEmbedderConfig( 1000, 60, 100, 10, 5, 0.5, 1000 );
        clock = new FakeClock( 0 );
        breaker = new Breaker( config, clock );
        opens = new ArrayList<>();
        closes = new ArrayList<>();
    }

    private void pass( final boolean success ) {
        assertEquals( Breaker.Admittance.PASS, breaker.beforeCall() );
        breaker.afterCall( success, false, opens::add, closes::add );
    }

    @Test
    void startsClosed() {
        assertEquals( CircuitState.CLOSED, breaker.currentState() );
    }

    @Test
    void successesDoNotTrip() {
        for( int i = 0; i < 20; i++ ) {
            pass( true );
        }
        assertEquals( CircuitState.CLOSED, breaker.currentState() );
        assertTrue( opens.isEmpty() );
    }

    @Test
    void failuresBelowMinCallsDoNotTrip() {
        // 4 failures, minCalls=5 — even at 100% rate the breaker stays closed
        for( int i = 0; i < 4; i++ ) {
            pass( false );
        }
        assertEquals( CircuitState.CLOSED, breaker.currentState() );
    }

    @Test
    void opensWhenThresholdExceeded() {
        // minCalls=5, rate=0.5 — 2 successes + 3 failures trips on the 5th sample
        pass( true );
        pass( true );
        assertEquals( CircuitState.CLOSED, breaker.currentState() );
        pass( false );
        pass( false );
        assertEquals( CircuitState.CLOSED, breaker.currentState() );
        pass( false ); // 3/5 = 0.6 > 0.5, trips here
        assertEquals( CircuitState.OPEN, breaker.currentState() );
        assertEquals( 1, opens.size() );
        assertEquals( CircuitState.CLOSED, opens.get( 0 ) );
    }

    @Test
    void openRejectsUntilCooldown() {
        tripOpen();
        // immediately after open, still rejecting
        assertEquals( Breaker.Admittance.REJECT, breaker.beforeCall() );
        // just under cooldown
        clock.advanceMillis( config.breakerCooldownMs() - 1 );
        assertEquals( Breaker.Admittance.REJECT, breaker.beforeCall() );
    }

    @Test
    void halfOpenAdmitsOneProbeThenRejects() {
        tripOpen();
        clock.advanceMillis( config.breakerCooldownMs() );
        assertEquals( Breaker.Admittance.PROBE, breaker.beforeCall() );
        // second caller arrives before the probe finishes
        assertEquals( Breaker.Admittance.REJECT, breaker.beforeCall() );
        assertEquals( CircuitState.HALF_OPEN, breaker.currentState() );
    }

    @Test
    void halfOpenProbeSuccessClosesBreaker() {
        tripOpen();
        clock.advanceMillis( config.breakerCooldownMs() );
        final Breaker.Admittance a = breaker.beforeCall();
        breaker.afterCall( true, a == Breaker.Admittance.PROBE, opens::add, closes::add );
        assertEquals( CircuitState.CLOSED, breaker.currentState() );
        assertEquals( List.of( CircuitState.HALF_OPEN ), closes );
        // window reset — subsequent failures start fresh
        assertEquals( 0, breaker.windowSamples() );
    }

    @Test
    void halfOpenProbeFailureReOpensAndResetsCooldown() {
        tripOpen();
        clock.advanceMillis( config.breakerCooldownMs() );
        final Breaker.Admittance a = breaker.beforeCall();
        final long openedBefore = clock.millis();
        breaker.afterCall( false, a == Breaker.Admittance.PROBE, opens::add, closes::add );
        assertEquals( CircuitState.OPEN, breaker.currentState() );
        // cooldown should be based on the moment we re-opened (openedBefore), so a fresh probe isn't
        // allowed until another cooldownMs has elapsed.
        clock.advanceMillis( config.breakerCooldownMs() - 1 );
        assertEquals( Breaker.Admittance.REJECT, breaker.beforeCall() );
        clock.advanceMillis( 1 );
        assertEquals( Breaker.Admittance.PROBE, breaker.beforeCall() );
        // two OPEN transitions observed: initial trip + re-open after probe failure
        assertEquals( 2, opens.size() );
        // sanity: verify we really waited
        assertTrue( clock.millis() >= openedBefore + config.breakerCooldownMs() );
    }

    @Test
    void rollingWindowTripsAtExactlyThreshold() {
        // Bigger window to keep the breaker closed across 10 samples below threshold.
        final QueryEmbedderConfig bigCfg = new QueryEmbedderConfig(
                1000, 60, 100, 20, 10, 0.5, 1000 );
        final Breaker b = new Breaker( bigCfg, clock );
        // 4 failures, 5 successes, then a 10th failure = 5/10 = 0.5 — exactly at threshold, trips.
        for( int i = 0; i < 4; i++ ) {
            assertEquals( Breaker.Admittance.PASS, b.beforeCall() );
            b.afterCall( false, false, opens::add, closes::add );
        }
        for( int i = 0; i < 5; i++ ) {
            assertEquals( Breaker.Admittance.PASS, b.beforeCall() );
            b.afterCall( true, false, opens::add, closes::add );
        }
        assertEquals( CircuitState.CLOSED, b.currentState() );
        assertEquals( Breaker.Admittance.PASS, b.beforeCall() );
        b.afterCall( false, false, opens::add, closes::add );
        assertEquals( CircuitState.OPEN, b.currentState() );
    }

    @Test
    void windowWrapsAroundAndMaintainsAccurateCount() {
        // Configure a 5-slot window so we can watch eviction deterministically
        final QueryEmbedderConfig smallCfg = new QueryEmbedderConfig( 1000, 60, 100, 5, 3, 0.9, 1000 );
        final Breaker b = new Breaker( smallCfg, clock );
        // 5 successes → window full, 0 failures
        for( int i = 0; i < 5; i++ ) {
            b.beforeCall();
            b.afterCall( true, false, opens::add, closes::add );
        }
        assertEquals( 5, b.windowSamples() );
        assertEquals( 0, b.windowFailures() );
        // one failure evicts the oldest (success) — count goes to 1
        b.beforeCall();
        b.afterCall( false, false, opens::add, closes::add );
        assertEquals( 5, b.windowSamples() );
        assertEquals( 1, b.windowFailures() );
        assertEquals( CircuitState.CLOSED, b.currentState() ); // 1/5 < 0.9
    }

    @Test
    void afterCallOutsideClosedIsIgnored() {
        // Defensive: if someone reports a CLOSED-style afterCall while state is OPEN,
        // nothing blows up.
        tripOpen();
        breaker.afterCall( false, false, opens::add, closes::add );
        assertEquals( CircuitState.OPEN, breaker.currentState() );
    }

    /* ---- helpers ---- */

    private void tripOpen() {
        // 5 failures, minCalls=5, rate=1.0 — trips on the 5th sample
        for( int i = 0; i < 5; i++ ) {
            pass( false );
        }
        assertEquals( CircuitState.OPEN, breaker.currentState() );
        assertFalse( opens.isEmpty() );
    }
}
