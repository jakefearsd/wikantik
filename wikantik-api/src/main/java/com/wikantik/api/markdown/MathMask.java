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

/**
 * Masks math ({@code $$} display blocks, inline {@code $…$} and {@code $$…$$}) so a {@code %%} inside it opens or closes
 * nothing. Serves {@link ObsidianComments#mask}.
 */
final class MathMask {

    private MathMask() {
    }

    /** A line of only {@code $$} opens a display block that the next such line closes (code lines excepted). */
    static void displayMath( final String text, final boolean[] masked ) {
        boolean open = false;
        int offset = 0;
        while ( offset <= text.length() ) {
            int end = text.indexOf( '\n', offset );
            if ( end < 0 ) {
                end = text.length();
            }
            final boolean fence = !open && masked[ Math.min( offset, masked.length - 1 ) ] && end > offset;
            final boolean delimiter = !fence && "$$".equals( text.substring( offset, end ).strip() );
            if ( open || delimiter ) {
                for ( int p = offset; p < end; p++ ) {
                    masked[ p ] = true;
                }
            }
            if ( delimiter ) {
                open = !open;
            }
            offset = end + 1;
        }
    }

    /** Inline {@code $…$} and {@code $$…$$} outside code and display math. */
    static void inlineMath( final String text, final boolean[] masked ) {
        final GfmTableLines tables = new GfmTableLines( text, masked );
        int i = 0;
        while ( i < text.length() ) {
            i = inlineMathStep( text, masked, tables, i );
        }
    }

    /** Looks at the character at {@code i}, masks the inline math that opens there (if any), and returns where to look next. */
    private static int inlineMathStep( final String text, final boolean[] masked, final GfmTableLines tables, final int i ) {
        final char c = text.charAt( i );
        if ( c == '\\' ) {
            return i + 2;
        }
        if ( c != '$' || masked[ i ] ) {
            return i + 1;
        }
        final boolean twice = i + 1 < text.length() && text.charAt( i + 1 ) == '$';
        final int end = twice ? inlineDisplayEnd( text, masked, i ) : InlineMath.matchEnd( text, i );
        if ( end < 0 || tables.splits( i, end ) ) {
            return twice ? i + 2 : i + 1;
        }
        for ( int p = i; p < end; p++ ) {
            masked[ p ] = true;
        }
        return end;
    }

    /**
     * The exclusive end of {@code $$…$$} opening at {@code i} inside a paragraph: the next {@code $$} outside code, before
     * any blank line; or -1.
     */
    private static int inlineDisplayEnd( final String text, final boolean[] masked, final int i ) {
        boolean lineBlank = false;
        for ( int j = i + 2; j < text.length(); j++ ) {
            final char c = text.charAt( j );
            if ( c == '\n' ) {
                if ( lineBlank ) {
                    return -1;
                }
                lineBlank = true;
            } else if ( c == '\\' ) {
                j++;
                lineBlank = false;
            } else if ( c == '$' && j + 1 < text.length() && text.charAt( j + 1 ) == '$' && !masked[ j ] ) {
                return j + 2;
            } else if ( c != ' ' && c != '\t' && c != '\r' ) {
                lineBlank = false;
            }
        }
        return -1;
    }
}
