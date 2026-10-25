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
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/fences/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
        final Session session = Mockito.mock( Session.class );
        Mockito.when( session.isAuthenticated() ).thenReturn( authenticated );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        try ( final MockedStatic< Wiki > wiki = mockWikiSession( session ) ) {
            servlet.doPost( request, response );
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
        final Reply r = post( "render", body( "plantuml", "@startuml\nclass {{{\nFoo -> \n@enduml" ), true );
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

    private MockedStatic< Wiki > mockWikiSession( final Session session ) {
        final MockedStatic< Wiki > wiki = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );
        final com.wikantik.api.spi.SessionSPI spi = Mockito.mock( com.wikantik.api.spi.SessionSPI.class );
        Mockito.when( spi.find( Mockito.any(), Mockito.any() ) ).thenReturn( session );
        wiki.when( Wiki::session ).thenReturn( spi );
        return wiki;
    }
}
