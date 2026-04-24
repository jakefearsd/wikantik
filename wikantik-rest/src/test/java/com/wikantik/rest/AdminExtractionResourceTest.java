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
import com.wikantik.WikiEngine;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AdminExtractionResourceTest {

    private TestEngine engine;
    private AdminExtractionResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        servlet = new AdminExtractionResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    private void installIndexer( final BootstrapEntityExtractionIndexer indexer ) {
        ( (WikiEngine) engine ).setManager( BootstrapEntityExtractionIndexer.class, indexer );
    }

    private static BootstrapEntityExtractionIndexer.Status idleStatus() {
        return new BootstrapEntityExtractionIndexer.Status(
            BootstrapEntityExtractionIndexer.State.IDLE,
            0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, null, false, 4 );
    }

    private static BootstrapEntityExtractionIndexer.Status runningStatus() {
        return new BootstrapEntityExtractionIndexer.Status(
            BootstrapEntityExtractionIndexer.State.RUNNING,
            10, 4, 0, 40, 12, 0, 5, 1,
            Instant.parse( "2026-04-24T10:00:00Z" ), null, 1234L, null, true, 4 );
    }

    @Test
    void doGet_returns503WhenIndexerAbsent() throws Exception {
        final HttpServletResponse response = run( "GET", null, null );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void doGet_returnsStatusMapWhenIndexerPresent() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.status() ).thenReturn( runningStatus() );
        installIndexer( indexer );

        final StringWriter sw = new StringWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( request, response );

        final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "RUNNING", body.get( "state" ).getAsString() );
        assertEquals( 10, body.get( "totalPages" ).getAsInt() );
        assertEquals( 5, body.get( "mentionsWritten" ).getAsInt() );
        assertEquals( "2026-04-24T10:00:00Z", body.get( "startedAt" ).getAsString() );
        assertEquals( 4, body.get( "concurrency" ).getAsInt() );
    }

    @Test
    void doDelete_returns503WhenIndexerAbsent() throws Exception {
        final HttpServletResponse response = run( "DELETE", null, null );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void doDelete_returns409WhenNothingToCancel() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.cancel() ).thenReturn( false );
        installIndexer( indexer );

        final HttpServletResponse response = run( "DELETE", null, null );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_CONFLICT );
    }

    @Test
    void doDelete_returnsStatusAfterSuccessfulCancel() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.cancel() ).thenReturn( true );
        Mockito.when( indexer.status() ).thenReturn( idleStatus() );
        installIndexer( indexer );

        final StringWriter sw = new StringWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doDelete( request, response );

        final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "IDLE", body.get( "state" ).getAsString() );
    }

    @Test
    void doPost_returns503WhenIndexerAbsent() throws Exception {
        final HttpServletResponse response = run( "POST", null, null );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void doPost_startsBatchWithForceFalseByDefault() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.start( false ) ).thenReturn( true );
        Mockito.when( indexer.status() ).thenReturn( runningStatus() );
        installIndexer( indexer );

        final StringWriter sw = new StringWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPost( request, response );

        Mockito.verify( indexer ).start( false );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_ACCEPTED );
    }

    @Test
    void doPost_parsesForceTrueVariants() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.start( true ) ).thenReturn( true );
        Mockito.when( indexer.status() ).thenReturn( runningStatus() );
        installIndexer( indexer );

        for ( final String value : new String[] { "true", "TRUE", "yes", "1", "  yes  " } ) {
            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
            Mockito.doReturn( value ).when( request ).getParameter( "force" );
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
            servlet.doPost( request, response );
        }
        Mockito.verify( indexer, Mockito.times( 5 ) ).start( true );
    }

    @Test
    void doPost_treatsUnknownForceValueAsFalse() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.start( false ) ).thenReturn( true );
        Mockito.when( indexer.status() ).thenReturn( runningStatus() );
        installIndexer( indexer );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        Mockito.doReturn( "maybe" ).when( request ).getParameter( "force" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
        servlet.doPost( request, response );

        Mockito.verify( indexer ).start( false );
    }

    @Test
    void doPost_returns409WhenAlreadyRunning() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.start( Mockito.anyBoolean() ) ).thenReturn( false );
        Mockito.when( indexer.status() ).thenReturn( runningStatus() );
        installIndexer( indexer );

        final StringWriter sw = new StringWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPost( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_CONFLICT );
        final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( body.has( "message" ) );
    }

    @Test
    void statusMap_includesLastErrorWhenPresent() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
        Mockito.when( indexer.status() ).thenReturn( new BootstrapEntityExtractionIndexer.Status(
            BootstrapEntityExtractionIndexer.State.ERROR,
            5, 2, 1, 20, 6, 1, 0, 0, null, Instant.parse( "2026-04-24T11:00:00Z" ),
            5000L, "disk full", false, 4 ) );
        installIndexer( indexer );

        final StringWriter sw = new StringWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( request, response );

        final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "ERROR", body.get( "state" ).getAsString() );
        assertEquals( "disk full", body.get( "lastError" ).getAsString() );
        assertEquals( "2026-04-24T11:00:00Z", body.get( "finishedAt" ).getAsString() );
    }

    private HttpServletResponse run( final String method, final String queryName, final String queryValue ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
        if ( queryName != null ) Mockito.doReturn( queryValue ).when( request ).getParameter( queryName );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
        switch ( method ) {
            case "GET"    -> servlet.doGet( request, response );
            case "POST"   -> servlet.doPost( request, response );
            case "DELETE" -> servlet.doDelete( request, response );
            default -> fail( "unexpected method: " + method );
        }
        return response;
    }
}
