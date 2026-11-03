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
 * The editor ({@code obsidianComments.js}) applies the same contract but takes code and math from its syntax tree
 * (CommonMark/GFM structure) rather than this hand mask; the shared fixture {@code comments.json} pins the two together,
 * and a case where this mask and real block structure disagree (#126: table rows, list-item fences) is a defect here.
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
        QuotedFences.mask( text, masked );
        MathMask.displayMath( text, masked );
        MathMask.inlineMath( text, masked );
        return masked;
    }

}
