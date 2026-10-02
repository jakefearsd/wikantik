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

import java.util.regex.Pattern;

/**
 * Marks the offsets of a Markdown body that fall inside code: fenced and indented code blocks ({@code ```} / {@code ~~~},
 * including {@code ```math}) and inline backtick spans. Port of the math {@code CodeRegions} scan.
 */
final class CodeMask {

    private static final Pattern LIST_ITEM = Pattern.compile( "^\\s*([-*+]|\\d{1,9}[.)])\\s.*" );

    private final boolean[] masked;
    private char fenceChar;
    private int fenceLen;
    private boolean inIndented;
    private boolean sawBlank;
    private String lastNonBlank;

    private CodeMask( final int length ) {
        this.masked = new boolean[ length ];
    }

    static boolean[] of( final String body ) {
        final CodeMask mask = new CodeMask( body.length() );
        int offset = 0;
        for ( final String line : body.split( "\n", -1 ) ) {
            mask.scanLine( line, offset );
            offset += line.length() + 1;
        }
        return mask.masked;
    }

    private void scanLine( final String line, final int offset ) {
        if ( fenceLen > 0 ) {
            maskRange( masked, offset, offset + line.length() );
            if ( closesFence( line ) ) {
                fenceLen = 0;
            }
        } else if ( line.isBlank() ) {
            sawBlank = true;
        } else if ( isIndentedCode( line ) ) {
            inIndented = true;
            sawBlank = false;
            maskRange( masked, offset, offset + line.length() );
        } else {
            scanProse( line, offset );
        }
    }

    private void scanProse( final String line, final int offset ) {
        inIndented = false;
        sawBlank = false;
        lastNonBlank = line;
        if ( opensFence( line ) ) {
            maskRange( masked, offset, offset + line.length() );
        } else {
            maskInlineCode( masked, line, offset );
        }
    }

    private boolean isIndentedCode( final String line ) {
        if ( indent( line ) < 4 ) {
            return false;
        }
        if ( inIndented ) {
            return true;
        }
        if ( lastNonBlank == null ) {
            return true;
        }
        return sawBlank && !LIST_ITEM.matcher( lastNonBlank ).matches() && indent( lastNonBlank ) < 2;
    }

    /** Indent width: spaces count 1, a tab counts as 4. */
    private static int indent( final String line ) {
        int w = 0;
        for ( int i = 0; i < line.length(); i++ ) {
            final char c = line.charAt( i );
            if ( c == ' ' ) {
                w++;
            } else if ( c == '\t' ) {
                w += 4;
            } else {
                break;
            }
        }
        return w;
    }

    private static int leadingSpaces( final String line ) {
        int i = 0;
        while ( i < line.length() && line.charAt( i ) == ' ' ) {
            i++;
        }
        return i;
    }

    private static int runLength( final String line, final int from, final char c ) {
        int k = from;
        while ( k < line.length() && line.charAt( k ) == c ) {
            k++;
        }
        return k - from;
    }

    /** Opens a fence when the line is a (max 3-space indented) run of 3+ backticks or tildes. */
    private boolean opensFence( final String line ) {
        final int ind = leadingSpaces( line );
        if ( ind > 3 || ind >= line.length() ) {
            return false;
        }
        final char c = line.charAt( ind );
        if ( c != '`' && c != '~' ) {
            return false;
        }
        final int len = runLength( line, ind, c );
        if ( len < 3 || c == '`' && line.indexOf( '`', ind + len ) >= 0 ) {
            return false;
        }
        fenceChar = c;
        fenceLen = len;
        return true;
    }

    private boolean closesFence( final String line ) {
        final int ind = leadingSpaces( line );
        if ( ind > 3 || ind >= line.length() || line.charAt( ind ) != fenceChar ) {
            return false;
        }
        final int len = runLength( line, ind, fenceChar );
        return len >= fenceLen && line.substring( ind + len ).isBlank();
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
            final int n = runLength( line, i, '`' );
            i += n;
            final int close = findClosingRun( line, i, n );
            if ( close >= 0 ) {
                maskRange( masked, base + runStart, base + close + n );
                i = close + n;
            }
        }
    }

    /** Index of the next backtick run of exactly {@code n} at or after {@code from}, or -1. */
    private static int findClosingRun( final String line, final int from, final int n ) {
        int j = from;
        while ( j < line.length() ) {
            if ( line.charAt( j ) != '`' ) {
                j++;
                continue;
            }
            final int m = runLength( line, j, '`' );
            if ( m == n ) {
                return j;
            }
            j += m;
        }
        return -1;
    }
}
