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

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeded property loops over {@link ChangeSet}: the algebra collaborative editing relies on, checked on far more
 * shapes than the recorded corpus holds.
 */
class ChangeSetPropertiesTest {

    private static final long SEED = 128;
    private static final int ITERATIONS = 5_000;

    private static final String[] ALPHABET = {
        "a", "b", "c", "x", "y", " ", ".", "#", "\n", "\n", "中", "文", "😀", "𝔸", "é",
    };

    private static String randomText( final SplittableRandom rnd, final int maxLen ) {
        final int target = rnd.nextInt( maxLen + 1 );
        final StringBuilder sb = new StringBuilder();
        while ( sb.length() < target ) {
            sb.append( ALPHABET[ rnd.nextInt( ALPHABET.length ) ] );
        }
        return sb.toString();
    }

    /** A random set on a document of {@code length}: 0-4 non-overlapping replacements, built right to left. */
    private static ChangeSet randomSet( final SplittableRandom rnd, final int length ) {
        final int n = rnd.nextInt( 5 );
        final int[] cuts = new int[ n * 2 ];
        for ( int i = 0; i < cuts.length; i++ ) {
            final double r = rnd.nextDouble();
            cuts[ i ] = r < 0.12 ? 0 : r < 0.24 ? length : rnd.nextInt( length + 1 );
        }
        java.util.Arrays.sort( cuts );
        ChangeSet set = ChangeSet.empty( length );
        for ( int i = cuts.length - 2; i >= 0; i -= 2 ) {
            final String insert = rnd.nextDouble() < 0.7 ? randomText( rnd, 6 ) : "";
            set = set.compose( ChangeSet.replace( set.newLength(), cuts[ i ], cuts[ i + 1 ], insert ) );
        }
        return set;
    }

    private static String randomDoc( final SplittableRandom rnd ) {
        return rnd.nextInt( 10 ) == 0 ? "" : randomText( rnd, 120 );
    }

    @Test
    void applyHonoursTheLengthsItDeclares() {
        final SplittableRandom rnd = new SplittableRandom( SEED );
        int checked = 0;
        for ( int i = 0; i < ITERATIONS; i++ ) {
            final String doc = randomDoc( rnd );
            final ChangeSet a = randomSet( rnd, doc.length() );
            assertEquals( doc.length(), a.length(), "iteration " + i );
            assertEquals( a.newLength(), a.apply( doc ).length(), "iteration " + i );
            checked++;
        }
        assertEquals( ITERATIONS, checked );
    }

    @Test
    void composeIsAssociative() {
        final SplittableRandom rnd = new SplittableRandom( SEED );
        for ( int i = 0; i < ITERATIONS; i++ ) {
            final String doc = randomDoc( rnd );
            final ChangeSet a = randomSet( rnd, doc.length() );
            final ChangeSet b = randomSet( rnd, a.newLength() );
            final ChangeSet c = randomSet( rnd, b.newLength() );
            final String stepwise = c.apply( b.apply( a.apply( doc ) ) );
            final ChangeSet left = a.compose( b ).compose( c );
            final ChangeSet right = a.compose( b.compose( c ) );
            assertEquals( stepwise, left.apply( doc ), "(a.b).c, iteration " + i );
            assertEquals( stepwise, right.apply( doc ), "a.(b.c), iteration " + i );
            assertEquals( left.newLength(), right.newLength(), "iteration " + i );
        }
    }

    @Test
    void concurrentSetsConvergeWhicheverArrivesFirst() {
        final SplittableRandom rnd = new SplittableRandom( SEED );
        for ( int i = 0; i < ITERATIONS; i++ ) {
            final String doc = randomDoc( rnd );
            final ChangeSet a = randomSet( rnd, doc.length() );
            final ChangeSet b = randomSet( rnd, doc.length() );
            final String viaA = a.compose( b.map( a ) ).apply( doc );
            final String viaB = b.compose( a.map( b, true ) ).apply( doc );
            assertEquals( viaA, viaB, "diamond, iteration " + i + " doc=" + doc + " a=" + ChangeSetJson.toJson( a )
                    + " b=" + ChangeSetJson.toJson( b ) );
            assertEquals( viaA, b.map( a ).apply( a.apply( doc ) ), "iteration " + i );
        }
    }

