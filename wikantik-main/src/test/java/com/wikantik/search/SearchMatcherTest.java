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

import com.wikantik.TestEngine;
import com.wikantik.api.search.QueryItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * Unit tests for {@link SearchMatcher}.
 */
class SearchMatcherTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.shutdown();
        }
    }

    /**
     * Helper to create a QueryItem with the given word and type.
     */
    private QueryItem queryItem( final String word, final int type ) {
        final QueryItem qi = new QueryItem();
        qi.word = word;
        qi.type = type;
        return qi;
    }

    // ---- 1. Null queries returns null ----

    @Test
    void testNullQueriesReturnsNull() throws IOException {
        final SearchMatcher matcher = new SearchMatcher( engine, null );
        final SearchResult result = matcher.matchPageContent( "TestPage", "some content here" );
        assertNull( result, "A null query array should always produce a null result" );
    }

    // ---- 2. Single REQUESTED word found once ----

    @Test
    void testSingleRequestedWordFoundOnce() throws IOException {
        final QueryItem[] queries = { queryItem( "hello", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "hello world" );
        assertNotNull( result, "Should find the word 'hello'" );
        assertEquals( 1, result.getScore(), "Single occurrence should yield score of 1" );
    }

    // ---- 3. Single REQUESTED word found multiple times on multiple lines ----

    @Test
    void testRequestedWordMultipleOccurrencesMultipleLines() throws IOException {
        final QueryItem[] queries = { queryItem( "wiki", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final String pageText = "This is a wiki page.\nThe wiki engine is great.\nUse the wiki daily.";
        final SearchResult result = matcher.matchPageContent( "OtherPage", pageText );
        assertNotNull( result, "Should find 'wiki' on multiple lines" );
        assertEquals( 3, result.getScore(), "Three occurrences across three lines should yield score of 3" );
    }

    // ---- 4. Case insensitivity ----

    @Test
    void testCaseInsensitiveMatching() throws IOException {
        final QueryItem[] queries = { queryItem( "hello", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "Hello HELLO hElLo" );
        assertNotNull( result, "Case-insensitive search should match all variants" );
        assertEquals( 3, result.getScore(), "All three case variants should be counted" );
    }

    // ---- 5. FORBIDDEN word found returns null ----

    @Test
    void testForbiddenWordReturnsNull() throws IOException {
        final QueryItem[] queries = {
            queryItem( "good", QueryItem.REQUESTED ),
            queryItem( "bad", QueryItem.FORBIDDEN )
        };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "This is good but also bad content" );
        assertNull( result, "Presence of a FORBIDDEN word should cause null result" );
    }

    @Test
    void testForbiddenWordReturnsNullEvenWithHighScoreOnOtherWords() throws IOException {
        final QueryItem[] queries = {
            queryItem( "excellent", QueryItem.REQUIRED ),
            queryItem( "spam", QueryItem.FORBIDDEN )
        };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final String pageText = "excellent excellent excellent\nspam is here";
        final SearchResult result = matcher.matchPageContent( "OtherPage", pageText );
        assertNull( result, "FORBIDDEN word should override any positive matches" );
    }

    // ---- 6. REQUIRED word not found returns null ----

    @Test
    void testRequiredWordNotFoundReturnsNull() throws IOException {
        final QueryItem[] queries = { queryItem( "mandatory", QueryItem.REQUIRED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "This page has no matching content" );
        assertNull( result, "Missing REQUIRED word should produce null result" );
    }

    // ---- 7. REQUIRED word found returns result with score ----

    @Test
    void testRequiredWordFoundReturnsResult() throws IOException {
        final QueryItem[] queries = { queryItem( "engine", QueryItem.REQUIRED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "The engine is running." );
        assertNotNull( result, "REQUIRED word present should produce a result" );
        assertEquals( 1, result.getScore(), "Single occurrence of required word should yield score of 1" );
    }

    // ---- 8. Mixed REQUIRED + REQUESTED both contribute to score ----

    @Test
    void testMixedRequiredAndRequestedBothContribute() throws IOException {
        final QueryItem[] queries = {
            queryItem( "alpha", QueryItem.REQUIRED ),
            queryItem( "beta", QueryItem.REQUESTED )
        };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final String pageText = "alpha beta beta";
        final SearchResult result = matcher.matchPageContent( "OtherPage", pageText );
        assertNotNull( result, "Both words present should produce a result" );
        // alpha=1 occurrence + beta=2 occurrences = 3
        assertEquals( 3, result.getScore(), "Score should be sum of all occurrences (1 + 2 = 3)" );
    }

    // ---- 9. Word in page name gives +5 bonus per query word ----

    @Test
    void testPageNameBonusForSingleWord() throws IOException {
        final QueryItem[] queries = { queryItem( "wiki", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "WikiPage", "wiki content here" );
        assertNotNull( result, "Word found in both name and content should produce a result" );
        // 1 occurrence in content + 5 bonus for name match = 6
        assertEquals( 6, result.getScore(), "Score should include +5 bonus for word appearing in page name" );
    }

    @Test
    void testPageNameBonusForMultipleWords() throws IOException {
        final QueryItem[] queries = {
            queryItem( "wiki", QueryItem.REQUESTED ),
            queryItem( "page", QueryItem.REQUESTED )
        };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "WikiPage", "wiki page" );
        assertNotNull( result, "Both words present should produce a result" );
        // wiki: 1 content + 5 name = 6; page: 1 content + 5 name = 6; total = 12
        assertEquals( 12, result.getScore(), "Each query word in the page name should independently add +5 bonus" );
    }

    @Test
    void testPageNameBonusIsCaseInsensitive() throws IOException {
        final QueryItem[] queries = { queryItem( "test", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "TestPage", "test content" );
        assertNotNull( result );
        // 1 content + 5 name = 6
        assertEquals( 6, result.getScore(), "Page name bonus should be case-insensitive" );
    }

    @Test
    void testPageNameBonusAloneWithNoContentMatch() throws IOException {
        final QueryItem[] queries = { queryItem( "mypage", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        // "mypage" appears in page name "MyPageName" but not in the content
        final SearchResult result = matcher.matchPageContent( "MyPageName", "unrelated content" );
        assertNotNull( result, "Name-only match should still produce a result" );
        assertEquals( 5, result.getScore(), "Score should be exactly 5 from the page name bonus alone" );
    }

    @Test
    void testForbiddenWordInPageNameDoesNotAddBonus() throws IOException {
        final QueryItem[] queries = {
            queryItem( "good", QueryItem.REQUESTED ),
            queryItem( "test", QueryItem.FORBIDDEN )
        };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        // "test" is in the page name but is FORBIDDEN; however, it's not in content, so it doesn't trigger null
        // The FORBIDDEN word should not get the +5 bonus
        final SearchResult result = matcher.matchPageContent( "TestPage", "good stuff here" );
        assertNotNull( result, "FORBIDDEN word only in name (not content) should not cause null" );
        // good=1 occurrence, test is FORBIDDEN so no bonus; total = 1
        assertEquals( 1, result.getScore(), "FORBIDDEN word in page name should not contribute +5 bonus" );
    }

    // ---- 10. No matches at all for REQUESTED words returns null ----

    @Test
    void testNoMatchesReturnsNull() throws IOException {
        final QueryItem[] queries = { queryItem( "xyzzy", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "nothing relevant here" );
        assertNull( result, "Zero total score should produce null result" );
    }

    // ---- 11. Empty page text with REQUESTED words returns null ----

    @Test
    void testEmptyPageTextReturnsNull() throws IOException {
        final QueryItem[] queries = { queryItem( "something", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "" );
        assertNull( result, "Empty page text with no name match should produce null result" );
    }

    // ---- 12. Multiple occurrences of word on same line each counted ----

    @Test
    void testMultipleOccurrencesOnSameLineAreCounted() throws IOException {
        final QueryItem[] queries = { queryItem( "cat", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "the cat sat on the cat mat with another cat" );
        assertNotNull( result, "Multiple occurrences on one line should be found" );
        assertEquals( 3, result.getScore(), "All three occurrences on the same line should be counted" );
    }

    // ---- Additional edge-case tests ----

    @Test
    void testRequiredWordMissingWithOtherRequestedWordsPresent() throws IOException {
        final QueryItem[] queries = {
            queryItem( "must", QueryItem.REQUIRED ),
            queryItem( "optional", QueryItem.REQUESTED )
        };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "this is optional content" );
        assertNull( result, "Missing REQUIRED word should return null even if REQUESTED words match" );
    }

    @Test
    void testSearchResultPageNameIsCorrect() throws IOException {
        final QueryItem[] queries = { queryItem( "content", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "MySpecialPage", "content here" );
        assertNotNull( result );
        assertEquals( "MySpecialPage", result.getPage().getName(), "Result page name should match the wikiname argument" );
    }

    @Test
    void testSearchResultContextsReturnsEmptyArray() throws IOException {
        final QueryItem[] queries = { queryItem( "data", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "data here" );
        assertNotNull( result );
        assertEquals( 0, result.getContexts().length, "getContexts() should return an empty array" );
    }

    @Test
    void testSubstringMatchesAreCounted() throws IOException {
        // indexOf matches substrings, so "cat" should match inside "concatenate"
        final QueryItem[] queries = { queryItem( "cat", QueryItem.REQUESTED ) };
        final SearchMatcher matcher = new SearchMatcher( engine, queries );

        final SearchResult result = matcher.matchPageContent( "OtherPage", "concatenate the catalog" );
        assertNotNull( result, "Substring matches should be counted by indexOf" );
        // "cat" in "concatenate" at index 3, "cat" in "catalog" at index 4 => 2 matches
        assertEquals( 2, result.getScore(), "Substring occurrences should be counted" );
    }

}
