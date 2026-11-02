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

/**
 * Obsidian {@code %%comments%%}: the one implementation of "a comment is not reader text". A comment (inline, or spanning
 * lines) is removed and replaced by the newlines it held, so line numbers and preview scroll sync stay stable.
 *
 * <ul>
 *   <li><b>Code is never read</b>: fenced and indented code and inline code spans ({@link CodeMask}) — so a Mermaid
 *       fence's own {@code %% comment} lines survive.</li>
 *   <li><b>Math is never read</b>: inline {@code $…$} (Pandoc's rule, as the renderer applies it) and {@code $$ … $$}
 *       blocks, where {@code %} is a LaTeX comment character.</li>
 *   <li>An <b>unterminated</b> {@code %%} stays literal. Obsidian hides everything after an unclosed {@code %%}; a page
 *       is too easy to lose that way.</li>
 *   <li>The closing {@code %%} is the next one after the opener, wherever it falls.</li>
 * </ul>
 *
 * <p>Comments are <em>hidden, not secret</em>: this is a presentation rule. It is never applied to what the access-rule
 * reader sees (an {@code [{ALLOW …}]} inside a comment is still enforced) nor to page history.
 *
 * <p>Linear in the text: a text with no {@code %%} costs one {@code indexOf} and is returned as the very same instance.
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
        if ( text == null || text.indexOf( MARKER ) < 0 ) {
            return text;
        }
        final boolean[] masked = mask( text );
        StringBuilder out = null;
        int copied = 0;
        int at = text.indexOf( MARKER );
        while ( at >= 0 ) {
            if ( masked[ at ] ) {
                at = text.indexOf( MARKER, at + 1 );
                continue;
            }
            final int close = text.indexOf( MARKER, at + MARKER.length() );
            if ( close < 0 ) {
                break;
            }
            if ( out == null ) {
                out = new StringBuilder( text.length() );
            }
            out.append( text, copied, at );
            for ( int p = at; p < close; p++ ) {
                if ( text.charAt( p ) == '\n' ) {
                    out.append( '\n' );
                }
            }
            copied = close + MARKER.length();
            at = text.indexOf( MARKER, copied );
        }
        if ( out == null ) {
            return text;
        }
        return out.append( text, copied, text.length() ).toString();
    }

    /** Offsets inside code or math: where a {@code %%} opens nothing. */
    private static boolean[] mask( final String text ) {
        final boolean[] masked = CodeMask.of( text );
        displayMath( text, masked );
        inlineMath( text, masked );
        return masked;
    }

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

    /** Inline {@code $…$} outside code and display math, by {@link #inlineMathEnd}. */
    private static void inlineMath( final String text, final boolean[] masked ) {
        int i = 0;
        while ( i < text.length() ) {
            i = inlineMathStep( text, masked, i );
        }
    }

    /** Looks at the character at {@code i}, masks the inline math that opens there (if any), and returns where to look next. */
    private static int inlineMathStep( final String text, final boolean[] masked, final int i ) {
        final char c = text.charAt( i );
        if ( c == '\\' ) {
            return i + 2;
        }
        if ( c != '$' || i + 1 < text.length() && text.charAt( i + 1 ) == '$' ) {
            return c == '$' ? i + 2 : i + 1;
        }
        final int end = masked[ i ] ? -1 : inlineMathEnd( text, i );
        if ( end < 0 ) {
            return i + 1;
        }
        for ( int p = i; p < end; p++ ) {
            masked[ p ] = true;
        }
        return end;
    }

    /**
     * Pandoc's {@code tex_math_dollars} for the {@code $} at {@code dollar} — the rule the renderer's inline-math parser
     * and the editor share: the character after must exist and not be whitespace, a backslash consumes the next
     * character, whitespace may not span a blank line or precede the closing {@code $}, and a closer followed by a digit
     * is currency, not math.
     *
     * @return the exclusive end of the span, or -1
     */
    private static int inlineMathEnd( final String s, final int dollar ) {
        final int n = s.length();
        final int first = dollar + 1;
        if ( first >= n || s.charAt( first ) == '$' || Character.isWhitespace( s.charAt( first ) ) ) {
            return -1;
        }
        int newlines = 0;
        boolean prevWhitespace = false;
        int j = first;
        while ( j < n ) {
            final char c = s.charAt( j );
            if ( c == '$' ) {
                return closeEnd( s, j, prevWhitespace );
            }
            if ( c == '\\' ) {
                if ( j + 1 >= n ) {
                    return -1;
                }
                newlines = 0;
                prevWhitespace = false;
                j += 2;
                continue;
            }
            final boolean ws = Character.isWhitespace( c );
            newlines = newlinesAfter( c, ws, newlines );
            if ( newlines >= 2 ) {
                return -1;
            }
            prevWhitespace = ws;
            j++;
        }
        return -1;
    }

    /** Consecutive-newline count: whitespace keeps it (a newline adds one), anything else resets it. */
    private static int newlinesAfter( final char c, final boolean whitespace, final int newlines ) {
        if ( !whitespace ) {
            return 0;
        }
        return c == '\n' ? newlines + 1 : newlines;
    }

    /** The end of a span closed by the {@code $} at {@code j}: none when whitespace precedes it or a digit follows it. */
    private static int closeEnd( final String s, final int j, final boolean prevWhitespace ) {
        final boolean digitFollows = j + 1 < s.length() && s.charAt( j + 1 ) >= '0' && s.charAt( j + 1 ) <= '9';
        return prevWhitespace || digitFollows ? -1 : j + 1;
    }
}
