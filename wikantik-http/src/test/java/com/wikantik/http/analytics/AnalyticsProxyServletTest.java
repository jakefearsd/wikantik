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

package com.wikantik.http.analytics;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsProxyServletTest {

    private static final String SCRIPT = "console.log('umami');";

    private HttpServer upstream;
    private final AtomicInteger scriptHits = new AtomicInteger();
    private final AtomicInteger sendHits = new AtomicInteger();
    private final AtomicReference< String > seenXff = new AtomicReference<>();
    private final AtomicReference< String > seenUserAgent = new AtomicReference<>();
    private final AtomicReference< String > seenBody = new AtomicReference<>();
    private final AtomicReference< String > seenCache = new AtomicReference<>();
    private volatile long sendDelayMs;
    private volatile boolean scriptFails;
    private volatile long scriptDelayMs;
    private final CountDownLatch sendArrived = new CountDownLatch( 1 );

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        upstream.setExecutor( Executors.newCachedThreadPool() );
        upstream.createContext( "/script.js", ex -> {
            scriptHits.incrementAndGet();
            if( scriptDelayMs > 0 ) {
                try {
                    Thread.sleep( scriptDelayMs );
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
            }
            if( scriptFails ) {
                ex.sendResponseHeaders( 500, -1 );
                ex.close();
                return;
            }
            final byte[] b = SCRIPT.getBytes( StandardCharsets.UTF_8 );
            ex.getResponseHeaders().add( "Content-Type", "text/javascript" );
            ex.sendResponseHeaders( 200, b.length );
            ex.getResponseBody().write( b );
            ex.close();
        } );
        upstream.createContext( "/api/send", ex -> {
            sendHits.incrementAndGet();
            sendArrived.countDown();
            seenXff.set( ex.getRequestHeaders().getFirst( "X-Forwarded-For" ) );
            seenUserAgent.set( ex.getRequestHeaders().getFirst( "User-Agent" ) );
            seenCache.set( ex.getRequestHeaders().getFirst( "x-umami-cache" ) );
            seenBody.set( new String( ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 ) );
            if( sendDelayMs > 0 ) {
                try {
                    Thread.sleep( sendDelayMs );
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
            }
            final byte[] b = "{\"cache\":\"abc\"}".getBytes( StandardCharsets.UTF_8 );
            ex.getResponseHeaders().add( "Content-Type", "application/json" );
            ex.sendResponseHeaders( 202, b.length );
            ex.getResponseBody().write( b );
            ex.close();
        } );
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop( 0 );
        ( ( java.util.concurrent.ExecutorService ) upstream.getExecutor() ).shutdownNow();
    }

    private String url() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private AnalyticsProxyServlet servlet( final String upstreamUrl, final Duration timeout, final int maxInflight,
                                           final Clock clock ) {
        final AnalyticsProxyServlet.AnalyticsProxyConfig cfg = new AnalyticsProxyServlet.AnalyticsProxyConfig(
                upstreamUrl, "script.js", timeout, maxInflight, "site-id" );
        return new AnalyticsProxyServlet( HttpClient.newBuilder().connectTimeout( cfg.timeout() )
                .followRedirects( HttpClient.Redirect.NEVER ).build(), () -> cfg, clock );
    }

    private AnalyticsProxyServlet servlet() {
        return servlet( url(), Duration.ofSeconds( 5 ), 16, Clock.systemUTC() );
    }

    /** Captured servlet response. */
    private static final class Resp {
        final HttpServletResponse mock = Mockito.mock( HttpServletResponse.class );
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = 200;
        final java.util.Map< String, String > headers = new java.util.HashMap<>();

        Resp() throws IOException {
            Mockito.doAnswer( i -> { status = i.getArgument( 0 ); return null; } ).when( mock ).setStatus( Mockito.anyInt() );
            Mockito.doAnswer( i -> { status = i.getArgument( 0 ); return null; } ).when( mock ).sendError( Mockito.anyInt() );
            Mockito.doAnswer( i -> { headers.put( i.getArgument( 0 ), i.getArgument( 1 ) ); return null; } )
                    .when( mock ).setHeader( Mockito.anyString(), Mockito.anyString() );
            Mockito.doAnswer( i -> { headers.put( "Content-Type", i.getArgument( 0 ) ); return null; } )
                    .when( mock ).setContentType( Mockito.anyString() );
            Mockito.doReturn( new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener( final WriteListener l ) { }
                @Override public void write( final int b ) { out.write( b ); }
            } ).when( mock ).getOutputStream();
        }

        String body() { return out.toString( StandardCharsets.UTF_8 ); }
    }

    private static HttpServletRequest request( final String pathInfo, final byte[] body ) throws IOException {
        return request( pathInfo, body, null );
    }

    private static HttpServletRequest request( final String pathInfo, final byte[] body, final String umamiCache ) throws IOException {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( umamiCache ).when( req ).getHeader( "x-umami-cache" );
        Mockito.doReturn( "/analytics" ).when( req ).getServletPath();
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( "203.0.113.7" ).when( req ).getRemoteAddr();
        Mockito.doReturn( "Mozilla/5.0 TestBrowser" ).when( req ).getHeader( "User-Agent" );
        Mockito.doReturn( "6.6.6.6" ).when( req ).getHeader( "X-Forwarded-For" );
        Mockito.doReturn( "application/json" ).when( req ).getContentType();
        if( body != null ) {
            Mockito.doReturn( body.length ).when( req ).getContentLength();
            final ByteArrayInputStream in = new ByteArrayInputStream( body );
            Mockito.doReturn( new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener( final ReadListener l ) { }
                @Override public int read() { return in.read(); }
            } ).when( req ).getInputStream();
        }
        return req;
    }

    private Resp get( final AnalyticsProxyServlet s, final String pathInfo ) throws Exception {
        final Resp r = new Resp();
        s.doGet( request( pathInfo, null ), r.mock );
        return r;
    }

    private Resp postWithCache( final AnalyticsProxyServlet s, final byte[] body, final String cache ) throws Exception {
        final Resp r = new Resp();
        s.doPost( request( "/api/send", body, cache ), r.mock );
        return r;
    }

    private static byte[] payload( final String website ) {
        return ( "{\"type\":\"event\",\"payload\":{\"website\":\"" + website + "\"}}" ).getBytes( StandardCharsets.UTF_8 );
    }

    private Resp post( final AnalyticsProxyServlet s, final String pathInfo, final byte[] body ) throws Exception {
        final Resp r = new Resp();
        s.doPost( request( pathInfo, body ), r.mock );
        return r;
    }

    @Test
    void scriptIsRelayedAndCached() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        final Resp a = get( s, "/script.js" );
        final Resp b = get( s, "/script.js" );
        assertEquals( 1, scriptHits.get() );
        assertEquals( 200, a.status );
        assertEquals( 200, b.status );
        assertEquals( SCRIPT, b.body() );
        assertEquals( "application/javascript", a.headers.get( "Content-Type" ) );
        assertEquals( "public, max-age=3600", a.headers.get( "Cache-Control" ) );
    }

    @Test
    void staleScriptServedWhenUpstreamDown() throws Exception {
        final MutableClock clock = new MutableClock();
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 1 ), 16, clock );
        assertEquals( SCRIPT, get( s, "/script.js" ).body() );
        upstream.stop( 0 );
        clock.advance( Duration.ofHours( 2 ) );
        final Resp r = get( s, "/script.js" );
        assertEquals( 200, r.status );
        assertEquals( SCRIPT, r.body() );
    }

    @Test
    void failedRefreshBacksOffForAMinute() throws Exception {
        final MutableClock clock = new MutableClock();
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 1 ), 16, clock );
        assertEquals( SCRIPT, get( s, "/script.js" ).body() );
        scriptFails = true;
        clock.advance( Duration.ofHours( 2 ) );
        assertEquals( SCRIPT, get( s, "/script.js" ).body() );
        assertEquals( 2, scriptHits.get() );
        clock.advance( Duration.ofSeconds( 30 ) );
        assertEquals( SCRIPT, get( s, "/script.js" ).body() );
        assertEquals( 2, scriptHits.get(), "no upstream call inside the backoff window" );
        clock.advance( Duration.ofSeconds( 31 ) );
        assertEquals( SCRIPT, get( s, "/script.js" ).body() );
        assertEquals( 3, scriptHits.get() );
    }

    @Test
    void configParsingFallsBackToDefaults() {
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( "wikantik.analytics.upstream", "http://u" );
        p.setProperty( "wikantik.analytics.website.id", "w" );
        p.setProperty( "wikantik.analytics.proxy.timeout.ms", "abc" );
        p.setProperty( "wikantik.analytics.proxy.max.inflight", "0" );
        AnalyticsProxyServlet.AnalyticsProxyConfig c = AnalyticsProxyServlet.parseConfig( p );
        assertEquals( Duration.ofMillis( 5000 ), c.timeout() );
        assertEquals( 16, c.maxInflight() );
        p.setProperty( "wikantik.analytics.proxy.timeout.ms", "-5" );
        assertEquals( Duration.ofMillis( 5000 ), AnalyticsProxyServlet.parseConfig( p ).timeout() );
        p.setProperty( "wikantik.analytics.proxy.timeout.ms", "250" );
        p.setProperty( "wikantik.analytics.proxy.max.inflight", "3" );
        c = AnalyticsProxyServlet.parseConfig( p );
        assertEquals( Duration.ofMillis( 250 ), c.timeout() );
        assertEquals( 3, c.maxInflight() );
    }

    @Test
    void nonPositiveTimeoutDoesNotBreakRequests() throws Exception {
        final AnalyticsProxyServlet s = servlet( url(), Duration.ZERO, 0, Clock.systemUTC() );
        assertEquals( 200, get( s, "/script.js" ).status );
    }

    @Test
    void scriptWithNothingCachedAndUpstreamDownIs502() throws Exception {
        upstream.stop( 0 );
        assertEquals( 502, get( servlet( url(), Duration.ofSeconds( 1 ), 16, Clock.systemUTC() ), "/script.js" ).status );
    }

    @Test
    void sendIsRelayedWithResolvedClientIp() throws Exception {
        final byte[] body = payload( "site-id" );
        final Resp r = post( servlet(), "/api/send", body );
        assertEquals( 202, r.status );
        assertEquals( "{\"cache\":\"abc\"}", r.body() );
        assertEquals( "203.0.113.7", seenXff.get() );
        assertEquals( "Mozilla/5.0 TestBrowser", seenUserAgent.get() );
        assertEquals( new String( body, StandardCharsets.UTF_8 ), seenBody.get() );
    }

    @Test
    void umamiCacheHeaderIsForwardedAndResponseTokenReturnedUnchanged() throws Exception {
        final Resp r = postWithCache( servlet(), payload( "site-id" ), "tok.en-123" );
        assertEquals( "tok.en-123", seenCache.get() );
        assertEquals( "{\"cache\":\"abc\"}", r.body() );
    }

    @Test
    void oversizedOrAbsentUmamiCacheHeaderIsNotForwarded() throws Exception {
        postWithCache( servlet(), payload( "site-id" ), "x".repeat( 4097 ) );
        assertEquals( null, seenCache.get() );
        postWithCache( servlet(), payload( "site-id" ), null );
        assertEquals( null, seenCache.get() );
    }

    @Test
    void foreignOrMissingWebsiteIdAndMalformedJsonAre400() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        assertEquals( 400, post( s, "/api/send", payload( "other-site" ) ).status );
        assertEquals( 400, post( s, "/api/send", "{\"type\":\"event\",\"payload\":{}}".getBytes( StandardCharsets.UTF_8 ) ).status );
        assertEquals( 400, post( s, "/api/send", "not json{".getBytes( StandardCharsets.UTF_8 ) ).status );
        assertEquals( 400, post( s, "/api/send", "[1]".getBytes( StandardCharsets.UTF_8 ) ).status );
        assertEquals( 0, sendHits.get() );
    }

    @Test
    void blankScriptNameMeansDefault() {
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( "wikantik.analytics.script.name", "   " );
        assertEquals( "script.js", AnalyticsProxyServlet.parseConfig( p ).scriptName() );
    }

    @Test
    void onlyTwoPathsExist() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        assertEquals( 404, get( s, "/api/send" ).status );
        assertEquals( 404, get( s, "/other.js" ).status );
        assertEquals( 404, get( s, "/../x" ).status );
        assertEquals( 404, get( s, null ).status );
        assertEquals( 404, post( s, "/script.js", new byte[ 0 ] ).status );
        assertEquals( 0, scriptHits.get() + sendHits.get() );
    }

    @Test
    void oversizeBodyRejected() throws Exception {
        final Resp r = post( servlet(), "/api/send", new byte[ 64 * 1024 + 1 ] );
        assertEquals( 413, r.status );
        assertEquals( 0, sendHits.get() );
    }

    @Test
    void disabledIs404() throws Exception {
        final AnalyticsProxyServlet s = servlet( "", Duration.ofSeconds( 1 ), 16, Clock.systemUTC() );
        assertEquals( 404, get( s, "/script.js" ).status );
        assertEquals( 404, post( s, "/api/send", new byte[ 2 ] ).status );
        assertFalse( new AnalyticsProxyServlet.AnalyticsProxyConfig( url(), "script.js", Duration.ofSeconds( 1 ), 1, " " ).enabled() );
        assertTrue( new AnalyticsProxyServlet.AnalyticsProxyConfig( url(), "script.js", Duration.ofSeconds( 1 ), 1, "x" ).enabled() );
    }

    @Test
    void slowUpstreamFailsFast() throws Exception {
        sendDelayMs = 30_000;
        final long t0 = System.nanoTime();
        final Resp r = post( servlet( url(), Duration.ofMillis( 200 ), 16, Clock.systemUTC() ), "/api/send", payload( "site-id" ) );
        assertEquals( 504, r.status );
        assertTrue( TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 ) < 1000 );
    }

    @Test
    void inflightCapShedsWith503() throws Exception {
        sendDelayMs = 3_000;
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 10 ), 1, Clock.systemUTC() );
        final Thread stuck = new Thread( () -> {
            try {
                post( s, "/api/send", payload( "site-id" ) );
            } catch( final Exception e ) {
                throw new IllegalStateException( e );
            }
        } );
        stuck.start();
        assertTrue( sendArrived.await( 5, TimeUnit.SECONDS ) );
        final long t0 = System.nanoTime();
        final Resp r = post( s, "/api/send", payload( "site-id" ) );
        assertEquals( 503, r.status );
        assertTrue( TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 ) < 500 );
        stuck.join( 10_000 );
    }

    /** Settable clock. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse( "2026-11-24T00:00:00Z" );
        void advance( final Duration d ) { now = now.plus( d ); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone( final java.time.ZoneId z ) { return this; }
        @Override public Instant instant() { return now; }
    }

    /**
     * Umami (a Node server) drops the connection when a request asks to upgrade to HTTP/2 (h2c), which the JDK
     * client does by default on plain http; prod saw "header parser received no bytes" on every script fetch.
     */
    @Test
    void productionClientSpeaksPlainHttp11SoAnUpgradeRefusingUpstreamAnswers() throws Exception {
        try( java.net.ServerSocket server = new java.net.ServerSocket( 0, 5, java.net.InetAddress.getLoopbackAddress() ) ) {
            final Thread t = new Thread( () -> {
                try( java.net.Socket s = server.accept() ) {
                    final java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader( s.getInputStream(), StandardCharsets.US_ASCII ) );
                    boolean upgrade = false;
                    for( String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine() ) {
                        upgrade |= line.toLowerCase( java.util.Locale.ROOT ).startsWith( "upgrade:" );
                    }
                    if( !upgrade ) {
                        s.getOutputStream().write( "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                .getBytes( StandardCharsets.US_ASCII ) );
                    }
                } catch( final IOException e ) {
                    // the test's assertion reports the outcome
                }
            } );
            t.start();
            final HttpClient client = AnalyticsProxyServlet.newUpstreamClient( Duration.ofSeconds( 2 ) );
            final java.net.http.HttpResponse< String > r = client.send(
                    java.net.http.HttpRequest.newBuilder( java.net.URI.create(
                            "http://127.0.0.1:" + server.getLocalPort() + "/script.js" ) )
                            .timeout( Duration.ofSeconds( 2 ) ).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString() );
            assertEquals( 200, r.statusCode() );
            assertEquals( "ok", r.body() );
            t.join( 2000 );
        }
    }

    // ---- mutation-driven additions ----

    /** A POST body that is a valid beacon for "site-id", padded with trailing blanks to exactly {@code size} bytes. */
    private static byte[] payloadOfSize( final int size ) {
        final byte[] base = payload( "site-id" );
        final byte[] out = new byte[ size ];
        java.util.Arrays.fill( out, ( byte ) ' ' );
        System.arraycopy( base, 0, out, 0, base.length );
        return out;
    }

    private Resp postWithDeclaredLength( final AnalyticsProxyServlet s, final byte[] body, final int declaredLength )
            throws Exception {
        final HttpServletRequest req = request( "/api/send", body );
        Mockito.doReturn( declaredLength ).when( req ).getContentLength();
        final Resp r = new Resp();
        s.doPost( req, r.mock );
        return r;
    }

    @Test
    void scriptIsServedFromCacheUntilExactlyOneHourOld() throws Exception {
        final MutableClock clock = new MutableClock();
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 2 ), 16, clock );
        get( s, "/script.js" );
        clock.advance( Duration.ofHours( 1 ).minusMillis( 1 ) );
        get( s, "/script.js" );
        assertEquals( 1, scriptHits.get(), "one millisecond short of the TTL is still fresh" );
        clock.advance( Duration.ofMillis( 1 ) );
        get( s, "/script.js" );
        assertEquals( 2, scriptHits.get(), "at exactly the TTL the script is refetched" );
    }

    @Test
    void failedRefreshIsRetriedAtExactlyTheEndOfTheBackoff() throws Exception {
        final MutableClock clock = new MutableClock();
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 2 ), 16, clock );
        get( s, "/script.js" );
        scriptFails = true;
        clock.advance( Duration.ofHours( 2 ) );
        get( s, "/script.js" );
        assertEquals( 2, scriptHits.get() );
        clock.advance( Duration.ofMillis( 59_999 ) );
        get( s, "/script.js" );
        assertEquals( 2, scriptHits.get(), "still inside the backoff" );
        clock.advance( Duration.ofMillis( 1 ) );
        get( s, "/script.js" );
        assertEquals( 3, scriptHits.get(), "backoff over at exactly 60s" );
    }

    @Test
    void successfulScriptResponseExplicitlySetsStatus200() throws Exception {
        final Resp r = new Resp();
        servlet().doGet( request( "/script.js", null ), r.mock );
        Mockito.verify( r.mock ).setStatus( HttpServletResponse.SC_OK );
        Mockito.verify( r.mock, Mockito.never() ).setStatus( Mockito.intThat( c -> c != 200 ) );
    }

    @Test
    void scriptFetchIsShedWhenTheInflightCapIsHeld() throws Exception {
        scriptDelayMs = 2_000;
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 10 ), 1, Clock.systemUTC() );
        final Thread stuck = new Thread( () -> {
            try {
                get( s, "/script.js" );
            } catch( final Exception e ) {
                throw new IllegalStateException( e );
            }
        } );
        stuck.start();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
        while( scriptHits.get() == 0 && System.nanoTime() < deadline ) {
            Thread.sleep( 10 );
        }
        assertEquals( 1, scriptHits.get() );
        final Resp shed = get( s, "/script.js" );
        assertEquals( 502, shed.status, "nothing cached and the only slot is busy" );
        assertEquals( 1, scriptHits.get(), "the shed request must not reach the upstream" );
        stuck.join( 10_000 );
    }

    @Test
    void inflightSlotIsReleasedAfterEachRequest() throws Exception {
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 5 ), 1, Clock.systemUTC() );
        for( int i = 0; i < 3; i++ ) {
            assertEquals( 202, post( s, "/api/send", payload( "site-id" ) ).status, "post " + i );
        }
        upstream.stop( 0 );
        assertEquals( 502, post( s, "/api/send", payload( "site-id" ) ).status );
        assertEquals( 502, post( s, "/api/send", payload( "site-id" ) ).status,
                "a failed relay must also release its slot (else 503)" );
    }

    @Test
    void scriptFetchFailureReleasesItsInflightSlot() throws Exception {
        final MutableClock clock = new MutableClock();
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 2 ), 1, clock );
        scriptFails = true;
        assertEquals( 502, get( s, "/script.js" ).status );
        scriptFails = false;
        clock.advance( Duration.ofSeconds( 61 ) );
        assertEquals( 200, get( s, "/script.js" ).status, "the slot from the failed fetch must be free again" );
    }

    @Test
    void relayedSendCarriesTheUpstreamContentType() throws Exception {
        final Resp r = post( servlet(), "/api/send", payload( "site-id" ) );
        assertEquals( "application/json", r.headers.get( "Content-Type" ) );
    }

    @Test
    void unreachableUpstreamOnSendIs502() throws Exception {
        upstream.stop( 0 );
        final Resp r = post( servlet( url(), Duration.ofSeconds( 1 ), 16, Clock.systemUTC() ), "/api/send", payload( "site-id" ) );
        assertEquals( 502, r.status );
        assertEquals( "", r.body() );
    }

    @Test
    void interruptedSendIs502AndKeepsTheInterruptFlag() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        Thread.currentThread().interrupt();
        try {
            final Resp r = post( s, "/api/send", payload( "site-id" ) );
            assertEquals( 502, r.status );
            assertTrue( Thread.currentThread().isInterrupted(), "the interrupt must be restored for the container" );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedScriptFetchIsReportedUpstreamFailureAndKeepsTheInterruptFlag() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        Thread.currentThread().interrupt();
        try {
            assertEquals( 502, get( s, "/script.js" ).status );
            assertTrue( Thread.currentThread().isInterrupted() );
        } finally {
            Thread.interrupted();
        }
        assertEquals( 0, scriptHits.get() );
    }

    @Test
    void bodyOfExactlyTheLimitIsAcceptedAndOneByteMoreIsRefused() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        assertEquals( 202, post( s, "/api/send", payloadOfSize( 64 * 1024 ) ).status );
        assertEquals( 413, post( s, "/api/send", payloadOfSize( 64 * 1024 + 1 ) ).status );
        assertEquals( 1, sendHits.get() );
    }

    @Test
    void declaredContentLengthOverTheLimitIsRefusedWithoutReadingTheBody() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        assertEquals( 413, postWithDeclaredLength( s, payload( "site-id" ), 64 * 1024 + 1 ).status );
        assertEquals( 0, sendHits.get() );
        assertEquals( 202, postWithDeclaredLength( s, payload( "site-id" ), 64 * 1024 ).status,
                "a declared length equal to the limit is allowed" );
    }

    @Test
    void chunkedBodyOverTheLimitIsRefusedEvenWithoutADeclaredLength() throws Exception {
        final AnalyticsProxyServlet s = servlet();
        assertEquals( 413, postWithDeclaredLength( s, payloadOfSize( 64 * 1024 + 1 ), -1 ).status );
        assertEquals( 202, postWithDeclaredLength( s, payloadOfSize( 64 * 1024 ), -1 ).status );
        assertEquals( 1, sendHits.get() );
    }

    @Test
    void upstreamWithATrailingSlashDoesNotProduceADoubleSlashPath() throws Exception {
        final AnalyticsProxyServlet s = servlet( url() + "/", Duration.ofSeconds( 2 ), 16, Clock.systemUTC() );
        assertEquals( 200, get( s, "/script.js" ).status );
        assertEquals( 202, post( s, "/api/send", payload( "site-id" ) ).status );
        assertEquals( 1, scriptHits.get() );
        assertEquals( 1, sendHits.get() );
    }

    @Test
    void containerConstructorReadsTheSettingsFromTheEngineOnceAndRelaysTheScript() throws Exception {
        final java.util.Properties props = new java.util.Properties();
        props.setProperty( "wikantik.analytics.upstream", url() );
        props.setProperty( "wikantik.analytics.website.id", "site-id" );
        final com.wikantik.api.core.Engine engine = Mockito.mock( com.wikantik.api.core.Engine.class );
        Mockito.doReturn( props ).when( engine ).getWikiProperties();
        final com.wikantik.api.spi.EngineSPI engineSpi = Mockito.mock( com.wikantik.api.spi.EngineSPI.class );
        Mockito.doReturn( engine ).when( engineSpi ).find( Mockito.nullable( jakarta.servlet.ServletContext.class ), Mockito.any() );
        final jakarta.servlet.ServletConfig config = Mockito.mock( jakarta.servlet.ServletConfig.class );
        Mockito.doReturn( Mockito.mock( jakarta.servlet.ServletContext.class ) ).when( config ).getServletContext();
        try( final org.mockito.MockedStatic< com.wikantik.api.spi.Wiki > wiki =
                     Mockito.mockStatic( com.wikantik.api.spi.Wiki.class, Mockito.CALLS_REAL_METHODS ) ) {
            wiki.when( com.wikantik.api.spi.Wiki::engine ).thenReturn( engineSpi );
            final AnalyticsProxyServlet s = new AnalyticsProxyServlet();
            s.init( config );
            final Resp a = new Resp();
            s.doGet( request( "/script.js", null ), a.mock );
            final Resp b = new Resp();
            s.doGet( request( "/script.js", null ), b.mock );
            assertEquals( SCRIPT, a.body() );
            assertEquals( SCRIPT, b.body() );
            assertEquals( 202, post( s, "/api/send", payload( "site-id" ) ).status );
            Mockito.verify( engine, Mockito.times( 1 ) ).getWikiProperties();
        }
    }
}
