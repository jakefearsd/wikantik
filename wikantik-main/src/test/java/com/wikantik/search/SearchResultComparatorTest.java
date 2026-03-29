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
package com.wikantik.search;

import com.wikantik.api.core.Page;
import com.wikantik.api.search.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Unit tests for {@link SearchResultComparator}.
 */
class SearchResultComparatorTest {

    private SearchResultComparator comparator;

    @BeforeEach
    void setUp() {
        comparator = new SearchResultComparator();
    }

    /**
     * Creates a mock SearchResult with the given page name and score.
     */
    private SearchResult mockResult( final String pageName, final int score ) {
        final SearchResult result = mock( SearchResult.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( pageName );
        when( result.getPage() ).thenReturn( page );
        when( result.getScore() ).thenReturn( score );
        return result;
    }

    // ---- Score-based ordering (primary sort, descending) ----

    @Test
    void testHigherScoreComesFirst() {
        final SearchResult high = mockResult( "PageA", 10 );
        final SearchResult low = mockResult( "PageB", 5 );

        assertTrue( comparator.compare( high, low ) < 0,
                "Higher-scored result should sort before lower-scored result (negative return)" );
    }

    @Test
    void testLowerScoreComesSecond() {
        final SearchResult low = mockResult( "PageA", 5 );
        final SearchResult high = mockResult( "PageB", 10 );

        assertTrue( comparator.compare( low, high ) > 0,
                "Lower-scored result should sort after higher-scored result (positive return)" );
    }

    @Test
    void testScoreDifferenceOfOne() {
        final SearchResult s1 = mockResult( "PageA", 6 );
        final SearchResult s2 = mockResult( "PageB", 5 );

        assertTrue( comparator.compare( s1, s2 ) < 0,
                "Score difference of 1 should still place the higher score first" );
    }

    // ---- Alphabetical tiebreak (secondary sort, ascending) ----

    @Test
    void testSameScoreAlphaBeforeBeta() {
        final SearchResult alpha = mockResult( "Alpha", 10 );
        final SearchResult beta = mockResult( "Beta", 10 );

        assertTrue( comparator.compare( alpha, beta ) < 0,
                "With equal scores, 'Alpha' should sort before 'Beta' alphabetically" );
    }

    @Test
    void testSameScoreBetaAfterAlpha() {
        final SearchResult beta = mockResult( "Beta", 10 );
        final SearchResult alpha = mockResult( "Alpha", 10 );

        assertTrue( comparator.compare( beta, alpha ) > 0,
                "With equal scores, 'Beta' should sort after 'Alpha' alphabetically" );
    }

    // ---- Equal elements ----

    @Test
    void testEqualScoreAndNameReturnsZero() {
        final SearchResult s1 = mockResult( "SamePage", 7 );
        final SearchResult s2 = mockResult( "SamePage", 7 );

        assertEquals( 0, comparator.compare( s1, s2 ),
                "Same score and same page name should return 0" );
    }

    // ---- Sorting a list ----

    @Test
    void testSortingListProducesCorrectOrder() {
        final SearchResult r1 = mockResult( "Zebra", 5 );
        final SearchResult r2 = mockResult( "Alpha", 10 );
        final SearchResult r3 = mockResult( "Beta", 10 );
        final SearchResult r4 = mockResult( "Charlie", 3 );
        final SearchResult r5 = mockResult( "Delta", 5 );

        final List< SearchResult > results = new ArrayList<>();
        results.add( r1 );
        results.add( r2 );
        results.add( r3 );
        results.add( r4 );
        results.add( r5 );

        results.sort( comparator );

        // Expected order: Alpha(10), Beta(10), Delta(5), Zebra(5), Charlie(3)
        assertEquals( "Alpha", results.get( 0 ).getPage().getName(), "Highest score, first alphabetically" );
        assertEquals( "Beta", results.get( 1 ).getPage().getName(), "Highest score, second alphabetically" );
        assertEquals( "Delta", results.get( 2 ).getPage().getName(), "Medium score, first alphabetically" );
        assertEquals( "Zebra", results.get( 3 ).getPage().getName(), "Medium score, second alphabetically" );
        assertEquals( "Charlie", results.get( 4 ).getPage().getName(), "Lowest score" );
    }

    // ---- Consistency with Collections.sort ----

    @Test
    void testCollectionsSortProducesStableOrdering() {
        final SearchResult r1 = mockResult( "Page3", 8 );
        final SearchResult r2 = mockResult( "Page1", 8 );
        final SearchResult r3 = mockResult( "Page2", 15 );
        final SearchResult r4 = mockResult( "Page4", 1 );

        final List< SearchResult > results = new ArrayList<>();
        results.add( r1 );
        results.add( r2 );
        results.add( r3 );
        results.add( r4 );

        Collections.sort( results, comparator );

        // Expected: Page2(15), Page1(8), Page3(8), Page4(1)
        assertEquals( "Page2", results.get( 0 ).getPage().getName(), "Highest score first" );
        assertEquals( "Page1", results.get( 1 ).getPage().getName(), "Tied score, alphabetically first" );
        assertEquals( "Page3", results.get( 2 ).getPage().getName(), "Tied score, alphabetically second" );
        assertEquals( "Page4", results.get( 3 ).getPage().getName(), "Lowest score last" );
    }

    // ---- Edge cases ----

    @Test
    void testBothScoresZeroFallsToAlphabetical() {
        final SearchResult a = mockResult( "Aardvark", 0 );
        final SearchResult b = mockResult( "Badger", 0 );

        assertTrue( comparator.compare( a, b ) < 0,
                "With scores of 0, sorting should fall through to alphabetical comparison" );
    }

    @Test
    void testBothScoresZeroReverseOrder() {
        final SearchResult a = mockResult( "Zebra", 0 );
        final SearchResult b = mockResult( "Apple", 0 );

        assertTrue( comparator.compare( a, b ) > 0,
                "With scores of 0, 'Zebra' should sort after 'Apple'" );
    }

    @Test
    void testVeryLargeScores() {
        final SearchResult large = mockResult( "PageA", 1_000_000 );
        final SearchResult small = mockResult( "PageB", 1 );

        assertTrue( comparator.compare( large, small ) < 0,
                "Very large score should still sort before a small score" );
    }

    @Test
    void testVeryLargeEqualScoresFallToAlphabetical() {
        final SearchResult a = mockResult( "Alpha", 999_999 );
        final SearchResult b = mockResult( "Omega", 999_999 );

        assertTrue( comparator.compare( a, b ) < 0,
                "Even with very large equal scores, alphabetical tiebreak should apply" );
    }

    @Test
    void testSymmetry() {
        final SearchResult s1 = mockResult( "PageA", 10 );
        final SearchResult s2 = mockResult( "PageB", 5 );

        final int forward = comparator.compare( s1, s2 );
        final int reverse = comparator.compare( s2, s1 );

        assertTrue( forward < 0, "Higher score should come first" );
        assertTrue( reverse > 0, "Lower score should come second" );
        assertEquals( 0, forward + reverse,
                "compare(a,b) + compare(b,a) should equal 0 for antisymmetry" );
    }

    @Test
    void testReflexive() {
        final SearchResult s = mockResult( "TestPage", 42 );

        assertEquals( 0, comparator.compare( s, s ),
                "Comparing a result to itself should return 0" );
    }

}
