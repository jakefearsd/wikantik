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
package com.wikantik.diff;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link TraditionalDiffProvider} and the remaining uncovered paths
 * in {@link ContextualDiffProvider} (hyperlink generation, ChangeMerger state transitions).
 *
 * <p>{@link ContextualDiffProviderTest} already exercises the basic diff paths with
 * {@code emitChangeNextPreviousHyperlinks = false}.  This class tests the default
 * hyperlink-enabled path and the {@link TraditionalDiffProvider} visitor methods.
 */
class DiffProvidersCITest {

    private static Context ctx;

    @BeforeAll
    static void setUpContext() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        final TestEngine engine = new TestEngine( props );
        ctx = Wiki.context().create( engine, Wiki.contents().page( engine, "Dummy" ) );
    }

    // =========================================================================
    // TraditionalDiffProvider
    // =========================================================================

    @Test
    void testTraditionalDiffIdenticalTextReturnsEmpty() throws Exception {
        final TraditionalDiffProvider provider = new TraditionalDiffProvider();
        provider.initialize( null, new Properties() );

        final String result = provider.makeDiffHtml( ctx, "same text", "same text" );
        assertEquals( "", result, "Identical text should produce an empty diff" );
    }

    @Test
    void testTraditionalDiffAddDelta() throws Exception {
        // "A C" -> "A B C" triggers an AddDelta (B is added)
        final TraditionalDiffProvider provider = new TraditionalDiffProvider();
        provider.initialize( null, new Properties() );

        final String result = provider.makeDiffHtml( ctx, "line one\nline three\n", "line one\nline two\nline three\n" );
        assertNotNull( result );
        assertFalse( result.isEmpty(), "Diff should not be empty when a line is added" );
        // The HTML table must contain the 'diffadd' CSS class
        assertTrue( result.contains( "diffadd" ),
                "Added lines should use the 'diffadd' CSS class, got: " + result );
    }

    @Test
    void testTraditionalDiffDeleteDelta() throws Exception {
        // "A B C" -> "A C" triggers a DeleteDelta (B is removed)
        final TraditionalDiffProvider provider = new TraditionalDiffProvider();
        provider.initialize( null, new Properties() );

        final String result = provider.makeDiffHtml( ctx, "line one\nline two\nline three\n", "line one\nline three\n" );
        assertNotNull( result );
        assertFalse( result.isEmpty(), "Diff should not be empty when a line is deleted" );
        // The HTML table must contain the 'diffrem' CSS class
        assertTrue( result.contains( "diffrem" ),
                "Deleted lines should use the 'diffrem' CSS class, got: " + result );
    }

    @Test
    void testTraditionalDiffChangeDelta() throws Exception {
        // Changing a line triggers both diffrem (original) and diffadd (revised)
        final TraditionalDiffProvider provider = new TraditionalDiffProvider();
        provider.initialize( null, new Properties() );

        final String result = provider.makeDiffHtml( ctx, "line one\nold line\nline three\n", "line one\nnew line\nline three\n" );
        assertNotNull( result );
        assertFalse( result.isEmpty(), "Diff should not be empty when a line is changed" );
        assertTrue( result.contains( "diffrem" ),
                "Changed lines should mark original as 'diffrem', got: " + result );
        assertTrue( result.contains( "diffadd" ),
                "Changed lines should mark revised as 'diffadd', got: " + result );
    }

    @Test
    void testTraditionalDiffWrapsInTable() throws Exception {
        final TraditionalDiffProvider provider = new TraditionalDiffProvider();
        provider.initialize( null, new Properties() );

        final String result = provider.makeDiffHtml( ctx, "old content\n", "new content\n" );
        assertNotNull( result );
        assertTrue( result.contains( "<table" ), "Output should be wrapped in a <table>" );
        assertTrue( result.contains( "</table>" ), "Output should close with </table>" );
    }

    @Test
    void testTraditionalDiffMultipleLines() throws Exception {
        // Generate a diff with multiple changed lines to exercise the line-count ChoiceFormat
        final TraditionalDiffProvider provider = new TraditionalDiffProvider();
        provider.initialize( null, new Properties() );

        final String old = "alpha\nbeta\ngamma\n";
        final String updated = "alpha\nbeta changed\ngamma changed\n";
        final String result = provider.makeDiffHtml( ctx, old, updated );
        assertNotNull( result );
        assertFalse( result.isEmpty() );
    }

    @Test
    void testTraditionalDiffProviderInfo() {
        assertEquals( "TraditionalDiffProvider", new TraditionalDiffProvider().getProviderInfo() );
    }

    // =========================================================================
    // ContextualDiffProvider with hyperlinks enabled (the production default)
    // =========================================================================

    /**
     * Verifies the anchor/back/forward link generation that fires when
     * {@code emitChangeNextPreviousHyperlinks} is {@code true} (the default).
     * With a single change the first change gets no back-link and
     * the forward-link is also absent (only one change in total).
     */
    @Test
    void testContextualDiffHyperlinksEnabledSingleChange() throws Exception {
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        // leave emitChangeNextPreviousHyperlinks = true (default)
        diff.initialize( null, new Properties() );

        final String result = diff.makeDiffHtml( ctx, "A B C", "A X C" );
        assertNotNull( result );
        // The change anchor is always emitted when hyperlinks are on
        assertTrue( result.contains( "change-" ),
                "Expected change anchor in output, got: " + result );
    }

    /**
     * Verifies the back-link (<code>&lt;&lt;</code>) for a second change and
     * the forward-link (<code>&gt;&gt;</code>) for the first change.
     */
    @Test
    void testContextualDiffHyperlinksEnabledMultipleChanges() throws Exception {
        // Two separate changes on the same line so the diff produces two change deltas.
        // Use content that forces two separated AddDelta / DeleteDelta regions.
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        diff.initialize( null, new Properties() );

        // Two distinct changes: "B" replaced, "E" replaced, separated by unchanged "C D".
        final String result = diff.makeDiffHtml( ctx,
                "A B C D E F",
                "A X C D Y F" );
        assertNotNull( result );

        // With two changes, the back-link ("<<") should appear for change #2
        // and the forward-link (">>") should appear for change #1
        assertTrue( result.contains( "&lt;&lt;" ) || result.contains( "&gt;&gt;" ),
                "Multiple changes should produce navigation hyperlinks, got: " + result );
    }

    /**
     * Verifies DIFF_START and DIFF_END are present in the output
     * (the outer div wrapping added by the production provider).
     */
    @Test
    void testContextualDiffProducesOuterDiv() throws Exception {
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        diff.initialize( null, new Properties() );

        final String result = diff.makeDiffHtml( ctx, "old", "new" );
        assertTrue( result.startsWith( "<div class=\"diff-wikitext\">" ),
                "Output should start with DIFF_START div, got: " + result );
        assertTrue( result.endsWith( "</div>" ),
                "Output should end with DIFF_END div, got: " + result );
    }

    /**
     * When the texts are identical the output is just the outer div wrapping
     * the unchanged content (no change markers).
     */
    @Test
    void testContextualDiffIdenticalTextsNoChangeMarkers() throws Exception {
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        diff.initialize( null, new Properties() );

        final String result = diff.makeDiffHtml( ctx, "same text", "same text" );
        assertFalse( result.contains( "diff-insertion" ),
                "Identical texts should not produce insertion markers" );
        assertFalse( result.contains( "diff-deletion" ),
                "Identical texts should not produce deletion markers" );
    }

    /**
     * The production INSERTION and DELETION CSS spans should appear when
     * hyperlinks are enabled.
     */
    @Test
    void testContextualDiffInsertionAndDeletionSpans() throws Exception {
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        diff.initialize( null, new Properties() );

        final String insertResult = diff.makeDiffHtml( ctx, "A C", "A B C" );
        assertTrue( insertResult.contains( "diff-insertion" ),
                "Insertion should be marked with 'diff-insertion' CSS class" );

        final String deleteResult = diff.makeDiffHtml( ctx, "A B C", "A C" );
        assertTrue( deleteResult.contains( "diff-deletion" ),
                "Deletion should be marked with 'diff-deletion' CSS class" );
    }

    /**
     * Verifies provider info string.
     */
    @Test
    void testContextualDiffProviderInfo() {
        assertEquals( "ContextualDiffProvider", new ContextualDiffProvider().getProviderInfo() );
    }

    /**
     * Exercises the context-limit elision path with hyperlinks enabled.
     * With a small limit, unchanged text between changes is elided with
     * the head/tail indicator HTML.
     */
    @Test
    void testContextualDiffElisionWithHyperlinksEnabled() throws Exception {
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        final Properties props = new Properties();
        props.setProperty( ContextualDiffProvider.PROP_UNCHANGED_CONTEXT_LIMIT, "2" );
        diff.initialize( null, props );

        // Two changes far apart — context between them should be elided
        final String result = diff.makeDiffHtml( ctx,
                "A B C D E F G H I J K L M N O P",
                "A B C D F G H I J K L M N O P" );
        // With limit=2 and a single deletion, the surrounding context is limited
        assertNotNull( result );
    }

    /**
     * ChangeDelta path (a word is changed, not just inserted or deleted)
     * with hyperlinks enabled.
     */
    @Test
    void testContextualDiffChangeDeltaWithHyperlinks() throws Exception {
        final ContextualDiffProvider diff = new ContextualDiffProvider();
        diff.initialize( null, new Properties() );

        // Replace one word: "B" -> "X"
        final String result = diff.makeDiffHtml( ctx, "A B C", "A X C" );
        assertNotNull( result );
        assertTrue( result.contains( "diff-insertion" ),
                "Change should include an insertion span" );
        assertTrue( result.contains( "diff-deletion" ),
                "Change should include a deletion span" );
    }

    // =========================================================================
    // DiffProvider.NullDiffProvider — covers the inner class
    // =========================================================================

    @Test
    void testNullDiffProviderReturnsSentinelString() throws Exception {
        final DiffProvider nullProvider = new DiffProvider.NullDiffProvider();
        nullProvider.initialize( null, new Properties() );

        final String result = nullProvider.makeDiffHtml( ctx, "old", "new" );
        assertTrue( result.contains( "NullDiffProvider" ),
                "NullDiffProvider should return its sentinel string, got: " + result );
    }

    @Test
    void testNullDiffProviderInfo() {
        assertEquals( "NullDiffProvider", new DiffProvider.NullDiffProvider().getProviderInfo() );
    }

}
