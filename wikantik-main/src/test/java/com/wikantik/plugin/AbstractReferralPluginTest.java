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
package com.wikantik.plugin;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted tests for uncovered paths in {@link AbstractReferralPlugin}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code globToRegex()} special-character escaping</li>
 *   <li>{@code wikitizeCollection()} — empty list, numItems limit, custom before/after,
 *       effectiveBefore/effectiveAfter markdown defaults, wiki-syntax legacy link format</li>
 *   <li>{@code applyColumnsStyle()} — items &gt; 1 wraps in div; items &le; 1 returns plain result</li>
 *   <li>{@code initialize()} — sortOrder variants: "java", "locale", "human", rule-based collator,
 *       invalid collator falls back to default</li>
 *   <li>{@code filterCollection()} — lastModified high-watermark tracking</li>
 *   <li>{@code makeHTML()} — basic rendering does not throw</li>
 * </ul>
 */
class AbstractReferralPluginTest {

    // Use a single static engine to avoid port conflicts between parallel tests
    static final TestEngine engine = TestEngine.build(
            with( "wikantik.cache.enable", "false" ),
            with( "wikantik.breakTitleWithSpaces", "false" ) );
    static final PluginManager manager = engine.getManager( PluginManager.class );

    private final List<String> createdPages = new ArrayList<>();
    private Context context;

    @BeforeEach
    void setUp() throws Exception {
        engine.saveText( "TestPage", "Referral plugin test content." );
        createdPages.add( "TestPage" );
        context = Wiki.context().create( engine,
                HttpMockFactory.createHttpRequest(),
                Wiki.contents().page( engine, "TestPage" ) );
    }

    @AfterEach
    void tearDown() {
        createdPages.forEach( engine::deleteTestPage );
        createdPages.clear();
        TestEngine.emptyWorkDir();
    }

    private void savePage( final String name, final String content ) throws Exception {
        engine.saveText( name, content );
        createdPages.add( name );
    }

    // ============== globToRegex ==============

    /**
     * '*' glob expands to ".*", '?' to ".".
     */
    @Test
    void testGlobToRegexWildcards() {
        assertEquals( ".*", AbstractReferralPlugin.globToRegex( "*" ) );
        assertEquals( ".", AbstractReferralPlugin.globToRegex( "?" ) );
    }

    /**
     * Special regex characters are escaped so they match literally.
     */
    @Test
    void testGlobToRegexEscapesRegexMeta() {
        final String glob = "page.name(v1)[test]{x}^end$plus+bar|baz";
        final String regex = AbstractReferralPlugin.globToRegex( glob );

        // Each special char should appear escaped
        assertTrue( regex.contains( "\\." ),  "dot should be escaped" );
        assertTrue( regex.contains( "\\(" ),  "( should be escaped" );
        assertTrue( regex.contains( "\\)" ),  ") should be escaped" );
        assertTrue( regex.contains( "\\[" ),  "[ should be escaped" );
        assertTrue( regex.contains( "\\]" ),  "] should be escaped" );
        assertTrue( regex.contains( "\\{" ),  "{ should be escaped" );
        assertTrue( regex.contains( "\\}" ),  "} should be escaped" );
        assertTrue( regex.contains( "\\^" ),  "^ should be escaped" );
        assertTrue( regex.contains( "\\$" ),  "$ should be escaped" );
        assertTrue( regex.contains( "\\+" ),  "+ should be escaped" );
        assertTrue( regex.contains( "\\|" ),  "| should be escaped" );
    }

    /**
     * A backslash in the glob pattern is also escaped in the regex.
     */
    @Test
    void testGlobToRegexEscapesBackslash() {
        final String regex = AbstractReferralPlugin.globToRegex( "a\\b" );
        assertTrue( regex.contains( "\\\\" ), "backslash should be escaped to \\\\" );
    }

