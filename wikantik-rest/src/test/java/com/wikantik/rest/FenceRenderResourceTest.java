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
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import com.wikantik.WikiSubsystems;
import com.wikantik.markdown.extensions.fences.ServerDrawing;
import com.wikantik.markdown.extensions.fences.ServerFenceDrawer;
import com.wikantik.render.RenderingManager;

import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FenceRenderResourceTest {

    private static final String CLASS_DIAGRAM = "@startuml\nclass Foo {\n  +bar()\n}\nFoo --> Baz\n@enduml";

    private TestEngine engine;
    private FenceRenderResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        servlet = new FenceRenderResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    /** Result of one servlet call. */
    private record Reply( JsonObject json, HttpServletResponse response ) {}

    private Reply post( final String action, final String body, final boolean authenticated ) throws Exception {
        return post( servlet, action, body, authenticated );
    }

    private Reply post( final FenceRenderResource target, final String action, final String body, final boolean authenticated )
            throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/fences/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
        final Session session = Mockito.mock( Session.class );
        Mockito.when( session.isAuthenticated() ).thenReturn( authenticated );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        try ( final MockedStatic< Wiki > wiki = mockWikiSession( session ) ) {
            target.doPost( request, response );
        }
        return new Reply( gson.fromJson( sw.toString(), JsonObject.class ), response );
    }

    private static String body( final String language, final String source ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "language", language );
        o.addProperty( "source", source );
        return o.toString();
    }

    @Test
    void unauthenticated401() throws Exception {
        final Reply r = post( "render", body( "plantuml", CLASS_DIAGRAM ), false );
        assertEquals( 401, r.json().get( "status" ).getAsInt() );
    }

    @Test
    void drawsForAuthenticatedUser() throws Exception {
        final Reply r = post( "render", body( "plantuml", CLASS_DIAGRAM ), true );
        assertTrue( r.json().get( "lightSvg" ).getAsString().startsWith( "<svg" ), r.json().toString() );
        assertTrue( r.json().get( "darkSvg" ).getAsString().startsWith( "<svg" ) );
        Mockito.verify( r.response() ).setHeader( "Cache-Control", "no-store" );
    }

    @Test
    void syntaxErrorReturnsLine() throws Exception {
        final Reply r = post( "render", body( "plantuml", "@startuml\nclass {\nFoo -> \n@enduml" ), true );
        assertEquals( "syntax-error", r.json().get( "error" ).getAsString(), r.json().toString() );
        assertTrue( r.json().get( "line" ).getAsInt() > 0, r.json().toString() );
        Mockito.verify( r.response(), Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    @Test
    void policyRefusalReturnsReason() throws Exception {
        final Reply r = post( "render", body( "plantuml", "@startuml\n!include x\nA -> B\n@enduml" ), true );
        assertEquals( "include", r.json().get( "error" ).getAsString(), r.json().toString() );
    }

    @Test
    void browserLanguage400() throws Exception {
        final Reply r = post( "render", body( "mermaid", "graph TD; A-->B" ), true );
        Mockito.verify( r.response() ).setStatus( 400 );
        assertEquals( "unsupported-language", r.json().get( "error" ).getAsString() );
    }

    @Test
    void badJson400() throws Exception {
        final Reply r = post( "render", "not json", true );
        assertEquals( 400, r.json().get( "status" ).getAsInt() );
    }

    @Test
    void jsonArrayBody400() throws Exception {
        final Reply r = post( "render", "[]", true );
        assertEquals( 400, r.json().get( "status" ).getAsInt() );
    }

    @Test
    void malformedJsonObject400() throws Exception {
        final Reply r = post( "render", "{\"language\": ", true );
        assertEquals( 400, r.json().get( "status" ).getAsInt() );
    }

    @Test
    void missingSource400() throws Exception {
        final JsonObject o = new JsonObject();
        o.addProperty( "language", "plantuml" );
        final Reply r = post( "render", o.toString(), true );
        assertEquals( 400, r.json().get( "status" ).getAsInt() );
    }

    @Test
    void unknownAction404() throws Exception {
        final Reply r = post( "bogus", body( "plantuml", CLASS_DIAGRAM ), true );
        assertEquals( 404, r.json().get( "status" ).getAsInt() );
    }

    // ---- request validation ------------------------------------------------------------------------------------------

    @Test
    void missingLanguage400() throws Exception {
        final JsonObject o = new JsonObject();
        o.addProperty( "source", CLASS_DIAGRAM );
        assertEquals( 400, post( "render", o.toString(), true ).json().get( "status" ).getAsInt() );
    }

    @Test
    void blankLanguage400() throws Exception {
        assertEquals( 400, post( "render", body( "   ", CLASS_DIAGRAM ), true ).json().get( "status" ).getAsInt() );
        assertEquals( 400, post( "render", body( "", CLASS_DIAGRAM ), true ).json().get( "status" ).getAsInt() );
    }

    @Test
    void nullSource400() throws Exception {
        assertEquals( 400, post( "render", "{\"language\":\"plantuml\",\"source\":null}", true ).json().get( "status" ).getAsInt() );
    }

    @Test
    void emptyJsonObject400() throws Exception {
        assertEquals( 400, post( "render", "{}", true ).json().get( "status" ).getAsInt() );
    }

    @Test
    void jsonNullAndScalarBodies400() throws Exception {
        assertEquals( 400, post( "render", "null", true ).json().get( "status" ).getAsInt() );
        assertEquals( 400, post( "render", "\"text\"", true ).json().get( "status" ).getAsInt() );
        assertEquals( 400, post( "render", "42", true ).json().get( "status" ).getAsInt() );
        assertEquals( 400, post( "render", "", true ).json().get( "status" ).getAsInt() );
    }

    @Test
    void nonStringFieldsAreNotTreatedAsStrings() throws Exception {
        assertEquals( 400, post( "render", "{\"language\":{\"a\":1},\"source\":\"A -> B\"}", true ).json().get( "status" ).getAsInt() );
    }

    @Test
    void unknownLanguageIsRefusedWithoutDrawing() throws Exception {
        final Reply r = post( "render", body( "PlantUML", CLASS_DIAGRAM ), true );
        Mockito.verify( r.response() ).setStatus( 400 );
        assertEquals( "unsupported-language", r.json().get( "error" ).getAsString(), "language names are exact lowercase keys" );
    }

    @Test
    void anyActionOtherThanRenderIs404AndNotCacheable() throws Exception {
        for ( final String action : new String[] { "Render", "render/extra", "draw", "" } ) {
            final Reply r = post( action, body( "plantuml", CLASS_DIAGRAM ), true );
            assertEquals( 404, r.json().get( "status" ).getAsInt(), "action '" + action + "'" );
            Mockito.verify( r.response() ).setHeader( "Cache-Control", "no-store" );
        }
    }

    @Test
    void unauthenticatedAndInvalidRequestsAreNeverCacheable() throws Exception {
        final Reply denied = post( "render", body( "plantuml", CLASS_DIAGRAM ), false );
        Mockito.verify( denied.response() ).setHeader( "Cache-Control", "no-store" );
        final Reply bad = post( "render", "nope", true );
        Mockito.verify( bad.response() ).setHeader( "Cache-Control", "no-store" );
    }

    @Test
    void unauthenticatedRequestNeverReachesTheDrawer() throws Exception {
        final int[] calls = { 0 };
        final FenceRenderResource stubbed = withDrawer( new StubDrawer( ( s, b ) -> {
            calls[ 0 ]++;
            return ServerDrawing.drawn( "<svg/>", "<svg/>" );
        } ) );
        assertEquals( 401, post( stubbed, "render", body( "plantuml", "A -> B" ), false ).json().get( "status" ).getAsInt() );
        assertEquals( 0, calls[ 0 ] );
    }

    // ---- the real drawer behind the endpoint -------------------------------------------------------------------------

    @Test
    void oversizedSourceIsRefusedWithAReason() throws Exception {
        final Reply r = post( "render", body( "plantuml", "A -> B : " + "x".repeat( 70_000 ) + "\n" ), true );
        assertEquals( "too-large", r.json().get( "error" ).getAsString(), r.json().toString() );
        assertFalse( r.json().has( "lightSvg" ) );
        Mockito.verify( r.response(), Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    @Test
    void emptySourceIsHandledWithoutAServerError() throws Exception {
        final Reply r = post( "render", body( "plantuml", "" ), true );
        Mockito.verify( r.response(), Mockito.never() ).setStatus( Mockito.anyInt() );
        assertTrue( r.json().has( "error" ) || r.json().has( "lightSvg" ), r.json().toString() );
    }

    @Test
    void killSwitchAnswersPlantumlDisabled() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.fences.plantuml.enabled", "false" );
        final TestEngine off = new TestEngine( props );
        try {
            final FenceRenderResource s = new FenceRenderResource();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( off.getServletContext() ).when( config ).getServletContext();
            s.init( config );

            final Reply r = post( s, "render", body( "plantuml", CLASS_DIAGRAM ), true );

            assertEquals( "plantuml-disabled", r.json().get( "error" ).getAsString(), r.json().toString() );
            assertEquals( "PlantUML rendering is disabled", r.json().get( "message" ).getAsString() );
            assertFalse( r.json().has( "lightSvg" ) );
        } finally {
            off.stop();
        }
    }

    // ---- shape of the answer for each outcome ------------------------------------------------------------------------

    private record StubDrawer( java.util.function.BiFunction< String, Long, ServerDrawing > outcome ) implements ServerFenceDrawer {
        @Override public String language() { return "plantuml"; }
        @Override public ServerDrawing draw( final String source, final long budgetMs ) { return outcome.apply( source, budgetMs ); }
        @Override public long defaultBudgetMs() { return 1234L; }
    }

    /** The servlet, with its rendering manager answering {@code drawer} for the plantuml language. */
    private FenceRenderResource withDrawer( final ServerFenceDrawer drawer ) throws Exception {
        final RenderingManager rm = Mockito.mock( RenderingManager.class );
        Mockito.when( rm.serverFenceDrawer( Mockito.anyString() ) ).thenAnswer( inv ->
                "plantuml".equals( inv.getArgument( 0 ) ) ? Optional.of( drawer ) : Optional.empty() );
        final WikiSubsystems subsystems = Mockito.mock( WikiSubsystems.class, Mockito.RETURNS_DEEP_STUBS );
        Mockito.when( subsystems.rendering().renderingManager() ).thenReturn( rm );
        final FenceRenderResource s = new FenceRenderResource() {
            private static final long serialVersionUID = 1L;
            @Override protected WikiSubsystems getSubsystems() { return subsystems; }
        };
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        s.init( config );
        return s;
    }

    @Test
    void theDrawerIsGivenItsOwnDefaultBudgetAndTheExactSource() throws Exception {
        final java.util.List< Object > seen = new java.util.ArrayList<>();
        final FenceRenderResource s = withDrawer( new StubDrawer( ( src, budget ) -> {
            seen.add( src );
            seen.add( budget );
            return ServerDrawing.drawn( "<svg>l</svg>", "<svg>d</svg>" );
        } ) );
        final Reply r = post( s, "render", body( "plantuml", "A -> B\r\n" ), true );
        assertEquals( java.util.List.of( "A -> B\r\n", 1234L ), seen );
        assertEquals( "<svg>l</svg>", r.json().get( "lightSvg" ).getAsString() );
        assertEquals( "<svg>d</svg>", r.json().get( "darkSvg" ).getAsString() );
        assertFalse( r.json().has( "error" ) );
    }

    @Test
    void refusalCarriesErrorMessageAndLine() throws Exception {
        final FenceRenderResource s = withDrawer( new StubDrawer( ( src, b ) -> ServerDrawing.refused( "syntax-error", "bad thing", 7, false ) ) );
        final Reply r = post( s, "render", body( "plantuml", "x" ), true );
        assertEquals( "syntax-error", r.json().get( "error" ).getAsString() );
        assertEquals( "bad thing", r.json().get( "message" ).getAsString() );
        assertEquals( 7, r.json().get( "line" ).getAsInt() );
        assertFalse( r.json().has( "lightSvg" ) );
    }

    @Test
    void refusalWithoutMessageOrLineCarriesOnlyTheReason() throws Exception {
        final FenceRenderResource s = withDrawer( new StubDrawer( ( src, b ) -> ServerDrawing.refused( "busy", null, 0, true ) ) );
        final Reply r = post( s, "render", body( "plantuml", "x" ), true );
        assertEquals( java.util.Set.of( "error" ), r.json().keySet() );
        assertEquals( "busy", r.json().get( "error" ).getAsString() );
    }

    // ---- cross-origin ------------------------------------------------------------------------------------------------

    @Test
    void aForeignOriginIsNeverGrantedCrossOriginAccess() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/fences/render" );
        Mockito.doReturn( "/render" ).when( request ).getPathInfo();
        Mockito.doReturn( "POST" ).when( request ).getMethod();
        Mockito.doReturn( "https://evil.example" ).when( request ).getHeader( "Origin" );
        Mockito.doReturn( new BufferedReader( new StringReader( body( "plantuml", CLASS_DIAGRAM ) ) ) ).when( request ).getReader();
        final Session session = Mockito.mock( Session.class );
        Mockito.when( session.isAuthenticated() ).thenReturn( true );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();

        try ( final MockedStatic< Wiki > wiki = mockWikiSession( session ) ) {
            servlet.service( request, response );
        }

        Mockito.verify( response, Mockito.never() ).setHeader( Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
        Mockito.verify( response, Mockito.never() ).setHeader( Mockito.eq( "Access-Control-Allow-Credentials" ), Mockito.anyString() );
        Mockito.verify( response ).setHeader( "Vary", "Origin" );
    }

    private MockedStatic< Wiki > mockWikiSession( final Session session ) {
        final MockedStatic< Wiki > wiki = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );
        final com.wikantik.api.spi.SessionSPI spi = Mockito.mock( com.wikantik.api.spi.SessionSPI.class );
        Mockito.when( spi.find( Mockito.any(), Mockito.any() ) ).thenReturn( session );
        wiki.when( Wiki::session ).thenReturn( spi );
        return wiki;
    }
}
