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
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.agent.HeadingOutline;
import com.wikantik.api.agent.KeyFact;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.Audience;
import com.wikantik.api.pagegraph.Confidence;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
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

    private TestEngine engine;
    private ForAgentProjectionService svc;
    private PageForAgentResource resource;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        // Public pages the wire-shape fixtures project. Unrestricted → the default test
        // security policy grants anonymous "view", so checkPagePermission passes and the
        // existing wire-shape assertions are unchanged.
        engine.saveText( "HybridRetrieval", "Public body for the for-agent wire-shape tests." );
        engine.saveText( "Slug", "Public body." );

        // The projection service is mocked: these tests exercise the HTTP/serialization
        // layer and the new ACL gate, not the projection logic itself.
        svc = mock( ForAgentProjectionService.class );
        engine.setManager( ForAgentProjectionService.class, svc );

        resource = new PageForAgentResource();
        resource.setEngineForTesting( engine );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "HybridRetrieval" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "Slug" ); }            catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "ForAgentAclPage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void empty_path_returns_400() throws Exception {
        final HttpServletRequest req = anonReq( "" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
    }

    @Test
    void unknown_id_returns_404() throws Exception {
        when( svc.project( "missing" ) ).thenReturn( Optional.empty() );
        final HttpServletRequest req = anonReq( "/missing" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
    }

    /**
     * Security: the projection exposes a page's summary, key facts, and heading outline.
     * An anonymous caller holding (or guessing) the canonical_id of an ACL-restricted page
     * must be denied (403) and none of the projection content may leak.
     */
    @Test
    void acl_restricted_projection_denied_for_anonymous() throws Exception {
        engine.saveText( "ForAgentAclPage", "[{ALLOW view Admin}]\nRestricted body." );

        final ForAgentProjection p = new ForAgentProjection(
                "01ZZZ", "ForAgentAclPage", "Restricted", "article", null,
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                null, null, null,
                "Top secret operator summary.",
                List.of( new KeyFact( "Secret fact.", "frontmatter" ) ),
                List.of(), List.of(), List.of(),
                null,
                null, false,
                "/api/pages/ForAgentAclPage", "/wiki/ForAgentAclPage?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ZZZ" ) ).thenReturn( Optional.of( p ) );

        final HttpServletRequest req = anonReq( "/01ZZZ" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final String body = sw.toString();
        // Security property first: no projection content may leak, regardless of envelope shape.
        assertFalse( body.contains( "Top secret operator summary." ),
                "Restricted projection content must never leak through /api/pages/for-agent" );
        assertFalse( body.contains( "Secret fact." ),
                "Restricted key facts must never leak through /api/pages/for-agent" );
        final JsonObject obj = JsonParser.parseString( body ).getAsJsonObject();
        assertTrue( obj.has( "error" ) && obj.get( "error" ).getAsBoolean(),
                "Projection of ACL-restricted page must be denied for anonymous" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "Projection of ACL-restricted page must return 403 for unauthorized user" );
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
                List.of(), List.of(),
                null,
                null, false,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final HttpServletRequest req = anonReq( "/01ABC" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
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
        // Guard the optional/scalar fields too, so the toJson extraction refactor
        // cannot silently drop one.
        assertEquals( "Hybrid Retrieval", data.get( "title" ).getAsString() );
        assertEquals( "article",          data.get( "type" ).getAsString() );
        assertEquals( "wikantik-development", data.get( "cluster" ).getAsString() );
        assertTrue( data.has( "audience" ) && !data.get( "audience" ).isJsonNull() );
        assertEquals( "2026-04-20T00:00:00Z", data.get( "verified_at" ).getAsString() );
        assertEquals( "jakefear",             data.get( "verified_by" ).getAsString() );
        assertEquals( "2026-04-22T11:10:00Z", data.get( "updated" ).getAsString() );
        assertEquals( "Operator reference for hybrid retrieval.", data.get( "summary" ).getAsString() );
        assertEquals( "BM25 + dense via RRF.", facts.get( 0 ).getAsJsonObject().get( "text" ).getAsString() );
        assertEquals( "frontmatter",           facts.get( 0 ).getAsJsonObject().get( "source" ).getAsString() );
        assertEquals( 2, outline.get( 0 ).getAsJsonObject().get( "level" ).getAsInt() );
        assertEquals( "/api/pages/HybridRetrieval",          data.get( "full_body_url" ).getAsString() );
        assertEquals( "/wiki/HybridRetrieval?format=md",     data.get( "raw_markdown_url" ).getAsString() );
        assertTrue( data.has( "key_facts" ) && data.has( "headings_outline" )
                && data.has( "recent_changes" ) && data.has( "mcp_tool_hints" )
                && data.has( "missing_fields" ) );
    }

    @Test
    void wire_shape_includes_agent_hints_and_summary_synthesized() throws Exception {
        // Fixture passes agentHints=null, summarySynthesized=false (the null-serialisation path).
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", "article",
                "wikantik-development",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                null, null, null,
                "Operator reference for hybrid retrieval.",
                List.of(), List.of(), List.of(), List.of(),
                null,
                null, false,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final HttpServletRequest req = anonReq( "/01ABC" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final JsonObject data = JsonParser.parseString( sw.toString() )
                .getAsJsonObject().getAsJsonObject( "data" );

        assertTrue( data.has( "agent_hints" ),
                "agent_hints field expected on /for-agent response" );
        assertTrue( data.has( "summary_synthesized" ),
                "summary_synthesized field expected on /for-agent response" );
        // Fixture passes agentHints=null — should serialise as JSON null, not be absent.
        assertTrue( data.get( "agent_hints" ).isJsonNull(),
                "fixture passes agentHints=null which should serialise as JSON null" );
        assertEquals( false, data.get( "summary_synthesized" ).getAsBoolean() );
    }

    @Test
    void degraded_flag_propagates() throws Exception {
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "Slug", "Title", "article", null,
                Audience.HUMANS_AND_AGENTS, Confidence.PROVISIONAL,
                null, null, null, null,
                List.of(), List.of(), List.of(), List.of(),
                null,
                null, false,
                "/api/pages/Slug", "/wiki/Slug?format=md",
                true, List.of( "headings_outline", "key_facts" ), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final HttpServletRequest req = anonReq( "/01ABC" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final JsonObject data = JsonParser.parseString( sw.toString() )
                .getAsJsonObject().getAsJsonObject( "data" );
        assertTrue( data.get( "degraded" ).getAsBoolean() );
        final JsonArray missing = data.getAsJsonArray( "missing_fields" );
        assertEquals( 2, missing.size() );
    }

    // ----- Helper methods -----

    /**
     * Builds a request backed by a fresh (not-logged-in) HttpSession id so the resource
     * resolves the caller as anonymous. {@code pathInfo} is the canonical-id path segment
     * (e.g. {@code "/01ABC"}), or {@code ""} to exercise the missing-id branch.
     */
    private HttpServletRequest anonReq( final String pathInfo ) {
        final HttpSession session = mock( HttpSession.class );
        when( session.getId() ).thenReturn( "anon-fa-" + System.nanoTime() );
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/pages/for-agent" + pathInfo );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        when( req.getSession() ).thenReturn( session );
        when( req.getSession( anyBoolean() ) ).thenReturn( session );
        return req;
    }
}
