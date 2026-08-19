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
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
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

class StructureResourceTest {

    private StructuralIndexService svc;
    private TestableStructureResource resource;

    /**
     * Overrides the ACL seam so these tests can control which slugs are "viewable"
     * without standing up the whole auth subsystem — that boundary
     * ({@link com.wikantik.auth.permissions.PermissionFilter}) is tested on its own.
     * {@code visible == null} means allow all (the pre-ACL-fix serialization tests).
     */
    static final class TestableStructureResource extends StructureResource {
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
        resource = new TestableStructureResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void clusters_returns_cluster_list() throws Exception {
        when( svc.listClusters() ).thenReturn( List.of( new ClusterSummary(
                "wikantik-development",
                new PageDescriptor( "01A", "WikantikDevelopment", "Wikantik Development",
                        PageType.HUB, "wikantik-development", List.of(), "hub", Instant.EPOCH, Optional.empty(), false ),
                12,
                Instant.parse( "2026-04-01T00:00:00Z" ) ) ) );

        final JsonObject body = callGet( "/clusters" );
        assertTrue( body.has( "data" ) );
        final var clusters = body.getAsJsonObject( "data" ).getAsJsonArray( "clusters" );
        assertEquals( 1, clusters.size() );
        assertEquals( "wikantik-development", clusters.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void tags_returns_tag_dictionary() throws Exception {
        when( svc.listTags( 1 ) ).thenReturn( List.of(
                new TagSummary( "retrieval", 5, List.of( "01X", "01Y" ) ) ) );
        final JsonObject body = callGet( "/tags" );
        assertEquals( 1, body.getAsJsonObject( "data" ).getAsJsonArray( "tags" ).size() );
    }

    @Test
    void sitemap_returns_all_pages() throws Exception {
        when( svc.sitemap() ).thenReturn( new Sitemap(
                List.of( new PageDescriptor( "01A", "Slug", "T", PageType.ARTICLE, null, List.of(),
                        "summary", Instant.EPOCH, Optional.empty(), false ) ),
                1, Instant.EPOCH ) );
        final JsonObject body = callGet( "/sitemap" );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
    }

    @Test
    void unknown_path_returns_404() throws Exception {
        final HttpServletResponse resp = callRaw( "/does-not-exist" );
        verify( resp ).setStatus( 404 );
    }

    /**
     * SECURITY: /api/structure is public and unauthenticated, and a descriptor
     * carries the page's title/cluster/tags/summary. A page the caller may not
     * view must be dropped from every listing.
     */
    @Test
    void sitemap_hides_pages_the_caller_cannot_view() throws Exception {
        when( svc.sitemap() ).thenReturn( new Sitemap(
                List.of(
                        new PageDescriptor( "01A", "PublicPage", "Public", PageType.ARTICLE, null, List.of(),
                                "public summary", Instant.EPOCH, Optional.empty(), false ),
                        new PageDescriptor( "01B", "SecretPage", "Secret", PageType.ARTICLE, null, List.of(),
                                "secret summary", Instant.EPOCH, Optional.empty(), false ) ),
                2, Instant.EPOCH ) );
        resource.visible = java.util.Set.of( "PublicPage" );   // SecretPage is ACL-restricted

        final JsonObject body = callGet( "/sitemap" );
        final var pages = body.getAsJsonObject( "data" ).getAsJsonArray( "pages" );
        assertEquals( 1, pages.size(), "restricted page must be dropped" );
        assertEquals( "PublicPage", pages.get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt(),
                "count must reflect only viewable pages" );
        assertFalse( body.toString().contains( "secret summary" ),
                "the restricted page's summary must not leak anywhere in the payload" );
    }

    @Test
    void pages_listing_hides_restricted_pages() throws Exception {
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "01A", "PublicPage", "Public", PageType.ARTICLE, null, List.of(),
                        "s", Instant.EPOCH, Optional.empty(), false ),
                new PageDescriptor( "01B", "SecretPage", "Secret", PageType.ARTICLE, null, List.of(),
                        "s", Instant.EPOCH, Optional.empty(), false ) ) );
        resource.visible = java.util.Set.of( "PublicPage" );

        final JsonObject body = callGet( "/pages" );
        final var pages = body.getAsJsonObject( "data" ).getAsJsonArray( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "PublicPage", pages.get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    private JsonObject callGet( final String pathInfo ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        resource.doGet( req, resp );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }

    private HttpServletResponse callRaw( final String pathInfo ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        resource.doGet( req, resp );
        return resp;
    }
}
