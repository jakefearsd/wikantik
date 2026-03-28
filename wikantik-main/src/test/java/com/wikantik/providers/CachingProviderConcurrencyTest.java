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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.cache.CachingManager;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for {@link CachingProvider}. Verifies that concurrent reads
 * via {@code getAllPages()} do not deadlock and return consistent results.
 */
class CachingProviderConcurrencyTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    /**
     * 10 threads calling getAllPages() simultaneously for 2 seconds.
     * Verifies no deadlock (test completes within timeout) and all threads
     * get consistent results.
     */
    @Test
    @Timeout( 10 )
    void testConcurrentReadsDoNotDeadlock() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        engine = TestEngine.build( props );

        // Create a few test pages
        engine.saveText( "ConcurrentPage1", "Content for concurrent test 1" );
        engine.saveText( "ConcurrentPage2", "Content for concurrent test 2" );
        engine.saveText( "ConcurrentPage3", "Content for concurrent test 3" );

        final int threadCount = 10;
        final long durationMillis = 2000;
        final ExecutorService executor = Executors.newFixedThreadPool( threadCount );
        final CountDownLatch startLatch = new CountDownLatch( 1 );
        final AtomicBoolean inconsistencyDetected = new AtomicBoolean( false );
        final AtomicInteger totalIterations = new AtomicInteger( 0 );
        final List< Future< Boolean > > futures = new ArrayList<>();

        for ( int t = 0; t < threadCount; t++ ) {
            futures.add( executor.submit( () -> {
                startLatch.await();
                final long deadline = System.currentTimeMillis() + durationMillis;
                while ( System.currentTimeMillis() < deadline ) {
                    try {
                        final Collection< Page > pages = engine.getManager( PageManager.class ).getAllPages();
                        // All pages should include at least our 3 test pages
                        if ( pages.size() < 3 ) {
                            inconsistencyDetected.set( true );
                            return false;
                        }
                        totalIterations.incrementAndGet();
                    } catch ( final Exception e ) {
                        inconsistencyDetected.set( true );
                        return false;
                    }
                }
                return true;
            } ) );
        }

        // Release all threads simultaneously
        startLatch.countDown();

        for ( final Future< Boolean > future : futures ) {
            assertTrue( future.get( 8, TimeUnit.SECONDS ),
                    "Thread should complete without deadlock or inconsistency" );
        }

        executor.shutdown();
        assertTrue( executor.awaitTermination( 5, TimeUnit.SECONDS ) );

        assertFalse( inconsistencyDetected.get(),
                "No inconsistency should be detected during concurrent reads" );
        assertTrue( totalIterations.get() > 0,
                "At least some iterations should have completed" );

        // Cleanup
        engine.deleteTestPage( "ConcurrentPage1" );
        engine.deleteTestPage( "ConcurrentPage2" );
        engine.deleteTestPage( "ConcurrentPage3" );
    }

    /**
     * Tests that concurrent reads interleaved with writes do not deadlock.
     * Readers call getAllPages() while a writer saves pages.
     */
    @Test
    @Timeout( 10 )
    void testConcurrentReadWriteDoesNotDeadlock() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        engine = TestEngine.build( props );

        final int readerCount = 5;
        final long durationMillis = 2000;
        final ExecutorService executor = Executors.newFixedThreadPool( readerCount + 1 );
        final CountDownLatch startLatch = new CountDownLatch( 1 );
        final AtomicBoolean errorDetected = new AtomicBoolean( false );
        final List< Future< Boolean > > futures = new ArrayList<>();

        // Writer thread
        futures.add( executor.submit( () -> {
            startLatch.await();
            final long deadline = System.currentTimeMillis() + durationMillis;
            int counter = 0;
            while ( System.currentTimeMillis() < deadline ) {
                try {
                    final String pageName = "ConcWritePage" + ( counter % 5 );
                    engine.saveText( pageName, "Write iteration " + counter );
                    counter++;
                } catch ( final Exception e ) {
                    errorDetected.set( true );
                    return false;
                }
            }
            return true;
        } ) );

        // Reader threads
        for ( int t = 0; t < readerCount; t++ ) {
            futures.add( executor.submit( () -> {
                startLatch.await();
                final long deadline = System.currentTimeMillis() + durationMillis;
                while ( System.currentTimeMillis() < deadline ) {
                    try {
                        engine.getManager( PageManager.class ).getAllPages();
                    } catch ( final Exception e ) {
                        errorDetected.set( true );
                        return false;
                    }
                }
                return true;
            } ) );
        }

        startLatch.countDown();

        for ( final Future< Boolean > future : futures ) {
            assertTrue( future.get( 8, TimeUnit.SECONDS ),
                    "Thread should complete without deadlock" );
        }

        executor.shutdown();
        assertTrue( executor.awaitTermination( 5, TimeUnit.SECONDS ) );
        assertFalse( errorDetected.get(), "No errors should occur during concurrent read/write" );

        // Cleanup
        for ( int i = 0; i < 5; i++ ) {
            try { engine.deleteTestPage( "ConcWritePage" + i ); } catch ( final Exception e ) { /* ignore */ }
        }
    }
}
