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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDiffTest {

    private static final String[] PIECES = { "a", "b", "\n", "\n\n", " ", "😀", "😁", "xyz", "" };

    private static String random( final Random r ) {
        final StringBuilder sb = new StringBuilder();
        final int n = r.nextInt( 25 );
        for ( int i = 0; i < n; i++ ) {
            sb.append( PIECES[ r.nextInt( PIECES.length ) ] );
        }
        return sb.toString();
    }

    private static String mutate( final Random r, final String s ) {
        final StringBuilder sb = new StringBuilder( s );
        for ( int k = r.nextInt( 4 ); k > 0; k-- ) {
            final int at = r.nextInt( sb.length() + 1 );
            final int del = Math.min( r.nextInt( 4 ), sb.length() - at );
            sb.replace( at, at + del, PIECES[ r.nextInt( PIECES.length ) ] );
        }
        return sb.toString();
    }

    @Test
    void seededPairsApplyExactlyAndNeverSplitASurrogatePair() {
        final Random r = new Random( 6 );
        for ( int i = 0; i < 3000; i++ ) {
            final String a = i % 50 == 0 ? "" : random( r );
            final String b = i % 70 == 0 ? "" : ( i % 2 == 0 ? random( r ) : mutate( r, a ) );
            final ChangeSet cs = TextDiff.changeSet( a, b );
            assertEquals( b, cs.apply( a ), "pair " + i );
            assertFalse( CollabText.splitsSurrogatePair( a, cs ), "pair " + i );
        }
    }

    @Test
    void anEditInTheMiddleOfALineTouchesOnlyThatSpan() {
        final String a = "first line\nsecond line here\nthird line\n";
        final String b = "first line\nsecond LINE here\nthird line\n";
        final List< int[] > touched = TextDiff.changeSet( a, b ).touchedRanges();
        assertEquals( 1, touched.size() );
        assertArrayEquals( new int[] { 18, 22 }, touched.get( 0 ) );
    }

    @Test
    void carriageReturnIsACallerBug() {
        assertThrows( IllegalArgumentException.class, () -> TextDiff.changeSet( "a\r\nb", "a\nb" ) );
    }

    @Test
    void aLongSingleLineEditDiffsInLinearTime() {
        for ( int i = 0; i < 20; i++ ) {
            time( 65536 );
        }
        final long base = best( 65536 );
        final long big = best( 262144 );
        final double ratio = ( double ) big / Math.max( 1, base );
        System.out.println( "TextDiff linear-time ratio 4x input = " + ratio + " (base " + base + "ns, big " + big + "ns)" );
        assertTrue( ratio < 8, "4x input cost " + ratio + "x" );
        assertTrue( big < 200_000_000L, "256 KB diff took " + big / 1_000_000 + " ms" );
    }

    private static long best( final int n ) {
        long best = Long.MAX_VALUE;
        for ( int i = 0; i < 7; i++ ) {
            best = Math.min( best, time( n ) );
        }
        return best;
    }

    private static long time( final int n ) {
        final String a = "x".repeat( n / 2 ) + "a" + "y".repeat( n / 2 );
        final String b = "x".repeat( n / 2 ) + "b" + "y".repeat( n / 2 );
        final long t0 = System.nanoTime();
        final ChangeSet cs = TextDiff.changeSet( a, b );
        final long t = System.nanoTime() - t0;
        assertEquals( b.length(), cs.newLength() );
        return t;
    }
}
