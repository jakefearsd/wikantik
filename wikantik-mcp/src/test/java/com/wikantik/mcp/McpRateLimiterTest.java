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
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class McpRateLimiterTest {

    /**
     * Testable rate limiter with a controllable clock.
     */
    private static McpRateLimiter createWithClock( final int globalLimit, final int perClientLimit,
                                                    final AtomicLong clock ) {
        return new McpRateLimiter( globalLimit, perClientLimit ) {
            @Override
            long clock() {
                return clock.get();
            }
        };
    }

    @Test
    void testGlobalLimitEnforced() {
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 3, 0, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertTrue( limiter.tryAcquire( "client-c" ) );
        // 4th call exceeds global limit of 3
        assertFalse( limiter.tryAcquire( "client-d" ) );
    }

    @Test
    void testPerClientLimitEnforced() {
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 0, 1, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        // Same client, 2nd call exceeds per-client limit
        assertFalse( limiter.tryAcquire( "client-a" ) );
        // Different client should still pass
        assertTrue( limiter.tryAcquire( "client-b" ) );
    }

    @Test
    void testDisabledWhenBothZero() {
        final McpRateLimiter limiter = new McpRateLimiter( 0, 0 );

        for ( int i = 0; i < 100; i++ ) {
            assertTrue( limiter.tryAcquire( "client-" + i ) );
        }
    }

    @Test
    void testWindowSlidesAfterOneSecond() {
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 2, 0, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertFalse( limiter.tryAcquire( "client-c" ) );

        // Advance clock past 1-second window
        clock.set( 1_000_000_001L );

        // Old entries should be evicted, new requests allowed
        assertTrue( limiter.tryAcquire( "client-d" ) );
        assertTrue( limiter.tryAcquire( "client-e" ) );
        assertFalse( limiter.tryAcquire( "client-f" ) );
    }

    @Test
    void testPerClientWindowSlides() {
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 0, 1, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertFalse( limiter.tryAcquire( "client-a" ) );

        // Advance past window
        clock.set( 1_000_000_001L );
        assertTrue( limiter.tryAcquire( "client-a" ) );
    }

    @Test
    void testBothLimitsEnforced() {
        final AtomicLong clock = new AtomicLong( 0 );
        // Global=3, per-client=1
        final McpRateLimiter limiter = createWithClock( 3, 1, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        // client-a is now rate limited per-client
        assertFalse( limiter.tryAcquire( "client-a" ) );
        // Different clients still allowed up to global limit
        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertTrue( limiter.tryAcquire( "client-c" ) );
        // Global limit reached
        assertFalse( limiter.tryAcquire( "client-d" ) );
    }

    @Test
    void testNullClientIdThrowsNpe() {
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 10, 2, clock );

        // ConcurrentHashMap does not allow null keys, so null clientId throws NPE
        assertThrows( NullPointerException.class, () -> limiter.tryAcquire( null ) );
    }

    @Test
    void testNegativeLimitsDisable() {
        final McpRateLimiter limiter = new McpRateLimiter( -1, -5 );

        // Negative limits should be treated the same as 0 (disabled)
        for ( int i = 0; i < 100; i++ ) {
            assertTrue( limiter.tryAcquire( "client-a" ) );
        }
    }

    @Test
    void testGlobalLimitOnlyIgnoresPerClient() {
        final AtomicLong clock = new AtomicLong( 0 );
        // Only global limit, no per-client limit
        final McpRateLimiter limiter = createWithClock( 5, 0, clock );

        // Same client can make all 5 calls
        for ( int i = 0; i < 5; i++ ) {
            assertTrue( limiter.tryAcquire( "client-a" ) );
        }
        assertFalse( limiter.tryAcquire( "client-a" ) );
    }

    @Test
    void testPerClientOnlyIgnoresGlobal() {
        final AtomicLong clock = new AtomicLong( 0 );
        // No global limit, per-client limit of 2
        final McpRateLimiter limiter = createWithClock( 0, 2, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertFalse( limiter.tryAcquire( "client-a" ) );

        // Many different clients should all get their own budget
        for ( int i = 0; i < 50; i++ ) {
            assertTrue( limiter.tryAcquire( "client-" + i ) );
        }
    }

    @Test
    void testCleanupStaleBucketsRemovedAfterTimeout() {
        // This test exercises the cleanupStaleEntries path by making
        // enough calls that the 1-in-100 probability cleanup triggers
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 1000, 1000, clock );

        // Make a request from a client
        assertTrue( limiter.tryAcquire( "stale-client" ) );

        // Advance clock past the stale threshold (60 seconds = 60_000_000_000 ns)
        clock.set( 61_000_000_000L );

        // Make many requests to increase probability of cleanup trigger (1 in 100)
        // With 200 calls, probability of at least one cleanup is 1-(99/100)^200 ≈ 87%
        for ( int i = 0; i < 200; i++ ) {
            limiter.tryAcquire( "active-client-" + i );
        }

        // The stale bucket should eventually be cleaned up. We verify indirectly
        // by checking that the limiter still functions correctly.
        clock.set( 62_000_000_000L );
        assertTrue( limiter.tryAcquire( "stale-client" ),
                "Stale client should be able to acquire again after window slides" );
    }

    @Test
    void testWindowEvictionWithPerClientLimit() {
        final AtomicLong clock = new AtomicLong( 0 );
        final McpRateLimiter limiter = createWithClock( 0, 3, clock );

        // Fill up the per-client budget
        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertFalse( limiter.tryAcquire( "client-a" ) );

        // Advance half a second — oldest entries still in window
        clock.set( 500_000_000L );
        assertFalse( limiter.tryAcquire( "client-a" ) );

        // Advance past full window for first 3 entries
        clock.set( 1_000_000_001L );
        assertTrue( limiter.tryAcquire( "client-a" ) );
    }

    @Test
    void testBothLimitsGlobalHitsFirst() {
        final AtomicLong clock = new AtomicLong( 0 );
        // Global=2, per-client=5 — global limit is more restrictive
        final McpRateLimiter limiter = createWithClock( 2, 5, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        assertTrue( limiter.tryAcquire( "client-b" ) );
        // Global limit reached even though per-client has budget
        assertFalse( limiter.tryAcquire( "client-c" ) );
    }

    @Test
    void testFailedPerClientDoesNotPolluteGlobalBucket() {
        final AtomicLong clock = new AtomicLong( 0 );
        // Global=3, per-client=1
        final McpRateLimiter limiter = createWithClock( 3, 1, clock );

        assertTrue( limiter.tryAcquire( "client-a" ) );
        // client-a per-client limit reached — this should NOT consume global budget
        assertFalse( limiter.tryAcquire( "client-a" ) );

        // Global budget should still have 2 slots (only 1 consumed by first successful call)
        assertTrue( limiter.tryAcquire( "client-b" ) );
        assertTrue( limiter.tryAcquire( "client-c" ) );
        // Now global is full
        assertFalse( limiter.tryAcquire( "client-d" ) );
    }
}
