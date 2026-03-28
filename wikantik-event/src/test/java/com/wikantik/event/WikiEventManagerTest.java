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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


public class WikiEventManagerTest {

    @Test
    public void shouldCheckRegisterUnregister() {
        final String client1 = "test1";
        final String client2 = "test2";
        final TestWikiEventListener listener = new TestWikiEventListener();
        WikiEventManager.addWikiEventListener( client1, listener );
        Assertions.assertEquals( 1, WikiEventManager.getWikiEventListeners( client1 ).size() );
        Assertions.assertTrue( WikiEventManager.isListening( client1 ) );

        WikiEventManager.removeWikiEventListener( client1, listener );
        Assertions.assertEquals( 0, WikiEventManager.getWikiEventListeners( client1 ).size() );
        Assertions.assertFalse( WikiEventManager.isListening( client1 ) );

        WikiEventManager.addWikiEventListener( client1, listener );
        WikiEventManager.addWikiEventListener( client2, listener );
        WikiEventManager.removeWikiEventListener( listener );
        Assertions.assertEquals( 0, WikiEventManager.getWikiEventListeners( client1 ).size() );
        Assertions.assertEquals( 0, WikiEventManager.getWikiEventListeners( client2 ).size() );
    }

    @Test
    public void shouldCheckAddingSameWikiEventListenerSeveralTimesOnlyGetsRegisteredOnce() {
        final String client = "test3";
        final TestWikiEventListener listener = new TestWikiEventListener();
        WikiEventManager.addWikiEventListener( client, listener );
        WikiEventManager.addWikiEventListener( client, listener );

        Assertions.assertEquals( 1, WikiEventManager.getWikiEventListeners( client ).size() );

        WikiEventManager.removeWikiEventListener( client, listener );
        Assertions.assertEquals( 0, WikiEventManager.getWikiEventListeners( client ).size() );
    }

    @Test
    public void shouldCheckEventsFiring() {
        final String client = "test4";
        final TestWikiEventListener listener = new TestWikiEventListener();
        WikiEventManager.addWikiEventListener( client, listener );
        WikiEventManager.fireEvent( client, new WikiPageEvent( "object which fires the event", WikiPageEvent.PAGE_REQUESTED, "page" ) );

        Assertions.assertEquals( 1, listener.getInvoked() );
        WikiEventManager.removeWikiEventListener( listener ); // dispose listener; if not done, listener would still be attached to test4 on other tests
    }

    /**
     * Tests that concurrent add/remove/fire operations on WikiEventManager do not throw
     * ConcurrentModificationException or produce corrupt state. Before the fix, the
     * removeDelegates() method unnecessarily synchronized on CopyOnWriteArrayList and
     * removeWikiEventListener(listener) wrapped the delegates map in an extra
     * Collections.synchronizedMap, both of which could cause contention issues.
     */
    @Test
    public void shouldHandleConcurrentAddRemoveWithoutErrors() throws Exception {
        final int threadCount = 8;
        final int iterationsPerThread = 200;
        final CyclicBarrier barrier = new CyclicBarrier( threadCount );
        final ExecutorService executor = Executors.newFixedThreadPool( threadCount );
        final List< Future< ? > > futures = new ArrayList<>();

        for( int t = 0; t < threadCount; t++ ) {
            final int threadId = t;
            futures.add( executor.submit( () -> {
                try {
                    barrier.await(); // all threads start at the same time
                    for( int i = 0; i < iterationsPerThread; i++ ) {
                        final String client = "concurrentClient-" + threadId + "-" + i;
                        final TestWikiEventListener listener = new TestWikiEventListener();

                        WikiEventManager.addWikiEventListener( client, listener );
                        WikiEventManager.fireEvent( client, new WikiPageEvent( client, WikiPageEvent.PAGE_REQUESTED, "page" ) );
                        WikiEventManager.removeWikiEventListener( client, listener );

                        // Also exercise the remove-by-listener path (the one that was wrapping in synchronizedMap)
                        final TestWikiEventListener listener2 = new TestWikiEventListener();
                        WikiEventManager.addWikiEventListener( client, listener2 );
                        WikiEventManager.removeWikiEventListener( listener2 );
                    }
                } catch( final Exception e ) {
                    throw new RuntimeException( e );
                }
            } ) );
        }

        // Collect results - any ConcurrentModificationException or deadlock will surface here
        for( final Future< ? > f : futures ) {
            f.get( 30, TimeUnit.SECONDS );
        }

        executor.shutdown();
        Assertions.assertTrue( executor.awaitTermination( 10, TimeUnit.SECONDS ) );
    }

}
