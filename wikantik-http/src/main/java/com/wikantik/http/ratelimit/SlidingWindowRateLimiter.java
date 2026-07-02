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
package com.wikantik.http.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding-window rate limiter with global and per-client limits.
 *
 * <p>Each bucket tracks request timestamps within a 1-second window. Per-client
 * buckets live in a Caffeine cache with a hard size cap and 1-hour TTL after
 * the last write — replacing the previous {@code ConcurrentHashMap} +
 * probabilistic-cleanup pattern with deterministic eviction.</p>
 */
public class SlidingWindowRateLimiter {

    private static final long WINDOW_NS = 1_000_000_000L;

    private final int globalLimit;
    private final int perClientLimit;
    private final boolean disabled;
    private final Ticker ticker;

    private final ConcurrentLinkedDeque< Long > globalBucket;
    private final Cache< String, ConcurrentLinkedDeque< Long > > clientBuckets;

    public SlidingWindowRateLimiter( final int globalLimit, final int perClientLimit ) {
        this( globalLimit, perClientLimit, 10000, Ticker.systemTicker() );
    }

    public SlidingWindowRateLimiter( final int globalLimit, final int perClientLimit,
                           final int maxClients, final Ticker ticker ) {
        this.globalLimit = globalLimit;
        this.perClientLimit = perClientLimit;
        this.disabled = globalLimit <= 0 && perClientLimit <= 0;
        this.ticker = ticker;
        this.globalBucket = globalLimit > 0 ? new ConcurrentLinkedDeque<>() : null;
        this.clientBuckets = Caffeine.newBuilder()
                .maximumSize( maxClients )
                .expireAfterWrite( Duration.ofHours( 1 ) )
                .ticker( ticker )
                .build();
    }

    /**
     * Attempts to acquire permission for a request from the given client.
     *
     * @param clientId identifies the client (e.g. "key:0" or "ip:10.0.0.1")
     * @return {@code true} if the request is allowed, {@code false} if rate limited
     */
    public boolean tryAcquire( final String clientId ) {
        if ( disabled ) return true;

        final long now = ticker.read();

        if ( globalBucket != null ) {
            evictOld( globalBucket, now );
            if ( globalBucket.size() >= globalLimit ) return false;
        }

        ConcurrentLinkedDeque< Long > clientDeque = null;
        if ( perClientLimit > 0 ) {
            clientDeque = clientBuckets.get( clientId, k -> new ConcurrentLinkedDeque<>() );
            evictOld( clientDeque, now );
            if ( clientDeque.size() >= perClientLimit ) return false;
        }

        if ( globalBucket != null ) globalBucket.addLast( now );
        if ( clientDeque != null )  clientDeque.addLast( now );
        return true;
    }

    private static void evictOld( final ConcurrentLinkedDeque< Long > deque, final long now ) {
        while ( true ) {
            final Long head = deque.peekFirst();
            if ( head == null || now - head < WINDOW_NS ) break;
            deque.pollFirst();
        }
    }

    /** Test seam: returns approximate current size of the per-client cache. */
    long clientCacheSize() {
        clientBuckets.cleanUp();
        return clientBuckets.estimatedSize();
    }

    /** Test seam: force Caffeine maintenance to run synchronously. */
    void invalidateNow() {
        clientBuckets.cleanUp();
    }
}
