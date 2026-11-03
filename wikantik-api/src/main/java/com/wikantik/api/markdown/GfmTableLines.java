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
package com.wikantik.api.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Serves {@link ObsidianComments#mask}: the lines of {@code text} that belong to a GFM table block, computed once
 * (linear). A table starts at a line with an unescaped {@code |} immediately followed by a delimiter row of the same cell count, and
 * continues through each following line that holds an unescaped {@code |}, up to a blank line, a pipe-less line (the reader — flexmark —
 * ends the table there, so it is a paragraph) or a line that begins another block (heading, fence, quote, list item).
 * Cells are separate inline contexts, so math inside a table never crosses a line break nor an unescaped {@code |}.
 */
final class GfmTableLines {
    private static final Pattern DELIMITER_CELL = Pattern.compile( "\\s*:?-+:?\\s*" );

    private final String text;
    private final int[] starts;
    private final boolean[] inTable;

    GfmTableLines( final String text, final boolean[] masked ) {
        this.text = text;
        final List< Integer > lineStarts = new ArrayList<>();
        lineStarts.add( 0 );
        for ( int p = text.indexOf( '\n' ); p >= 0; p = text.indexOf( '\n', p + 1 ) ) {
            lineStarts.add( p + 1 );
        }
        this.starts = lineStarts.stream().mapToInt( Integer::intValue ).toArray();
        this.inTable = new boolean[ starts.length ];
        if ( text.indexOf( '|' ) >= 0 ) {
            detect( masked );
        }
    }

    private String line( final int n ) {
        final int end = n + 1 < starts.length ? starts[ n + 1 ] - 1 : text.length();
        return text.substring( starts[ n ], end );
    }

    private void detect( final boolean[] masked ) {
        int n = 0;
        while ( n + 1 < starts.length ) {
            if ( !masked[ Math.min( starts[ n ], masked.length - 1 ) ] && startsTable( n ) ) {
                final int depth = GfmTableRows.quotes( line( n ) );
                inTable[ n ] = true;
                int k = n + 1;
                inTable[ k ] = true;
                k++;
                while ( k < starts.length && GfmTableRows.continues( line( k ), depth ) ) {
                    inTable[ k ] = true;
                    k++;
                }
                n = k;
            } else {
                n++;
            }
        }
    }

    private boolean startsTable( final int n ) {
        final String head = GfmTableRows.content( line( n ) );
        final String delimiter = GfmTableRows.content( line( n + 1 ) );
        if ( GfmTableRows.quotes( line( n ) ) != GfmTableRows.quotes( line( n + 1 ) ) || !GfmTableRows.hasPipe( head ) || delimiter.isBlank() ) {
            return false;
        }
        final List< String > delimiterCells = GfmTableRows.cells( delimiter );
        if ( delimiterCells.size() != GfmTableRows.cells( head ).size() ) {
            return false;
        }
        for ( final String cell : delimiterCells ) {
            if ( !DELIMITER_CELL.matcher( cell ).matches() ) {
                return false;
            }
        }
        return delimiterCells.size() > 1 || GfmTableRows.hasPipe( delimiter );
    }

    private int lineOf( final int offset ) {
        final int at = java.util.Arrays.binarySearch( starts, offset );
        return at >= 0 ? at : -at - 2;
    }

    /** Whether the math span {@code [from, end)} would cross a line break or a cell border of a table block. */
    boolean splits( final int from, final int end ) {
        final int first = lineOf( from );
        final int last = lineOf( end - 1 );
        for ( int n = first; n <= last; n++ ) {
            if ( inTable[ n ] && ( n != first || n != last ) ) {
                return true;
            }
        }
        if ( inTable[ first ] ) {
            return GfmTableRows.unescapedPipe( text.substring( from, end ), 0 ) >= 0;
        }
        return false;
    }
}
