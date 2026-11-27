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
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.Selection;
import com.wikantik.rest.CollabWire.Answer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the collaboration wire accepts from a hostile or broken client: every defect is a named refusal (a 400
 * {@code rejected} answer), never an exception, and a half-valid push is refused whole.
 */
class CollabWireParsingTest {

    private static JsonObject json( final String s ) {
        return JsonParser.parseString( s ).getAsJsonObject();
    }

    private static CollabPush.Parsed parse( final String s ) {
        return CollabPush.parse( json( s ) );
    }

    // ---- push: version ----

    @ParameterizedTest
    @ValueSource( strings = { "{}", "{\"version\":null}", "{\"version\":\"3\"}", "{\"version\":1.5}",
            "{\"version\":1e10}", "{\"version\":true}", "{\"version\":[1]}", "{\"version\":2147483648}" } )
    void aVersionThatIsNotAnExactIntIsRefused( final String body ) {
        assertEquals( "version must be an integer", parse( body ).refusal() );
    }

    @ParameterizedTest
    @CsvSource( { "0,0", "7,7", "2147483647,2147483647", "-1,-1", "3.0,3", "3e0,3" } )
    void aVersionWithAnExactIntValueIsAccepted( final String literal, final int expected ) {
        final CollabPush.Parsed p = parse( "{\"version\":" + literal + "}" );
        assertNull( p.refusal() );
        assertEquals( expected, p.version() );
        assertTrue( p.updates().isEmpty() );
        assertTrue( p.metaEdits().isEmpty() );
        assertNull( p.selection() );
    }

    // ---- push: updates ----

    @ParameterizedTest
    @CsvSource( delimiter = '|', value = {
            "{\"version\":1,\"updates\":{}}|updates must be a list",
            "{\"version\":1,\"updates\":\"x\"}|updates must be a list",
            "{\"version\":1,\"updates\":[5]}|an update must be an object",
            "{\"version\":1,\"updates\":[null]}|an update must be an object",
            "{\"version\":1,\"updates\":[{\"changes\":[3]}]}|an update needs a clientID string",
            "{\"version\":1,\"updates\":[{\"clientID\":7,\"changes\":[3]}]}|an update needs a clientID string",
            "{\"version\":1,\"updates\":[{\"clientID\":\"c\"}]}|an update needs a changes list",
            "{\"version\":1,\"updates\":[{\"clientID\":\"c\",\"changes\":{}}]}|an update needs a changes list",
            "{\"version\":1,\"updates\":[{\"clientID\":\"c\",\"changes\":null}]}|an update needs a changes list" } )
    void aMalformedUpdateIsRefusedByName( final String body, final String refusal ) {
        assertEquals( refusal, parse( body ).refusal() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[0]", "[-1]", "[1.5]", "[\"x\"]", "[[0]]", "[3,3]", "[[0,\"a\\nb\"]]" } )
    void anIllFormedChangeSetIsARefusalNotAnException( final String changes ) {
        final CollabPush.Parsed p = parse( "{\"version\":1,\"updates\":[{\"clientID\":\"c\",\"changes\":" + changes + "}]}" );
        assertNotNull( p.refusal(), changes );
        assertTrue( p.refusal().startsWith( "malformed changes: " ), p.refusal() );
        assertTrue( p.updates().isEmpty(), "a refused push applies nothing" );
    }

    @Test
    void aWellFormedUpdateIsParsedInOrderAndAnAbsentOrNullListIsEmpty() {
        final CollabPush.Parsed p = parse( "{\"version\":4,\"updates\":[{\"clientID\":\"a\",\"changes\":[3]},"
                + "{\"clientID\":\"b\",\"changes\":[[1,\"x\"]]}]}" );

        assertNull( p.refusal() );
        assertEquals( List.of( "a", "b" ), p.updates().stream().map( u -> u.clientId() ).toList() );
        assertNull( parse( "{\"version\":4,\"updates\":null}" ).refusal() );
    }

    @Test
    void aBadSecondUpdateRefusesTheWholePush() {
        final CollabPush.Parsed p = parse( "{\"version\":1,\"updates\":[{\"clientID\":\"a\",\"changes\":[3]},{\"clientID\":\"b\"}]}" );

        assertEquals( "an update needs a changes list", p.refusal() );
        assertTrue( p.updates().isEmpty() );
    }

    // ---- push: selection ----

    @ParameterizedTest
    @ValueSource( strings = { "5", "\"s\"", "[1]", "true" } )
    void aSelectionThatIsNotAnObjectIsRefused( final String sel ) {
        assertEquals( "selection must be an object", parse( "{\"version\":1,\"selection\":" + sel + "}" ).refusal() );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "{}",
            "{\"head\":1,\"version\":1,\"unconfirmed\":0}",
            "{\"anchor\":1,\"version\":1,\"unconfirmed\":0}",
            "{\"anchor\":1,\"head\":1,\"unconfirmed\":0}",
            "{\"anchor\":1,\"head\":1,\"version\":1}",
            "{\"anchor\":1.5,\"head\":1,\"version\":1,\"unconfirmed\":0}",
            "{\"anchor\":\"1\",\"head\":1,\"version\":1,\"unconfirmed\":0}" } )
    void aSelectionMissingAnIntegerFieldIsRefused( final String sel ) {
        assertEquals( "selection needs integer anchor, head, version and unconfirmed",
                parse( "{\"version\":1,\"selection\":" + sel + "}" ).refusal() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "anchor", "head", "version", "unconfirmed" } )
    void aNegativeSelectionFieldIsRefusedNamingIt( final String field ) {
        final JsonObject sel = json( "{\"anchor\":1,\"head\":1,\"version\":1,\"unconfirmed\":0}" );
        sel.addProperty( field, -1 );

        final String refusal = parse( "{\"version\":1,\"selection\":" + sel + "}" ).refusal();

        assertNotNull( refusal );
        assertTrue( refusal.startsWith( "selection: " ) && refusal.contains( field ), refusal );
    }

