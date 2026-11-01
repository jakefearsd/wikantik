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
package com.wikantik.auth.acl;

import com.wikantik.util.AclDirectiveScanner;

import java.util.TreeMap;

/**
 * Where {@link AccessRules} looks for access rules: every span of a page text either old reading could take for one, in
 * linear time.
 */
final class DirectiveCandidates {

    private static final String OPENER = "[{";
    private static final String CLOSER = "}]";
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    private DirectiveCandidates() {
    }

    /**
     * Every candidate span, start to end: what the old text scan matches anywhere (with {@code DENY}), and every
     * <code>[{</code> whose first word starts {@code allow}/{@code deny} in any case up to the first <code>}]</code>
     * with no blank line between, in the frontmatter and the body each on its own.
     */
    static TreeMap< Integer, Integer > find( final String text, final int bodyStart ) {
        final TreeMap< Integer, Integer > out = new TreeMap<>();
        for ( final AclDirectiveScanner.Span span : AclDirectiveScanner.findAll( text, true, false ) ) {
            out.put( span.start(), AclDirectiveScanner.endWithEmptyParentheses( text, span ) );
        }
        shaped( text, 0, bodyStart, out );
        shaped( text, bodyStart, text.length(), out );
        return out;
    }

    /** The any-case, wrapped-or-not openers of {@code text[from, to)}, each up to its first closer in the region. */
    private static void shaped( final String text, final int from, final int to, final TreeMap< Integer, Integer > out ) {
        final Region region = new Region( text, to );
        int opener = text.indexOf( OPENER, from );
        while ( opener >= 0 && opener < to ) {
            final int keyword = skipWhitespace( text, opener + OPENER.length(), to );
            if ( keywordShaped( text, keyword ) ) {
                final int closer = region.closerAfter( keyword );
                if ( closer >= 0 && region.blankLineAfter( opener ) > closer ) {
                    final int end = closer + CLOSER.length();
                    out.putIfAbsent( opener, text.startsWith( "()", end ) && end + 2 <= to ? end + 2 : end );
                }
            }
            opener = text.indexOf( OPENER, opener + 1 );
        }
    }

    /** Monotone cursors over one region: the next closer and the next blank line, each found once per stretch. */
    private static final class Region {
        private final String text;
        private final int to;
        private int closerFrom = -1;
        private int closer = -1;
        private int blankFrom = -1;
        private int blank = -1;

        Region( final String text, final int to ) {
            this.text = text;
            this.to = to;
        }

        /** The first closer at or after {@code pos} that ends inside the region, or {@code -1}. */
        int closerAfter( final int pos ) {
            if ( closerFrom < 0 || pos < closerFrom || ( closer >= 0 && pos > closer ) ) {
                final int found = text.indexOf( CLOSER, pos );
                closer = found >= 0 && found + CLOSER.length() <= to ? found : -1;
                closerFrom = pos;
            }
            return closer;
        }

        /** Where the first blank line at or after {@code pos} begins, or the region's end. */
        int blankLineAfter( final int pos ) {
            if ( blankFrom < 0 || pos < blankFrom || pos > blank ) {
                blank = firstBlankLine( text, pos, to );
                blankFrom = pos;
            }
            return blank;
        }
    }

    /** The first line ending at or after {@code from} that begins a blank line, or {@code to}. */
    private static int firstBlankLine( final String text, final int from, final int to ) {
        for ( int i = from; i < to; i++ ) {
            final char c = text.charAt( i );
            if ( c == '\n' || c == '\r' ) {
                int j = c == '\r' && i + 1 < to && text.charAt( i + 1 ) == '\n' ? i + 2 : i + 1;
                while ( j < to && ( text.charAt( j ) == ' ' || text.charAt( j ) == '\t' ) ) {
                    j++;
                }
                if ( j < to && ( text.charAt( j ) == '\n' || text.charAt( j ) == '\r' ) ) {
                    return i;
                }
            }
        }
        return to;
    }

    /**
     * Whether {@code text[from, to)} holds a <code>{</code>, then any whitespace, then {@code allow} or {@code deny} in
     * any case — the cheap test that lets a text with no directive skip parsing.
     */
    static boolean mayHoldDirective( final String text, final int from, final int to ) {
        int brace = text.indexOf( '{', from );
        while ( brace >= 0 && brace < to ) {
            if ( keywordShaped( text, skipWhitespace( text, brace + 1, to ) ) ) {
                return true;
            }
            brace = text.indexOf( '{', brace + 1 );
        }
        return false;
    }

    private static boolean keywordShaped( final String text, final int at ) {
        return text.regionMatches( true, at, ALLOW, 0, ALLOW.length() ) || text.regionMatches( true, at, DENY, 0, DENY.length() );
    }

    static int skipWhitespace( final String text, final int from, final int to ) {
        int i = from;
        while ( i < to && Character.isWhitespace( text.charAt( i ) ) ) {
            i++;
        }
        return i;
    }
}
