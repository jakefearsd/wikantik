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
 * behind a reverse proxy thanks to Tomcat's {@code RemoteIpValve}
 * (header named by {@code PROXY_REMOTE_IP_HEADER}, default {@code CF-Connecting-IP}). Loopback callers are always
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
 *   <li>{@code WIKANTIK_RATELIMIT_SESSION_PATHS} — CSV path prefixes of
 *       session-limited endpoints, default {@code /api/collab/}; each prefix is
 *       normalised to end in {@code /}. A variable that is <em>set but blank</em>
 *       disables session paths entirely (every request is classified as default or
 *       expensive); an unset variable means the default.</li>
 *   <li>{@code WIKANTIK_RATELIMIT_SESSION_PERCLIENT} — default 100 req/s</li>
 *   <li>{@code WIKANTIK_RATELIMIT_EXEMPT_CIDRS} — CSV IPv4 CIDRs, default empty
 *       (loopback is always exempt regardless)</li>
 * </ul>
 *
 * <p><b>Session-limited paths.</b> A request whose path (after the context path)
 * starts with a session-limited prefix (default {@code /api/collab/}) is limited by
 * its own per-IP <b>session</b> bucket ({@code WIKANTIK_RATELIMIT_SESSION_PERCLIENT})
 * instead of the default and expensive buckets. Those endpoints are long-lived
 * collaborative-editing sessions whose steady polling would exhaust the default bucket
 * shared by everyone behind one NAT; the collab session adds its own per-participant
 * caps (Phase 1), and the session bucket keeps a ceiling on any one address. Two
 * guards stop the classification from becoming a bypass:</p>
 * <ul>
 *   <li>A request carrying an {@code Authorization} header is <em>never</em> a
 *       session-path request — it is classified as default/expensive as before. This
 *       filter runs before {@code BasicAuthFilter}, so a Basic-auth request to a
 *       session path would otherwise reach the (deliberately slow) password check
 *       under the generous session limit.</li>
 *   <li>A configured prefix naming fewer than two segments below the root (three
 *       path segments counting the root, e.g. {@code /} or {@code /api/}) is too broad:
 *       it is WARNed at startup and refused, so a mis-set variable cannot move the
 *       whole API onto the session bucket.</li>
 * </ul>
 *
 * <p>On limit: {@code 429} + {@code Retry-After: 1}, a SecurityLog line, and a
 * {@code wikantik_ratelimit.rejected_total{tier=default|expensive|session}} counter.</p>
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
    public static final String ENV_SESSION_PATHS       = "WIKANTIK_RATELIMIT_SESSION_PATHS";
    public static final String ENV_SESSION_PERCLIENT   = "WIKANTIK_RATELIMIT_SESSION_PERCLIENT";
    public static final String ENV_EXEMPT_CIDRS        = "WIKANTIK_RATELIMIT_EXEMPT_CIDRS";

    public static final int DEFAULT_DEFAULT_PERCLIENT   = 25;
    public static final int DEFAULT_EXPENSIVE_PERCLIENT = 3;
    public static final int DEFAULT_EXPENSIVE_GLOBAL    = 10;
    public static final String DEFAULT_EXPENSIVE_PATHS  = "/api/bundle,/api/search,/sparql";
    public static final String DEFAULT_SESSION_PATHS    = "/api/collab/";
    public static final int DEFAULT_SESSION_PERCLIENT   = 100;

    private static final String TIER_DEFAULT = "default";
    private static final String TIER_EXPENSIVE = "expensive";
    private static final String TIER_SESSION = "session";

    /** Exact paths that must never be limited (monitoring probes). */
    private static final String EXEMPT_EXACT_PATH = "/api/health";

    /**
     * Resolved filter configuration. Package-private test seam: tests build one
     * directly (with a fake {@link Ticker}); the container path resolves it from
     * the environment in {@link #init}.
     */
    record Config( int defaultPerClient, int expensivePerClient, int expensiveGlobal,
                   List< String > expensivePathPrefixes,
                   List< String > sessionPathPrefixes, int sessionPerClient,
                   List< InternalNetworkFilter.CidrRange > exemptRanges,
                   Ticker ticker ) {

        static Config of( final int defaultPerClient, final int expensivePerClient, final int expensiveGlobal,
                          final List< String > expensivePathPrefixes, final List< String > exemptCidrs,
                          final Ticker ticker ) {
            return of( defaultPerClient, expensivePerClient, expensiveGlobal, expensivePathPrefixes,
                    sessionPathsFrom( null ), DEFAULT_SESSION_PERCLIENT, exemptCidrs, ticker );
        }

        static Config of( final int defaultPerClient, final int expensivePerClient, final int expensiveGlobal,
                          final List< String > expensivePathPrefixes, final List< String > sessionPathPrefixes,
                          final List< String > exemptCidrs, final Ticker ticker ) {
            return of( defaultPerClient, expensivePerClient, expensiveGlobal, expensivePathPrefixes,
                    sessionPathPrefixes, DEFAULT_SESSION_PERCLIENT, exemptCidrs, ticker );
        }

        static Config of( final int defaultPerClient, final int expensivePerClient, final int expensiveGlobal,
                          final List< String > expensivePathPrefixes, final List< String > sessionPathPrefixes,
                          final int sessionPerClient, final List< String > exemptCidrs, final Ticker ticker ) {
            return new Config( defaultPerClient, expensivePerClient, expensiveGlobal,
                    List.copyOf( expensivePathPrefixes ), normalisePrefixes( sessionPathPrefixes ),
                    sessionPerClient, parseCidrs( exemptCidrs ), ticker );
        }

        /**
         * Session-path prefixes from the raw {@link #ENV_SESSION_PATHS} value: {@code null}
         * (unset) means {@link #DEFAULT_SESSION_PATHS}; a blank value disables session paths.
         */
        static List< String > sessionPathsFrom( final String raw ) {
            if ( raw == null ) {
                return normalisePrefixes( List.of( DEFAULT_SESSION_PATHS ) );
            }
            return normalisePrefixes( Arrays.stream( raw.split( "," ) ).toList() );
        }

        /**
         * Trims, drops blanks, appends a trailing slash so a prefix never matches a sibling
         * like /api/collabX, and refuses (with a WARN) a prefix too broad to be a session path.
         */
        private static List< String > normalisePrefixes( final List< String > prefixes ) {
            return prefixes.stream().map( String::trim ).filter( s -> !s.isEmpty() )
                    .map( s -> s.endsWith( "/" ) ? s : s + "/" )
                    .filter( Config::narrowEnough ).toList();
        }

        /** A session prefix must name at least two segments below the root, e.g. {@code /api/collab/}. */
        private static boolean narrowEnough( final String prefix ) {
            final long segments = Arrays.stream( prefix.split( "/" ) ).filter( seg -> !seg.isEmpty() ).count();
            if ( segments < 2 ) {
                LOG.warn( "Refusing session rate-limit prefix '{}' from {}: too broad (it must name at least "
                        + "two path segments, e.g. /api/collab/) — those requests use the default bucket",
                        prefix, ENV_SESSION_PATHS );
                return false;
            }
            return true;
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
                    paths, sessionPathsFrom( System.getenv( ENV_SESSION_PATHS ) ),
                    envIntOr( ENV_SESSION_PERCLIENT, DEFAULT_SESSION_PERCLIENT ),
                    parseCidrs( cidrs ), Ticker.systemTicker() );
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
    private SlidingWindowRateLimiter sessionLimiter;
    private Counter rejectedDefault;
    private Counter rejectedExpensive;
    private Counter rejectedSession;

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
                && config.expensiveGlobal() <= 0 && config.sessionPerClient() <= 0;
        if ( disabled ) {
            LOG.info( "RateLimitFilter DISABLED (all limits <= 0)" );
            return;
        }

        // default tier: per-client only — BackpressureFilter bounds the aggregate.
        defaultLimiter = new SlidingWindowRateLimiter( 0, config.defaultPerClient(), 10000, config.ticker() );
        expensiveLimiter = new SlidingWindowRateLimiter(
                config.expensiveGlobal(), config.expensivePerClient(), 10000, config.ticker() );
        sessionLimiter = new SlidingWindowRateLimiter( 0, config.sessionPerClient(), 10000, config.ticker() );

        final MeterRegistry reg = MeterRegistryHolder.get();
        if ( reg != null ) {
            rejectedDefault = rejectionCounter( reg, TIER_DEFAULT );
            rejectedExpensive = rejectionCounter( reg, TIER_EXPENSIVE );
            rejectedSession = rejectionCounter( reg, TIER_SESSION );
        }
        LOG.info( "RateLimitFilter initialised: default={}/s per client; expensive={}/s per client, "
                        + "{}/s global, paths={}; session={}/s per client, paths={}; exemptCidrs={} "
                        + "(+loopback always){}",
                config.defaultPerClient(), config.expensivePerClient(), config.expensiveGlobal(),
                config.expensivePathPrefixes(), config.sessionPerClient(), config.sessionPathPrefixes(),
                config.exemptRanges().size(),
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

        // Classify on the container-normalized path, NOT the raw request URI.
        // getRequestURI() is left percent-encoded by Tomcat while servlet mapping is
        // done on the decoded path, so `GET /%73parql` maps to the SPARQL servlet yet
        // getRequestURI() returns "/%73parql", which does not start with "/sparql" —
        // dropping the request out of the expensive tier. getServletPath()+getPathInfo()
        // is the already-decoded, already-normalized path the container matched on.
        final String path = classificationPath( httpReq );
        final String rawUri = httpReq.getRequestURI();   // for logging only
        final String ip = httpReq.getRemoteAddr();
        if ( EXEMPT_EXACT_PATH.equals( path ) || isExemptAddress( ip ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String tier = tierOf( httpReq, path );
        if ( limiterFor( tier ).tryAcquire( "ip:" + ip ) ) {
            chain.doFilter( request, response );
            return;
        }

        SECURITY.warn( "Rate limit exceeded: tier={}, ip={}, path={}", tier, ip, rawUri );
        final Counter counter = counterFor( tier );
        if ( counter != null ) {
            counter.increment();
        }
        sendRateLimited( httpRes );
    }

    /**
     * The bucket a request is charged to. A request with an {@code Authorization} header
     * is never a session-path request (it would otherwise reach the Basic-auth password
     * check, which runs after this filter, under the generous session limit).
     */
    private String tierOf( final HttpServletRequest req, final String path ) {
        if ( req.getHeader( "Authorization" ) == null && isSessionPath( path ) ) {
            return TIER_SESSION;
        }
        return isExpensivePath( path ) ? TIER_EXPENSIVE : TIER_DEFAULT;
    }

    private SlidingWindowRateLimiter limiterFor( final String tier ) {
        return switch ( tier ) {
            case TIER_SESSION -> sessionLimiter;
            case TIER_EXPENSIVE -> expensiveLimiter;
            default -> defaultLimiter;
        };
    }

    private Counter counterFor( final String tier ) {
        return switch ( tier ) {
            case TIER_SESSION -> rejectedSession;
            case TIER_EXPENSIVE -> rejectedExpensive;
            default -> rejectedDefault;
        };
    }

    private static Counter rejectionCounter( final MeterRegistry reg, final String tier ) {
        return Counter.builder( "wikantik_ratelimit.rejected_total" )
                .description( "HTTP requests rejected by the per-client rate limiter (429)" )
                .tag( "tier", tier ).register( reg );
    }

    private boolean isSessionPath( final String path ) {
        if ( path == null ) return false;
        for ( final String prefix : config.sessionPathPrefixes() ) {
            if ( path.startsWith( prefix ) ) return true;
        }
        return false;
    }

    private boolean isExpensivePath( final String path ) {
        if ( path == null ) return false;
        for ( final String prefix : config.expensivePathPrefixes() ) {
            if ( path.startsWith( prefix ) ) return true;
        }
        return false;
    }

    /**
     * The path to classify against the expensive-tier prefixes: the container's
     * already-decoded, already-normalized servlet path, so a percent-encoded request
     * (e.g. {@code /%73parql}) is classified on the path the container actually mapped
     * ({@code /sparql}), not on the raw encoded URI. Falls back to the raw URI only if
     * the servlet path is unavailable (defensive — should not happen in a container).
     */
    private static String classificationPath( final HttpServletRequest req ) {
        final String servletPath = req.getServletPath();
        if ( servletPath == null ) {
            return req.getRequestURI();
        }
        final String pathInfo = req.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
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
