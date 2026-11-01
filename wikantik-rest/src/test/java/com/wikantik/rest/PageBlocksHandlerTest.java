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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code GET/POST /api/pages/{name}/blocks} plus the {@code section=^id} forms of {@code /preview} and {@code /embed}. */
class PageBlocksHandlerTest {

    private static TestEngine engine;
    private static PageResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @AfterEach
    void clean() {
        engine.deleteQuietly( "RestBlkPage", "RestBlkDerived", "RestBlkHost" );
    }

    /** What a call answered. */
    private record Reply( int status, String body ) {
        JsonObject json() {
            return new Gson().fromJson( body, JsonObject.class );
        }
    }

    private PageResource spy( final boolean view, final boolean edit ) {
        final PageResource spy = Mockito.spy( servlet );
        Mockito.doReturn( view ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.eq( "view" ) );
        Mockito.doReturn( edit ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.eq( "edit" ) );
        return spy;
    }

    private Reply get( final PageResource s, final String path, final Map< String, String > params ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();
        params.forEach( ( k, v ) -> Mockito.doReturn( v ).when( request ).getParameter( k ) );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        s.doGet( request, response );
        return new Reply( statusOf( response ), sw.toString() );
    }

    private Reply post( final PageResource s, final String path, final String body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        s.doPost( request, response );
        return new Reply( statusOf( response ), sw.toString() );
    }

    /** The last status set on the mock response; 200 when none was. */
    private static int statusOf( final HttpServletResponse response ) {
        final var captor = org.mockito.ArgumentCaptor.forClass( Integer.class );
        Mockito.verify( response, Mockito.atLeast( 0 ) ).setStatus( captor.capture() );
        return captor.getAllValues().isEmpty() ? 200 : captor.getValue();
    }

    // ----- GET /blocks -----

    @Test
    void listReturnsVersionCanAssignAndBlocks() throws Exception {
        engine.saveText( "RestBlkPage", "First para.\n\nSecond para. ^two\n\n- an item\n" );
        final Reply r = get( spy( true, true ), "RestBlkPage/blocks", Map.of() );
        assertEquals( 200, r.status() );
        final JsonObject o = r.json();
        assertEquals( 1, o.get( "version" ).getAsInt() );
        assertTrue( o.get( "canAssign" ).getAsBoolean() );
        final JsonArray blocks = o.getAsJsonArray( "blocks" );
        assertTrue( blocks.size() >= 3, o.toString() );
        final JsonObject b0 = blocks.get( 0 ).getAsJsonObject();
        assertEquals( 0, b0.get( "index" ).getAsInt() );
        assertTrue( b0.get( "id" ).isJsonNull() );
        assertEquals( "paragraph", b0.get( "kind" ).getAsString() );
        assertEquals( "First para.", b0.get( "text" ).getAsString() );
        final JsonObject b1 = blocks.get( 1 ).getAsJsonObject();
        assertEquals( "two", b1.get( "id" ).getAsString() );
        assertEquals( "Second para.", b1.get( "text" ).getAsString() );
    }

