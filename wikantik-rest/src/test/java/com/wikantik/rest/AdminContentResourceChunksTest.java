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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the index-status / rebuild-indexes endpoints and the
 * deprecation headers applied to the legacy reindex endpoint.
 * Uses the same {@link TestEngine}-based harness as {@link AdminContentResourceTest}
 * and registers a Mockito-stubbed {@link ContentIndexRebuildService} via
 * {@code engine.setManager}.
 */
class AdminContentResourceChunksTest {

    private TestEngine engine;
    private AdminContentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AdminContentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.stop();
        }
    }

    // ---- GET /admin/content/index-status ----

    @Test
    void getIndexStatusReturns200WithExpectedShape() throws Exception {
        final ContentIndexRebuildService svc = Mockito.mock( ContentIndexRebuildService.class );
        final IndexStatusSnapshot snap = new IndexStatusSnapshot(
            new IndexStatusSnapshot.Pages( 100, 5, 95 ),
            new IndexStatusSnapshot.Lucene( 95, 0, Instant.parse( "2026-04-16T10:00:00Z" ) ),
            new IndexStatusSnapshot.Chunks( 95, 0, 800, 287, 42, 512 ),
            new IndexStatusSnapshot.Rebuild(
                "IDLE", null, 0, 0, 0, 0, 0, 0, List.of() ) );
        Mockito.doReturn( snap ).when( svc ).snapshot();
        engine.setManager( ContentIndexRebuildService.class, svc );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( createRequest( "/index-status" ), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertNotNull( obj );

        // Top-level blocks
        assertTrue( obj.has( "pages" ) );
        assertTrue( obj.has( "lucene" ) );
        assertTrue( obj.has( "chunks" ) );
        assertTrue( obj.has( "rebuild" ) );

        final JsonObject pages = obj.getAsJsonObject( "pages" );
        assertEquals( 100, pages.get( "total" ).getAsInt() );
        assertEquals( 5, pages.get( "system" ).getAsInt() );
        assertEquals( 95, pages.get( "indexable" ).getAsInt() );

        final JsonObject lucene = obj.getAsJsonObject( "lucene" );
        assertEquals( 95, lucene.get( "documents_indexed" ).getAsInt() );
        assertEquals( 0, lucene.get( "queue_depth" ).getAsInt() );
        assertEquals( "2026-04-16T10:00:00Z", lucene.get( "last_update" ).getAsString() );

        final JsonObject chunks = obj.getAsJsonObject( "chunks" );
        assertEquals( 95, chunks.get( "pages_with_chunks" ).getAsInt() );
        assertEquals( 0, chunks.get( "pages_missing_chunks" ).getAsInt() );
        assertEquals( 800, chunks.get( "total_chunks" ).getAsInt() );
        assertEquals( 287, chunks.get( "avg_tokens" ).getAsInt() );
        assertEquals( 42, chunks.get( "min_tokens" ).getAsInt() );
        assertEquals( 512, chunks.get( "max_tokens" ).getAsInt() );

        final JsonObject rebuild = obj.getAsJsonObject( "rebuild" );
        assertEquals( "IDLE", rebuild.get( "state" ).getAsString() );
        assertTrue( rebuild.get( "started_at" ).isJsonNull() );
        assertEquals( 0, rebuild.get( "pages_total" ).getAsInt() );
        assertEquals( 0, rebuild.get( "pages_iterated" ).getAsInt() );
        assertEquals( 0, rebuild.get( "pages_chunked" ).getAsInt() );
        assertEquals( 0, rebuild.get( "system_pages_skipped" ).getAsInt() );
        assertEquals( 0, rebuild.get( "lucene_queued" ).getAsInt() );
        assertEquals( 0, rebuild.get( "chunks_written" ).getAsInt() );
        assertTrue( rebuild.get( "errors" ).isJsonArray() );
        assertEquals( 0, rebuild.getAsJsonArray( "errors" ).size() );
    }

    // ---- POST /admin/content/rebuild-indexes ----

    @Test
    void triggerRebuildReturns202OnSuccess() throws Exception {
        final ContentIndexRebuildService svc = Mockito.mock( ContentIndexRebuildService.class );
        final Instant started = Instant.parse( "2026-04-17T09:00:00Z" );
        final IndexStatusSnapshot startingSnap = new IndexStatusSnapshot(
            new IndexStatusSnapshot.Pages( 10, 1, 9 ),
            new IndexStatusSnapshot.Lucene( 0, 0, null ),
            new IndexStatusSnapshot.Chunks( 0, 9, 0, 0, 0, 0 ),
            new IndexStatusSnapshot.Rebuild(
                "STARTING", started, 0, 0, 0, 0, 0, 0, List.of() ) );
        Mockito.doReturn( startingSnap ).when( svc ).triggerRebuild();
        engine.setManager( ContentIndexRebuildService.class, svc );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( createPostRequest( "/rebuild-indexes", "{}" ), response );

        verify( response ).setStatus( 202 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "STARTING",
            obj.getAsJsonObject( "rebuild" ).get( "state" ).getAsString() );
    }

    @Test
    void triggerRebuildReturns409WhenInFlight() throws Exception {
        final ContentIndexRebuildService svc = Mockito.mock( ContentIndexRebuildService.class );
        final IndexStatusSnapshot running = new IndexStatusSnapshot(
            new IndexStatusSnapshot.Pages( 10, 1, 9 ),
            new IndexStatusSnapshot.Lucene( 0, 5, null ),
            new IndexStatusSnapshot.Chunks( 4, 5, 30, 250, 20, 400 ),
            new IndexStatusSnapshot.Rebuild(
                "RUNNING", Instant.parse( "2026-04-17T09:00:00Z" ),
                9, 4, 4, 0, 4, 30, List.of() ) );
        Mockito.doThrow( new ContentIndexRebuildService.ConflictException( running ) )
            .when( svc ).triggerRebuild();
        engine.setManager( ContentIndexRebuildService.class, svc );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( createPostRequest( "/rebuild-indexes", "{}" ), response );

        verify( response ).setStatus( 409 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "RUNNING",
            obj.getAsJsonObject( "rebuild" ).get( "state" ).getAsString() );
    }

    @Test
    void triggerRebuildReturns503WhenDisabled() throws Exception {
        final ContentIndexRebuildService svc = Mockito.mock( ContentIndexRebuildService.class );
        Mockito.doThrow( new ContentIndexRebuildService.DisabledException() )
            .when( svc ).triggerRebuild();
        engine.setManager( ContentIndexRebuildService.class, svc );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( createPostRequest( "/rebuild-indexes", "{}" ), response );

        verify( response ).setStatus( 503 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "rebuild disabled", obj.get( "error" ).getAsString() );
        assertEquals( "wikantik.rebuild.enabled", obj.get( "flag" ).getAsString() );
    }

    // ---- POST /admin/content/reindex deprecation ----

    @Test
    void legacyReindexCarriesDeprecationHeader() throws Exception {
        // Exercise the real reindex handler end-to-end (no service stub).
        engine.saveText( "DeprecationTestPage", "hello" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( createPostRequest( "/reindex", "{}" ), response );

        // Body still reports successful queueing.
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.has( "started" ) );
        assertTrue( obj.get( "started" ).getAsBoolean() );

        // Deprecation + Link headers set before body write.
        verify( response ).setHeader( "Deprecation", "true" );
        final ArgumentCaptor< String > linkCap = ArgumentCaptor.forClass( String.class );
        verify( response ).setHeader( eq( "Link" ), linkCap.capture() );
        final String link = linkCap.getValue();
        assertTrue( link.contains( "/admin/content/rebuild-indexes" ),
            "Link header should point at successor, was: " + link );
        assertTrue( link.contains( "successor-version" ),
            "Link header should declare rel=successor-version, was: " + link );

        // Cleanup
        try { engine.getManager( com.wikantik.api.managers.PageManager.class )
            .deletePage( "DeprecationTestPage" ); }
        catch ( final Exception e ) { /* ignore */ }
    }

    // ---- Helpers ----

    private HttpServletRequest createRequest( final String pathInfo ) {
        final String path = "/admin/content" + ( pathInfo != null ? pathInfo : "" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        return request;
    }

    private HttpServletRequest createPostRequest( final String pathInfo, final String body ) {
        final HttpServletRequest request = createRequest( pathInfo );
        try {
            Mockito.doReturn( new BufferedReader( new StringReader( body ) ) )
                .when( request ).getReader();
        } catch ( final java.io.IOException e ) {
            throw new RuntimeException( e );
        }
        return request;
    }
}