    @Test
    void aWellFormedSelectionIsParsedAndNullIsNoSelection() {
        final CollabPush.Parsed p = parse( "{\"version\":1,\"selection\":{\"anchor\":2,\"head\":5,\"version\":1,\"unconfirmed\":3}}" );

        assertNull( p.refusal() );
        assertEquals( new Selection( 2, 5, 1, 3 ), p.selection() );
        assertNull( parse( "{\"version\":1,\"selection\":null}" ).selection() );
    }

    // ---- push: meta ops ----

    @Test
    void metaOpsAreParsedIntoTheirKindsAndTypedValues() {
        final CollabPush.Parsed p = parse( "{\"version\":1,\"metaOps\":["
                + "{\"field\":\"title\",\"value\":\"T\"},{\"field\":\"year\",\"value\":2024},"
                + "{\"field\":\"gone\",\"value\":null},{\"field\":\"tags\",\"add\":\"a\"},{\"field\":\"tags\",\"remove\":\"b\"}]}" );

        assertNull( p.refusal() );
        assertEquals( List.of( MetaEdit.set( "title", "T" ), MetaEdit.set( "year", 2024L ), MetaEdit.set( "gone", null ),
                MetaEdit.add( "tags", "a" ), MetaEdit.remove( "tags", "b" ) ), p.metaEdits() );
    }

    @ParameterizedTest
    @CsvSource( delimiter = '|', value = {
            "{}|metaOps must be a list",
            "\"x\"|metaOps must be a list",
            "[5]|a meta op must be an object",
            "[null]|a meta op must be an object",
            "[{\"value\":1}]|a meta op needs a field string",
            "[{\"field\":3,\"value\":1}]|a meta op needs a field string",
            "[{\"field\":\"f\"}]|exactly one of value, add and remove is required",
            "[{\"field\":\"f\",\"value\":1,\"add\":\"x\"}]|exactly one of value, add and remove is required",
            "[{\"field\":\"f\",\"add\":\"x\",\"remove\":\"y\"}]|exactly one of value, add and remove is required",
            "[{\"field\":\"f\",\"add\":5}]|add and remove take a string",
            "[{\"field\":\"f\",\"remove\":null}]|add and remove take a string",
            "[{\"field\":\"f\",\"remove\":[\"x\"]}]|add and remove take a string" } )
    void aMalformedMetaOpIsRefusedByName( final String metaOps, final String refusal ) {
        assertEquals( refusal, parse( "{\"version\":1,\"metaOps\":" + metaOps + "}" ).refusal() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "add", "remove" } )
    void aBlankItemForAnAddOrRemoveIsRefusedAsMalformed( final String kind ) {
        final CollabPush.Parsed p = parse( "{\"version\":1,\"metaOps\":[{\"field\":\"f\",\"" + kind + "\":\"  \"}]}" );

        assertNotNull( p.refusal() );
        assertTrue( p.refusal().startsWith( "malformed meta op: " ), p.refusal() );
        assertTrue( p.metaEdits().isEmpty() );
    }

