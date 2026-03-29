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
package com.wikantik.ajax;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PagePermission;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WikiAjaxDispatcherServlet}. Uses the package-private
 * constructor to inject mock dependencies, avoiding the need for a running engine.
 */
class WikiAjaxDispatcherServletTest {

    private AuthorizationManager authorizationManager;
    private Engine engine;
    private WikiAjaxDispatcherServlet servlet;
    private Session session;
    private HttpServletRequest request;
    private HttpServletResponse response;

    /** A simple, reusable WikiAjaxServlet stub. */
    private static final String TEST_SERVLET_ALIAS = "TestServlet";
    private WikiAjaxServlet testAjaxServlet;
    private final List<String> capturedActions = new ArrayList<>();
    private final List<List<String>> capturedParams = new ArrayList<>();

    @BeforeEach
    void setUp() {
        authorizationManager = mock( AuthorizationManager.class );
        engine = MockEngineBuilder.engine()
                .with( AuthorizationManager.class, authorizationManager )
                .build();
        when( engine.getContentEncoding() ).thenReturn( StandardCharsets.UTF_8 );

        session = mock( Session.class );
        request = mock( HttpServletRequest.class );
        response = mock( HttpServletResponse.class );

        // Build the servlet with injected mocks, then spy so findSession can be stubbed
        servlet = spy( new WikiAjaxDispatcherServlet( engine, authorizationManager ) );
        doReturn( session ).when( servlet ).findSession( request );

        // Build a capturing WikiAjaxServlet for tests that need to verify dispatch
        capturedActions.clear();
        capturedParams.clear();
        testAjaxServlet = new WikiAjaxServlet() {
            @Override public String getServletMapping() { return TEST_SERVLET_ALIAS; }
            @Override public void service( final HttpServletRequest req, final HttpServletResponse res,
                                           final String actionName, final List<String> params ) {
                capturedActions.add( actionName );
                capturedParams.add( new ArrayList<>( params ) );
            }
        };
    }

    @AfterEach
    void tearDown() {
        // Clear any registered test servlets to avoid state leaking between tests.
        // The static map is package-private via registerServlet, so we unregister by registering a null-safe dummy.
        // The only safe approach: just re-register the alias with a no-op if it was registered.
        // (The static map is ConcurrentHashMap — using the public API is the safest approach.)
        WikiAjaxDispatcherServlet.registerServlet( TEST_SERVLET_ALIAS, new WikiAjaxServlet() {
            @Override public String getServletMapping() { return TEST_SERVLET_ALIAS; }
            @Override public void service( HttpServletRequest req, HttpServletResponse res,
                                           String actionName, List<String> params ) { }
        } );
    }

    // ---- getServletName tests ----

    @Test
    void testGetServletNameExtractsNameFromSimplePath() throws ServletException {
        assertEquals( "MyPlugin", servlet.getServletName( "/ajax/MyPlugin" ) );
    }

    @Test
    void testGetServletNameExtractsNameWithTrailingSlash() throws ServletException {
        assertEquals( "MyPlugin", servlet.getServletName( "/ajax/MyPlugin/" ) );
    }

    @Test
    void testGetServletNameExtractsNameWithSubPath() throws ServletException {
        assertEquals( "MyPlugin", servlet.getServletName( "/ajax/MyPlugin/action" ) );
    }

    @Test
    void testGetServletNameExtractsNameWithQueryString() throws ServletException {
        assertEquals( "MyPlugin", servlet.getServletName( "/ajax/MyPlugin?param=1" ) );
    }

    @Test
    void testGetServletNameExtractsNameWithFragment() throws ServletException {
        assertEquals( "MyPlugin", servlet.getServletName( "/ajax/MyPlugin#hash" ) );
    }

    @Test
    void testGetServletNameExtractsNameFromFullUrl() throws ServletException {
        assertEquals( "MyPlugin", servlet.getServletName( "http://localhost:8080/ajax/MyPlugin" ) );
    }

    @Test
    void testGetServletNameThrowsForInvalidPath() {
        assertThrows( ServletException.class, () -> servlet.getServletName( "/no-ajax-prefix/MyPlugin" ) );
    }

    @Test
    void testGetServletNameReturnsNullForBlankPath() throws ServletException {
        assertNull( servlet.getServletName( "" ) );
        assertNull( servlet.getServletName( "   " ) );
    }

    // ---- registerServlet / findServletByName tests ----

    @Test
    void testRegisterAndFindServletByAlias() {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );

