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
package com.wikantik.extractcli.configref;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boundary behaviour of {@link DescriptionRenderer} and {@link HoistScanner}. */
class ConfigRefHelpersTest {

    // ---- escapeProseDollars ----

    @Test
    void aDollarAtTheStartOfProseIsEscaped() {
        assertEquals( "\\$a", DescriptionRenderer.escapeProseDollars( "$a" ) );
    }

    @Test
    void aDollarAlreadyEscapedIsLeftAlone() {
        assertEquals( "x \\$a", DescriptionRenderer.escapeProseDollars( "x \\$a" ) );
        assertEquals( "\\$a", DescriptionRenderer.escapeProseDollars( "\\$a" ) );
    }

    @Test
    void dollarsInsideBackticksAreLiteralAndOutsideAreEscaped() {
        assertEquals( "`$x` and \\$y and `$z`", DescriptionRenderer.escapeProseDollars( "`$x` and $y and `$z`" ) );
    }

    @Test
    void anInnerDollarAfterAnOrdinaryCharacterIsEscaped() {
        assertEquals( "a\\$b", DescriptionRenderer.escapeProseDollars( "a$b" ) );
    }

    // ---- truncateAtWordBoundary ----

    @Test
    void textAtExactlyTheLimitIsReturnedUnchanged() {
        assertEquals( "abcde", DescriptionRenderer.truncateAtWordBoundary( "abcde", 5 ) );
        assertEquals( "abc", DescriptionRenderer.truncateAtWordBoundary( "abc", 5 ) );
    }

    @Test
    void textOneOverTheLimitIsCutAtTheLastSpaceAtOrBeforeIt() {
        assertEquals( "ab cd", DescriptionRenderer.truncateAtWordBoundary( "ab cd efg", 6 ) );
        assertEquals( "ab", DescriptionRenderer.truncateAtWordBoundary( "ab cdef", 5 ) );
    }

    @Test
    void aSpaceAtTheVeryLimitIsAValidCutPoint() {
        assertEquals( "abcde", DescriptionRenderer.truncateAtWordBoundary( "abcde fgh", 5 ) );
    }

    @Test
    void withNoSpaceTheTextIsHardCutAtTheLimit() {
        assertEquals( "abcde", DescriptionRenderer.truncateAtWordBoundary( "abcdefgh", 5 ) );
    }

    @Test
    void aLeadingSpaceOnlyIsNotAUsableCutPoint() {
        assertEquals( "abcd", DescriptionRenderer.truncateAtWordBoundary( " abcdefgh", 5 ).strip() );
    }

    // ---- renderDescription ----

    private static String words( final int n ) {
        return "w".repeat( n );
    }

    @Test
    void aDescriptionOfExactlyTheSummaryCapIsNotTruncated() {
        final String text = words( 155 );
        final DescriptionRenderer.DescriptionRender r = DescriptionRenderer.renderDescription( List.of( text ) );
        assertFalse( r.truncated() );
        assertEquals( text, r.summary() );
        assertEquals( text, r.full() );
    }

    @Test
    void aDescriptionOneOverTheCapIsTruncatedWithAnEllipsisAndKeepsTheFullText() {
        final String text = words( 156 );
        final DescriptionRenderer.DescriptionRender r = DescriptionRenderer.renderDescription( List.of( text ) );
        assertTrue( r.truncated() );
        assertEquals( words( 155 ) + " …", r.summary() );
        assertEquals( text, r.full() );
    }

    @Test
    void theSummaryEndsAtTheFirstSentenceWhenItFitsTheCap() {
        final String first = "First sentence.";
        final String text = first + " " + words( 160 );
        final DescriptionRenderer.DescriptionRender r = DescriptionRenderer.renderDescription( List.of( text ) );
        assertEquals( first + " …", r.summary() );
        assertTrue( r.truncated() );
    }

