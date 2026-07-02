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

import com.github.benmanes.caffeine.cache.Ticker;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.http.ratelimit.SlidingWindowRateLimiter;

import io.micrometer.core.instrument.Counter;
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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-client-IP rate limiting for the public HTTP surface, in two tiers:
 *
 * <ul>
 *   <li><b>default</b> — everything mapped to this filter (/api/*, /id/*,
 *       /export/*): generous per-client limit, no global cap
 *       ({@link BackpressureFilter} already bounds aggregate load).</li>
 *   <li><b>expensive</b> — endpoints that do per-request embedding inference
 *       or heavy query work (/api/bundle, /api/search, /sparql): a small
 *       per-client limit plus a global cap that protects the single-host
 *       inference tier from unauthenticated compute amplification.</li>
 * </ul>
 *
 * <p>Client identity is {@code getRemoteAddr()}, which is the real client IP
 * behind Cloudflare thanks to Tomcat's {@code RemoteIpValve}
 * ({@code remoteIpHeader="CF-Connecting-IP"}). Loopback callers are always
 * exempt — local ops and the eval harness run full-speed — plus an optional
 * IPv4 CIDR allowlist; the exact path {@code /api/health} is skipped so
 * monitoring can never be limited. This is complementary to
 * {@link BackpressureFilter}: that filter is overload protection (concurrency),
 * this one is per-client fairness (rate).</p>
 *
 * <p><strong>Configuration</strong> (env vars, read once at init; a limit of
 * {@code 0} disables that bucket, all-zero disables the filter):</p>
 * <ul>
 *   <li>{@code WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT} — default 25 req/s</li>
 *   <li>{@code WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT} — default 3 req/s</li>
 *   <li>{@code WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL} — default 10 req/s</li>
 *   <li>{@code WIKANTIK_RATELIMIT_EXPENSIVE_PATHS} — CSV path prefixes,
 *       default {@code /api/bundle,/api/search,/sparql}</li>
 *   <li>{@code WIKANTIK_RATELIMIT_EXEMPT_CIDRS} — CSV IPv4 CIDRs, default empty
 *       (loopback is always exempt regardless)</li>
 * </ul>
 *
 * <p>On limit: {@code 429} + {@code Retry-After: 1}, a SecurityLog line, and a
 * {@code wikantik_ratelimit.rejected_total{tier=...}} counter.</p>
 *
 * <p>Filter ordering: after {@code RequestMetricsFilter} (429s get measured),
 * before the auth filters (reject cheaply before any auth/DB work).</p>
 */
public class RateLimitFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( RateLimitFilter.class );
    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );

    public static final String ENV_DEFAULT_PERCLIENT   = "WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT";
    public static final String ENV_EXPENSIVE_PERCLIENT = "WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT";
    public static final String ENV_EXPENSIVE_GLOBAL    = "WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL";
    public static final String ENV_EXPENSIVE_PATHS     = "WIKANTIK_RATELIMIT_EXPENSIVE_PATHS";
    public static final String ENV_EXEMPT_CIDRS        = "WIKANTIK_RATELIMIT_EXEMPT_CIDRS";

    public static final int DEFAULT_DEFAULT_PERCLIENT   = 25;
    public static final int DEFAULT_EXPENSIVE_PERCLIENT = 3;
    public static final int DEFAULT_EXPENSIVE_GLOBAL    = 10;
    public static final String DEFAULT_EXPENSIVE_PATHS  = "/api/bundle,/api/search,/sparql";

    /** Exact paths that must never be limited (monitoring probes). */
    private static final String EXEMPT_EXACT_PATH = "/api/health";

    /**
     * Resolved filter configuration. Package-private test seam: tests build one
     * directly (with a fake {@link Ticker}); the container path resolves it from
     * the environment in {@link #init}.
     */
    record Config( int defaultPerClient, int expensivePerClient, int expensiveGlobal,
                   List< String > expensivePathPrefixes,
                   List< InternalNetworkFilter.CidrRange > exemptRanges,
                   Ticker ticker ) {

        static Config of( final int defaultPerClient, final int expensivePerClient, final int expensiveGlobal,
                          final List< String > expensivePathPrefixes, final List< String > exemptCidrs,
                          final Ticker ticker ) {
            return new Config( defaultPerClient, expensivePerClient, expensiveGlobal,
                    List.copyOf( expensivePathPrefixes ), parseCidrs( exemptCidrs ), ticker );
        }

        static Config fromEnvironment() {
            final List< String > paths = Arrays.stream(
                    envOr( ENV_EXPENSIVE_PATHS, DEFAULT_EXPENSIVE_PATHS ).split( "," ) )
                    .map( String::trim ).filter( s -> !s.isEmpty() ).toList();
            final List< String > cidrs = Arrays.stream(
                    envOr( ENV_EXEMPT_CIDRS, "" ).split( "," ) )
                    .map( String::trim ).filter( s -> !s.isEmpty() ).toList();
            return new Config(
                    envIntOr( ENV_DEFAULT_PERCLIENT, DEFAULT_DEFAULT_PERCLIENT ),
                    envIntOr( ENV_EXPENSIVE_PERCLIENT, DEFAULT_EXPENSIVE_PERCLIENT ),
                    envIntOr( ENV_EXPENSIVE_GLOBAL, DEFAULT_EXPENSIVE_GLOBAL ),
                    paths, parseCidrs( cidrs ), Ticker.systemTicker() );
        }

        private static List< InternalNetworkFilter.CidrRange > parseCidrs( final List< String > cidrs ) {
            final List< InternalNetworkFilter.CidrRange > ranges = new ArrayList<>();
            for ( final String cidr : cidrs ) {
                final String[] parts = cidr.split( "/" );
                try {
                    ranges.add( new InternalNetworkFilter.CidrRange(
                            parts[ 0 ], parts.length > 1 ? Integer.parseInt( parts[ 1 ] ) : 32 ) );
                } catch ( final IllegalArgumentException e ) {
                    LOG.warn( "Ignoring malformed exempt CIDR '{}': {}", cidr, e.getMessage() );
                }
            }
            return List.copyOf( ranges );
        }

        private static String envOr( final String name, final String fallback ) {
            final String v = System.getenv( name );
            return v == null || v.isBlank() ? fallback : v.trim();
        }

        private static int envIntOr( final String name, final int fallback ) {
            final String v = System.getenv( name );
            if ( v == null || v.isBlank() ) return fallback;
            try {
                return Integer.parseInt( v.trim() );
            } catch ( final NumberFormatException e ) {
                LOG.warn( "Unparsable {}='{}' — using default {}", name, v, fallback );
                return fallback;
            }
        }
    }

    private Config config;
    private boolean disabled;
    private SlidingWindowRateLimiter defaultLimiter;
    private SlidingWindowRateLimiter expensiveLimiter;
    private Counter rejectedDefault;
    private Counter rejectedExpensive;

    /** Container constructor — configuration resolved from the environment in {@link #init}. */
    public RateLimitFilter() {
    }

    /** Test seam: pre-resolved configuration, {@link #init} skips the environment. */
    RateLimitFilter( final Config config ) {
        this.config = config;
    }

    @Override
    public void init( final FilterConfig filterConfig ) {
        if ( config == null ) {
            config = Config.fromEnvironment();
        }
        disabled = config.defaultPerClient() <= 0 && config.expensivePerClient() <= 0
                && config.expensiveGlobal() <= 0;
        if ( disabled ) {
            LOG.info( "RateLimitFilter DISABLED (all limits <= 0)" );
            return;
        }

        // default tier: per-client only — BackpressureFilter bounds the aggregate.
        defaultLimiter = new SlidingWindowRateLimiter( 0, config.defaultPerClient(), 10000, config.ticker() );
        expensiveLimiter = new SlidingWindowRateLimiter(
                config.expensiveGlobal(), config.expensivePerClient(), 10000, config.ticker() );

        final MeterRegistry reg = MeterRegistryHolder.get();
        if ( reg != null ) {
            rejectedDefault = Counter.builder( "wikantik_ratelimit.rejected_total" )
                    .description( "HTTP requests rejected by the per-client rate limiter (429)" )
                    .tag( "tier", "default" ).register( reg );
            rejectedExpensive = Counter.builder( "wikantik_ratelimit.rejected_total" )
                    .description( "HTTP requests rejected by the per-client rate limiter (429)" )
                    .tag( "tier", "expensive" ).register( reg );
        }
        LOG.info( "RateLimitFilter initialised: default={}/s per client; expensive={}/s per client, "
                        + "{}/s global, paths={}; exemptCidrs={} (+loopback always){}",
                config.defaultPerClient(), config.expensivePerClient(), config.expensiveGlobal(),
                config.expensivePathPrefixes(), config.exemptRanges().size(),
                reg == null ? "; no MeterRegistry — metrics will NOT publish" : "" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        if ( disabled
                || !( request instanceof HttpServletRequest httpReq )
                || !( response instanceof HttpServletResponse httpRes ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String path = httpReq.getRequestURI();
        final String ip = httpReq.getRemoteAddr();
        if ( EXEMPT_EXACT_PATH.equals( path ) || isExemptAddress( ip ) ) {
            chain.doFilter( request, response );
            return;
        }

        final boolean expensive = isExpensivePath( path );
        final SlidingWindowRateLimiter limiter = expensive ? expensiveLimiter : defaultLimiter;
        if ( limiter.tryAcquire( "ip:" + ip ) ) {
            chain.doFilter( request, response );
            return;
        }

        SECURITY.warn( "Rate limit exceeded: tier={}, ip={}, path={}",
                expensive ? "expensive" : "default", ip, path );
        final Counter counter = expensive ? rejectedExpensive : rejectedDefault;
        if ( counter != null ) {
            counter.increment();
        }
        sendRateLimited( httpRes );
    }

    private boolean isExpensivePath( final String path ) {
        if ( path == null ) return false;
        for ( final String prefix : config.expensivePathPrefixes() ) {
            if ( path.startsWith( prefix ) ) return true;
        }
        return false;
    }

    /**
     * Loopback callers (IPv4 and IPv6) are always exempt; additional IPv4 CIDRs
     * come from configuration. Malformed/unresolvable addresses are NOT exempt.
     */
    private boolean isExemptAddress( final String ip ) {
        if ( ip == null || ip.isBlank() ) return false;
        try {
            final InetAddress addr = InetAddress.getByName( ip );
            if ( addr.isLoopbackAddress() ) return true;
            final byte[] bytes = addr.getAddress();
            if ( bytes.length == 4 ) {
                for ( final InternalNetworkFilter.CidrRange range : config.exemptRanges() ) {
                    if ( range.contains( bytes ) ) return true;
                }
            }
        } catch ( final UnknownHostException e ) {
            LOG.warn( "Rate limiter could not parse remote address '{}' — treating as non-exempt: {}",
                    ip, e.getMessage() );
        }
        return false;
    }

    private static void sendRateLimited( final HttpServletResponse res ) throws IOException {
        res.setStatus( 429 );
        res.setHeader( "Retry-After", "1" );
        res.setContentType( "application/json;charset=UTF-8" );
        final byte[] body =
                "{\"error\":\"rate_limited\",\"message\":\"Too many requests; please retry in 1 second\"}"
                        .getBytes( StandardCharsets.UTF_8 );
        res.setContentLength( body.length );
        res.getOutputStream().write( body );
    }
}
