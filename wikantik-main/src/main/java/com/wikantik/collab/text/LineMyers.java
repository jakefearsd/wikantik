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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Forward Myers over interned line arrays with an edit-distance cap, so cost is bounded by O((N+M) * maxD). */
final class LineMyers {

    private LineMyers() {
    }

    /**
     * The differing line ranges as {@code {aFrom, aTo, bFrom, bTo}} (line indexes, ascending), or {@code null} when
     * the edit distance exceeds {@code maxD}.
     */
    static List< int[] > diff( final String[] aText, final String[] bText, final int maxD ) {
        final Map< String, Integer > ids = new HashMap<>();
        final int[] a = intern( aText, ids );
        final int[] b = intern( bText, ids );
        final int n = a.length;
        final int m = b.length;
        final int cap = Math.min( maxD, n + m );
        final int off = cap + 1;
        final int[] v = new int[ 2 * cap + 3 ];
        final List< int[] > trace = new ArrayList<>();
        for ( int d = 0; d <= cap; d++ ) {
            trace.add( Arrays.copyOfRange( v, off - d - 1, off + d + 2 ) );
            for ( int k = -d; k <= d; k += 2 ) {
                int x = takeDown( v, off, k, d ) ? v[ off + k + 1 ] : v[ off + k - 1 ] + 1;
                int y = x - k;
                while ( x < n && y < m && a[ x ] == b[ y ] ) {
                    x++;
                    y++;
                }
                v[ off + k ] = x;
                if ( x >= n && y >= m ) {
                    return backtrack( trace, n, m, d );
                }
            }
        }
        return null;
    }

    private static boolean takeDown( final int[] v, final int off, final int k, final int d ) {
        return k == -d || ( k != d && v[ off + k - 1 ] < v[ off + k + 1 ] );
    }

    private static List< int[] > backtrack( final List< int[] > trace, final int n, final int m, final int dEnd ) {
        final List< int[] > steps = new ArrayList<>();
        int x = n;
        int y = m;
        for ( int d = dEnd; d > 0; d-- ) {
            final int[] t = trace.get( d );   // index of k is k + d + 1
            final int k = x - y;
            final boolean down = k == -d || ( k != d && t[ k - 1 + d + 1 ] < t[ k + 1 + d + 1 ] );
            final int prevK = down ? k + 1 : k - 1;
            final int px = t[ prevK + d + 1 ];
            final int py = px - prevK;
            final int midX = down ? px : px + 1;
            final int midY = down ? py + 1 : py;
            steps.add( new int[] { px, py, midX, midY } );
            x = px;
            y = py;
        }
        return mergeSteps( steps );
    }

    /** Folds single-line edit steps (given last-first) into ascending hunks, joining steps that abut. */
    private static List< int[] > mergeSteps( final List< int[] > steps ) {
        final List< int[] > hunks = new ArrayList<>();
        for ( int i = steps.size() - 1; i >= 0; i-- ) {
            final int[] s = steps.get( i );
            final int[] last = hunks.isEmpty() ? null : hunks.get( hunks.size() - 1 );
            if ( last != null && last[ 1 ] == s[ 0 ] && last[ 3 ] == s[ 1 ] ) {
                last[ 1 ] = s[ 2 ];
                last[ 3 ] = s[ 3 ];
            } else {
                hunks.add( new int[] { s[ 0 ], s[ 2 ], s[ 1 ], s[ 3 ] } );
            }
        }
        return hunks;
    }

    private static int[] intern( final String[] lines, final Map< String, Integer > ids ) {
        final int[] out = new int[ lines.length ];
        for ( int i = 0; i < lines.length; i++ ) {
            out[ i ] = ids.computeIfAbsent( lines[ i ], k -> ids.size() );
        }
        return out;
    }
}
