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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PingSearchEnginesTool using a stub HttpClient that returns
 * configurable status codes without actually making HTTP calls.
 */
class PingSearchEnginesToolTest {

    private final Gson gson = new Gson();

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final PingSearchEnginesTool tool,
                                                     final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    /** Stub HttpClient that returns a fixed status code for every request. */
    private static HttpClient stubClient( final int statusCode ) {
        return new StubHttpClient( statusCode, null );
    }

    /** Stub HttpClient that throws on every request. */
    private static HttpClient failingClient( final IOException exception ) {
        return new StubHttpClient( 0, exception );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testGooglePing_success() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", null, stubClient( 200 ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "service", "google_ping" );

        final Map< String, Object > data = executeAndParse( tool, args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "google_ping", results.get( 0 ).get( "service" ) );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( 200.0, results.get( 0 ).get( "status" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testIndexNow_success() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", "my-api-key-123", stubClient( 202 ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "service", "indexnow" );
        args.put( "urls", List.of( "http://wiki.example.com/wiki/PageA", "http://wiki.example.com/wiki/PageB" ) );

        final Map< String, Object > data = executeAndParse( tool, args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "indexnow", results.get( 0 ).get( "service" ) );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( 202.0, results.get( 0 ).get( "status" ) );
        assertEquals( 2.0, results.get( 0 ).get( "urlsSubmitted" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testIndexNow_noApiKey() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", null, stubClient( 200 ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "service", "indexnow" );

        final Map< String, Object > data = executeAndParse( tool, args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "indexnow", results.get( 0 ).get( "service" ) );
        assertEquals( false, results.get( 0 ).get( "success" ) );
        assertTrue( results.get( 0 ).get( "error" ).toString().contains( "apiKey" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAll_services() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", "my-key", stubClient( 200 ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "service", "all" );

        final Map< String, Object > data = executeAndParse( tool, args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 2, results.size() );

        final boolean hasGoogle = results.stream().anyMatch( r -> "google_ping".equals( r.get( "service" ) ) );
        final boolean hasIndexNow = results.stream().anyMatch( r -> "indexnow".equals( r.get( "service" ) ) );
        assertTrue( hasGoogle );
        assertTrue( hasIndexNow );
    }

    @Test
    void testToolDefinition() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", null, stubClient( 200 ) );

        final McpSchema.Tool def = tool.definition();
        assertEquals( "ping_search_engines", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testGooglePing_failure() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", null,
                failingClient( new IOException( "Connection refused" ) ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "service", "google_ping" );

        final Map< String, Object > data = executeAndParse( tool, args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( false, results.get( 0 ).get( "success" ) );
        assertNotNull( results.get( 0 ).get( "error" ) );
    }

    /**
     * A minimal stub HttpClient that returns a fixed response or throws an exception.
     * Avoids Mockito issues with sealed/final JDK classes.
     */
    private static class StubHttpClient extends HttpClient {
        private final int statusCode;
        private final IOException exception;

        StubHttpClient( final int statusCode, final IOException exception ) {
            this.statusCode = statusCode;
            this.exception = exception;
        }

        @Override
        public java.util.Optional< java.net.CookieHandler > cookieHandler() { return java.util.Optional.empty(); }
        @Override
        public java.util.Optional< java.time.Duration > connectTimeout() { return java.util.Optional.of( java.time.Duration.ofSeconds( 10 ) ); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public java.util.Optional< java.net.ProxySelector > proxy() { return java.util.Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override
        public java.util.Optional< java.net.Authenticator > authenticator() { return java.util.Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_1_1; }
        @Override
        public java.util.Optional< java.util.concurrent.Executor > executor() { return java.util.Optional.empty(); }

        @Override
        @SuppressWarnings( "unchecked" )
        public <T> HttpResponse< T > send( final HttpRequest request,
                                            final HttpResponse.BodyHandler< T > handler )
                throws IOException, InterruptedException {
            if ( exception != null ) {
                throw exception;
            }
            return ( HttpResponse< T > ) new StubResponse( statusCode );
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture< HttpResponse< T > > sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler< T > handler ) {
            return null;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture< HttpResponse< T > > sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler< T > handler,
                final HttpResponse.PushPromiseHandler< T > pushPromiseHandler ) {
            return null;
        }
    }

    private static class StubResponse implements HttpResponse< String > {
        private final int statusCode;

        StubResponse( final int statusCode ) { this.statusCode = statusCode; }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return null; }
        @Override public java.util.Optional< HttpResponse< String > > previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of( Map.of(), ( a, b ) -> true ); }
        @Override public String body() { return ""; }
        @Override public java.util.Optional< javax.net.ssl.SSLSession > sslSession() { return java.util.Optional.empty(); }
        @Override public java.net.URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
