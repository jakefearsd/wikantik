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

import java.util.Properties;
import java.util.function.Supplier;

/**
 * Single-entry property cache implementation.
 * <p>
 * This is the original cache behavior - it only caches the last accessed
 * page's properties. When iterating through version history for a single page,
 * this provides excellent performance because the same property file is read
 * repeatedly. However, alternating between different pages causes cache misses.
 * <p>
 * Thread-safe through atomic reference updates.
 *
 * @since 2.12.3
 */
public class SingleEntryPropertyCache implements PropertyCacheStrategy {

    private volatile CachedEntry cachedEntry;

    /**
     * Holds a cached property file entry.
     */
    private record CachedEntry( String page, Properties props, long lastModified ) {
        CachedEntry {
            if ( page == null ) {
                throw new IllegalArgumentException( "page must not be null!" );
            }
            if ( props == null ) {
                throw new IllegalArgumentException( "properties must not be null!" );
            }
        }
    }

    @Override
    public Properties get( final String page, final long lastModified, final Supplier<Properties> loader ) {
        final CachedEntry entry = cachedEntry;

        // Check if cached entry matches
        if ( entry != null && entry.page().equals( page ) && entry.lastModified() == lastModified ) {
            return entry.props();
        }

        // Cache miss - load from disk
        final Properties props = loader.get();
        if ( props != null ) {
            cachedEntry = new CachedEntry( page, props, lastModified );
        }
        return props;
    }

    @Override
    public void invalidate( final String page ) {
        final CachedEntry entry = cachedEntry;
        if ( entry != null && entry.page().equals( page ) ) {
            cachedEntry = null;
        }
    }

    @Override
    public void clear() {
        cachedEntry = null;
    }

    @Override
    public void put( final String page, final Properties props, final long lastModified ) {
        cachedEntry = new CachedEntry( page, props, lastModified );
    }
}
