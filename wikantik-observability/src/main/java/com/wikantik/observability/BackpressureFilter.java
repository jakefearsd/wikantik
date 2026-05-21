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
package com.wikantik.observability;

import com.wikantik.api.observability.MeterRegistryHolder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency-bounded admission control. Caps the number of concurrent
 * in-flight HTTP requests at {@code maxInflight} permits; requests over the
 * cap get an immediate {@code 503 Service Unavailable} with {@code Retry-After: 1}
 * instead of queueing for an unbounded time.
 *
 * <p><strong>Why a fixed semaphore over latency-adaptive or CPU-threshold
 * gating:</strong> a fixed permit count is the only approach where the
 * rejection decision uses purely local state (the in-flight counter) and is
 * therefore deterministic — no false alarms from GC pauses, no flapping from
 * transient latency spikes. The tuning knob is the permit count itself: set
 * it at or above the observed-comfortable steady-state level and the system
 * never rejects under normal load. Under burst overload, the served subset
 * keeps its measured throughput while the excess gets 503'd in microseconds
 * — strictly better than the pre-filter behaviour where requests queue for
 * up to 60 s before timing out.</p>
 *
 * <p><strong>Configuration:</strong> permit count comes from the
 * {@code WIKANTIK_MAX_INFLIGHT_REQUESTS} env var (default 700, picked one
 * notch above docker1's measured-comfortable N=650 sustained ceiling).
 * Setting it to {@code 0} or negative disables the filter (no-op pass-through).</p>
 *
 * <p><strong>Exemptions:</strong> health checks ({@code /api/health}) and the
 * Prometheus scrape endpoint ({@code /metrics}) bypass the semaphore entirely.
 * A 503 on those would make jakemon think the container is down and add to
 * the very alarm fatigue we're trying to avoid.</p>
 *
 * <p><strong>Published metrics:</strong></p>
 * <ul>
 *   <li>{@code wikantik_backpressure.rejected_total} — counter, total 503s issued by this filter</li>
 *   <li>{@code wikantik_backpressure.inflight} — gauge, requests currently holding a permit</li>
 *   <li>{@code wikantik_backpressure.permits_max} — gauge, configured permit count (for context)</li>
 * </ul>
 *
 * <p>Filter ordering: this should run after request-correlation logging
 * (so a rejected request still gets a request-id in the access log) but
 * before any expensive filters (CSRF token verification, content rewriting,
 * etc.) — there's no point spending CPU on a request we're about to reject.</p>
 */
public class BackpressureFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( BackpressureFilter.class );

    /** Env var carrying the permit cap. Read once at init. */
    public static final String ENV_MAX_INFLIGHT = "WIKANTIK_MAX_INFLIGHT_REQUESTS";

    /** Default permit cap when the env var is unset / blank / unparsable. */
    public static final int DEFAULT_MAX_INFLIGHT = 700;

    /**
     * Path prefixes that bypass the semaphore. Kept tiny — adding routes here
     * means they're un-protected against overload, so reserve for paths that
     * MUST always succeed (health, metrics).
     */
    private static final Set< String > EXEMPT_PREFIXES = Set.of(
        "/api/health",
        "/metrics"
    );

    /**
     * Whole-path exact matches that bypass. Kept separate from the prefix
     * set so we don't accidentally exempt anything that just starts with one
     * of these strings.
     */
    private static final Set< String > EXEMPT_EXACT = Set.of(
        "/favicon.ico"
    );

    private Semaphore semaphore;
    private int maxInflight;
    private final AtomicInteger inflight = new AtomicInteger( 0 );
    private Counter rejectedCounter;

    @Override
    public void init( final FilterConfig config ) {
        this.maxInflight = resolveMaxInflight();
        if ( this.maxInflight <= 0 ) {
            LOG.info( "BackpressureFilter DISABLED ({}={} → semaphore not installed)",
                ENV_MAX_INFLIGHT, this.maxInflight );
            this.semaphore = null;
            return;
        }
        this.semaphore = new Semaphore( this.maxInflight, /*fair*/ false );

        final MeterRegistry reg = MeterRegistryHolder.get();
        if ( reg != null ) {
            rejectedCounter = Counter.builder( "wikantik_backpressure.rejected_total" )
                .description( "HTTP requests rejected by the in-flight-concurrency cap (503)" )
                .register( reg );
            Gauge.builder( "wikantik_backpressure.inflight", inflight, AtomicInteger::get )
                .description( "Requests currently holding a backpressure permit" )
                .register( reg );
            Gauge.builder( "wikantik_backpressure.permits_max", this, f -> (double) f.maxInflight )
                .description( "Configured backpressure permit ceiling" )
                .register( reg );
            LOG.info( "BackpressureFilter initialised with max_inflight={} (metrics registered)",
                this.maxInflight );
        } else {
            LOG.warn( "BackpressureFilter initialised with max_inflight={} but no MeterRegistry "
                + "is available — semaphore active, metrics will NOT publish", this.maxInflight );
        }
    }

    @Override
    public void doFilter( final ServletRequest request,
                           final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        // Fast path for disabled filter, non-HTTP requests, and exempt paths.
        if ( semaphore == null
                || !( request instanceof HttpServletRequest httpReq )
                || !( response instanceof HttpServletResponse httpRes ) ) {
            chain.doFilter( request, response );
            return;
        }
        final String path = httpReq.getRequestURI();
        if ( isExempt( path ) ) {
            chain.doFilter( request, response );
            return;
        }

        if ( semaphore.tryAcquire() ) {
            inflight.incrementAndGet();
            try {
                chain.doFilter( request, response );
            } finally {
                inflight.decrementAndGet();
                semaphore.release();
            }
        } else {
            // Permit unavailable: 503 with Retry-After, no logging (would spam
            // under saturation — the counter is the visibility channel).
            if ( rejectedCounter != null ) {
                rejectedCounter.increment();
            }
            sendUnavailable( httpRes );
        }
    }

    /** Exempt-path predicate. Cheap string ops; checked on every request. */
    static boolean isExempt( final String path ) {
        if ( path == null ) return false;
        if ( EXEMPT_EXACT.contains( path ) ) return true;
        for ( final String pfx : EXEMPT_PREFIXES ) {
            if ( path.startsWith( pfx ) ) return true;
        }
        return false;
    }

    private static void sendUnavailable( final HttpServletResponse res ) throws IOException {
        res.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        res.setHeader( "Retry-After", "1" );
        res.setContentType( "application/json;charset=UTF-8" );
        final byte[] body =
            "{\"error\":\"service_unavailable\",\"message\":\"Server at capacity; please retry in 1 second\"}"
                .getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        res.setContentLength( body.length );
        res.getOutputStream().write( body );
    }

    /**
     * Resolve the permit count from the environment, with default fallback.
     * Package-private for unit tests that override via a system property.
     */
    int resolveMaxInflight() {
        // System property wins for tests + ad-hoc tuning; env var is the
        // operator surface in production.
        final String sys = System.getProperty( ENV_MAX_INFLIGHT );
        if ( sys != null && !sys.isBlank() ) {
            return parseIntOrDefault( sys );
        }
        final String env = System.getenv( ENV_MAX_INFLIGHT );
        if ( env != null && !env.isBlank() ) {
            return parseIntOrDefault( env );
        }
        return DEFAULT_MAX_INFLIGHT;
    }

    private int parseIntOrDefault( final String raw ) {
        try {
            return Integer.parseInt( raw.trim() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "{} value '{}' is not an integer; using default {}",
                ENV_MAX_INFLIGHT, raw, DEFAULT_MAX_INFLIGHT );
            return DEFAULT_MAX_INFLIGHT;
        }
    }

    @Override
    public void destroy() {
        // No background threads or pooled resources; nothing to release.
        // Semaphore + metrics are GC'd with the filter instance.
    }

    /** Test-only introspection. */
    int currentInflight() { return inflight.get(); }
}
