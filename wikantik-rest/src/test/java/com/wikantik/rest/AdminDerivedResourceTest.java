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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.derived.DerivedPageIngestionService;
import com.wikantik.derived.DerivedReflowService;
import com.wikantik.derived.IngestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Note: AdminDerivedResource.resolveAdminAuthor() is stubbed in the Stub class below
// to return a fixed author name without requiring engine infrastructure.

/**
 * Unit tests for {@link AdminDerivedResource}.
 *
 * <p>The {@link DerivedReflowService} is injected via a subclass stub — no engine boot needed.
 */
class AdminDerivedResourceTest {

    private DerivedReflowService reflowService;
    private AdminDerivedResource servlet;
    private HttpServletRequest  req;
    private HttpServletResponse resp;
    private StringWriter        body;

    /** Fixed author name returned by the Stub so tests don't need engine infrastructure. */
    private static final String STUB_AUTHOR = "root";

    /** Test servlet subclass that injects the mocked reflow service and a fixed admin author. */
    private final class Stub extends AdminDerivedResource {
        @Override
        protected DerivedReflowService buildReflowService() {
            return reflowService;
        }

        @Override
        protected String resolveAdminAuthor( final jakarta.servlet.http.HttpServletRequest request ) {
            return STUB_AUTHOR;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        reflowService = mock( DerivedReflowService.class );
        servlet = new Stub();
        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    // -----------------------------------------------------------------------
    // GET /admin/derived/status
    // -----------------------------------------------------------------------

    @Test
    void status_returnsCorrectJson() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );

        final DerivedReflowService.ReflowStatus status =
            new DerivedReflowService.ReflowStatus( 5, 2, DerivedPageIngestionService.CURRENT_EXTRACTOR_VERSION );
        when( reflowService.status() ).thenReturn( status );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 5,  json.get( "derivedTotal" ).getAsInt() );
        assertEquals( 2,  json.get( "staleCount" ).getAsInt() );
        assertEquals( DerivedPageIngestionService.CURRENT_EXTRACTOR_VERSION,
                          json.get( "currentExtractorVersion" ).getAsInt() );
    }

    @Test
    void get_unknownAction_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/unknown" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // POST /admin/derived/reflow?page=MyPage  (single-page)
    // -----------------------------------------------------------------------

    @Test
    void reflow_singlePage_callsReflowAndReturnsJson() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/reflow" );
        when( req.getParameter( "page" ) ).thenReturn( "MyReport" );

        when( reflowService.reflow( eq( "MyReport" ), eq( STUB_AUTHOR ) ) )
            .thenReturn( IngestResult.updated( "MyReport" ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "updated", json.get( "status" ).getAsString() );
        assertEquals( "MyReport", json.get( "page" ).getAsString() );

        verify( reflowService, times( 1 ) ).reflow( "MyReport", STUB_AUTHOR );
        verify( reflowService, never() ).reflowAll( any() );
    }

    // -----------------------------------------------------------------------
    // POST /admin/derived/reflow  (corpus-wide)
    // -----------------------------------------------------------------------

    @Test
    void reflow_corpusWide_callsReflowAllAndReturnsJson() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/reflow" );
        when( req.getParameter( "page" ) ).thenReturn( null );

        final DerivedReflowService.ReflowSummary summary = new DerivedReflowService.ReflowSummary( 3, 1, 0 );
        when( reflowService.reflowAll( eq( STUB_AUTHOR ) ) ).thenReturn( summary );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 3, json.get( "reflowed" ).getAsInt() );
        assertEquals( 1, json.get( "skipped" ).getAsInt() );
        assertEquals( 0, json.get( "failed" ).getAsInt() );

        verify( reflowService, times( 1 ) ).reflowAll( STUB_AUTHOR );
        verify( reflowService, never() ).reflow( anyString(), any() );
    }

    @Test
    void post_unknownAction_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // Security: author is derived from the session principal, not caller input
    // -----------------------------------------------------------------------

    /**
     * The reflow handler must attribute the reflow to the authenticated admin session
     * principal (here "root" from the Stub), never pass null or a caller-supplied value.
     * Verifies both the single-page and corpus-wide paths.
     */
    @Test
    void reflow_authorDerivedFromSession_notNullOrCallerSupplied() throws Exception {
        // Single-page path
        when( req.getPathInfo() ).thenReturn( "/reflow" );
        when( req.getParameter( "page" ) ).thenReturn( "SecurePage" );
        when( reflowService.reflow( eq( "SecurePage" ), eq( STUB_AUTHOR ) ) )
            .thenReturn( IngestResult.updated( "SecurePage" ) );

        servlet.doPost( req, resp );

        // Must be called with STUB_AUTHOR ("root"), never with null
        verify( reflowService ).reflow( "SecurePage", STUB_AUTHOR );
        verify( reflowService, never() ).reflow( anyString(), isNull() );
    }

    @Test
    void reflowAll_authorDerivedFromSession_notNullOrCallerSupplied() throws Exception {
        // Corpus-wide path
        when( req.getPathInfo() ).thenReturn( "/reflow" );
        when( req.getParameter( "page" ) ).thenReturn( null );
        final DerivedReflowService.ReflowSummary summary = new DerivedReflowService.ReflowSummary( 1, 0, 0 );
        when( reflowService.reflowAll( eq( STUB_AUTHOR ) ) ).thenReturn( summary );

        servlet.doPost( req, resp );

        // Must be called with STUB_AUTHOR ("root"), never with null
        verify( reflowService ).reflowAll( STUB_AUTHOR );
        verify( reflowService, never() ).reflowAll( isNull() );
    }
}
