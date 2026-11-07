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

import java.util.ArrayList;
import java.util.List;

/** String scanning and hunk-boundary helpers for {@link TextDiff}. */
final class HunkScan {

    private HunkScan() {
    }

    /** Offsets of each line start in {@code [from, to)}; lines keep their {@code \n}. */
    static int[] lineStarts( final String s, final int from, final int to ) {
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

    static String[] lines( final String s, final int[] starts, final int end ) {
        final String[] out = new String[ starts.length ];
        for ( int i = 0; i < starts.length; i++ ) {
            out[ i ] = s.substring( starts[ i ], i + 1 < starts.length ? starts[ i + 1 ] : end );
        }
        return out;
    }

    static int commonPrefix( final String a, final int a0, final int a1, final String b, final int b0, final int b1 ) {
        final int max = Math.min( a1 - a0, b1 - b0 );
        int i = 0;
        while ( i < max && a.charAt( a0 + i ) == b.charAt( b0 + i ) ) {
            i++;
        }
        return i;
    }

    static int commonSuffix( final String a, final int a0, final int a1, final String b, final int b0, final int b1 ) {
        final int max = Math.min( a1 - a0, b1 - b0 );
        int i = 0;
        while ( i < max && a.charAt( a1 - 1 - i ) == b.charAt( b1 - 1 - i ) ) {
            i++;
        }
        return i;
    }

    /** Widens boundaries off surrogate pairs, then merges hunks the widening made touch or overlap. */
    static List< int[] > widenAndMerge( final String a, final String b, final List< int[] > hunks ) {
        final List< int[] > out = new ArrayList<>();
        for ( final int[] h : hunks ) {
            while ( h[ 0 ] > 0 && ( CollabText.insidePair( a, h[ 0 ] ) || CollabText.insidePair( b, h[ 2 ] ) ) ) {
                h[ 0 ]--;
                h[ 2 ]--;
            }
            while ( h[ 1 ] < a.length() && ( CollabText.insidePair( a, h[ 1 ] ) || CollabText.insidePair( b, h[ 3 ] ) ) ) {
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
}
