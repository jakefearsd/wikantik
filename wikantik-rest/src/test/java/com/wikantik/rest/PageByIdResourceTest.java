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
import com.wikantik.api.core.Engine;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
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

class PageByIdResourceTest {

    private StructuralIndexService svc;
    private TestablePageByIdResource resource;

    /** ACL seam override — see the note in StructureResourceTest. null = allow all. */
    static final class TestablePageByIdResource extends PageByIdResource {
        private static final long serialVersionUID = 1L;
        transient java.util.Set< String > visible;   // null = allow all
        @Override
        protected java.util.Set< String > filterViewable( final HttpServletRequest request,
                                                           final java.util.Collection< String > pageNames ) {
            if ( visible == null ) {
                return new java.util.HashSet<>( pageNames );
            }
            final java.util.Set< String > out = new java.util.HashSet<>( pageNames );
            out.retainAll( visible );
            return out;
        }
    }

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new TestablePageByIdResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void resolves_id_to_descriptor() throws Exception {
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of( new PageDescriptor(
                "01A", "HybridRetrieval", "Hybrid Retrieval", PageType.ARTICLE,
                "wikantik-development", List.of( "retrieval" ), "summary", Instant.EPOCH, false ) ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/01A" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertEquals( "HybridRetrieval",
                body.getAsJsonObject( "data" ).get( "slug" ).getAsString() );
    }

    @Test
    void unknown_id_returns_404() throws Exception {
        when( svc.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/missing" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
    }

    /**
     * SECURITY: a restricted page's canonical_id must be indistinguishable from a
     * missing one — same 404 — so the endpoint neither confirms the page exists nor
     * leaks its title/cluster/summary to an unauthenticated caller.
     */
    @Test
    void restricted_id_returns_404_hiding_existence() throws Exception {
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of( new PageDescriptor(
                "01A", "SecretPage", "Secret", PageType.ARTICLE,
                "cluster", List.of(), "secret summary", Instant.EPOCH, false ) ) );
        resource.visible = java.util.Set.of();   // caller may view nothing

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/01A" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
        assertFalse( sw.toString().contains( "SecretPage" ), "restricted slug must not leak" );
        assertFalse( sw.toString().contains( "secret summary" ), "restricted summary must not leak" );
    }

    // ---- mutation-testing follow-up

    private StringWriter call( final String pathInfo, final HttpServletResponse resp ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        resource.doGet( req, resp );
        return sw;
    }

    @Test
    void aMissingOrBareSlashPathIs400WithAnErrorBodyAndAOneCharacterIdIsLookedUp() throws Exception {
        for ( final String path : new String[] { null, "", "/" } ) {
            final HttpServletResponse resp = mock( HttpServletResponse.class );
            final StringWriter sw = call( path, resp );
            verify( resp ).setStatus( 400 );
            verify( resp ).setContentType( "application/json; charset=UTF-8" );
            assertEquals( "{\"error\":\"canonical_id required in path\"}", sw.toString() );
        }
        when( svc.getByCanonicalId( "X" ) ).thenReturn( Optional.empty() );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        call( "/X", resp );
        verify( svc ).getByCanonicalId( "X" );
        verify( resp ).setStatus( 404 );
    }

    @Test
    void theNotFoundAnswerIsJsonAndNamesTheIdTheCallerSent() throws Exception {
        when( svc.getByCanonicalId( "nope" ) ).thenReturn( Optional.empty() );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        final StringWriter sw = call( "/nope", resp );

        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "{\"error\":\"no page for canonical_id nope\"}", sw.toString() );
    }

    @Test
    void withNoStructuralIndexTheAnswerIs503() throws Exception {
        final WikiEngine noIndex = mock( WikiEngine.class );
        when( noIndex.getManager( StructuralIndexService.class ) ).thenReturn( null );
        resource.setEngineForTesting( noIndex );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        final StringWriter sw = call( "/01A", resp );

        verify( resp ).setStatus( 503 );
        assertEquals( "{\"error\":\"structural index unavailable\"}", sw.toString() );
    }

    @Test
    void theDescriptorEnvelopeCarriesEveryFieldAndOmitsTheAbsentOnes() throws Exception {
        when( svc.getByCanonicalId( "01F" ) ).thenReturn( Optional.of( new PageDescriptor(
                "01F", "FullPage", "Full Page", PageType.HUB, "some-cluster", List.of( "a", "b" ), "the summary",
                Instant.parse( "2026-01-02T03:04:05Z" ), false ) ) );
        when( svc.getByCanonicalId( "01B" ) ).thenReturn( Optional.of( new PageDescriptor(
                "01B", "BarePage", "Bare Page", PageType.ARTICLE, null, List.of(), null, null, false ) ) );

        final HttpServletResponse fullResp = mock( HttpServletResponse.class );
        final JsonObject full = JsonParser.parseString( call( "/01F", fullResp ).toString() )
                .getAsJsonObject().getAsJsonObject( "data" );
        verify( fullResp ).setStatus( 200 );
        verify( fullResp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "01F", full.get( "id" ).getAsString() );
        assertEquals( "FullPage", full.get( "slug" ).getAsString() );
        assertEquals( "Full Page", full.get( "title" ).getAsString() );
        assertEquals( "hub", full.get( "type" ).getAsString() );
        assertEquals( "some-cluster", full.get( "cluster" ).getAsString() );
        assertEquals( "the summary", full.get( "summary" ).getAsString() );
        assertEquals( "2026-01-02T03:04:05Z", full.get( "updated" ).getAsString() );
        assertEquals( "[\"a\",\"b\"]", full.getAsJsonArray( "tags" ).toString() );

        final JsonObject bare = JsonParser.parseString( call( "/01B", mock( HttpServletResponse.class ) ).toString() )
                .getAsJsonObject().getAsJsonObject( "data" );
        assertFalse( bare.has( "cluster" ) );
        assertFalse( bare.has( "summary" ) );
        assertFalse( bare.has( "updated" ) );
        assertEquals( 0, bare.getAsJsonArray( "tags" ).size() );
    }
}
