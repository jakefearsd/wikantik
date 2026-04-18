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
package com.wikantik.search.hybrid;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Clock} whose notion of "now" is advanced explicitly by tests.
 * All reads go through {@link #millis()}; the other accessors derive from it.
 */
final class FakeClock extends Clock {

    private final AtomicLong nowMillis;
    private final ZoneId zone;

    FakeClock( final long initialMillis ) {
        this( initialMillis, ZoneId.of( "UTC" ) );
    }

    private FakeClock( final long initialMillis, final ZoneId zone ) {
        this.nowMillis = new AtomicLong( initialMillis );
        this.zone = zone;
    }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone( final ZoneId z ) { return new FakeClock( nowMillis.get(), z ); }

    @Override public long millis() { return nowMillis.get(); }

    @Override public Instant instant() { return Instant.ofEpochMilli( nowMillis.get() ); }

    void advanceMillis( final long delta ) { nowMillis.addAndGet( delta ); }
}
