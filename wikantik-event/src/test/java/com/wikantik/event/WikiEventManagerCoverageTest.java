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

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for WikiEventManager — targets uncovered paths
 * in getWikiEventListeners, removeWikiEventListener (global), isListening,
 * fireEvent, and shutdown.
 */
class WikiEventManagerCoverageTest {

    @Test
    void testGetWikiEventListenersReturnsUnmodifiableSet() {
        final Object client = new Object();
        final WikiEventListener listener = event -> {};
        WikiEventManager.addWikiEventListener( client, listener );

        final Set<WikiEventListener> listeners = WikiEventManager.getWikiEventListeners( client );
        assertNotNull( listeners );
        assertTrue( listeners.contains( listener ) );
        assertThrows( UnsupportedOperationException.class, () -> listeners.add( event -> {} ) );

        WikiEventManager.removeWikiEventListener( client, listener );
    }

    @Test
    void testGetWikiEventListenersEmptyForNewClient() {
        final Object client = new Object();
        final Set<WikiEventListener> listeners = WikiEventManager.getWikiEventListeners( client );
        assertNotNull( listeners );
        assertTrue( listeners.isEmpty() );
    }

    @Test
    void testRemoveListenerGlobally() {
        final Object client1 = new Object();
        final Object client2 = new Object();
        final WikiEventListener shared = event -> {};

        WikiEventManager.addWikiEventListener( client1, shared );
        WikiEventManager.addWikiEventListener( client2, shared );

        assertTrue( WikiEventManager.getWikiEventListeners( client1 ).contains( shared ) );
        assertTrue( WikiEventManager.getWikiEventListeners( client2 ).contains( shared ) );

        // Remove globally
        final boolean removed = WikiEventManager.removeWikiEventListener( shared );
        assertTrue( removed );

        assertFalse( WikiEventManager.getWikiEventListeners( client1 ).contains( shared ) );
        assertFalse( WikiEventManager.getWikiEventListeners( client2 ).contains( shared ) );
    }

    @Test
    void testRemoveNonexistentListenerReturnsFalse() {
        final boolean removed = WikiEventManager.removeWikiEventListener( event -> {} );
        assertFalse( removed );
    }

    @Test
    void testIsListeningReturnsTrueWhenListenerAttached() {
        final Object client = new Object();
        assertFalse( WikiEventManager.isListening( client ) );

        final WikiEventListener listener = event -> {};
        WikiEventManager.addWikiEventListener( client, listener );
        assertTrue( WikiEventManager.isListening( client ) );

        WikiEventManager.removeWikiEventListener( client, listener );
    }

    @Test
    void testFireEventInvokesListeners() {
        final Object client = new Object();
        final AtomicInteger count = new AtomicInteger();
        final WikiEventListener listener = event -> count.incrementAndGet();

        WikiEventManager.addWikiEventListener( client, listener );
        WikiEventManager.fireEvent( client, new WikiEngineEvent( client, WikiEngineEvent.INITIALIZED ) );

        assertEquals( 1, count.get() );

        WikiEventManager.removeWikiEventListener( client, listener );
    }

    @Test
    void testFireEventWithMultipleListeners() {
        final Object client = new Object();
        final AtomicInteger count = new AtomicInteger();
        final WikiEventListener listener1 = event -> count.incrementAndGet();
        final WikiEventListener listener2 = event -> count.incrementAndGet();

        WikiEventManager.addWikiEventListener( client, listener1 );
        WikiEventManager.addWikiEventListener( client, listener2 );
        WikiEventManager.fireEvent( client, new WikiEngineEvent( client, WikiEngineEvent.SHUTDOWN ) );

        assertEquals( 2, count.get() );

        WikiEventManager.removeWikiEventListener( client, listener1 );
        WikiEventManager.removeWikiEventListener( client, listener2 );
    }

    @Test
    void testAddSameListenerTwiceReturnsFalse() {
        final Object client = new Object();
        final WikiEventListener listener = event -> {};

        assertTrue( WikiEventManager.addWikiEventListener( client, listener ) );
        assertFalse( WikiEventManager.addWikiEventListener( client, listener ) );

        WikiEventManager.removeWikiEventListener( client, listener );
    }

    @Test
    void testAddListenerWithMonitorClassReturnsFalse() {
        // c_permitMonitor is false, so adding to WikiEventManager.class should return false
        final WikiEventListener listener = event -> {};
        assertFalse( WikiEventManager.addWikiEventListener( WikiEventManager.class, listener ) );
    }

