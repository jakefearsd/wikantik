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
package com.wikantik.api.parser;

/**
 * Marks the offsets of a Markdown body that fall inside code: fenced blocks ({@code ```} / {@code ~~~},
 * including {@code ```math}) and inline backtick spans. Port of the math {@code CodeRegions} scan.
 */
final class CodeMask {

    private CodeMask() {
    }


    static boolean[] of( final String body ) {
        final boolean[] masked = new boolean[ body.length() ];
        int offset = 0;
        String fence = null;
        for ( final String line : body.split( "\n", -1 ) ) {
            final String trimmed = line.strip();
            final String marker = fenceMarker( trimmed );
            if ( fence == null && marker != null ) {
                fence = marker;
                maskRange( masked, offset, offset + line.length() );
            } else if ( fence != null ) {
                maskRange( masked, offset, offset + line.length() );
                if ( marker != null && trimmed.startsWith( fence ) ) {
                    fence = null;
                }
            } else {
                maskInlineCode( masked, line, offset );
            }
            offset += line.length() + 1;
        }
        return masked;
    }

    private static String fenceMarker( final String trimmed ) {
        if ( trimmed.startsWith( "```" ) ) {
            return "```";
        }
        return trimmed.startsWith( "~~~" ) ? "~~~" : null;
    }

    private static void maskRange( final boolean[] masked, final int from, final int to ) {
        for ( int p = from; p < to && p < masked.length; p++ ) {
            masked[ p ] = true;
        }
    }

    /** Masks paired backtick runs (a run of N backticks closed by a run of exactly N). */
    private static void maskInlineCode( final boolean[] masked, final String line, final int base ) {
        int i = 0;
        while ( i < line.length() ) {
            if ( line.charAt( i ) != '`' ) {
                i++;
                continue;
            }
            final int runStart = i;
            final int n = runLength( line, i );
            i += n;
            final int close = findClosingRun( line, i, n );
            if ( close >= 0 ) {
                maskRange( masked, base + runStart, base + close + n );
                i = close + n;
            }
        }
    }

    private static int runLength( final String line, final int from ) {
        int k = from;
        while ( k < line.length() && line.charAt( k ) == '`' ) {
            k++;
        }
        return k - from;
    }

    /** Index of the next backtick run of exactly {@code n} at or after {@code from}, or -1. */
    private static int findClosingRun( final String line, final int from, final int n ) {
        int j = from;
        while ( j < line.length() ) {
            if ( line.charAt( j ) != '`' ) {
                j++;
                continue;
            }
            final int m = runLength( line, j );
            if ( m == n ) {
                return j;
            }
            j += m;
        }
        return -1;
    }
}
