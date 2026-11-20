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
package com.wikantik.rest;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The route table of a REST servlet whose paths are made of whole segments: a method plus a pattern per handler,
 * and the matching of a request to one.
 *
 * <p>A request path is the servlet path plus the path info, with {@code prefix} removed when the path starts with
 * it and empty segments dropped ({@code /a//b/} reads as {@code a, b}). A pattern is a {@code /}-separated list of
 * literal segments and {@code {}} placeholders, each of which captures exactly one segment; {@code ""} is the
 * prefix itself. A route matches when the method is equal and the segment count is equal, so a route can never
 * capture a name that itself contains {@code /}: a servlet whose paths do that dispatches by suffix instead.
 *
 * <p>Routes are matched in registration order, which only matters if two patterns could match one path.
 *
 * @param <H> what a route runs; the table never invokes it, the caller does.
 */
final class SegmentRouteTable< H > {

    /** The route a request matched: its handler, and the segments its {@code {}} placeholders captured, in order. */
    record Match< H >( H handler, List< String > captured ) {
    }

    private record Route< H >( String method, String[] pattern, H handler ) {
    }

    private final String prefix;
    private final List< Route< H > > routes = new ArrayList<>();

    /** A table of paths under {@code prefix}, for example {@code /api/work}. */
    SegmentRouteTable( final String prefix ) {
        this.prefix = prefix;
    }

    /** Registers {@code handler} for {@code method} (upper case) on {@code pattern}; returns this table. */
    SegmentRouteTable< H > add( final String method, final String pattern, final H handler ) {
        routes.add( new Route<>( method, pattern.isEmpty() ? new String[ 0 ] : pattern.split( "/" ), handler ) );
        return this;
    }

    /** The route for the request's method and path, or empty when none matches. */
    Optional< Match< H > > match( final HttpServletRequest request ) {
        final String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase( Locale.ROOT );
        return match( method, segments( request, prefix ) );
    }

    /** The route for {@code method} and already-split {@code segments}, or empty when none matches. */
    Optional< Match< H > > match( final String method, final String[] segments ) {
        for ( final Route< H > route : routes ) {
            final List< String > captured = capture( route, method, segments );
            if ( captured != null ) {
                return Optional.of( new Match<>( route.handler(), captured ) );
            }
        }
        return Optional.empty();
    }

    /** The non-empty segments of the request path under {@code prefix}. */
    static String[] segments( final HttpServletRequest request, final String prefix ) {
        final String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        final String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = servletPath + pathInfo;
        if ( path.startsWith( prefix ) ) {
            path = path.substring( prefix.length() );
        }
        final List< String > out = new ArrayList<>();
        for ( final String s : path.split( "/" ) ) {
            if ( !s.isEmpty() ) {
                out.add( s );
            }
        }
        return out.toArray( new String[ 0 ] );
    }

    private static < H > List< String > capture( final Route< H > route, final String method, final String[] segments ) {
        if ( !route.method().equals( method ) || route.pattern().length != segments.length ) {
            return null;
        }
        final List< String > captured = new ArrayList<>();
        for ( int i = 0; i < segments.length; i++ ) {
            if ( "{}".equals( route.pattern()[ i ] ) ) {
                captured.add( segments[ i ] );
            } else if ( !route.pattern()[ i ].equals( segments[ i ] ) ) {
                return null;
            }
        }
        return captured;
    }
}
