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

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Hand-rolled three-state circuit breaker backing {@link QueryEmbedder}.
 *
 * <p>All state transitions are serialized by {@code this}. The breaker does
 * <em>not</em> know how to call the embedding client; callers ask for an
 * admission ticket via {@link #beforeCall()}, invoke the downstream, and then
 * report the outcome via {@link #afterCall}. Transition notifications are
 * pushed to caller-supplied consumers so metric/log side effects stay out of
 * the breaker.</p>
 *
 * <p>Rolling window strategy: a circular boolean buffer of
 * {@link QueryEmbedderConfig#breakerWindowSize} slots. A CLOSED-state failure
 * pushes a sample into the buffer; {@link QueryEmbedderConfig#breakerMinCalls}
 * observations and a ratio ≥ {@link QueryEmbedderConfig#breakerFailureRate}
 * trip the breaker OPEN.</p>
 *
 * <p>HALF_OPEN admits exactly one probe; subsequent admissions are rejected
 * until the probe resolves and re-sets the state.</p>
 */
final class Breaker {

    enum Admittance {
        /** Normal call (CLOSED state). */
        PASS,
        /** HALF_OPEN probe — this call decides whether to CLOSE or re-OPEN. */
        PROBE,
        /** OPEN (or HALF_OPEN with an in-flight probe) — short-circuit. */
        REJECT
    }

    private final QueryEmbedderConfig config;
    private final Clock clock;

    private CircuitState state = CircuitState.CLOSED;

    /* Circular buffer of recent outcomes in CLOSED state. true = failure. */
    private final boolean[] window;
    private int windowIndex;
    private int windowFilled;
    private int windowFailures;

    private long openedAtEpochMs;
    private boolean probeInFlight;

    Breaker( final QueryEmbedderConfig config, final Clock clock ) {
        this.config = config;
        this.clock = clock;
        this.window = new boolean[ config.breakerWindowSize() ];
    }

    synchronized CircuitState currentState() {
        return state;
    }

    /**
     * Decide whether a caller may proceed. Call exactly once per embed attempt;
     * every PASS or PROBE MUST be matched with {@link #afterCall} so the window
     * and HALF_OPEN flag stay consistent.
     */
    synchronized Admittance beforeCall() {
        if( state == CircuitState.OPEN ) {
            final long now = clock.millis();
            if( now - openedAtEpochMs >= config.breakerCooldownMs() ) {
                // cooldown elapsed — promote to HALF_OPEN and let this caller probe
                state = CircuitState.HALF_OPEN;
                probeInFlight = true;
                return Admittance.PROBE;
            }
            return Admittance.REJECT;
        }
        if( state == CircuitState.HALF_OPEN ) {
            if( probeInFlight ) {
                return Admittance.REJECT;
            }
            probeInFlight = true;
            return Admittance.PROBE;
        }
        return Admittance.PASS; // CLOSED
    }

    /**
     * Report call outcome.
     *
     * @param success       whether the embed succeeded
     * @param wasProbe      true iff the admitted ticket was {@link Admittance#PROBE}
     * @param onOpen        invoked with the previous state when transitioning to OPEN
     * @param onClose       invoked with the previous state when transitioning to CLOSED
     */
    synchronized void afterCall( final boolean success,
                                 final boolean wasProbe,
                                 final Consumer< CircuitState > onOpen,
                                 final Consumer< CircuitState > onClose ) {
        if( wasProbe ) {
            probeInFlight = false;
            if( success ) {
                transitionTo( CircuitState.CLOSED, onOpen, onClose );
            } else {
                transitionTo( CircuitState.OPEN, onOpen, onClose );
            }
            return;
        }
        // CLOSED path: record into window, evaluate for OPEN transition.
        if( state != CircuitState.CLOSED ) {
            // Defensive: if we got here while not CLOSED the caller violated the protocol.
            // Still do nothing — the state machine's integrity is preserved.
            return;
        }
        recordOutcome( success );
        if( shouldTrip() ) {
            transitionTo( CircuitState.OPEN, onOpen, onClose );
        }
    }

    /* ---- package-private helpers for tests ---- */

    synchronized int windowFailures() {
        return windowFailures;
    }

    synchronized int windowSamples() {
        return windowFilled;
    }

    /* ---- private helpers ---- */

    private void recordOutcome( final boolean success ) {
        final boolean isFailure = !success;
        if( windowFilled == window.length ) {
            // window full — evict oldest
            if( window[ windowIndex ] ) {
                windowFailures--;
            }
        } else {
            windowFilled++;
        }
        window[ windowIndex ] = isFailure;
        if( isFailure ) {
            windowFailures++;
        }
        windowIndex = ( windowIndex + 1 ) % window.length;
    }

    private boolean shouldTrip() {
        if( windowFilled < config.breakerMinCalls() ) {
            return false;
        }
        final double rate = (double) windowFailures / (double) windowFilled;
        return rate >= config.breakerFailureRate();
    }

    private void transitionTo( final CircuitState next,
                               final Consumer< CircuitState > onOpen,
                               final Consumer< CircuitState > onClose ) {
        final CircuitState previous = state;
        if( previous == next && next != CircuitState.OPEN ) {
            // Re-entering OPEN (HALF_OPEN probe failure) is still meaningful — reset cooldown.
            return;
        }
        state = next;
        switch( next ) {
            case OPEN -> {
                openedAtEpochMs = clock.millis();
                resetWindow();
                onOpen.accept( previous );
            }
            case CLOSED -> {
                resetWindow();
                onClose.accept( previous );
            }
            case HALF_OPEN -> {
                // beforeCall is the only path that sets HALF_OPEN; nothing to do here.
            }
        }
    }

    private void resetWindow() {
        for( int i = 0; i < window.length; i++ ) {
            window[ i ] = false;
        }
        windowIndex = 0;
        windowFilled = 0;
        windowFailures = 0;
    }
}
