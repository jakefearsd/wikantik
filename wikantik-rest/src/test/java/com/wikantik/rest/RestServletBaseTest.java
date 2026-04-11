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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * Exercises the CORS handling in {@link RestServletBase}. Uses a tiny concrete
 * subclass so we can drive the protected {@code setCorsHeaders} helper without
 * spinning up a full servlet container.
 */
class RestServletBaseTest {

    private static class TestRestServlet extends RestServletBase {
        private final Engine engine;
        TestRestServlet( final Engine engine ) { this.engine = engine; }
        @Override protected Engine getEngine() { return engine; }
        // Expose the package-private helper for tests.
        void applyCors( final HttpServletRequest req, final HttpServletResponse resp ) {
            setCorsHeaders( req, resp );
        }
        String callRequirePathParam( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
            return requirePathParam( req, resp );
        }
        Page callRequirePage( final HttpServletRequest req, final HttpServletResponse resp, final String name ) throws IOException {
            return requirePage( req, resp, name );
        }
        JsonObject callParseJsonBody( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
            return parseJsonBody( req, resp );
        }
    }

    private Engine engine;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock( Engine.class );
        final Properties props = new Properties();
        props.setProperty( "wikantik.cors.allowedOrigins",
                "https://wiki.example.com,https://other.example.com" );
        Mockito.when( engine.getWikiProperties() ).thenReturn( props );
    }

    @Test
    void testAllowedOriginIsEchoed() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( "https://wiki.example.com" );

        servlet.applyCors( req, resp );

        Mockito.verify( resp ).setHeader( "Access-Control-Allow-Origin", "https://wiki.example.com" );
        Mockito.verify( resp ).setHeader( "Vary", "Origin" );
    }

    @Test
    void testDisallowedOriginNotEchoed() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( "https://evil.example.com" );

        servlet.applyCors( req, resp );

        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
    }

    @Test
    void testNoOriginHeaderEmitsNoCorsHeader() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( null );

        servlet.applyCors( req, resp );

        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
    }

    @Test
    void testCredentialsHeaderNeverSet() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( "https://wiki.example.com" );

        servlet.applyCors( req, resp );

        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Credentials" ), Mockito.anyString() );
    }

    // ---- New helper tests ----

    @Test
    void requirePathParam_nullPath_sends400AndReturnsNull() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        Mockito.when( req.getPathInfo() ).thenReturn( null );

        final String result = servlet.callRequirePathParam( req, resp );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void requirePathParam_emptyPath_sends400AndReturnsNull() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        Mockito.when( req.getPathInfo() ).thenReturn( "/" );

        final String result = servlet.callRequirePathParam( req, resp );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void requirePathParam_validPath_returnsValueWithoutSendingStatus() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getPathInfo() ).thenReturn( "/Main" );

        final String result = servlet.callRequirePathParam( req, resp );

        assertEquals( "Main", result );
        Mockito.verify( resp, Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    @Test
    void requirePage_missing_sends404AndReturnsNull() throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        Mockito.when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        Mockito.when( pm.getPage( "Ghost" ) ).thenReturn( null );

        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        final Page result = servlet.callRequirePage( req, resp, "Ghost" );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void requirePage_present_returnsPage() throws Exception {
        final Page page = Mockito.mock( Page.class );
        final PageManager pm = Mockito.mock( PageManager.class );
        Mockito.when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        Mockito.when( pm.getPage( "Main" ) ).thenReturn( page );

        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );

        final Page result = servlet.callRequirePage( req, resp, "Main" );

        assertNotNull( result );
        Mockito.verify( resp, Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    @Test
    void parseJsonBody_malformed_sends400WithExceptionDetail() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        Mockito.when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{bad json" ) ) );

        final JsonObject result = servlet.callParseJsonBody( req, resp );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void parseJsonBody_valid_returnsObject() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{\"x\":1}" ) ) );

        final JsonObject result = servlet.callParseJsonBody( req, resp );

        assertNotNull( result );
        assertEquals( 1, result.get( "x" ).getAsInt() );
        Mockito.verify( resp, Mockito.never() ).setStatus( Mockito.anyInt() );
    }
}
