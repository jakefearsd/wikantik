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
    private static StubHttpClient stubClient( final int statusCode ) {
        return new StubHttpClient( statusCode, null );
    }

    /** Stub HttpClient that throws on every request. */
    private static HttpClient failingClient( final IOException exception ) {
        return new StubHttpClient( 0, exception );
    }

    @Test
    void pingIsNotReadOnlyAndIsOpenWorld() {
        final McpSchema.ToolAnnotations ann = new PingSearchEnginesTool(
                "http://wiki.example.com", null, stubClient( 200 ) ).definition().annotations();
        assertEquals( Boolean.FALSE, ann.readOnlyHint(),
                "ping_search_engines makes external HTTP calls — must not be read-only" );
        assertEquals( Boolean.TRUE, ann.openWorldHint(),
                "ping_search_engines interacts with external systems — open-world" );
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
    void testIndexNow_blankApiKey() {
        // Config-surface burn-down (task 7): wikantik.indexnow.apiKey now ships as an
        // explicit blank default in ini/wikantik.properties rather than being absent, so
        // McpToolRegistry passes "" (not null) through when the operator hasn't set one.
        // Must degrade identically to the null case.
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", "", stubClient( 200 ) );

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
    @SuppressWarnings( "unchecked" )
    void testIndexNow_rejectsUrlOutsideConfiguredBase() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool(
                "http://wiki.example.com", "my-api-key-123", stubClient( 200 ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "service", "indexnow" );
        args.put( "urls", List.of( "http://169.254.169.254/meta-data",
                                   "http://wiki.example.com/wiki/PageA" ) );

        final Map< String, Object > data = executeAndParse( tool, args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        final Map< String, Object > indexNow = results.get( 0 );
        // Must not silently submit the hostile URL; the tool should reject the request.
        assertEquals( false, indexNow.get( "success" ) );
        assertNotNull( indexNow.get( "error" ) );
        assertTrue( indexNow.get( "error" ).toString().toLowerCase().contains( "url" ),
                    "error should mention rejected URL, was: " + indexNow.get( "error" ) );
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

    private static String bodyOf( final HttpRequest request ) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe( new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe( final java.util.concurrent.Flow.Subscription s ) { s.request( Long.MAX_VALUE ); }
            @Override public void onNext( final java.nio.ByteBuffer b ) {
                final byte[] bytes = new byte[ b.remaining() ];
                b.get( bytes );
                out.writeBytes( bytes );
            }
            @Override public void onError( final Throwable t ) { throw new IllegalStateException( t ); }
            @Override public void onComplete() { /* all bytes delivered */ }
        } );
        return out.toString( java.nio.charset.StandardCharsets.UTF_8 );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > only( final PingSearchEnginesTool tool, final Map< String, Object > args ) {
        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) executeAndParse( tool, args ).get( "results" );
        assertEquals( 1, results.size(), results.toString() );
        return results.get( 0 );
    }

    private static Map< String, Object > svc( final String service, final Object... more ) {
        final Map< String, Object > a = new HashMap<>();
        a.put( "service", service );
        for ( int i = 0; i < more.length; i += 2 ) {
            a.put( ( String ) more[ i ], more[ i + 1 ] );
        }
        return a;
    }

    @Test
    void successIsExactlyTheTwoHundredRangeForBothServices() {
        final int[] statuses = { 199, 200, 299, 300 };
        final boolean[] expected = { false, true, true, false };
        for ( int i = 0; i < statuses.length; i++ ) {
            final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "key", stubClient( statuses[ i ] ) );
            assertEquals( expected[ i ], only( tool, svc( "google_ping" ) ).get( "success" ), "google " + statuses[ i ] );
            assertEquals( expected[ i ], only( tool, svc( "indexnow" ) ).get( "success" ), "indexnow " + statuses[ i ] );
        }
    }

    @Test
    void googlePingSubmitsTheEncodedSitemapUrlAndReportsIt() {
        final StubHttpClient client = stubClient( 200 );
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com/", null, client );

        final Map< String, Object > entry = only( tool, svc( "google_ping" ) );

        assertEquals( "http://wiki.example.com/sitemap.xml", entry.get( "sitemapUrl" ) );
        assertEquals( "GET", client.lastRequest.method() );
        assertEquals( "https://www.google.com/ping?sitemap=http%3A%2F%2Fwiki.example.com%2Fsitemap.xml",
                client.lastRequest.uri().toString() );
        assertEquals( "http://wiki.example.com", tool.baseUrl(), "trailing slash stripped" );
    }

    @Test
    void indexNowPostsHostKeyAndEveryUrlCommaSeparated() {
        final StubHttpClient client = stubClient( 200 );
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k-1", client );

        only( tool, svc( "indexnow", "urls", List.of( "http://wiki.example.com/wiki/A", "http://WIKI.example.com/wiki/B",
                "http://wiki.example.com/wiki/C" ) ) );

        assertEquals( "POST", client.lastRequest.method() );
        assertEquals( "https://api.indexnow.org/indexnow", client.lastRequest.uri().toString() );
        assertEquals( "{\"host\":\"wiki.example.com\",\"key\":\"k-1\",\"urlList\":["
                + "\"http://wiki.example.com/wiki/A\",\"http://WIKI.example.com/wiki/B\",\"http://wiki.example.com/wiki/C\"]}",
                bodyOf( client.lastRequest ) );
    }

    @Test
    void indexNowSubmitsTheSitemapWhenNoUrlsOrAnEmptyListAreGiven() {
        for ( final Object urls : new Object[] { null, List.of() } ) {
            final StubHttpClient client = stubClient( 200 );
            final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k", client );

            final Map< String, Object > entry = only( tool, svc( "indexnow", "urls", urls ) );

            assertEquals( 1.0, entry.get( "urlsSubmitted" ) );
            assertTrue( bodyOf( client.lastRequest ).endsWith( "\"urlList\":[\"http://wiki.example.com/sitemap.xml\"]}" ) );
        }
    }

    @Test
    void indexNowRejectsUrlsOfAnotherSchemePortHostOrNoHostAndNeverSends() {
        final String[] hostile = { "https://wiki.example.com/p", "http://wiki.example.com:8080/p", "http://other.example.com/p",
            "/relative/path", "http://wiki.example.com/bad path" };
        for ( final String url : hostile ) {
            final StubHttpClient client = stubClient( 200 );
            final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k", client );

            final Map< String, Object > entry = only( tool, svc( "indexnow", "urls", List.of( url ) ) );

            assertEquals( false, entry.get( "success" ), url );
            assertEquals( "Rejected URL outside configured baseUrl: " + url, entry.get( "error" ) );
            assertEquals( 0, client.sends, url );
        }
    }

    @Test
    void indexNowRejectsANullUrlEntry() {
        final StubHttpClient client = stubClient( 200 );
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k", client );
        final List< String > urls = new java.util.ArrayList<>();
        urls.add( null );

        final Map< String, Object > entry = only( tool, svc( "indexnow", "urls", urls ) );

        assertEquals( false, entry.get( "success" ) );
        assertEquals( 0, client.sends );
    }

    @Test
    void indexNowAcceptsAnExplicitPortThatMatchesTheBase() {
        final StubHttpClient client = stubClient( 200 );
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com:8080", "k", client );

        final Map< String, Object > entry = only( tool, svc( "indexnow", "urls", List.of( "http://wiki.example.com:8080/p" ) ) );

        assertEquals( true, entry.get( "success" ) );
    }

    @Test
    void aBaseUrlThatIsNotAbsoluteHttpWithAHostFailsBothServicesWithoutSending() {
        final String[] bases = { "", "   ", "/wiki", "ftp://wiki.example.com", "wiki.example.com", "http://" };
        for ( final String base : bases ) {
            final StubHttpClient client = stubClient( 200 );
            final PingSearchEnginesTool tool = new PingSearchEnginesTool( base, "k", client );

            final Map< String, Object > google = only( tool, svc( "google_ping" ) );
            final Map< String, Object > indexNow = only( tool, svc( "indexnow" ) );

            assertEquals( false, google.get( "success" ), "'" + base + "'" );
            assertTrue( google.get( "error" ).toString().contains( "absolute wikantik.baseURL" ), google.toString() );
            assertEquals( false, indexNow.get( "success" ), "'" + base + "'" );
            assertTrue( indexNow.get( "error" ).toString().contains( "absolute wikantik.baseURL" ), indexNow.toString() );
            assertEquals( 0, client.sends, "'" + base + "'" );
        }
    }

    @Test
    void anHttpsBaseUrlIsAccepted() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "https://wiki.example.com", "k", stubClient( 200 ) );
        assertEquals( true, only( tool, svc( "google_ping" ) ).get( "success" ) );
    }

    @Test
    void aMissingOrBlankServiceIsAnErrorAndAnUnknownOneDoesNothing() {
        final StubHttpClient client = stubClient( 200 );
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k", client );

        final McpSchema.CallToolResult blank = tool.execute( svc( "  " ) );
        assertTrue( blank.isError() );
        assertTrue( ( ( McpSchema.TextContent ) blank.content().get( 0 ) ).text().contains( "Missing required parameter: service" ) );
        @SuppressWarnings( "unchecked" )
        final List< Object > none = ( List< Object > ) executeAndParse( tool, svc( "bing" ) ).get( "results" );
        assertEquals( List.of(), none );
        assertEquals( 0, client.sends );
    }

    @Test
    void anInterruptedIndexNowSendReportsFailureAndRestoresTheInterruptFlag() {
        final StubHttpClient client = stubClient( 200 );
        client.interrupt = true;
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k", client );
        try {
            final Map< String, Object > entry = only( tool, svc( "indexnow" ) );

            assertEquals( false, entry.get( "success" ) );
            assertTrue( entry.get( "error" ).toString().startsWith( "IndexNow ping failed" ), entry.toString() );
            assertTrue( Thread.currentThread().isInterrupted(), "interrupt status must be preserved" );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anIndexNowIoFailureIsReportedWithItsMessage() {
        final PingSearchEnginesTool tool = new PingSearchEnginesTool( "http://wiki.example.com", "k",
                failingClient( new IOException( "boom" ) ) );
        final Map< String, Object > entry = only( tool, svc( "indexnow" ) );
        assertEquals( "IndexNow ping failed: boom", entry.get( "error" ) );
    }

    /**
     * A minimal stub HttpClient that returns a fixed response or throws an exception.
     * Avoids Mockito issues with sealed/final JDK classes.
     */
    private static class StubHttpClient extends HttpClient {
        private final int statusCode;
        private final IOException exception;
        HttpRequest lastRequest;
        int sends;
        boolean interrupt;

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
            lastRequest = request;
            sends++;
            if ( interrupt ) {
                throw new InterruptedException( "interrupted by test" );
            }
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
