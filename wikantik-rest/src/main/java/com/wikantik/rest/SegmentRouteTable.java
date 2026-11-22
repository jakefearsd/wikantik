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
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The route table of a REST servlet whose paths are made of whole segments: a method plus a pattern per handler,
 * and the matching of a request to one.
 *
 * <p>A pattern is a {@code /}-separated list of literal segments and {@code {}} placeholders, each of which
 * captures exactly one segment; {@code ""} is the servlet root. A route matches when the method is equal and the
 * segment count is equal, and empty segments in the request are dropped ({@code /a//b/} reads as {@code a, b}).
 * Routes are matched in registration order, which only matters if two patterns could match one path.
 *
 * <p><b>Which table.</b> {@link PathRouteTable} matches one raw path string against exact keys, regexes or
 * predicates, and so can serve a servlet whose path carries a name containing {@code /}. This class cannot: a
 * {@code {}} never spans a {@code /}, so such a servlet (a page or cluster name, a login) dispatches by suffix or
 * with {@link PathRouteTable} instead. Use this class where every path is literal segments and ids.
 *
 * @param <H> what a route runs; the table never invokes it, the caller does (see {@link #dispatch}).
 */
final class SegmentRouteTable< H > {

    /** The route a request matched: its handler, and the segments its {@code {}} placeholders captured, in order. */
    record Match< H >( H handler, List< String > captured ) {
    }

    /**
     * A route's action, for servlets that dispatch with {@link #dispatch}: run against {@code context} (the servlet,
     * or a record of the servlet and what it resolved before dispatching) with the segments the route captured.
     */
    @FunctionalInterface
    interface Action< C > {
        void run( C context, HttpServletRequest request, HttpServletResponse response, List< String > captured )
                throws IOException;
    }

    private record Route< H >( String method, String[] pattern, H handler ) {
    }

    private final List< Route< H > > routes;
    private final String prefix;

    private SegmentRouteTable( final String prefix, final List< Route< H > > routes ) {
        this.prefix = prefix;
        this.routes = List.copyOf( routes );
    }

    /** Collects routes for a table of paths read from the path info (what follows the servlet's mapping). */
    static < H > Builder< H > builder() {
        return new Builder<>( null );
    }

    /**
     * Collects routes for a table of paths read from servlet path plus path info with {@code prefix} removed, for
     * a servlet one class serves under several mappings of which only the prefix is stable, for example
     * {@code /api/work}.
     */
    static < H > Builder< H > builder( final String prefix ) {
        return new Builder<>( prefix );
    }

    /** Collects routes; {@link #build()} seals them into a table. */
    static final class Builder< H > {
        private final String prefix;
        private final List< Route< H > > routes = new ArrayList<>();

        private Builder( final String prefix ) {
            this.prefix = prefix;
        }

        /** Registers {@code handler} for {@code method} (upper case) on {@code pattern}. */
        Builder< H > add( final String method, final String pattern, final H handler ) {
            routes.add( new Route<>( method, pattern.isEmpty() ? new String[ 0 ] : pattern.split( "/" ), handler ) );
            return this;
        }

        SegmentRouteTable< H > build() {
            return new SegmentRouteTable<>( prefix, routes );
        }
    }

    /** Every registered route as {@code METHOD pattern}, in registration order (for tests that guard route coverage). */
    List< String > describe() {
        final List< String > out = new ArrayList<>();
        for ( final Route< H > route : routes ) {
            out.add( route.method() + " " + String.join( "/", route.pattern() ) );
        }
        return out;
    }

    /** The non-empty segments of the request path, under this table's prefix if it has one. */
    String[] segments( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = pathInfo;
        if ( prefix != null ) {
            final String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
            path = servletPath + pathInfo;
            if ( path.startsWith( prefix ) ) {
                path = path.substring( prefix.length() );
            }
        }
        final List< String > out = new ArrayList<>();
        for ( final String s : path.split( "/" ) ) {
            if ( !s.isEmpty() ) {
                out.add( s );
            }
        }
        return out.toArray( new String[ 0 ] );
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

    /**
     * Runs the route for {@code method} and the request's path against {@code context}.
     *
     * @return true when a route ran, false when none matches, so the caller writes its own not-found response.
     */
    static < C > boolean dispatch( final SegmentRouteTable< Action< C > > table, final C context,
                                   final String method, final HttpServletRequest request,
                                   final HttpServletResponse response ) throws IOException {
        final Optional< Match< Action< C > > > match = table.match( method, table.segments( request ) );
        if ( match.isEmpty() ) {
            return false;
        }
        match.get().handler().run( context, request, response, match.get().captured() );
        return true;
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