    @Test
    void listFiltersByTextOrIdCaseInsensitivelyAndCapsLimit() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < 130; i++ ) {
            sb.append( "Paragraph number " ).append( i ).append( i == 7 ? " NEEDLE" : "" ).append( ".\n\n" );
        }
        sb.append( "Tail. ^zebra9\n" );
        engine.saveText( "RestBlkPage", sb.toString() );
        final PageResource s = spy( true, true );

        assertEquals( 20, get( s, "RestBlkPage/blocks", Map.of() ).json().getAsJsonArray( "blocks" ).size() );
        assertEquals( 100, get( s, "RestBlkPage/blocks", Map.of( "limit", "5000" ) ).json().getAsJsonArray( "blocks" ).size() );
        assertEquals( 3, get( s, "RestBlkPage/blocks", Map.of( "limit", "3" ) ).json().getAsJsonArray( "blocks" ).size() );

        final JsonArray byText = get( s, "RestBlkPage/blocks", Map.of( "q", "needle" ) ).json().getAsJsonArray( "blocks" );
        assertEquals( 1, byText.size() );
        assertEquals( 7, byText.get( 0 ).getAsJsonObject().get( "index" ).getAsInt() );
        final JsonArray byId = get( s, "RestBlkPage/blocks", Map.of( "q", "ZEBRA" ) ).json().getAsJsonArray( "blocks" );
        assertEquals( 1, byId.size() );
        assertEquals( "zebra9", byId.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
    }

    @Test
    void listTextIsCutTo120Chars() throws Exception {
        engine.saveText( "RestBlkPage", "x".repeat( 300 ) + "\n" );
        final JsonObject b = get( spy( true, true ), "RestBlkPage/blocks", Map.of() ).json()
                .getAsJsonArray( "blocks" ).get( 0 ).getAsJsonObject();
        assertEquals( 120, b.get( "text" ).getAsString().length() );
    }

    @Test
    void canAssignIsFalseWithoutEditAndOnADerivedPage() throws Exception {
        engine.saveText( "RestBlkPage", "Para.\n" );
        assertFalse( get( spy( true, false ), "RestBlkPage/blocks", Map.of() ).json().get( "canAssign" ).getAsBoolean() );
        engine.saveText( "RestBlkDerived", "---\nderived_from: a.pdf\n---\nPara.\n" );
        final JsonObject d = get( spy( true, true ), "RestBlkDerived/blocks", Map.of() ).json();
        assertFalse( d.get( "canAssign" ).getAsBoolean() );
        assertEquals( 1, d.getAsJsonArray( "blocks" ).size() );
    }

    @Test
    void missingAndUnviewablePagesAreTheSame404() throws Exception {
        engine.saveText( "RestBlkPage", "Secret.\n" );
        final Reply hidden = get( spy( false, false ), "RestBlkPage/blocks", Map.of() );
        final Reply missing = get( spy( true, true ), "RestBlkNoSuchPage/blocks", Map.of() );
        assertEquals( 404, hidden.status() );
        assertEquals( 404, missing.status() );
        assertEquals( missing.body(), hidden.body() );
        assertFalse( hidden.body().contains( "Secret" ) );
    }

    // ----- POST /blocks -----

    private static String payload( final int version, final int index, final String fp ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "version", version );
        o.addProperty( "index", index );
        o.addProperty( "fingerprint", fp );
        return o.toString();
    }

    @Test
    void postAssignsAndReturnsIdAndVersion() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n\nBeta.\n" );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 1, "beta" ) );
        assertEquals( 200, r.status() );
        final String id = r.json().get( "id" ).getAsString();
        assertTrue( id.matches( "[a-z0-9]{6}" ) );
        assertTrue( r.json().get( "version" ).getAsInt() >= 1 );
        assertTrue( engine.getManager( PageManager.class ).getPureText( "RestBlkPage", PageProvider.LATEST_VERSION )
                .contains( "Beta. ^" + id ) );
    }

    @Test
    void postOnAnAlreadyTaggedBlockReturnsItsIdWithoutChange() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha. ^abc123\n" );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 0, "alpha" ) );
        assertEquals( 200, r.status() );
        assertEquals( "abc123", r.json().get( "id" ).getAsString() );
    }

    @Test
    void postIs404WhenMissingOrUnviewableAnd403WhenViewableButNotEditable() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        assertEquals( 404, post( spy( true, true ), "RestBlkNoSuchPage/blocks", payload( 1, 0, "" ) ).status() );
        assertEquals( 404, post( spy( false, false ), "RestBlkPage/blocks", payload( 1, 0, "" ) ).status() );
        assertEquals( 403, post( spy( true, false ), "RestBlkPage/blocks", payload( 1, 0, "" ) ).status() );
    }

    @Test
    void post409BodiesCarryTheReason() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        final PageResource s = spy( true, true );
        final Reply stale = post( s, "RestBlkPage/blocks", payload( 9, 0, "" ) );
        assertEquals( 409, stale.status() );
        assertEquals( "stale", stale.json().get( "reason" ).getAsString() );
        final Reply gone = post( s, "RestBlkPage/blocks", payload( 1, 7, "" ) );
        assertEquals( 409, gone.status() );
        assertEquals( "block-not-located", gone.json().get( "reason" ).getAsString() );
        engine.saveText( "RestBlkDerived", "---\nderived_from: a.pdf\n---\nPara.\n" );
        final Reply derived = post( s, "RestBlkDerived/blocks", payload( 1, 0, "" ) );
        assertEquals( 409, derived.status() );
        assertEquals( "derived", derived.json().get( "reason" ).getAsString() );
    }

    @Test
    void postWithAMalformedBodyIs400() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        assertEquals( 400, post( spy( true, true ), "RestBlkPage/blocks", "{\"index\":0}" ).status() );
    }

    // ----- /preview and /embed with section=^id -----

    @Test
    void previewOfABlockExcerptsThatBlock() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n\nThe **quoted** claim. ^claim1\n" );
        final JsonObject o = get( spy( true, true ), "RestBlkPage/preview", Map.of( "section", "^claim1" ) ).json();
        assertEquals( "The quoted claim.", o.get( "excerpt" ).getAsString() );
        assertFalse( o.has( "blockMissing" ) );
    }

    @Test
    void previewOfAMissingBlockFallsBackToThePageExcerptAndFlagsIt() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n\nOther. ^there\n" );
        final JsonObject o = get( spy( true, true ), "RestBlkPage/preview", Map.of( "section", "^gone" ) ).json();
        assertTrue( o.get( "excerpt" ).getAsString().startsWith( "Intro para." ) );
        assertTrue( o.get( "blockMissing" ).getAsBoolean() );
    }

    @Test
    void embedOfABlockRendersOnlyThatBlock() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n\nThe **quoted** claim. ^claim1\n\nOutro.\n" );
        final JsonObject o = get( servlet, "RestBlkPage/embed", Map.of( "section", "^claim1" ) ).json();
        final String html = o.get( "html" ).getAsString();
        assertTrue( html.contains( "<strong>quoted</strong>" ), html );
        assertFalse( html.contains( "Intro para" ) || html.contains( "Outro" ), html );
        assertFalse( o.get( "missing" ).getAsBoolean() );
    }

    @Test
    void embedOfAMissingBlockIsFlaggedMissing() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n" );
        final JsonObject o = get( servlet, "RestBlkPage/embed", Map.of( "section", "^gone" ) ).json();
        assertTrue( o.get( "missing" ).getAsBoolean(), o.toString() );
    }
}
