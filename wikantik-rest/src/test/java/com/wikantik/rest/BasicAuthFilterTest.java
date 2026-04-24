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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.SessionSPI;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.WikiSecurityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the header-decoding helper. The full servlet contract is
 * harder to exercise without engine wiring — the production path is covered
 * by the manual-test A/B against the live admin endpoints and by the explicit
 * {@link AdminAuthFilterTest} suite that already covers what happens once a
 * principal is on the session.
 */
class BasicAuthFilterTest {

    @Test
    void decodesWellFormedBasicCredentials() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( "alice:s3cret".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertArrayEquals( new String[]{ "alice", "s3cret" }, decoded );
    }

    @Test
    void handlesColonInPasswordByTakingOnlyFirstSplit() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( "alice:secret:with:colons".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertEquals( "alice", decoded[ 0 ] );
        assertEquals( "secret:with:colons", decoded[ 1 ] );
    }

    @Test
    void returnsNullOnMalformedBase64() {
        assertNull( BasicAuthFilter.decodeCredentials( "Basic !!not base64!!" ) );
    }

    @Test
    void missingColonYieldsEmptyUsernameAndPassword() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( "no-colon-here".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertArrayEquals( new String[]{ "", "" }, decoded );
    }

    @Test
    void emptyCredentialsDecodeToEmpty() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( ":".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertArrayEquals( new String[]{ "", "" }, decoded );
    }

    // ----- doFilter coverage -----

    private BasicAuthFilter filter;
    private Engine engine;
    private AuthenticationManager authMgr;

    @BeforeEach
    void setUpDoFilter() throws Exception {
        filter = new BasicAuthFilter();
        engine = mock( Engine.class );
        authMgr = mock( AuthenticationManager.class );
        when( engine.getManager( AuthenticationManager.class ) ).thenReturn( authMgr );

        final FilterConfig config = mock( FilterConfig.class );
        final ServletContext ctx = mock( ServletContext.class );
        when( config.getServletContext() ).thenReturn( ctx );

        // Wiki.engine().find(...) is called during init() — stub it statically.
        try ( MockedStatic< Wiki > wiki = mockStatic( Wiki.class, CALLS_REAL_METHODS ) ) {
            final com.wikantik.api.spi.EngineSPI spi = mock( com.wikantik.api.spi.EngineSPI.class );
            when( spi.find( any( ServletContext.class ), any() ) ).thenReturn( engine );
            wiki.when( Wiki::engine ).thenReturn( spi );
            filter.init( config );
        }
    }

    private HttpServletRequest requestWithAuth( final String header ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/pages" );
        doReturn( header ).when( req ).getHeader( "Authorization" );
        return req;
    }

    private static HttpServletResponse responseWithWriter( final StringWriter sw ) {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        try {
            doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        } catch ( final java.io.IOException e ) {
            throw new AssertionError( e );
        }
        return resp;
    }

