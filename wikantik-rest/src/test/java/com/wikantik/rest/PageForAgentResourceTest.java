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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.agent.HeadingOutline;
import com.wikantik.api.agent.KeyFact;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.Audience;
import com.wikantik.api.structure.Confidence;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageForAgentResourceTest {

    private ForAgentProjectionService svc;
    private PageForAgentResource resource;

    @BeforeEach
    void setUp() {
        svc = mock( ForAgentProjectionService.class );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( ForAgentProjectionService.class ) ).thenReturn( svc );
        resource = new PageForAgentResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void empty_path_returns_400() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
    }

    @Test
    void unknown_id_returns_404() throws Exception {
        when( svc.project( "missing" ) ).thenReturn( Optional.empty() );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/missing" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
    }

    @Test
    void happy_path_serialises_full_envelope() throws Exception {
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", "article",
                "wikantik-development",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                Instant.parse( "2026-04-20T00:00:00Z" ), "jakefear",
                Instant.parse( "2026-04-22T11:10:00Z" ),
                "Operator reference for hybrid retrieval.",
                List.of( new KeyFact( "BM25 + dense via RRF.", "frontmatter" ) ),
                List.of( new HeadingOutline( 2, "Wiring" ) ),
                List.of(), List.of(), List.of(), List.of(),
                null,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/01ABC" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        final JsonObject data = body.getAsJsonObject( "data" );
        assertEquals( "01ABC",            data.get( "id" ).getAsString() );
        assertEquals( "HybridRetrieval",  data.get( "slug" ).getAsString() );
        assertEquals( "authoritative",    data.get( "confidence" ).getAsString() );
        final JsonArray facts = data.getAsJsonArray( "key_facts" );
        assertEquals( 1, facts.size() );
        final JsonArray outline = data.getAsJsonArray( "headings_outline" );
        assertEquals( "Wiring", outline.get( 0 ).getAsJsonObject().get( "text" ).getAsString() );
        assertTrue( data.has( "runbook" ) );
        assertTrue( data.get( "runbook" ).isJsonNull() );
        assertEquals( false, data.get( "degraded" ).getAsBoolean() );
    }

    @Test
    void degraded_flag_propagates() throws Exception {
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "Slug", "Title", "article", null,
                Audience.HUMANS_AND_AGENTS, Confidence.PROVISIONAL,
                null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null,
                "/api/pages/Slug", "/wiki/Slug?format=md",
                true, List.of( "headings_outline", "key_facts" ) );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/01ABC" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final JsonObject data = JsonParser.parseString( sw.toString() )
                .getAsJsonObject().getAsJsonObject( "data" );
        assertTrue( data.get( "degraded" ).getAsBoolean() );
        final JsonArray missing = data.getAsJsonArray( "missing_fields" );
        assertEquals( 2, missing.size() );
    }
}
