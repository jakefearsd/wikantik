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

import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Same-origin relay to the Umami server: the browser loads the tracker from {@code /analytics/<script name>} and
 * posts beacons to {@code /analytics/api/send}; exactly those two paths are forwarded, nothing else.
 *
 * <p>The proxy never lets an analytics outage hurt a reader: every upstream call has a timeout, concurrency is
 * capped (excess requests are shed with 503), and the script is served stale when a refresh fails. The client
 * address forwarded upstream is {@code request.getRemoteAddr()} (already resolved by the container's remote-IP
 * valve) — an incoming {@code X-Forwarded-For} is never trusted.</p>
 */
public class AnalyticsProxyServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AnalyticsProxyServlet.class );

    static final String PROP_WEBSITE_ID = "wikantik.analytics.website.id";
    static final String PROP_UPSTREAM = "wikantik.analytics.upstream";
    static final String PROP_SCRIPT_NAME = "wikantik.analytics.script.name";
    static final String PROP_TIMEOUT_MS = "wikantik.analytics.proxy.timeout.ms";
    static final String PROP_MAX_INFLIGHT = "wikantik.analytics.proxy.max.inflight";

    private static final String SEND_PATH = "/api/send";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Duration SCRIPT_TTL = Duration.ofHours( 1 );
    private static final long WARN_INTERVAL_MS = 60_000L;

    /**
     * Proxy settings. {@code enabled()} needs both the upstream and the website id, so a half-configured
     * deployment serves nothing.
     */
    public record AnalyticsProxyConfig( String upstream, String scriptName, Duration timeout, int maxInflight,
                                        String websiteId ) {
        public boolean enabled() {
            return upstream != null && !upstream.isBlank() && websiteId != null && !websiteId.isBlank();
        }
    }

    private record CachedScript( byte[] body, Instant fetchedAt ) { }

    private final Supplier< AnalyticsProxyConfig > configSupplier;
    private final Clock clock;
    private final AtomicReference< CachedScript > script = new AtomicReference<>();
    private final AtomicLong lastWarnMs = new AtomicLong( Long.MIN_VALUE );
    private volatile HttpClient client;
    private volatile Semaphore inflight;

    /** Container entry point: settings come from the engine properties. */
    public AnalyticsProxyServlet() {
        this.client = null;
        this.configSupplier = this::configFromEngine;
        this.clock = Clock.systemUTC();
    }

    /** Test seam. */
    public AnalyticsProxyServlet( final HttpClient client, final Supplier< AnalyticsProxyConfig > config ) {
        this( client, config, Clock.systemUTC() );
    }

    AnalyticsProxyServlet( final HttpClient client, final Supplier< AnalyticsProxyConfig > config, final Clock clock ) {
        this.client = client;
        this.configSupplier = config;
        this.clock = clock;
    }

    private AnalyticsProxyConfig configFromEngine() {
        final Engine engine = Wiki.engine().find( getServletContext(), null );
        final Properties p = engine.getWikiProperties();
        return new AnalyticsProxyConfig(
                p.getProperty( PROP_UPSTREAM, "" ).trim(),
                p.getProperty( PROP_SCRIPT_NAME, "script.js" ).trim(),
                Duration.ofMillis( intProp( p, PROP_TIMEOUT_MS, 5000 ) ),
                intProp( p, PROP_MAX_INFLIGHT, 16 ),
                p.getProperty( PROP_WEBSITE_ID, "" ).trim() );
    }

    private static int intProp( final Properties p, final String key, final int dflt ) {
        final String v = p.getProperty( key );
        if( v == null || v.isBlank() ) {
            return dflt;
        }
        try {
            return Integer.parseInt( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.warn( "Ignoring non-numeric value '{}' for {}; using {}", v, key, dflt, e );
            return dflt;
        }
    }

    private HttpClient client( final AnalyticsProxyConfig cfg ) {
        HttpClient c = client;
        if( c == null ) {
            synchronized( this ) {
                c = client;
                if( c == null ) {
                    c = HttpClient.newBuilder().connectTimeout( cfg.timeout() )
                            .followRedirects( HttpClient.Redirect.NEVER ).build();
                    client = c;
                }
            }
        }
        return c;
    }

    private Semaphore inflight( final AnalyticsProxyConfig cfg ) {
        Semaphore s = inflight;
        if( s == null ) {
            synchronized( this ) {
                s = inflight;
                if( s == null ) {
                    s = new Semaphore( Math.max( 1, cfg.maxInflight() ) );
                    inflight = s;
                }
            }
        }
        return s;
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final AnalyticsProxyConfig cfg = configSupplier.get();
        final String path = req.getPathInfo();
        if( !cfg.enabled() || path == null || !path.equals( "/" + cfg.scriptName() ) ) {
            resp.setStatus( HttpServletResponse.SC_NOT_FOUND );
            return;
        }
        CachedScript cached = script.get();
        final Instant now = clock.instant();
        if( cached == null || Duration.between( cached.fetchedAt(), now ).compareTo( SCRIPT_TTL ) >= 0 ) {
            final CachedScript fetched = fetchScript( cfg, path );
            if( fetched != null ) {
                script.set( fetched );
                cached = fetched;
            }
        }
        if( cached == null ) {
            resp.setStatus( HttpServletResponse.SC_BAD_GATEWAY );
            return;
        }
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "application/javascript" );
        resp.setHeader( "Cache-Control", "public, max-age=3600" );
        resp.getOutputStream().write( cached.body() );
    }

    /** Fetches the script; returns {@code null} on any failure (logged, rate-limited) so the caller can serve stale. */
    private CachedScript fetchScript( final AnalyticsProxyConfig cfg, final String path ) {
        final Semaphore gate = inflight( cfg );
        if( !gate.tryAcquire() ) {
            warnLimited( path, "in-flight cap reached" );
            return null;
        }
        try {
            final HttpRequest request = HttpRequest.newBuilder( upstreamUri( cfg, path ) )
                    .timeout( cfg.timeout() ).GET().build();
            final HttpResponse< byte[] > r = client( cfg ).send( request, HttpResponse.BodyHandlers.ofByteArray() );
            if( r.statusCode() != 200 ) {
                warnLimited( path, "upstream answered " + r.statusCode() );
                return null;
            }
            return new CachedScript( r.body(), clock.instant() );
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            warnLimited( path, e.toString() );
            return null;
        } catch( final IOException | RuntimeException e ) {
            warnLimited( path, e.toString() );
            return null;
        } finally {
            gate.release();
        }
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final AnalyticsProxyConfig cfg = configSupplier.get();
        if( !cfg.enabled() || !SEND_PATH.equals( req.getPathInfo() ) ) {
            resp.setStatus( HttpServletResponse.SC_NOT_FOUND );
            return;
        }
        if( req.getContentLength() > MAX_BODY_BYTES ) {
            resp.setStatus( HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE );
            return;
        }
        final byte[] body;
        try( InputStream in = req.getInputStream() ) {
            body = in.readNBytes( MAX_BODY_BYTES + 1 );
        }
        if( body.length > MAX_BODY_BYTES ) {
            resp.setStatus( HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE );
            return;
        }
        final Semaphore gate = inflight( cfg );
        if( !gate.tryAcquire() ) {
            warnLimited( SEND_PATH, "in-flight cap reached" );
            resp.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            return;
        }
        try {
            final String contentType = req.getContentType();
            final HttpRequest.Builder builder = HttpRequest.newBuilder( upstreamUri( cfg, SEND_PATH ) )
                    .timeout( cfg.timeout() )
                    .header( "Content-Type", contentType != null ? contentType : "application/json" )
                    .header( "X-Forwarded-For", req.getRemoteAddr() )
                    .POST( HttpRequest.BodyPublishers.ofByteArray( body ) );
            final String userAgent = req.getHeader( "User-Agent" );
            if( userAgent != null ) {
                builder.header( "User-Agent", userAgent );
            }
            final HttpResponse< byte[] > r = client( cfg ).send( builder.build(), HttpResponse.BodyHandlers.ofByteArray() );
            resp.setStatus( r.statusCode() );
            r.headers().firstValue( "Content-Type" ).ifPresent( resp::setContentType );
            resp.getOutputStream().write( r.body() );
        } catch( final HttpTimeoutException e ) {
            warnLimited( SEND_PATH, e.toString() );
            resp.setStatus( HttpServletResponse.SC_GATEWAY_TIMEOUT );
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            warnLimited( SEND_PATH, e.toString() );
            resp.setStatus( HttpServletResponse.SC_BAD_GATEWAY );
        } catch( final IOException e ) {
            warnLimited( SEND_PATH, e.toString() );
            resp.setStatus( HttpServletResponse.SC_BAD_GATEWAY );
        } finally {
            gate.release();
        }
    }

    private static URI upstreamUri( final AnalyticsProxyConfig cfg, final String path ) {
        final String base = cfg.upstream().endsWith( "/" )
                ? cfg.upstream().substring( 0, cfg.upstream().length() - 1 ) : cfg.upstream();
        return URI.create( base + path );
    }

    /** At most one warning per minute for upstream failures, however many requests fail. */
    private void warnLimited( final String path, final String cause ) {
        final long now = clock.millis();
        final long last = lastWarnMs.get();
        if( ( last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS ) && lastWarnMs.compareAndSet( last, now ) ) {
            LOG.warn( "Analytics proxy upstream failure for {}: {} (further failures suppressed for 60s)", path, cause );
        }
    }
}
