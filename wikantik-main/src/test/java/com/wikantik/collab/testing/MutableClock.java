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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** A manually driven UTC clock for deterministic tests (F5). Thread-safe. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock( final Instant start ) {
        this.now = Objects.requireNonNull( start, "start" );
    }

    public void advance( final Duration d ) {
        synchronized( this ) {
            now = now.plus( d );
        }
    }

    public void set( final Instant t ) {
        now = Objects.requireNonNull( t, "t" );
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** The timeline is zone-independent; the clock stays UTC. */
    @Override
    public Clock withZone( final ZoneId zone ) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
