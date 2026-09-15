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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.pages.PageLock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the ICAST_INTEGER_MULTIPLY_CAST_TO_LONG SpotBugs finding
 * in {@link DefaultPageLockService#lockPage(Page, String)}: the lock-duration
 * arithmetic must not overflow {@code int} before it is widened to {@code long}.
 */
class DefaultPageLockServiceTest {

    /**
     * A lock duration (in minutes) large enough that {@code expiryTime * 60}
     * overflows a 32-bit int (> Integer.MAX_VALUE / 60 ≈ 35,791,394) even
     * though the resulting millisecond value fits comfortably in a long.
     */
    private static final int OVERFLOWING_EXPIRY_MINUTES = 40_000_000;

    @Test
    void lockPageComputesExpiryWithoutIntOverflow() {
        final Engine engine = mock( Engine.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage" );

        final DefaultPageLockService service =
            new DefaultPageLockService( engine, OVERFLOWING_EXPIRY_MINUTES, this );

        final long beforeMillis = System.currentTimeMillis();
        final PageLock lock = service.lockPage( page, "alice" );
        try {
            assertTrue( lock != null, "lockPage should have acquired the lock" );

            final long expectedExpiryMillis = beforeMillis + OVERFLOWING_EXPIRY_MINUTES * 60_000L;
            final long actualExpiryMillis = lock.getExpiryTime().getTime();

            // With the int-overflow bug, expiryTime * 60 * 1000L wraps to a
            // small/negative int before widening, producing an expiry that is
            // NOT roughly "now + OVERFLOWING_EXPIRY_MINUTES minutes" (it lands
            // in the past or wildly off). Assert the expiry is within a small
            // tolerance of the correct long-arithmetic result.
            final long toleranceMillis = 5_000L;
            assertTrue(
                Math.abs( actualExpiryMillis - expectedExpiryMillis ) < toleranceMillis,
                "expiry " + actualExpiryMillis + " should be within " + toleranceMillis
                    + "ms of " + expectedExpiryMillis + " but overflowed arithmetic would diverge wildly" );

            // A correctly-computed far-future expiry must never appear "expired".
            assertTrue( lock.getTimeLeft() > 0, "a lock set for the far future must not already show as expired" );
        } finally {
            service.shutdown();
        }
    }
}
