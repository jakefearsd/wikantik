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
import java.util.Objects;

/**
 * The conservative half of removing page access-rule directives ({@code [{ALLOW view Alice}]},
 * {@code [{DENY edit Bob}]}) from raw page text: it hides the ones it is <em>sure</em> are not documentation, and keeps
 * the rest byte for byte. <b>It is not, on its own, a guarantee that no enforced rule survives</b> — the guarantee is
 * {@code com.wikantik.auth.acl.AclDirectiveText} in {@code wikantik-main}, which first removes every span
 * {@code DefaultAclManager} enforces (judged by a real Markdown parser) and only then runs this pass. Call that, not
 * this, wherever text is shown to someone who may not edit the page.
 *
 * <p>What counts as a directive mirrors {@code DefaultAclManager}, which this module cannot depend on: the
 * <code>[{ALLOW &lt;permission&gt; &lt;principals&gt;}]</code> span, where the permission is one of the seven page
 * permissions, found by {@link AclDirectiveScanner} (linear time, match for match identical to the ACL manager's
 * expression). {@code DENY} is recognised too, the spelling plugin normalisation produces
 * ({@code [{ALLOW view All}]()}) goes together with its empty {@code ()}, and — because the renderer reads a link
 * label that spans lines as readily as a one-line one — so does a directive wrapped over several lines
 * (<code>[{ALLOW edit alice,\n bob}]</code>), which the enforcement expression does not match. Like the renderer's
 * link label, the wrapped shape never spans a blank line, and it applies only to page body: frontmatter, which the
 * renderer never reads, is passed with {@code wrapped = false} by the facade, which splits the two. The pass is
 * code-aware in the same way the ACL manager is: the identical text inside a fenced code block or an inline code span
 * is documentation, not a rule, and is kept.
 *
 * <p>Everything else is returned byte-for-byte: other {@code [{Plugin}]} spans, line endings, whitespace around a
 * removed directive. Only the directive itself (plus an immediately trailing {@code ()}) is removed — except that a
 * directive that is all its line holds (spaces and tabs aside) goes with that line's terminator (on the last line,
 * with the terminator before it), so no blank line is left behind: a blank line would end the paragraph or the HTML
 * block the directive sat in and change how the rest of the page renders ({@link #removal}).
 *
 * <p><b>Fail-closed bias, not a promise.</b> Code detection is a small purpose-built scanner (this module carries no
 * Markdown parser) that only trusts what it can be certain of: a <em>closed</em> fenced block whose fences have at
 * most three spaces of indent, and an inline backtick span that opens and closes on one line. Anything else — an
 * unclosed fence, a span that wraps onto another line, an indented code block, a fence nested deeper in a list — is
 * treated as ordinary text, so a directive there is removed. That errs towards hiding a documentation example, but a
 * real parser disagrees with this scanner in places (an inline span that pairs across lines, a backtick inside inline
 * HTML or an autolink, fence-looking lines in an HTML block, tilde fences with a second tilde in the info string), and
 * where it does a trusted "code" region can hold a rule the ACL manager enforces. Hence the facade above.
 *
 * <p>Removing a directive can in principle splice the text either side of it into a new one
 * ({@code [{ALLOW vi[{ALLOW view a}]ew a}]}), so the scan repeats until nothing more matches (bounded).
 */
public final class AclDirectives {

    /** Upper bound on re-scans after a removal; real pages converge in one. */
    private static final int MAX_PASSES = 8;

    private AclDirectives() {
    }

    /**
     * Removes every access-rule directive outside the code this pass can recognise from {@code markdown}.
     *
     * @param markdown raw page text (frontmatter included is fine), may be {@code null}
     * @return the text without directives; the same instance when there was nothing to remove;
     *         {@code null} when {@code markdown} is {@code null}
     */
    public static String strip( final String markdown ) {
        return strip( markdown, "" );
    }

    /**
     * Like {@link #strip(String)}, but each removed directive is replaced by {@code replacement}
     * (for callers such as excerpt builders that must keep the words either side apart).
     *
     * @param markdown    raw page text, may be {@code null}
     * @param replacement text put in place of each directive; must be shorter than the shortest
     *                    directive and must not itself contain an opening <code>[&#123;</code>
     * @return the text with directives replaced; the same instance when there was nothing to replace
     */
    public static String strip( final String markdown, final String replacement ) {
        return strip( markdown, replacement, true );
    }

    /**
     * Like {@link #strip(String, String)}, choosing whether the wrapped shape counts.
     *
     * @param markdown    raw page text, may be {@code null}
     * @param replacement text put in place of each directive (see {@link #strip(String, String)})
     * @param wrapped     {@code true} for page body: a directive wrapped over several lines (no blank line) is removed
     *                    too, as the renderer reads one. {@code false} for frontmatter, which the renderer never reads:
     *                    only the one-line shape the ACL manager enforces there counts, so a stray opener in a value can
     *                    never reach a closer beyond the frontmatter
     * @return the text with directives replaced; the same instance when there was nothing to replace
     */
    public static String strip( final String markdown, final String replacement, final boolean wrapped ) {
        Objects.requireNonNull( replacement, "replacement" );
        if ( markdown == null || markdown.indexOf( "[{" ) < 0 ) {
            return markdown;
        }
        String current = markdown;
        for ( int pass = 0; pass < MAX_PASSES; pass++ ) {
            final String next = stripOnce( current, replacement, wrapped );
            if ( next == null ) {
                break;
            }
            current = next;
        }
        return current;
    }

