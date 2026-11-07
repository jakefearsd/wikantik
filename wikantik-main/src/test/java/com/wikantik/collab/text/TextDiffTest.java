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
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDiffTest {

    private static final String[] PIECES = { "a", "b", "\n", "\n\n", " ", "😀", "😁", "xyz", "" };

    private final List< String > warns = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void captureLog() {
        appender = new AbstractAppender( "textDiffTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                if ( e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN ) ) {
                    warns.add( e.getMessage().getFormattedMessage() );
                }
            }
        };
        appender.start();
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        final org.apache.logging.log4j.core.config.Configuration config = ctx.getConfiguration();
        final org.apache.logging.log4j.core.config.LoggerConfig lc =
                org.apache.logging.log4j.core.config.LoggerConfig.newBuilder().setAdditivity( false )
                        .setLevel( org.apache.logging.log4j.Level.WARN ).setLoggerName( TextDiff.class.getName() )
                        .setIncludeLocation( "false" ).setRefs( new org.apache.logging.log4j.core.config.AppenderRef[ 0 ] )
                        .setConfig( config ).build();
        lc.addAppender( appender, org.apache.logging.log4j.Level.WARN, null );
        config.addLogger( TextDiff.class.getName(), lc );
        ctx.updateLoggers();
    }

    @AfterEach
    void releaseLog() {
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( TextDiff.class.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    /**
     * A multi-hunk line edit. Kept, deleted and inserted lines are unique and start and end with different letters
     * (K..k, D..d, I..i), so no hunk can slide and the optimal line diff has exactly one hunk per edited region.
     */
    private record MultiHunk( String a, String b, List< int[] > spans ) {
    }

    private static String line( final Random r, final char first, final char last, final int seq, final int n ) {
        return first + Integer.toString( seq ) + "." + n + "-" + Integer.toString( r.nextInt( 1 << 20 ), 36 )
                + ( r.nextInt( 5 ) == 0 ? " \uD83D\uDE00 " : "" ) + last + "\n";
    }

    private static MultiHunk multiHunk( final Random r, final int seq ) {
        final int n = 6 + r.nextInt( 50 );
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        final List< int[] > spans = new ArrayList<>();
        int i = 0;
        int fresh = 0;
        while ( i < n ) {
            final boolean edit = i > 0 && r.nextInt( 4 ) == 0;   // every hunk follows at least one kept line
            if ( !edit ) {
                final String kept = line( r, 'K', 'k', seq, i++ );
                a.append( kept );
                b.append( kept );
                continue;
            }
            final int del = Math.min( r.nextInt( 4 ), n - i );
            final int ins = del == 0 ? 1 + r.nextInt( 3 ) : r.nextInt( 4 );   // line counts change
            final int from = a.length();
            for ( int k = 0; k < del; k++ ) {
                a.append( line( r, 'D', 'd', seq, i + k ) );
            }
            for ( int k = 0; k < ins; k++ ) {
                b.append( line( r, 'I', 'i', seq, fresh++ ) );
            }
            spans.add( new int[] { from, a.length() } );
            i += del;
            if ( i < n ) {   // a kept line closes the hunk
                final String kept = line( r, 'K', 'k', seq, i++ );
                a.append( kept );
                b.append( kept );
            }
        }
        return new MultiHunk( a.toString(), b.toString(), spans );
    }

    @Test
    void randomMultiHunkLineEditsDiffToOneRangePerHunkInsideIt() {
        final Random r = new Random( 41 );
        int hunks = 0;
        for ( int seq = 0; seq < 2000; seq++ ) {
            final MultiHunk m = multiHunk( r, seq );
            final ChangeSet cs = TextDiff.changeSet( m.a(), m.b() );
            assertEquals( m.b(), cs.apply( m.a() ), "case " + seq );
            assertFalse( CollabText.splitsSurrogatePair( m.a(), cs ), "case " + seq );
            final List< int[] > touched = cs.touchedRanges();
            final int c = seq;
            assertEquals( m.spans().size(), touched.size(), () -> "case " + c + ": one range per hunk; a=" + m.a()
                    + " b=" + m.b() + " spans=" + m.spans().stream().map( java.util.Arrays::toString ).toList() + " touched="
                    + touched.stream().map( java.util.Arrays::toString ).toList() );
            for ( int k = 0; k < touched.size(); k++ ) {
                final int[] t = touched.get( k );
                final int[] span = m.spans().get( k );
                assertTrue( t[ 0 ] >= span[ 0 ] && t[ 1 ] <= span[ 1 ], "case " + seq + " hunk " + k + ": range ["
                        + t[ 0 ] + ", " + t[ 1 ] + "] outside its span [" + span[ 0 ] + ", " + span[ 1 ] + "]" );
            }
            hunks += touched.size();
        }
        assertTrue( hunks > 2000, "the generator produced multi-hunk edits: " + hunks );
        assertTrue( warns.isEmpty(), "no fallback under the default cap: " + warns );
    }

    @Test
    void aCommonPrefixEndingMidLineStillDiffsExactly() {
        // the shared "A" ends inside a line: the trim must not hand the line diff a partial line
        final String a = "AB\nQ\n";
        final String b = "AC\nAB\nQ\n";
        final ChangeSet cs = TextDiff.changeSet( a, b );
        assertEquals( b, cs.apply( a ) );
        assertEquals( 1, cs.touchedRanges().size(), "one insertion" );
        // the same shape between kept lines, with a mid-line prefix and a mid-line suffix around the edit
        final String c = "K\nAB\nQ\nAB\nZ\n";
        final String d = "K\nAC\nAB\nQ\nAB\nZ\n";
        final ChangeSet cd = TextDiff.changeSet( c, d );
        assertEquals( d, cd.apply( c ) );
        assertEquals( 1, cd.touchedRanges().size(), "one insertion" );
    }

    /** Lines from a tiny alphabet: no unique column-0 marker, many repeated lines and shared prefixes and suffixes. */
    private static String plainLines( final Random r, final int n ) {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < n; i++ ) {
            final int len = r.nextInt( 4 );
            for ( int k = 0; k < len; k++ ) {
                sb.append( "AB \uD83D\uDE00".charAt( r.nextInt( 3 ) ) );
            }
            if ( r.nextInt( 9 ) == 0 ) {
                sb.append( "\uD83D\uDE00" );
            }
            sb.append( '\n' );
        }
        return sb.toString();
    }

    @Test
    void randomLineEditsWithoutUniqueMarkersApplyExactly() {
        final Random r = new Random( 47 );
        for ( int seq = 0; seq < 3000; seq++ ) {
            final String[] lines = plainLines( r, 2 + r.nextInt( 30 ) ).split( "(?<=\n)" );
            final List< String > b = new ArrayList<>( List.of( lines ) );
            for ( int k = 1 + r.nextInt( 4 ); k > 0; k-- ) {   // multi-hunk line inserts, deletes and replacements
                final int at = r.nextInt( b.size() + 1 );
                final int del = Math.min( r.nextInt( 3 ), b.size() - at );
                b.subList( at, at + del ).clear();
                b.addAll( at, List.of( plainLines( r, r.nextInt( 3 ) ).split( "(?<=\n)" ) ).stream()
                        .filter( x -> !x.isEmpty() ).toList() );
            }
            final String from = String.join( "", lines );
            final String to = String.join( "", b );
            final ChangeSet cs = TextDiff.changeSet( from, to );
            assertEquals( to, cs.apply( from ), "case " + seq );
            assertFalse( CollabText.splitsSurrogatePair( from, cs ), "case " + seq );
            for ( final int[] t : cs.touchedRanges() ) {
                assertTrue( t[ 0 ] <= t[ 1 ] && t[ 1 ] <= from.length(), "case " + seq );
            }
        }
        assertTrue( warns.isEmpty(), "no fallback: " + warns );
    }

    @Test
    void theCoarseFallbackIsExactAndWarns() {
        final Random r = new Random( 43 );
        int fellBack = 0;
        for ( int seq = 0; seq < 300; seq++ ) {
            final MultiHunk m = multiHunk( r, seq );
            final int before = warns.size();
            final ChangeSet cs = TextDiff.changeSet( m.a(), m.b(), 1 );
            assertEquals( m.b(), cs.apply( m.a() ), "case " + seq );
            if ( m.spans().size() > 1 ) {
                assertEquals( 1, cs.touchedRanges().size(), "case " + seq + ": one coarse span" );
                assertEquals( before + 1, warns.size(), "case " + seq + ": the fallback is logged" );
                assertTrue( warns.get( warns.size() - 1 ).contains( "exceeded 1 edits" ), warns.get( warns.size() - 1 ) );
                fellBack++;
            }
        }
        assertTrue( fellBack > 50, "the fallback ran: " + fellBack );
    }

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
        final long base = bestOf( () -> middleEdit( 65536 ) );
        final long big = bestOf( () -> middleEdit( 262144 ) );
        System.out.println( "TextDiff middle edit base " + base + "ns, 4x " + big + "ns" );
        assertTrue( big < 500_000_000L, "256 KB diff took " + big / 1_000_000 + " ms" );
        if ( base > 5_000_000L ) {
            assertTrue( ( double ) big / base < 8, "4x input cost " + ( double ) big / base + "x" );
        }
    }

    @Test
    void editsNearBothEndsOfAOneLine256KbPageRefineInBoundedTime() {
        final String a = "p" + "x".repeat( 262144 ) + "q";
        final String b = "P" + "x".repeat( 262144 ) + "Q";
        final long t = bestOf( () -> check( a, b ) );
        System.out.println( "TextDiff both-ends single-line edit " + t + "ns" );
        assertTrue( t < 500_000_000L, "took " + t / 1_000_000 + " ms" );
        assertEquals( 1, TextDiff.changeSet( a, b ).touchedRanges().size(), "one line hunk: prefix/suffix trim only" );
    }

    @Test
    void scatteredChangesAcross256KbOfShortLinesDiffInBoundedTime() {
        final Random r = new Random( 7 );
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for ( int i = 0; i < 26000; i++ ) {
            final String line = String.format( "line%06d\n", i );
            a.append( line );
            b.append( r.nextInt( 100 ) == 0 ? String.format( "LINE%06d\n", i ) : line );
        }
        final long t = bestOf( () -> check( a.toString(), b.toString() ) );
        System.out.println( "TextDiff scattered 1% changes " + t + "ns" );
        assertTrue( t < 500_000_000L, "took " + t / 1_000_000 + " ms" );
    }

    @Test
    void aCompletelyRewrittenLargePageDiffsInBoundedTime() {
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for ( int i = 0; i < 20000; i++ ) {
            a.append( "a" ).append( i ).append( '\n' );
            b.append( "b" ).append( i ).append( '\n' );
        }
        final long t = bestOf( () -> check( a.toString(), b.toString() ) );
        System.out.println( "TextDiff full rewrite " + t + "ns" );
        assertTrue( t < 1_000_000_000L, "took " + t / 1_000_000 + " ms" );
    }

    @Test
    void anEditDistanceBeyondTheCapFallsBackToOneCoarseSpan() {
        final String a = "keep\nl1\nl2\nl3\nl4\nkeep2\n";
        final String b = "keep\nm1\nm2\nm3\nm4\nkeep2\n";
        final ChangeSet cs = TextDiff.changeSet( a, b, 2 );
        assertEquals( b, cs.apply( a ) );
        assertEquals( 1, cs.touchedRanges().size() );
    }

    @Test
    void growingARunTouchesOnlyTheInsertedPosition() {
        final List< int[] > t = TextDiff.changeSet( "aa", "aaa" ).touchedRanges();
        assertEquals( 1, t.size() );
        assertEquals( t.get( 0 )[ 0 ], t.get( 0 )[ 1 ] );
        assertEquals( "aaa", TextDiff.changeSet( "aa", "aaa" ).apply( "aa" ) );
    }

    @Test
    void aFinalLineWithoutANewlineDiffsExactly() {
        final String a = "one\ntwo\nthree";
        final String b = "one\ntwo\nthree!";
        final List< int[] > t = TextDiff.changeSet( a, b ).touchedRanges();
        assertEquals( 1, t.size() );
        assertArrayEquals( new int[] { 13, 13 }, t.get( 0 ) );
        final String c = "one\nTWO\nthree";
        assertEquals( c, TextDiff.changeSet( a, c ).apply( a ) );
    }

    @Test
    void separatedEditsYieldExactlyOneRangePerEdit() {
        final String a = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n";
        final String b = "alpha\nbeXa\ngamma\ndelta\nepsilon\nzetY\n";
        final List< int[] > t = TextDiff.changeSet( a, b ).touchedRanges();
        assertEquals( 2, t.size() );
        assertArrayEquals( new int[] { 8, 9 }, t.get( 0 ) );
        assertArrayEquals( new int[] { 34, 35 }, t.get( 1 ) );
    }

    @Test
    void aBoundaryInsideASurrogatePairWidensToTheWholePair() {
        final String a = "x\uD83D\uDE00y";
        final String b = "x\uD83D\uDE01y";
        final ChangeSet cs = TextDiff.changeSet( a, b );
        assertEquals( b, cs.apply( a ) );
        assertFalse( CollabText.splitsSurrogatePair( a, cs ) );
        assertArrayEquals( new int[] { 1, 3 }, cs.touchedRanges().get( 0 ) );
    }

    @Test
    void widenedNeighbouringHunksMergeIntoOne() {
        final String a = "\uD83D\uDE00\n\uD83D\uDE00\n";
        final String b = "\uD83D\uDE01\n\uD83D\uDE01\n";
        final ChangeSet cs = TextDiff.changeSet( a, b );
        assertEquals( b, cs.apply( a ) );
        assertFalse( CollabText.splitsSurrogatePair( a, cs ) );
    }

    private static void middleEdit( final int n ) {
        check( "x".repeat( n / 2 ) + "a" + "y".repeat( n / 2 ), "x".repeat( n / 2 ) + "b" + "y".repeat( n / 2 ) );
    }

    private static void check( final String a, final String b ) {
        assertEquals( b, TextDiff.changeSet( a, b ).apply( a ) );
    }

    private static long bestOf( final Runnable r ) {
        for ( int i = 0; i < 3; i++ ) {
            r.run();
        }
        long best = Long.MAX_VALUE;
        for ( int i = 0; i < 5; i++ ) {
            final long t0 = System.nanoTime();
            r.run();
            best = Math.min( best, System.nanoTime() - t0 );
        }
        return best;
    }
}
