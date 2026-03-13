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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Tests for SingleEntryPropertyCache validation and caching behavior.
 */
class SingleEntryPropertyCacheTest {

    @Test
    void putNullPageThrowsIllegalArgumentException() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        Assertions.assertThrows( IllegalArgumentException.class,
                () -> cache.put( null, new Properties(), 0L ),
                "null page should throw IllegalArgumentException" );
    }

    @Test
    void putNullPropertiesThrowsIllegalArgumentException() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        Assertions.assertThrows( IllegalArgumentException.class,
                () -> cache.put( "TestPage", null, 0L ),
                "null properties should throw IllegalArgumentException" );
    }

    @Test
    void cacheHitReturnsProperties() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final Properties props = new Properties();
        props.setProperty( "key", "value" );

        cache.put( "TestPage", props, 100L );
        final Properties result = cache.get( "TestPage", 100L, Properties::new );

        Assertions.assertSame( props, result, "Cache hit should return the same Properties instance" );
    }

    @Test
    void cacheMissOnDifferentPageCallsLoader() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final Properties original = new Properties();
        cache.put( "PageA", original, 100L );

        final Properties loaded = new Properties();
        loaded.setProperty( "fresh", "true" );
        final Properties result = cache.get( "PageB", 100L, () -> loaded );

        Assertions.assertSame( loaded, result, "Cache miss should return loader result" );
    }

    @Test
    void invalidateClearsMatchingEntry() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        cache.put( "TestPage", new Properties(), 100L );

        cache.invalidate( "TestPage" );

        final Properties loaded = new Properties();
        final Properties result = cache.get( "TestPage", 100L, () -> loaded );
        Assertions.assertSame( loaded, result, "After invalidate, get should trigger loader" );
    }
}
