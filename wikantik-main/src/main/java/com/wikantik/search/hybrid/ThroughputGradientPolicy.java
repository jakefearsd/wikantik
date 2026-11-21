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

import java.util.concurrent.TimeUnit;

/**
 * Learns the query-embedding concurrency limit from the embedder's own response times.
 *
 * <p>An embedder (a GPU running Ollama, say) gets more done with a few calls in parallel, up to its own
 * parallelism, and past that extra calls only wait in its queue: throughput goes flat while response time
 * keeps rising. By Little's law throughput is {@code N / R}: the calls in flight over the response time. Each
 * measured call reports the concurrency it experienced (a time-average, not a whole number); the policy files
 * it under the nearest level {@code c} and keeps, per level, a smoothed mean of both the concurrency and the
 * response time, so a level's throughput is exactly {@code N(c) / R(c)}. It settles on the level past which
 * one more parallel call stops buying at least {@code minGain} more throughput — the knee — whatever the
 * hardware. Gains between neighbouring levels are normalised per whole call of concurrency, because the
 * levels' true mean concurrencies are rarely exactly one apart.</p>
 *
 * <p>Rules, applied after every measured call:</p>
 * <ul>
 *   <li>A timeout halves the limit (never below {@code min}).</li>
 *   <li>If the step to the current level buys less than half of {@code minGain}, step down. This needs no
 *       shedding: holding calls the embedder cannot use only makes them wait.</li>
 *   <li>Only while calls are being shed (demand above the limit) does the limit go up: when the next level is
 *       known to pay, or when this level is measured, pays, and the next has not been measured. An idle period never inflates
 *       the limit, so the next burst is not let straight into a queue.</li>
 *   <li>Between half of {@code minGain} and {@code minGain} the limit holds (hysteresis against noise).</li>
 *   <li>While shedding, if the level below has no fresh data the step to this one cannot be judged, so the
 *       limit probes one level down. Data older than {@link #STALE_AFTER_MS} is forgotten, so the policy keeps
 *       re-checking its neighbours and follows the embedder when its capacity changes (a bulk re-embed sharing
 *       the GPU, a hardware or {@code OLLAMA_NUM_PARALLEL} change).</li>
 * </ul>
 *
 * <p>Not thread-safe: {@link EmbedConcurrencyLimiter} serialises calls.</p>
 */
final class ThroughputGradientPolicy implements ConcurrencyLimitPolicy {

    /** Samples a level needs before it can be judged. */
    static final int MIN_SAMPLES = 10;
    /** Response times are averaged over about this many recent samples per level. */
    static final int SMOOTHING_WINDOW = 20;
    /** A level not seen for this long is measured again before it is trusted. */
    static final long STALE_AFTER_MS = 30_000L;
    /** Once a level has data, a sample above this multiple of its average counts as only this multiple. */
    static final double OUTLIER_FACTOR = 4.0;
    /** Neighbouring levels whose mean concurrencies are closer than this cannot be compared. */
    static final double MIN_SEPARATION = 0.25;

    private static final long STALE_AFTER_NANOS = TimeUnit.MILLISECONDS.toNanos( STALE_AFTER_MS );

    private enum Verdict { PAYS, MARGINAL, DOES_NOT_PAY, UNKNOWN }

    /** Smoothed response time and concurrency of one concurrency level. */
    private static final class Level {
        double meanNanos;
        double meanConcurrency;
        int samples;
        long lastNanos;

        void add( final long rttNanos, final double concurrency, final long now ) {
            if( samples > 0 && now - lastNanos > STALE_AFTER_NANOS ) {
                samples = 0;
            }
            double rtt = rttNanos;
            if( samples >= MIN_SAMPLES ) {
                rtt = Math.min( rtt, OUTLIER_FACTOR * meanNanos );
            }
            samples++;
            final int window = Math.min( samples, SMOOTHING_WINDOW );
            meanNanos = samples == 1 ? rtt : meanNanos + ( rtt - meanNanos ) / window;
            meanConcurrency = samples == 1 ? concurrency : meanConcurrency + ( concurrency - meanConcurrency ) / window;
            lastNanos = now;
        }

        boolean trusted( final long now ) {
            return samples >= MIN_SAMPLES && now - lastNanos <= STALE_AFTER_NANOS;
        }

        /** Little's law: calls completed per nanosecond at this level. */
        double throughput() {
            return meanConcurrency / meanNanos;
        }
    }

    private final int initial;
    private final int min;
    private final int max;
    private final double minGain;
    private final Level[] levels;

    ThroughputGradientPolicy( final int initial, final int min, final int max, final double minGain ) {
        if( min < 1 || min > initial || initial > max ) {
            throw new IllegalArgumentException(
                    "need 1 <= min <= initial <= max, got min=" + min + " initial=" + initial + " max=" + max );
        }
        if( !( minGain > 0.0 && minGain <= 1.0 ) ) {
            throw new IllegalArgumentException( "minGain must be in (0, 1], got " + minGain );
        }
        this.initial = initial;
        this.min = min;
        this.max = max;
        this.minGain = minGain;
        this.levels = new Level[ max + 2 ];
        for( int i = 0; i < levels.length; i++ ) {
            levels[ i ] = new Level();
        }
    }

    @Override
    public int initialLimit() {
        return initial;
    }

    @Override
    public String mode() {
        return "adaptive";
    }

    @Override
    public int onSample( final int limit, final double concurrency, final long rttNanos,
                         final boolean timedOut, final boolean saturated, final long now ) {
        final long level = Math.round( concurrency );
        if( level >= 1 && level < levels.length && rttNanos > 0 ) {
            levels[ (int) level ].add( rttNanos, concurrency, now );
        }
        final int current = Math.clamp( limit, min, max );
        if( timedOut ) {
            return Math.max( min, current / 2 );
        }
        final Verdict here = current > min ? verdict( current, now ) : Verdict.PAYS;
        if( here == Verdict.DOES_NOT_PAY ) {
            return current - 1;
        }
        if( !saturated ) {
            return current;
        }
        if( current < max ) {
            final Verdict up = verdict( current + 1, now );
            final boolean explore = here == Verdict.PAYS && up == Verdict.UNKNOWN && levels[ current ].trusted( now );
            if( up == Verdict.PAYS || explore ) {
                return current + 1;
            }
        }
        if( here == Verdict.UNKNOWN && levels[ current ].trusted( now ) && !levels[ current - 1 ].trusted( now ) ) {
            return current - 1;
        }
        return current;
    }

    /** Whether going from {@code c - 1} to {@code c} calls in flight buys enough throughput. */
    private Verdict verdict( final int c, final long now ) {
        if( c <= 1 ) {
            return Verdict.PAYS;
        }
        final Level at = levels[ c ];
        final Level below = levels[ c - 1 ];
        if( !at.trusted( now ) || !below.trusted( now ) ) {
            return Verdict.UNKNOWN;
        }
        final double apart = at.meanConcurrency - below.meanConcurrency;
        if( apart < MIN_SEPARATION ) {
            return Verdict.UNKNOWN;
        }
        final double gain = Math.pow( at.throughput() / below.throughput(), 1.0 / apart );   // per whole call
        if( gain >= 1.0 + minGain ) {
            return Verdict.PAYS;
        }
        return gain < 1.0 + minGain / 2 ? Verdict.DOES_NOT_PAY : Verdict.MARGINAL;
    }
}
