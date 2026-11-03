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
 * The pure string reading of GFM table rows (quote depth, content past the markers, unescaped pipes, cells). Serves
 * {@link GfmTableLines}, and through it {@link ObsidianComments#mask}.
 */
final class GfmTableRows {
    private static final Pattern BLOCK_START = Pattern.compile( "^ {0,3}(#{1,6}([ \\t]|$)|```|~~~|([-*+]|\\d{1,9}[.)])([ \\t]|$))" );

    private GfmTableRows() {
    }

    static boolean continues( final String line, final int depth ) {
        final String content = content( line );
        return quotes( line ) == depth && !content.isBlank() && hasPipe( content ) && !BLOCK_START.matcher( content ).find();
    }

    /** The line past its quote markers and indent. */
    static String content( final String line ) {
        int p = 0;
        while ( p < line.length() && ( line.charAt( p ) == ' ' || line.charAt( p ) == '>' || line.charAt( p ) == '\t' ) ) {
            p++;
        }
        return line.substring( p );
    }

    static int quotes( final String line ) {
        int levels = 0;
        for ( int p = 0; p < line.length() && ( line.charAt( p ) == ' ' || line.charAt( p ) == '>' || line.charAt( p ) == '\t' ); p++ ) {
            if ( line.charAt( p ) == '>' ) {
                levels++;
            }
        }
        return levels;
    }

    static boolean hasPipe( final String content ) {
        return unescapedPipe( content, 0 ) >= 0;
    }

    static int unescapedPipe( final String s, final int from ) {
        for ( int p = from; p < s.length(); p++ ) {
            if ( s.charAt( p ) == '\\' ) {
                p++;
            } else if ( s.charAt( p ) == '|' ) {
                return p;
            }
        }
        return -1;
    }

    /** The cells of a row, outer pipes optional. */
    static List< String > cells( final String row ) {
        final List< String > out = new ArrayList<>();
        final String trimmed = row.strip();
        int from = trimmed.startsWith( "|" ) ? 1 : 0;
        for ( int bar = unescapedPipe( trimmed, from ); bar >= 0; bar = unescapedPipe( trimmed, from ) ) {
            out.add( trimmed.substring( from, bar ) );
            from = bar + 1;
        }
        if ( from < trimmed.length() ) {
            out.add( trimmed.substring( from ) );
        }
        return out;
    }
}
