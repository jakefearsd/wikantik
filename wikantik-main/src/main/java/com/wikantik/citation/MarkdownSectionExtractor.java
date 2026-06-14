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
package com.wikantik.citation;

import com.wikantik.api.frontmatter.FrontmatterParser;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Returns the body text under a given ATX heading-path (" > "-joined). Frontmatter is stripped
 * first. An empty heading-path returns the whole (frontmatter-stripped) body. Capture runs until
 * the next heading of equal-or-higher level. Returns empty when the path does not resolve.
 */
public final class MarkdownSectionExtractor {

    private static final Pattern ATX = Pattern.compile( "^(#{1,6})\\s+(.*?)\\s*#*\\s*$" );

    public Optional< String > sectionText( final String rawBody, final String headingPath ) {
        final String body = FrontmatterParser.parse( rawBody == null ? "" : rawBody ).body();
        if ( headingPath == null || headingPath.isBlank() ) { return Optional.of( body ); }
        final String[] want = headingPath.split( " > " );
        for ( int i = 0; i < want.length; i++ ) { want[ i ] = Spans.normalize( want[ i ] ); }

        final String[] lines = body.split( "\n", -1 );
        final Deque< String > path = new ArrayDeque<>();   // titles, shallow->deep
        final Deque< Integer > levels = new ArrayDeque<>();
        int captureLevel = -1;
        StringBuilder capture = null;

        for ( final String line : lines ) {
            final Matcher m = ATX.matcher( line );
            if ( m.matches() ) {
                final int level = m.group( 1 ).length();
                if ( capture != null && level <= captureLevel ) {
                    return Optional.of( capture.toString() );      // section ended
                }
                while ( !levels.isEmpty() && levels.peekLast() >= level ) { levels.removeLast(); path.removeLast(); }
                levels.addLast( level );
                path.addLast( Spans.normalize( m.group( 2 ) ) );
                if ( capture == null && pathMatches( path, want ) ) {
                    captureLevel = level;
                    capture = new StringBuilder();
                }
            } else if ( capture != null ) {
                capture.append( line ).append( '\n' );
            }
        }
        return capture == null ? Optional.empty() : Optional.of( capture.toString() );
    }

    private static boolean pathMatches( final Deque< String > path, final String[] want ) {
        if ( path.size() != want.length ) { return false; }
        int i = 0;
        for ( final String seg : path ) { if ( !seg.equals( want[ i++ ] ) ) { return false; } }
        return true;
    }
}
