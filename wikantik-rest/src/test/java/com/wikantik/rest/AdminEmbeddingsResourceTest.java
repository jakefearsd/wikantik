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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.EmbeddingIndexStatsRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AdminEmbeddingsResource}: works with the Knowledge Graph off (no KnowledgeGraphService registered). */
class AdminEmbeddingsResourceTest {

    private final Gson gson = new Gson();
    private TestEngine engine;
    private EmbeddingConfig config;
    private EmbeddingIndexStatsRepository stats;
    private AdminEmbeddingsResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        stats = Mockito.mock( EmbeddingIndexStatsRepository.class );
        config = null;
        servlet = RestTestSupport.initServlet( () -> new AdminEmbeddingsResource() {
            @Override EmbeddingConfig embeddingConfig() { return config; }
            @Override EmbeddingIndexStatsRepository buildStatsRepository() { return stats; }
        }, engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private JsonObject call( final String pathInfo, final String method ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/embeddings" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        if ( "POST".equals( method ) ) {
            servlet.doPost( req, resp );
        } else {
            servlet.doGet( req, resp );
        }
        final String body = sw.toString();
        return body.isEmpty() ? new JsonObject() : gson.fromJson( body, JsonObject.class );
    }

    @Test
    void status_whenEmbeddingsDisabled_isNotReadyWithZeros() throws Exception {
        final Properties p = new Properties();
        config = EmbeddingConfig.fromProperties( p );
        assertFalse( config.enabled() );
        final JsonObject o = call( "/status", "GET" );
        assertFalse( o.get( "ready" ).getAsBoolean() );
        assertEquals( 0, o.get( "pagesEmbedded" ).getAsInt() );
        assertEquals( 0, o.get( "chunksTotal" ).getAsLong() );
        assertEquals( 0, o.get( "chunksEmbedded" ).getAsLong() );
        assertEquals( 0, o.get( "dimension" ).getAsInt() );
        Mockito.verifyNoInteractions( stats );
    }

    @Test
    void status_whenEnabled_reportsRealIndexCounts() throws Exception {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( "wikantik.genai.mode", "FULL" );
        config = EmbeddingConfig.fromProperties( p );
        assertTrue( config.enabled() );
        Mockito.when( stats.stats( config.model().code() ) )
               .thenReturn( new EmbeddingIndexStatsRepository.Stats( 7, 120L, 100L ) );
        final JsonObject o = call( "/status", "GET" );
        assertTrue( o.get( "ready" ).getAsBoolean() );
        assertEquals( config.model().code(), o.get( "model" ).getAsString() );
        assertEquals( config.model().dimension(), o.get( "dimension" ).getAsInt() );
        assertEquals( 7, o.get( "pagesEmbedded" ).getAsInt() );
        assertEquals( 120L, o.get( "chunksTotal" ).getAsLong() );
        assertEquals( 100L, o.get( "chunksEmbedded" ).getAsLong() );
    }

    @Test
    void status_withNoEmbeddedChunks_isNotReady() throws Exception {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( "wikantik.genai.mode", "FULL" );
        config = EmbeddingConfig.fromProperties( p );
        Mockito.when( stats.stats( Mockito.anyString() ) )
               .thenReturn( new EmbeddingIndexStatsRepository.Stats( 0, 5L, 0L ) );
        assertFalse( call( "/status", "GET" ).get( "ready" ).getAsBoolean() );
    }

    @Test
    void status_withoutDataSource_isNotReady() throws Exception {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( "wikantik.genai.mode", "FULL" );
        config = EmbeddingConfig.fromProperties( p );
        stats = null;
        final JsonObject o = call( "/status", "GET" );
        assertFalse( o.get( "ready" ).getAsBoolean() );
        assertEquals( 0, o.get( "chunksTotal" ).getAsLong() );
    }

    @Test
    void unknownSubResource_is404() throws Exception {
        assertEquals( 404, call( "/bogus", "GET" ).get( "status" ).getAsInt() );
        assertEquals( 404, call( "/status", "POST" ).get( "status" ).getAsInt() );
    }

    @Test
    void missingPath_is400() throws Exception {
        assertEquals( 400, call( "/", "GET" ).get( "status" ).getAsInt() );
    }

    @Test
    void pagesWithoutFrontmatter_listsPaged() throws Exception {
        final JsonObject o = call( "/pages-without-frontmatter", "GET" );
        assertTrue( o.has( "pages" ) );
        assertTrue( o.has( "total" ) );
    }

    @Test
    void backfillStatus_returnsFields() throws Exception {
        final JsonObject o = call( "/backfill-frontmatter", "GET" );
        assertTrue( o.has( "running" ) && o.has( "total" ) && o.has( "processed" ) && o.has( "errors" ) );
        assertFalse( o.get( "running" ).getAsBoolean() );
    }

    @Test
    void backfill_postInvalidatesPagesWithoutFrontmatterCache() throws Exception {
        engine.saveText( "NoFrontmatterPage", "Just a body, no YAML block." );
        assertTrue( containsPage( call( "/pages-without-frontmatter", "GET" ), "NoFrontmatterPage" ) );

        assertEquals( "started", call( "/backfill-frontmatter", "POST" ).get( "status" ).getAsString() );

        final long deadline = System.currentTimeMillis() + 5000;
        boolean stillListed = true;
        while ( System.currentTimeMillis() < deadline ) {
            stillListed = containsPage( call( "/pages-without-frontmatter", "GET" ), "NoFrontmatterPage" );
            if ( !stillListed ) {
                break;
            }
            Thread.sleep( 50 );
        }
        assertFalse( stillListed, "backfill completion must invalidate the pages-without-frontmatter cache" );
    }

    private static boolean containsPage( final JsonObject list, final String name ) {
        if ( !list.has( "pages" ) ) {
            return false;
        }
        for ( final var el : list.getAsJsonArray( "pages" ) ) {
            if ( name.equals( el.getAsJsonObject().get( "name" ).getAsString() ) ) {
                return true;
            }
        }
        return false;
    }
}
