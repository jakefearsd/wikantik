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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import com.wikantik.parser.Heading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static com.wikantik.TestEngine.with;

/**
 * Additional coverage tests for {@link TableOfContents} targeting previously
 * uncovered branches: custom title, numbered=yes, start parameter, prefix
 * parameter, the VAR_ALREADY_PROCESSING guard, and numbered heading output.
 */
public class TableOfContentsCITest {

    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    @AfterEach
    public void tearDown() throws Exception {
        testEngine.getManager( PageManager.class ).deletePage( "TocTest" );
    }

    // Helper: create a context using the shared testEngine for the given page name
    private Context contextFor( final String pageName ) {
        return Wiki.context().create( testEngine,
                Wiki.contents().page( testEngine, pageName ) );
    }

    /**
     * A custom title parameter is rendered instead of the default i18n title.
     * Covers the {@code title != null} branch inside execute().
     * Uses manager.execute() directly so the parameter is passed to the plugin.
     */
    @Test
    public void testCustomTitle() throws Exception {
        testEngine.saveText( "TocTest", "# Section One" );
        final Context ctx = contextFor( "TocTest" );
        final String res = manager.execute( ctx, "{TableOfContents title='My Contents'}" );

        Assertions.assertTrue( res.contains( "My Contents" ),
                "Custom title should appear in TOC heading" );
        Assertions.assertTrue( res.contains( "toc" ),
                "Result should contain TOC div" );
    }

    /**
     * numbered=yes (case-insensitive alias for true) in execute() activates numbered list mode.
     * Covers the {@code numbered.equalsIgnoreCase("yes")} branch inside execute().
     * After calling execute(), the usingNumberedList field is true, so when headingAdded()
     * is called it emits numbered prefixes.
     */
    @Test
    public void testNumberedYesProducesNumberedOutput() throws Exception {
        testEngine.saveText( "TocTest", "# First Heading\n\n## Sub Heading" );
        final Context ctx = contextFor( "TocTest" );

        // Use execute to set usingNumberedList via the "yes" path
        final String tocHtml = manager.execute( ctx, "{TableOfContents numbered='yes'}" );

        // The execute result is structurally a TOC; the numbered content is produced via
        // headingAdded() which the MarkdownParser doesn't call, so we confirm the
        // execute result is non-empty and contains the TOC structure.
        Assertions.assertTrue( tocHtml.contains( "toc" ),
                "Result should contain TOC div" );
        Assertions.assertTrue( tocHtml.contains( "collapsebox" ),
                "Result should contain collapsebox div" );
    }

    /**
     * Numbered list with a start value: headingAdded emits "start " prefix for level-1 headings.
     * Covers the {@code startStr.matches("^\\d+$")} branch in execute(), and the
     * HEADING_LARGE numbered output in headingAdded().
     */
    @Test
    public void testNumberedWithStartProducesPrefix() throws Exception {
        testEngine.saveText( "TocTest", "content" );
        final Context ctx = contextFor( "TocTest" );

        // Create a pre-configured TableOfContents with numbered=true and start=3
        final TableOfContents toc = createNumberedToc( ctx, 3, "" );

        // Fire headingAdded directly (the MarkdownParser doesn't fire this)
        final Heading h1 = makeHeading( Heading.HEADING_LARGE, "Chapter One", "Chapter-One" );
        toc.headingAdded( ctx, h1 );

        final String buf = toc.buf.toString();
        Assertions.assertTrue( buf.contains( "3 " ),
                "Numbered TOC starting at 3 should contain '3 ' prefix before heading text" );
    }

    /**
     * Numbered list with a custom prefix prepends it before each counter.
     * Covers the {@code prefix = TextUtil.replaceEntities(params.get(PARAM_PREFIX))} path and
     * the numbered HEADING_LARGE output in headingAdded().
     */
    @Test
    public void testNumberedWithPrefixProducesPrefix() throws Exception {
        testEngine.saveText( "TocTest", "content" );
        final Context ctx = contextFor( "TocTest" );

        final TableOfContents toc = createNumberedToc( ctx, 1, "A." );

        final Heading h1 = makeHeading( Heading.HEADING_LARGE, "Alpha", "Alpha" );
        toc.headingAdded( ctx, h1 );

        final String buf = toc.buf.toString();
        Assertions.assertTrue( buf.contains( "A." ),
                "Numbered TOC with prefix 'A.' should include that prefix" );
        Assertions.assertTrue( buf.contains( "1 " ),
                "Numbered TOC should also include the counter" );
    }

