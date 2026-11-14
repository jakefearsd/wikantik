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

import com.wikantik.api.core.Engine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A single-value TTL cache for operator-only full-corpus scans (broken block references, audit
 * chain verification, pages without frontmatter). The TTL comes from {@code wikantik.admin.scanCacheTTL}
 * (seconds, default 30, {@code <= 0} disables caching).
 *
 * <p>Value and computation time live together in one immutable {@link Snapshot} behind one volatile
 * field, so a reader can never pair a fresh value with a stale timestamp. Two racing requests may
 * both recompute (at most one redundant scan); neither can observe a torn state.</p>
 *
 * @param <T> the cached value type
 */
final class ScanCache< T > {

    private static final Logger LOG = LogManager.getLogger( ScanCache.class );

    /** Property key shared by every cached admin scan. */
    static final String TTL_KEY = "wikantik.admin.scanCacheTTL";
    static final long DEFAULT_TTL_SECONDS = 30L;

    /** A computed value and the epoch-millis instant it was computed. */
    record Snapshot< T >( T value, long computedAtMs ) {}

    private final LongSupplier clockMs;
    private volatile Snapshot< T > cached;

    ScanCache() {
        this( System::currentTimeMillis );
    }

    ScanCache( final LongSupplier clockMs ) {
        this.clockMs = clockMs;
    }

    /** Returns the cached snapshot if younger than the TTL, else computes and stores a new one. */
    Snapshot< T > get( final long ttlSeconds, final Supplier< T > compute ) {
        final long now = clockMs.getAsLong();
        if ( ttlSeconds > 0 ) {
            final Snapshot< T > c = cached;
            if ( c != null && now - c.computedAtMs() < ttlSeconds * 1000L ) {
                return c;
            }
        }
        final Snapshot< T > fresh = new Snapshot<>( compute.get(), now );
        if ( ttlSeconds > 0 ) {
            cached = fresh;
        }
        return fresh;
    }

    void invalidate() {
        cached = null;
    }

    /** Reads {@link #TTL_KEY}; an absent engine/property or unparseable value yields the default (fail safe). */
    static long ttlSeconds( final Engine engine ) {
        final Properties props = engine == null ? null : engine.getWikiProperties();
        if ( props == null ) {
            return DEFAULT_TTL_SECONDS;
        }
        final String raw = props.getProperty( TTL_KEY, Long.toString( DEFAULT_TTL_SECONDS ) );
        try {
            return Long.parseLong( raw.trim() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Invalid {}='{}' - using {}s", TTL_KEY, raw, DEFAULT_TTL_SECONDS );
            return DEFAULT_TTL_SECONDS;
        }
    }
}