    @Test
    void metaOpsAreCheckedBeforeUpdatesSoTheFirstDefectNamedIsTheMetaOne() {
        final CollabPush.Parsed p = parse( "{\"version\":1,\"metaOps\":{},\"updates\":{}}" );

        assertEquals( "metaOps must be a list", p.refusal() );
    }

    @Test
    void aRefusedPushNeverReachesTheSession() {
        final com.wikantik.collab.CollabSession session = mock( com.wikantik.collab.CollabSession.class );
        final List< String > observed = new ArrayList<>();

        final Answer a = CollabPush.run( session, "p1", "alice", json( "{\"version\":\"x\"}" ), 5,
                ( status, reason ) -> observed.add( status + ":" + reason ) );

        assertEquals( 400, a.status() );
        assertTrue( observed.isEmpty(), "a wire refusal is not observed as a session outcome" );
        verify( session, never() ).push( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }

    @ParameterizedTest
    @CsvSource( { "1,1000", "3,333", "10,100", "1000,1", "5000,1", "0,1000", "-4,1000" } )
    void aRateLimitedPushTellsTheClientWhenToRetry( final int perSecond, final long retryAfterMs ) {
        final com.wikantik.collab.CollabSession session = mock( com.wikantik.collab.CollabSession.class );
        when( session.push( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() ) )
                .thenReturn( com.wikantik.collab.model.PushResponse.refused( 429, 0, "rate" ) );

        final Answer a = CollabPush.run( session, "p1", "alice", json( "{\"version\":1}" ), perSecond, ( s, r ) -> { } );

        assertEquals( 429, a.status() );
        assertEquals( retryAfterMs, ( (JsonObject) a.body() ).get( "retryAfterMs" ).getAsLong() );
    }

    // ---- request routing, bodies and cursors ----

    private static HttpServletRequest pathInfo( final String path ) {
        final HttpServletRequest r = mock( HttpServletRequest.class );
        when( r.getPathInfo() ).thenReturn( path );
        return r;
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "/", "/a", "/ab", "/a/", "//b", "/ab/", "x" } )
    void aPathWithoutAPageAndAnActionHasNoRoute( final String path ) {
        assertNull( CollabRequests.route( pathInfo( path ) ), path );
    }

    @Test
    void aNullPathHasNoRoute() {
        assertNull( CollabRequests.route( pathInfo( null ) ) );
    }

    @ParameterizedTest
    @CsvSource( { "/a/b,a,b", "/Page/updates,Page,updates", "/ab/c,ab,c", "/Page/x,Page,x" } )
    void aPageAndActionAreSplitAtTheLastSlash( final String path, final String page, final String action ) {
        assertEquals( List.of( page, action ), List.of( CollabRequests.route( pathInfo( path ) ) ) );
    }

    private static HttpServletRequest body( final String text, final long declared ) throws IOException {
        final HttpServletRequest r = mock( HttpServletRequest.class );
        final ByteArrayInputStream in = new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
        when( r.getContentLengthLong() ).thenReturn( declared );
        when( r.getInputStream() ).thenReturn( new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener( final ReadListener l ) { }
        } );
        when( r.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        when( r.getRequestURI() ).thenReturn( "/api/collab/P/updates" );
        return r;
    }

