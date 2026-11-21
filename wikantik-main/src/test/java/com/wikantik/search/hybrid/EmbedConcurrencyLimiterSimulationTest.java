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
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the adaptive limiter against a simulated embedder and checks where the limit settles.
 *
 * <p>The simulated embedder is a processor-sharing GPU: with {@code n} calls in flight it completes
 * {@code k / (1 + penalty * (k - 1))} calls' worth of work per service time, {@code k = min(n, parallelism)}.
 * Up to its parallelism more calls buy more throughput (less than linearly when {@code penalty > 0},
 * like a real GPU batching embeddings); past it they only queue. Arrivals are Poisson, call sizes vary
 * ±20%, and the simulated clock drives the limiter, so every run is deterministic.</p>
 *
 * <p>With random arrivals a limit of exactly the parallelism leaves a slot idle between one call finishing and
 * the next arriving, so one extra call of buffering can genuinely buy a few percent of throughput; where that
 * gain lands in the hold band the limit may rest at parallelism + 1.</p>
 */
class EmbedConcurrencyLimiterSimulationTest {

    private static final double SERVICE_S = 0.060;   // one query alone: ~60 ms, as measured on docker2

    private static final class Sim {
        final AtomicLong clock = new AtomicLong();
        final EmbedConcurrencyLimiter limiter;
        final Random rnd = new Random( 42 );
        final List< double[] > jobs = new ArrayList<>();            // [remaining work]
        final List< EmbedConcurrencyLimiter.Permit > permits = new ArrayList<>();
        int parallelism;
        double penalty;
        double arrivalsPerSec;
        double t;
        double nextArrival;
        double[] timeAtLimit;

        Sim( final int parallelism, final double penalty, final double arrivalsPerSec ) {
            limiter = new EmbedConcurrencyLimiter(
                    new ThroughputGradientPolicy( 4, 1, 32, EmbedConcurrencyConfig.DEFAULT_MIN_GAIN ), clock::get );
            this.parallelism = parallelism;
            this.penalty = penalty;
            this.arrivalsPerSec = arrivalsPerSec;
            nextArrival = expo();
        }

        double capacityPerSec() {
            return rate( parallelism ) / SERVICE_S;
        }

        double rate( final int n ) {
            final int k = Math.min( n, parallelism );
            return k == 0 ? 0 : k / ( 1 + penalty * ( k - 1 ) );
        }

        double expo() {
            return -Math.log( 1 - rnd.nextDouble() ) / arrivalsPerSec;
        }

        /** Runs for {@code seconds}; returns the time-weighted mean limit over the last {@code measureLast} seconds. */
        Stats runFor( final double seconds, final double measureLast ) {
            final double end = t + seconds;
            final double measureFrom = end - measureLast;
            timeAtLimit = new double[ 34 ];
            while( t < end ) {
                final int n = jobs.size();
                final double perJob = n == 0 ? 0 : rate( n ) / n;
                int next = -1;
                double tComplete = Double.POSITIVE_INFINITY;
                for( int i = 0; i < n; i++ ) {
                    final double tc = t + jobs.get( i )[ 0 ] / perJob;
                    if( tc < tComplete ) {
                        tComplete = tc;
                        next = i;
                    }
                }
                final double tNext = Math.min( end, Math.min( nextArrival, tComplete ) );
                final double dt = tNext - t;
                for( final double[] j : jobs ) {
                    j[ 0 ] -= dt * perJob;
                }
                if( tNext > measureFrom ) {
                    timeAtLimit[ limiter.limit() ] += tNext - Math.max( t, measureFrom );
                }
                t = tNext;
                clock.set( (long) ( t * 1e9 ) );
                if( tNext == tComplete ) {
                    jobs.remove( next );
                    limiter.release( permits.remove( next ), EmbedConcurrencyLimiter.Outcome.COMPLETED );
                } else if( tNext == nextArrival ) {
                    final EmbedConcurrencyLimiter.Permit p = limiter.tryAcquire();
                    if( p != null ) {
                        jobs.add( new double[]{ SERVICE_S * ( 0.8 + 0.4 * rnd.nextDouble() ) } );
                        permits.add( p );
                    }
                    nextArrival = t + expo();
                }
            }
            return new Stats( timeAtLimit, measureLast );
        }
    }

