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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReopenedTimeTest {

    private static Instant t( final String s ) {
        return Instant.parse( "2026-" + s + ":00Z" );
    }

    private static ReopenedTime.At at( final String when, final String from, final String to ) {
        return new ReopenedTime.At( t( when ), new StatusMoves.Move( from, to ) );
    }

    @Test
    void noReopeningIsNoGap() {
        assertEquals( Duration.ZERO, ReopenedTime.gap( List.of( at( "11-02T09:00", "ready", "in_progress" ),
            at( "11-05T09:00", "in_progress", "done" ) ), t( "11-02T09:00" ) ) );
    }

    @Test
    void eachDoneIntervalBeforeAReopeningIsAGap() {
        assertEquals( Duration.ofDays( 3 ), ReopenedTime.gap( List.of( at( "11-02T09:00", "ready", "in_progress" ),
            at( "11-03T09:00", "in_progress", "done" ), at( "11-04T09:00", "done", "in_progress" ),
            at( "11-05T09:00", "in_progress", "done" ), at( "11-07T09:00", "done", "in_progress" ),
            at( "11-10T09:00", "in_progress", "done" ) ), t( "11-02T09:00" ) ) );
    }

    @Test
    void aDoneIntervalBeforeTheFirstStartIsNotCounted() {
        assertEquals( Duration.ZERO, ReopenedTime.gap( List.of( at( "11-01T09:00", "ready", "done" ),
            at( "11-02T09:00", "done", "ready" ), at( "11-03T09:00", "ready", "in_progress" ),
            at( "11-05T09:00", "in_progress", "done" ) ), t( "11-03T09:00" ) ) );
    }
}