    /** One left-to-right pass; {@code null} when no directive outside code was found. */
    private static String stripOnce( final String text, final String replacement, final boolean wrapped ) {
        // Wide mode (for body text): this pass only hides, and the renderer hands a directive wrapped over several
        // lines to the ACL parser as readily as a one-line one, so the multi-line shape must go too.
        final AclDirectiveScanner scanner = new AclDirectiveScanner( text, true, wrapped );
        AclDirectiveScanner.Span span = scanner.next( 0 );
        if ( span == null ) {
            return null;
        }
        final OffsetRanges code = OffsetRanges.of( codeRegions( text ) );
        final StringBuilder out = new StringBuilder( text.length() );
        int copiedTo = 0;
        boolean changed = false;
        while ( span != null ) {
            if ( code.contains( span.start() ) ) {
                // A candidate that starts in code is skipped, and the search resumes just past its start rather than
                // at its end: it was found by the lazy run to the first closer, which can swallow a real directive
                // that begins inside the code (`[{DENY view x` followed by a real [{ALLOW …}]).
                span = scanner.next( span.start() + 1 );
                continue;
            }
            final Removal removal = removal( text, span.start(), AclDirectiveScanner.endWithEmptyParentheses( text, span ),
                    copiedTo, text.length() );
            out.append( text, copiedTo, removal.start() ).append( removal.wholeLine() ? "" : replacement );
            copiedTo = removal.end();
            changed = true;
            span = scanner.next( copiedTo );
        }
        if ( !changed ) {
            return null;
        }
        return out.append( text, copiedTo, text.length() ).toString();
    }

    /**
     * What to cut for a directive at {@code [start, end)}: the directive itself, or, when it is all its line holds
     * (spaces and tabs aside), the whole line with its terminator — on the last line of the region, with the terminator
     * before it.
     * The cut never reaches below {@code floor} or beyond {@code ceiling} (the edges of the region being rewritten).
     *
     * @param start     where the directive starts
     * @param end       where it ends (with any empty {@code ()} after it)
     * @param wholeLine whether the line went with it; the caller then puts no replacement in its place, as the line end
     *                  before it already keeps the words either side apart
     */
    public record Removal( int start, int end, boolean wholeLine ) {
    }

    /**
     * @param text    the text
     * @param start   the directive's start
     * @param end     the directive's end (with any empty {@code ()} after it)
     * @param floor   the lowest offset the cut may reach (a line start, or text already handled)
     * @param ceiling the highest offset the cut may reach (a line end)
     * @return the range to cut
     */
    public static Removal removal( final String text, final int start, final int end, final int floor, final int ceiling ) {
        final int lineStart = skipBlanksBack( text, start, floor );
        final int lineEnd = skipBlanks( text, end, ceiling );
        if ( !startsLine( text, lineStart ) || !endsLine( text, lineEnd ) ) {
            return new Removal( start, end, false );
        }
        if ( lineEnd < ceiling ) {
            return new Removal( lineStart, lineEnd + lineEndLength( text, lineEnd, ceiling ), true );
        }
        if ( lineStart > floor ) {
            return new Removal( lineEndStartBefore( text, lineStart, floor ), lineEnd, true );
        }
        return new Removal( lineStart, lineEnd, true );
    }

    private static int skipBlanksBack( final String text, final int from, final int floor ) {
        int i = from;
        while ( i > floor && isBlank( text.charAt( i - 1 ) ) ) {
            i--;
        }
        return i;
    }

    private static int skipBlanks( final String text, final int from, final int ceiling ) {
        int i = from;
        while ( i < ceiling && isBlank( text.charAt( i ) ) ) {
            i++;
        }
        return i;
    }

    private static boolean startsLine( final String text, final int at ) {
        return at == 0 || isLineEnd( text.charAt( at - 1 ) );
    }

    private static boolean endsLine( final String text, final int at ) {
        return at == text.length() || isLineEnd( text.charAt( at ) );
    }

    /** Length of the line terminator at {@code at} ({@code \r\n} is one), not reaching {@code ceiling}. */
    private static int lineEndLength( final String text, final int at, final int ceiling ) {
        return text.startsWith( "\r\n", at ) && at + 1 < ceiling ? 2 : 1;
    }

    /** Where the line terminator that ends right at {@code at} starts, not reaching below {@code floor}. */
    private static int lineEndStartBefore( final String text, final int at, final int floor ) {
        return at - 2 >= floor && text.startsWith( "\r\n", at - 2 ) ? at - 2 : at - 1;
    }

