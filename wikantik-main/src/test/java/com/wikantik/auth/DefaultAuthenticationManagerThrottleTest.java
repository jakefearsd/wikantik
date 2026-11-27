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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiSecurityEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brute-force throttle's bookkeeping (failed attempts are counted per login name, a success clears them), the
 * LOGIN_FAILED / LOGOUT events, and what logout tears down.
 */
class DefaultAuthenticationManagerThrottleTest {

    private TestEngine engine;
    private DefaultAuthenticationManager auth;
    /** Strong reference: the event manager holds listeners weakly. */
    private final List< WikiSecurityEvent > events = new ArrayList<>();
    private final WikiEventListener listener = ( final WikiEvent e ) -> {
        if( e instanceof WikiSecurityEvent se ) {
            events.add( se );
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        auth = ( DefaultAuthenticationManager ) engine.getManager( AuthenticationManager.class );
        WikiEventManager.addWikiEventListener( auth, listener );
    }

    private Session sessionFor( final HttpServletRequest request ) {
        return WikiSession.getWikiSession( engine, request );
    }

    @Test
    void eachFailedPasswordLoginIsCountedAgainstThatLoginNameOnly() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = sessionFor( request );

        assertEquals( 0, auth.loginAttemptCount( Users.JANNE ) );
        assertFalse( auth.login( session, request, Users.JANNE, "wrong-1" ) );
        assertEquals( 1, auth.loginAttemptCount( Users.JANNE ) );
        assertFalse( auth.login( session, request, Users.JANNE, "wrong-2" ) );
        assertEquals( 2, auth.loginAttemptCount( Users.JANNE ) );
        assertEquals( 0, auth.loginAttemptCount( Users.ADMIN ), "another login name is not penalised" );
    }

    @Test
    void aSuccessfulLoginClearsThePriorFailuresOfThatLoginNameButNotOtherNames() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = sessionFor( request );
        auth.login( session, request, Users.JANNE, "wrong-1" );
        auth.login( session, request, Users.JANNE, "wrong-2" );
        auth.login( session, request, Users.ADMIN, "wrong-admin" );
        assertEquals( 2, auth.loginAttemptCount( Users.JANNE ) );

        assertTrue( auth.login( session, request, Users.JANNE, Users.JANNE_PASS ) );

        assertEquals( 0, auth.loginAttemptCount( Users.JANNE ) );
        assertEquals( 1, auth.loginAttemptCount( Users.ADMIN ), "only the successful user's bucket is cleared" );
    }

    @Test
    void aFailedPasswordLoginFiresLoginFailedNamingTheAttemptedLogin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();

        assertFalse( auth.login( sessionFor( request ), request, Users.JANNE, "wrong" ) );

        final List< WikiSecurityEvent > failed = events.stream()
                .filter( e -> e.getType() == WikiSecurityEvent.LOGIN_FAILED ).toList();
        assertEquals( 1, failed.size() );
        assertEquals( Users.JANNE, ( ( java.security.Principal ) failed.get( 0 ).getPrincipal() ).getName() );
    }

    @Test
    void anAnonymousVisitorLoginFiresLoginAnonymous() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();

        assertTrue( auth.login( request ) );

        assertTrue( events.stream().anyMatch( e -> e.getType() == WikiSecurityEvent.LOGIN_ANONYMOUS ),
                "events: " + events );
    }

    @Test
    void logoutInvalidatesTheHttpSessionAndFiresLogoutNamingTheDepartingUser() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession http = request.getSession();
        final Session session = sessionFor( request );
        assertTrue( auth.login( session, request, Users.JANNE, Users.JANNE_PASS ) );
        events.clear();

        auth.logout( request );

        Mockito.verify( http ).invalidate();
        final List< WikiSecurityEvent > logouts = events.stream()
                .filter( e -> e.getType() == WikiSecurityEvent.LOGOUT ).toList();
        assertEquals( 1, logouts.size() );
        assertEquals( Users.JANNE, ( ( java.security.Principal ) logouts.get( 0 ).getPrincipal() ).getName() );
    }

    @Test
    void cookieAssertionsAndCookieAuthenticationFollowTheirProperties() throws Exception {
        final java.util.Properties on = TestEngine.getTestProperties();
        on.setProperty( AuthenticationManager.PROP_ALLOW_COOKIE_ASSERTIONS, "false" );
        on.setProperty( AuthenticationManager.PROP_ALLOW_COOKIE_AUTH, "true" );
        final DefaultAuthenticationManager m = ( DefaultAuthenticationManager )
                new TestEngine( on ).getManager( AuthenticationManager.class );

        assertFalse( m.allowsCookieAssertions() );
        assertTrue( m.allowsCookieAuthentication() );
    }
}
