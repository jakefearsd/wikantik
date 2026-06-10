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
package com.wikantik.rest;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.SessionSPI;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MustChangePasswordFilter} — the filter that blocks
 * flagged users on /api/* and /admin/* except for the auth surface.
 */
class MustChangePasswordFilterTest {

    private TestEngine engine;
    private MustChangePasswordFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );

        filter = new MustChangePasswordFilter();
        final FilterConfig filterConfig = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( filterConfig ).getServletContext();
        filter.init( filterConfig );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    // ----- helpers -----

    /** A mock Session that is NOT authenticated. */
    private static Session anonSession() {
        final Session s = Mockito.mock( Session.class );
        Mockito.when( s.isAuthenticated() ).thenReturn( false );
        return s;
    }

    /** A mock Session that IS authenticated for the given login name. */
    private static Session authedSession( final String login ) {
        final Session s = Mockito.mock( Session.class );
        Mockito.when( s.isAuthenticated() ).thenReturn( true );
        final Principal p = Mockito.mock( Principal.class );
        Mockito.when( p.getName() ).thenReturn( login );
        Mockito.when( s.getLoginPrincipal() ).thenReturn( p );
        return s;
    }

    /**
     * Stub Wiki.session().find(...) to return the given session.
     * Must be used in a try-with-resources block.
     */
    private static MockedStatic<Wiki> stubWikiSession( final Session session ) {
        final MockedStatic<Wiki> wiki = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );
        final SessionSPI spi = Mockito.mock( SessionSPI.class );
        Mockito.when( spi.find( any(), any() ) ).thenReturn( session );
        wiki.when( Wiki::session ).thenReturn( spi );
        return wiki;
    }

    /**
     * Build a GET request to the given path with the given Accept header.
     * Uses a unique HttpSession id to avoid the shared "mock-session" pollution.
     */
    private static HttpServletRequest buildRequest( final String path, final String accept,
                                                    final Boolean passwordMustChange ) {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "mcpf-test-" + System.nanoTime() ).when( httpSession ).getId();

        // Wire up session attribute storage so PasswordChangeGate.cache() + read work.
        final java.util.Map<String, Object> attrs = new java.util.concurrent.ConcurrentHashMap<>();
        Mockito.doAnswer( inv -> {
            attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) );
            return null;
        } ).when( httpSession ).setAttribute( anyString(), any() );
        Mockito.doAnswer( inv -> attrs.get( inv.<String>getArgument( 0 ) ) )
               .when( httpSession ).getAttribute( anyString() );

        if ( passwordMustChange != null ) {
            attrs.put( PasswordChangeGate.SESSION_ATTRIBUTE, passwordMustChange );
        }

        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/JSPWiki", path );
        Mockito.doReturn( "GET" ).when( req ).getMethod();
        if ( accept != null ) {
            Mockito.doReturn( accept ).when( req ).getHeader( "Accept" );
        }
        Mockito.doReturn( httpSession ).when( req ).getSession();
        Mockito.doReturn( httpSession ).when( req ).getSession( Mockito.anyBoolean() );
        return req;
    }

    // ----- tests -----

    /**
     * 1. Unauthenticated session → chain called, no 403.
     */
    @Test
    void anonymousRequestPassesThrough() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/pages", "application/json", null );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        try ( MockedStatic<Wiki> w = stubWikiSession( anonSession() ) ) {
            filter.doFilter( req, resp, chain );
        }

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    /**
     * 2. Authenticated user whose flag is false → chain called.
     */
    @Test
    void authenticatedUnflaggedUserPassesThrough() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/pages", "application/json", Boolean.FALSE );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        try ( MockedStatic<Wiki> w = stubWikiSession( authedSession( "unflaggeduser" ) ) ) {
            filter.doFilter( req, resp, chain );
        }

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    /**
     * 3. Authenticated user with flag=true, GET /api/pages with Accept: application/json
     *    → chain NOT called; status 403; body has "code":"PASSWORD_CHANGE_REQUIRED" and "error":true.
     */
    @Test
    void flaggedUserIsRejectedWithStructuredCode() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/pages", "application/json", Boolean.TRUE );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        try ( MockedStatic<Wiki> w = stubWikiSession( authedSession( "flaggeduser" ) ) ) {
            filter.doFilter( req, resp, chain );
        }

        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setStatus( HttpServletResponse.SC_FORBIDDEN );

        final String body = sw.toString();
        assertTrue( body.contains( "\"code\":\"PASSWORD_CHANGE_REQUIRED\"" ),
                "body must contain structured code, got: " + body );
        assertTrue( body.contains( "\"error\":true" ),
                "body must contain error:true, got: " + body );
        assertTrue( body.contains( "\"message\":\"You must change your password before continuing\"" ),
                "body must contain message text, got: " + body );
    }

    /**
     * 4. Flagged user, request URI /api/auth/profile → chain called (exempt prefix).
     */
    @Test
    void flaggedUserMayStillCallAuthEndpoints() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/auth/profile", "application/json", Boolean.TRUE );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        try ( MockedStatic<Wiki> w = stubWikiSession( authedSession( "flaggeduser" ) ) ) {
            filter.doFilter( req, resp, chain );
        }

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    /**
     * 5. Flagged user, GET with Accept: text/html (SPA navigation) → chain called.
     */
    @Test
    void spaNavigationPassesThrough() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/pages", "text/html,application/xhtml+xml", Boolean.TRUE );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        // Wiki.session() should not even be consulted — SPA navigation short-circuits
        filter.doFilter( req, resp, chain );

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    /**
     * 6. OPTIONS request to any gated path → chain called without ever consulting
     *    the session SPI (pre-flight short-circuit).
     */
    @Test
    void optionsRequestPassesThroughWithoutSessionLookup() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/pages", "application/json", null );
        Mockito.doReturn( "OPTIONS" ).when( req ).getMethod();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        // No Wiki.session() stub — if the filter ever calls it the mock will throw
        filter.doFilter( req, resp, chain );

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    /**
     * 7. Flagged user, GET /api/health → exempt prefix → chain called, no 403.
     */
    @Test
    void healthEndpointIsExemptForFlaggedUser() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/health", "application/json", Boolean.TRUE );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        // isExempt() short-circuits before Wiki.session() is consulted
        filter.doFilter( req, resp, chain );

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    /**
     * 8. Flagged user, DELETE /api/auth/profile (account self-deletion) →
     *    NOT exempt — chain not called, status 403,
     *    body contains "code":"PASSWORD_CHANGE_REQUIRED".
     */
    @Test
    void flaggedUserCannotSelfDeleteViaAuthSurface() throws Exception {
        final HttpServletRequest req = buildRequest( "/api/auth/profile", "application/json", Boolean.TRUE );
        Mockito.doReturn( "DELETE" ).when( req ).getMethod();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        final FilterChain chain = Mockito.mock( FilterChain.class );

        try ( MockedStatic<Wiki> w = stubWikiSession( authedSession( "flaggeduser" ) ) ) {
            filter.doFilter( req, resp, chain );
        }

        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setStatus( HttpServletResponse.SC_FORBIDDEN );

        final String body = sw.toString();
        assertTrue( body.contains( "\"code\":\"PASSWORD_CHANGE_REQUIRED\"" ),
                "body must contain structured code, got: " + body );
    }
}
