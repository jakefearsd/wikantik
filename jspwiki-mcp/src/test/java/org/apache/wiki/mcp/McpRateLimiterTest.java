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
package org.apache.wiki.mcp;

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
}
