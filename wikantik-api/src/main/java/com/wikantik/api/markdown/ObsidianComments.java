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

import com.wikantik.api.parser.CodeMask;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Obsidian {@code %%comments%%}: the one implementation of "a comment is not reader text". A comment (inline, or spanning
 * lines) is removed and replaced by the newlines it held, so line numbers and preview scroll sync stay stable.
 *
 * <ul>
 *   <li><b>Code is never read</b>, neither for an opening nor for a closing {@code %%}: fenced and indented code and
 *       inline code spans ({@link CodeMask}), and fences inside block quotes and callouts — so a Mermaid fence's own
 *       {@code %% comment} lines survive, and a {@code %%} before such a fence never closes on one of them.</li>
 *   <li><b>Math is never read</b>: inline {@code $…$} ({@link InlineMath}), inline {@code $$…$$} within a paragraph, and
 *       {@code $$ … $$} blocks, where {@code %} is a LaTeX comment character.</li>
 *   <li>An <b>unterminated</b> {@code %%} stays literal. Obsidian hides everything after an unclosed {@code %%}; a page
 *       is too easy to lose that way.</li>
 *   <li>The closing {@code %%} is the next one after the opener outside code and math. Code wins: a backtick inside a
 *       comment that pairs with a later one makes a code span, which can leave the comment unterminated.</li>
 * </ul>
 *
 * <p>Comments are <em>hidden, not secret</em>: this is a presentation rule. It is never applied to what the access-rule
 * reader sees nor to page history an editor reads. So an access rule inside a comment is not "commented out": the reader
 * still reads it, and it locks the page to administrators ({@code AccessRules.Shape.IN_COMMENT}) rather than take effect
 * unseen; the save check refuses one. Where removing a comment changes the block structure around a rule (a comment-only
 * line becomes blank, so an indented rule after it is code to the render), the page locks too.
 *
 * <p>Linear in the text: a text with no {@code %%} costs one {@code indexOf} and is returned as the very same instance.
 * The editor's copy, {@code obsidianComments.js}, follows the same rules, pinned by the shared fixture
 * {@code comments.json}.
 */
public final class ObsidianComments {

    private static final String MARKER = "%%";

    private ObsidianComments() {
    }

    /**
     * @param text page text or body; may be {@code null}
     * @return the text without its comments (newlines kept); the same instance when nothing was removed
     */
    public static String strip( final String text ) {
        final List< int[] > ranges = ranges( text );
        if ( ranges.isEmpty() ) {
            return text;
        }
        final StringBuilder out = new StringBuilder( text.length() );
        int copied = 0;
        for ( final int[] r : ranges ) {
            out.append( text, copied, r[ 0 ] );
            for ( int p = r[ 0 ]; p < r[ 1 ]; p++ ) {
                if ( text.charAt( p ) == '\n' ) {
                    out.append( '\n' );
                }
            }
            copied = r[ 1 ];
        }
        return out.append( text, copied, text.length() ).toString();
    }

    /**
     * @param text page text or body; may be {@code null}
     * @return each comment's {@code [from, to)}, delimiters included, in order (an unterminated {@code %%} is none)
     */
    public static List< int[] > ranges( final String text ) {
        final List< int[] > out = new ArrayList<>();
        if ( text == null || text.indexOf( MARKER ) < 0 ) {
            return out;
        }
        final boolean[] masked = mask( text );
        int at = next( text, masked, 0 );
        while ( at >= 0 ) {
            final int close = next( text, masked, at + MARKER.length() );
            if ( close < 0 ) {
                break;
            }
            out.add( new int[]{ at, close + MARKER.length() } );
            at = next( text, masked, close + MARKER.length() );
        }
        return out;
    }

    /** The next {@code %%} at or after {@code from} with neither of its characters in code or math, or -1. */
    private static int next( final String text, final boolean[] masked, final int from ) {
        int at = text.indexOf( MARKER, from );
        while ( at >= 0 && ( masked[ at ] || masked[ at + 1 ] ) ) {
            at = text.indexOf( MARKER, at + 1 );
        }
        return at;
    }

    /** Offsets inside code or math: where a {@code %%} opens or closes nothing. */
    static boolean[] mask( final String text ) {
        final boolean[] masked = CodeMask.of( text );
        quotedFences( text, masked );
        displayMath( text, masked );
        inlineMath( text, masked );
        return masked;
    }

