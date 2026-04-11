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

import com.wikantik.api.core.Engine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Properties;


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
}
