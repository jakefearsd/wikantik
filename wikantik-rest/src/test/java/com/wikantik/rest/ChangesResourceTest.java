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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ChangesResourceTest {

    private TestEngine engine;
    private ChangesResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "ChangesPageA", "First page for changes test." );
        engine.saveText( "ChangesPageB", "Second page for changes test." );

        servlet = new ChangesResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "ChangesPageA" ); } catch ( final Exception ignored ) {}
            try { pm.deletePage( "ChangesPageB" ); } catch ( final Exception ignored ) {}
            engine.stop();
        }
    }

    @Test
    void testChangesWithNoSinceReturnsAllPages() throws Exception {
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "since" ) );
        assertTrue( obj.has( "generated_at" ) );
        assertTrue( obj.has( "pages" ) );
        assertTrue( obj.get( "pages" ).isJsonArray() );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertTrue( pages.size() >= 2, "Should include the two test pages" );

        final JsonObject entry = pages.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "slug" ) );
        assertTrue( entry.has( "modified_at" ) );
        assertTrue( entry.has( "url" ) );
        assertTrue( entry.get( "url" ).getAsString().contains( "/wiki/" ) );
    }

    @Test
    void testChangesWithFutureSinceReturnsEmpty() throws Exception {
        final String json = doGet( "2099-01-01T00:00:00Z" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertEquals( 0, pages.size(), "No pages should be modified after 2099" );
    }

    @Test
    void testChangesWithInvalidSinceReturns400() throws Exception {
        final String json = doGet( "not-a-date" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        assertTrue( obj.has( "error" ) );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testParseIso8601ZuluForm() throws ParseException {
        final Date d = ChangesResource.parseIso8601( "2026-04-11T12:00:00Z" );
        assertNotNull( d );
    }

    @Test
    void testParseIso8601DateOnly() throws ParseException {
        final Date d = ChangesResource.parseIso8601( "2026-04-11" );
        assertNotNull( d );
    }

    @Test
    void testEncodePathSegmentSpaces() {
        assertEquals( "Cold%20War%20Markets",
                ChangesResource.encodePathSegment( "Cold War Markets" ) );
    }

    @Test
    void testEncodePathSegmentSpecialChars() {
        // & becomes %26; spaces become %20
        assertEquals( "Cold%20War%20%26%20Markets",
                ChangesResource.encodePathSegment( "Cold War & Markets" ) );
    }

    @Test
    void testShouldSkipStaleDropsOldPages() {
        final Page stale = Mockito.mock( Page.class );
        Mockito.when( stale.getLastModified() ).thenReturn( new Date( 1_000_000L ) );
        final Date since = new Date( 2_000_000_000_000L );
        assertTrue( ChangesResource.shouldSkipStale( stale, since ) );
    }

    @Test
    void testShouldSkipStaleKeepsFreshPages() {
        final Page fresh = Mockito.mock( Page.class );
        Mockito.when( fresh.getLastModified() ).thenReturn( new Date( 3_000_000_000_000L ) );
        final Date since = new Date( 2_000_000_000_000L );
        assertFalse( ChangesResource.shouldSkipStale( fresh, since ) );
    }

    @Test
    void testShouldSkipStaleKeepsPagesWithNullLastModified() {
        final Page noTime = Mockito.mock( Page.class );
        Mockito.when( noTime.getLastModified() ).thenReturn( null );
        assertFalse( ChangesResource.shouldSkipStale( noTime, new Date() ) );
    }

    // ----- helpers -----

    private String doGet( final String since ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/changes" );
        when( req.getParameter( "since" ) ).thenReturn( since );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );
        return sw.toString();
    }
}
