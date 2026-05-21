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

        // Add one more — capacity is exceeded; eviction is async under Caffeine.
        cache.get( "page3", 1000L, () -> {
            loadCount.incrementAndGet();
            return new Properties();
        });
        assertEquals( 4, loadCount.get() );

        // Force Caffeine to run its eviction policy synchronously so we can
        // observe the bounded size. Without cleanUp(), the maintenance task
        // runs on the common pool and may not have settled by the next assert.
        cache.cache().cleanUp();
        assertEquals( 3, cache.size(), "Size should respect the configured max after cleanUp" );

        // Some prior entry has been evicted; loading it again forces a fresh
        // load. Caffeine uses Window TinyLFU rather than strict LRU, so we
        // don't assert WHICH prior entry was evicted — only that the cap is
        // honoured and that one prior entry is now a miss.
        final int beforeReload = loadCount.get();
        for ( int i = 0; i < 4; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }
        cache.cache().cleanUp();
        assertTrue( loadCount.get() > beforeReload,
            "At least one prior entry should have been evicted and re-loaded" );
        assertEquals( 3, cache.size(), "Size still respects the configured max" );
    }

    @Test
    void testLruCacheEvictsWhenOverCapacity() {
        // Caffeine uses Window TinyLFU rather than strict LRU. The interface
        // contract is "bounded cache + evict least-valuable entry under
        // pressure" — NOT "evict in strict access-order". We assert the bound
        // is respected and that filling past capacity does cause evictions,
        // without prescribing which specific entry gets evicted (TinyLFU's
        // small-sample warmup makes exact entry prediction unreliable for
        // tiny test fixtures).
        final LruPropertyCache cache = new LruPropertyCache( 3 );
        final AtomicInteger loadCount = new AtomicInteger( 0 );

        // Insert 10 distinct pages into a cache sized for 3.
        for ( int i = 0; i < 10; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }
        cache.cache().cleanUp();

        assertEquals( 10, loadCount.get(),
            "Every distinct page should have fired the loader on first access" );
        assertTrue( cache.size() <= 3,
            "Cache size should respect the configured max (got " + cache.size() + ")" );

        // Re-access each page; some hits, some misses — but the misses force
        // re-loads, confirming that earlier inserts were genuinely evicted.
        for ( int i = 0; i < 10; i++ ) {
            cache.get( "page" + i, 1000L, () -> {
                loadCount.incrementAndGet();
                return new Properties();
            });
        }
        assertTrue( loadCount.get() > 10,
            "Some prior entries should have been evicted and required re-loading; "
            + "loader fired " + ( loadCount.get() - 10 ) + " additional time(s)" );
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
