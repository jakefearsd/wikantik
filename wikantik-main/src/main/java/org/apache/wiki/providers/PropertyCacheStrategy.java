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
 * Strategy interface for caching page property files.
 * <p>
 * Different implementations can provide different caching strategies
 * (single-entry, LRU, no-op) based on configuration and use case.
 *
 * @since 2.12.3
 */
public interface PropertyCacheStrategy {

    /**
     * Gets cached properties for a page, loading them if not cached.
     *
     * @param page the page name
     * @param lastModified the last modification time of the property file (for cache validation)
     * @param loader supplier that loads the properties from disk
     * @return the Properties object, either from cache or freshly loaded
     */
    Properties get( String page, long lastModified, Supplier<Properties> loader );

    /**
     * Invalidates the cache entry for a specific page.
     *
     * @param page the page name to invalidate
     */
    void invalidate( String page );

    /**
     * Clears all cached entries.
     */
    void clear();

    /**
     * Updates the cache with new properties (typically after a write operation).
     *
     * @param page the page name
     * @param props the properties to cache
     * @param lastModified the file's last modification time
     */
    void put( String page, Properties props, long lastModified );
}
