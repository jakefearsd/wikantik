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
package com.wikantik.auth.sso;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.exception.http.FoundAction;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SSORedirectServlet} covering all doGet() branches.
 * The engine is not actually started; all dependencies are mocked.
 */
class SSORedirectServletTest {

    private Engine engine;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private SSORedirectServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        engine = MockEngineBuilder.engine().build();
        request = mock( HttpServletRequest.class );
        response = mock( HttpServletResponse.class );
        when( request.getContextPath() ).thenReturn( "/JSPWiki" );
        when( request.getParameter( "client_name" ) ).thenReturn( null );

        // Build servlet and inject engine via reflection (init() normally calls Wiki.engine().find())
        servlet = new SSORedirectServlet();
        injectEngine( servlet, engine );
    }

    @AfterEach
    void tearDown() {
        SSOConfigHolder.removeConfig( engine );
    }

    // ---- SSO not configured (null SSOConfig) → 404 ----

    @Test
    void doGetReturns404WhenSSOConfigIsNull() throws Exception {
        // No config registered → SSOConfigHolder.getConfig returns null
        servlet.doGet( request, response );

        verify( response ).sendError( HttpServletResponse.SC_NOT_FOUND, "SSO is not enabled." );
    }

    // ---- SSO disabled → 404 ----

    @Test
    void doGetReturns404WhenSSOIsDisabled() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "false" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost/sso/callback" );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        servlet.doGet( request, response );

        verify( response ).sendError( HttpServletResponse.SC_NOT_FOUND, "SSO is not enabled." );
    }

    // ---- SSO enabled but pac4jConfig null → 500 ----

    @Test
    void doGetReturns500WhenPac4jConfigIsNull() throws Exception {
        // A disabled SSOConfig has null pac4jConfig; we manually stub it
        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( null );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        servlet.doGet( request, response );

        verify( response ).sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SSO configuration error." );
    }

    // ---- SSO enabled, no client found for requested name → redirect to Login.jsp ----

    @Test
    void doGetRedirectsToLoginWhenClientNameNotFound() throws Exception {
        when( request.getParameter( "client_name" ) ).thenReturn( "UnknownClient" );

        final Config pac4jConfig = mock( Config.class );
        final Clients clients = mock( Clients.class );
        when( pac4jConfig.getClients() ).thenReturn( clients );
        when( clients.findClient( "UnknownClient" ) ).thenReturn( Optional.empty() );

        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( pac4jConfig );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        servlet.doGet( request, response );

        verify( response ).sendRedirect( "/JSPWiki/Login.jsp?error=no_sso_client" );
    }

    // ---- SSO enabled, no clients at all (no client_name param) → redirect to Login.jsp ----

    @Test
    void doGetRedirectsToLoginWhenNoClientsConfigured() throws Exception {
        when( request.getParameter( "client_name" ) ).thenReturn( null );

        final Config pac4jConfig = mock( Config.class );
        final Clients clients = mock( Clients.class );
        when( pac4jConfig.getClients() ).thenReturn( clients );
        when( clients.findAllClients() ).thenReturn( List.of() );

        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( pac4jConfig );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        servlet.doGet( request, response );

        verify( response ).sendRedirect( "/JSPWiki/Login.jsp?error=no_sso_client" );
    }

    // ---- SSO enabled, client found, redirect action with location ----

    @Test
    @SuppressWarnings( "unchecked" )
    void doGetRedirectsToIdpWhenClientProvidesLocation() throws Exception {
        when( request.getParameter( "client_name" ) ).thenReturn( null );

        final FoundAction foundAction = new FoundAction( "https://idp.example.com/auth" );

        final Client client = mock( Client.class );
        when( client.getName() ).thenReturn( "OidcClient" );
        when( client.getRedirectionAction( any( CallContext.class ) ) )
                .thenReturn( Optional.of( foundAction ) );

        final Clients clients = mock( Clients.class );
        when( clients.findAllClients() ).thenReturn( List.of( client ) );

        final Config pac4jConfig = mock( Config.class );
        when( pac4jConfig.getClients() ).thenReturn( clients );

        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( pac4jConfig );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        // Need a real HttpSession for JEEContext
        final jakarta.servlet.http.HttpSession session = mock( jakarta.servlet.http.HttpSession.class );
        when( request.getSession( anyBoolean() ) ).thenReturn( session );
        when( request.getSession() ).thenReturn( session );

        servlet.doGet( request, response );

        verify( response ).sendRedirect( "https://idp.example.com/auth" );
    }

    // ---- SSO enabled, client throws exception → redirect to Login.jsp ----

    @Test
    @SuppressWarnings( "unchecked" )
    void doGetRedirectsToLoginOnClientException() throws Exception {
        when( request.getParameter( "client_name" ) ).thenReturn( null );

        final Client client = mock( Client.class );
        when( client.getName() ).thenReturn( "BrokenClient" );
        when( client.getRedirectionAction( any( CallContext.class ) ) )
                .thenThrow( new RuntimeException( "broken client" ) );

        final Clients clients = mock( Clients.class );
        when( clients.findAllClients() ).thenReturn( List.of( client ) );

        final Config pac4jConfig = mock( Config.class );
        when( pac4jConfig.getClients() ).thenReturn( clients );

        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( pac4jConfig );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        final jakarta.servlet.http.HttpSession session = mock( jakarta.servlet.http.HttpSession.class );
        when( request.getSession( anyBoolean() ) ).thenReturn( session );
        when( request.getSession() ).thenReturn( session );

        servlet.doGet( request, response );

        verify( response ).sendRedirect( "/JSPWiki/Login.jsp?error=sso_redirect_failed" );
    }

    // ---- SSO enabled, client returns empty Optional → redirect to Login.jsp ----

    @Test
    @SuppressWarnings( "unchecked" )
    void doGetRedirectsToLoginWhenRedirectionActionIsEmpty() throws Exception {
        when( request.getParameter( "client_name" ) ).thenReturn( null );

        final Client client = mock( Client.class );
        when( client.getName() ).thenReturn( "OidcClient" );
        when( client.getRedirectionAction( any( CallContext.class ) ) )
                .thenReturn( Optional.empty() );

        final Clients clients = mock( Clients.class );
        when( clients.findAllClients() ).thenReturn( List.of( client ) );

        final Config pac4jConfig = mock( Config.class );
        when( pac4jConfig.getClients() ).thenReturn( clients );

        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( pac4jConfig );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        final jakarta.servlet.http.HttpSession session = mock( jakarta.servlet.http.HttpSession.class );
        when( request.getSession( anyBoolean() ) ).thenReturn( session );
        when( request.getSession() ).thenReturn( session );

        servlet.doGet( request, response );

        verify( response ).sendRedirect( "/JSPWiki/Login.jsp?error=sso_redirect_failed" );
    }

    // ---- Helper: inject engine via reflection ----

    private static void injectEngine( final SSORedirectServlet target, final Engine eng ) {
        try {
            final java.lang.reflect.Field f = SSORedirectServlet.class.getDeclaredField( "engine" );
            f.setAccessible( true );
            f.set( target, eng );
        } catch ( final ReflectiveOperationException e ) {
            throw new RuntimeException( e );
        }
    }
}
