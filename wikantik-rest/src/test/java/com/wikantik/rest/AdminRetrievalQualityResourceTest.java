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
import com.wikantik.api.core.Engine;
import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.eval.RetrievalRunResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRetrievalQualityResourceTest {

    private RetrievalQualityRunner runner;
    private AdminRetrievalQualityResource resource;
    private Engine engine;

    @BeforeEach
    void setUp() {
        runner = mock( RetrievalQualityRunner.class );
        engine = mock( Engine.class );
        when( engine.getManager( RetrievalQualityRunner.class ) ).thenReturn( runner );
        resource = new AdminRetrievalQualityResource();
        resource.setEngineForTesting( engine );
    }

    private static RetrievalRunResult sample( final long id, final RetrievalMode m ) {
        return new RetrievalRunResult( id, "core-agent-queries", m,
            0.85, 0.83, 0.92, 0.74,
            Instant.parse( "2026-04-25T03:00:00Z" ),
            Instant.parse( "2026-04-25T03:01:00Z" ),
            16, 0, false );
    }

    @Test
    void get_returns_200_with_recent_runs() throws Exception {
        when( runner.recentRuns( any(), any(), anyInt() ) )
            .thenReturn( List.of( sample( 1L, RetrievalMode.HYBRID ), sample( 2L, RetrievalMode.BM25 ) ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );
        verify( resp ).setStatus( 200 );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        final JsonObject data = body.getAsJsonObject( "data" );
        assertEquals( 2, data.get( "count" ).getAsInt() );
        assertEquals( "hybrid", data.getAsJsonArray( "recent_runs" )
            .get( 0 ).getAsJsonObject().get( "mode" ).getAsString() );
    }

    @Test
    void get_returns_503_when_runner_unavailable() throws Exception {
        when( engine.getManager( RetrievalQualityRunner.class ) ).thenReturn( null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }

    @Test
    void get_with_invalid_mode_returns_400() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "mode" ) ).thenReturn( "fancy_mode_42" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );
        verify( resp ).setStatus( 400 );
    }

    @Test
    void post_run_returns_200_with_result() throws Exception {
        when( runner.runNow( eq( "core-agent-queries" ), eq( RetrievalMode.HYBRID ) ) )
            .thenReturn( sample( 42L, RetrievalMode.HYBRID ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader(
            "{\"query_set_id\":\"core-agent-queries\",\"mode\":\"hybrid\"}" ) ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doPost( req, resp );
        verify( resp ).setStatus( 200 );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        final JsonObject data = body.getAsJsonObject( "data" );
        assertEquals( 42L, data.get( "run_id" ).getAsLong() );
        assertEquals( "hybrid", data.get( "mode" ).getAsString() );
        assertEquals( 0.85, data.get( "ndcg_at_5" ).getAsDouble(), 1e-9 );
    }

    @Test
    void post_run_with_unknown_set_returns_400() throws Exception {
        when( runner.runNow( any(), any() ) )
            .thenThrow( new IllegalArgumentException( "Unknown query_set_id: bogus" ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader(
            "{\"query_set_id\":\"bogus\",\"mode\":\"hybrid\"}" ) ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doPost( req, resp );
        verify( resp ).setStatus( 400 );
    }

    @Test
    void post_run_with_bad_mode_returns_400() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader(
            "{\"query_set_id\":\"core-agent-queries\",\"mode\":\"fancy42\"}" ) ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doPost( req, resp );
        verify( resp ).setStatus( 400 );
    }

    @Test
    void post_run_missing_query_set_returns_400() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader(
            "{\"mode\":\"hybrid\"}" ) ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doPost( req, resp );
        verify( resp ).setStatus( 400 );
    }

    @Test
    void post_unknown_path_returns_404() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/banana" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doPost( req, resp );
        verify( resp ).setStatus( 404 );
    }

    @Test
    void post_run_returns_503_when_runner_unavailable() throws Exception {
        when( engine.getManager( RetrievalQualityRunner.class ) ).thenReturn( null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/run" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doPost( req, resp );
        verify( resp ).setStatus( 503 );
    }

    @Test
    void result_with_null_metrics_serialises_as_json_null() throws Exception {
        when( runner.runNow( any(), any() ) ).thenReturn( new RetrievalRunResult(
            7L, "core-agent-queries", RetrievalMode.HYBRID,
            null, null, null, null,
            Instant.now(), Instant.now(), 0, 16, true ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader(
            "{\"query_set_id\":\"core-agent-queries\",\"mode\":\"hybrid\"}" ) ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doPost( req, resp );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        final JsonObject data = body.getAsJsonObject( "data" );
        assertNotNull( data );
        // Default RestServletBase.GSON drops null fields; absent equals "no score".
        assertEquals( false, data.has( "ndcg_at_5" ) );
        assertTrue( data.get( "degraded" ).getAsBoolean() );
        assertEquals( 16, data.get( "queries_skipped" ).getAsInt() );
    }
}
