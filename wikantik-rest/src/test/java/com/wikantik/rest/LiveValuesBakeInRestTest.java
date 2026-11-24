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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import com.wikantik.livevalues.LiveValuesLookup;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bake-in guard for live values on the REST write paths ({@code PageSaveHandler} and the frontmatter backfill):
 * read the raw page, save it again, and the stored summary and body tokens are byte-identical. Runs through the real
 * save pipeline (TestEngine), where the live-values service is registered, so an expanded value would show.
 */
class LiveValuesBakeInRestTest {

    private static final String SUMMARY = "{{wiki:articles|round=100}} articles, {{wiki:clusters}} clusters, kept current";
    private static final String BODY = "Intro with {{wiki:pages}} pages and {{wiki:cluster.a/b.pages|else=few}}.\n\n"
            + "```\n{{wiki:articles}}\n```\n";
    private static final Gson GSON = new Gson();

    private static TestEngine engine;
    private static PageResource servlet;

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
        installExpandingLiveValues();
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    private static ParsedPage stored( final String name ) throws Exception {
        final PageManager pm = engine.getManager( PageManager.class );
        return FrontmatterParser.parse( pm.getPureText( name, -1 ) );
    }

    private static void put( final String name, final JsonObject body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + name );
        Mockito.doReturn( "/" + name ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPut( request, response );
        assertTrue( GSON.fromJson( sw.toString(), JsonObject.class ).get( "success" ).getAsBoolean(), sw.toString() );
    }

    /** TestEngine registers no live-values service; install one that expands every token so a leak would show. */
    private static void installExpandingLiveValues() {
        final LiveValues lv = Mockito.mock( LiveValues.class );
        Mockito.when( lv.expandText( Mockito.anyString() ) ).thenAnswer( i ->
                ( (String) i.getArgument( 0 ) ).replaceAll( "\\{\\{wiki:[^}]*\\}\\}", "EXPANDED" ) );
        Mockito.when( lv.expandBody( Mockito.anyString() ) ).thenAnswer( i ->
                ( (String) i.getArgument( 0 ) ).replaceAll( "\\{\\{wiki:[^}]*\\}\\}", "EXPANDED" ) );
        engine.serviceRegistry().put( LiveValues.class, lv );
    }

    @Test
    void theLiveValuesServiceIsActiveSoAnExpansionWouldShow() {
        assertNotNull( LiveValuesLookup.of( engine ) );
        assertNotEquals( "{{wiki:version}}", LiveValuesLookup.of( engine ).expandText( "{{wiki:version}}" ) );
    }

    @Test
    void putOfRawContentWithFrontmatterKeepsTokens() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "---\ntype: article\nsummary: \"" + SUMMARY + "\"\n---\n" + BODY );
        put( "BakeInRaw", body );

        final ParsedPage p = stored( "BakeInRaw" );
        assertEquals( SUMMARY, p.metadata().get( "summary" ) );
        assertEquals( BODY.strip(), p.body().replace( "\r\n", "\n" ).strip() );
    }

    @Test
    void putOfStructuredEditorPayloadKeepsTokens() throws Exception {
        final JsonObject meta = new JsonObject();
        meta.addProperty( "type", "article" );
        meta.addProperty( "summary", SUMMARY );
        final JsonObject body = new JsonObject();
        body.addProperty( "content", BODY );
        body.add( "metadata", meta );
        body.addProperty( "replaceMetadata", true );
        put( "BakeInStructured", body );

        final ParsedPage p = stored( "BakeInStructured" );
        assertEquals( SUMMARY, p.metadata().get( "summary" ) );
        assertEquals( BODY.strip(), p.body().replace( "\r\n", "\n" ).strip() );
    }

    @Test
    void frontmatterBackfillKeepsBodyTokensByteIdentical() throws Exception {
        final PageManager pm = engine.getManager( PageManager.class );
        engine.saveText( "BakeInBackfill", BODY );
        final FrontmatterBackfillAdminHandlers handlers =
                new FrontmatterBackfillAdminHandlers( () -> pm, () -> null, () -> engine );

        final HttpServletResponse started = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( started ).getWriter();
        handlers.handlePostBackfillFrontmatter( started );
        awaitBackfill( handlers );

        final ParsedPage p = stored( "BakeInBackfill" );
        assertEquals( Boolean.TRUE, p.metadata().get( "auto-generated" ), "backfill must have run on this page" );
        assertEquals( BODY.strip(), p.body().replace( "\r\n", "\n" ).strip() );
    }

    private static void awaitBackfill( final FrontmatterBackfillAdminHandlers handlers ) throws Exception {
        final long deadline = System.currentTimeMillis() + 30_000;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
            handlers.handleGetBackfillStatus( response );
            final JsonObject status = GSON.fromJson( sw.toString(), JsonObject.class );
            if ( !status.get( "running" ).getAsBoolean() && status.get( "total" ).getAsInt() > 0 ) {
                return;
            }
            Thread.sleep( 50 );
        }
        throw new AssertionError( "backfill did not finish" );
    }
}
