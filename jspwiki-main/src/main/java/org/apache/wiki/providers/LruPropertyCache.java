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
package org.apache.wiki.providers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * LRU (Least Recently Used) property cache implementation.
 * <p>
 * Caches multiple property files with automatic eviction of the least
 * recently used entries when the cache reaches its maximum size.
 * This is more effective than single-entry caching when alternating
 * between different pages.
 * <p>
 * Thread-safe through synchronization.
 *
 * @since 2.12.3
 */
public class LruPropertyCache implements PropertyCacheStrategy {

    /** Default cache size if not specified. */
    public static final int DEFAULT_SIZE = 100;

    private final int maxSize;
    private final Map<String, CachedEntry> cache;

    /**
     * Holds a cached property file entry.
     */
    private record CachedEntry( Properties props, long lastModified ) { }

    /**
     * Creates an LRU cache with the default size.
     */
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
        // LinkedHashMap with access-order for LRU behavior
        this.cache = new LinkedHashMap<>( maxSize + 1, 0.75f, true ) {
            @Override
            protected boolean removeEldestEntry( final Map.Entry<String, CachedEntry> eldest ) {
                return size() > LruPropertyCache.this.maxSize;
            }
        };
    }

    @Override
    public synchronized Properties get( final String page, final long lastModified, final Supplier<Properties> loader ) {
        final CachedEntry entry = cache.get( page );

        // Check if cached entry is valid
        if ( entry != null && entry.lastModified() == lastModified ) {
            return entry.props();
        }

        // Cache miss or stale - load from disk
        final Properties props = loader.get();
        if ( props != null ) {
            cache.put( page, new CachedEntry( props, lastModified ) );
        }
        return props;
    }

    @Override
    public synchronized void invalidate( final String page ) {
        cache.remove( page );
    }

    @Override
    public synchronized void clear() {
        cache.clear();
    }

    @Override
    public synchronized void put( final String page, final Properties props, final long lastModified ) {
        cache.put( page, new CachedEntry( props, lastModified ) );
    }

    /**
     * Returns the current number of cached entries.
     *
     * @return the cache size
     */
    public synchronized int size() {
        return cache.size();
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
