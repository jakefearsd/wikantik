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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedTextTest {

    private static List< int[] > spans( final int... bounds ) {
        final List< int[] > out = new ArrayList<>();
        for ( int i = 0; i < bounds.length; i += 2 ) {
            out.add( new int[]{ bounds[ i ], bounds[ i + 1 ] } );
        }
        return out;
    }

    private static void assertSpans( final List< int[] > expected, final List< int[] > actual ) {
        assertEquals( expected.size(), actual.size(), "span count" );
        for ( int i = 0; i < expected.size(); i++ ) {
            assertArrayEquals( expected.get( i ), actual.get( i ), "span " + i );
        }
    }

    @Test
    void theSourceItselfMapsToItself() {
        final String source = "abc";
        final TrackedText t = TrackedText.of( source );
        assertSame( source, t.text() );
        assertEquals( 3, t.length() );
        assertEquals( 3, t.sourceLength() );
        for ( int i = 0; i < 3; i++ ) {
            assertEquals( i, t.toSource( i ) );
            assertEquals( i + 1, t.toSourceEnd( i + 1 ) );
            assertTrue( t.isCopied( i ) );
        }
        assertEquals( 0, t.toSourceEnd( 0 ) );
        assertEquals( 3, t.toSource( 3 ) );
        assertSpans( spans( 0, 3 ), t.sourceSpans( 0, 3 ) );
        assertTrue( t.sourceSpans( 1, 1 ).isEmpty() );
    }

    @Test
    void aCutIsSkippedOverBothWays() {
        final TrackedText t = TrackedText.of( "abcdef" ).builder().copy( 0, 2 ).copy( 4, 6 ).build();
        assertEquals( "abef", t.text() );
        assertEquals( 1, t.toSource( 1 ) );
        assertEquals( 4, t.toSource( 2 ), "the char after the cut is the source's e" );
        assertEquals( 2, t.toSourceEnd( 2 ), "an end after b stays before the cut" );
        assertEquals( 6, t.toSourceEnd( 4 ) );
        assertEquals( 6, t.toSource( 4 ) );
        assertSpans( spans( 1, 2, 4, 5 ), t.sourceSpans( 1, 3 ) );
        assertTrue( t.cutBefore( 2 ), "c and d were cut right before e" );
        assertFalse( t.cutBefore( 1 ) );
    }

    @Test
    void aCutAtTheStartIsSeenBeforePositionZero() {
        final TrackedText t = TrackedText.of( "xxab" ).builder().copy( 2, 4 ).build();
        assertEquals( "ab", t.text() );
        assertEquals( 0, t.toSourceEnd( 0 ) );
        assertEquals( 2, t.toSource( 0 ) );
        assertTrue( t.cutBefore( 0 ) );
        assertFalse( TrackedText.of( "ab" ).cutBefore( 0 ) );
    }

    @Test
    void anInsertedLiteralMapsToWhereItWasPut() {
        final TrackedText t = TrackedText.of( "abcdef" ).builder().copy( 0, 2 ).insert( "XY" ).copy( 2, 6 ).build();
        assertEquals( "abXYcdef", t.text() );
        assertFalse( t.isCopied( 2 ) );
        assertFalse( t.isCopied( 3 ) );
        assertEquals( 2, t.toSource( 2 ) );
        assertEquals( 2, t.toSource( 3 ) );
        assertEquals( 2, t.toSourceEnd( 4 ), "an end after the literal is where it was put" );
        assertEquals( 2, t.toSource( 4 ) );
        assertSpans( spans( 0, 6 ), t.sourceSpans( 0, 8 ) );
        assertSpans( spans( 1, 3 ), t.sourceSpans( 1, 5 ) );
        assertFalse( t.cutBefore( 4 ), "an insertion is no cut" );
    }

    @Test
    void editsOfAnEditMapToTheFirstSource() {
        final TrackedText once = TrackedText.of( "0123456789" ).builder().copy( 0, 3 ).copy( 5, 10 ).build();   // 01256789
        final TrackedText twice = once.builder().copy( 0, 2 ).insert( "-" ).copy( 4, 8 ).build();                // 01-6789
        assertEquals( "01-6789", twice.text() );
        assertEquals( 6, twice.toSource( 3 ) );
        assertEquals( 2, twice.toSource( 2 ), "the literal sits after 1, so before the source's 2" );
        assertEquals( 10, twice.toSourceEnd( 7 ) );
        assertSpans( spans( 0, 2, 6, 10 ), twice.sourceSpans( 0, 7 ) );
        assertEquals( 10, twice.sourceLength() );
    }

    @Test
    void aBuilderFromAPositionAnchorsItsFirstLiteralThere() {
        final TrackedText base = TrackedText.of( "head|body" );
        final TrackedText body = base.builder( 5 ).insert( "\n" ).copy( 5, 9 ).build();
        assertEquals( "\nbody", body.text() );
        assertEquals( 5, body.toSource( 0 ) );
        assertEquals( 5, body.toSourceEnd( 0 ) );
        final TrackedText empty = base.builder( 5 ).build();
        assertEquals( "", empty.text() );
        assertEquals( 5, empty.toSourceEnd( 0 ) );
        assertEquals( 5, empty.toSource( 0 ) );
    }

    @Test
    void concatenatedPartsKeepTheirOwnMaps() {
        final TrackedText base = TrackedText.of( "AAA---BBB" );
        final TrackedText head = base.builder().copy( 0, 1 ).build();
        final TrackedText mid = base.builder( 3 ).copy( 3, 6 ).build();
        final TrackedText tail = base.builder( 6 ).copy( 8, 9 ).build();
        final TrackedText all = TrackedText.concat( head, mid, tail );
        assertEquals( "A---B", all.text() );
        assertEquals( 3, all.toSource( 1 ) );
        assertEquals( 8, all.toSource( 4 ) );
        assertTrue( all.cutBefore( 1 ) );
        assertTrue( all.cutBefore( 4 ) );
        assertSpans( spans( 0, 1, 3, 6, 8, 9 ), all.sourceSpans( 0, 5 ) );
        final TrackedText lead = TrackedText.literal( "---\n", base, 0 );
        final TrackedText joined = TrackedText.concat( lead, tail );
        assertEquals( "---\nB", joined.text() );
        assertEquals( 0, joined.toSource( 2 ) );
        assertEquals( 8, joined.toSource( 4 ) );
    }

    @Test
    void concatenatingTextsOfDifferentSourcesIsRefused() {
        assertThrows( IllegalArgumentException.class, () -> TrackedText.concat( TrackedText.of( "ab" ), TrackedText.of( "abc" ) ) );
        assertThrows( IllegalArgumentException.class, TrackedText::concat );
    }

    @Test
    void outOfRangePositionsAreRefused() {
        final TrackedText t = TrackedText.of( "abc" );
        assertThrows( IndexOutOfBoundsException.class, () -> t.toSource( 4 ) );
        assertThrows( IndexOutOfBoundsException.class, () -> t.toSource( -1 ) );
        assertThrows( IndexOutOfBoundsException.class, () -> t.toSourceEnd( 4 ) );
        assertThrows( IndexOutOfBoundsException.class, () -> t.isCopied( 3 ) );
        assertThrows( IndexOutOfBoundsException.class, () -> t.sourceSpans( 2, 1 ) );
        assertThrows( IndexOutOfBoundsException.class, () -> t.builder().copy( 1, 4 ) );
        assertThrows( IndexOutOfBoundsException.class, () -> t.builder( 4 ) );
    }

    /** Random edits of random edits: every copied char is the source char it maps to, and the copied chars ascend. */
    @Test
    void randomEditChainsMapEveryCopiedCharBackToItself() {
        final Random random = new Random( 0x7EC7L );
        for ( int round = 0; round < 2_000; round++ ) {
            final StringBuilder sb = new StringBuilder();
            for ( int i = random.nextInt( 40 ); i > 0; i-- ) {
                sb.append( ( char ) ( 'a' + random.nextInt( 26 ) ) );
            }
            final String source = sb.toString();
            TrackedText t = TrackedText.of( source );
            for ( int step = random.nextInt( 5 ); step >= 0; step-- ) {
                final TrackedText.Builder b = t.builder();
                int at = 0;
                while ( at < t.length() ) {
                    final int to = Math.min( t.length(), at + 1 + random.nextInt( 6 ) );
                    if ( random.nextInt( 3 ) > 0 ) {
                        b.copy( at, to );
                    }
                    if ( random.nextInt( 4 ) == 0 ) {
                        b.insert( "#" );
                    }
                    at = to;
                }
                t = b.build();
            }
            int last = -1;
            for ( int i = 0; i < t.length(); i++ ) {
                if ( t.isCopied( i ) ) {
                    final int s = t.toSource( i );
                    assertEquals( source.charAt( s ), t.text().charAt( i ), "round " + round + " pos " + i );
                    assertTrue( s > last, "copied chars ascend" );
                    last = s;
                } else {
                    assertEquals( '#', t.text().charAt( i ) );
                }
            }
            final StringBuilder fromSpans = new StringBuilder();
            for ( final int[] span : t.sourceSpans( 0, t.length() ) ) {
                fromSpans.append( source, span[ 0 ], span[ 1 ] );
            }
            assertEquals( t.text().replace( "#", "" ), fromSpans.toString() );
        }
    }
}
