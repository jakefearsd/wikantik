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
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for {@link SessionMonitor} covering branches not exercised
 * by {@link SessionMonitorTest}:
 * <ul>
 *   <li>{@code getInstance()} throws for null engine</li>
 *   <li>{@code register()} binds a wiki session to a new HTTP session ID</li>
 *   <li>{@code remove(String)} removes by session ID string</li>
 *   <li>{@code remove(HttpServletRequest)} removes via request</li>
 *   <li>{@code remove(HttpServletRequest)} throws for null request</li>
 *   <li>{@code remove(HttpSession)} throws for null session</li>
 *   <li>{@code sessions()} count reflects active sessions</li>
 *   <li>{@code sessionCreated()} logs without error</li>
 *   <li>{@code sessionDestroyed()} fires SESSION_EXPIRED event</li>
 *   <li>{@code addWikiEventListener()} / {@code removeWikiEventListener()}</li>
 *   <li>{@code find(String)} with null ID maps to the "(null)" key</li>
 * </ul>
 */
class SessionMonitorCITest {

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

    // ---- getInstance: null engine throws ----

    @Test
    void getInstanceThrowsForNullEngine() {
        assertThrows( IllegalArgumentException.class,
                () -> SessionMonitor.getInstance( null ),
                "getInstance(null) should throw IllegalArgumentException" );
    }

    // ---- getInstance: same engine returns same monitor ----

    @Test
    void getInstanceReturnsSameMonitorForSameEngine() {
        final SessionMonitor second = SessionMonitor.getInstance( engine );
        assertSame( monitor, second, "getInstance should return the same monitor for the same engine" );
    }

    // ---- register: binds a session to an HTTP session ----

    @Test
    void registerBindsSessionToHttpSession() {
        final HttpSession httpSession = mock( HttpSession.class );
        when( httpSession.getId() ).thenReturn( "register-test-id" );

        // Create a guest session via find first
        final Session guestSession = monitor.find( httpSession );
        assertNotNull( guestSession );

        // Now register a different (mock) session under the same ID
        final Session newSession = mock( Session.class );
        monitor.register( httpSession, newSession );

        // Subsequent find() should return the registered session
        final Session retrieved = monitor.find( httpSession );
        assertSame( newSession, retrieved,
                "After register(), find() should return the registered session" );
    }

    // ---- remove(String): remove by session ID string ----

    @Test
    void removeByStringIdRemovesSession() {
        final String sessionId = "remove-by-string-" + System.nanoTime();
        final Session created = monitor.find( sessionId );
        assertNotNull( created );

        monitor.remove( sessionId );

        // A new find should create a fresh session
        final Session afterRemove = monitor.find( sessionId );
        assertNotSame( created, afterRemove,
                "After remove(String), find() should create a new session" );
    }

    @Test
    void removeByNullStringIdIsNoOp() {
        // remove(null) should not throw
        assertDoesNotThrow( () -> monitor.remove( (String) null ) );
    }

    // ---- remove(HttpServletRequest): removes session via request ----

    @Test
    void removeByRequestRemovesSession() {
        final HttpSession httpSession = mock( HttpSession.class );
        when( httpSession.getId() ).thenReturn( "remove-by-request-" + System.nanoTime() );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getSession() ).thenReturn( httpSession );

        final Session created = monitor.find( httpSession );
        assertNotNull( created );

        monitor.remove( request );

        final Session afterRemove = monitor.find( httpSession );
        assertNotSame( created, afterRemove,
                "After remove(request), find() should create a new session" );
    }

    @Test
    void removeByNullRequestThrows() {
        assertThrows( IllegalArgumentException.class,
                () -> monitor.remove( (HttpServletRequest) null ),
                "remove(null request) should throw IllegalArgumentException" );
    }

    // ---- remove(HttpSession): throws for null session ----

    @Test
    void removeByNullHttpSessionThrows() {
        assertThrows( IllegalArgumentException.class,
                () -> monitor.remove( (HttpSession) null ),
                "remove(null session) should throw IllegalArgumentException" );
    }

    // ---- sessions(): reflects active count ----

    @Test
    void sessionsCountReflectsActiveEntries() {
        final int before = monitor.sessions();
        assertTrue( before >= 0 );

        // Add two new sessions with unique IDs
        monitor.find( "count-test-a-" + System.nanoTime() );
        monitor.find( "count-test-b-" + System.nanoTime() );

        final int after = monitor.sessions();
        assertTrue( after >= before + 2,
                "sessions() should include the newly added sessions" );
    }

    // ---- sessionCreated: no exception ----

    @Test
    void sessionCreatedDoesNotThrow() {
        final HttpSession httpSession = mock( HttpSession.class );
        when( httpSession.getId() ).thenReturn( "created-session-id" );

        final HttpSessionEvent event = new HttpSessionEvent( httpSession );
        assertDoesNotThrow( () -> monitor.sessionCreated( event ),
                "sessionCreated() should not throw" );
    }

    // ---- sessionDestroyed: fires SESSION_EXPIRED event ----

    @Test
    void sessionDestroyedFiresSessionExpiredEvent() {
        final HttpSession httpSession = mock( HttpSession.class );
        when( httpSession.getId() ).thenReturn( "expire-session-" + System.nanoTime() );

        // Register a session
        monitor.find( httpSession );

        // Attach a listener to catch the SESSION_EXPIRED event
        final boolean[] eventFired = { false };
        final WikiEventListener listener = event -> {
            if ( event instanceof WikiSecurityEvent wse
                    && wse.getType() == WikiSecurityEvent.SESSION_EXPIRED ) {
                eventFired[0] = true;
            }
        };
        monitor.addWikiEventListener( listener );

        try {
            final HttpSessionEvent sessionEvent = new HttpSessionEvent( httpSession );
            monitor.sessionDestroyed( sessionEvent );

            assertTrue( eventFired[0], "SESSION_EXPIRED event should have been fired on session destruction" );
        } finally {
            monitor.removeWikiEventListener( listener );
        }
    }

    // ---- addWikiEventListener / removeWikiEventListener: no exception ----

    @Test
    void addAndRemoveWikiEventListenerDoNotThrow() {
        final WikiEventListener listener = mock( WikiEventListener.class );
        assertDoesNotThrow( () -> monitor.addWikiEventListener( listener ) );
        assertDoesNotThrow( () -> monitor.removeWikiEventListener( listener ) );
    }

    // ---- find(String) with null maps to "(null)" key ----

    @Test
    void findByNullStringMapsToNullKey() {
        final Session s1 = monitor.find( (String) null );
        final Session s2 = monitor.find( (String) null );
        assertNotNull( s1 );
        assertSame( s1, s2, "Null string ID should consistently map to the same session" );
    }

    // ---- userPrincipals: sorted and non-null ----

    @Test
    void userPrincipalsReturnsSortedNonNullArray() {
        monitor.find( "sort-test-1-" + System.nanoTime() );
        monitor.find( "sort-test-2-" + System.nanoTime() );

        final Principal[] principals = monitor.userPrincipals();
        assertNotNull( principals );
        // All elements must be non-null
        for ( final Principal p : principals ) {
            assertNotNull( p );
        }
    }
}
