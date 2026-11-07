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
package com.wikantik.collab.testing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MutableClockTest {

    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    @Test
    void startsAtTheGivenInstant() {
        assertEquals( T0, new MutableClock( T0 ).instant() );
    }

    @Test
    void advanceMovesTimeForward() {
        final MutableClock c = new MutableClock( T0 );
        c.advance( Duration.ofSeconds( 90 ) );
        assertEquals( T0.plusSeconds( 90 ), c.instant() );
        assertEquals( T0.plusSeconds( 90 ).toEpochMilli(), c.millis() );
    }

    @Test
    void setJumpsToAnInstant() {
        final MutableClock c = new MutableClock( T0 );
        c.set( T0.minusSeconds( 5 ) );
        assertEquals( T0.minusSeconds( 5 ), c.instant() );
    }

    @Test
    void zoneIsUtcAndWithZoneKeepsTheSameTimeline() {
        final MutableClock c = new MutableClock( T0 );
        assertEquals( ZoneOffset.UTC, c.getZone() );
        assertSame( c, c.withZone( ZoneOffset.ofHours( 3 ) ) );
    }
}
