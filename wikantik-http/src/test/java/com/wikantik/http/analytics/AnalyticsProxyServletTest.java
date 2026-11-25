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
    private volatile long sendDelayMs;
    private volatile boolean scriptFails;
    private final CountDownLatch sendArrived = new CountDownLatch( 1 );

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        upstream.setExecutor( Executors.newCachedThreadPool() );
        upstream.createContext( "/script.js", ex -> {
            scriptHits.incrementAndGet();
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
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
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
        final byte[] body = "{\"type\":\"event\",\"payload\":{}}".getBytes( StandardCharsets.UTF_8 );
        final Resp r = post( servlet(), "/api/send", body );
        assertEquals( 202, r.status );
        assertEquals( "{\"cache\":\"abc\"}", r.body() );
        assertEquals( "203.0.113.7", seenXff.get() );
        assertEquals( "Mozilla/5.0 TestBrowser", seenUserAgent.get() );
        assertEquals( new String( body, StandardCharsets.UTF_8 ), seenBody.get() );
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
        final Resp r = post( servlet( url(), Duration.ofMillis( 200 ), 16, Clock.systemUTC() ), "/api/send", new byte[ 2 ] );
        assertEquals( 504, r.status );
        assertTrue( TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 ) < 1000 );
    }

    @Test
    void inflightCapShedsWith503() throws Exception {
        sendDelayMs = 3_000;
        final AnalyticsProxyServlet s = servlet( url(), Duration.ofSeconds( 10 ), 1, Clock.systemUTC() );
        final Thread stuck = new Thread( () -> {
            try {
                post( s, "/api/send", new byte[ 2 ] );
            } catch( final Exception e ) {
                throw new IllegalStateException( e );
            }
        } );
        stuck.start();
        assertTrue( sendArrived.await( 5, TimeUnit.SECONDS ) );
        final long t0 = System.nanoTime();
        final Resp r = post( s, "/api/send", new byte[ 2 ] );
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
}
