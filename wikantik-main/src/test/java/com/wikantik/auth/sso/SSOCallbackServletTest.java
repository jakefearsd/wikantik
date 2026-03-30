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
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pac4j.core.config.Config;
import org.pac4j.core.engine.CallbackLogic;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SSOCallbackServlet} covering all {@code service()} branches.
 */
class SSOCallbackServletTest {

    private Engine engine;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private SSOCallbackServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        engine = MockEngineBuilder.engine().build();
        request = mock( HttpServletRequest.class );
        response = mock( HttpServletResponse.class );
        when( request.getContextPath() ).thenReturn( "/JSPWiki" );
        when( request.getRequestURI() ).thenReturn( "/JSPWiki/sso/callback" );

        // Stub getSession for JEEContext construction
        final HttpSession session = mock( HttpSession.class );
        when( request.getSession() ).thenReturn( session );
        when( request.getSession( anyBoolean() ) ).thenReturn( session );

        servlet = new SSOCallbackServlet();
        injectEngine( servlet, engine );
    }

    @AfterEach
    void tearDown() {
        SSOConfigHolder.removeConfig( engine );
    }

    // ---- SSO not configured (null SSOConfig) → 404 ----

    @Test
    void serviceReturns404WhenSSOConfigIsNull() throws Exception {
        servlet.service( request, response );

        verify( response ).sendError( HttpServletResponse.SC_NOT_FOUND, "SSO is not enabled." );
    }

    // ---- SSO disabled → 404 ----

    @Test
    void serviceReturns404WhenSSOIsDisabled() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "false" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost/sso/callback" );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        servlet.service( request, response );

        verify( response ).sendError( HttpServletResponse.SC_NOT_FOUND, "SSO is not enabled." );
    }

    // ---- SSO enabled but pac4jConfig null → 500 ----

    @Test
    void serviceReturns500WhenPac4jConfigIsNull() throws Exception {
        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( null );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        servlet.service( request, response );

        verify( response ).sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SSO configuration error." );
    }

    // ---- Callback logic throws → redirect to Login.jsp ----

    @Test
    void serviceRedirectsToLoginWhenCallbackLogicThrows() throws Exception {
        // We need a non-null pac4jConfig to get past the null check, but
        // the DefaultCallbackLogic will throw because there is no real pac4j state.
        // We use a real (but empty) Config object which will cause the callback logic
        // to fail when it can't find a matching client.
        final Config pac4jConfig = mock( Config.class );
        final org.pac4j.core.client.Clients clients = mock( org.pac4j.core.client.Clients.class );
        when( pac4jConfig.getClients() ).thenReturn( clients );
        // Make the callback logic throw by having clients.findClient throw
        when( clients.findClient( anyString() ) ).thenThrow( new RuntimeException( "no client" ) );

        final SSOConfig ssoConfig = mock( SSOConfig.class );
        when( ssoConfig.isEnabled() ).thenReturn( true );
        when( ssoConfig.getPac4jConfig() ).thenReturn( pac4jConfig );
        SSOConfigHolder.setConfig( engine, ssoConfig );

        // The DefaultCallbackLogic will fail when trying to process, causing the catch block
        // to execute and send a redirect
        servlet.service( request, response );

        verify( response ).sendRedirect( "/JSPWiki/Login.jsp?error=sso_callback_failed" );
    }

    // ---- Helper: inject engine via reflection ----

    private static void injectEngine( final SSOCallbackServlet target, final Engine eng ) {
        try {
            final java.lang.reflect.Field f = SSOCallbackServlet.class.getDeclaredField( "engine" );
            f.setAccessible( true );
            f.set( target, eng );
        } catch ( final ReflectiveOperationException e ) {
            throw new RuntimeException( e );
        }
    }
}
