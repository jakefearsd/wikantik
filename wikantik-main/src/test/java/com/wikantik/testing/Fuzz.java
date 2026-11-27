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
package com.wikantik.testing;

import java.security.SecureRandom;

/**
 * How much a seeded generator test runs. On a fixed seed a generator test checks the same inputs on every build, so
 * after its first green run it can only catch a change to the code it covers; the full count buys nothing more on the
 * next commit. The gate therefore runs a twentieth of the cases on the seeds the test was written with (quick, and
 * still reproducible), and {@code -Dtests.fuzz=deep} (set by {@code bin/run-tests.sh --all}) runs every case on seeds
 * derived from a fresh run seed, so each deep run tries inputs no run has tried before. A deep failure is reproduced
 * with the {@code -Dtests.fuzz.seed} that {@link #reproduce()} names; put it in the assertion message.
 */
public final class Fuzz {

    /** {@code deep}: every case, fresh seeds. Anything else: the gate's share on the written seeds. */
    public static final String MODE = "tests.fuzz";
    /** The run seed for a deep run (decimal or {@code 0x} hex); picked at random when absent. */
    public static final String SEED = "tests.fuzz.seed";

    static final int GATE_DIVISOR = 20;

    private static final long PICKED = new SecureRandom().nextLong();

    private Fuzz() {
    }

    public static boolean deep() {
        return "deep".equals( System.getProperty( MODE ) );
    }

    /** The number of cases to run: {@code deepCount} in a deep run, a twentieth of it (at least one) otherwise. */
    public static int cases( final int deepCount ) {
        return deep() ? deepCount : Math.max( 1, deepCount / GATE_DIVISOR );
    }

    /** The seed to use for a stream the test wrote as {@code written}: unchanged in the gate, mixed with the run seed in a deep run. */
    public static long seed( final long written ) {
        return deep() ? written ^ runSeed() : written;
    }

    /** Text for an assertion message: how to run this exact case set again. */
    public static String reproduce() {
        return deep()
            ? "rerun with -Dtests.fuzz=deep -Dtests.fuzz.seed=0x" + Long.toHexString( runSeed() )
            : "gate share on the written seeds; -Dtests.fuzz=deep runs every case on fresh seeds";
    }

    private static long runSeed() {
        final String given = System.getProperty( SEED );
        return given == null || given.isBlank() ? PICKED : Long.decode( given.trim() );
    }
}
