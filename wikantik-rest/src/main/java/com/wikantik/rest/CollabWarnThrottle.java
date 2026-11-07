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
package com.wikantik.rest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the collaboration surface's per-request refusal WARNs (a malformed or oversize body, a malformed push) to one a
 * minute per key (a participant, or a client address): a client stuck repeating a refused request, several times a
 * second, would otherwise flood the log. {@link #admit} says whether this one warns, and how many were kept quiet since
 * the last; callers log the quiet ones at DEBUG. The map is bounded: past {@link #MAX_KEYS} keys, expired windows are
 * dropped.
 */
final class CollabWarnThrottle {

    /** {@link #admit}'s answer when this refusal is kept quiet (logged at DEBUG). */
    static final long QUIET = -1;
    /** One WARN per key per this long. */
    static final Duration INTERVAL = Duration.ofMinutes( 1 );
    /** Above this many keys, expired windows are dropped. */
    static final int MAX_KEYS = 10_000;

    /** The start of a key's current minute and how many refusals it kept quiet. */
    private record Window( Instant start, long quiet ) {
    }

    private final Clock clock;
    private final Map< String, Window > windows = new ConcurrentHashMap<>();

    CollabWarnThrottle( final Clock clock ) {
        this.clock = clock;
    }

    /**
     * Whether a refusal for {@code key} warns now: {@link #QUIET} when one already warned within the minute; else how
     * many were kept quiet since that one (0 for the first).
     */
    long admit( final String key ) {
        final Instant now = clock.instant();
        final long[] out = { QUIET };
        windows.compute( key == null ? "" : key, ( k, w ) -> {
            if( w == null || !now.isBefore( w.start().plus( INTERVAL ) ) ) {
                out[ 0 ] = w == null ? 0 : w.quiet();
                return new Window( now, 0 );
            }
            return new Window( w.start(), w.quiet() + 1 );
        } );
        if( windows.size() > MAX_KEYS ) {
            windows.values().removeIf( w -> !now.isBefore( w.start().plus( INTERVAL ) ) );
        }
        return out[ 0 ];
    }

    /** The number of keys held (for tests). */
    int size() {
        return windows.size();
    }

    /** The suffix a WARN carries when refusals were kept quiet before it. */
    static String quietSuffix( final long quiet ) {
        return quiet > 0 ? " (" + quiet + " more like it in the last minute were logged at DEBUG)" : "";
    }
}