    @Test
    void jsonRoundTripsExactly() {
        final SplittableRandom rnd = new SplittableRandom( SEED );
        for ( int i = 0; i < ITERATIONS; i++ ) {
            final String doc = randomDoc( rnd );
            final ChangeSet a = randomSet( rnd, doc.length() );
            final JsonArray json = ChangeSetJson.toJson( a );
            final JsonArray reparsed = JsonParser.parseString( json.toString() ).getAsJsonArray();
            final ChangeSet back = ChangeSetJson.fromJson( reparsed );
            assertEquals( json, ChangeSetJson.toJson( back ), "iteration " + i );
            assertEquals( a.apply( doc ), back.apply( doc ), "iteration " + i );
        }
    }

    @Test
    void anEmptySetIsTheIdentity() {
        final ChangeSet e = ChangeSet.empty( 3 );
        assertTrue( e.empty() );
        assertTrue( ChangeSet.empty( 0 ).empty() );
        assertEquals( 3, e.length() );
        assertEquals( 3, e.newLength() );
        assertEquals( "abc", e.apply( "abc" ) );
        assertEquals( "[3]", ChangeSetJson.toJson( e ).toString() );
        assertEquals( "[]", ChangeSetJson.toJson( ChangeSet.empty( 0 ) ).toString() );
        final ChangeSet r = ChangeSet.replace( 3, 1, 2, "XY" );
        assertFalse( r.empty() );
        assertSame( r, e.compose( r ) );
        assertSame( r, r.compose( ChangeSet.empty( 4 ) ) );
        assertSame( r, r.map( e ) );
        assertEquals( "[4]", ChangeSetJson.toJson( e.map( r ) ).toString() );
    }

