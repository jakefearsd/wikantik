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
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAgentGradeAuditServlet} — the servlet adapter in
 * front of {@code AgentGradeAuditResource}, covering the lazy-delegate
 * construction, its three 503 guards, and query-parameter parsing.
 */
class AdminAgentGradeAuditServletTest {

    private StructuralIndexService     index;
    private ReferenceManager           refs;
    private WikiEngine                 engine;
    private AdminAgentGradeAuditServlet servlet;
    private HttpServletRequest         req;
    private HttpServletResponse        resp;
    private StringWriter               body;

    @BeforeEach
    void setUp() throws Exception {
        index = mock( StructuralIndexService.class );
        refs  = mock( ReferenceManager.class );
        when( index.listPagesByFilter( any( StructuralFilter.class ) ) )
                .thenReturn( List.< PageDescriptor >of() );

        engine = mock( WikiEngine.class );
        wire( index, refs );

        servlet = new AdminAgentGradeAuditServlet();
        servlet.setEngine( engine );

        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    private void wire( final StructuralIndexService svc, final ReferenceManager refMgr ) {
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( svc, null, refMgr, null, null, null, null, null ) );
    }

    private JsonObject json() {
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    // ------------------------------------------------------------------ 503 guards

    @Test
    void missingStructuralIndex_returns503() throws Exception {
        wire( null, refs );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( body.toString().contains( "structural index service unavailable" ), body.toString() );
    }

    @Test
    void missingReferenceManager_returns503() throws Exception {
        wire( index, null );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( body.toString().contains( "reference manager not yet initialised" ), body.toString() );
    }

    @Test
    void aFailed503DoesNotCacheADelegate_soALaterReadySubsystemSucceeds() throws Exception {
        wire( null, refs );
        servlet.doGet( req, resp );
        verify( resp ).setStatus( 503 );

        // Subsystem finishes registering; the next request must work.
        wire( index, refs );
        final StringWriter second = new StringWriter();
        final HttpServletResponse resp2 = mock( HttpServletResponse.class );
        when( resp2.getWriter() ).thenReturn( new PrintWriter( second ) );

        servlet.doGet( req, resp2 );

        verify( resp2 ).setStatus( 200 );
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void emptyIndex_returns200WithZeroTotal() throws Exception {
        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        final JsonObject out = json();
        assertEquals( 0, out.get( "total" ).getAsInt() );
        assertEquals( 50, out.get( "limit" ).getAsInt(), "default limit" );
        assertEquals( 0, out.get( "offset" ).getAsInt(), "default offset" );
        assertEquals( 0, out.getAsJsonArray( "pages" ).size() );
    }

    @Test
    void delegateIsBuiltOnceAndReusedAcrossRequests() throws Exception {
        servlet.doGet( req, resp );

        final HttpServletResponse resp2 = mock( HttpServletResponse.class );
        when( resp2.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        // Everything the first request did to build the delegate is now history;
        // the second request must not touch the subsystem bridge again.
        org.mockito.Mockito.clearInvocations( engine );

        servlet.doGet( req, resp2 );

        verify( index, times( 2 ) ).listPagesByFilter( any( StructuralFilter.class ) );
        verify( engine, never() ).getPageGraphSubsystem();
    }

    // ------------------------------------------------------------------ parameter parsing

    @Test
    void explicitLimitAndOffsetAreEchoed() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "10" );
        when( req.getParameter( "offset" ) ).thenReturn( "5" );

        servlet.doGet( req, resp );

        final JsonObject out = json();
        assertEquals( 10, out.get( "limit" ).getAsInt() );
        assertEquals( 5, out.get( "offset" ).getAsInt() );
    }

    @Test
    void whitespacePaddedNumbersAreParsed() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "  25  " );

        servlet.doGet( req, resp );

        assertEquals( 25, json().get( "limit" ).getAsInt() );
    }

    @Test
    void nonNumericParametersFallBackToDefaults() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "not-a-number" );
        when( req.getParameter( "offset" ) ).thenReturn( "???" );

        servlet.doGet( req, resp );

        final JsonObject out = json();
        assertEquals( 50, out.get( "limit" ).getAsInt() );
        assertEquals( 0, out.get( "offset" ).getAsInt() );
    }

    @Test
    void blankParametersFallBackToDefaults() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "   " );
        when( req.getParameter( "offset" ) ).thenReturn( "" );

        servlet.doGet( req, resp );

        assertEquals( 50, json().get( "limit" ).getAsInt() );
        assertEquals( 0, json().get( "offset" ).getAsInt() );
    }

    @Test
    void outOfRangeLimitIsClampedByTheDelegate() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "9999" );

        servlet.doGet( req, resp );

        assertEquals( 200, json().get( "limit" ).getAsInt(), "the resource caps limit at 200" );
    }

    @Test
    void negativeOffsetIsNormalisedToZero() throws Exception {
        when( req.getParameter( "offset" ) ).thenReturn( "-7" );

        servlet.doGet( req, resp );

        assertEquals( 0, json().get( "offset" ).getAsInt() );
    }
}
