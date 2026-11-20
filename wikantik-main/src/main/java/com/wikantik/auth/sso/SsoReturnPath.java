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
package com.wikantik.auth.sso;

import java.util.Locale;
import java.util.Optional;

/**
 * The page a user should land on after single sign-on: the page they started signing in from.
 * <p>
 * The SPA passes it as {@code /sso/login?return=<path>}; {@link SSORedirectServlet} stores it in the
 * HTTP session under {@link #SESSION_ATTRIBUTE} and {@link SSOCallbackServlet} sends the user there once
 * the IdP has answered. Because the value is reachable by anyone who can craft a link, it is accepted only
 * as a same-origin path inside this wiki's context: no scheme, no {@code //} or backslash (both can turn a
 * path into another host), no control characters (header splitting), and never the sign-in machinery
 * itself (matched case-insensitively, as the SPA router matches routes), which would loop; nor a
 * {@code .}/{@code ..} segment, which the browser would resolve to somewhere else. Anything else falls back
 * to the front page.
 * </p>
 */
public final class SsoReturnPath {

    /** Session attribute that carries the return path from {@code /sso/login} to {@code /sso/callback}. */
    public static final String SESSION_ATTRIBUTE = SsoReturnPath.class.getName() + ".returnTo";

    static final int MAX_LENGTH = 2048;

    /** Sign-in machinery: landing on any of these after signing in would loop or sign the user out. */
    private static final String[] LOOPING_ROUTES = { "/login", "/logout", "/sso" };

    private SsoReturnPath() {
    }

    /**
     * @param raw         the requested path, including the context path, query and fragment
     * @param contextPath the servlet context path ({@code ""} for the root context)
     * @return the path when it is safe to redirect to, else empty
     */
    public static Optional< String > sanitize( final String raw, final String contextPath ) {
        if( !isPlainLocalPath( raw ) ) {
            return Optional.empty();
        }
        final String context = contextPath == null ? "" : contextPath;
        if( !context.isEmpty() && !raw.startsWith( context + "/" ) ) {
            return Optional.empty();
        }
        final String inContext = raw.substring( context.length() ).toLowerCase( Locale.ROOT );
        if( hasDotSegment( inContext ) || isSignInRoute( inContext ) ) {
            return Optional.empty();
        }
        return Optional.of( raw );
    }

    /** A path on this host: leading slash, no {@code //} or backslash, no control characters, bounded length. */
    private static boolean isPlainLocalPath( final String raw ) {
        if( raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH ) {
            return false;
        }
        if( raw.charAt( 0 ) != '/' || raw.startsWith( "//" ) || raw.indexOf( '\\' ) >= 0 ) {
            return false;
        }
        return raw.chars().noneMatch( c -> c < 0x20 || c == 0x7f );
    }

    private static boolean hasDotSegment( final String inContext ) {
        final String path = inContext.substring( 0, firstIndexOf( inContext, "?#" ) );
        for( final String segment : path.split( "/", -1 ) ) {
            if( ".".equals( segment ) || "..".equals( segment ) ) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSignInRoute( final String inContext ) {
        for( final String route : LOOPING_ROUTES ) {
            if( inContext.startsWith( route )
                    && ( inContext.length() == route.length() || "/?#".indexOf( inContext.charAt( route.length() ) ) >= 0 ) ) {
                return true;
            }
        }
        return false;
    }

    private static int firstIndexOf( final String s, final String chars ) {
        for( int i = 0; i < s.length(); i++ ) {
            if( chars.indexOf( s.charAt( i ) ) >= 0 ) {
                return i;
            }
        }
        return s.length();
    }
}