    private static boolean isBlank( final char c ) {
        return c == ' ' || c == '\t';
    }

    private static boolean isLineEnd( final char c ) {
        return c == '\n' || c == '\r';
    }

    // ---------------------------------------------------------------- code regions

    /** {@code [start, end)} offsets of every closed fenced code block and one-line inline code span. */
    private static List< int[] > codeRegions( final String text ) {
        final List< int[] > regions = fencedBlocks( text );
        int lineStart = 0;
        while ( lineStart < text.length() ) {
            final int newline = text.indexOf( '\n', lineStart );
            final int lineEnd = newline < 0 ? text.length() : newline;
            spansInLine( text, lineStart, lineEnd, regions );
            lineStart = lineEnd + 1;
        }
        return regions;
    }

    /** A run of three or more {@code `} or {@code ~} that starts a line, and where it ends. */
    private record Marker( char ch, int length, int end ) {
    }

    /**
     * Fenced code blocks, CommonMark-style: an opening run of 3+ {@code `} or {@code ~} (at most three
     * spaces of indent; a backtick fence's info string may not contain a backtick) closed by a run of
     * the same character at least as long with nothing after it. An unclosed fence is not reported (see the
     * class comment). Each block spans from the start of its opening line to the end of its closing line.
     */
    private static List< int[] > fencedBlocks( final String text ) {
        final List< int[] > blocks = new ArrayList<>();
        final int length = text.length();
        Marker open = null;
        int openStart = 0;
        int lineStart = 0;
        while ( lineStart < length ) {
            final int newline = text.indexOf( '\n', lineStart );
            final int lineEnd = newline < 0 ? length : newline;
            final Marker marker = markerAt( text, lineStart, lineEnd );
            if ( open == null ) {
                if ( marker != null && opensFence( text, marker, lineEnd ) ) {
                    open = marker;
                    openStart = lineStart;
                }
            } else if ( marker != null && closesFence( text, open, marker, lineEnd ) ) {
                blocks.add( new int[]{ openStart, lineEnd } );
                open = null;
            }
            lineStart = lineEnd + 1;
        }
        return blocks;
    }

    private static Marker markerAt( final String text, final int lineStart, final int lineEnd ) {
        int i = lineStart;
        while ( i < lineEnd && i - lineStart < 4 && text.charAt( i ) == ' ' ) {
            i++;
        }
        if ( i - lineStart > 3 || i >= lineEnd ) {
            return null;
        }
        final char ch = text.charAt( i );
        if ( ch != '`' && ch != '~' ) {
            return null;
        }
        final int end = skipRun( text, i, lineEnd, ch );
        return end - i >= 3 ? new Marker( ch, end - i, end ) : null;
    }

    private static boolean opensFence( final String text, final Marker marker, final int lineEnd ) {
        if ( marker.ch() == '~' ) {
            return true;
        }
        final int backtick = text.indexOf( '`', marker.end() );
        return backtick < 0 || backtick >= lineEnd;
    }

    private static boolean closesFence( final String text, final Marker open, final Marker marker,
                                        final int lineEnd ) {
        return marker.ch() == open.ch()
                && marker.length() >= open.length()
                && text.substring( marker.end(), lineEnd ).isBlank();
    }

    /**
     * Inline code spans within the line {@code [from, to)}: a backtick run closed by the next run of
     * exactly the same length on the same line. A backslash makes the backtick after it literal. An
     * unmatched run is just text.
     */
    private static void spansInLine( final String text, final int from, final int to, final List< int[] > out ) {
        int i = from;
        while ( i < to ) {
            if ( text.charAt( i ) != '`' || isEscaped( text, i, from ) ) {
                i++;
                continue;
            }
            final int runEnd = skipRun( text, i, to, '`' );
            final int run = runEnd - i;
            final int close = closingRun( text, runEnd, to, run );
            if ( close < 0 ) {
                i = runEnd;
            } else {
                out.add( new int[]{ i, close + run } );
                i = close + run;
            }
        }
    }

    /** Start of the next backtick run of exactly {@code run} characters within {@code [from, to)}, else -1. */
    private static int closingRun( final String text, final int from, final int to, final int run ) {
        int j = from;
        while ( j < to ) {
            if ( text.charAt( j ) != '`' ) {
                j++;
                continue;
            }
            final int end = skipRun( text, j, to, '`' );
            if ( end - j == run ) {
                return j;
            }
            j = end;
        }
        return -1;
    }

    /** True when an odd number of backslashes (not reaching before {@code floor}) precede {@code index}. */
    private static boolean isEscaped( final String text, final int index, final int floor ) {
        int backslashes = 0;
        for ( int k = index - 1; k >= floor && text.charAt( k ) == '\\'; k-- ) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** Index just past the run of {@code ch} that starts at {@code start}, not going beyond {@code limit}. */
    private static int skipRun( final String text, final int start, final int limit, final char ch ) {
        int end = start;
        while ( end < limit && text.charAt( end ) == ch ) {
            end++;
        }
        return end;
    }
}
