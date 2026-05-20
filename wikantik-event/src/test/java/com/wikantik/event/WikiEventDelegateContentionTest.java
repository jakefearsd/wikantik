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
package com.wikantik.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency-focused tests for the post-2026-05-20 WikiEventDelegate
 * rewrite (WeakHashMap-backed storage, fireEvent dispatching outside the
 * lock). Sibling to {@code WikiEngineEventTest} which covers the
 * single-threaded contract.
 */
class WikiEventDelegateContentionTest {

    /** Trivial listener that counts the events it received. */
    private static final class CountingListener implements WikiEventListener {
        final AtomicInteger fires = new AtomicInteger();
        @Override
        public void actionPerformed( final WikiEvent event ) {
            fires.incrementAndGet();
        }
    }

    @Test
    void concurrentAddOfSameInstanceLeavesOneEntry() throws Exception {
        final Object client = new Object();  // arbitrary client key
        final CountingListener listener = new CountingListener();
        final int threadCount = 32;
        final int callsPerThread = 64;

        final ExecutorService pool = Executors.newFixedThreadPool( threadCount );
        final CountDownLatch ready = new CountDownLatch( threadCount );
        final CountDownLatch go = new CountDownLatch( 1 );
        try {
            for ( int i = 0; i < threadCount; i++ ) {
                pool.submit( () -> {
                    ready.countDown();
                    try { go.await(); } catch ( final InterruptedException e ) { Thread.currentThread().interrupt(); return; }
                    for ( int n = 0; n < callsPerThread; n++ ) {
                        WikiEventManager.addWikiEventListener( client, listener );
                    }
                } );
            }
            assertTrue( ready.await( 5, TimeUnit.SECONDS ), "workers should be ready" );
            go.countDown();
            pool.shutdown();
            assertTrue( pool.awaitTermination( 30, TimeUnit.SECONDS ), "workers should finish" );

            // Despite 32 × 64 = 2048 concurrent add calls, exactly one listener entry exists.
            assertEquals( 1, WikiEventManager.getWikiEventListeners( client ).size() );

            // And a single fired event lands exactly once on the (single) listener.
            WikiEventManager.fireEvent( client, new WikiEngineEvent( client, 0 ) );
            assertEquals( 1, listener.fires.get() );
        } finally {
            WikiEventManager.removeWikiEventListener( client, listener );
            pool.shutdownNow();
        }
    }

    @Test
    void fireEventDispatchesOutsideLockAllowingReentrantRegistration() throws Exception {
        final Object client = new Object();
        final CountingListener observer = new CountingListener();
        final CountingListener lateArrival = new CountingListener();

        // A listener whose callback registers a SECOND listener on the same client.
        // Under the old code (lock held through dispatch), this re-entrant
        // registration would race with the surrounding iteration and trip
        // ConcurrentModificationException. The new code snapshots under the
        // lock then dispatches outside, so the re-entrant registration takes
        // effect cleanly for SUBSEQUENT fires.
        final WikiEventListener registerer = new WikiEventListener() {
            boolean done = false;
            @Override
            public void actionPerformed( final WikiEvent event ) {
                if ( !done ) {
                    done = true;
                    WikiEventManager.addWikiEventListener( client, lateArrival );
                }
                observer.actionPerformed( event );
            }
        };

        try {
            WikiEventManager.addWikiEventListener( client, registerer );

            // First fire — registerer runs, registers lateArrival.
            WikiEventManager.fireEvent( client, new WikiEngineEvent( client, 0 ) );
            assertEquals( 1, observer.fires.get(),
                "registerer should observe the first fire" );
            assertEquals( 0, lateArrival.fires.get(),
                "lateArrival shouldn't see the fire that registered it" );

            // Second fire — lateArrival is now subscribed.
            WikiEventManager.fireEvent( client, new WikiEngineEvent( client, 0 ) );
            assertEquals( 2, observer.fires.get() );
            assertEquals( 1, lateArrival.fires.get() );
        } finally {
            WikiEventManager.removeWikiEventListener( client, registerer );
            WikiEventManager.removeWikiEventListener( client, lateArrival );
        }
    }

    @Test
    void weakReferenceCleanupAfterGc() throws Exception {
        final Object client = new Object();
        // Strong reference scoped to a block — after the block exits the listener
        // is only referenced by the WeakHashMap's internal weak key.
        {
            final CountingListener temp = new CountingListener();
            WikiEventManager.addWikiEventListener( client, temp );
            assertTrue( WikiEventManager.getWikiEventListeners( client ).contains( temp ) );
        }

        // Encourage GC. WeakHashMap relies on key clearing happening before
        // the next map operation observes the cleared queue. We loop a few
        // times with both gc() and a short sleep — System.gc() is advisory but
        // generally takes effect within a couple of cycles in OpenJDK.
        boolean cleared = false;
        for ( int attempt = 0; attempt < 40 && !cleared; attempt++ ) {
            System.gc();
            Thread.sleep( 50 );
            cleared = WikiEventManager.getWikiEventListeners( client ).isEmpty();
        }
        assertTrue( cleared, "WeakHashMap should have cleared the listener after GC" );
    }
}
