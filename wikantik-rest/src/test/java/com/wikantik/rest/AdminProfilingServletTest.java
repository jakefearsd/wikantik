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
import com.wikantik.observability.JfrProfilingService.RecordingInfo;
import com.wikantik.rest.admin.ProfilingResource;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link AdminProfilingServlet} — the thin HTTP adapter in front of
 * {@link ProfilingResource}. {@link ProfilingResource} itself is fully covered by
 * {@code com.wikantik.rest.admin.ProfilingResourceTest}, so here we mock it out
 * (injected via reflection into the servlet's lazily-built {@code delegate} field)
 * and pin the servlet's routing, validation, and error-mapping behavior.
 */
class AdminProfilingServletTest {

    private final AdminProfilingServlet servlet = new AdminProfilingServlet();

    @AfterEach
    void tearDown() {
        System.clearProperty( "wikantik.profiling.dir" );
        System.clearProperty( "wikantik.profiling.dir.max_bytes" );
    }

    private void injectDelegate( final ProfilingResource delegate ) throws Exception {
        final Field f = AdminProfilingServlet.class.getDeclaredField( "delegate" );
        f.setAccessible( true );
        f.set( servlet, delegate );
    }

    private static RecordingInfo info( final String id, final String status, final Path path ) {
        return new RecordingInfo( id, Instant.parse( "2026-05-20T10:00:00Z" ), 60, "lbl", path, 42L, status );
    }

    // ----- POST /start -----

    @Test
    void startMissingDurationReturns400() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/start", "{}" );

        Mockito.verify( response ).sendError( 400, "missing required field: duration_s" );
        Mockito.verifyNoInteractions( mockDelegate );
    }

    @Test
    void startNonIntegerDurationReturns400() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/start", "{\"duration_s\":\"abc\"}" );

        Mockito.verify( response ).sendError( 400, "duration_s must be an integer" );
        Mockito.verifyNoInteractions( mockDelegate );
    }

    @Test
    void startSuccessWritesRecordingJsonWithLabel() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.start( 30, "stress-run" ) ).thenReturn( "{\"recording_id\":\"abc\"}" );
        injectDelegate( mockDelegate );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = doPostCapturing( "/start",
                "{\"duration_s\":30,\"label\":\"stress-run\"}", sw );

        Mockito.verify( response ).setStatus( 200 );
        Mockito.verify( response ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "{\"recording_id\":\"abc\"}", sw.toString() );
        Mockito.verify( mockDelegate ).start( 30, "stress-run" );
    }

    @Test
    void startWithoutLabelDefaultsToEmptyString() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.start( 15, "" ) ).thenReturn( "{}" );
        injectDelegate( mockDelegate );

        doPost( "/start", "{\"duration_s\":15}" );

        Mockito.verify( mockDelegate ).start( 15, "" );
    }

    @Test
    void startRestErrorMapsToItsStatusAndMessage() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.start( Mockito.anyInt(), Mockito.anyString() ) )
                .thenThrow( new ProfilingResource.RestError( 409, "already recording" ) );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/start", "{\"duration_s\":10}" );

        Mockito.verify( response ).sendError( 409, "already recording" );
    }

    @Test
    void startMalformedJsonReturns400() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/start", "not json{{{" );

        Mockito.verify( response ).sendError( HttpServletResponse.SC_BAD_REQUEST, "invalid JSON body" );
    }

    // ----- POST /stop -----

    @Test
    void stopMissingRecordingIdReturns400() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/stop", "{}" );

        Mockito.verify( response ).sendError( 400, "missing required field: recording_id" );
        Mockito.verifyNoInteractions( mockDelegate );
    }

    @Test
    void stopSuccessWritesRecordingJson() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.stop( "rec-1" ) ).thenReturn( "{\"status\":\"FINISHED\"}" );
        injectDelegate( mockDelegate );

        final StringWriter sw = new StringWriter();
        doPostCapturing( "/stop", "{\"recording_id\":\"rec-1\"}", sw );

        assertEquals( "{\"status\":\"FINISHED\"}", sw.toString() );
        Mockito.verify( mockDelegate ).stop( "rec-1" );
    }

    @Test
    void stopUnknownRecordingIdReturns404() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.stop( "ghost" ) ).thenThrow( new ProfilingResource.RestError( 404, "Unknown recording_id: ghost" ) );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/stop", "{\"recording_id\":\"ghost\"}" );

        Mockito.verify( response ).sendError( 404, "Unknown recording_id: ghost" );
    }

    // ----- POST unknown route -----

    @Test
    void postUnknownRouteReturns404() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doPost( "/bogus", "{}" );

        Mockito.verify( response ).sendError( HttpServletResponse.SC_NOT_FOUND, "unknown profiling route: /bogus" );
    }

    // ----- GET /recordings -----

    @Test
    void getRecordingsListsAllRecordings() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.list() ).thenReturn( "[{\"recording_id\":\"a\"}]" );
        injectDelegate( mockDelegate );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = doGetCapturing( "/recordings", sw );

        Mockito.verify( response ).setStatus( 200 );
        assertEquals( "[{\"recording_id\":\"a\"}]", sw.toString() );
    }

    @Test
    void getRecordingsWithTrailingSlashAlsoListsAll() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.list() ).thenReturn( "[]" );
        injectDelegate( mockDelegate );

        final StringWriter sw = new StringWriter();
        doGetCapturing( "/recordings/", sw );

        assertEquals( "[]", sw.toString() );
    }

    // ----- GET /recordings/{id} -----

    @Test
    void getRecordingBlankIdReturns400() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doGet( "/recordings/   " );

        Mockito.verify( response ).sendError( 400, "bad recording_id segment" );
    }

    @Test
    void getRecordingIdContainingSlashReturns400() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doGet( "/recordings/abc/def" );

        Mockito.verify( response ).sendError( 400, "bad recording_id segment" );
    }

    @Test
    void getRecordingDownloadsFileWhenPresentOnDisk() throws Exception {
        final Path tmp = Files.createTempFile( "profiling-test", ".jfr" );
        try {
            Files.write( tmp, "jfr-bytes".getBytes( StandardCharsets.UTF_8 ) );
            final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
            Mockito.when( mockDelegate.filePathFor( "rec-1" ) ).thenReturn( tmp );
            injectDelegate( mockDelegate );

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/profiling/jfr/recordings/rec-1" );
            Mockito.doReturn( "/recordings/rec-1" ).when( request ).getPathInfo();
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final ServletOutputStream sos = new ServletOutputStream() {
                @Override public void write( final int b ) { baos.write( b ); }
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener( final WriteListener listener ) { }
            };
            Mockito.doReturn( sos ).when( response ).getOutputStream();

            servlet.doGet( request, response );

            assertEquals( "jfr-bytes", baos.toString( StandardCharsets.UTF_8 ) );
            Mockito.verify( response ).setContentType( "application/octet-stream" );
            Mockito.verify( response ).setHeader( Mockito.eq( "Content-Disposition" ),
                    Mockito.contains( tmp.getFileName().toString() ) );
            Mockito.verify( response ).setContentLengthLong( 9L );
            Mockito.verify( response, Mockito.never() ).sendError( Mockito.eq( 404 ), Mockito.anyString() );
        } finally {
            Files.deleteIfExists( tmp );
        }
    }

    @Test
    void getRecordingReturns404WhenFileMissingFromDisk() throws Exception {
        final Path missing = Path.of( System.getProperty( "java.io.tmpdir" ), "no-such-profiling-file.jfr" );
        Files.deleteIfExists( missing );
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.filePathFor( "rec-gone" ) ).thenReturn( missing );
        injectDelegate( mockDelegate );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/profiling/jfr/recordings/rec-gone" );
        Mockito.doReturn( "/recordings/rec-gone" ).when( request ).getPathInfo();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();

        servlet.doGet( request, response );

        Mockito.verify( response ).sendError( 404, "recording file not on disk: no-such-profiling-file.jfr" );
    }

    @Test
    void getRecordingUnknownIdMapsRestErrorTo404() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        Mockito.when( mockDelegate.filePathFor( "ghost" ) )
                .thenThrow( new ProfilingResource.RestError( 404, "Unknown recording_id: ghost" ) );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doGet( "/recordings/ghost" );

        Mockito.verify( response ).sendError( 404, "Unknown recording_id: ghost" );
    }

    // ----- GET unknown route -----

    @Test
    void getUnknownRouteReturns404() throws Exception {
        final ProfilingResource mockDelegate = Mockito.mock( ProfilingResource.class );
        injectDelegate( mockDelegate );

        final HttpServletResponse response = doGet( "/bogus" );

        Mockito.verify( response ).sendError( HttpServletResponse.SC_NOT_FOUND, "unknown profiling route: /bogus" );
    }

    // ----- resolveDelegate() real construction (no reflection injection) -----

    @Test
    void resolveDelegateBuildsRealServiceFromSystemProperties() throws Exception {
        final Path dir = Files.createTempDirectory( "wikantik-profiling-test" );
        System.setProperty( "wikantik.profiling.dir", dir.toString() );
        System.setProperty( "wikantik.profiling.dir.max_bytes", "123456" );

        final StringWriter sw = new StringWriter();
        doGetCapturing( "/recordings", sw );

        // No recordings started against the freshly built real JfrProfilingService.
        assertEquals( "[]", sw.toString(),
                "resolveDelegate() should build a real JfrProfilingService rooted at the configured dir" );
    }

    // ----- helpers -----

    private HttpServletResponse doPost( final String pathInfo, final String body ) throws Exception {
        return doPostCapturing( pathInfo, body, new StringWriter() );
    }

    private HttpServletResponse doPostCapturing( final String pathInfo, final String body, final StringWriter sw ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/profiling/jfr" + pathInfo );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return response;
    }

    private HttpServletResponse doGet( final String pathInfo ) throws Exception {
        return doGetCapturing( pathInfo, new StringWriter() );
    }

    private HttpServletResponse doGetCapturing( final String pathInfo, final StringWriter sw ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/profiling/jfr" + pathInfo );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return response;
    }
}
