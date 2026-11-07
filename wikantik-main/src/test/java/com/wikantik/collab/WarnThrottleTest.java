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
package com.wikantik.collab;

import com.wikantik.collab.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Final review item 5: per-request refusal WARNs are at most one a minute per participant or address. */
class WarnThrottleTest {

    @Test
    void oneWarningPerKeyPerMinuteThenTheCountOfThoseSuppressed() {
        final MutableClock clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
        final WarnThrottle throttle = new WarnThrottle( clock );

        assertEquals( 0, throttle.admit( "p1" ), "the first warns" );
        assertEquals( WarnThrottle.QUIET, throttle.admit( "p1" ) );
        assertEquals( WarnThrottle.QUIET, throttle.admit( "p1" ) );
        assertEquals( 0, throttle.admit( "p2" ), "another key has its own minute" );
        clock.advance( Duration.ofSeconds( 59 ) );
        assertEquals( WarnThrottle.QUIET, throttle.admit( "p1" ) );
        clock.advance( Duration.ofSeconds( 1 ) );
        assertEquals( 3, throttle.admit( "p1" ), "a minute on: warns again, saying how many were quiet" );
        assertEquals( WarnThrottle.QUIET, throttle.admit( "p1" ) );
    }

    @Test
    void manyKeysStayBounded() {
        final MutableClock clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
        final WarnThrottle throttle = new WarnThrottle( clock );
        for( int i = 0; i < WarnThrottle.MAX_KEYS + 10; i++ ) {
            throttle.admit( "k" + i );
        }
        clock.advance( Duration.ofMinutes( 2 ) );
        throttle.admit( "late" );
        assertEquals( 1, throttle.size(), "expired windows are dropped once the map is over its bound" );
    }
}
