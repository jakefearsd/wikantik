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
import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.Sitemap;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StructureResourceTest {

    private StructuralIndexService svc;
    private StructureResource resource;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new StructureResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void clusters_returns_cluster_list() throws Exception {
        when( svc.listClusters() ).thenReturn( List.of( new ClusterSummary(
                "wikantik-development",
                new PageDescriptor( "01A", "WikantikDevelopment", "Wikantik Development",
                        PageType.HUB, "wikantik-development", List.of(), "hub", Instant.EPOCH ),
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
                        "summary", Instant.EPOCH ) ),
                1, Instant.EPOCH ) );
        final JsonObject body = callGet( "/sitemap" );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
    }

    @Test
    void unknown_path_returns_404() throws Exception {
        final HttpServletResponse resp = callRaw( "/does-not-exist" );
        verify( resp ).setStatus( 404 );
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