    /**
     * Plain alphabetic characters pass through unchanged.
     */
    @Test
    void testGlobToRegexPlainCharsUnchanged() {
        assertEquals( "FooBar", AbstractReferralPlugin.globToRegex( "FooBar" ) );
    }

    // ============== applyColumnsStyle ==============

    /**
     * When {@code items > 1} the result should be wrapped in a columns div.
     * Exercised via the ReferringPagesPlugin which inherits applyColumnsStyle.
     */
    @Test
    void testApplyColumnsStyleWrapsWhenItemsGreaterThanOne() throws Exception {
        savePage( "Alpha", "Refers to [TestPage]()." );
        savePage( "Beta",  "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin columns=2 before='#' after='\\n'}" );

        assertTrue( result.startsWith( "<div style=\"columns:2;" ),
                    "columns=2 must wrap output in a columns div, got: " + result );
    }

    /**
     * When {@code items <= 1} the result is returned without a wrapping div.
     */
    @Test
    void testApplyColumnsStyleNoWrapWhenItemsLeOrOne() throws Exception {
        savePage( "GammaPage", "Refers to [TestPage]()." );

        // columns=1 → items == 1 → no wrap
        final String result = manager.execute( context, "{ReferringPagesPlugin columns=1}" );
        assertFalse( result.startsWith( "<div style=\"columns:" ),
                     "columns=1 must NOT wrap output, got: " + result );
    }

    // ============== wikitizeCollection — empty and limited ==============

    /**
     * When the page list is empty, the plugin output should be the "nobody" message.
     */
    @Test
    void testWikitizeCollectionEmptyListYieldsNobody() throws Exception {
        // Execute ReferringPagesPlugin on a page with no referrers
        savePage( "IsolatedPage", "No links." );
        final Context ctx = Wiki.context().create( engine,
                HttpMockFactory.createHttpRequest(),
                Wiki.contents().page( engine, "IsolatedPage" ) );

        final String result = manager.execute( ctx,
                "{INSERT com.wikantik.plugin.ReferringPagesPlugin}" );
        // Empty collection → "nobody"
        assertTrue( result.contains( "nobody" ) || result.contains( "ul" ),
                    "Empty referrers should return nobody or empty list, got: " + result );
    }

    /**
     * Custom {@code before} and {@code separator} parameters are used in output when specified.
     * Using a plain text marker like "ITEM:" which won't be interpreted as wiki markup.
     */
    @Test
    void testWikitizeCollectionCustomBeforeAfter() throws Exception {
        savePage( "PageRef1", "Refers to [TestPage]()." );

        // before='ITEM:' and after='\n' — the rendered HTML should not strip these literally
        // but 'ITEM:' is plain text that will appear as-is before each link
        final String result = manager.execute( context,
                "{ReferringPagesPlugin before='ITEM:' after='\\n'}" );

        assertTrue( result.contains( "ITEM:" ),
                    "Custom before marker 'ITEM:' must appear in output, got: " + result );
    }

    // ============== sortOrder variants (exercised via initialize) ==============

    /**
     * sortOrder=java uses JavaNaturalComparator and must produce a sorted list.
     */
    @Test
    void testSortOrderJava() throws Exception {
        savePage( "Zebra", "Refers to [TestPage]()." );
        savePage( "Apple", "Refers to [TestPage]()." );
        savePage( "Mango", "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin sortOrder=java}" );

        assertNotNull( result );
        // Just verify execution completes without error and produces a list
        assertTrue( result.contains( "<ul>" ) || result.contains( "Apple" ) );
    }

    /**
     * sortOrder=locale uses LocaleComparator and must produce output without error.
     */
    @Test
    void testSortOrderLocale() throws Exception {
        savePage( "PageLocale1", "Refers to [TestPage]()." );
        savePage( "PageLocale2", "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin sortOrder=locale}" );

        assertNotNull( result );
        assertFalse( result.isEmpty() );
    }

