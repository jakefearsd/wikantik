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
package com.wikantik.providers;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Properties;
import java.util.function.Supplier;

/**
 * LRU (Least Recently Used) property cache implementation backed by Caffeine.
 *
 * <p>Caches multiple property files with automatic eviction of the least
 * recently used entries when the cache reaches its maximum size. This is more
 * effective than single-entry caching when alternating between different
 * pages.</p>
 *
 * <p><strong>Concurrency:</strong> Caffeine internally uses segment-level
 * striped locks (no instance-wide monitor), so concurrent readers + writers
 * to different keys proceed without contention. Replaces the prior
 * {@link java.util.LinkedHashMap}-backed implementation whose methods were
 * all {@code synchronized} on the instance — at N=650 the JFR recorded
 * ~1,400 {@code jdk.JavaMonitorEnter} events on {@link #get} from threads
 * queueing for the one mutex.</p>
 *
 * @since 2.12.3
 */
public class LruPropertyCache implements PropertyCacheStrategy {

    /** Default cache size if not specified. */
    public static final int DEFAULT_SIZE = 100;

    private final int maxSize;
    private final Cache< String, CachedEntry > cache;

    /** Holds a cached property file entry. */
    private record CachedEntry( Properties props, long lastModified ) { }

    /** Creates an LRU cache with the default size. */
    public LruPropertyCache() {
        this( DEFAULT_SIZE );
    }

    /**
     * Creates an LRU cache with the specified maximum size.
     *
     * @param maxSize the maximum number of entries to cache
     */
    public LruPropertyCache( final int maxSize ) {
        if ( maxSize < 1 ) {
            throw new IllegalArgumentException( "Cache size must be at least 1" );
        }
        this.maxSize = maxSize;
        this.cache = Caffeine.newBuilder()
            .maximumSize( maxSize )
            .recordStats()
            .build();
    }

    /**
     * The underlying Caffeine cache — exposed for metric registration via
     * {@code CaffeineCacheMetricsBridge}. Not intended for callers outside
     * the metrics path.
     */
    public Cache< String, CachedEntry > cache() {
        return cache;
    }

    @Override
    public Properties get( final String page, final long lastModified, final Supplier< Properties > loader ) {
        final CachedEntry entry = cache.getIfPresent( page );

        // Cache hit only when the lastModified timestamp matches the stored one;
        // stale entries fall through to a fresh load + write-back.
        if ( entry != null && entry.lastModified() == lastModified ) {
            return entry.props();
        }

        final Properties props = loader.get();
        if ( props != null ) {
            cache.put( page, new CachedEntry( props, lastModified ) );
        }
        return props;
    }

    @Override
    public void invalidate( final String page ) {
        cache.invalidate( page );
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public void put( final String page, final Properties props, final long lastModified ) {
        cache.put( page, new CachedEntry( props, lastModified ) );
    }

    /**
     * Returns the current number of cached entries. Caffeine's
     * {@link Cache#estimatedSize()} is an approximation under high concurrency
     * but exact for steady-state inspection.
     *
     * @return the cache size
     */
    public int size() {
        return (int) cache.estimatedSize();
    }

    /**
     * Returns the maximum cache size.
     *
     * @return the maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }
}