        final WikiAjaxServlet found = servlet.findServletByName( TEST_SERVLET_ALIAS );

        assertNotNull( found );
        assertSame( testAjaxServlet, found );
    }

    @Test
    void testFindServletByNameReturnsNullForUnknownAlias() {
        final WikiAjaxServlet found = servlet.findServletByName( "NonExistentServlet" );

        assertNull( found );
    }

    @Test
    void testRegisterServletWithExplicitAlias() {
        final String alias = "CustomAlias";
        WikiAjaxDispatcherServlet.registerServlet( alias, testAjaxServlet );

        assertSame( testAjaxServlet, servlet.findServletByName( alias ) );
    }

    // ---- doGet / doPost dispatch tests ----

    @Test
    void testDoGetDispatchesToRegisteredServletWhenPermissionGranted() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        assertEquals( 1, capturedActions.size(), "service() should have been called once" );
        verify( request ).setCharacterEncoding( StandardCharsets.UTF_8.displayName() );
        verify( response ).setCharacterEncoding( StandardCharsets.UTF_8.displayName() );
    }

    @Test
    void testDoPostDispatchesToRegisteredServlet() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doPost( request, response );

        assertEquals( 1, capturedActions.size(), "service() should have been called once via doPost" );
    }

    @Test
    void testDoGetDoesNotDispatchWhenPermissionDenied() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( false );

        servlet.doGet( request, response );

        // service() must NOT have been called
        assertTrue( capturedActions.isEmpty(), "service() must not be called when permission is denied" );
        // Encoding must not be set on either side
        verify( request, never() ).setCharacterEncoding( any() );
        verify( response, never() ).setCharacterEncoding( any() );
    }

    @Test
    void testDoGetThrowsServletExceptionForUnregisteredServlet() {
        when( request.getRequestURI() ).thenReturn( "/ajax/UnknownServlet" );

        assertThrows( ServletException.class, () -> servlet.doGet( request, response ) );
    }

    @Test
    void testDoGetPassesActionNameToService() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS + "/myAction" );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        assertEquals( 1, capturedActions.size() );
        assertEquals( "myAction", capturedActions.get( 0 ) );
    }

    @Test
    void testDoGetPassesNullParamsAsEmptyList() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( request.getParameter( "params" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        assertEquals( 1, capturedParams.size() );
        assertTrue( capturedParams.get( 0 ).isEmpty(), "null params should yield an empty list" );
    }

    @Test
    void testDoGetPassesCommaSeparatedParamValues() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( request.getParameter( "params" ) ).thenReturn( "alpha,beta,gamma" );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        assertEquals( 1, capturedParams.size() );
        assertEquals( List.of( "alpha", "beta", "gamma" ), capturedParams.get( 0 ) );
    }

    @Test
    void testDoGetWithBlankParamsYieldsEmptyList() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( request.getParameter( "params" ) ).thenReturn( "   " );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        assertEquals( 1, capturedParams.size() );
        assertTrue( capturedParams.get( 0 ).isEmpty(), "blank params string should yield an empty list" );
    }

    @Test
    void testDoGetWithPermissionRegisteredExplicitly() throws Exception {
        final Permission editPerm = PagePermission.EDIT;
        WikiAjaxDispatcherServlet.registerServlet( TEST_SERVLET_ALIAS, testAjaxServlet, editPerm );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( authorizationManager.checkPermission( eq( session ), eq( editPerm ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        assertEquals( 1, capturedActions.size() );
        verify( authorizationManager ).checkPermission( session, editPerm );
    }

    @Test
    void testDoGetChecksPermissionOnCorrectSession() throws Exception {
        WikiAjaxDispatcherServlet.registerServlet( testAjaxServlet );
        when( request.getRequestURI() ).thenReturn( "/ajax/" + TEST_SERVLET_ALIAS );
        when( authorizationManager.checkPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( true );

        servlet.doGet( request, response );

        // findSession must have been called with the request
        verify( servlet ).findSession( request );
        // authorizationManager must have been checked with the returned session
        verify( authorizationManager ).checkPermission( eq( session ), any( Permission.class ) );
    }

    @Test
    void testDoGetWithNullServletNameDoesNothing() throws Exception {
        // A blank path returns null from getServletName; no exception, no dispatch
        when( request.getRequestURI() ).thenReturn( "" );

        assertDoesNotThrow( () -> servlet.doGet( request, response ) );
        assertTrue( capturedActions.isEmpty() );
    }

}
