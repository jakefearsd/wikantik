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
}
