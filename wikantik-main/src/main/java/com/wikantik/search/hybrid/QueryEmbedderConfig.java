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

import java.util.Properties;

/**
 * Tunables for {@link QueryEmbedder}. All settings are backed by keys under the
 * {@code wikantik.search.hybrid.embedder} namespace and have conservative
 * defaults tuned for a flaky embedding backend: short timeout, modest cache,
 * and a breaker that opens quickly enough to spare users noticeable latency.
 *
 * @param timeoutMs           per-call wall-clock budget for the underlying embed request
 * @param cacheTtlSeconds     TTL for a cached query vector
 * @param cacheMaxEntries     Caffeine maximum size
 * @param breakerWindowSize   rolling window of recent calls used to evaluate the failure rate
 * @param breakerMinCalls     minimum observations in the window before the breaker will consider opening
 * @param breakerFailureRate  failure rate (0.0 – 1.0) that trips the breaker open
 * @param breakerCooldownMs   how long the breaker stays OPEN before a probe is allowed
 */
public record QueryEmbedderConfig(
        long timeoutMs,
        long cacheTtlSeconds,
        long cacheMaxEntries,
        int breakerWindowSize,
        int breakerMinCalls,
        double breakerFailureRate,
        long breakerCooldownMs ) {

    /** Config-key prefix, used both for property lookup and for documentation. */
    public static final String PREFIX = "wikantik.search.hybrid.embedder.";

    public static final long DEFAULT_TIMEOUT_MS = 2000L;
    public static final long DEFAULT_CACHE_TTL_SECONDS = 600L;
    public static final long DEFAULT_CACHE_MAX_ENTRIES = 1000L;
    public static final int DEFAULT_BREAKER_WINDOW_SIZE = 20;
    public static final int DEFAULT_BREAKER_MIN_CALLS = 10;
    public static final double DEFAULT_BREAKER_FAILURE_RATE = 0.5;
    public static final long DEFAULT_BREAKER_COOLDOWN_MS = 30_000L;

    public QueryEmbedderConfig {
        if( timeoutMs <= 0 ) {
            throw new IllegalArgumentException( "timeoutMs must be > 0, got " + timeoutMs );
        }
        if( cacheTtlSeconds <= 0 ) {
            throw new IllegalArgumentException( "cacheTtlSeconds must be > 0, got " + cacheTtlSeconds );
        }
        if( cacheMaxEntries <= 0 ) {
            throw new IllegalArgumentException( "cacheMaxEntries must be > 0, got " + cacheMaxEntries );
        }
        if( breakerWindowSize <= 0 ) {
            throw new IllegalArgumentException( "breakerWindowSize must be > 0, got " + breakerWindowSize );
        }
        if( breakerMinCalls <= 0 || breakerMinCalls > breakerWindowSize ) {
            throw new IllegalArgumentException(
                    "breakerMinCalls must be in (0, windowSize], got " + breakerMinCalls );
        }
        if( breakerFailureRate <= 0.0 || breakerFailureRate > 1.0 ) {
            throw new IllegalArgumentException(
                    "breakerFailureRate must be in (0.0, 1.0], got " + breakerFailureRate );
        }
        if( breakerCooldownMs < 0 ) {
            throw new IllegalArgumentException( "breakerCooldownMs must be >= 0, got " + breakerCooldownMs );
        }
    }

    /** All-defaults config. */
    public static QueryEmbedderConfig defaults() {
        return new QueryEmbedderConfig(
                DEFAULT_TIMEOUT_MS,
                DEFAULT_CACHE_TTL_SECONDS,
                DEFAULT_CACHE_MAX_ENTRIES,
                DEFAULT_BREAKER_WINDOW_SIZE,
                DEFAULT_BREAKER_MIN_CALLS,
                DEFAULT_BREAKER_FAILURE_RATE,
                DEFAULT_BREAKER_COOLDOWN_MS );
    }

    /**
     * Build a config from a {@link Properties}. Unknown or missing keys fall back
     * to the defaults. Parse failures propagate as {@link IllegalArgumentException}
     * so operators see bad config early rather than silently running with defaults.
     */
    public static QueryEmbedderConfig fromProperties( final Properties p ) {
        if( p == null ) {
            return defaults();
        }
        return new QueryEmbedderConfig(
                readLong( p, "timeout-ms",               DEFAULT_TIMEOUT_MS ),
                readLong( p, "cache.ttl-seconds",        DEFAULT_CACHE_TTL_SECONDS ),
                readLong( p, "cache.max-entries",        DEFAULT_CACHE_MAX_ENTRIES ),
                (int) readLong( p, "breaker.window-size", DEFAULT_BREAKER_WINDOW_SIZE ),
                (int) readLong( p, "breaker.min-calls",   DEFAULT_BREAKER_MIN_CALLS ),
                readDouble( p, "breaker.failure-rate",   DEFAULT_BREAKER_FAILURE_RATE ),
                readLong( p, "breaker.cooldown-ms",      DEFAULT_BREAKER_COOLDOWN_MS ) );
    }

    private static long readLong( final Properties p, final String suffix, final long fallback ) {
        final String raw = p.getProperty( PREFIX + suffix );
        if( raw == null || raw.isBlank() ) {
            return fallback;
        }
        try {
            return Long.parseLong( raw.trim() );
        } catch( final NumberFormatException e ) {
            throw new IllegalArgumentException(
                    "Invalid long value for " + PREFIX + suffix + ": " + raw, e );
        }
    }

    private static double readDouble( final Properties p, final String suffix, final double fallback ) {
        final String raw = p.getProperty( PREFIX + suffix );
        if( raw == null || raw.isBlank() ) {
            return fallback;
        }
        try {
            return Double.parseDouble( raw.trim() );
        } catch( final NumberFormatException e ) {
            throw new IllegalArgumentException(
                    "Invalid double value for " + PREFIX + suffix + ": " + raw, e );
        }
    }
}