    // ---- fences inside block quotes ------------------------------------------------------------------------------------

    /**
     * Fences opened inside a block quote (a callout is one), which {@link CodeMask} reads as text: from the opening line to
     * the closing one at the same quote depth, or to the first line that leaves that depth. Lines already code at the top
     * level are left as they are.
     */
    private static void quotedFences( final String text, final boolean[] masked ) {
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

    // ---- math ---------------------------------------------------------------------------------------------------------

    /** A line of only {@code $$} opens a display block that the next such line closes (code lines excepted). */
    private static void displayMath( final String text, final boolean[] masked ) {
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
    private static void inlineMath( final String text, final boolean[] masked ) {
        final TableLines tables = new TableLines( text, masked );
        int i = 0;
        while ( i < text.length() ) {
            i = inlineMathStep( text, masked, tables, i );
        }
    }

    /** Looks at the character at {@code i}, masks the inline math that opens there (if any), and returns where to look next. */
    private static int inlineMathStep( final String text, final boolean[] masked, final TableLines tables, final int i ) {
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
     * The lines of {@code text} that belong to a GFM table block, computed once (linear). A table starts at a line with an
     * unescaped {@code |} immediately followed by a delimiter row of the same cell count, and continues through each
     * following line that holds an unescaped {@code |}, up to a blank line, a pipe-less line (the reader — flexmark —
     * ends the table there, so it is a paragraph) or a line that begins another block (heading, fence, quote, list item).
     * Cells are separate inline contexts, so math inside a table never crosses a line break nor an unescaped {@code |}.
     */
    private static final class TableLines {
        private static final Pattern DELIMITER_CELL = Pattern.compile( "\\s*:?-+:?\\s*" );
        private static final Pattern BLOCK_START = Pattern.compile( "^ {0,3}(#{1,6}([ \\t]|$)|```|~~~|([-*+]|\\d{1,9}[.)])([ \\t]|$))" );

        private final String text;
        private final int[] starts;
        private final boolean[] inTable;

        TableLines( final String text, final boolean[] masked ) {
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
                    final int depth = quotes( line( n ) );
                    inTable[ n ] = true;
                    int k = n + 1;
                    inTable[ k ] = true;
                    k++;
                    while ( k < starts.length && continues( line( k ), depth ) ) {
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
            final String head = content( line( n ) );
            final String delimiter = content( line( n + 1 ) );
            if ( quotes( line( n ) ) != quotes( line( n + 1 ) ) || !hasPipe( head ) || delimiter.isBlank() ) {
                return false;
            }
            final List< String > delimiterCells = cells( delimiter );
            if ( delimiterCells.size() != cells( head ).size() ) {
                return false;
            }
            for ( final String cell : delimiterCells ) {
                if ( !DELIMITER_CELL.matcher( cell ).matches() ) {
                    return false;
                }
            }
            return delimiterCells.size() > 1 || hasPipe( delimiter );
        }

        private static boolean continues( final String line, final int depth ) {
            final String content = content( line );
            return quotes( line ) == depth && !content.isBlank() && hasPipe( content ) && !BLOCK_START.matcher( content ).find();
        }

        /** The line past its quote markers and indent. */
        private static String content( final String line ) {
            int p = 0;
            while ( p < line.length() && ( line.charAt( p ) == ' ' || line.charAt( p ) == '>' || line.charAt( p ) == '\t' ) ) {
                p++;
            }
            return line.substring( p );
        }

        private static int quotes( final String line ) {
            int levels = 0;
            for ( int p = 0; p < line.length() && ( line.charAt( p ) == ' ' || line.charAt( p ) == '>' || line.charAt( p ) == '\t' ); p++ ) {
                if ( line.charAt( p ) == '>' ) {
                    levels++;
                }
            }
            return levels;
        }

        private static boolean hasPipe( final String content ) {
            return unescapedPipe( content, 0 ) >= 0;
        }

        private static int unescapedPipe( final String s, final int from ) {
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
        private static List< String > cells( final String row ) {
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
                return unescapedPipe( text.substring( from, end ), 0 ) >= 0;
            }
            return false;
        }
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
