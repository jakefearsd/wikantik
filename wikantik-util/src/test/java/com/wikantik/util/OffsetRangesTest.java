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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffsetRangesTest {

    @Test
    void anEmptySetContainsNothing() {
        assertFalse( OffsetRanges.of( List.of() ).contains( 0 ) );
    }

    @Test
    void rangesAreHalfOpen() {
        final OffsetRanges ranges = OffsetRanges.of( List.of( new int[]{ 3, 6 } ) );
        assertFalse( ranges.contains( 2 ) );
        assertTrue( ranges.contains( 3 ) );
        assertTrue( ranges.contains( 5 ) );
        assertFalse( ranges.contains( 6 ) );
    }

    @Test
    void unsortedOverlappingAndEmptyRangesAreNormalised() {
        final OffsetRanges ranges = OffsetRanges.of( List.of(
                new int[]{ 20, 25 }, new int[]{ 0, 4 }, new int[]{ 3, 8 }, new int[]{ 8, 9 }, new int[]{ 15, 15 }, new int[]{ 30, 28 } ) );
        for ( final int inside : new int[]{ 0, 3, 7, 8, 20, 24 } ) {
            assertTrue( ranges.contains( inside ), "offset " + inside );
        }
        for ( final int outside : new int[]{ 9, 10, 15, 19, 25, 28, 29, 30, 100, -1 } ) {
            assertFalse( ranges.contains( outside ), "offset " + outside );
        }
    }

    @Test
    void agreesWithTheObviousLinearCheckOnRandomRanges() {
        final Random random = new Random( 42L );
        for ( int round = 0; round < 500; round++ ) {
            final List< int[] > raw = new ArrayList<>();
            for ( int i = random.nextInt( 12 ); i > 0; i-- ) {
                final int start = random.nextInt( 100 );
                raw.add( new int[]{ start, start + random.nextInt( 15 ) - 2 } );
            }
            final OffsetRanges ranges = OffsetRanges.of( raw );
            for ( int offset = -2; offset < 120; offset++ ) {
                boolean expected = false;
                for ( final int[] r : raw ) {
                    expected |= offset >= r[ 0 ] && offset < r[ 1 ];
                }
                assertEquals( expected, ranges.contains( offset ), "round " + round + " offset " + offset );
            }
        }
    }

    @Test
    void hundredsOfThousandsOfQueriesAgainstHundredsOfThousandsOfRangesAreFast() {
        final List< int[] > raw = new ArrayList<>();
        for ( int i = 0; i < 300_000; i++ ) {
            raw.add( new int[]{ i * 10, i * 10 + 3 } );
        }
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> {
            final OffsetRanges ranges = OffsetRanges.of( raw );
            int hits = 0;
            for ( int offset = 0; offset < 3_000_000; offset += 2 ) {
                if ( ranges.contains( offset ) ) {
                    hits++;
                }
            }
            assertTrue( hits > 0 );
        } );
    }
}
