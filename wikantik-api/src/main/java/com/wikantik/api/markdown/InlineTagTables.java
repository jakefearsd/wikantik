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

import java.util.regex.Pattern;

/** GFM table rows for {@link InlineTagSyntax}: a cell's text starts a fresh inline run, so its pipes act as boundaries. */
final class InlineTagTables {

    private static final Pattern TABLE_DELIMITER = Pattern.compile( "^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$" );

    private InlineTagTables() {
    }

    /**
     * In a GFM table a cell's text starts a fresh inline run, so {@code |#foo|} holds a tag. Turns the unescaped pipes of
     * table rows into spaces (same length) so the usual "after whitespace" rule sees the cell start.
     */
    static String openTableCells( final String s ) {
        if ( s.indexOf( '|' ) < 0 ) {
            return s;
        }
        final String[] lines = s.split( "\n", -1 );
        boolean inTable = false;
        for ( int i = 0; i < lines.length; i++ ) {
            final boolean delimiter = i > 0 && !inTable && lines[ i ].indexOf( '|' ) >= 0 && lines[ i ].indexOf( '-' ) >= 0
                    && TABLE_DELIMITER.matcher( lines[ i ] ).matches()
                    && lines[ i - 1 ].indexOf( '|' ) >= 0 && cellCount( lines[ i - 1 ] ) == cellCount( lines[ i ] );
            if ( delimiter ) {
                lines[ i - 1 ] = pipesToSpaces( lines[ i - 1 ] );
                inTable = true;
            } else if ( inTable && !lines[ i ].isBlank() ) {
                lines[ i ] = pipesToSpaces( lines[ i ] );
            } else {
                inTable = false;
            }
        }
        return String.join( "\n", lines );
    }

    private static int cellCount( final String row ) {
        String t = row.strip();
        if ( t.startsWith( "|" ) ) {
            t = t.substring( 1 );
        }
        if ( t.endsWith( "|" ) && !t.endsWith( "\\|" ) ) {
            t = t.substring( 0, t.length() - 1 );
        }
        int cells = 1;
        for ( int i = 0; i < t.length(); i++ ) {
            if ( t.charAt( i ) == '\\' ) {
                i++;
            } else if ( t.charAt( i ) == '|' ) {
                cells++;
            }
        }
        return cells;
    }

    private static String pipesToSpaces( final String row ) {
        final StringBuilder b = new StringBuilder( row );
        for ( int i = 0; i < b.length(); i++ ) {
            if ( b.charAt( i ) == '\\' ) {
                i++;
            } else if ( b.charAt( i ) == '|' ) {
                b.setCharAt( i, ' ' );
            }
        }
        return b.toString();
    }
}
