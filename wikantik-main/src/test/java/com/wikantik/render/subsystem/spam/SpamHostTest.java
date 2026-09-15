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
package com.wikantik.render.subsystem.spam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the ICAST_INTEGER_MULTIPLY_CAST_TO_LONG SpotBugs finding
 * in {@link SpamHost#SpamHost(String, SpamChange, int)}: the ban-duration
 * arithmetic must not overflow {@code int} before it is widened to {@code long}.
 */
class SpamHostTest {

    /**
     * A ban duration (in minutes) large enough that {@code banTimeMinutes * 60}
     * overflows a 32-bit int (> Integer.MAX_VALUE / 60 ≈ 35,791,394) even
     * though the resulting millisecond value fits comfortably in a long.
     */
    private static final int OVERFLOWING_BAN_MINUTES = 40_000_000;

    @Test
    void releaseTimeComputedWithoutIntOverflow() {
        final long before = System.currentTimeMillis();
        final SpamHost host = new SpamHost( "127.0.0.1", null, OVERFLOWING_BAN_MINUTES );
        final long after = System.currentTimeMillis();

        final long expectedMin = before + OVERFLOWING_BAN_MINUTES * 60_000L;
        final long expectedMax = after + OVERFLOWING_BAN_MINUTES * 60_000L;

        assertTrue( host.releaseTime() >= expectedMin && host.releaseTime() <= expectedMax,
            "releaseTime " + host.releaseTime() + " should be ~" + expectedMin
                + " (now + " + OVERFLOWING_BAN_MINUTES + " minutes) but overflowed int arithmetic would diverge wildly" );

        // A ban that far in the future must be well after "now", not wrapped into the past.
        assertTrue( host.releaseTime() > host.addedTime(),
            "releaseTime must be after addedTime for a positive ban duration" );
    }
}
