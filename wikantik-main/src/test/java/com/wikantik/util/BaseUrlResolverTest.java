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
package com.wikantik.util;

import com.wikantik.api.core.Engine;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaseUrlResolverTest {

    // --- Tier 1: configured base URL ---

    @Test
    void testConfiguredBaseUrlIsUsedWhenPresent() {
        final Engine engine = mock( Engine.class );
        when( engine.getBaseURL() ).thenReturn( "http://engine.example.com" );
        final HttpServletRequest request = mockRequest( "http", "request.example.com", 8080, "/ctx" );

        final String result = BaseUrlResolver.resolve( engine, request, "https://configured.example.com" );

        assertEquals( "https://configured.example.com", result );
    }

    @Test
    void testConfiguredBaseUrlTrailingSlashIsStripped() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "http", "localhost", 80, "" );

        final String result = BaseUrlResolver.resolve( engine, request, "https://configured.example.com/" );

        assertEquals( "https://configured.example.com", result );
    }

    @Test
    void testBlankConfiguredBaseUrlFallsThrough() {
        final Engine engine = mock( Engine.class );
        when( engine.getBaseURL() ).thenReturn( "http://engine.example.com" );
        final HttpServletRequest request = mockRequest( "http", "localhost", 80, "" );

        final String result = BaseUrlResolver.resolve( engine, request, "   " );

        assertEquals( "http://engine.example.com", result );
    }

    // --- Tier 2: engine base URL ---

    @Test
    void testEngineBaseUrlIsUsedWhenConfiguredIsNull() {
        final Engine engine = mock( Engine.class );
        when( engine.getBaseURL() ).thenReturn( "https://engine.example.com/wiki" );
        final HttpServletRequest request = mockRequest( "http", "localhost", 8080, "" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "https://engine.example.com/wiki", result );
    }

    @Test
    void testEngineBaseUrlTrailingSlashIsStripped() {
        final Engine engine = mock( Engine.class );
        when( engine.getBaseURL() ).thenReturn( "http://engine.example.com/" );
        final HttpServletRequest request = mockRequest( "http", "localhost", 80, "" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://engine.example.com", result );
    }

    @Test
    void testEngineBaseUrlWithoutHttpFallsThrough() {
        final Engine engine = mock( Engine.class );
        when( engine.getBaseURL() ).thenReturn( "relative/path" );
        final HttpServletRequest request = mockRequest( "http", "localhost", 8080, "/ctx" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://localhost:8080/ctx", result );
    }

    @Test
    void testNullEngineBaseUrlFallsThrough() {
        final Engine engine = mock( Engine.class );
        when( engine.getBaseURL() ).thenReturn( null );
        final HttpServletRequest request = mockRequest( "http", "localhost", 8080, "" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://localhost:8080", result );
    }

    // --- Tier 3: request-derived URL ---

    @Test
    void testRequestDerivedHttpWithNonDefaultPort() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "http", "myhost.com", 8080, "/app" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://myhost.com:8080/app", result );
    }

    @Test
    void testRequestDerivedHttpWithDefaultPort() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "http", "myhost.com", 80, "/app" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://myhost.com/app", result );
    }

    @Test
    void testRequestDerivedHttpsWithDefaultPort() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "https", "secure.example.com", 443, "" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "https://secure.example.com", result );
    }

    @Test
    void testRequestDerivedHttpsWithNonDefaultPort() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "https", "secure.example.com", 8443, "/wiki" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "https://secure.example.com:8443/wiki", result );
    }

    @Test
    void testRequestDerivedWithNullContextPath() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "http", "localhost", 8080, null );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://localhost:8080", result );
    }

    @Test
    void testRequestDerivedTrailingSlashIsStripped() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( "http", "localhost", 8080, "/" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://localhost:8080", result );
    }

    // --- Null/edge cases ---

    @Test
    void testNullSchemeAndServerNameFallsBackToLocalhost() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( null, null, 80, "/ctx" );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://localhost/ctx", result );
    }

    @Test
    void testNullSchemeAndServerNameAndContextPath() {
        final Engine engine = mock( Engine.class );
        final HttpServletRequest request = mockRequest( null, null, 80, null );

        final String result = BaseUrlResolver.resolve( engine, request, null );

        assertEquals( "http://localhost", result );
    }

    @Test
    void testNullEngine() {
        final HttpServletRequest request = mockRequest( "http", "localhost", 9090, "/wiki" );

        final String result = BaseUrlResolver.resolve( null, request, null );

        assertEquals( "http://localhost:9090/wiki", result );
    }

    private static HttpServletRequest mockRequest( final String scheme, final String serverName,
                                                   final int serverPort, final String contextPath ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getScheme() ).thenReturn( scheme );
        when( request.getServerName() ).thenReturn( serverName );
        when( request.getServerPort() ).thenReturn( serverPort );
        when( request.getContextPath() ).thenReturn( contextPath );
        return request;
    }
}
