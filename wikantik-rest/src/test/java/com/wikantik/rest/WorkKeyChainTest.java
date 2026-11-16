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

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.EngineSPI;
import com.wikantik.api.spi.SessionSPI;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real {@link WorkApiKeyFilter}, {@link RememberMeAuthFilter} and {@link MustChangePasswordFilter} in the order
 * {@code web.xml} runs them. A key request must never be turned into a login session of the key's owner (no
 * principal, no HttpSession, no login), and the cookie-based filters must not act on it at all.
 */
@RequiresPostgres
class WorkKeyChainTest {

    private MockedStatic< Wiki > wikiStatic;
    private AuthenticationManager authMgr;
    private Session session;
    private ApiKeyService keys;
    private WorkApiKeyFilter keyFilter;
    private RememberMeAuthFilter rememberMe;
    private MustChangePasswordFilter mustChange;
    private HttpSession httpSession;
    private HttpServletResponse resp;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        PostgresTestDb.createDataSource();
        keys = new ApiKeyService( PostgresTestDb.createDataSource() );
        authMgr = mock( AuthenticationManager.class );
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        session = mock( Session.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getAuthSubsystem() ).thenReturn(
            new AuthSubsystem.Services( authMgr, null, null, null, null, null, null, null, null ) );
        wikiStatic = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );
        final EngineSPI engineSpi = mock( EngineSPI.class );
        when( engineSpi.find( any( ServletContext.class ), any() ) ).thenReturn( engine );
        wikiStatic.when( Wiki::engine ).thenReturn( engineSpi );
        final SessionSPI sessionSpi = mock( SessionSPI.class );
        when( sessionSpi.find( any(), any() ) ).thenReturn( session );
        wikiStatic.when( Wiki::session ).thenReturn( sessionSpi );
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getServletContext() ).thenReturn( mock( ServletContext.class ) );
        keyFilter = new WorkApiKeyFilter( () -> keys, () -> true );
        rememberMe = new RememberMeAuthFilter();
        rememberMe.init( config );
        mustChange = new MustChangePasswordFilter();
        mustChange.init( config );
        httpSession = mock( HttpSession.class );
        final Map< String, Object > attrs = new HashMap<>();
        Mockito.doAnswer( inv -> attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ) ).when( httpSession )
            .setAttribute( Mockito.anyString(), any() );
        Mockito.doAnswer( inv -> attrs.get( ( String ) inv.getArgument( 0 ) ) ).when( httpSession )
            .getAttribute( Mockito.anyString() );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        Mockito.doReturn( new PrintWriter( body ) ).when( resp ).getWriter();
    }

    @AfterEach
    void tearDown() {
        wikiStatic.close();
    }

    private HttpServletRequest request( final String token, final Cookie... cookies ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        Mockito.doReturn( "Bearer " + token ).when( req ).getHeader( "Authorization" );
        Mockito.doReturn( "GET" ).when( req ).getMethod();
        Mockito.doReturn( "/api/work/items" ).when( req ).getRequestURI();
        Mockito.doReturn( "" ).when( req ).getContextPath();
        Mockito.doReturn( "10.0.0.1" ).when( req ).getRemoteAddr();
        Mockito.doReturn( cookies ).when( req ).getCookies();
        final Map< String, Object > attrs = new HashMap<>();
        Mockito.doAnswer( inv -> attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ) ).when( req )
            .setAttribute( Mockito.anyString(), any() );
        Mockito.doAnswer( inv -> attrs.get( ( String ) inv.getArgument( 0 ) ) ).when( req )
            .getAttribute( Mockito.anyString() );
        return req;
    }

    /** Runs the three filters in web.xml order; returns the request that reached the end of the chain, or null. */
    private ServletRequest run( final HttpServletRequest req ) throws Exception {
        final AtomicReference< ServletRequest > end = new AtomicReference<>();
        final FilterChain last = ( r, s ) -> end.set( r );
        final FilterChain afterRememberMe = ( r, s ) -> mustChange.doFilter( r, s, last );
        final FilterChain afterKey = ( r, s ) -> rememberMe.doFilter( r, s, afterRememberMe );
        keyFilter.doFilter( req, resp, afterKey );
        return end.get();
    }

    private void assertNoSession( final HttpServletRequest req ) throws Exception {
        verify( req, never() ).getSession();
        verify( req, never() ).getSession( true );
        verify( authMgr, never() ).login( any( HttpServletRequest.class ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "root", "agent-owner" } )
    void aWorkKeyWithARememberMeCookieNeverBecomesAnOwnerSession( final String owner ) throws Exception {
        final String token = keys.generate( owner, "k", ApiKeyService.Scope.WORK, "admin" ).plaintext();
        final HttpServletRequest req = request( token, new Cookie( "WikantikUID", "anything" ) );
        final ServletRequest reached = run( req );
        assertNotNull( reached, "the request must reach the servlet: " + body );
        final HttpServletRequest seen = ( HttpServletRequest ) reached;
        assertNull( seen.getUserPrincipal(), "a key request exposes no user principal" );
        assertNull( seen.getRemoteUser() );
        assertNotNull( seen.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) );
        assertNoSession( req );
    }

    @Test
    void aWorkKeyRequestWithAnotherUsersSessionCookieIsNotBlockedByThatUsersPasswordFlag() throws Exception {
        final String token = keys.generate( "owner", "k", ApiKeyService.Scope.WORK, "admin" ).plaintext();
        final HttpServletRequest req = request( token, new Cookie( "JSESSIONID", "abc" ) );
        Mockito.doReturn( httpSession ).when( req ).getSession( false );
        httpSession.setAttribute( PasswordChangeGate.SESSION_ATTRIBUTE, Boolean.TRUE );
        final Principal flagged = mock( Principal.class );
        when( flagged.getName() ).thenReturn( "flagged" );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getLoginPrincipal() ).thenReturn( flagged );
        assertNotNull( run( req ), "the flagged user's cookie must not block a key request: " + body );
        assertEquals( "", body.toString() );
    }

    /** An agent's own token is a key request too: with a login cookie on the same request it still opens no session. */
    @Test
    void anAgentTokenWithARememberMeCookieNeverBecomesASession() throws Exception {
        final com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef agent =
            new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 11, 7 );
        keyFilter = new WorkApiKeyFilter( () -> keys, () -> true,
            () -> token -> java.util.Optional.of( agent ),
            new com.wikantik.http.ratelimit.SlidingWindowRateLimiter( 0, 0 ) );
        final HttpServletRequest req = request( "wka_codex", new Cookie( "WikantikUID", "anything" ),
            new Cookie( "JSESSIONID", "someone-elses" ) );
        final ServletRequest reached = run( req );
        assertNotNull( reached, "the request must reach the servlet: " + body );
        final HttpServletRequest seen = ( HttpServletRequest ) reached;
        assertNull( seen.getUserPrincipal() );
        assertNull( seen.getRemoteUser() );
        org.junit.jupiter.api.Assertions.assertEquals( agent,
            seen.getAttribute( com.wikantik.auth.apikeys.AgentTokenAuth.ATTR_AGENT_REF ) );
        assertNoSession( req );
    }
}
