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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PercentilesTest {

    private static List< Duration > minutes( final Integer... values ) {
        final List< Duration > out = new ArrayList<>();
        for ( final Integer v : values ) {
            out.add( v == null ? null : Duration.ofMinutes( v ) );
        }
        return out;
    }

    private static void check( final List< Duration > values, final int n, final long p50, final long p85 ) {
        final Durations d = Percentiles.of( values );
        assertEquals( n, d.n() );
        assertEquals( Duration.ofMinutes( p50 ), d.p50() );
        assertEquals( Duration.ofMinutes( p85 ), d.p85() );
    }

    @Test
    void aSingleValueIsBothPercentiles() {
        check( minutes( 5 ), 1, 5, 5 );
    }

    @Test
    void twoValues() {
        check( minutes( 1, 2 ), 2, 1, 2 );
    }

    @Test
    void oneToTwenty() {
        check( minutes( IntStream.rangeClosed( 1, 20 ).boxed().toArray( Integer[]::new ) ), 20, 10, 17 );
    }

    @Test
    void theRankIsIntegerMathNotFloatingPoint() {
        // 85 * 20 / 100.0 is 17.000000000000004 in floating point: its ceiling is 18, the rank is 17
        assertEquals( Duration.ofMinutes( 17 ), Percentiles.nearestRank( minutes( IntStream.rangeClosed( 1, 20 )
            .boxed().toArray( Integer[]::new ) ), 85 ) );
        for ( int n = 1; n <= 200; n++ ) {
            final List< Duration > sorted = new ArrayList<>();
            for ( int i = 1; i <= n; i++ ) {
                sorted.add( Duration.ofMinutes( i ) );
            }
            for ( final int p : new int[] { 1, 50, 85, 99, 100 } ) {
                final long rank = ( p * ( long ) n + 99 ) / 100;
                assertEquals( Duration.ofMinutes( Math.max( 1, Math.min( n, rank ) ) ),
                    Percentiles.nearestRank( sorted, p ), "n=" + n + " p=" + p );
            }
        }
    }

    @Test
    void unsortedInputIsSorted() {
        check( minutes( 3, 1, 2 ), 3, 2, 3 );
    }

    @Test
    void emptyIsNone() {
        assertSame( Durations.NONE, Percentiles.of( List.of() ) );
        assertNull( Durations.NONE.p50() );
        assertEquals( 0, Durations.NONE.n() );
    }

    @Test
    void nullsAndNegativesAreDropped() {
        check( minutes( null, -1, 4 ), 1, 4, 4 );
        assertSame( Durations.NONE, Percentiles.of( Arrays.asList( null, Duration.ofMinutes( -3 ) ) ) );
    }
}
