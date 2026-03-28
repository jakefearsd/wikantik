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
package com.wikantik.auth;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SessionMonitor}, verifying the ConcurrentHashMap-based
 * session management is thread-safe and functionally correct.
 */
class SessionMonitorTest {

    private TestEngine engine;
    private SessionMonitor monitor;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        monitor = SessionMonitor.getInstance( engine );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    /**
     * Verifies that calling find() with the same session ID returns the same Session object.
     */
    @Test
    void testFindReturnsSameSessionForSameId() {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "test-session-same-id" ).when( httpSession ).getId();

        final Session first = monitor.find( httpSession );
        final Session second = monitor.find( httpSession );

        assertNotNull( first, "First find should return non-null session" );
        assertSame( first, second, "Subsequent find with same ID should return same Session object" );
    }

    /**
     * Verifies that find() creates a guest session for an unknown session ID.
     */
    @Test
    void testFindCreatesGuestForUnknownId() {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "unknown-session-" + System.nanoTime() ).when( httpSession ).getId();

        final Session session = monitor.find( httpSession );

        assertNotNull( session, "find() should return non-null session for unknown ID" );
        assertTrue( session.isAnonymous(), "Session created for unknown ID should be a guest/anonymous session" );
    }

    /**
     * Verifies that remove() deletes the session, causing a subsequent find() to create a new one.
     */
    @Test
    void testRemoveDeletesSession() {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "session-to-remove" ).when( httpSession ).getId();

        final Session original = monitor.find( httpSession );
        assertNotNull( original );

        // Remove the session
        monitor.remove( httpSession );

        // Find again -- should get a different (new) session
        final Session afterRemove = monitor.find( httpSession );
        assertNotNull( afterRemove, "find() after remove should return a new session" );
        assertNotSame( original, afterRemove,
                "After removal, find() should create a new session, not return the old one" );
    }

    /**
     * Verifies that concurrent calls to find() do not corrupt state.
     * 10 threads each call find() 100 times with unique session IDs and
     * verify they always get back the same Session object for a given ID.
     */
    @Test
    @Timeout( 10 )
    void testConcurrentFindDoesNotCorruptState() throws Exception {
        final int threadCount = 10;
        final int iterationsPerThread = 100;
        final ExecutorService executor = Executors.newFixedThreadPool( threadCount );
        final CountDownLatch startLatch = new CountDownLatch( 1 );
        final List< Future< Boolean > > futures = new ArrayList<>();

        for ( int t = 0; t < threadCount; t++ ) {
            final int threadId = t;
            futures.add( executor.submit( () -> {
                startLatch.await();
                for ( int i = 0; i < iterationsPerThread; i++ ) {
                    final String sessionId = "concurrent-" + threadId + "-" + i;
                    final Session s1 = monitor.find( sessionId );
                    final Session s2 = monitor.find( sessionId );
                    if ( s1 != s2 ) {
                        return false; // Concurrency corruption detected
                    }
                }
                return true;
            } ) );
        }

        // Release all threads simultaneously
        startLatch.countDown();

        for ( final Future< Boolean > future : futures ) {
            assertTrue( future.get( 5, TimeUnit.SECONDS ),
                    "Concurrent find() calls must return same session for same ID" );
        }

        executor.shutdown();
        assertTrue( executor.awaitTermination( 5, TimeUnit.SECONDS ) );
    }

    /**
     * Verifies that userPrincipals() returns principals for all active sessions.
     */
    @Test
    void testUserPrincipalsReturnsAllSessions() {
        // Create multiple sessions
        final Session s1 = monitor.find( "principals-session-1" );
        final Session s2 = monitor.find( "principals-session-2" );
        final Session s3 = monitor.find( "principals-session-3" );

        assertNotNull( s1 );
        assertNotNull( s2 );
        assertNotNull( s3 );

        final Principal[] principals = monitor.userPrincipals();
        // There should be at least 3 principals (may be more from setUp or other sessions)
        assertTrue( principals.length >= 3,
                "userPrincipals() should return at least 3 principals, got " + principals.length );
    }

    /**
     * Verifies that sessionDestroyed() removes the session from the monitor.
     */
    @Test
    void testSessionDestroyedRemovesSession() {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "session-to-destroy" ).when( httpSession ).getId();

        // Create a session
        final Session original = monitor.find( httpSession );
        assertNotNull( original );

        // Simulate session destruction via the HttpSessionListener
        final HttpSessionEvent event = new HttpSessionEvent( httpSession );
        monitor.sessionDestroyed( event );

        // Find again -- should get a new session (old one was removed)
        final Session afterDestroy = monitor.find( httpSession );
        assertNotSame( original, afterDestroy,
                "After sessionDestroyed, find() should create a new session" );
    }

    /**
     * Verifies that find(String) and find(HttpSession) share the same backing map.
     */
    @Test
    void testFindByStringAndHttpSessionShareState() {
        final String sessionId = "shared-state-session";
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( sessionId ).when( httpSession ).getId();

        final Session byString = monitor.find( sessionId );
        final Session byHttp = monitor.find( httpSession );

        assertSame( byString, byHttp,
                "find(String) and find(HttpSession) with same ID should return the same Session" );
    }

    /**
     * Verifies that null session handling works correctly.
     */
    @Test
    void testFindWithNullSessionUsesNullKey() {
        final Session s1 = monitor.find( (HttpSession) null );
        final Session s2 = monitor.find( (HttpSession) null );
        assertNotNull( s1 );
        assertSame( s1, s2, "Null sessions should map to the same Session" );
    }
}
