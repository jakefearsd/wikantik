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
package com.wikantik.collab.text;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns two texts into a {@link ChangeSet} that touches only what changed. Common prefix and suffix are trimmed
 * first, the middle is diffed with Myers at line granularity, and each changed line hunk is refined to characters
 * by prefix/suffix trimming (linear, never a second Myers). Change boundaries never split a surrogate pair.
 *
 * <p>Both inputs must be LF-only (callers normalise with {@link CollabText#toLf}); a carriage return is a caller
 * bug and throws {@link IllegalArgumentException}.
 */
public final class TextDiff {

    private static final Logger LOG = LogManager.getLogger( TextDiff.class );

    /** Line-level Myers gives up beyond this many edited lines; the differing span is then replaced coarsely. */
    static final int MAX_EDITS = 2000;

    private TextDiff() {
    }

    /** A change set {@code cs} with {@code cs.apply(from).equals(to)}. */
    public static ChangeSet changeSet( final String from, final String to ) {
        return changeSet( from, to, MAX_EDITS );
    }

    /**
     * As {@link #changeSet(String, String)}, but a pure deletion whose position is ambiguous (the deleted run can slide
     * along a repeat, as {@code "<A><B>"} losing {@code "<A>"} can also read as losing {@code "A><"}) is slid to the
     * position that puts the fewest of {@code avoid}'s boundaries strictly inside it; ties keep the position the plain
     * diff chose. {@code avoid} holds {@code {from, to}} ranges of {@code from} (a draft's local changes,
     * {@link ChangeSet#touchedRanges}): a deletion mapped over an edit inside it is split around that edit, so a slid
     * deletion takes out exactly the text that was deleted instead of a shifted run that cuts the neighbouring text. A
     * slide never crosses a neighbouring change and never splits a surrogate pair.
     */
    public static ChangeSet changeSet( final String from, final String to, final List< int[] > avoid ) {
        final List< int[] > hunks = hunks( from, to, MAX_EDITS );
        if ( avoid.isEmpty() ) {
            return build( from, to, hunks );
        }
        for ( int i = 0; i < hunks.size(); i++ ) {
            final int[] h = hunks.get( i );
            if ( h[ 2 ] == h[ 3 ] ) {
                final int lo = i == 0 ? 0 : hunks.get( i - 1 )[ 1 ];
                final int hi = i == hunks.size() - 1 ? from.length() : hunks.get( i + 1 )[ 0 ];
                final int shift = bestSlide( from, h[ 0 ], h[ 1 ], lo, hi, avoid );
                if ( shift != 0 ) {
                    // a pure deletion keeps its place in the new text's terms: the text before it moves by the shift too
                    hunks.set( i, new int[] { h[ 0 ] + shift, h[ 1 ] + shift, h[ 2 ] + shift, h[ 3 ] + shift } );
                }
            }
        }
        return build( from, to, hunks );
    }

    /**
     * The shift (negative left, positive right) of the deletion {@code [a0, a1)} of {@code a} within {@code [lo, hi]}
     * that puts the fewest {@code avoid} boundaries strictly inside it; 0 on a tie with the original position.
     */
    private static int bestSlide( final String a, final int a0, final int a1, final int lo, final int hi,
                                  final List< int[] > avoid ) {
        int left = 0;
        while ( a0 + left - 1 >= lo && a.charAt( a0 + left - 1 ) == a.charAt( a1 + left - 1 ) ) {
            left--;
        }
        int right = 0;
        while ( a1 + right < hi && a.charAt( a0 + right ) == a.charAt( a1 + right ) ) {
            right++;
        }
        int best = 0;
        int bestInside = inside( a0, a1, avoid );
        for ( int shift = left; shift <= right && bestInside > 0; shift++ ) {
            if ( shift == 0 || splitsPair( a, a0 + shift ) || splitsPair( a, a1 + shift ) ) {
                continue;
            }
            final int n = inside( a0 + shift, a1 + shift, avoid );
            if ( n < bestInside || n == bestInside && Math.abs( shift ) < Math.abs( best ) ) {
                best = shift;
                bestInside = n;
            }
        }
        return best;
    }

    /** How many boundaries of {@code avoid} lie strictly inside {@code (x, y)}. */
    private static int inside( final int x, final int y, final List< int[] > avoid ) {
        int n = 0;
        for ( final int[] r : avoid ) {
            n += ( r[ 0 ] > x && r[ 0 ] < y ? 1 : 0 ) + ( r[ 1 ] != r[ 0 ] && r[ 1 ] > x && r[ 1 ] < y ? 1 : 0 );
        }
        return n;
    }

    /** True when {@code pos} falls between the two halves of a surrogate pair. */
    private static boolean splitsPair( final String s, final int pos ) {
        return pos > 0 && pos < s.length() && Character.isHighSurrogate( s.charAt( pos - 1 ) )
            && Character.isLowSurrogate( s.charAt( pos ) );
    }

    /** As {@link #changeSet(String, String)} with an explicit line-edit-distance cap (tests force the fallback). */
    static ChangeSet changeSet( final String from, final String to, final int maxD ) {
        return build( from, to, hunks( from, to, maxD ) );
    }

    /** The changed {@code {aFrom, aTo, bFrom, bTo}} hunks, ascending, off surrogate pairs. */
    private static List< int[] > hunks( final String from, final String to, final int maxD ) {
        if ( from.indexOf( '\r' ) >= 0 || to.indexOf( '\r' ) >= 0 ) {
            throw new IllegalArgumentException( "TextDiff inputs must be LF-only" );
        }
        final List< int[] > hunks = new ArrayList<>();   // {aFrom, aTo, bFrom, bTo}
        final int prefix = HunkScan.commonPrefix( from, 0, from.length(), to, 0, to.length() );
        final int suffix = HunkScan.commonSuffix( from, prefix, from.length(), to, prefix, to.length() );
        final int aEnd = from.length() - suffix;
        final int bEnd = to.length() - suffix;
        if ( prefix < aEnd && prefix < bEnd ) {
            // the line diff must see whole lines: a character trim that stops mid-line leaves a partial line that no
            // longer equals its whole twin on the other side, and the hunks around it merge across a kept line (which
            // an OT merge then treats as deleted and re-inserted). Give the partial lines back; refine trims them again.
            final int p = prefix == 0 ? 0 : from.lastIndexOf( '\n', prefix - 1 ) + 1;
            final int s = lineAlignedSuffix( from, to, suffix );
            lineHunks( from, p, from.length() - s, to, p, to.length() - s, hunks, maxD );
        } else if ( prefix < aEnd || prefix < bEnd ) {
            hunks.add( new int[] { prefix, aEnd, prefix, bEnd } );
        }
        return HunkScan.widenAndMerge( from, to, hunks );
    }

    /**
     * {@code suffix} shortened so that it starts at a line start in both texts: kept when the character before it is a
     * {@code \n} (or the text start) in both, else cut to begin after its own first {@code \n} (zero when it has none).
     */
    private static int lineAlignedSuffix( final String from, final String to, final int suffix ) {
        final int aEnd = from.length() - suffix;
        final int bEnd = to.length() - suffix;
        if ( suffix == 0 || ( aEnd == 0 || from.charAt( aEnd - 1 ) == '\n' ) && ( bEnd == 0 || to.charAt( bEnd - 1 ) == '\n' ) ) {
            return suffix;
        }
        final int nl = from.indexOf( '\n', aEnd );
        return nl < 0 ? 0 : from.length() - nl - 1;
    }

    private static void lineHunks( final String a, final int a0, final int a1, final String b, final int b0, final int b1,
                                   final List< int[] > out, final int maxD ) {
        final int[] aLines = HunkScan.lineStarts( a, a0, a1 );
        final int[] bLines = HunkScan.lineStarts( b, b0, b1 );
        final String[] aText = HunkScan.lines( a, aLines, a1 );
        final String[] bText = HunkScan.lines( b, bLines, b1 );
        final List< int[] > lineHunks = LineMyers.diff( aText, bText, maxD );
        if ( lineHunks == null ) {
            LOG.warn( "Line diff exceeded {} edits ({} vs {} lines); replacing the whole differing span", maxD,
                    aText.length, bText.length );
            refine( a, a0, a1, b, b0, b1, out );
            return;
        }
        for ( final int[] h : lineHunks ) {
            refine( a, lineOffset( aLines, h[ 0 ], a1 ), lineOffset( aLines, h[ 1 ], a1 ),
                    b, lineOffset( bLines, h[ 2 ], b1 ), lineOffset( bLines, h[ 3 ], b1 ), out );
        }
    }

    private static int lineOffset( final int[] starts, final int line, final int end ) {
        return line < starts.length ? starts[ line ] : end;
    }

    private static void refine( final String a, final int a0, final int a1, final String b, final int b0, final int b1,
                                final List< int[] > out ) {
        final int p = HunkScan.commonPrefix( a, a0, a1, b, b0, b1 );
        final int s = HunkScan.commonSuffix( a, a0 + p, a1, b, b0 + p, b1 );
        if ( a0 + p < a1 - s || b0 + p < b1 - s ) {
            out.add( new int[] { a0 + p, a1 - s, b0 + p, b1 - s } );
        }
    }

    private static ChangeSet build( final String a, final String b, final List< int[] > hunks ) {
        if ( hunks.isEmpty() ) {
            return ChangeSet.empty( a.length() );
        }
        final SectionBuilder sb = new SectionBuilder();
        int posA = 0;
        for ( final int[] h : hunks ) {
            sb.addSection( h[ 0 ] - posA, ChangeSet.KEPT, false );
            final String insert = b.substring( h[ 2 ], h[ 3 ] );
            sb.addSection( h[ 1 ] - h[ 0 ], insert.length(), false );
            sb.addInsert( insert );
            posA = h[ 1 ];
        }
        sb.addSection( a.length() - posA, ChangeSet.KEPT, false );
        return sb.build();
    }
}
