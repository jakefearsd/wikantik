/*
 * Copyright 2025 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.http.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic guard against the session-cookie misconfiguration that causes
 * random logouts / unstable sessions.
 *
 * <p>A {@code JSESSIONID} cookie issued with {@code SameSite=Strict} (or with no
 * SameSite attribute at all) is <em>withheld by browsers on top-level
 * navigations</em> — a page refresh or an SSO callback redirect — so the very
 * next request arrives with no session and the user appears randomly logged out.
 * The curl/XHR path always sends the cookie, so this regression hides from API
 * tests and only bites real browsers, making it expensive to diagnose. It is
 * also easy to reintroduce: it lives in a single Tomcat {@code CookieProcessor
 * sameSiteCookies} setting (conf/context.xml) that drifts independently of the
 * code.</p>
 *
 * <p>This filter inspects the session cookie the application actually emits and
 * logs at {@code ERROR} (rate-limited) when the policy is the known-broken one,
 * so the misconfiguration is loud in the logs the moment it ships rather than
 * resurfacing weeks later as mysterious "I keep getting logged out" reports. It
 * never modifies the response.</p>
 */
public class SessionCookiePolicyFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( SessionCookiePolicyFilter.class );

    /** Classification of an auth cookie's SameSite policy. */
    enum Policy { OK, STRICT, MISSING, NOT_SESSION }

    /**
     * Identity-bearing cookies whose SameSite policy governs whether a logged-in
     * user stays logged in across top-level navigations: the container session
     * cookie and the remember-me cookie (plus its legacy name). Lower-cased.
     */
    private static final Set< String > AUTH_COOKIE_NAMES =
            Set.of( "jsessionid", "wikantikuid", "jspwikiuid" );

    /** Rate-limit: emit the ERROR at most once per this interval. */
    private static final long LOG_INTERVAL_MILLIS = 5L * 60L * 1000L;

    private final AtomicLong lastLoggedAtMillis = new AtomicLong( 0L );

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        // Run the request first; the container appends the JSESSIONID Set-Cookie
        // during processing, so we inspect the final headers on the way out.
        chain.doFilter( request, response );
        if ( response instanceof HttpServletResponse res && !res.isCommitted() ) {
            for ( final String header : res.getHeaders( "Set-Cookie" ) ) {
                final Policy policy = classify( header );
                if ( policy == Policy.STRICT || policy == Policy.MISSING ) {
                    maybeLog( cookieName( header ), policy );
                    // keep scanning: a response can set both the session and the
                    // remember-me cookie, and we want to flag whichever is broken.
                }
            }
        }
    }

    /**
     * Classifies a single {@code Set-Cookie} header value. Package-private so the
     * substance is unit-testable without a servlet container.
     *
     * @param setCookieHeader one Set-Cookie header value (may be {@code null})
     * @return {@link Policy#NOT_SESSION} when it is not an identity-bearing cookie;
     *         otherwise OK / STRICT / MISSING per its SameSite attribute.
     */
    static Policy classify( final String setCookieHeader ) {
        final String name = cookieName( setCookieHeader );
        if ( name == null || !AUTH_COOKIE_NAMES.contains( name.toLowerCase( Locale.ROOT ) ) ) {
            return Policy.NOT_SESSION;
        }
        final String lower = setCookieHeader.toLowerCase( Locale.ROOT );
        if ( lower.contains( "samesite=strict" ) ) {
            return Policy.STRICT;
        }
        if ( !lower.contains( "samesite=" ) ) {
            return Policy.MISSING;
        }
        return Policy.OK;
    }

    /** Extracts the cookie name (text before the first '='), or {@code null}. */
    private static String cookieName( final String setCookieHeader ) {
        if ( setCookieHeader == null ) {
            return null;
        }
        final int eq = setCookieHeader.indexOf( '=' );
        return eq <= 0 ? null : setCookieHeader.substring( 0, eq ).trim();
    }

    private void maybeLog( final String cookie, final Policy policy ) {
        final long now = System.currentTimeMillis();
        final long last = lastLoggedAtMillis.get();
        if ( now - last < LOG_INTERVAL_MILLIS || !lastLoggedAtMillis.compareAndSet( last, now ) ) {
            return;
        }
        final String detail = policy == Policy.STRICT ? "Strict" : "absent";
        // LOG.error justified: a Strict/absent SameSite auth cookie is withheld by browsers
        // on top-level navigation (refresh, SSO callback), producing the random-logout /
        // unstable-session failure. This must be loud so the config regression is caught at once.
        LOG.error( "Auth cookie {} is being issued with SameSite={} — browsers withhold it on "
                + "top-level navigations (page refresh, SSO callback), which manifests as random "
                + "logouts / unstable sessions. Fix: set SameSite=Lax (Tomcat CookieProcessor "
                + "sameSiteCookies=\"lax\" in conf/context.xml for JSESSIONID; "
                + "CookieAuthenticationLoginModule for the remember-me cookie).",
                cookie, detail );
    }

    @Override
    public void init( final FilterConfig filterConfig ) {
        /* no configuration */
    }

    @Override
    public void destroy() {
        /* no resources to release */
    }
}
