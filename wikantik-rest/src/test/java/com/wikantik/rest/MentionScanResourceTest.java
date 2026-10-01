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
import com.wikantik.api.pagegraph.PageTitleLookup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MentionScanResourceTest {
    private static TestEngine engine;
    private static MentionScanResource servlet;
    private final Gson gson = new Gson();

    private static final PageTitleLookup LOOKUP = new PageTitleLookup() {
        @Override public List< String > rank( final Collection< String > names, final String q ) { return List.of(); }
        @Override public List< TitleEntry > entries() {
            return List.of( new TitleEntry( "ExpenseRatio", "Expense Ratio", List.of( "Expense Ratio" ) ),
                            new TitleEntry( "SecretTopic", "Secret Topic", List.of( "Secret Topic" ) ) );
        }
    };

    @BeforeAll static void start() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( MentionScanResource::new, engine );
    }

    @AfterAll static void stop() { engine.stop(); }

    private record Result( HttpServletResponse response, String body ) {}

    private Result post( final MentionScanResource target, final String json ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/mentions/scan" );
        Mockito.doReturn( new BufferedReader( new StringReader( json ) ) ).when( req ).getReader();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        target.doPost( req, resp );
        return new Result( resp, sw.toString() );
    }

    private MentionScanResource ready( final Set< String > viewable ) {
        final MentionScanResource spy = Mockito.spy( servlet );
        Mockito.doReturn( Optional.of( LOOKUP ) ).when( spy ).titleLookup();
        Mockito.doAnswer( inv -> {
            final Collection< String > names = inv.getArgument( 1 );
            return new java.util.HashSet<>( names.stream().filter( viewable::contains ).toList() );
        } ).when( spy ).filterViewable( Mockito.any(), Mockito.any() );
        return spy;
    }

    @Test void returnsViewableMentionsOnly() throws Exception {
        final Result r = post( ready( Set.of( "ExpenseRatio" ) ),
                "{\"page\":\"Draft\",\"text\":\"The expense ratio and the secret topic.\"}" );
        final JsonObject obj = gson.fromJson( r.body(), JsonObject.class );
        assertEquals( 1, obj.getAsJsonArray( "mentions" ).size() );
        final JsonObject m = obj.getAsJsonArray( "mentions" ).get( 0 ).getAsJsonObject();
        assertEquals( "ExpenseRatio", m.get( "target" ).getAsString() );
        assertEquals( 4, m.get( "from" ).getAsInt() );
        assertEquals( 17, m.get( "to" ).getAsInt() );
        assertFalse( r.body().contains( "SecretTopic" ) );
    }

    @Test void aRestrictedPageSharingAPhraseDoesNotShadowAViewableOne() throws Exception {
        // "AlphaRestricted" sorts first, so it would own the shared phrase in the trie; the caller can only view
        // "BetaPublic", which must still be suggested.
        final PageTitleLookup shared = new PageTitleLookup() {
            @Override public List< String > rank( final Collection< String > names, final String q ) { return List.of(); }
            @Override public List< TitleEntry > entries() {
                return List.of( new TitleEntry( "AlphaRestricted", "Alpha", List.of( "Shared Phrase" ) ),
                                new TitleEntry( "BetaPublic", "Beta", List.of( "Shared Phrase" ) ) );
            }
        };
        final MentionScanResource spy = ready( Set.of( "BetaPublic" ) );
        Mockito.doReturn( Optional.of( shared ) ).when( spy ).titleLookup();
        final Result r = post( spy, "{\"page\":\"Draft\",\"text\":\"About the shared phrase here.\"}" );
        final JsonObject obj = gson.fromJson( r.body(), JsonObject.class );
        assertEquals( 1, obj.getAsJsonArray( "mentions" ).size(), r.body() );
        assertEquals( "BetaPublic", obj.getAsJsonArray( "mentions" ).get( 0 ).getAsJsonObject().get( "target" ).getAsString() );
        assertFalse( r.body().contains( "AlphaRestricted" ) );
    }

    @Test void warmingIndexAnswers503() throws Exception {
        final MentionScanResource spy = Mockito.spy( servlet );
        Mockito.doReturn( Optional.empty() ).when( spy ).titleLookup();
        Mockito.verify( post( spy, "{\"text\":\"x\"}" ).response() ).setStatus( 503 );
    }

    @Test void missingTextIs400() throws Exception {
        Mockito.verify( post( ready( Set.of() ), "{\"page\":\"P\"}" ).response() ).setStatus( 400 );
    }

    @Test void malformedJsonIs400() throws Exception {
        Mockito.verify( post( ready( Set.of() ), "{not json" ).response() ).setStatus( 400 );
    }

    @Test void oversizedBodyIs413() throws Exception {
        final String big = "{\"text\":\"" + "a".repeat( MentionScanResource.MAX_CHARS ) + "\"}";
        Mockito.verify( post( ready( Set.of() ), big ).response() ).setStatus( 413 );
    }
}
