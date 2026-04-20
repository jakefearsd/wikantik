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
package com.wikantik.tools;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sliding-window rate limiter with global and per-client limits.
 *
 * <p>Each bucket tracks request timestamps within a 1-second window.
 * Stale client entries are cleaned up probabilistically to prevent unbounded growth.</p>
 */
public class ToolsRateLimiter {

    private static final String GLOBAL_KEY = "__global__";
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final long STALE_THRESHOLD_NS = 60_000_000_000L;

    private final int globalLimit;
    private final int perClientLimit;
    private final ConcurrentHashMap< String, ConcurrentLinkedDeque< Long > > buckets;
    private final boolean disabled;

    public ToolsRateLimiter( final int globalLimit, final int perClientLimit ) {
        this.globalLimit = globalLimit;
        this.perClientLimit = perClientLimit;
        this.buckets = new ConcurrentHashMap<>();
        this.disabled = globalLimit <= 0 && perClientLimit <= 0;
    }

    public boolean tryAcquire( final String clientId ) {
        if ( disabled ) {
            return true;
        }

        final long now = clock();

        final ConcurrentLinkedDeque< Long > globalDeque =
                globalLimit > 0 ? getAndEvict( GLOBAL_KEY, now ) : null;
        final ConcurrentLinkedDeque< Long > clientDeque =
                perClientLimit > 0 ? getAndEvict( clientId, now ) : null;

        if ( globalDeque != null && globalDeque.size() >= globalLimit ) {
            return false;
        }
        if ( clientDeque != null && clientDeque.size() >= perClientLimit ) {
            return false;
        }

        if ( globalDeque != null ) {
            globalDeque.addLast( now );
        }
        if ( clientDeque != null ) {
            clientDeque.addLast( now );
        }

        if ( ThreadLocalRandom.current().nextInt( 100 ) == 0 ) {
            cleanupStaleEntries( now );
        }

        return true;
    }

    private ConcurrentLinkedDeque< Long > getAndEvict( final String key, final long now ) {
        final ConcurrentLinkedDeque< Long > deque =
                buckets.computeIfAbsent( key, k -> new ConcurrentLinkedDeque<>() );
        evictOld( deque, now );
        return deque;
    }

    private void evictOld( final ConcurrentLinkedDeque< Long > deque, final long now ) {
        while ( true ) {
            final Long head = deque.peekFirst();
            if ( head == null || now - head < WINDOW_NS ) {
                break;
            }
            deque.pollFirst();
        }
    }

    private void cleanupStaleEntries( final long now ) {
        final Iterator< Map.Entry< String, ConcurrentLinkedDeque< Long > > > it =
                buckets.entrySet().iterator();
        while ( it.hasNext() ) {
            final Map.Entry< String, ConcurrentLinkedDeque< Long > > entry = it.next();
            if ( GLOBAL_KEY.equals( entry.getKey() ) ) {
                continue;
            }
            final ConcurrentLinkedDeque< Long > deque = entry.getValue();
            final Long last = deque.peekLast();
            if ( last == null || now - last > STALE_THRESHOLD_NS ) {
                it.remove();
            }
        }
    }

    /** Overridable clock for testing. Returns nanoseconds. */
    long clock() {
        return System.nanoTime();
    }
}
