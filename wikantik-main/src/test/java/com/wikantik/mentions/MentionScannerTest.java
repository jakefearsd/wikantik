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
package com.wikantik.mentions;

import com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MentionScannerTest {

    private static final List< TitleEntry > ENTRIES = List.of(
            new TitleEntry( "IndexFundsHub", "Index Funds Hub", List.of( "Index Funds Hub", "index fund" ) ),
            new TitleEntry( "LowCostIndexFundInvesting", "Low-Cost Index Fund Investing",
                    List.of( "Low Cost Index Fund Investing", "Low-Cost Index Fund Investing" ) ),
            new TitleEntry( "ExpenseRatio", "Expense Ratio", List.of( "Expense Ratio" ) ),
            new TitleEntry( "Design", "Design", List.of( "Design" ) ),
            new TitleEntry( "Go", "Go", List.of( "Go" ) ),
            new TitleEntry( "SelfPage", "Self Page", List.of( "Self Page" ) ) );

    private static List< Mention > scan( final String text ) {
        return MentionScanner.scan( text, "SelfPage", ENTRIES );
    }

    @Test void findsAPhraseInProseWithExactOffsets() {
        final String text = "Buy a broad index fund today.\n";
        final Mention m = scan( text ).get( 0 );
        assertEquals( "IndexFundsHub", m.target() );
        assertEquals( "index fund", m.phrase() );
        assertEquals( "index fund", text.substring( m.from(), m.to() ) );
        assertEquals( 1, m.line() );
    }

    @Test void longestPhraseWins() {
        final List< Mention > ms = scan( "Read about low-cost index fund investing first.\n" );
        assertEquals( 1, ms.size() );
        assertEquals( "LowCostIndexFundInvesting", ms.get( 0 ).target() );
        assertEquals( "low-cost index fund investing", ms.get( 0 ).phrase() );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "## The index fund heading\n",
            "Use `index fund` in code.\n",
            "```\nindex fund\n```\n",
            "Already [an index fund](Somewhere) linked.\n",
            "See https://example.com/index-fund-guide now.\n",
            "Math $index fund$ here.\n",
            "![index fund](pic.png)\n",
            "Before [{Plugin text='index fund'}] after.\n",
            "<div>index fund</div>\n",
            "---\ntitle: index fund\n---\nbody\n",
            "Read [an index fund](https://example.com) now.\n",
            "![index fund](https://e.com/p.png)\n",
            "<https://e.com/index-fund>\n",
            "Visit www.example.com/index-fund today.\n" } )
    void ineligibleContextsAreSkipped( final String text ) {
        assertTrue( scan( text ).stream().noneMatch( m -> m.target().equals( "IndexFundsHub" ) ), text );
    }

    @Test void listItemsTableCellsAndBlockquotesAreEligible() {
        assertEquals( 3, scan( "- an index fund\n\n| a |\n|---|\n| expense ratio |\n\n> low-cost index fund investing\n" ).size() );
    }

    @Test void selfAndAlreadyLinkedTargetsAreExcluded() {
        assertTrue( scan( "This Self Page mentions itself.\n" ).isEmpty() );
        assertTrue( scan( "An index fund. Also [hub](IndexFundsHub).\n" ).isEmpty() );
    }

    @Test void commonWordsAndShortPhrasesAreIgnored() {
        assertTrue( scan( "The design is good. Go now.\n" ).isEmpty() );
    }

    @Test void onlyTheFirstOccurrencePerTargetIsReportedWithACountOfTheRest() {
        final List< Mention > ms = scan( "index fund one.\n\nindex fund two.\n\nindex fund three.\n" );
        assertEquals( 1, ms.size() );
        assertEquals( 1, ms.get( 0 ).line() );
        assertEquals( 2, ms.get( 0 ).more() );
    }

    @Test void offsetsAreUtf16CodeUnitsAcrossEmojiAndCrlf() {
        final String text = "🎉 Party\r\n\r\nThen an expense ratio 😀 matters.\r\n";
        final Mention m = scan( text ).get( 0 );
        assertEquals( "expense ratio", text.substring( m.from(), m.to() ) );
        assertEquals( 3, m.line() );
    }

    @Test void contextShowsTheSurroundingLine() {
        final Mention m = scan( "Some words before an expense ratio and after.\n" ).get( 0 );
        assertTrue( m.context().contains( "an expense ratio and" ), m.context() );
    }

    @Test void resultsAreInDocumentOrderAndUncapped() {
        final StringBuilder sb = new StringBuilder();
        final java.util.List< TitleEntry > many = new java.util.ArrayList<>();
        for ( int i = 0; i < 60; i++ ) {
            many.add( new TitleEntry( "Topic" + i, "Topic " + i, List.of( "zebra topic " + i ) ) );
            sb.append( "zebra topic " ).append( i ).append( ".\n\n" );
        }
        final List< Mention > ms = MentionScanner.scan( sb.toString(), "X", many );
        assertEquals( 60, ms.size(), "the REST layer caps after permission filtering, not the scanner" );
        for ( int i = 1; i < ms.size(); i++ ) {
            assertTrue( ms.get( i ).from() > ms.get( i - 1 ).from() );
        }
    }

    @Test void lineIndexMapsOffsetsToOneBasedLines() {
        final MentionScanner.LineIndex idx = new MentionScanner.LineIndex( "ab\ncd\n\nef" );
        assertEquals( 1, idx.lineOf( 0 ) );
        assertEquals( 1, idx.lineOf( 2 ) );   // the newline itself belongs to its line
        assertEquals( 2, idx.lineOf( 3 ) );
        assertEquals( 3, idx.lineOf( 6 ) );
        assertEquals( 4, idx.lineOf( 7 ) );
        assertEquals( 4, idx.lineOf( 9 ) );
    }
}
