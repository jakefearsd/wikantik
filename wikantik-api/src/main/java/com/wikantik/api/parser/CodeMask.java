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
public final class CodeMask {

    private static final Pattern LIST_ITEM = Pattern.compile( "^\\s*([-*+]|\\d{1,9}[.)])\\s.*" );

    private final boolean[] masked;
    /** Offsets inside a fenced block only (fence lines included), a subset of {@link #masked}. */
    private final boolean[] fenced;
    private char fenceChar;
    private int fenceLen;
    private boolean inIndented;
    private boolean sawBlank;
    private String lastNonBlank;
    /** Content column of the innermost open list item (0 when not in a list); fences may be indented relative to it. */
    private int listContentIndent;
    /** Indent that the open fence's own indent was measured against (0, or the list content column). */
    private int fenceBase;

    private CodeMask( final int length ) {
        this.masked = new boolean[ length ];
        this.fenced = new boolean[ length ];
    }

    /** Marks every offset inside code: fenced blocks, indented code blocks and inline backtick spans. */
    public static boolean[] of( final String body ) {
        return scan( body ).masked;
    }

    /** Marks only the offsets inside fenced code blocks (fence lines included); indented code and inline spans are not marked. */
    public static boolean[] fenced( final String body ) {
        return scan( body ).fenced;
    }

    private static CodeMask scan( final String body ) {
        final CodeMask mask = new CodeMask( body.length() );
        int offset = 0;
        for ( final String line : body.split( "\n", -1 ) ) {
            mask.scanLine( line, offset );
            offset += line.length() + 1;
        }
        return mask;
    }

    private void scanLine( final String line, final int offset ) {
        if ( fenceLen > 0 ) {
            maskRange( masked, offset, offset + line.length() );
            maskRange( fenced, offset, offset + line.length() );
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
        trackListContext( line );
        if ( opensFence( line ) ) {
            maskRange( masked, offset, offset + line.length() );
            maskRange( fenced, offset, offset + line.length() );
        } else {
            maskInlineCode( masked, line, offset );
        }
    }

    private void trackListContext( final String line ) {
        final java.util.regex.Matcher m = LIST_ITEM.matcher( line );
        if ( m.matches() ) {
            listContentIndent = m.end( 1 ) + 1;
        } else if ( indent( line ) < listContentIndent ) {
            listContentIndent = 0;
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

    /** Opens a fence when the line is a (max 3-space indented, relative to the list content column inside a list) run of 3+ backticks or tildes. */
    private boolean opensFence( final String line ) {
        final int ind = leadingSpaces( line );
        final int base = ind > 3 && listContentIndent > 0 && ind >= listContentIndent ? listContentIndent : 0;
        if ( ind - base > 3 || ind >= line.length() ) {
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
        fenceBase = base;
        return true;
    }

    private boolean closesFence( final String line ) {
        final int ind = leadingSpaces( line );
        if ( ind - fenceBase > 3 || ind >= line.length() || line.charAt( ind ) != fenceChar ) {
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

    /**
     * Masks paired backtick runs (a run of N backticks closed by the next run of exactly N). The partner of every
     * run is precomputed right-to-left, so a line of many unmatched runs is scanned in linear time instead of each
     * opener rescanning the rest of the line.
     */
    private static void maskInlineCode( final boolean[] masked, final String line, final int base ) {
        final java.util.List< int[] > runs = backtickRuns( line );
        final int[] partner = new int[ runs.size() ];
        final java.util.Map< Integer, Integer > nextOfLength = new java.util.HashMap<>();
        for ( int r = runs.size() - 1; r >= 0; r-- ) {
            final Integer next = nextOfLength.put( runs.get( r )[ 1 ], r );
            partner[ r ] = next == null ? -1 : next;
        }
        int r = 0;
        while ( r < runs.size() ) {
            final int close = partner[ r ];
            if ( close < 0 ) {
                r++;
                continue;
            }
            maskRange( masked, base + runs.get( r )[ 0 ], base + runs.get( close )[ 0 ] + runs.get( close )[ 1 ] );
            r = close + 1;
        }
    }

    /** Every maximal backtick run of {@code line} as {@code {start, length}}, in order. */
    private static java.util.List< int[] > backtickRuns( final String line ) {
        final java.util.List< int[] > runs = new java.util.ArrayList<>();
        int i = line.indexOf( '`' );
        while ( i >= 0 ) {
            final int n = runLength( line, i, '`' );
            runs.add( new int[] { i, n } );
            i = line.indexOf( '`', i + n );
        }
        return runs;
    }
}
