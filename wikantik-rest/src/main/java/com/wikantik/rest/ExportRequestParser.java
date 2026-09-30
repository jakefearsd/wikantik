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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.api.pagegraph.PageType;
import com.wikantik.export.ExportSelection;
import com.wikantik.export.UnresolvedLinkMode;

/**
 * Pure request-parsing helpers for {@link ExportResource}, split out so the servlet stays
 * focused on routing/streaming (PMD {@code GodClass} — the parsing methods are all static
 * and share no state with the servlet, so they don't belong on it).
 */
final class ExportRequestParser {

    private ExportRequestParser() {
    }

    /**
     * Parses the wire query parameters into an {@link ExportSelection}. Throws
     * {@link IllegalArgumentException} (message names the offending parameter) on any bad
     * value — callers turn that into a 400.
     */
    static ExportSelection parseSelection( final HttpServletRequest req ) {
        final List< String > clusters = paramList( req, "cluster" );
        final List< String > tags = paramList( req, "tag" );
        final boolean subclusters = parseSubclusters( req.getParameter( "subclusters" ) );
        final Optional< PageType > type = parseType( req.getParameter( "type" ) );
        final String statusParam = req.getParameter( "status" );
        final Optional< String > status = ( statusParam == null || statusParam.isBlank() )
                ? Optional.empty() : Optional.of( statusParam );
        final int hops = parseHops( req.getParameter( "hops" ) );
        final UnresolvedLinkMode unresolved;
        try {
            unresolved = UnresolvedLinkMode.fromWire( req.getParameter( "unresolved" ) );
        } catch ( final IllegalArgumentException e ) {
            throw new IllegalArgumentException( "unresolved: " + e.getMessage() );
        }
        return new ExportSelection( clusters, subclusters, tags, type, status, hops, unresolved );
    }

    private static List< String > paramList( final HttpServletRequest req, final String name ) {
        final String[] values = req.getParameterValues( name );
        return values == null ? List.of() : Arrays.asList( values );
    }

    private static boolean parseSubclusters( final String raw ) {
        if ( raw == null || raw.isBlank() || "true".equalsIgnoreCase( raw ) ) {
            return true;
        }
        if ( "false".equalsIgnoreCase( raw ) ) {
            return false;
        }
        throw new IllegalArgumentException( "subclusters must be true or false, got: " + raw );
    }

    private static Optional< PageType > parseType( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return Optional.empty();
        }
        final PageType type = PageType.fromFrontmatter( raw );
        if ( type == PageType.UNKNOWN ) {
            throw new IllegalArgumentException( "Unknown type: " + raw );
        }
        return Optional.of( type );
    }

    private static int parseHops( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return 0;
        }
        final int hops;
        try {
            hops = Integer.parseInt( raw.trim() );
        } catch ( final NumberFormatException e ) {
            throw new IllegalArgumentException( "hops must be an integer between 0 and "
                    + ExportSelection.MAX_HOPS + ", got: " + raw );
        }
        if ( hops < 0 || hops > ExportSelection.MAX_HOPS ) {
            throw new IllegalArgumentException( "hops must be between 0 and " + ExportSelection.MAX_HOPS
                    + ", got: " + hops );
        }
        return hops;
    }

    /**
     * Derives an absolute base URL ({@code scheme://host[:port]} + context path) from the
     * request, for {@code ExportService} to fall back on when {@code wikantik.baseURL} is
     * blank. Default ports (80 for http, 443 for https) are omitted.
     */
    static String requestBaseUrl( final HttpServletRequest req ) {
        final String scheme = req.getScheme() == null ? "http" : req.getScheme().toLowerCase( Locale.ROOT );
        final int port = req.getServerPort();
        final boolean defaultPort = ( "http".equals( scheme ) && port == 80 )
                || ( "https".equals( scheme ) && port == 443 );
        final StringBuilder sb = new StringBuilder( scheme ).append( "://" ).append( req.getServerName() );
        if ( !defaultPort && port > 0 ) {
            sb.append( ':' ).append( port );
        }
        final String contextPath = req.getContextPath();
        if ( contextPath != null ) {
            sb.append( contextPath );
        }
        return sb.toString();
    }
}