    @Test
    void doFilter_passesThroughWhenNoAuthorizationHeader() throws Exception {
        final HttpServletRequest req = requestWithAuth( null );
        final HttpServletResponse resp = responseWithWriter( new StringWriter() );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );
        verify( chain ).doFilter( req, resp );
    }

    @Test
    void doFilter_passesThroughForNonBasicScheme() throws Exception {
        final HttpServletRequest req = requestWithAuth( "Bearer token-abc" );
        final HttpServletResponse resp = responseWithWriter( new StringWriter() );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );
        verify( chain ).doFilter( req, resp );
    }

    @Test
    void doFilter_returns400OnMalformedBase64() throws Exception {
        final HttpServletRequest req = requestWithAuth( "Basic !!!not-base64!!!" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = responseWithWriter( sw );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );
        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( sw.toString().contains( "Malformed" ) );
    }

    @Test
    void doFilter_returns400OnEmptyUsername() throws Exception {
        final String header = "Basic " + Base64.getEncoder().encodeToString( ":p".getBytes() );
        final HttpServletRequest req = requestWithAuth( header );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = responseWithWriter( sw );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );
        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( sw.toString().toLowerCase().contains( "username" ) );
    }

    @Test
    void doFilter_skipsReLoginWhenSessionAlreadyAuthedAsSameUser() throws Exception {
        final String header = "Basic " + Base64.getEncoder().encodeToString( "alice:s".getBytes() );
        final HttpServletRequest req = requestWithAuth( header );
        final HttpServletResponse resp = responseWithWriter( new StringWriter() );
        final FilterChain chain = mock( FilterChain.class );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        final Principal principal = mock( Principal.class );
        when( principal.getName() ).thenReturn( "alice" );
        when( session.getUserPrincipal() ).thenReturn( principal );

        try ( MockedStatic< Wiki > wiki = mockStatic( Wiki.class, CALLS_REAL_METHODS ) ) {
            final SessionSPI spi = mock( SessionSPI.class );
            when( spi.find( eq( engine ), any( HttpServletRequest.class ) ) ).thenReturn( session );
            wiki.when( Wiki::session ).thenReturn( spi );
            filter.doFilter( req, resp, chain );
        }

        verify( chain ).doFilter( req, resp );
        verify( authMgr, never() ).login( any(), any(), anyString(), anyString() );
    }

    @Test
    void doFilter_returns401OnWikiSecurityException() throws Exception {
        final String header = "Basic " + Base64.getEncoder().encodeToString( "alice:wrong".getBytes() );
        final HttpServletRequest req = requestWithAuth( header );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = responseWithWriter( sw );
        final FilterChain chain = mock( FilterChain.class );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );
        when( authMgr.login( eq( session ), any(), eq( "alice" ), eq( "wrong" ) ) )
            .thenThrow( new WikiSecurityException( "nope" ) );

        try ( MockedStatic< Wiki > wiki = mockStatic( Wiki.class, CALLS_REAL_METHODS ) ) {
            final SessionSPI spi = mock( SessionSPI.class );
            when( spi.find( eq( engine ), any( HttpServletRequest.class ) ) ).thenReturn( session );
            wiki.when( Wiki::session ).thenReturn( spi );
            filter.doFilter( req, resp, chain );
        }

        verify( resp ).setHeader( "WWW-Authenticate", "Basic realm=\"Wikantik\"" );
        verify( resp ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void doFilter_returns401OnBadCredentials() throws Exception {
        final String header = "Basic " + Base64.getEncoder().encodeToString( "alice:wrong".getBytes() );
        final HttpServletRequest req = requestWithAuth( header );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = responseWithWriter( sw );
        final FilterChain chain = mock( FilterChain.class );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );
        when( authMgr.login( any(), any(), anyString(), anyString() ) ).thenReturn( false );

        try ( MockedStatic< Wiki > wiki = mockStatic( Wiki.class, CALLS_REAL_METHODS ) ) {
            final SessionSPI spi = mock( SessionSPI.class );
            when( spi.find( eq( engine ), any( HttpServletRequest.class ) ) ).thenReturn( session );
            wiki.when( Wiki::session ).thenReturn( spi );
            filter.doFilter( req, resp, chain );
        }

        verify( resp ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void doFilter_passesThroughAfterSuccessfulLogin() throws Exception {
        final String header = "Basic " + Base64.getEncoder().encodeToString( "alice:ok".getBytes() );
        final HttpServletRequest req = requestWithAuth( header );
        final HttpServletResponse resp = responseWithWriter( new StringWriter() );
        final FilterChain chain = mock( FilterChain.class );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );
        when( authMgr.login( any(), any(), anyString(), anyString() ) ).thenReturn( true );

        try ( MockedStatic< Wiki > wiki = mockStatic( Wiki.class, CALLS_REAL_METHODS ) ) {
            final SessionSPI spi = mock( SessionSPI.class );
            when( spi.find( eq( engine ), any( HttpServletRequest.class ) ) ).thenReturn( session );
            wiki.when( Wiki::session ).thenReturn( spi );
            filter.doFilter( req, resp, chain );
        }

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
    }

    @Test
    void destroy_isNoOp() {
        filter.destroy();
    }
}
