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
package com.wikantik.tools;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ToolsRateLimiterTest {

    private static ToolsRateLimiter createWithClock( final int globalLimit, final int perClientLimit,
                                                     final AtomicLong clock ) {
        return new ToolsRateLimiter( globalLimit, perClientLimit ) {
            @Override
            long clock() {
                return clock.get();
            }
        };
    }

    @Test
    void globalLimitEnforced() {
        final AtomicLong clock = new AtomicLong( 0 );
        final ToolsRateLimiter limiter = createWithClock( 3, 0, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertTrue( limiter.tryAcquire( "client-c" ) );
        assertFalse( limiter.tryAcquire( "client-d" ) );
    }

    @Test
    void perClientLimitEnforced() {
        final AtomicLong clock = new AtomicLong( 0 );
        final ToolsRateLimiter limiter = createWithClock( 0, 1, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertFalse( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-b" ) );
    }

    @Test
    void disabledWhenBothZero() {
        final ToolsRateLimiter limiter = new ToolsRateLimiter( 0, 0 );
        for ( int i = 0; i < 100; i++ ) {
            assertTrue( limiter.tryAcquire( "client-" + i ) );
        }
    }

    @Test
    void windowSlidesAfterOneSecond() {
        final AtomicLong clock = new AtomicLong( 0 );
        final ToolsRateLimiter limiter = createWithClock( 2, 0, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertFalse( limiter.tryAcquire( "client-c" ) );

        clock.set( 1_000_000_001L );

        assertTrue( limiter.tryAcquire( "client-d" ) );
        assertTrue( limiter.tryAcquire( "client-e" ) );
        assertFalse( limiter.tryAcquire( "client-f" ) );
    }

    @Test
    void negativeLimitsDisable() {
        final ToolsRateLimiter limiter = new ToolsRateLimiter( -1, -5 );
        for ( int i = 0; i < 100; i++ ) {
            assertTrue( limiter.tryAcquire( "client-a" ) );
        }
    }

    @Test
    void failedPerClientDoesNotPolluteGlobalBucket() {
        final AtomicLong clock = new AtomicLong( 0 );
        final ToolsRateLimiter limiter = createWithClock( 3, 1, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertFalse( limiter.tryAcquire( "client-a" ) );

        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertTrue( limiter.tryAcquire( "client-c" ) );
        assertFalse( limiter.tryAcquire( "client-d" ) );
    }

    @Test
    void usesRealClockWhenNotOverridden() {
        // No clock() override — exercises the real System.nanoTime() implementation.
        final ToolsRateLimiter limiter = new ToolsRateLimiter( 5, 0 );
        assertTrue( limiter.tryAcquire( "client-a" ) );
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, ? > bucketsOf( final ToolsRateLimiter limiter ) throws Exception {
        final Field field = ToolsRateLimiter.class.getDeclaredField( "buckets" );
        field.setAccessible( true );
        return ( Map< String, ? > ) field.get( limiter );
    }

    @Test
    void staleClientBucketsAreEventuallyCleanedUp() throws Exception {
        // cleanupStaleEntries() fires probabilistically (1/100 per tryAcquire call), so this
        // seeds many stale buckets then hammers tryAcquire until the sweep fires (virtually
        // certain within a few thousand calls) and asserts the stale entries were actually
        // removed — not just that no exception was thrown.
        final AtomicLong clock = new AtomicLong( 0 );
        final ToolsRateLimiter limiter = createWithClock( 0, 1000, clock );
        for ( int i = 0; i < 200; i++ ) {
            assertTrue( limiter.tryAcquire( "stale-client-" + i ) );
        }
        assertEquals( 200, bucketsOf( limiter ).size() );

        // Past both the 1s sliding window and the 60s stale threshold.
        clock.set( 70_000_000_000L );

        boolean cleaned = false;
        for ( int i = 0; i < 5000 && !cleaned; i++ ) {
            limiter.tryAcquire( "fresh-client-" + i );
            if ( bucketsOf( limiter ).size() < 200 ) {
                cleaned = true;
            }
        }
        assertTrue( cleaned, "Expected the probabilistic stale-entry sweep to evict the 200 "
                + "stale client buckets within 5000 tryAcquire calls" );
    }
}
