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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Line-level text helpers shared by the fence scanner: line splitting, indentation, blockquote and list markers. */
final class FenceLines {

    static final int MAX_FENCE_INDENT = 3;

    private FenceLines() {
    }

    static List< String > splitLines( final String body ) {
        final List< String > lines = new ArrayList<>();
        final int n = body.length();
        int start = 0;
        for ( int i = 0; i < n; i++ ) {
            final char ch = body.charAt( i );
            if ( ch == '\n' || ch == '\r' ) {
                lines.add( body.substring( start, i ) );
                if ( ch == '\r' && i + 1 < n && body.charAt( i + 1 ) == '\n' ) {
                    i++;
                }
                start = i + 1;
            }
        }
        if ( start < n ) {
            lines.add( body.substring( start ) );
        }
        return lines;
    }

    /** Position after a blockquote marker ({@code >} with up to 3 spaces before, one optional space after), or -1. */
    static int skipBlockquoteMarker( final String line, final int from ) {
        final int ind = countSpaces( line, from, Integer.MAX_VALUE );
        final int p = from + ind;
        if ( ind > MAX_FENCE_INDENT || p >= line.length() || line.charAt( p ) != '>' ) {
            return -1;
        }
        return p + 1 < line.length() && line.charAt( p + 1 ) == ' ' ? p + 2 : p + 1;
    }

    /** End of a list marker at {@code p} (bullet, or 1-9 digits plus {@code .}/{@code )}) followed by space/EOL; else -1. */
    static int listMarkerEnd( final String line, final int p ) {
        final int n = line.length();
        if ( p >= n ) {
            return -1;
        }
        final char c = line.charAt( p );
        final int e;
        if ( c == '-' || c == '*' || c == '+' ) {
            e = p + 1;
        } else if ( c >= '0' && c <= '9' ) {
            e = orderedMarkerEnd( line, p );
        } else {
            return -1;
        }
        return e >= 0 && ( e >= n || line.charAt( e ) == ' ' ) ? e : -1;
    }

    /** End of an ordered-list marker (1-9 digits plus {@code .}/{@code )}) starting at {@code p}, or -1. */
    private static int orderedMarkerEnd( final String line, final int p ) {
        final int n = line.length();
        int e = p;
        while ( e < n && e - p < 10 && line.charAt( e ) >= '0' && line.charAt( e ) <= '9' ) {
            e++;
        }
        if ( e - p > 9 || e >= n || ( line.charAt( e ) != '.' && line.charAt( e ) != ')' ) ) {
            return -1;
        }
        return e + 1;
    }

    static boolean isCloser( final String line, final int p, final char ch, final int minLen ) {
        int e = p;
        while ( e < line.length() && line.charAt( e ) == ch ) {
            e++;
        }
        return e - p >= minLen && isBlank( line, e );
    }

    static int countSpaces( final String line, final int from, final int max ) {
        int p = from;
        while ( p < line.length() && p - from < max && line.charAt( p ) == ' ' ) {
            p++;
        }
        return p - from;
    }

    static boolean isBlank( final String line, final int from ) {
        for ( int p = from; p < line.length(); p++ ) {
            final char c = line.charAt( p );
            if ( c != ' ' && c != '\t' ) {
                return false;
            }
        }
        return true;
    }

    /** Minimal int stack (avoids boxing on the hot path). */
    static final class IntStack {
        private int[] a = new int[ 8 ];
        private int n;

        boolean isEmpty() {
            return n == 0;
        }

        int peek() {
            return a[ n - 1 ];
        }

        void pop() {
            n--;
        }

        void clear() {
            n = 0;
        }

        void push( final int v ) {
            if ( n == a.length ) {
                a = Arrays.copyOf( a, n * 2 );
            }
            a[ n++ ] = v;
        }
    }
}
