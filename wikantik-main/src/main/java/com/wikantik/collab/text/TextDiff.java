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
import org.suigeneris.jrcs.diff.Diff;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.Revision;
import org.suigeneris.jrcs.diff.delta.Delta;
import org.suigeneris.jrcs.diff.myers.MyersDiff;

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

    private TextDiff() {
    }

    /** A change set {@code cs} with {@code cs.apply(from).equals(to)}. */
    public static ChangeSet changeSet( final String from, final String to ) {
        if ( from.indexOf( '\r' ) >= 0 || to.indexOf( '\r' ) >= 0 ) {
            throw new IllegalArgumentException( "TextDiff inputs must be LF-only" );
        }
        final List< int[] > hunks = new ArrayList<>();   // {aFrom, aTo, bFrom, bTo}
        final int prefix = commonPrefix( from, 0, from.length(), to, 0, to.length() );
        final int suffix = commonSuffix( from, prefix, from.length(), to, prefix, to.length() );
        final int aEnd = from.length() - suffix;
        final int bEnd = to.length() - suffix;
        if ( prefix < aEnd && prefix < bEnd ) {
            lineHunks( from, prefix, aEnd, to, prefix, bEnd, hunks );
        } else if ( prefix < aEnd || prefix < bEnd ) {
            hunks.add( new int[] { prefix, aEnd, prefix, bEnd } );
        }
        return build( from, to, widenAndMerge( from, to, hunks ) );
    }

    private static void lineHunks( final String a, final int a0, final int a1, final String b, final int b0, final int b1,
                                   final List< int[] > out ) {
        final int[] aLines = lineStarts( a, a0, a1 );
        final int[] bLines = lineStarts( b, b0, b1 );
        final String[] aText = lines( a, aLines, a1 );
        final String[] bText = lines( b, bLines, b1 );
        final Revision rev;
        try {
            rev = Diff.diff( aText, bText, new MyersDiff() );
        } catch ( final DifferentiationFailedException e ) {
            LOG.warn( "Line diff failed ({}); replacing the whole differing span", e.getMessage(), e );
            refine( a, a0, a1, b, b0, b1, out );
            return;
        }
        for ( int i = 0; i < rev.size(); i++ ) {
            final Delta d = rev.getDelta( i );
            final int la = d.getOriginal().first();
            final int lb = d.getRevised().first();
            final int as = lineOffset( aLines, la, a1 );
            final int ae = lineOffset( aLines, la + d.getOriginal().size(), a1 );
            final int bs = lineOffset( bLines, lb, b1 );
            final int be = lineOffset( bLines, lb + d.getRevised().size(), b1 );
            refine( a, as, ae, b, bs, be, out );
        }
    }

    private static int lineOffset( final int[] starts, final int line, final int end ) {
        return line < starts.length ? starts[ line ] : end;
    }

    private static void refine( final String a, final int a0, final int a1, final String b, final int b0, final int b1,
                                final List< int[] > out ) {
        final int p = commonPrefix( a, a0, a1, b, b0, b1 );
        final int s = commonSuffix( a, a0 + p, a1, b, b0 + p, b1 );
        if ( a0 + p < a1 - s || b0 + p < b1 - s ) {
            out.add( new int[] { a0 + p, a1 - s, b0 + p, b1 - s } );
        }
    }

    /** Offsets of each line start in {@code [from, to)}; lines keep their {@code \n}. */
    private static int[] lineStarts( final String s, final int from, final int to ) {
        int count = from < to ? 1 : 0;
        for ( int i = from; i < to - 1; i++ ) {
            if ( s.charAt( i ) == '\n' ) {
                count++;
            }
        }
        final int[] starts = new int[ count ];
        if ( count > 0 ) {
            int n = 1;
            starts[ 0 ] = from;
            for ( int i = from; i < to - 1; i++ ) {
                if ( s.charAt( i ) == '\n' ) {
                    starts[ n++ ] = i + 1;
                }
            }
        }
        return starts;
    }

    private static String[] lines( final String s, final int[] starts, final int end ) {
        final String[] out = new String[ starts.length ];
        for ( int i = 0; i < starts.length; i++ ) {
            out[ i ] = s.substring( starts[ i ], i + 1 < starts.length ? starts[ i + 1 ] : end );
        }
        return out;
    }

    private static int commonPrefix( final String a, final int a0, final int a1, final String b, final int b0, final int b1 ) {
        final int max = Math.min( a1 - a0, b1 - b0 );
        int i = 0;
        while ( i < max && a.charAt( a0 + i ) == b.charAt( b0 + i ) ) {
            i++;
        }
        return i;
    }

    private static int commonSuffix( final String a, final int a0, final int a1, final String b, final int b0, final int b1 ) {
        final int max = Math.min( a1 - a0, b1 - b0 );
        int i = 0;
        while ( i < max && a.charAt( a1 - 1 - i ) == b.charAt( b1 - 1 - i ) ) {
            i++;
        }
        return i;
    }

    private static boolean insidePair( final String s, final int pos ) {
        return pos > 0 && pos < s.length() && Character.isHighSurrogate( s.charAt( pos - 1 ) )
                && Character.isLowSurrogate( s.charAt( pos ) );
    }

    /** Widens boundaries off surrogate pairs, then merges hunks the widening made touch or overlap. */
    private static List< int[] > widenAndMerge( final String a, final String b, final List< int[] > hunks ) {
        final List< int[] > out = new ArrayList<>();
        for ( final int[] h : hunks ) {
            while ( h[ 0 ] > 0 && ( insidePair( a, h[ 0 ] ) || insidePair( b, h[ 2 ] ) ) ) {
                h[ 0 ]--;
                h[ 2 ]--;
            }
            while ( h[ 1 ] < a.length() && ( insidePair( a, h[ 1 ] ) || insidePair( b, h[ 3 ] ) ) ) {
                h[ 1 ]++;
                h[ 3 ]++;
            }
            final int[] last = out.isEmpty() ? null : out.get( out.size() - 1 );
            if ( last != null && h[ 0 ] <= last[ 1 ] ) {
                last[ 1 ] = Math.max( last[ 1 ], h[ 1 ] );
                last[ 3 ] = Math.max( last[ 3 ], h[ 3 ] );
            } else {
                out.add( h );
            }
        }
        return out;
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