    @Test
    void aBodyOfExactlyTheCapIsReadAndOneByteOverIsOversize() throws Exception {
        final String exact = "{\"a\":\"" + "x".repeat( 20 ) + "\"}";
        final int cap = exact.length();

        assertInstanceOf( JsonObject.class, CollabRequests.readJson( body( exact, -1 ), cap ) );
        final Answer over = assertInstanceOf( Answer.class, CollabRequests.readJson( body( exact + " ", -1 ), cap ) );
        assertEquals( 413, over.status() );
        assertEquals( "oversize", ( (JsonObject) over.body() ).get( "reason" ).getAsString() );
        assertEquals( "the request body is limited to " + cap + " bytes", ( (JsonObject) over.body() ).get( "message" ).getAsString() );
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutReadingTheBody() throws Exception {
        final HttpServletRequest r = body( "{}", 101 );

        final Answer a = assertInstanceOf( Answer.class, CollabRequests.readJson( r, 100 ) );

        assertEquals( 413, a.status() );
        verify( r, never() ).getInputStream();
        assertInstanceOf( JsonObject.class, CollabRequests.readJson( body( "{}", 100 ), 100 ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "   ", "\n" } )
    void aBlankBodyIsAnEmptyObject( final String text ) throws Exception {
        final JsonObject o = assertInstanceOf( JsonObject.class, CollabRequests.readJson( body( text, -1 ), 100 ) );
        assertEquals( 0, o.size() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[1]", "3", "\"s\"", "null" } )
    void aBodyThatIsNotAnObjectIs400( final String text ) throws Exception {
        final Answer a = assertInstanceOf( Answer.class, CollabRequests.readJson( body( text, -1 ), 100 ) );

        assertEquals( 400, a.status() );
        assertEquals( "the body must be a JSON object", ( (JsonObject) a.body() ).get( CollabWire.MESSAGE ).getAsString() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "{", "{\"a\":", "not json", "{'a':1" } )
    void malformedJsonIs400NotAnException( final String text ) throws Exception {
        final Answer a = assertInstanceOf( Answer.class, CollabRequests.readJson( body( text, -1 ), 100 ) );

        assertEquals( 400, a.status() );
        assertTrue( ( (JsonObject) a.body() ).get( CollabWire.MESSAGE ).getAsString().startsWith( "Invalid JSON body: " ) );
    }

    private static HttpServletRequest cursors( final String since, final String meta, final String presence, final String events ) {
        final HttpServletRequest r = mock( HttpServletRequest.class );
        when( r.getParameter( "since" ) ).thenReturn( since );
        when( r.getParameter( "metaSince" ) ).thenReturn( meta );
        when( r.getParameter( "presenceSince" ) ).thenReturn( presence );
        when( r.getParameter( "eventsSince" ) ).thenReturn( events );
        return r;
    }

    @Test
    void theCursorsDefaultToEverythingExceptSinceWhichIsRequired() {
        final com.wikantik.collab.model.PullCursor c = CollabRequests.cursor( cursors( "4", null, "", "  " ) );

        assertEquals( 4, c.since() );
        assertEquals( 0, c.metaSince() );
        assertEquals( 0, c.presenceSince() );
        assertEquals( 0, c.eventsSince() );
        assertThrows( NumberFormatException.class, () -> CollabRequests.cursor( cursors( null, null, null, null ) ) );
    }

    @Test
    void paddedCursorsAreTrimmedAndReadAsLongs() {
        final com.wikantik.collab.model.PullCursor c = CollabRequests.cursor( cursors( "1", " 5 ", "3000000000", "0" ) );

        assertEquals( 5, c.metaSince() );
        assertEquals( 3_000_000_000L, c.presenceSince() );
        assertEquals( 0, c.eventsSince() );
    }

    @ParameterizedTest
    @CsvSource( { "-1,metaSince", "-1,presenceSince", "-1,eventsSince" } )
    void aNegativeCursorIsRefusedNamingTheParameter( final String value, final String name ) {
        final HttpServletRequest r = cursors( "1", null, null, null );
        when( r.getParameter( name ) ).thenReturn( value );

        final NumberFormatException e = assertThrows( NumberFormatException.class, () -> CollabRequests.cursor( r ) );

        assertEquals( name + " is negative", e.getMessage() );
    }
}
