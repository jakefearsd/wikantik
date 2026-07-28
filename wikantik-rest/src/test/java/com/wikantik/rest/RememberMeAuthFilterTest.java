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
import com.wikantik.auth.subsystem.AuthSubsystem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RememberMeAuthFilter}.
 *
 * <p>The contract this pins down is deliberately narrow: re-authenticate only
 * when remember-me is enabled AND the request carries a remember-me cookie AND
 * the session is not already authenticated — and never block the request when
 * any of that blows up.</p>
 */
class RememberMeAuthFilterTest {

    private MockedStatic< Wiki >  wikiStatic;
    private WikiEngine            engine;
    private AuthenticationManager authMgr;
    private Session               session;
    private SessionSPI            sessionSpi;

    private RememberMeAuthFilter filter;
    private HttpServletRequest   req;
    private HttpServletResponse  resp;
    private FilterChain          chain;

    @BeforeEach
    void setUp() throws Exception {
        engine  = mock( WikiEngine.class );
        authMgr = mock( AuthenticationManager.class );
        session = mock( Session.class );

        when( engine.getAuthSubsystem() ).thenReturn(
                new AuthSubsystem.Services( authMgr, null, null, null, null, null, null, null ) );

        wikiStatic = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );

        final EngineSPI engineSpi = mock( EngineSPI.class );
        when( engineSpi.find( any( ServletContext.class ), any() ) ).thenReturn( engine );
        wikiStatic.when( Wiki::engine ).thenReturn( engineSpi );

        sessionSpi = mock( SessionSPI.class );
        when( sessionSpi.find( any(), any() ) ).thenReturn( session );
        wikiStatic.when( Wiki::session ).thenReturn( sessionSpi );

        final FilterConfig config = mock( FilterConfig.class );
        when( config.getServletContext() ).thenReturn( mock( ServletContext.class ) );

        filter = new RememberMeAuthFilter();
        filter.init( config );

        req   = mock( HttpServletRequest.class );
        resp  = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
        when( req.getRequestURI() ).thenReturn( "/api/pages" );
    }

    @AfterEach
    void tearDown() {
        wikiStatic.close();
        filter.destroy();
    }

    private static Cookie[] cookies( final String... nameValuePairs ) {
        final Cookie[] out = new Cookie[ nameValuePairs.length / 2 ];
        for ( int i = 0; i < out.length; i++ ) {
            out[ i ] = new Cookie( nameValuePairs[ i * 2 ], nameValuePairs[ i * 2 + 1 ] );
        }
        return out;
    }

    // ------------------------------------------------------------------ re-auth happens

    @Test
    void currentCookieOnUnauthenticatedSession_triggersReAuth() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( cookies( "WikantikUID", "abc123" ) );
        when( session.isAuthenticated() ).thenReturn( false );

        filter.doFilter( req, resp, chain );

        verify( authMgr ).login( req );
        verify( chain ).doFilter( req, resp );
    }

    @Test
    void legacyJspWikiCookieIsAlsoHonoured() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( cookies( "JSPWikiUID", "legacy-value" ) );
        when( session.isAuthenticated() ).thenReturn( false );

        filter.doFilter( req, resp, chain );

        verify( authMgr ).login( req );
    }

    @Test
    void rememberMeCookieAmongOthersIsStillFound() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn(
                cookies( "JSESSIONID", "s1", "theme", "dark", "WikantikUID", "abc123" ) );
        when( session.isAuthenticated() ).thenReturn( false );

        filter.doFilter( req, resp, chain );

        verify( authMgr ).login( req );
    }

    // ------------------------------------------------------------------ re-auth skipped

    @Test
    void cookieAuthenticationDisabled_neverReAuthenticates() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( false );
        when( req.getCookies() ).thenReturn( cookies( "WikantikUID", "abc123" ) );

        filter.doFilter( req, resp, chain );

        verify( authMgr, never() ).login( any() );
        verify( chain ).doFilter( req, resp );
    }

    @Test
    void anonymousRequestWithoutCookies_neverMintsASession() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( null );

        filter.doFilter( req, resp, chain );

        verify( sessionSpi, never() ).find( any(), any() );
        verify( authMgr, never() ).login( any() );
        verify( chain ).doFilter( req, resp );
    }

    @Test
    void unrelatedCookiesOnly_isTreatedAsNoRememberMe() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( cookies( "JSESSIONID", "s1", "theme", "dark" ) );

        filter.doFilter( req, resp, chain );

        verify( authMgr, never() ).login( any() );
    }

    @Test
    void emptyRememberMeCookieValue_isIgnored() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( cookies( "WikantikUID", "" ) );

        filter.doFilter( req, resp, chain );

        verify( authMgr, never() ).login( any() );
    }

    @Test
    void alreadyAuthenticatedSession_shortCircuitsWithoutLogin() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( cookies( "WikantikUID", "abc123" ) );
        when( session.isAuthenticated() ).thenReturn( true );

        filter.doFilter( req, resp, chain );

        verify( authMgr, never() ).login( any() );
        verify( chain ).doFilter( req, resp );
    }

    // ------------------------------------------------------------------ failure is non-fatal

    @Test
    void loginFailure_doesNotBlockTheRequest() throws Exception {
        when( authMgr.allowsCookieAuthentication() ).thenReturn( true );
        when( req.getCookies() ).thenReturn( cookies( "WikantikUID", "tampered" ) );
        when( session.isAuthenticated() ).thenReturn( false );
        when( authMgr.login( req ) ).thenThrow( new IllegalStateException( "bad cookie signature" ) );

        filter.doFilter( req, resp, chain );

        verify( chain, times( 1 ) ).doFilter( req, resp );
        verify( resp, never() ).sendError( org.mockito.ArgumentMatchers.anyInt() );
    }

    @Test
    void unavailableAuthManager_doesNotBlockTheRequest() throws Exception {
        when( engine.getAuthSubsystem() ).thenReturn(
                new AuthSubsystem.Services( null, null, null, null, null, null, null, null ) );

        filter.doFilter( req, resp, chain );

        verify( chain, times( 1 ) ).doFilter( req, resp );
    }
}
