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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Admission control for query embeddings: at most {@link #limit()} calls reach the embedder at once, and a call
 * over the limit is <em>shed</em> ({@link #tryAcquire()} returns {@code null}) so its search falls back to BM25
 * at once instead of queueing. The limit itself comes from a {@link ConcurrencyLimitPolicy}: fixed, or learned
 * from how fast the embedder answers ({@link ThroughputGradientPolicy}).
 *
 * <p>Each measured call is reported with the concurrency it <em>experienced</em>: the time-average of the calls
 * in flight over its lifetime, from a running integral of the in-flight count. The count at the moment a call
 * started would mislead: under load a call that starts with three in flight soon has four, and Little's law
 * (throughput = concurrency / response time) needs the average.</p>
 *
 * <p>Acquire and release share this object's monitor; both are a few arithmetic operations, so contention is
 * negligible at query rates.</p>
 */
public final class EmbedConcurrencyLimiter {

    private static final Logger LOG = LogManager.getLogger( EmbedConcurrencyLimiter.class );

    /** A shed within this window means demand is above the limit, which is the only reason to raise it. */
    static final long SATURATION_WINDOW_NANOS = TimeUnit.SECONDS.toNanos( 1 );

    /** How a call that held a slot ended. */
    public enum Outcome {
        /** The embedder answered: the response time is a sample. */
        COMPLETED,
        /**
         * The call ran out its timeout: a sample, and a sign of overload. Calls timing out in the same stall
         * (started before the last timeout was acted on) count once.
         */
        TIMED_OUT,
        /** Nothing was measured (breaker rejection, transport error, empty answer): the slot comes back, nothing else. */
        DROPPED
    }

    /**
     * A held slot.
     *
     * @param concurrency calls in flight when it was granted, itself included
     * @param startNanos  when it was granted
     * @param areaAtStart the in-flight integral (call-nanoseconds) at that moment
     */
    public record Permit( int concurrency, long startNanos, double areaAtStart ) { }

    private final ConcurrencyLimitPolicy policy;
    private final LongSupplier nanoTime;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile int limit;
    private long lastShedNanos;
    private boolean everShed;
    /** When a timeout last reached the policy; timeouts of calls started before then belong to the same stall. */
    private long lastTimeoutCutNanos = Long.MIN_VALUE;
    /** Integral of the in-flight count over time, in call-nanoseconds, up to {@link #lastChangeNanos}. */
    private double area;
    private long lastChangeNanos;

    EmbedConcurrencyLimiter( final ConcurrencyLimitPolicy policy, final LongSupplier nanoTime ) {
        this.policy = Objects.requireNonNull( policy, "policy" );
        this.nanoTime = Objects.requireNonNull( nanoTime, "nanoTime" );
        this.limit = policy.initialLimit();
        this.lastChangeNanos = nanoTime.getAsLong();
    }

    /** A slot, or {@code null} when the limit is full and the caller must fall back at once. */
    public synchronized Permit tryAcquire() {
        final long now = nanoTime.getAsLong();
        final int current = inFlight.get();
        if( current >= limit ) {
            lastShedNanos = now;
            everShed = true;
            return null;
        }
        advanceArea( now );
        inFlight.incrementAndGet();
        return new Permit( current + 1, now, area );
    }

    /** Returns the slot and, for a measured outcome, lets the policy move the limit. A {@code null} permit is ignored. */
    public void release( final Permit permit, final Outcome outcome ) {
        if( permit == null ) {
            return;
        }
        synchronized( this ) {
            final long now = nanoTime.getAsLong();
            advanceArea( now );
            inFlight.decrementAndGet();
            if( outcome == Outcome.DROPPED ) {
                return;
            }
            if( outcome == Outcome.TIMED_OUT ) {
                if( permit.startNanos() < lastTimeoutCutNanos ) {
                    return;   // same stall as a timeout already acted on: one congestion event, one cut
                }
                lastTimeoutCutNanos = now;
            }
            final long rtt = now - permit.startNanos();
            final double experienced = rtt > 0
                    ? Math.max( 1.0, ( area - permit.areaAtStart() ) / rtt )
                    : permit.concurrency();
            final boolean saturated = everShed && now - lastShedNanos <= SATURATION_WINDOW_NANOS;
            final int before = limit;
            final int after = policy.onSample( before, experienced, rtt, outcome == Outcome.TIMED_OUT, saturated, now );
            if( after != before ) {
                limit = after;
                LOG.debug( "Query embedding concurrency limit {} -> {} (sample: {} in flight on average, {} ms, {})",
                        before, after, String.format( java.util.Locale.ROOT, "%.2f", experienced ), TimeUnit.NANOSECONDS.toMillis( rtt ), outcome );
            }
        }
    }

    private void advanceArea( final long now ) {
        area += (double) inFlight.get() * ( now - lastChangeNanos );
        lastChangeNanos = now;
    }

    /** The limit now in force. */
    public int limit() {
        return limit;
    }

    /** Calls holding a slot right now. */
    public int inFlight() {
        return inFlight.get();
    }

    /** {@code fixed} or {@code adaptive}. */
    public String mode() {
        return policy.mode();
    }
}
