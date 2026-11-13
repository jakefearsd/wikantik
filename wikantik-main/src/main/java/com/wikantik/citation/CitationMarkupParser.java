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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extracts {@code [claim](cite://target/heading "span")} links from a page body, in document order. */
public final class CitationMarkupParser {

    private static final String SCHEME = "cite://";

    public List< ParsedCitation > parse( final String body ) {
        final List< ParsedCitation > out = new ArrayList<>();
        if ( body == null || body.isEmpty() ) { return out; }
        final Map< String, Integer > ordinals = new HashMap<>();
        final Scan m = new Scan( body );
        while ( m.find() ) {
            final String claim = m.claim.trim();
            final String path = m.path;
            final String span = m.span == null ? "" : m.span;
            final int slash = path.indexOf( '/' );
            final String target = slash < 0 ? path : path.substring( 0, slash );
            final String headingPath = slash < 0 ? "" : decodeHeadingPath( path.substring( slash + 1 ) );
            final String spanHash = Spans.hash( Spans.normalize( span ) );
            final String key = target + " " + headingPath + " " + spanHash;
            final int ordinal = ordinals.merge( key, 0, ( a, b ) -> a + 1 );
            out.add( new ParsedCitation( target, headingPath, span, spanHash, claim, ordinal ) );
        }
        return out;
    }

    /**
     * One forward pass that finds exactly what the regex
     * {@code \[([^\]]*)]\(\s*cite://([^\s)"]+)\s*(?:"([^"]*)")?\s*\)} finds. The regex rescanned to the next
     * {@code ]}, path end or closing quote from every opener, so a body of unclosed openers cost quadratic time on
     * the save path; here each of those scans is remembered and never repeated.
     */
    private static final class Scan {
        private final String s;
        private int cursor;
        /** End of the last path that led nowhere: a later path starting before it ends there too, and fails too. */
        private int failedPathEnd = -1;
        /** An opening quote at or after this offset has no closing quote. */
        private int noQuoteFrom = Integer.MAX_VALUE;
        String claim;
        String path;
        String span;

        Scan( final String s ) {
            this.s = s;
        }

        boolean find() {
            int pos = cursor;
            while ( true ) {
                final int open = s.indexOf( '[', pos );
                if ( open < 0 ) {
                    return false;
                }
                final int close = s.indexOf( ']', open + 1 );
                if ( close < 0 ) {
                    return false;
                }
                final int end = tail( close + 1 );
                if ( end < 0 ) {
                    pos = close + 1; // every opener before this ] shares it, and fails the same way
                    continue;
                }
                claim = s.substring( open + 1, close );
                cursor = end;
                return true;
            }
        }

        /** Matches {@code ( cite://path "span" )} at {@code at}; returns the offset past it, or -1. */
        private int tail( final int at ) {
            if ( at >= s.length() || s.charAt( at ) != '(' ) {
                return -1;
            }
            final int pathStart = skipSpace( at + 1 ) + SCHEME.length();
            if ( !s.startsWith( SCHEME, pathStart - SCHEME.length() ) || pathStart < failedPathEnd ) {
                return -1;
            }
            int pathEnd = pathStart;
            while ( pathEnd < s.length() && isPathChar( s.charAt( pathEnd ) ) ) {
                pathEnd++;
            }
            if ( pathEnd == pathStart ) {
                return -1;
            }
            int at2 = skipSpace( pathEnd );
            String title = null;
            if ( at2 < s.length() && s.charAt( at2 ) == '"' ) {
                final int quoteEnd = at2 >= noQuoteFrom ? -1 : s.indexOf( '"', at2 + 1 );
                if ( quoteEnd < 0 ) {
                    noQuoteFrom = Math.min( noQuoteFrom, at2 );
                    failedPathEnd = pathEnd;
                    return -1;
                }
                title = s.substring( at2 + 1, quoteEnd );
                at2 = skipSpace( quoteEnd + 1 );
            }
            if ( at2 >= s.length() || s.charAt( at2 ) != ')' ) {
                failedPathEnd = pathEnd;
                return -1;
            }
            path = s.substring( pathStart, pathEnd );
            span = title;
            return at2 + 1;
        }

        private int skipSpace( final int from ) {
            int i = from;
            while ( i < s.length() && isSpace( s.charAt( i ) ) ) {
                i++;
            }
            return i;
        }

        /** The regex {@code \s} class. */
        private static boolean isSpace( final char c ) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
        }

        private static boolean isPathChar( final char c ) {
            return !isSpace( c ) && c != ')' && c != '"';
        }
    }

    private static String decodeHeadingPath( final String raw ) {
        final String[] segs = raw.split( "/" );
        final StringBuilder sb = new StringBuilder();
        for ( final String seg : segs ) {
            if ( seg.isEmpty() ) { continue; }
            if ( sb.length() > 0 ) { sb.append( " > " ); }
            sb.append( URLDecoder.decode( seg, StandardCharsets.UTF_8 ).trim() );
        }
        return sb.toString();
    }
}
