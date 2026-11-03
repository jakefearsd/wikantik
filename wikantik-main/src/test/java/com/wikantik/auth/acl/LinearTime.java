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
package com.wikantik.auth.acl;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The one linear-time guard for scanners under test: whether {@code run} costs about {@code FACTOR}x more on
 * {@code FACTOR}x the input (linear) rather than {@code FACTOR}^2 x (quadratic). It compares ratios, never absolute
 * milliseconds, so a loaded machine cannot fail it: a warm-up first, the two sizes timed alternately (a slow stretch slows
 * both), the best of {@code ROUNDS} kept per size, and the bound is {@code FACTOR^1.5}, the geometric midpoint between the
 * two. Only a generous overall cap ({@link #HANG_GUARD}) is absolute, and it is there to stop a hang, not to measure.
 */
final class LinearTime {

    static final int FACTOR = 4;
    static final int ROUNDS = 5;
    static final Duration HANG_GUARD = Duration.ofSeconds( 120 );
    /** A cost under this is timer noise; the ratio is taken against at least this much. */
    private static final double FLOOR_MS = 0.5;

    private LinearTime() {
    }

    /** @return {@code cost(FACTOR * small) / cost(small)}, each the best of {@link #ROUNDS} interleaved runs of {@code run} */
    static double ratio( final IntFunction< String > make, final int small, final Consumer< String > run ) {
        final String a = make.apply( small );
        final String b = make.apply( small * FACTOR );
        run.accept( a );   // warm up: class loading and JIT are not the cost
        double bestA = Double.MAX_VALUE;
        double bestB = Double.MAX_VALUE;
        for ( int i = 0; i < ROUNDS; i++ ) {
            bestA = Math.min( bestA, millis( run, a ) );
            bestB = Math.min( bestB, millis( run, b ) );
        }
        return bestB / Math.max( bestA, FLOOR_MS );
    }

    static double bound() {
        return Math.pow( FACTOR, 1.5 );
    }

    /** Fails when {@code run} costs more than linear in the number of units {@code make} repeats. */
    static void assertLinear( final String what, final IntFunction< String > make, final int small, final Consumer< String > run ) {
        final double ratio = assertTimeoutPreemptively( HANG_GUARD, () -> ratio( make, small, run ) );
        org.junit.jupiter.api.Assertions.assertTrue( ratio < bound(),
                what + ": " + FACTOR + "x the input cost " + ratio + "x the time (linear is about " + FACTOR + ", bound " + bound() + ")" );
    }

    private static double millis( final Consumer< String > run, final String text ) {
        final long start = System.nanoTime();
        run.accept( text );
        return ( System.nanoTime() - start ) / 1e6;
    }
}