    @Test
    void testRemoveListenerWithMonitorClassReturnsTrue() {
        assertTrue( WikiEventManager.removeWikiEventListener( WikiEventManager.class, null ) );
    }

    @Test
    void testShutdownClearsAllDelegates() {
        final Object client = new Object();
        final WikiEventListener listener = event -> {};
        WikiEventManager.addWikiEventListener( client, listener );
        assertTrue( WikiEventManager.isListening( client ) );

        WikiEventManager.shutdown();
        assertFalse( WikiEventManager.isListening( client ) );
    }

    /** Marker class used only so getDelegateFor()'s Class-preload path has a client type unique to this test. */
    private static final class ProbeClient {}

    @Test
    void testUnregisterListenersForRemovesOnlyThatClient() {
        final Object client1 = new Object();
        final Object client2 = new Object();
        final WikiEventListener listener1 = event -> {};
        final WikiEventListener listener2 = event -> {};

        WikiEventManager.addWikiEventListener( client1, listener1 );
        WikiEventManager.addWikiEventListener( client2, listener2 );

        WikiEventManager.unregisterListenersFor( client1 );

        assertFalse( WikiEventManager.isListening( client1 ) );
        assertTrue( WikiEventManager.isListening( client2 ) );

        WikiEventManager.removeWikiEventListener( client2, listener2 );
    }

    @Test
    void testDelegateForClassPreloadsCacheAndIsReusedByMatchingInstance() {
        // A Class-valued client populates the preload cache (getDelegateFor's first branch).
        WikiEventManager.addWikiEventListener( ProbeClient.class, event -> {} );

        // A subsequent instance of that same class should pick up the preloaded, class-matching
        // delegate rather than creating a fresh one (getDelegateFor's preload-cache-match branch).
        final ProbeClient instance = new ProbeClient();
        final Set<WikiEventListener> listeners = WikiEventManager.getWikiEventListeners( instance );
        assertEquals( 1, listeners.size() );
    }

    @Test
    void testFireEventOnClientWithNoListenersIsNoOp() {
        // Exercises WikiEventDelegate.fireEvent()'s empty-listener early return.
        final Object client = new Object();
        assertDoesNotThrow( () ->
                WikiEventManager.fireEvent( client, new WikiEngineEvent( client, WikiEngineEvent.INITIALIZED ) ) );
    }

    @Test
    void testFireEventSwallowsThrowingListenerAndStillNotifiesOthers() {
        final Object client = new Object();
        final AtomicInteger secondListenerCalls = new AtomicInteger();
        final WikiEventListener throwing = event -> { throw new RuntimeException( "boom" ); };
        final WikiEventListener wellBehaved = event -> secondListenerCalls.incrementAndGet();

        WikiEventManager.addWikiEventListener( client, throwing );
        WikiEventManager.addWikiEventListener( client, wellBehaved );

        assertDoesNotThrow( () ->
                WikiEventManager.fireEvent( client, new WikiEngineEvent( client, WikiEngineEvent.INITIALIZED ) ) );
        assertEquals( 1, secondListenerCalls.get() );

        WikiEventManager.removeWikiEventListener( client, throwing );
        WikiEventManager.removeWikiEventListener( client, wellBehaved );
    }

    @Test
    void testFireEventNotifiesMonitorWhenSet() throws Exception {
        final Field monitorField = WikiEventManager.class.getDeclaredField( "c_monitor" );
        monitorField.setAccessible( true );
        final AtomicInteger monitorCalls = new AtomicInteger();
        final WikiEventListener monitor = event -> monitorCalls.incrementAndGet();
        monitorField.set( null, monitor );
        try {
            final Object client = new Object();
            WikiEventManager.fireEvent( client, new WikiEngineEvent( client, WikiEngineEvent.INITIALIZED ) );
            assertEquals( 1, monitorCalls.get() );
        } finally {
            monitorField.set( null, null );
        }
    }

    @Test
    void testGetWikiEventListenersOrdersDistinctListenersByComparator() {
        // With 2+ distinct listeners, the backing TreeSet must invoke
        // WikiEventListenerComparator.compare() on non-equal listeners.
        final Object client = new Object();
        final WikiEventListener listenerA = event -> {};
        final WikiEventListener listenerB = event -> {};
        WikiEventManager.addWikiEventListener( client, listenerA );
        WikiEventManager.addWikiEventListener( client, listenerB );

        final Set<WikiEventListener> listeners = WikiEventManager.getWikiEventListeners( client );
        assertEquals( 2, listeners.size() );

        WikiEventManager.removeWikiEventListener( client, listenerA );
        WikiEventManager.removeWikiEventListener( client, listenerB );
    }
}
