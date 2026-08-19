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
                "wikantik-development", List.of( "retrieval" ), "summary", Instant.EPOCH, Optional.empty(), false ) ) );

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
                "cluster", List.of(), "secret summary", Instant.EPOCH, Optional.empty(), false ) ) );
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
}
