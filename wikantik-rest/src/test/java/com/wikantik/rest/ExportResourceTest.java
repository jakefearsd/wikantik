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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;
import com.wikantik.export.ExportOptions;
import com.wikantik.export.ExportPreview;
import com.wikantik.export.ExportSelection;
import com.wikantik.export.ExportService;
import com.wikantik.export.ExportTooLargeException;
import com.wikantik.export.UnresolvedLinkMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExportResource}. The service seam ({@link ExportResource#exportService})
 * is stubbed in every test except the deliberately real-policy {@link #adminOptionsWithRealPermissionCheck_returns200()}
 * check, so the tests exercise request parsing, status-code mapping, and streaming wiring without
 * standing up a live {@link ExportService}.
 */
class ExportResourceTest {

    private static TestEngine engine;
    private static final ExportService stubService = mock( ExportService.class );

    @BeforeAll
    static void start() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @BeforeEach
    void anon() {
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
        reset( stubService );
    }

    /**
     * Logs admin in fresh on every call, registering the session under
     * {@link HttpMockFactory#SHARED_SESSION_ID} every time — unlike {@link TestEngine#adminSession()},
     * which only performs the real login (and thus the {@code SessionMonitor} registration) on its
     * <em>first</em> call and returns the cached {@link Session} thereafter. Since {@link #anon()}
     * evicts that registration before every test, a cached-and-not-re-registered admin session
     * would resolve as anonymous on every test after the first admin-based one in the class.
     */
    private Session loginAdmin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = WikiSession.getWikiSession( engine, request );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        return session;
    }

    private ExportResource servlet() throws Exception {
        final ExportResource r = new ExportResource() {
            @Override protected ExportService exportService( final HttpServletRequest req ) { return stubService; }
        };
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( engine.getServletContext() ).when( cfg ).getServletContext();
        r.init( cfg );
        return r;
    }

    private HttpServletRequest get( final String pathInfo, final Map< String, String[] > params ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/export" + ( pathInfo == null ? "" : pathInfo ) );
        doReturn( pathInfo ).when( req ).getPathInfo();
        params.forEach( ( k, v ) -> {
            doReturn( v ).when( req ).getParameterValues( k );
            doReturn( v[ 0 ] ).when( req ).getParameter( k );
        } );
        return req;
    }

    private record Resp( HttpServletResponse mock, StringWriter body, ByteArrayOutputStream bytes ) {}

    private Resp resp() throws Exception {
        final HttpServletResponse mock = HttpMockFactory.createHttpResponse();
        final StringWriter body = new StringWriter();
        doReturn( new PrintWriter( body ) ).when( mock ).getWriter();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ServletOutputStream sos = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener writeListener ) { /* not needed for tests */ }
            @Override public void write( final int b ) throws IOException { bytes.write( b ); }
        };
        doReturn( sos ).when( mock ).getOutputStream();
        return new Resp( mock, body, bytes );
    }

    // -------------------------------------------------------------------------
    // Authentication / authorization
    // -------------------------------------------------------------------------

    @Test
    void anonymousGets401() throws Exception {
        final Resp r = resp();
        servlet().doGet( get( "/preview", Map.of() ), r.mock() );
        verify( r.mock() ).setStatus( 401 );
        verifyNoInteractions( stubService );
    }

    @Test
    void authenticatedWithoutGrantGets403() throws Exception {
        engine.janneSession();   // authenticated, registers under the shared mock-session id
        final ExportResource r = new ExportResource() {
            @Override protected ExportService exportService( final HttpServletRequest req ) { return stubService; }
            @Override protected boolean canExport( final Session s ) { return false; }
        };
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( engine.getServletContext() ).when( cfg ).getServletContext();
        r.init( cfg );
        final Resp resp = resp();

        r.doGet( get( "/preview", Map.of() ), resp.mock() );

        verify( resp.mock() ).setStatus( 403 );
        verifyNoInteractions( stubService );
    }

    /**
     * No override of {@code canExport} here — this exercises the real
     * {@code AuthorizationManager} check for the {@code export} action against an admin session
     * (Admin implies {@code AllPermission}, which implies every {@code WikiPermission}), so the
     * 403 test above isn't the only thing standing between this resource and a real policy grant.
     */
    @Test
    void adminOptionsWithRealPermissionCheck_returns200() throws Exception {
        loginAdmin();
        when( stubService.options() ).thenReturn( new ExportOptions( List.of( "finance" ), List.of( "tax" ) ) );
        final Resp r = resp();

        servlet().doGet( get( "/options", Map.of() ), r.mock() );

        // No explicit setStatus(200) call on this branch (relies on the servlet container's
        // implicit 200 default, same as the /preview branch) — assert the real permission check
        // let the request through by verifying the options body was actually written, and that
        // no error status was ever set.
        verify( r.mock(), org.mockito.Mockito.never() ).setStatus( org.mockito.ArgumentMatchers.anyInt() );
        final JsonObject o = JsonParser.parseString( r.body().toString() ).getAsJsonObject();
        assertEquals( List.of( "finance" ), o.get( "clusters" ).getAsJsonArray().asList().stream()
                .map( e -> e.getAsString() ).toList() );
    }

    // -------------------------------------------------------------------------
    // /preview
    // -------------------------------------------------------------------------

    @Test
    void adminPreviewReturnsJson() throws Exception {
        loginAdmin();
        when( stubService.preview( any(), any() ) )
                .thenReturn( new ExportPreview( 2, 1, 100, 0, 2000, false, List.of( "A", "B" ) ) );
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "cluster", new String[]{ "finance" }, "hops", new String[]{ "1" } ) ), r.mock() );

        final JsonObject o = JsonParser.parseString( r.body().toString() ).getAsJsonObject();
        assertEquals( 2, o.get( "pages" ).getAsInt() );
        final ArgumentCaptor< ExportSelection > sel = ArgumentCaptor.forClass( ExportSelection.class );
        verify( stubService ).preview( any(), sel.capture() );
        assertEquals( List.of( "finance" ), sel.getValue().clusters() );
        assertEquals( 1, sel.getValue().hops() );
    }

    @Test
    void previewDefaultsMatchWireContract() throws Exception {
        loginAdmin();
        when( stubService.preview( any(), any() ) )
                .thenReturn( new ExportPreview( 0, 0, 0, 0, 2000, false, List.of() ) );
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of() ), r.mock() );

        final ArgumentCaptor< ExportSelection > sel = ArgumentCaptor.forClass( ExportSelection.class );
        verify( stubService ).preview( any(), sel.capture() );
        assertEquals( List.of(), sel.getValue().clusters() );
        assertEquals( List.of(), sel.getValue().tags() );
        assertTrue( sel.getValue().includeSubClusters() );
        assertEquals( Optional.empty(), sel.getValue().type() );
        assertEquals( Optional.empty(), sel.getValue().status() );
        assertEquals( 0, sel.getValue().hops() );
        assertEquals( UnresolvedLinkMode.KEEP, sel.getValue().unresolved() );
    }

    @Test
    void subclustersFalseIsHonoured() throws Exception {
        loginAdmin();
        when( stubService.preview( any(), any() ) )
                .thenReturn( new ExportPreview( 0, 0, 0, 0, 2000, false, List.of() ) );
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "subclusters", new String[]{ "false" } ) ), r.mock() );

        final ArgumentCaptor< ExportSelection > sel = ArgumentCaptor.forClass( ExportSelection.class );
        verify( stubService ).preview( any(), sel.capture() );
        assertFalse( sel.getValue().includeSubClusters() );
    }

    @Test
    void badSubclustersIs400() throws Exception {
        loginAdmin();
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "subclusters", new String[]{ "maybe" } ) ), r.mock() );

        verify( r.mock() ).setStatus( 400 );
        assertTrue( r.body().toString().toLowerCase( java.util.Locale.ROOT ).contains( "subclusters" ), r.body().toString() );
        verifyNoInteractions( stubService );
    }

    @Test
    void badHopsIs400() throws Exception {
        loginAdmin();
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "hops", new String[]{ "9" } ) ), r.mock() );

        verify( r.mock() ).setStatus( 400 );
        assertTrue( r.body().toString().contains( "hops" ), r.body().toString() );
        verifyNoInteractions( stubService );
    }

    @Test
    void nonNumericHopsIs400() throws Exception {
        loginAdmin();
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "hops", new String[]{ "abc" } ) ), r.mock() );

        verify( r.mock() ).setStatus( 400 );
        assertTrue( r.body().toString().contains( "hops" ), r.body().toString() );
        verifyNoInteractions( stubService );
    }

    @Test
    void badTypeIs400() throws Exception {
        loginAdmin();
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "type", new String[]{ "nonsense" } ) ), r.mock() );

        verify( r.mock() ).setStatus( 400 );
        assertTrue( r.body().toString().contains( "nonsense" ), r.body().toString() );
        verifyNoInteractions( stubService );
    }

    @Test
    void validTypeIsForwarded() throws Exception {
        loginAdmin();
        when( stubService.preview( any(), any() ) )
                .thenReturn( new ExportPreview( 0, 0, 0, 0, 2000, false, List.of() ) );
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "type", new String[]{ "article" } ) ), r.mock() );

        final ArgumentCaptor< ExportSelection > sel = ArgumentCaptor.forClass( ExportSelection.class );
        verify( stubService ).preview( any(), sel.capture() );
        assertEquals( Optional.of( PageType.ARTICLE ), sel.getValue().type() );
    }

    @Test
    void badUnresolvedIs400() throws Exception {
        loginAdmin();
        final Resp r = resp();

        servlet().doGet( get( "/preview", Map.of( "unresolved", new String[]{ "bogus" } ) ), r.mock() );

        verify( r.mock() ).setStatus( 400 );
        verifyNoInteractions( stubService );
    }

    // -------------------------------------------------------------------------
    // download (bare path)
    // -------------------------------------------------------------------------

    @Test
    void overCapIs413WithCount() throws Exception {
        loginAdmin();
        when( stubService.prepare( any(), any() ) ).thenThrow( new ExportTooLargeException( 5000, 2000 ) );
        final Resp r = resp();

        servlet().doGet( get( null, Map.of() ), r.mock() );

        verify( r.mock() ).setStatus( 413 );
        final JsonObject o = JsonParser.parseString( r.body().toString() ).getAsJsonObject();
        assertEquals( 5000, o.get( "count" ).getAsInt() );
        assertEquals( 2000, o.get( "cap" ).getAsInt() );
    }

    @Test
    void emptySelectionIs400() throws Exception {
        final Session admin = loginAdmin();
        final ExportService.PreparedExport emptyPrepared = new ExportService.PreparedExport(
                new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP ),
                List.of(), "wikantik-export-20260929-1412.zip", admin );
        when( stubService.prepare( any(), any() ) ).thenReturn( emptyPrepared );
        final Resp r = resp();

        servlet().doGet( get( "", Map.of() ), r.mock() );

        verify( r.mock() ).setStatus( 400 );
        assertTrue( r.body().toString().contains( "no pages" ), r.body().toString() );
    }

    @Test
    void downloadSetsZipHeaders() throws Exception {
        loginAdmin();
        final PageDescriptor page = new PageDescriptor( "01ABC", "TestPage", "Test Page", PageType.ARTICLE,
                null, List.of(), null, Instant.now(), Optional.empty(), false );
        final ExportSelection selection = new ExportSelection(
                List.of(), true, List.of(), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP );
        final ExportService.PreparedExport prepared = new ExportService.PreparedExport(
                selection, List.of( page ), "wikantik-export-20260929-1412.zip", loginAdmin() );
        when( stubService.prepare( any(), any() ) ).thenReturn( prepared );
        Mockito.doAnswer( invocation -> {
            final java.io.OutputStream out = invocation.getArgument( 1 );
            out.write( "PK".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
            return null;
        } ).when( stubService ).stream( any(), any() );
        final Resp r = resp();

        servlet().doGet( get( null, Map.of() ), r.mock() );

        verify( r.mock() ).setStatus( 200 );
        verify( r.mock() ).setContentType( "application/zip" );
        verify( r.mock() ).setHeader( "Content-Disposition", "attachment; filename=\"wikantik-export-20260929-1412.zip\"" );
        verify( r.mock() ).setHeader( "Cache-Control", "no-store" );
        assertEquals( "PK", r.bytes().toString( java.nio.charset.StandardCharsets.UTF_8 ) );
    }

    @Test
    void clientDisconnectDuringStreamDoesNotThrow() throws Exception {
        loginAdmin();
        final PageDescriptor page = new PageDescriptor( "01ABC", "TestPage", "Test Page", PageType.ARTICLE,
                null, List.of(), null, Instant.now(), Optional.empty(), false );
        final ExportSelection selection = new ExportSelection(
                List.of(), true, List.of(), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP );
        final ExportService.PreparedExport prepared = new ExportService.PreparedExport(
                selection, List.of( page ), "wikantik-export-20260929-1412.zip", loginAdmin() );
        when( stubService.prepare( any(), any() ) ).thenReturn( prepared );
        Mockito.doThrow( new IOException( "broken pipe" ) ).when( stubService ).stream( any(), any() );
        final Resp r = resp();

        servlet().doGet( get( null, Map.of() ), r.mock() );   // must not propagate

        verify( r.mock() ).setStatus( 200 );   // status was already committed before the stream broke
    }

    // -------------------------------------------------------------------------
    // Unknown sub-path
    // -------------------------------------------------------------------------

    @Test
    void unknownSubPathIs404() throws Exception {
        loginAdmin();
        final Resp r = resp();

        servlet().doGet( get( "/nope", Map.of() ), r.mock() );

        verify( r.mock() ).setStatus( 404 );
        verifyNoInteractions( stubService );
    }

    // -------------------------------------------------------------------------
    // requestBaseUrl — the wikantik.baseURL fallback derived from the request
    // -------------------------------------------------------------------------

    @Test
    void requestBaseUrl_omitsDefaultHttpPort() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getScheme() ).thenReturn( "http" );
        when( req.getServerName() ).thenReturn( "wiki.example.com" );
        when( req.getServerPort() ).thenReturn( 80 );
        when( req.getContextPath() ).thenReturn( "" );

        assertEquals( "http://wiki.example.com", ExportRequestParser.requestBaseUrl( req ) );
    }

    @Test
    void requestBaseUrl_omitsDefaultHttpsPort() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getScheme() ).thenReturn( "https" );
        when( req.getServerName() ).thenReturn( "wiki.example.com" );
        when( req.getServerPort() ).thenReturn( 443 );
        when( req.getContextPath() ).thenReturn( "/wikantik" );

        assertEquals( "https://wiki.example.com/wikantik", ExportRequestParser.requestBaseUrl( req ) );
    }

    @Test
    void requestBaseUrl_keepsNonDefaultPort() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getScheme() ).thenReturn( "http" );
        when( req.getServerName() ).thenReturn( "localhost" );
        when( req.getServerPort() ).thenReturn( 8080 );
        when( req.getContextPath() ).thenReturn( "" );

        assertEquals( "http://localhost:8080", ExportRequestParser.requestBaseUrl( req ) );
    }

    /**
     * Proves the production {@link ExportResource#exportService} seam actually wires
     * {@link ExportRequestParser#requestBaseUrl} through to {@code ExportService.fromSubsystems}'s
     * fallback parameter — not just that {@code requestBaseUrl} computes the right string in
     * isolation (covered above). Mocks the static factory itself so the assertion is on the
     * exact fallback argument the seam passed, independent of what {@code fromSubsystems} does
     * with it.
     */
    @Test
    void exportServiceSeam_passesRequestDerivedFallbackToFromSubsystems() throws Exception {
        final ExportResource r = new ExportResource();
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( engine.getServletContext() ).when( cfg ).getServletContext();
        r.init( cfg );
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/export/options" );
        doReturn( "https" ).when( req ).getScheme();
        doReturn( "wiki.example.com" ).when( req ).getServerName();
        doReturn( 443 ).when( req ).getServerPort();
        doReturn( "/wikantik" ).when( req ).getContextPath();
        final ExportService expected = mock( ExportService.class );

        try ( org.mockito.MockedStatic< ExportService > factory = org.mockito.Mockito.mockStatic( ExportService.class ) ) {
            factory.when( () -> ExportService.fromSubsystems( any(), any(), org.mockito.ArgumentMatchers.anyString() ) )
                    .thenReturn( expected );

            final ExportService actual = r.exportService( req );

            assertEquals( expected, actual );
            factory.verify( () -> ExportService.fromSubsystems(
                    org.mockito.ArgumentMatchers.eq( engine ), any(),
                    org.mockito.ArgumentMatchers.eq( "https://wiki.example.com/wikantik" ) ) );
        }
    }
}
