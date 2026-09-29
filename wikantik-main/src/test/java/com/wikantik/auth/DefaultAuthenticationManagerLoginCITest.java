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
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.sso.SSOConfig;
import com.wikantik.auth.sso.SSOConfigHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultAuthenticationManager} branches reached only through the
 * request-driven {@code login(HttpServletRequest)}/{@code logout(HttpServletRequest)}
 * surface and session-fixation defense ({@code rotateSessionId}) — none of which any
 * existing wikantik-main test exercises directly. Uses a real {@link TestEngine} because
 * these paths thread through {@code SessionMonitor}/{@code WikiSession} plumbing that
 * isn't worth re-mocking.
 */
class DefaultAuthenticationManagerLoginCITest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        // A fresh TestEngine per test isolates SessionMonitor state (keyed per-engine),
        // even though HttpMockFactory hands out requests sharing one default mock session id.
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterEach
    void tearDown() {
        SSOConfigHolder.removeConfig( engine );
    }

    // --- login(HttpServletRequest): null-session guard on the (Session, request, user, pass) overload ---

    @Test
    void login_nullSession_returnsFalseWithoutThrowing() throws Exception {
        final AuthenticationManager auth = engine.getManager( AuthenticationManager.class );
        assertFalse( auth.login( null, null, "someone", "irrelevant" ) );
    }

    // --- login(HttpServletRequest): SSO branch is attempted when SSO is enabled for this engine ---

    @Test
    void login_httpRequest_withSsoEnabled_attemptsSsoLoginModuleWithoutThrowing() throws Exception {
        final Properties ssoProps = new Properties();
        ssoProps.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        final SSOConfig ssoConfig = new SSOConfig( ssoProps, "http://localhost/sso/callback" );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final AuthenticationManager auth = engine.getManager( AuthenticationManager.class );
        // The SSO login module will fail cleanly (no pac4j profile in the mock session) and
        // the flow falls through to container/cookie/anonymous login — must never throw.
        assertDoesNotThrow( () -> auth.login( request ) );
    }

    // --- logout(HttpServletRequest) ---

    @Test
    void logout_withNullRequest_doesNotThrow() {
        final AuthenticationManager auth = engine.getManager( AuthenticationManager.class );
        assertDoesNotThrow( () -> auth.logout( null ) );
    }

    @Test
    void logout_invalidatesSessionAndRemovesItFromCache() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = WikiSession.getWikiSession( engine, request );
        final AuthenticationManager auth = engine.getManager( AuthenticationManager.class );
        assertTrue( auth.login( session, request, Users.JANNE, Users.JANNE_PASS ), "precondition: login must succeed" );
        assertTrue( session.isAuthenticated() );

        auth.logout( request );

        assertFalse( session.isAuthenticated(), "the original session object must be invalidated" );
        // A fresh lookup for the same request must no longer return the invalidated session —
        // it was removed from the WikiSession cache.
        final Session postLogout = Wiki.session().find( engine, request );
        assertFalse( postLogout.isAuthenticated(), "logout must remove the cached session, not reuse the invalidated one" );
    }

    // --- rotateSessionId (private, exercised via the successful login(Session, request, user, pass) path) ---

    @Test
    void login_whenRequestHasNoActiveHttpSession_skipsRotationWithoutThrowing() throws Exception {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getSession( false ) ).thenReturn( null );
        final Session session = WikiSession.guestSession( engine );

        final AuthenticationManager auth = engine.getManager( AuthenticationManager.class );
        assertTrue( auth.login( session, request, Users.JANNE, Users.JANNE_PASS ) );
    }

    @Test
    void login_whenSessionIdChangeThrowsIllegalState_completesLoginWithoutThrowing() throws Exception {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpSession httpSession = mock( HttpSession.class );
        when( httpSession.getId() ).thenReturn( "some-session-id" );
        when( request.getSession( false ) ).thenReturn( httpSession );
        when( request.changeSessionId() ).thenThrow( new IllegalStateException( "no active session to rotate" ) );
        final Session session = WikiSession.guestSession( engine );

        final AuthenticationManager auth = engine.getManager( AuthenticationManager.class );
        assertTrue( auth.login( session, request, Users.JANNE, Users.JANNE_PASS ),
                "a session-ID-rotation failure must not fail the login itself" );
    }
}