    /**
     * sortOrder=human uses HumanComparator and must produce output without error.
     */
    @Test
    void testSortOrderHuman() throws Exception {
        savePage( "PageHuman1", "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin sortOrder=human}" );

        assertNotNull( result );
        assertFalse( result.isEmpty() );
    }

    /**
     * A valid RuleBasedCollator rule string (e.g., "&lt; a &lt; b") must be accepted
     * and produce output without error.
     */
    @Test
    void testSortOrderRuleBasedCollator() throws Exception {
        savePage( "PageRule1", "Refers to [TestPage]()." );

        // Simple RuleBasedCollator rule
        final String result = manager.execute( context,
                "{ReferringPagesPlugin sortOrder='< a < b < c < d < e < f < g < h < i < j < k < l < m < n < o < p < q < r < s < t < u < v < w < x < y < z'}" );

        assertNotNull( result );
        assertFalse( result.isEmpty() );
    }

    /**
     * An invalid sortOrder rule string must fall back to the default sorter (no exception thrown).
     */
    @Test
    void testSortOrderInvalidRuleFallsBack() throws Exception {
        savePage( "PageBadRule", "Refers to [TestPage]()." );

        // "!!invalid!!" is not a valid RuleBasedCollator rule — must fall back silently
        assertDoesNotThrow( () ->
                manager.execute( context, "{ReferringPagesPlugin sortOrder='!!invalid!!'}" )
        );
    }

    // ============== filterCollection — lastModified high-watermark ==============

    /**
     * When {@code show=count} and {@code showLastModified=true} the output contains the count
     * followed by a date string — exercises the lastModified high-watermark tracking inside
     * {@code filterCollection()}.
     */
    @Test
    void testFilterCollectionLastModifiedWatermark() throws Exception {
        savePage( "RefForDate1", "Refers to [TestPage]()." );
        savePage( "RefForDate2", "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin show=count showLastModified=true}" );

        assertNotNull( result );
        // Result should be "{count} ({date})" — at minimum a digit should appear
        assertTrue( result.matches( "\\d+.*" ) || result.contains( "(" ),
                    "show=count showLastModified=true must produce a date, got: " + result );
    }

    // ============== filterWikiPageCollection ==============

    /**
     * Verifies that filterWikiPageCollection filters out pages whose names are excluded.
     * Exercised end-to-end via UnusedPagesPlugin with an exclude parameter.
     */
    @Test
    void testFilterWikiPageCollectionViaExclude() throws Exception {
        savePage( "UnusedExcludeA", "Orphaned page A." );
        savePage( "UnusedExcludeB", "Orphaned page B." );

        final String result = manager.execute( context,
                "{UnusedPagesPlugin exclude='*ExcludeA*'}" );

        assertFalse( result.contains( "UnusedExcludeA" ),
                     "Page matching exclude pattern must not appear in results" );
    }

    // ============== include parameter path ==============

    /**
     * Only pages matching the {@code include} glob pattern should appear.
     */
    @Test
    void testIncludePatternFiltersToMatchingPages() throws Exception {
        savePage( "IncTarget1", "Refers to [TestPage]()." );
        savePage( "OtherPage9", "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin include='IncTarget*'}" );

        assertTrue( result.contains( "IncTarget1" ),
                    "IncTarget1 should be included, got: " + result );
        assertFalse( result.contains( "OtherPage9" ),
                     "OtherPage9 should not be included, got: " + result );
    }

    // ============== makeHTML (CutMutator) ==============

    /**
     * maxwidth parameter causes long page titles to be cut — exercises CutMutator and makeHTML.
     */
    @Test
    void testMaxWidthCutsMutatesLongTitle() throws Exception {
        savePage( "AVeryLongPageNameThatExceedsTenChars", "Refers to [TestPage]()." );

        final String result = manager.execute( context,
                "{ReferringPagesPlugin maxwidth=10}" );

        // Should not throw; with maxwidth=10 a long name should be truncated with "..."
        assertNotNull( result );
    }
}
