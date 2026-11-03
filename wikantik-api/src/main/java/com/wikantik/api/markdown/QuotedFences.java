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
 * Masks fences opened inside block quotes (a callout is one), which {@code CodeMask} reads as text. Serves
 * {@link ObsidianComments#mask}.
 */
final class QuotedFences {

    private QuotedFences() {
    }

    /**
     * Fences opened inside a block quote (a callout is one), which {@link CodeMask} reads as text: from the opening line to
     * the closing one at the same quote depth, or to the first line that leaves that depth. Lines already code at the top
     * level are left as they are.
     */
    static void mask( final String text, final boolean[] masked ) {
        if ( text.indexOf( '>' ) < 0 ) {
            return;
        }
        final QuotedFence fence = new QuotedFence();
        int offset = 0;
        while ( offset <= text.length() ) {
            int end = text.indexOf( '\n', offset );
            if ( end < 0 ) {
                end = text.length();
            }
            if ( fence.scanLine( text, offset, end, masked ) ) {
                for ( int p = offset; p < end; p++ ) {
                    masked[ p ] = true;
                }
            }
            offset = end + 1;
        }
    }

    /** The one fence open inside a quote, if any, while the lines are scanned. */
    private static final class QuotedFence {
        private char fenceChar;
        private int fenceLen;
        private int depth;

        /** Reads line {@code [start, end)}; whether it belongs to a quoted fence. */
        boolean scanLine( final String text, final int start, final int end, final boolean[] masked ) {
            if ( fenceLen > 0 ) {
                final int content = quoteContent( text, start, end, depth );
                if ( content >= 0 ) {
                    if ( closes( text, content, end ) ) {
                        fenceLen = 0;
                    }
                    return true;
                }
                fenceLen = 0;   // the quote ended, and the fence with it
            }
            if ( start >= end || masked[ start ] ) {
                return false;
            }
            final int levels = quoteDepth( text, start, end );
            if ( levels == 0 ) {
                return false;
            }
            return opens( text, quoteContent( text, start, end, levels ), end, levels );
        }

        private boolean opens( final String text, final int content, final int end, final int levels ) {
            final int at = skipSpaces( text, content, end, 3 );
            if ( at < 0 || at >= end || text.charAt( at ) != '`' && text.charAt( at ) != '~' ) {
                return false;
            }
            final char c = text.charAt( at );
            final int len = run( text, at, end, c );
            if ( len < 3 || c == '`' && text.indexOf( '`', at + len ) >= 0 && text.indexOf( '`', at + len ) < end ) {
                return false;
            }
            fenceChar = c;
            fenceLen = len;
            depth = levels;
            return true;
        }

        private boolean closes( final String text, final int content, final int end ) {
            final int at = skipSpaces( text, content, end, 3 );
            if ( at < 0 || at >= end || text.charAt( at ) != fenceChar ) {
                return false;
            }
            final int len = run( text, at, end, fenceChar );
            return len >= fenceLen && text.substring( at + len, end ).isBlank();
        }
    }

    /** How many quote levels ({@code >} after at most three spaces, each) line {@code [start, end)} opens with. */
    private static int quoteDepth( final String text, final int start, final int end ) {
        int levels = 0;
        int p = start;
        while ( true ) {
            final int at = skipSpaces( text, p, end, 3 );
            if ( at < 0 || at >= end || text.charAt( at ) != '>' ) {
                return levels;
            }
            levels++;
            p = at + 1 < end && text.charAt( at + 1 ) == ' ' ? at + 2 : at + 1;
        }
    }

    /** Where the content after {@code levels} quote markers starts, or -1 when the line has fewer. */
    private static int quoteContent( final String text, final int start, final int end, final int levels ) {
        int p = start;
        for ( int level = 0; level < levels; level++ ) {
            final int at = skipSpaces( text, p, end, 3 );
            if ( at < 0 || at >= end || text.charAt( at ) != '>' ) {
                return -1;
            }
            p = at + 1 < end && text.charAt( at + 1 ) == ' ' ? at + 2 : at + 1;
        }
        return p;
    }

    /** {@code from} past at most {@code max} spaces; -1 when more follow. */
    private static int skipSpaces( final String text, final int from, final int end, final int max ) {
        int p = from;
        while ( p < end && text.charAt( p ) == ' ' ) {
            p++;
        }
        return p - from > max ? -1 : p;
    }

    private static int run( final String text, final int from, final int end, final char c ) {
        int p = from;
        while ( p < end && text.charAt( p ) == c ) {
            p++;
        }
        return p - from;
    }
}
