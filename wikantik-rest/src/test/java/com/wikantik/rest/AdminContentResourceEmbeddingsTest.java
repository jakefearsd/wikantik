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
import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.admin.IndexStatusSnapshot;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * Characterisation of the embedding-reindex route and the {@code embeddings} block of
 * {@code index-status}, plus the 503 guards on the index and chunk routes.
 */
class AdminContentResourceEmbeddingsTest {

    private TestEngine engine;
    private AdminContentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        servlet = new AdminContentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void reindexEmbeddingsReturns503WhenHybridDisabled() throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPost( request( "/reindex-embeddings" ), response );
        verify( response ).setStatus( 503 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 503, obj.get( "status" ).getAsInt() );
        assertEquals( "Embedding reindex unavailable — hybrid search is disabled.", obj.get( "message" ).getAsString() );
        assertEquals( "wikantik.search.hybrid.enabled", obj.get( "flag" ).getAsString() );
    }

    @Test
    void reindexEmbeddingsReturns202WhenStarted() throws Exception {
        final BootstrapEmbeddingIndexer boot = bootstrap();
        Mockito.doReturn( new BootstrapEmbeddingIndexer.Progress(
            BootstrapEmbeddingIndexer.State.RUNNING, 5L, null, null, null ) ).when( boot ).progress();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPost( request( "/reindex-embeddings" ), response );
        verify( response ).setStatus( 202 );
        verify( boot ).forceStart();
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "RUNNING", obj.get( "state" ).getAsString() );
        assertEquals( "test-model", obj.get( "model_code" ).getAsString() );
    }

    @Test
    void reindexEmbeddingsReturns409WhenAlreadyRunning() throws Exception {
        final BootstrapEmbeddingIndexer boot = bootstrap();
        Mockito.doReturn( new BootstrapEmbeddingIndexer.Progress(
            BootstrapEmbeddingIndexer.State.RUNNING, 5L, null, null, null ) ).when( boot ).progress();
        Mockito.doThrow( new IllegalStateException( "running" ) ).when( boot ).forceStart();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPost( request( "/reindex-embeddings" ), response );
        verify( response ).setStatus( 409 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 409, obj.get( "status" ).getAsInt() );
        assertEquals( "Embedding reindex is already running.", obj.get( "message" ).getAsString() );
        assertEquals( "RUNNING", obj.get( "state" ).getAsString() );
        assertEquals( "test-model", obj.get( "model_code" ).getAsString() );
    }

    @Test
    void indexStatusEmbeddingsBlockCarriesBootstrapAndDisabledEmbedder() throws Exception {
        final ContentIndexRebuildService svc = Mockito.mock( ContentIndexRebuildService.class );
        Mockito.doReturn( new IndexStatusSnapshot(
            new IndexStatusSnapshot.Pages( 1, 0, 1 ),
            new IndexStatusSnapshot.Lucene( 1, 0, null ),
            new IndexStatusSnapshot.Chunks( 1, 0, 1, 1, 1, 1 ),
            new IndexStatusSnapshot.Rebuild( "IDLE", null, 0, 0, 0, 0, 0, 0, 0, List.of() ) ) ).when( svc ).snapshot();
        engine.setManager( ContentIndexRebuildService.class, svc );
        final BootstrapEmbeddingIndexer boot = bootstrap();
        Mockito.doReturn( new BootstrapEmbeddingIndexer.Progress(
            BootstrapEmbeddingIndexer.State.COMPLETED, 9L, Instant.parse( "2026-01-01T00:00:00Z" ),
            Instant.parse( "2026-01-01T00:01:00Z" ), null ) ).when( boot ).progress();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( request( "/index-status" ), response );
        final JsonObject emb = gson.fromJson( sw.toString(), JsonObject.class ).getAsJsonObject( "embeddings" );
        final JsonObject b = emb.getAsJsonObject( "bootstrap" );
        assertEquals( "COMPLETED", b.get( "state" ).getAsString() );
        assertEquals( 9, b.get( "chunks_total" ).getAsInt() );
        assertEquals( "2026-01-01T00:00:00Z", b.get( "started_at" ).getAsString() );
        assertEquals( "2026-01-01T00:01:00Z", b.get( "completed_at" ).getAsString() );
        assertTrue( b.get( "error_message" ).isJsonNull() );
        final JsonObject e = emb.getAsJsonObject( "embedder" );
        assertEquals( "DISABLED", e.get( "circuit_state" ).getAsString() );
        assertEquals( 0, e.get( "call_success" ).getAsInt() );
        assertEquals( 0, e.get( "breaker_call_rejected" ).getAsInt() );
    }

    @Test
    void indexStatusIs503WithoutRebuildService() throws Exception {
        engine.setManager( ContentIndexRebuildService.class, null );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( request( "/index-status" ), response );
        verify( response ).setStatus( 503 );
        assertTrue( sw.toString().contains( "rebuild service not available" ) );
    }

    @Test
    void chunkRoutesAre503WithoutRepository() throws Exception {
        engine.setManager( ContentChunkRepository.class, null );
        for ( final String path : new String[]{ "/chunks/outliers", "/chunks" } ) {
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
            final HttpServletRequest req = request( path );
            Mockito.doReturn( "SomePage" ).when( req ).getParameter( "page" );
            servlet.doGet( req, response );
            verify( response ).setStatus( 503 );
            assertTrue( sw.toString().contains( "chunk repository not available" ), path );
        }
    }

    private BootstrapEmbeddingIndexer bootstrap() {
        final BootstrapEmbeddingIndexer boot = Mockito.mock( BootstrapEmbeddingIndexer.class );
        Mockito.doReturn( "test-model" ).when( boot ).modelCode();
        engine.setManager( BootstrapEmbeddingIndexer.class, boot );
        return boot;
    }

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/content" + pathInfo );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        try {
            Mockito.doReturn( new BufferedReader( new StringReader( "{}" ) ) ).when( request ).getReader();
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( e );
        }
        return request;
    }
}
