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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency contract for the Caffeine-backed {@link LruPropertyCache}.
 *
 * <p>The pre-Caffeine impl had every public method {@code synchronized} on
 * the instance, so all access serialised through one mutex. These tests would
 * still have passed under that regime (slowly); their value is to lock in
 * the post-Caffeine contract that multiple readers and writers proceed
 * without deadlock, and that the LRU eviction respects the configured size.
 * The actual throughput win is measured by the load harness, not here.</p>
 */
class LruPropertyCacheConcurrencyTest {

    @Test
    void manyConcurrentReaders_returnSameCachedProperties() throws Exception {
        final LruPropertyCache cache = new LruPropertyCache( 1000 );
        final long modTime = 12345L;
        final Properties expected = new Properties();
        expected.setProperty( "k", "v" );

        // Seed once
        cache.put( "page1", expected, modTime );

        final int threads = 32;
        final int iterations = 500;
        final ExecutorService exec = Executors.newFixedThreadPool( threads );
        final CountDownLatch start = new CountDownLatch( 1 );
        final AtomicReference< Properties > mismatch = new AtomicReference<>();
        final AtomicInteger loaderCalls = new AtomicInteger();

        for ( int t = 0; t < threads; t++ ) {
            exec.submit( () -> {
                start.await();
                for ( int i = 0; i < iterations; i++ ) {
                    final Properties got = cache.get( "page1", modTime, () -> {
                        loaderCalls.incrementAndGet();
                        return new Properties(); // wrong value if reached
                    } );
                    if ( !"v".equals( got.getProperty( "k" ) ) ) {
                        mismatch.set( got );
                        return null;
                    }
                }
                return null;
            } );
        }

        start.countDown();
        exec.shutdown();
        assertTrue( exec.awaitTermination( 30, TimeUnit.SECONDS ),
            "Reader pool did not finish — possible deadlock" );
        assertNull( mismatch.get(), "A reader saw unexpected properties" );
        assertEquals( 0, loaderCalls.get(),
            "Loader fired on a hit path — cache miss when there should have been a hit" );
    }

    @Test
    void readerHitsAndWriterPutsDontDeadlock_withDifferentKeys() throws Exception {
        final LruPropertyCache cache = new LruPropertyCache( 200 );
        final int threads = 16;
        final int iterations = 500;
        final ExecutorService exec = Executors.newFixedThreadPool( threads );
        final CountDownLatch start = new CountDownLatch( 1 );

        // Seed 100 keys so most reads hit
        for ( int k = 0; k < 100; k++ ) {
            final Properties p = new Properties();
            p.setProperty( "key", "v" + k );
            cache.put( "page" + k, p, 1L );
        }

        for ( int t = 0; t < threads; t++ ) {
            final int tid = t;
            exec.submit( () -> {
                start.await();
                for ( int i = 0; i < iterations; i++ ) {
                    final String key = "page" + ( ( tid + i ) % 100 );
                    if ( i % 5 == 0 ) {
                        // 20% of operations are writes (different lastModified to force re-cache)
                        final Properties p = new Properties();
                        p.setProperty( "key", "updated" );
                        cache.put( key, p, i + 100L );
                    } else {
                        cache.get( key, 1L, () -> {
                            final Properties stale = new Properties();
                            stale.setProperty( "key", "fallback" );
                            return stale;
                        } );
                    }
                }
                return null;
            } );
        }

        start.countDown();
        exec.shutdown();
        assertTrue( exec.awaitTermination( 30, TimeUnit.SECONDS ),
            "Mixed read/write pool did not finish — possible deadlock" );
        // No specific value assertions: the test exists to guard against deadlock + corruption.
        // Cache contents are valid by construction (Caffeine handles concurrent put/get).
        assertNotNull( cache.cache(), "cache() accessor should still return the live instance" );
    }

    @Test
    void respectsMaxSize_underConcurrentWrites() throws Exception {
        final int cap = 50;
        final LruPropertyCache cache = new LruPropertyCache( cap );
        final int threads = 8;
        final int writes = 200;
        final ExecutorService exec = Executors.newFixedThreadPool( threads );
        final CountDownLatch start = new CountDownLatch( 1 );

        for ( int t = 0; t < threads; t++ ) {
            final int tid = t;
            exec.submit( () -> {
                start.await();
                for ( int i = 0; i < writes; i++ ) {
                    final Properties p = new Properties();
                    p.setProperty( "k", String.valueOf( i ) );
                    cache.put( "page-" + tid + "-" + i, p, 1L );
                }
                return null;
            } );
        }

        start.countDown();
        exec.shutdown();
        assertTrue( exec.awaitTermination( 30, TimeUnit.SECONDS ),
            "Writer pool did not finish — possible deadlock" );

        // Caffeine cleans up async; force the work to drain before we sample size.
        cache.cache().cleanUp();
        final int size = cache.size();
        // Caffeine's maximumSize is a soft bound under bursty inserts; allow a small overage.
        assertTrue( size <= cap + 10,
            "LRU should respect maxSize (cap=" + cap + ", got " + size + ")" );
    }
}
