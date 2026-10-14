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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A set of half-open character ranges {@code [start, end)} with a fast "is this offset inside any of them" question:
 * the ranges are sorted and merged once, then each query is a binary search. Checking every candidate against every
 * range instead is quadratic on a large page with many code spans and many candidates, and that check now runs on
 * every read.
 *
 * <p>The ranges need not be sorted, disjoint or non-empty on the way in — a Markdown parser reports them in tree order
 * and a hand scanner in whatever order it found them.
 */
public final class OffsetRanges {

    private final int[] starts;
    private final int[] ends;

    private OffsetRanges( final int[] starts, final int[] ends ) {
        this.starts = starts;
        this.ends = ends;
    }

    /**
     * @param ranges {@code [start, end)} pairs, in any order; empty and reversed pairs are ignored
     * @return the merged set
     */
    public static OffsetRanges of( final List< int[] > ranges ) {
        final List< int[] > sorted = new ArrayList<>( ranges.size() );
        for ( final int[] range : ranges ) {
            if ( range[ 1 ] > range[ 0 ] ) {
                sorted.add( range );
            }
        }
        sorted.sort( Comparator.comparingInt( r -> r[ 0 ] ) );
        final int[] mergedStarts = new int[ sorted.size() ];
        final int[] mergedEnds = new int[ sorted.size() ];
        int count = 0;
        for ( final int[] range : sorted ) {
            if ( count > 0 && range[ 0 ] <= mergedEnds[ count - 1 ] ) {
                mergedEnds[ count - 1 ] = Math.max( mergedEnds[ count - 1 ], range[ 1 ] );
            } else {
                mergedStarts[ count ] = range[ 0 ];
                mergedEnds[ count ] = range[ 1 ];
                count++;
            }
        }
        return new OffsetRanges( Arrays.copyOf( mergedStarts, count ), Arrays.copyOf( mergedEnds, count ) );
    }

    /** True if {@code offset} falls inside any range. */
    public boolean contains( final int offset ) {
        int low = 0;
        int high = starts.length - 1;
        while ( low <= high ) {
            final int mid = ( low + high ) >>> 1;
            if ( starts[ mid ] > offset ) {
                high = mid - 1;
            } else if ( ends[ mid ] <= offset ) {
                low = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }
}
