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

import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PropertyCacheStrategy implementations.
 */
class PropertyCacheStrategyTest {

    // ============== SingleEntryPropertyCache Tests ==============

    @Test
    void testSingleEntryCacheHit() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        final Properties props1 = cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            final Properties p = new Properties();
            p.setProperty( "key", "value1" );
            return p;
        });

        assertEquals( 1, loadCount.get(), "Should load on first access" );
        assertEquals( "value1", props1.getProperty( "key" ) );

        // Access same page again - should hit cache
        final Properties props2 = cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 1, loadCount.get(), "Should not reload on cache hit" );
        assertEquals( "value1", props2.getProperty( "key" ) );
    }

    @Test
    void testSingleEntryCacheMissOnDifferentPage() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        // Load page1
        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 1, loadCount.get() );

        // Access page2 - should miss and evict page1
        cache.get( "page2", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 2, loadCount.get(), "Should load on cache miss" );

        // Access page1 again - should miss since it was evicted
        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 3, loadCount.get(), "Should reload evicted page" );
    }

    @Test
    void testSingleEntryCacheMissOnModifiedFile() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        // Load page at time 1000
        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 1, loadCount.get() );

        // Access same page but with updated modification time
        cache.get( "page1", 2000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 2, loadCount.get(), "Should reload when file modified" );
    }

    @Test
    void testSingleEntryCacheInvalidate() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 1, loadCount.get() );

        cache.invalidate( "page1" );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 2, loadCount.get(), "Should reload after invalidate" );
    }

    @Test
    void testSingleEntryCacheClear() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        cache.clear();

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 2, loadCount.get(), "Should reload after clear" );
    }

    @Test
    void testSingleEntryCachePut() {
        final SingleEntryPropertyCache cache = new SingleEntryPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        final Properties preCached = new Properties();
        preCached.setProperty( "precached", "true" );
        cache.put( "page1", preCached, 1000L );

        final Properties result = cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 0, loadCount.get(), "Should use pre-cached value" );
        assertEquals( "true", result.getProperty( "precached" ) );
    }

    // ============== LruPropertyCache Tests ==============

    @Test
    void testLruCacheHit() {
        final LruPropertyCache cache = new LruPropertyCache( 10 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        final Properties props1 = cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            final Properties p = new Properties();
            p.setProperty( "key", "value1" );
            return p;
        });

        assertEquals( 1, loadCount.get() );

        final Properties props2 = cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 1, loadCount.get(), "Should hit cache" );
        assertEquals( "value1", props2.getProperty( "key" ) );
    }

    @Test
    void testLruCacheMultiplePages() {
        final LruPropertyCache cache = new LruPropertyCache( 10 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        // Load multiple pages
        for ( int i = 0; i < 5; i++ ) {
            final int page = i;
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }

        assertEquals( 5, loadCount.get() );
        assertEquals( 5, cache.size() );

        // All pages should be cached
        for ( int i = 0; i < 5; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }

        assertEquals( 5, loadCount.get(), "All pages should hit cache" );
    }

    @Test
    void testLruCacheEviction() {
        final LruPropertyCache cache = new LruPropertyCache( 3 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        // Fill the cache
        for ( int i = 0; i < 3; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }

        assertEquals( 3, loadCount.get() );
        assertEquals( 3, cache.size() );

        // Add one more - should evict oldest
        cache.get( "page3", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 4, loadCount.get() );
        assertEquals( 3, cache.size(), "Size should not exceed max" );

        // page0 should have been evicted (LRU)
        cache.get( "page0", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 5, loadCount.get(), "Evicted page should be reloaded" );
    }

    @Test
    void testLruCacheLruOrder() {
        final LruPropertyCache cache = new LruPropertyCache( 3 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        // Fill cache with pages 0, 1, 2
        for ( int i = 0; i < 3; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }

        // Access page0 to make it most recently used
        cache.get( "page0", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        // Add page3 - should evict page1 (now oldest)
        cache.get( "page3", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 4, loadCount.get() );

        // page0 should still be in cache
        cache.get( "page0", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 4, loadCount.get(), "page0 should still be cached" );

        // page1 should have been evicted
        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 5, loadCount.get(), "page1 should have been evicted" );
    }

    @Test
    void testLruCacheModifiedFile() {
        final LruPropertyCache cache = new LruPropertyCache( 10 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        // Access with newer modification time
        cache.get( "page1", 2000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 2, loadCount.get(), "Should reload when file modified" );
    }

    @Test
    void testLruCacheInvalidate() {
        final LruPropertyCache cache = new LruPropertyCache( 10 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        cache.invalidate( "page1" );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 2, loadCount.get(), "Should reload after invalidate" );
    }

    @Test
    void testLruCacheClear() {
        final LruPropertyCache cache = new LruPropertyCache( 10 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        for ( int i = 0; i < 5; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }

        assertEquals( 5, cache.size() );

        cache.clear();

        assertEquals( 0, cache.size(), "Cache should be empty after clear" );
    }

    @Test
    void testLruCacheInvalidSize() {
        assertThrows( IllegalArgumentException.class, () -> new LruPropertyCache( 0 ) );
        assertThrows( IllegalArgumentException.class, () -> new LruPropertyCache( -1 ) );
    }

    // ============== NoOpPropertyCache Tests ==============

    @Test
    void testNoOpCacheAlwaysLoads() {
        final NoOpPropertyCache cache = new NoOpPropertyCache();
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 3, loadCount.get(), "NoOp cache should always load" );
    }

    @Test
    void testNoOpCacheOperationsAreNoOp() {
        final NoOpPropertyCache cache = new NoOpPropertyCache();

        // These should not throw
        cache.invalidate( "page1" );
        cache.clear();
        cache.put( "page1", new Properties(), 1000L );

        final AtomicInteger loadCount = new AtomicInteger( 0 );
        cache.get( "page1", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });

        assertEquals( 1, loadCount.get(), "Put should not affect NoOp cache" );
    }
}
