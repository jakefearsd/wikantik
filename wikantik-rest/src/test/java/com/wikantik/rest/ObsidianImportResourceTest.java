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

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executor;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;
import com.wikantik.importer.ImportJobRegistry;
import com.wikantik.importer.ImportLimits;
import com.wikantik.importer.ImportTestJobs;
import com.wikantik.importer.TestVaults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link ObsidianImportResource}. */
class ObsidianImportResourceTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @BeforeEach
    void anon() {
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
    }

    private void loginAdmin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = WikiSession.getWikiSession( engine, request );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
    }

    /** Queued executor: jobs stay RUNNING until drained. */
    private static final class Queued implements Executor {
        @Override public void execute( final Runnable r ) { /* never runs: job stays RUNNING */ }
    }

    private ObsidianImportResource servlet( final TestEngine eng, final boolean canCreate, final boolean admin )
            throws Exception {
        final ObsidianImportResource r = new ObsidianImportResource() {
            @Override protected ImportJobRegistry newRegistry( final ImportLimits l ) {
                return new ImportJobRegistry( l.maxConcurrent(), Clock.systemUTC(), new Queued() );
            }
            @Override protected boolean canCreatePages( final Session s ) { return canCreate; }
            @Override protected boolean isAdmin( final Session s ) { return admin; }
        };
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( eng.getServletContext() ).when( cfg ).getServletContext();
        r.init( cfg );
        return r;
    }

    private ObsidianImportResource servlet() throws Exception {
        return servlet( engine, true, true );
    }

    private record Resp( HttpServletResponse mock, StringWriter body ) {}

    private Resp resp() throws Exception {
        final HttpServletResponse mock = HttpMockFactory.createHttpResponse();
        final StringWriter body = new StringWriter();
        doReturn( new PrintWriter( body ) ).when( mock ).getWriter();
        return new Resp( mock, body );
    }

    private HttpServletRequest get( final String path ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/import/obsidian" + path );
        doReturn( path ).when( req ).getPathInfo();
        return req;
    }

    private HttpServletRequest post( final String path, final byte[] zip, final long size,
                                     final Map< String, String > params ) throws Exception {
        final HttpServletRequest req = get( path );
        doReturn( "multipart/form-data; boundary=x" ).when( req ).getContentType();
        final Part part = mock( Part.class );
        doReturn( new ByteArrayInputStream( zip ) ).when( part ).getInputStream();
        doReturn( size ).when( part ).getSize();
        doReturn( "v.zip" ).when( part ).getSubmittedFileName();
        doReturn( part ).when( req ).getPart( "file" );
        params.forEach( ( k, v ) -> doReturn( v ).when( req ).getParameter( k ) );
        return req;
    }

    private HttpServletRequest post( final String path, final byte[] zip ) throws Exception {
        return post( path, zip, zip.length, Map.of() );
    }

    private static byte[] oneNote() {
        return TestVaults.zipText( Map.of( "A.md", "# A" ) );
    }

    private JsonObject json( final Resp r ) {
        return JsonParser.parseString( r.body().toString() ).getAsJsonObject();
    }

    @Test
    void anonymousIs401() throws Exception {
        final Resp r = resp();
        servlet().doPost( post( "/plan", oneNote() ), r.mock() );
        verify( r.mock() ).setStatus( 401 );
    }

    @Test
    void noCreatePagesIs403() throws Exception {
        loginAdmin();
        final ObsidianImportResource s = servlet( engine, false, false );
        final Resp r1 = resp();
        s.doPost( post( "/plan", oneNote() ), r1.mock() );
        verify( r1.mock() ).setStatus( 403 );
        final Resp r2 = resp();
        s.doGet( get( "/jobs/current" ), r2.mock() );
        verify( r2.mock() ).setStatus( 403 );
    }

    @Test
    void notMultipartIs415() throws Exception {
        loginAdmin();
        final HttpServletRequest req = get( "/plan" );
        doReturn( "application/json" ).when( req ).getContentType();
        final Resp r = resp();
        servlet().doPost( req, r.mock() );
        verify( r.mock() ).setStatus( 415 );
    }

    @Test
    void planReturnsPlanJson() throws Exception {
        loginAdmin();
        final Resp r = resp();
        servlet().doPost( post( "/plan", oneNote() ), r.mock() );
        final JsonObject o = json( r );
        assertTrue( o.has( "planHash" ), r.body().toString() );
        assertEquals( 1, o.getAsJsonObject( "totals" ).get( "pagesNew" ).getAsInt() );
    }

    @Test
    void unsafeEntryIs400NamingEntry() throws Exception {
        loginAdmin();
        final Resp r = resp();
        servlet().doPost( post( "/plan", TestVaults.zipText( Map.of( "../x.md", "x" ) ) ), r.mock() );
        verify( r.mock() ).setStatus( 400 );
        assertTrue( r.body().toString().contains( "../x.md" ), r.body().toString() );
    }

    @Test
    void overUploadLimitIs413() throws Exception {
        loginAdmin();
        final Resp r = resp();
        servlet().doPost( post( "/plan", oneNote(), 104857601L, Map.of() ), r.mock() );
        verify( r.mock() ).setStatus( 413 );
        assertTrue( r.body().toString().contains( "wikantik.import.maxUploadBytes" ), r.body().toString() );
    }

    @Test
    void tooManyNotesIs413() throws Exception {
        final TestEngine small = TestEngine.build( Map.entry( "wikantik.import.maxPages", "1" ) );
        try {
            final HttpServletRequest login = HttpMockFactory.createHttpRequest();
            final Session session = WikiSession.getWikiSession( small, login );
            small.getManager( AuthenticationManager.class ).login( session, login, Users.ADMIN, Users.ADMIN_PASS );
            final Resp r = resp();
            servlet( small, true, true ).doPost(
                post( "/plan", TestVaults.zipText( Map.of( "A.md", "# A", "B.md", "# B" ) ) ), r.mock() );
            verify( r.mock() ).setStatus( 413 );
            assertTrue( r.body().toString().contains( "wikantik.import.maxPages" ), r.body().toString() );
        } finally {
            SessionMonitor.getInstance( small ).remove( HttpMockFactory.SHARED_SESSION_ID );
            small.stop();
        }
    }

    private String planHash( final ObsidianImportResource s, final byte[] zip ) throws Exception {
        final Resp r = resp();
        s.doPost( post( "/plan", zip ), r.mock() );
        return json( r ).get( "planHash" ).getAsString();
    }

    @Test
    void applyWithStaleHashIs409() throws Exception {
        loginAdmin();
        final ObsidianImportResource s = servlet();
        final Resp r = resp();
        s.doPost( post( "/apply", oneNote(), oneNote().length, Map.of( "planHash", "nope" ) ), r.mock() );
        verify( r.mock() ).setStatus( 409 );
        assertTrue( s.registry().current( Users.ADMIN ).isEmpty(), "no job may be registered on a stale hash" );
    }

    @Test
    void applyStartsJobAnd202() throws Exception {
        loginAdmin();
        final ObsidianImportResource s = servlet();
        final byte[] zip = oneNote();
        final String hash = planHash( s, zip );
        final Resp r = resp();
        s.doPost( post( "/apply", zip, zip.length, Map.of( "planHash", hash ) ), r.mock() );
        verify( r.mock() ).setStatus( 202 );
        final String id = json( r ).get( "jobId" ).getAsString();
        assertTrue( s.registry().find( id ).isPresent() );
    }

    @Test
    void secondApplyWhileRunningIs409() throws Exception {
        loginAdmin();
        final ObsidianImportResource s = servlet();
        final byte[] zip = oneNote();
        final String hash = planHash( s, zip );
        s.doPost( post( "/apply", zip, zip.length, Map.of( "planHash", hash ) ), resp().mock() );
        final Resp r = resp();
        s.doPost( post( "/apply", zip, zip.length, Map.of( "planHash", hash ) ), r.mock() );
        verify( r.mock() ).setStatus( 409 );
    }

    @Test
    void otherUsersApplyAtCapacityIs429() throws Exception {
        loginAdmin();
        final ObsidianImportResource s = servlet();
        s.registry().start( "Bob", id -> ImportTestJobs.job( id, "Bob" ) );
        final byte[] zip = oneNote();
        final Resp r = resp();
        s.doPost( post( "/apply", zip, zip.length, Map.of( "planHash", "x" ) ), r.mock() );
        verify( r.mock() ).setStatus( 429 );
    }

    @Test
    void jobReadableOnlyByOwnerOrAdmin() throws Exception {
        loginAdmin();
        final ObsidianImportResource denied = servlet( engine, true, false );
        final String id = denied.registry().start( "Bob", i -> ImportTestJobs.job( i, "Bob" ) ).id();
        final Resp r1 = resp();
        denied.doGet( get( "/jobs/" + id ), r1.mock() );
        verify( r1.mock() ).setStatus( 403 );

        final ObsidianImportResource allowed = servlet( engine, true, true );
        final String id2 = allowed.registry().start( "Bob", i -> ImportTestJobs.job( i, "Bob" ) ).id();
        final Resp r2 = resp();
        allowed.doGet( get( "/jobs/" + id2 ), r2.mock() );
        assertEquals( id2, json( r2 ).get( "jobId" ).getAsString() );

        final Resp r3 = resp();
        allowed.doGet( get( "/jobs/unknown" ), r3.mock() );
        verify( r3.mock() ).setStatus( 404 );
    }

    @Test
    void currentJobIs404WhenNone() throws Exception {
        loginAdmin();
        final Resp r = resp();
        servlet().doGet( get( "/jobs/current" ), r.mock() );
        verify( r.mock() ).setStatus( 404 );
    }
}