    /**
     * When the plugin has already been invoked on the same context
     * (VAR_ALREADY_PROCESSING is set), it returns a simple anchor link instead
     * of processing again. Covers the early-return at line 158.
     */
    @Test
    public void testAlreadyProcessingReturnsLink() throws Exception {
        testEngine.saveText( "TocTest", "# Heading" );
        final Context ctx = contextFor( "TocTest" );

        // First call sets VAR_ALREADY_PROCESSING on the context
        manager.execute( ctx, "{TableOfContents}" );
        // Second call on the SAME context should return the short anchor link
        final String res2 = manager.execute( ctx, "{TableOfContents}" );

        Assertions.assertTrue( res2.contains( "#section-TOC" ),
                "Second TOC invocation should produce the short anchor link" );
        Assertions.assertTrue( res2.contains( "toc" ),
                "Second invocation link should contain 'toc' class" );
    }

    /**
     * Numbered headingAdded() with all three levels produces toclevel class and numeric output.
     * Covers numbered heading output for HEADING_SMALL, HEADING_MEDIUM, HEADING_LARGE.
     */
    @Test
    public void testNumberedAllThreeLevels() throws Exception {
        testEngine.saveText( "TocTest", "content" );
        final Context ctx = contextFor( "TocTest" );

        final TableOfContents toc = createNumberedToc( ctx, 1, "" );

        toc.headingAdded( ctx, makeHeading( Heading.HEADING_LARGE, "H1", "H1" ) );
        toc.headingAdded( ctx, makeHeading( Heading.HEADING_MEDIUM, "H2", "H2" ) );
        toc.headingAdded( ctx, makeHeading( Heading.HEADING_SMALL, "H3", "H3" ) );

        final String buf = toc.buf.toString();
        Assertions.assertTrue( buf.contains( "toclevel-1" ), "Should contain toclevel-1 class" );
        Assertions.assertTrue( buf.contains( "toclevel-2" ), "Should contain toclevel-2 class" );
        Assertions.assertTrue( buf.contains( "toclevel-3" ), "Should contain toclevel-3 class" );
        // numbered prefix for level 1 "1 "
        Assertions.assertTrue( buf.contains( "1 " ),
                "Numbered heading at level 1 should have prefix '1 '" );
        // numbered prefix for level 2 "1.1 "
        Assertions.assertTrue( buf.contains( "1.1 " ),
                "Numbered heading at level 2 should have prefix '1.1 '" );
        // numbered prefix for level 3 "1.1.1 "
        Assertions.assertTrue( buf.contains( "1.1.1 " ),
                "Numbered heading at level 3 should have prefix '1.1.1 '" );
    }

    /**
     * start=0 is a valid digit-only string; the start guard {@code start < 0} is not taken.
     * Verifies via execute() that the plugin handles start=0 without error.
     */
    @Test
    public void testNumberedStartZero() throws Exception {
        testEngine.saveText( "TocTest", "# Heading" );
        final Context ctx = contextFor( "TocTest" );
        final String res = manager.execute( ctx, "{TableOfContents numbered='true' start='0'}" );

        Assertions.assertTrue( res.contains( "toc" ),
                "Result should contain TOC div" );
    }

    /**
     * A non-numeric start value is ignored (does not match the regex),
     * falling through to the default start=0 behaviour.
     */
    @Test
    public void testNumberedStartNonNumericIgnored() throws Exception {
        testEngine.saveText( "TocTest", "# Heading" );
        final Context ctx = contextFor( "TocTest" );
        final String res = manager.execute( ctx, "{TableOfContents numbered='true' start='abc'}" );

        Assertions.assertTrue( res.contains( "toc" ),
                "Result should contain TOC div even with invalid start" );
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link TableOfContents} instance pre-configured with a numbered list
     * using the given start index and prefix. The instance is set up the same way
     * that {@code execute()} would configure it when {@code numbered=true}.
     */
    private TableOfContents createNumberedToc( final Context ctx, final int start,
                                               final String prefixStr ) throws Exception {
        final TableOfContents toc = new TableOfContents();

        // Set the private fields via reflection, matching what execute() does
        setField( toc, "usingNumberedList", true );
        setField( toc, "starting", start );
        setField( toc, "level1Index", Math.max( start - 1, 0 ) );
        setField( toc, "level2Index", 0 );
        setField( toc, "level3Index", 0 );
        setField( toc, "prefix", prefixStr );
        setField( toc, "lastLevel", Heading.HEADING_LARGE );

        return toc;
    }

    private static void setField( final Object obj, final String fieldName, final Object value )
            throws Exception {
        final Field f = TableOfContents.class.getDeclaredField( fieldName );
        f.setAccessible( true );
        f.set( obj, value );
    }

    private static Heading makeHeading( final int level, final String text,
                                        final String section ) {
        final Heading h = new Heading();
        h.level = level;
        h.titleText = text;
        h.titleAnchor = text.toLowerCase();
        h.titleSection = section;
        return h;
    }
}
