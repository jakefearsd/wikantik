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
package com.wikantik.plantuml;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The service's least-recently-used outcome cache, bounded both by entry count and by the approximate bytes it holds
 * (the characters of both SVG variants and of the message, plus a fixed per-entry overhead; PlantUML's SVG is
 * almost all ASCII, which a Java string stores at one byte per character). An outcome that alone exceeds the byte
 * bound is not cached. Not thread-safe: {@link PlantUmlService} only touches it while holding its lock.
 */
final class OutcomeCache {

    /** Counted for every entry on top of its text: the key, the records and the map node. */
    static final long ENTRY_OVERHEAD_BYTES = 256L;

    /** A cached outcome; {@code expiresAtNanos == 0} means it never expires (normal LRU lifetime). */
    record Entry( RenderOutcome outcome, long expiresAtNanos ) {

        /** @return true when this entry carries an expiry that has passed */
        boolean expired( final long now ) {
            return expiresAtNanos != 0 && now - expiresAtNanos >= 0;
        }
    }

    private final Map< String, Entry > map = new LinkedHashMap<>( 16, 0.75f, true );
    private final int maxEntries;
    private final long maxBytes;
    private long bytes;

    OutcomeCache( final int maxEntries, final long maxBytes ) {
        this.maxEntries = Math.max( 1, maxEntries );
        this.maxBytes = Math.max( 0L, maxBytes );
    }

    /** @return the entry for {@code key} (marking it most recently used), or null */
    Entry get( final String key ) {
        return map.get( key );
    }

    /** Stores {@code entry}, then evicts least-recently-used entries until both bounds hold. */
    void put( final String key, final Entry entry ) {
        final Entry old = map.remove( key );
        if( old != null ) {
            bytes -= weight( old.outcome() );
        }
        final long w = weight( entry.outcome() );
        if( w > maxBytes ) {
            return;
        }
        map.put( key, entry );
        bytes += w;
        final Iterator< Entry > eldest = map.values().iterator();
        while( ( map.size() > maxEntries || bytes > maxBytes ) && eldest.hasNext() ) {
            bytes -= weight( eldest.next().outcome() );
            eldest.remove();
        }
    }

    /** @return the approximate bytes held */
    long bytes() {
        return bytes;
    }

    /** @return the number of entries held */
    int size() {
        return map.size();
    }

    /** @return the approximate heap bytes {@code o} holds while cached */
    static long weight( final RenderOutcome o ) {
        long w = ENTRY_OVERHEAD_BYTES + length( o.message() );
        if( o.isDrawn() ) {
            w += length( o.drawing().lightSvg() ) + length( o.drawing().darkSvg() );
        }
        return w;
    }

    private static long length( final String s ) {
        return s == null ? 0L : s.length();
    }
}
