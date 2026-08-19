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
package com.wikantik.connectors.http;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF egress policy for connector HTTP fetches. Connector target URLs are supplied
 * by an admin, but "admin" is a scoped role here and the server sits on a private
 * Docker/LAN network (a DB, an embedder, sibling services, cloud metadata) — so an
 * unrestricted fetch is a real forge-a-request-from-the-server primitive. This guard
 * rejects a URL whose scheme is not http(s), or whose host resolves to any loopback,
 * private, link-local (incl. 169.254.169.254 metadata), multicast or unspecified
 * address, or an IPv6 unique-local address.
 *
 * <p>Default-deny for private networks. An operator running an intentionally-internal
 * crawl can opt out with {@code -Dwikantik.connectors.egress.allowPrivate=true}; the
 * scheme check always applies.
 *
 * <p>Callers must re-check on every redirect hop — a public URL that 302s to
 * {@code http://169.254.169.254/} is the classic bypass. {@link #followsSafely} does
 * that for the manual-redirect fetch path.
 */
public final class EgressGuard {

    /** Escape hatch for deliberate internal crawling; the scheme allowlist still applies. */
    public static final String PROP_ALLOW_PRIVATE = "wikantik.connectors.egress.allowPrivate";

    /** Cap on redirect hops the fetcher will follow, each re-validated. */
    public static final int MAX_REDIRECTS = 5;

    private EgressGuard() {}

    /** Thrown when a target URL is not allowed to be fetched. Message is caller-safe (no secrets). */
    public static final class EgressBlockedException extends Exception {
        private static final long serialVersionUID = 1L;
        public EgressBlockedException( final String msg ) { super( msg ); }
    }

    private static boolean allowPrivate() {
        return Boolean.parseBoolean( System.getProperty( PROP_ALLOW_PRIVATE, "false" ) );
    }

    /**
     * Validates a single URL. Throws {@link EgressBlockedException} if the scheme is
     * not http(s) or the host resolves to a blocked address.
     */
    public static void check( final URI uri ) throws EgressBlockedException {
        if ( uri == null ) {
            throw new EgressBlockedException( "no target URL" );
        }
        final String scheme = uri.getScheme();
        if ( scheme == null
                || !( scheme.equalsIgnoreCase( "http" ) || scheme.equalsIgnoreCase( "https" ) ) ) {
            throw new EgressBlockedException( "scheme not permitted for connector fetch: " + scheme );
        }
        if ( allowPrivate() ) {
            return;
        }
        final String host = uri.getHost();
        if ( host == null || host.isBlank() ) {
            throw new EgressBlockedException( "target URL has no host" );
        }
        final InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName( host );
        } catch ( final UnknownHostException e ) {
            throw new EgressBlockedException( "target host does not resolve: " + host );
        }
        for ( final InetAddress a : addrs ) {
            if ( isBlockedAddress( a ) ) {
                // Do not echo the resolved IP — enough to say the host maps to an internal address.
                throw new EgressBlockedException( "target host resolves to a non-routable/internal address: " + host );
            }
        }
    }

    /** True for any address a connector must never reach. */
    static boolean isBlockedAddress( final InetAddress a ) {
        if ( a.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || a.isAnyLocalAddress() // 0.0.0.0, ::
                || a.isLinkLocalAddress()// 169.254.0.0/16 (incl. 169.254.169.254 metadata), fe80::/10
                || a.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16
                || a.isMulticastAddress() ) {
            return true;
        }
        // IPv6 unique-local addresses fc00::/7 are private but not flagged by isSiteLocalAddress.
        if ( a instanceof Inet6Address ) {
            final byte[] b = a.getAddress();
            if ( b.length == 16 && ( b[ 0 ] & 0xFE ) == 0xFC ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a lowercase HTTP status is a redirect the fetcher should follow.
     */
    public static boolean isRedirect( final int status ) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Resolves and validates a redirect Location against the previous URL, returning
     * the next URI to fetch. Throws if the resolved target is blocked. Relative
     * Locations are resolved against {@code current}.
     */
    public static URI followsSafely( final URI current, final String location ) throws EgressBlockedException {
        if ( location == null || location.isBlank() ) {
            throw new EgressBlockedException( "redirect without a Location" );
        }
        final URI next;
        try {
            next = current.resolve( location.trim() );
        } catch ( final IllegalArgumentException e ) {
            throw new EgressBlockedException( "redirect to a malformed Location" );
        }
        check( next );
        return next;
    }

    /** For diagnostics/log lines: the lowercased host, or {@code "?"}. */
    static String hostOf( final URI uri ) {
        final String h = uri == null ? null : uri.getHost();
        return h == null ? "?" : h.toLowerCase( Locale.ROOT );
    }
}