    private record Stats( double[] timeAtLimit, double total ) {
        double mean() {
            double sum = 0;
            for( int i = 0; i < timeAtLimit.length; i++ ) {
                sum += i * timeAtLimit[ i ];
            }
            return sum / total;
        }

        double fractionAt( final int limit ) {
            return timeAtLimit[ limit ] / total;
        }

        @Override public String toString() {
            final StringBuilder sb = new StringBuilder( String.format( "mean=%.2f [", mean() ) );
            for( int i = 0; i < timeAtLimit.length; i++ ) {
                if( timeAtLimit[ i ] > 0 ) {
                    sb.append( String.format( " %d:%.0f%%", i, 100 * timeAtLimit[ i ] / total ) );
                }
            }
            return sb.append( " ]" ).toString();
        }
    }

    private static Sim overloaded( final int parallelism, final double penalty ) {
        final Sim probe = new Sim( parallelism, penalty, 1 );
        return new Sim( parallelism, penalty, 3 * probe.capacityPerSec() );
    }

    private static void assertSettlesBetween( final Stats s, final int lo, final int hi ) {
        double inRange = 0;
        for( int i = lo; i <= hi; i++ ) {
            inRange += s.fractionAt( i );
        }
        assertTrue( inRange >= 0.9, "expected the limit to settle in [" + lo + ", " + hi + "], got " + s );
    }

    private static void assertSettlesAt( final Stats s, final int expected ) {
        assertTrue( Math.abs( s.mean() - expected ) <= 0.5 && s.fractionAt( expected ) >= 0.75,
                "expected the limit to settle at " + expected + ", got " + s );
    }

    @Test
    void settlesAtTheParallelismOfAPerfectlyParallelEmbedder() {
        assertSettlesBetween( overloaded( 2, 0 ).runFor( 120, 60 ), 2, 3 );
        assertSettlesAt( overloaded( 8, 0 ).runFor( 120, 60 ), 8 );
    }

    @Test
    void settlesAtTheParallelismOfABatchingGpu() {
        // penalty 0.25, parallelism 4: steps to 2, 3, 4 buy 60%, 25%, 14% more throughput; the 5th buys nothing.
        assertSettlesAt( overloaded( 4, 0.25 ).runFor( 120, 60 ), 4 );
    }

    @Test
    void stopsWhereOneMoreCallBuysLessThanTheMinimumGain() {
        // penalty 0.52 is docker2's iGPU (16.7/s alone, 26/s at 4): steps to 3 and 4 buy only 11% and 6%, so the
        // 4th call sits in the hold band (between minGain/2 and minGain) and the 5th buys nothing.
        final Stats s = overloaded( 4, 0.52 ).runFor( 120, 60 );
        assertSettlesBetween( s, 2, 4 );
        assertTrue( s.mean() < 3.6, "the 4th call buys too little to be the resting point, got " + s );
    }

    @Test
    void followsTheEmbedderWhenItsCapacityChanges() {
        final Sim sim = overloaded( 8, 0 );
        assertSettlesAt( sim.runFor( 60, 20 ), 8 );

        sim.parallelism = 2;   // a bulk re-embed takes most of the GPU
        assertSettlesBetween( sim.runFor( 60, 30 ), 2, 3 );

        sim.parallelism = 8;   // the re-embed finishes
        assertSettlesAt( sim.runFor( 120, 40 ), 8 );
    }

    @Test
    void climbsOnlyAsFarAsBurstsDemandUnderLightLoad() {
        // 10% of an 8-wide embedder's capacity: a rare Poisson burst may shed and earn a step up, but the limit
        // must not drift toward the ceiling while there is no sustained demand for it.
        final Sim probe = new Sim( 8, 0, 1 );
        final Sim sim = new Sim( 8, 0, 0.1 * probe.capacityPerSec() );
        final Stats s = sim.runFor( 120, 60 );
        assertTrue( s.mean() <= 6.0, "expected the limit to stay near its start, got " + s );
    }
}