    @Test
    void aSentenceEndingExactlyAtTheCapIsStillUsed() {
        final String first = words( 154 ) + ".";   // 155 chars, '.' is the last one
        final String text = first + " tail words follow here";
        final DescriptionRenderer.DescriptionRender r = DescriptionRenderer.renderDescription(
                List.of( text, "padding padding padding padding padding" ) );
        assertEquals( first + " …", r.summary() );
    }

    @Test
    void aSentenceEndingPastTheCapFallsBackToAWordBoundaryCut() {
        final String text = words( 100 ) + " " + words( 100 ) + ". end";
        final DescriptionRenderer.DescriptionRender r = DescriptionRenderer.renderDescription( List.of( text ) );
        assertEquals( words( 100 ) + " …", r.summary() );
    }

    @Test
    void aDescriptionWithOnlyAnExampleLineSaysSeeBelow() {
        final DescriptionRenderer.DescriptionRender r = DescriptionRenderer.renderDescription( List.of( "Example: a=b" ) );
        assertTrue( r.truncated() );
        assertEquals( "(see full description below) …", r.summary() );
        assertEquals( "`Example: a=b`", r.full() );
    }

    // ---- HoistScanner ----

    @Test
    void aBlankLineBetweenAPreambleAndTheKeyBlockDropsThePreamble() {
        final Map< Integer, HoistScanner.Hoist > h = HoistScanner.scanHoists( List.of(
                "# [S]", "# Preamble.", "#", "# Own text.", "# Type: string", "", "a.b = 1" ) );
        assertEquals( Map.of(), h );
    }

    @Test
    void aSectionPreambleIsHoistedOutOfTheFirstKeyOnly() {
        final Map< Integer, HoistScanner.Hoist > h = HoistScanner.scanHoists( List.of(
                "# [S]", "# Preamble one.", "#", "# Own text.", "# Type: string", "a.b = 1",
                "# Second key text.", "# Type: string", "a.c = 2" ) );
        assertEquals( List.of( 6 ), List.copyOf( h.keySet() ) );
        assertEquals( List.of( "Preamble one." ), h.get( 6 ).preambleParagraphs() );
        assertEquals( List.of( "Own text." ), h.get( 6 ).ownDescription() );
    }

    @Test
    void aNonKeyLineBetweenTheMarkerAndTheKeyDiscardsTheBlock() {
        final Map< Integer, HoistScanner.Hoist > h = HoistScanner.scanHoists( List.of(
                "# [S]", "# Preamble.", "#", "# Own.", "# Type: string", "not a key line", "a.b = 1" ) );
        assertEquals( Map.of(), h );
    }

    @Test
    void aSectionMarkerResetsTheBlockSoEarlierCommentsDoNotLeakIn() {
        final Map< Integer, HoistScanner.Hoist > h = HoistScanner.scanHoists( List.of(
                "# stray comment", "#", "# more", "# [S]", "# Own.", "# Type: string", "a.b = 1" ) );
        assertEquals( Map.of(), h, "a single paragraph is never hoisted" );
    }

    @Test
    void aSinglePargraphBlockIsNotHoisted() {
        assertEquals( Map.of(), HoistScanner.scanHoists( List.of( "# [S]", "# Own.", "# Type: string", "a.b = 1" ) ) );
    }

    @Test
    void aKeyWhoseOwnParagraphIsDirectivesOnlyHasNothingToHoist() {
        assertEquals( Map.of(), HoistScanner.scanHoists( List.of(
                "# [S]", "# Preamble.", "#", "# Type: string", "a.b = 1" ) ) );
    }

    @Test
    void anExclamationCommentCountsAsACommentLine() {
        final Map< Integer, HoistScanner.Hoist > h = HoistScanner.scanHoists( List.of(
                "# [S]", "! Preamble.", "!", "! Own.", "! Type: string", "a.b = 1" ) );
        assertEquals( List.of( "Own." ), h.get( 6 ).ownDescription() );
    }
}
