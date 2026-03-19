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
 * No-op property cache implementation that performs no caching.
 * <p>
 * Every call to {@link #get} invokes the loader, making this useful
 * for testing and debugging scenarios where you want to ensure fresh
 * data is always read from disk.
 *
 * @since 2.12.3
 */
public class NoOpPropertyCache implements PropertyCacheStrategy {

    @Override
    public Properties get( final String page, final long lastModified, final Supplier<Properties> loader ) {
        return loader.get();
    }

    @Override
    public void invalidate( final String page ) {
        // No-op - nothing to invalidate
    }

    @Override
    public void clear() {
        // No-op - nothing to clear
    }

    @Override
    public void put( final String page, final Properties props, final long lastModified ) {
        // No-op - don't cache
    }
}