    @Test
    void replaceBuildsTheSameShapeAsChangeSetOf() {
        assertEquals( "[1,[1,\"XY\"],1]", ChangeSetJson.toJson( ChangeSet.replace( 3, 1, 2, "XY" ) ).toString() );
        assertEquals( "[[0,\"a\",\"b\"],3]", ChangeSetJson.toJson( ChangeSet.replace( 3, 0, 0, "a\nb" ) ).toString() );
        assertEquals( "[2,[1]]", ChangeSetJson.toJson( ChangeSet.replace( 3, 2, 3, "" ) ).toString() );
        assertEquals( "[3]", ChangeSetJson.toJson( ChangeSet.replace( 3, 2, 2, "" ) ).toString() );
        // CodeMirror splits inserted text on \r\n, \r and \n alike: each is one line break
        final ChangeSet crlf = ChangeSet.replace( 0, 0, 0, "a\r\nb\rc" );
        assertEquals( "[[0,\"a\",\"b\",\"c\"]]", ChangeSetJson.toJson( crlf ).toString() );
        assertEquals( "a\nb\nc", crlf.apply( "" ) );
        assertThrows( IllegalArgumentException.class, () -> ChangeSet.replace( 3, 2, 1, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> ChangeSet.replace( 3, -1, 1, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> ChangeSet.replace( 3, 1, 4, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> ChangeSet.replace( -1, 0, 0, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> ChangeSet.empty( -1 ) );
        assertEquals( "[1,[1,\"\",\"\"],1]", ChangeSetJson.toJson( ChangeSet.replace( 3, 1, 2, "\n" ) ).toString() );
        assertEquals( "[1,[1],1]", ChangeSetJson.toJson( ChangeSet.replace( 3, 1, 2, null ) ).toString() );
    }

    @Test
    void mismatchedLengthsAreRefused() {
        final ChangeSet three = ChangeSet.replace( 3, 0, 1, "x" );
        final ChangeSet four = ChangeSet.replace( 4, 0, 1, "x" );
        final IllegalArgumentException apply = assertThrows( IllegalArgumentException.class, () -> three.apply( "abcd" ) );
        assertTrue( apply.getMessage().contains( "length" ), apply.getMessage() );
        assertThrows( IllegalArgumentException.class, () -> three.compose( four ) );
        assertThrows( IllegalArgumentException.class, () -> three.map( four ) );
        assertThrows( IllegalArgumentException.class, () -> three.map( four, true ) );
        assertThrows( IllegalArgumentException.class, () -> three.mapPos( 4, 1 ) );
        assertThrows( IllegalArgumentException.class, () -> three.mapPos( -1, 1 ) );
        assertThrows( NullPointerException.class, () -> three.apply( null ) );
    }

    @Test
    void anEmptySideDoesNotExcuseALengthMismatch() {
        // Deliberate divergence: CodeMirror's compose/map return the other set unchecked when one side is empty,
        // handing back a set of the wrong length. The server relies on these throwing instead.
        assertThrows( IllegalArgumentException.class,
                () -> ChangeSet.empty( 5 ).compose( ChangeSet.replace( 7, 0, 1, "x" ) ) );
        assertThrows( IllegalArgumentException.class,
                () -> ChangeSet.replace( 3, 0, 1, "x" ).map( ChangeSet.empty( 4 ) ) );
    }

    @Test
    void mapPosFollowsAssociationAtAnInsertion() {
        final ChangeSet insertAt1 = ChangeSet.replace( 3, 1, 1, "XY" );
        assertEquals( 1, insertAt1.mapPos( 1, -1 ) );
        assertEquals( 3, insertAt1.mapPos( 1, 1 ) );
        assertEquals( 0, insertAt1.mapPos( 0, 1 ) );
        assertEquals( 5, insertAt1.mapPos( 3, -1 ) );
        final ChangeSet deleteMiddle = ChangeSet.replace( 5, 1, 4, "" );
        assertEquals( 1, deleteMiddle.mapPos( 2, -1 ) );
        assertEquals( 1, deleteMiddle.mapPos( 2, 1 ) );
        assertEquals( 2, deleteMiddle.mapPos( 5, 1 ) );
    }

    @Test
    void touchedRangesReportEachChangeInTheOldDocument() {
        assertTrue( ChangeSet.empty( 5 ).touchedRanges().isEmpty() );
        final ChangeSet two = ChangeSet.replace( 10, 7, 9, "" ).compose( ChangeSet.replace( 8, 1, 3, "abc" ) );
        final List< int[] > ranges = two.touchedRanges();
        assertEquals( 2, ranges.size() );
        assertArrayEquals( new int[] { 1, 3 }, ranges.get( 0 ) );
        assertArrayEquals( new int[] { 7, 9 }, ranges.get( 1 ) );
        // adjacent changes are reported as one range
        final ChangeSet adjacent = ChangeSetJson.fromJson( JsonParser.parseString( "[1,[1,\"a\"],[2],1]" ).getAsJsonArray() );
        final List< int[] > merged = new ArrayList<>( adjacent.touchedRanges() );
        assertEquals( 1, merged.size() );
        assertArrayEquals( new int[] { 1, 4 }, merged.get( 0 ) );
        assertArrayEquals( new int[] { 2, 2 }, ChangeSet.replace( 4, 2, 2, "z" ).touchedRanges().get( 0 ) );
    }

    @Test
    void valueSemanticsFollowTheRepresentation() {
        final ChangeSet a = ChangeSet.replace( 3, 1, 2, "XY" );
        final ChangeSet b = ChangeSetJson.fromJson( ChangeSetJson.toJson( a ) );
        assertEquals( a, b );
        assertEquals( a.hashCode(), b.hashCode() );
        assertFalse( a.equals( ChangeSet.replace( 3, 1, 2, "XZ" ) ) );
        assertFalse( a.equals( "x" ) );
        assertEquals( ChangeSetJson.toJson( a ).toString(), a.toString() );
    }
}
