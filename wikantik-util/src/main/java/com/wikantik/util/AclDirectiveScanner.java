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
package com.wikantik.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds access-rule directives (<code>[{ALLOW view Alice}]</code>, optionally <code>[{DENY edit Bob}]</code>) in page
 * text, in time linear in the length of the text.
 *
 * <p>It is a hand-written equivalent of the regular expression {@code DefaultAclManager} used to enforce access
 * rules, driven by {@code Matcher.find()}:
 * <pre>
 * \[\{\s*ALLOW\s+(comment|delete|edit|modify|rename|upload|view)\s*(.*?)\s*\}\]
 * </pre>
 * <em>Match for match</em> identical (same starts, same ends, same permission, same principal text), which a seeded
 * differential test against the original expression pins down. The expression is not usable on page text a user
 * controls: its adjacent unbounded quantifiers make it cubic on a run of whitespace and quadratic on many
 * unterminated openers, and it ran on every read. The directive may <em>not</em> be length-bounded to fix that — a
 * bound would silently stop enforcing long access lists.
 *
 * <p>What the expression accepts, derived once: after the permission word and any whitespace, the match ends at the
 * <em>first</em> closer (<code>}]</code>) at or after that point, provided the text up to it — less the whitespace
 * immediately before the closer — holds no line terminator (LF, CR, U+0085, U+2028, U+2029: what {@code .} refuses).
 * Otherwise there is no match at this start, and no later closer can help. Whitespace is Java's {@code \s}: space,
 * tab, newline, vertical tab, form feed, carriage return.
 *
 * <p>The scan walks the openers (<code>[{</code>) once with {@code indexOf} and keeps monotone cached positions (next
 * closer, next line terminator, whitespace run before a closer), so overlapping candidates that share a closer are
 * decided in constant time each rather than by rescanning.
 *
 * <p>Instances are single-use, not thread-safe, and cheap; use {@link #findAll} for the common case.
 */
public final class AclDirectiveScanner {

    /**
     * One directive: {@code text.substring( start, end )} is the whole {@code [{…}]} span;
     * {@code text.substring( principalsStart, principalsEnd )} is what follows the permission (trimmed of
     * surrounding whitespace) — the second capture group of the original expression.
     */
    public record Span( int start, int end, String keyword, String permission, int principalsStart, int principalsEnd ) {
    }

    /** What {@link #match} answers when this start and every later one are known to have no closer. */
    private static final Span EXHAUSTED = new Span( -1, -1, "", "", -1, -1 );

    /** Cache marker: no {@code }]} at or after the query position. */
    private static final int NONE = Integer.MAX_VALUE;

    private static final String OPENER = "[{";
    private static final String CLOSER = "}]";
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    /** The page permissions an access rule can name — the action constants of {@code PagePermission}. */
    private static final String[] PERMISSIONS = { "comment", "delete", "edit", "modify", "rename", "upload", "view" };

    private final String text;
    private final int length;
    private final boolean includeDeny;

    // Monotone caches. Each remembers "the answer for a query at queryPos" and is valid for any later query up to
    // the answer itself, so a run of overlapping candidates costs one scan between them.
    private int closeQuery = -1;
    private int closeAt = NONE;
    private int terminatorQuery = -1;
    private int terminatorAt;
    private int runQuery = -1;
    private int runStart;

    /**
     * @param text        the page text
     * @param includeDeny when {@code true} a {@code DENY} head is accepted alongside {@code ALLOW}; when
     *                    {@code false} only {@code ALLOW} is, which is exactly what the ACL manager enforces
     */
    public AclDirectiveScanner( final String text, final boolean includeDeny ) {
        this.text = text;
        this.length = text.length();
        this.includeDeny = includeDeny;
    }

    /**
     * Every directive in {@code text}, left to right, as {@code Matcher.find()} would report them (each search resumes
     * at the end of the previous match).
     *
     * @param text        the page text, may be {@code null}
     * @param includeDeny whether {@code [{DENY …}]} counts
     * @return the directives, never {@code null}
     */
    public static List< Span > findAll( final String text, final boolean includeDeny ) {
        if ( text == null || text.indexOf( OPENER ) < 0 ) {
            return List.of();
        }
        final AclDirectiveScanner scanner = new AclDirectiveScanner( text, includeDeny );
        final List< Span > found = new ArrayList<>();
        int from = 0;
        for ( Span span = scanner.next( from ); span != null; span = scanner.next( from ) ) {
            found.add( span );
            from = span.end();
        }
        return found;
    }

    /**
     * The end of {@code span} extended over an immediately following empty {@code ()} — the spelling plugin
     * normalisation gives a directive ({@code [{ALLOW view All}]()}).
     */
    public static int endWithEmptyParentheses( final String text, final Span span ) {
        return text.startsWith( "()", span.end() ) ? span.end() + 2 : span.end();
    }

    /**
     * The first directive that starts at or after {@code from}, or {@code null}. A caller that wants to look past a
     * candidate it rejects passes {@code span.start() + 1}; queries should not move backwards (the caches would
     * merely be recomputed, and the scan stays correct).
     */
    public Span next( final int from ) {
        int start = from;
        while ( start >= 0 && start < length ) {
            start = text.indexOf( OPENER, start );
            if ( start < 0 ) {
                return null;
            }
            final Span span = match( start );
            if ( span == EXHAUSTED ) {
                return null;
            }
            if ( span != null ) {
                return span;
            }
            start++;
        }
        return null;
    }

    /** The directive that starts exactly at {@code opener}, {@code null} for none, or {@link #EXHAUSTED}. */
    private Span match( final int opener ) {
        final int keywordAt = skipWhitespace( opener + OPENER.length() );
        final String keyword = keywordAt( keywordAt );
        if ( keyword == null ) {
            return null;
        }
        final int afterKeyword = keywordAt + keyword.length();
        final int permissionAt = skipWhitespace( afterKeyword );
        if ( permissionAt == afterKeyword ) {
            return null;                                         // \s+ needs at least one
        }
        final String permission = permissionAt( permissionAt );
        if ( permission == null ) {
            return null;
        }
        final int principalsStart = skipWhitespace( permissionAt + permission.length() );
        final int closer = closerAtOrAfter( principalsStart );
        if ( closer < 0 ) {
            return EXHAUSTED;
        }
        final int principalsEnd = Math.max( principalsStart, whitespaceRunStart( closer ) );
        if ( terminatorAtOrAfter( principalsStart ) < principalsEnd ) {
            return null;
        }
        return new Span( opener, closer + CLOSER.length(), keyword, permission, principalsStart, principalsEnd );
    }

    private String keywordAt( final int at ) {
        if ( text.startsWith( ALLOW, at ) ) {
            return ALLOW;
        }
        return includeDeny && text.startsWith( DENY, at ) ? DENY : null;
    }

    private String permissionAt( final int at ) {
        for ( final String permission : PERMISSIONS ) {
            if ( text.startsWith( permission, at ) ) {
                return permission;
            }
        }
        return null;
    }

    private int skipWhitespace( final int from ) {
        int i = from;
        while ( i < length && isWhitespace( text.charAt( i ) ) ) {
            i++;
        }
        return i;
    }

    /** The first closer at or after {@code pos}, or -1 when there is none. */
    private int closerAtOrAfter( final int pos ) {
        if ( closeQuery < 0 || pos < closeQuery || pos > closeAt ) {
            final int found = text.indexOf( CLOSER, pos );
            closeAt = found < 0 ? NONE : found;
            closeQuery = pos;
        }
        return closeAt == NONE ? -1 : closeAt;
    }

    /** The first line terminator at or after {@code pos}, or the text length when there is none. */
    private int terminatorAtOrAfter( final int pos ) {
        if ( terminatorQuery < 0 || pos < terminatorQuery || pos > terminatorAt ) {
            int i = pos;
            while ( i < length && !isTerminator( text.charAt( i ) ) ) {
                i++;
            }
            terminatorAt = i;
            terminatorQuery = pos;
        }
        return terminatorAt;
    }

    /** Where the run of whitespace that ends right before {@code closer} begins. */
    private int whitespaceRunStart( final int closer ) {
        if ( runQuery != closer ) {
            int i = closer;
            while ( i > 0 && isWhitespace( text.charAt( i - 1 ) ) ) {
                i--;
            }
            runStart = i;
            runQuery = closer;
        }
        return runStart;
    }

    /** Java's {@code \s}: space, tab, newline, vertical tab, form feed, carriage return. */
    private static boolean isWhitespace( final char c ) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /** What {@code .} refuses without {@code DOTALL}: LF, CR, U+0085, U+2028, U+2029. */
    private static boolean isTerminator( final char c ) {
        return c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029;
    }
}
