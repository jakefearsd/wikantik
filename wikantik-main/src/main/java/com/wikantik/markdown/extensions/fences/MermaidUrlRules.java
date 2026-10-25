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
package com.wikantik.markdown.extensions.fences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL-level rules of the Mermaid safety policy (see {@link MermaidFenceRenderer}): the scheme allow-list,
 * quoted-string checks and page-name extraction for {@code /wiki/} targets.
 */
final class MermaidUrlRules {

    private static final Logger LOG = LogManager.getLogger( MermaidUrlRules.class );

    private static final Pattern QUOTED = Pattern.compile( "\"([^\"]*+)\"" );

    private MermaidUrlRules() {
    }

    /** Every double-quoted string in a click/link/links statement must be a safe URL or contain no ':'. */
    static boolean quotedStringsSafe( final String statement ) {
        final Matcher m = QUOTED.matcher( statement );
        while ( m.find() ) {
            final String decoded = MermaidTextRules.decodeEntities( m.group( 1 ) ).strip();
            if ( decoded.indexOf( ':' ) >= 0 && !safeUrl( decoded ) ) {
                return false;
            }
        }
        return true;
    }

    /** Applies the scheme rule after removing whitespace/control chars browsers ignore inside a scheme. */
    static boolean safeUrl( final String url ) {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < url.length(); i++ ) {
            final char c = url.charAt( i );
            if ( c > 0x20 && !( c >= 0x7f && c <= 0x9f ) && c != '​' && c != '­' ) {
                sb.append( c );
            }
        }
        final String u = sb.toString().toLowerCase( Locale.ROOT );
        if ( u.startsWith( "/" ) || u.startsWith( "#" ) || u.startsWith( "http://" ) || u.startsWith( "https://" ) ) {
            return true;
        }
        final int colon = u.indexOf( ':' );
        if ( colon < 0 ) {
            return true;
        }
        final int slash = u.indexOf( '/' );
        return slash >= 0 && slash < colon;
    }

    static Optional< String > pageName( final String url ) {
        if ( !url.startsWith( "/wiki/" ) ) {
            return Optional.empty();
        }
        String name = url.substring( "/wiki/".length() );
        final int cut = firstIndexOf( name, '#', '?' );
        if ( cut >= 0 ) {
            name = name.substring( 0, cut );
        }
        try {
            name = URLDecoder.decode( name.replace( "+", "%2B" ), StandardCharsets.UTF_8 );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Diagram link target '{}' has bad percent-encoding; not tracked: {}", url, e.getMessage() );
            return Optional.empty();
        }
        return name.isBlank() ? Optional.empty() : Optional.of( name );
    }

    private static int firstIndexOf( final String s, final char a, final char b ) {
        final int i = s.indexOf( a );
        final int j = s.indexOf( b );
        return i < 0 ? j : j < 0 ? i : Math.min( i, j );
    }
}
