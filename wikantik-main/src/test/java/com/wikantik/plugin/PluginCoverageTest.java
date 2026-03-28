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
import com.wikantik.WikiSession;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.pages.PageManager;
import com.wikantik.parser.Heading;
import com.wikantik.render.RenderingManager;
import com.wikantik.search.SearchManager;
import jakarta.servlet.http.HttpServletRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test coverage for wiki plugins that previously had 0% or low coverage.
 * Tests exercise plugins through the rendering pipeline using the standard TestEngine pattern.
 */
class PluginCoverageTest {

    // ========================================================================
    //  ReferredPagesPlugin tests (was 0%, 109 lines)
    // ========================================================================
    @Nested
    class ReferredPagesPluginTests {

        static final TestEngine engine = TestEngine.build(
                with( "wikantik.cache.enable", "false" ) );
        static final PluginManager manager = engine.getManager( PluginManager.class );

        private final List<String> createdPages = new ArrayList<>();

        @AfterEach
        void tearDown() {
            createdPages.forEach( engine::deleteTestPage );
            createdPages.clear();
            TestEngine.emptyWorkDir();
        }

        private void savePage( final String name, final String content ) throws WikiException {
            engine.saveText( name, content );
            createdPages.add( name );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testPageWithOutboundLinks() throws Exception {
            savePage( "SourcePage", "Links to [TargetA]() and [TargetB]()." );
            savePage( "TargetA", "Target A content." );
            savePage( "TargetB", "Target B content." );

            final Context ctx = contextFor( "SourcePage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=SourcePage}" );

            assertAll(
                    () -> assertTrue( res.contains( "ReferredPagesPlugin" ), "Should contain plugin div class" ),
                    () -> assertTrue( res.contains( "TargetA" ), "Should list TargetA" ),
                    () -> assertTrue( res.contains( "TargetB" ), "Should list TargetB" ),
                    () -> assertTrue( res.contains( "<ul>" ), "Should produce a list" ),
                    () -> assertTrue( res.contains( "<li>" ), "Should contain list items" )
            );
        }

        @Test
        void testPageWithNoOutboundLinks() throws Exception {
            savePage( "IsolatedPage", "This page has no links." );

            final Context ctx = contextFor( "IsolatedPage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=IsolatedPage}" );

            assertAll(
                    () -> assertTrue( res.contains( "ReferredPagesPlugin" ), "Should contain plugin div" ),
                    () -> assertTrue( res.contains( "IsolatedPage" ), "Should show root page name" ),
                    () -> assertFalse( res.contains( "<li>" ), "Should have no list items for page with no links" )
            );
        }

        @Test
        void testDefaultPageParameter() throws Exception {
            // When no page param, uses the current context page
            savePage( "CurrentPage", "Link to [SomeTarget]()." );
            savePage( "SomeTarget", "Target content." );

            final Context ctx = contextFor( "CurrentPage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin}" );

            assertTrue( res.contains( "CurrentPage" ), "Should use context page as root" );
            assertTrue( res.contains( "SomeTarget" ), "Should list referenced page" );
        }

        @Test
        void testDepthParameter() throws Exception {
            // Create a chain: A -> B -> C
            savePage( "ChainA", "Link to [ChainB]()." );
            savePage( "ChainB", "Link to [ChainC]()." );
            savePage( "ChainC", "End of chain." );

            final Context ctx = contextFor( "ChainA" );

            // depth=1 should only show direct references
            final String resDepth1 = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=ChainA depth=1}" );
            assertTrue( resDepth1.contains( "ChainB" ), "depth=1: should list direct ref ChainB" );

            // depth=2 should include transitive references
            final Context ctx2 = contextFor( "ChainA" );
            final String resDepth2 = manager.execute( ctx2,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=ChainA depth=2}" );
            assertTrue( resDepth2.contains( "ChainB" ), "depth=2: should list ChainB" );
            assertTrue( resDepth2.contains( "ChainC" ), "depth=2: should list transitive ChainC" );
        }

        @Test
        void testNonExistentPageParameter() throws Exception {
            savePage( "AnyPage", "Some content." );

            final Context ctx = contextFor( "AnyPage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=DoesNotExist}" );

            // Non-existent page should produce a div but no list items
            assertTrue( res.contains( "ReferredPagesPlugin" ), "Should still produce plugin div" );
        }

        @Test
        void testExcludePattern() throws Exception {
            savePage( "ExclSource", "Links to [ExclTargetA]() and [ExclTargetB]()." );
            savePage( "ExclTargetA", "Content A." );
            savePage( "ExclTargetB", "Content B." );

            final Context ctx = contextFor( "ExclSource" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=ExclSource exclude=ExclTargetA}" );

            assertFalse( res.contains( ">ExclTargetA<" ), "ExclTargetA should be excluded" );
            assertTrue( res.contains( "ExclTargetB" ), "ExclTargetB should still be present" );
        }

        @Test
        void testIncludePattern() throws Exception {
            savePage( "InclSource", "Links to [InclAlpha]() and [InclBeta]()." );
            savePage( "InclAlpha", "Alpha." );
            savePage( "InclBeta", "Beta." );

            final Context ctx = contextFor( "InclSource" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=InclSource include=InclAlpha}" );

            assertTrue( res.contains( "InclAlpha" ), "InclAlpha should be included" );
            assertFalse( res.contains( "InclBeta" ), "InclBeta should not match include pattern" );
        }

        @Test
        void testFormatFull() throws Exception {
            savePage( "FmtSource", "Link to [FmtTarget]()." );
            savePage( "FmtTarget", "Target." );

            final Context ctx = contextFor( "FmtSource" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=FmtSource format=full}" );

            assertTrue( res.contains( "ReferredPagesPlugin" ), "Full format should render" );
            assertTrue( res.contains( "FmtTarget" ), "Should list target in full format" );
        }

        @Test
        void testFormatSort() throws Exception {
            savePage( "SortSource", "Links to [SortBeta]() and [SortAlpha]()." );
            savePage( "SortAlpha", "Alpha." );
            savePage( "SortBeta", "Beta." );

            final Context ctx = contextFor( "SortSource" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=SortSource format=sort}" );

            assertTrue( res.contains( "SortAlpha" ), "Should contain SortAlpha" );
            assertTrue( res.contains( "SortBeta" ), "Should contain SortBeta" );
        }

        @Test
        void testColumnsParameter() throws Exception {
            savePage( "ColSource", "Link to [ColTarget]()." );
            savePage( "ColTarget", "Target." );

            final Context ctx = contextFor( "ColSource" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=ColSource columns=3}" );

            assertTrue( res.contains( "columns:3" ), "Should apply columns style" );
        }

        @Test
        void testInvalidIncludePatternThrows() throws Exception {
            savePage( "PatErr", "Link to [PatTarget]()." );
            savePage( "PatTarget", "Target." );

            final Context ctx = contextFor( "PatErr" );
            // Use an unclosed group which is always invalid regex
            assertThrows( PluginException.class, () ->
                    manager.execute( ctx,
                            "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=PatErr include='(?P<bad'}" ) );
        }

        @Test
        void testFormatFullWithAlreadySeenPage() throws Exception {
            // A -> B and A -> C, and both B and C link to D. In full format, when D appears
            // a second time it is listed by name only (no hyperlink).
            savePage( "FsRoot", "Links to [FsBranch1]() and [FsBranch2]()." );
            savePage( "FsBranch1", "Link to [FsLeaf]()." );
            savePage( "FsBranch2", "Link to [FsLeaf]()." );
            savePage( "FsLeaf", "Leaf content." );

            final Context ctx = contextFor( "FsRoot" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.ReferredPagesPlugin WHERE page=FsRoot depth=2 format=full}" );

            assertTrue( res.contains( "FsLeaf" ), "Leaf should appear at least once" );
            assertTrue( res.contains( "<ul>" ), "Should have nested lists in full format" );
        }
    }

    // ========================================================================
    //  TableOfContents tests (augment existing, was 0% on many branches)
    // ========================================================================
    @Nested
    class TableOfContentsTests {

        static final TestEngine engine = TestEngine.build();
        static final PluginManager manager = engine.getManager( PluginManager.class );

        @AfterEach
        void tearDown() {
            engine.deleteTestPage( "TocTest" );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testTocWithMultipleLevels() throws Exception {
            final String src = "[{TableOfContents}]()\n\n# Main Heading\n\n## Sub Heading\n\n### Sub Sub Heading";
            engine.saveText( "TocTest", src );
            final String res = engine.getI18nHTML( "TocTest" );

            assertAll(
                    () -> assertTrue( res.contains( "toc" ), "Should contain TOC div" ),
                    () -> assertTrue( res.contains( "Main Heading" ), "Should contain h1 text" ),
                    () -> assertTrue( res.contains( "Sub Heading" ), "Should contain h2 text" ),
                    () -> assertTrue( res.contains( "Sub Sub Heading" ), "Should contain h3 text" ),
                    () -> assertTrue( res.contains( "<h1" ), "Should produce h1 tag" ),
                    () -> assertTrue( res.contains( "<h2" ), "Should produce h2 tag" ),
                    () -> assertTrue( res.contains( "<h3" ), "Should produce h3 tag" )
            );
        }

        @Test
        void testTocWithNoHeadings() throws Exception {
            final String src = "[{TableOfContents}]()\n\nJust some plain text without headings.";
            engine.saveText( "TocTest", src );
            final String res = engine.getI18nHTML( "TocTest" );

            assertTrue( res.contains( "toc" ), "Should still have toc div" );
            // No heading tags should be generated outside the TOC itself
            assertFalse( res.contains( "<h1" ), "Should have no h1 headings" );
        }

        @Test
        void testTocWithCustomTitle() throws Exception {
            // Use manager.execute() to test the plugin's title parameter directly
            engine.saveText( "TocTest", "# Heading One" );
            final Context ctx = contextFor( "TocTest" );
            final String res = manager.execute( ctx, "{TableOfContents title='My Custom TOC'}" );

            assertAll(
                    () -> assertTrue( res.contains( "toc" ), "Should contain TOC div" ),
                    () -> assertTrue( res.contains( "My Custom TOC" ), "Should contain custom title" ),
                    () -> assertFalse( res.contains( "Table of Contents" ), "Should not contain default title" )
            );
        }

        @Test
        void testTocNumbered() throws Exception {
            // Use manager.execute() to exercise the numbered parameter code path in the plugin
            engine.saveText( "TocTest", "# First\n\n## Second\n\n### Third" );
            final Context ctx = contextFor( "TocTest" );
            final String res = manager.execute( ctx, "{TableOfContents numbered='true'}" );

            assertAll(
                    () -> assertTrue( res.contains( "toc" ), "Should contain TOC" ),
                    () -> assertTrue( res.contains( "collapsebox" ), "Should have collapsebox div" )
            );
        }

        @Test
        void testTocNumberedWithStartAndPrefix() throws Exception {
            engine.saveText( "TocTest", "# Chapter A\n\n# Chapter B" );
            final Context ctx = contextFor( "TocTest" );
            final String res = manager.execute( ctx, "{TableOfContents numbered='true' start='3' prefix='Ch'}" );

            assertAll(
                    () -> assertTrue( res.contains( "toc" ), "Should contain TOC" ),
                    () -> assertTrue( res.contains( "collapsebox" ), "Should have collapsebox div" )
            );
        }

        @Test
        void testTocNumberedYes() throws Exception {
            // Test the "yes" alternative for numbered parameter
            engine.saveText( "TocTest", "# Heading" );
            final Context ctx = contextFor( "TocTest" );
            final String res = manager.execute( ctx, "{TableOfContents numbered='yes'}" );

            assertTrue( res.contains( "toc" ), "Should contain TOC with numbered=yes" );
        }

        @Test
        void testTocLevelReset() throws Exception {
            // Tests the level counter reset: going from h3 back to h1
            final String src = "[{TableOfContents}]()\n\n### Deep First\n\n## Mid\n\n# Top\n\n## Mid Again\n\n### Deep Again";
            engine.saveText( "TocTest", src );
            final String res = engine.getI18nHTML( "TocTest" );

            assertAll(
                    () -> assertTrue( res.contains( "toc" ), "Should contain TOC" ),
                    () -> assertTrue( res.contains( "Deep First" ), "Should list deep heading" ),
                    () -> assertTrue( res.contains( "Top" ), "Should list top heading" )
            );
        }

        @Test
        void testTocAlreadyProcessing() throws Exception {
            // When the TOC plugin is invoked while already processing (recursive), it returns a link
            engine.saveText( "TocTest", "# Heading" );
            final Context ctx = contextFor( "TocTest" );
            // First call sets the processing variable
            manager.execute( ctx, "{TableOfContents}" );
            // Second call on same context should return the toc link instead
            final String res2 = manager.execute( ctx, "{TableOfContents}" );
            assertTrue( res2.contains( "toc" ), "Recursive call should return toc link" );
        }

        @Test
        void testHeadingAddedAllLevels() throws Exception {
            // Directly invoke headingAdded to exercise the callback method that the
            // MarkdownParser does not fire (it generates its own TOC)
            engine.saveText( "TocTest", "content" );
            final Context ctx = contextFor( "TocTest" );
            final TableOfContents toc = new TableOfContents();

            final Heading h1 = new Heading();
            h1.level = Heading.HEADING_LARGE;
            h1.titleText = "Large Heading";
            h1.titleAnchor = "large-heading";
            h1.titleSection = "Large-Heading";
            toc.headingAdded( ctx, h1 );

            final Heading h2 = new Heading();
            h2.level = Heading.HEADING_MEDIUM;
            h2.titleText = "Medium Heading";
            h2.titleAnchor = "medium-heading";
            h2.titleSection = "Medium-Heading";
            toc.headingAdded( ctx, h2 );

            final Heading h3 = new Heading();
            h3.level = Heading.HEADING_SMALL;
            h3.titleText = "Small Heading";
            h3.titleAnchor = "small-heading";
            h3.titleSection = "Small-Heading";
            toc.headingAdded( ctx, h3 );

            // Check that the buffer contains the heading text and proper TOC level classes
            final String tocHtml = toc.buf.toString();
            assertAll(
                    () -> assertTrue( tocHtml.contains( "Large Heading" ), "Should contain large heading" ),
                    () -> assertTrue( tocHtml.contains( "Medium Heading" ), "Should contain medium heading" ),
                    () -> assertTrue( tocHtml.contains( "Small Heading" ), "Should contain small heading" ),
                    () -> assertTrue( tocHtml.contains( "toclevel-1" ), "Should have toclevel-1 class" ),
                    () -> assertTrue( tocHtml.contains( "toclevel-2" ), "Should have toclevel-2 class" ),
                    () -> assertTrue( tocHtml.contains( "toclevel-3" ), "Should have toclevel-3 class" )
            );
        }

        @Test
        void testHeadingAddedLevelReset() throws Exception {
            // Tests the heading counter reset when going from h3 back to h1
            engine.saveText( "TocTest", "content" );
            final Context ctx = contextFor( "TocTest" );
            final TableOfContents toc = new TableOfContents();

            // Start with a small heading (h3)
            final Heading h3 = new Heading();
            h3.level = Heading.HEADING_SMALL;
            h3.titleText = "Sub Sub";
            h3.titleAnchor = "sub-sub";
            h3.titleSection = "Sub-Sub";
            toc.headingAdded( ctx, h3 );

            // Go to medium (h2) - should reset level3Index
            final Heading h2 = new Heading();
            h2.level = Heading.HEADING_MEDIUM;
            h2.titleText = "Sub";
            h2.titleAnchor = "sub";
            h2.titleSection = "Sub";
            toc.headingAdded( ctx, h2 );

            // Go to large (h1) - should reset both level2Index and level3Index
            final Heading h1 = new Heading();
            h1.level = Heading.HEADING_LARGE;
            h1.titleText = "Main";
            h1.titleAnchor = "main";
            h1.titleSection = "Main";
            toc.headingAdded( ctx, h1 );

            final String tocHtml = toc.buf.toString();
            assertTrue( tocHtml.contains( "Main" ), "Should contain main heading after level reset" );
        }

        @Test
        void testHeadingAddedWithPercentSign() throws Exception {
            // Test that % signs in section names are handled (replaced with _)
            engine.saveText( "TocTest", "content" );
            final Context ctx = contextFor( "TocTest" );
            final TableOfContents toc = new TableOfContents();

            final Heading h = new Heading();
            h.level = Heading.HEADING_LARGE;
            h.titleText = "Heading With Special Chars";
            h.titleAnchor = "heading-special";
            h.titleSection = "Heading%20Special";
            toc.headingAdded( ctx, h );

            final String tocHtml = toc.buf.toString();
            // The titleSection should have % replaced with _
            assertTrue( tocHtml.contains( "Heading_20Special" ), "Percent signs should be replaced with underscores" );
        }
    }

    // ========================================================================
    //  Image plugin tests (was 0%, 78 lines)
    // ========================================================================
    @Nested
    class ImagePluginTests {

        static final TestEngine engine = TestEngine.build();
        static final PluginManager manager = engine.getManager( PluginManager.class );

        @AfterEach
        void tearDown() {
            engine.deleteTestPage( "ImgTest" );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testBasicImage() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png'}" );

            assertAll(
                    () -> assertTrue( res.contains( "<img" ), "Should contain img tag" ),
                    () -> assertTrue( res.contains( "src=\"http://example.com/test.png\"" ),
                            "Should have correct src" ),
                    () -> assertTrue( res.contains( "imageplugin" ), "Should have imageplugin class" ),
                    () -> assertTrue( res.contains( "<table" ), "Image wrapped in table" )
            );
        }

        @Test
        void testImageWithWidthAndHeight() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' width=200 height=100}" );

            assertAll(
                    () -> assertTrue( res.contains( "width=\"200\"" ), "Should have width" ),
                    () -> assertTrue( res.contains( "height=\"100\"" ), "Should have height" )
            );
        }

        @Test
        void testImageWithCaption() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' caption='My Caption'}" );

            assertTrue( res.contains( "<caption>My Caption</caption>" ), "Should contain caption" );
        }

        @Test
        void testImageWithLink() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' link='http://example.com'}" );

            assertAll(
                    () -> assertTrue( res.contains( "<a href=\"http://example.com\">" ),
                            "Should contain link wrapping image" ),
                    () -> assertTrue( res.contains( "</a>" ), "Should close link tag" )
            );
        }

        @Test
        void testImageWithLinkAndTarget() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' link='http://example.com' target='_blank'}" );

            assertTrue( res.contains( "target=\"_blank\"" ), "Should contain target attribute" );
        }

        @Test
        void testImageWithInvalidTarget() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' link='http://example.com' target='123invalid'}" );

            // Invalid targets (starting with digit) are dropped
            assertFalse( res.contains( "target=" ), "Invalid target should be ignored" );
        }

        @Test
        void testImageWithAltText() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' alt='Description'}" );

            assertTrue( res.contains( "alt=\"Description\"" ), "Should contain alt text" );
        }

        @Test
        void testImageWithStyle() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' style='border: 1px solid'}" );

            assertTrue( res.contains( "style=\"border: 1px solid;" ), "Should contain style with trailing semicolon" );
        }

        @Test
        void testImageWithCssClass() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' class='myclass'}" );

            assertTrue( res.contains( "class=\"myclass\"" ), "Should contain custom CSS class" );
        }

        @Test
        void testImageWithBorder() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' border=1}" );

            assertTrue( res.contains( "border=\"1\"" ), "Should contain border attribute" );
        }

        @Test
        void testImageWithTitle() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' title='My Title'}" );

            assertTrue( res.contains( "title=\"My Title\"" ), "Should contain title attribute on table" );
        }

        @Test
        void testImageAlignCenter() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' align='center'}" );

            assertTrue( res.contains( "margin-left: auto; margin-right: auto" ),
                    "Center alignment should use auto margins" );
        }

        @Test
        void testImageAlignLeft() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' align='left'}" );

            assertTrue( res.contains( "float:left" ), "Left alignment should use float" );
        }

        @Test
        void testImageWithNoSrcThrows() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            assertThrows( PluginException.class, () ->
                    manager.execute( ctx, "{INSERT com.wikantik.plugin.Image}" ) );
        }

        @Test
        void testImageDataUrlPassesThrough() throws Exception {
            // With allowHTML=true (test config), data: URLs are not sanitized
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='data:image/png;base64,abc'}" );

            assertTrue( res.contains( "<img" ), "Should still produce img tag" );
            assertTrue( res.contains( "data:image/png" ), "data: URL should pass through when HTML allowed" );
        }

        @Test
        void testImageJavascriptUrlPassesThrough() throws Exception {
            // With allowHTML=true (test config), javascript: URLs are not sanitized
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='javascript:alert(1)'}" );

            assertTrue( res.contains( "<img" ), "Should still produce img tag" );
        }

        @Test
        void testImageWithValidNamedTarget() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' link='http://example.com' target='myframe'}" );

            assertTrue( res.contains( "target=\"myframe\"" ), "Named target starting with letter should be valid" );
        }

        @Test
        void testImageStyleAlreadyHasSemicolon() throws Exception {
            engine.saveText( "ImgTest", "content" );
            final Context ctx = contextFor( "ImgTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Image WHERE src='http://example.com/test.png' style='color: red;'}" );

            // Should not add double semicolons
            assertFalse( res.contains( ";;" ), "Should not have double semicolons in style" );
        }
    }

    // ========================================================================
    //  IndexPlugin tests (was 0%, 48 lines)
    // ========================================================================
    @Nested
    class IndexPluginTests {

        static final TestEngine engine = TestEngine.build(
                with( "wikantik.cache.enable", "false" ) );
        static final PluginManager manager = engine.getManager( PluginManager.class );

        private final List<String> createdPages = new ArrayList<>();

        @AfterEach
        void tearDown() {
            createdPages.forEach( engine::deleteTestPage );
            createdPages.clear();
            TestEngine.emptyWorkDir();
        }

        private void savePage( final String name, final String content ) throws WikiException {
            engine.saveText( name, content );
            createdPages.add( name );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testBasicIndex() throws Exception {
            savePage( "AlphaPage", "Alpha content." );
            savePage( "BetaPage", "Beta content." );

            final Context ctx = contextFor( "AlphaPage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.IndexPlugin}" );

            assertAll(
                    () -> assertTrue( res.contains( "index" ), "Should have index class" ),
                    () -> assertTrue( res.contains( "AlphaPage" ), "Should contain AlphaPage" ),
                    () -> assertTrue( res.contains( "BetaPage" ), "Should contain BetaPage" ),
                    () -> assertTrue( res.contains( "href" ), "Should contain links" )
            );
        }

        @Test
        void testIndexWithIncludePattern() throws Exception {
            savePage( "IdxAlpha", "Content A." );
            savePage( "IdxBeta", "Content B." );

            final Context ctx = contextFor( "IdxAlpha" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.IndexPlugin WHERE include='IdxAlpha'}" );

            assertTrue( res.contains( "IdxAlpha" ), "Should contain included page" );
            assertFalse( res.contains( "IdxBeta" ), "Should exclude non-matching page" );
        }

        @Test
        void testIndexWithExcludePattern() throws Exception {
            savePage( "IdxExclA", "Content A." );
            savePage( "IdxExclB", "Content B." );

            final Context ctx = contextFor( "IdxExclA" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.IndexPlugin WHERE exclude='IdxExclB'}" );

            assertTrue( res.contains( "IdxExclA" ), "Should contain non-excluded page" );
            assertFalse( res.contains( "IdxExclB" ), "Should exclude matching page" );
        }

        @Test
        void testIndexAlphabeticalHeaders() throws Exception {
            savePage( "Aaardvark", "A content." );
            savePage( "Bbison", "B content." );
            savePage( "Ccougar", "C content." );

            final Context ctx = contextFor( "Aaardvark" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.IndexPlugin}" );

            // Index should have alphabetical section headers
            assertAll(
                    () -> assertTrue( res.contains( "section" ), "Should have section class for headers" ),
                    () -> assertTrue( res.contains( "header" ), "Should have header div" )
            );
        }
    }

    // ========================================================================
    //  Search plugin tests (was 0%, 54 lines)
    // ========================================================================
    @Nested
    class SearchPluginTests {

        private TestEngine engine;
        private PluginManager manager;
        private SearchManager searchMgr;

        @AfterEach
        void tearDown() {
            if( engine != null ) {
                engine.deleteTestPage( "SearchTestPage" );
                engine.deleteTestPage( "SearchableFoo" );
                engine.stop();
            }
        }

        private void initEngine() {
            final var props = TestEngine.getTestProperties();
            final String workDir = props.getProperty( "wikantik.workDir" );
            final String workRepo = props.getProperty( "wikantik.fileSystemProvider.pageDir" );
            props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
            props.setProperty( "wikantik.lucene.indexdelay", "0" );
            props.setProperty( "wikantik.lucene.initialdelay", "0" );
            props.setProperty( "wikantik.workDir", workDir + System.currentTimeMillis() );
            props.setProperty( "wikantik.fileSystemProvider.pageDir", workRepo + System.currentTimeMillis() );
            engine = TestEngine.build( props );
            manager = engine.getManager( PluginManager.class );
            searchMgr = engine.getManager( SearchManager.class );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testSearchWithResults() throws Exception {
            initEngine();
            final String uniqueTerm = "zqxwv" + System.currentTimeMillis();
            engine.saveText( "SearchableFoo", "This page contains " + uniqueTerm + " as content." );

            // Wait for the search engine to index the page
            Awaitility.await( "waiting for search index" )
                    .atMost( 10, TimeUnit.SECONDS )
                    .pollInterval( 200, TimeUnit.MILLISECONDS )
                    .until( () -> {
                        final Context c = Wiki.context().create( engine,
                                HttpMockFactory.createHttpRequest(),
                                ContextEnum.PAGE_EDIT.getRequestContext() );
                        final var results = searchMgr.findPages( uniqueTerm, c );
                        return results != null && !results.isEmpty();
                    } );

            final Context ctx = contextFor( "SearchableFoo" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Search WHERE query='" + uniqueTerm + "'}" );

            assertAll(
                    () -> assertTrue( res.contains( "<table" ), "Should produce result table" ),
                    () -> assertTrue( res.contains( "search-result" ), "Should have search-result class" ),
                    () -> assertTrue( res.contains( "SearchableFoo" ), "Should contain page name in results" ),
                    () -> assertTrue( res.contains( "Score" ), "Should have Score header" ),
                    () -> assertTrue( res.contains( "Page" ), "Should have Page header" )
            );
        }

        @Test
        void testSearchWithNoResults() throws Exception {
            initEngine();
            engine.saveText( "SearchTestPage", "Content." );

            final Context ctx = contextFor( "SearchTestPage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Search WHERE query='xyzzy_nonexistent_term_98765'}" );

            // When no results, search returns a table with "No results"
            assertAll(
                    () -> assertNotNull( res, "Should not return null" ),
                    () -> assertTrue( res.contains( "No results" ), "Should contain 'No results'" ),
                    () -> assertTrue( res.contains( "<table" ), "Should produce table even with no results" )
            );
        }

        @Test
        void testSearchWithMaxParameter() throws Exception {
            initEngine();
            final String uniqueTerm = "maxqr" + System.currentTimeMillis();
            engine.saveText( "SearchTestPage", "The " + uniqueTerm + " content." );

            // Wait for indexing
            Awaitility.await( "waiting for max search index" )
                    .atMost( 10, TimeUnit.SECONDS )
                    .pollInterval( 200, TimeUnit.MILLISECONDS )
                    .until( () -> {
                        final Context c = Wiki.context().create( engine,
                                HttpMockFactory.createHttpRequest(),
                                ContextEnum.PAGE_EDIT.getRequestContext() );
                        final var results = searchMgr.findPages( uniqueTerm, c );
                        return results != null && !results.isEmpty();
                    } );

            final Context ctx = contextFor( "SearchTestPage" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Search WHERE query='" + uniqueTerm + "' max=1}" );

            assertTrue( res.contains( "<table" ), "Should produce table" );
        }

        @Test
        void testSearchWithSetParameter() throws Exception {
            initEngine();
            engine.saveText( "SearchTestPage", "Content." );

            final Context ctx = contextFor( "SearchTestPage" );
            // Execute with a named set
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Search WHERE query='Content' set='myResults'}" );

            assertNotNull( res, "Should not return null" );
        }

        @Test
        void testSearchWithNoQueryUsesDefaultSet() throws Exception {
            initEngine();
            engine.saveText( "SearchTestPage", "Content." );

            final Context ctx = contextFor( "SearchTestPage" );
            // Without query, the plugin looks for a variable in the default set
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.Search}" );

            // Should return empty string when no prior results exist
            assertEquals( "", res, "Should return empty when no query and no pre-existing set" );
        }
    }

    // ========================================================================
    //  IfPlugin tests (augment existing, targeting uncovered branches)
    // ========================================================================
    @Nested
    class IfPluginAdditionalTests {

        static final TestEngine engine = TestEngine.build();
        static final PluginManager manager = engine.getManager( PluginManager.class );

        private final List<String> createdPages = new ArrayList<>();

        @AfterEach
        void tearDown() {
            createdPages.forEach( engine::deleteTestPage );
            createdPages.clear();
        }

        private void savePage( final String name, final String content ) throws WikiException {
            engine.saveText( name, content );
            createdPages.add( name );
        }

        Context getJanneBasedContext( final Page page ) throws WikiException {
            final HttpServletRequest request = HttpMockFactory.createHttpRequest();
            final var session = WikiSession.getWikiSession( engine, request );
            engine.getManager( AuthenticationManager.class ).login( session, request, Users.JANNE, Users.JANNE_PASS );
            return Wiki.context().create( engine, request, page );
        }

        @Test
        void testIfPageExistsTrue() throws Exception {
            savePage( "ExistingPage", "This page exists." );
            savePage( "TestIf", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='ExistingPage' exists='true'\n\nPage exists content}" );

            assertEquals( "<p>Page exists content</p>\n", res, "Should show body when page exists and exists=true" );
        }

        @Test
        void testIfPageExistsFalse() throws Exception {
            savePage( "TestIf2", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf2" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='NoSuchPage' exists='true'\n\nShould not see this}" );

            assertEquals( "", res, "Should hide body when page does not exist but exists=true" );
        }

        @Test
        void testIfPageExistsFalseNegated() throws Exception {
            savePage( "TestIf3", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf3" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='NoSuchPage' exists='false'\n\nPage does not exist}" );

            assertEquals( "<p>Page does not exist</p>\n", res,
                    "Should show body when page does not exist and exists=false" );
        }

        @Test
        void testIfPageContains() throws Exception {
            savePage( "ContentPage", "The secret keyword is xyzzy." );
            savePage( "TestIf4", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf4" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='ContentPage' contains='xyzzy'\n\nFound the keyword}" );

            assertEquals( "<p>Found the keyword</p>\n", res, "Should show body when page contains match" );
        }

        @Test
        void testIfPageContainsNoMatch() throws Exception {
            savePage( "ContentPage2", "Nothing special here." );
            savePage( "TestIf5", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf5" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='ContentPage2' contains='xyzzy'\n\nShould not see}" );

            assertEquals( "", res, "Should hide body when page does not contain match" );
        }

        @Test
        void testIfPageIs() throws Exception {
            savePage( "ExactPage", "exact match" );
            savePage( "TestIf6", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf6" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='ExactPage' is='exact match'\n\nExact match found}" );

            assertEquals( "<p>Exact match found</p>\n", res, "Should show body on exact match" );
        }

        @Test
        void testIfPageIsNoMatch() throws Exception {
            savePage( "ExactPage2", "something else" );
            savePage( "TestIf7", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf7" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin page='ExactPage2' is='exact match'\n\nShould not see}" );

            assertEquals( "", res, "Should hide body on no exact match" );
        }

        @Test
        void testIfVar() throws Exception {
            savePage( "TestIf8", "[{SET myvar=hello}]\n\nSome content" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf8" );
            final Context ctx = getJanneBasedContext( page );

            // Variable-based conditions require the variable to be set in context.
            // Just verify it does not throw for var parameter.
            final String res = manager.execute( ctx,
                    "{IfPlugin var='applicationname' exists='true'\n\nApp name exists}" );

            assertEquals( "<p>App name exists</p>\n", res,
                    "Should show body when variable exists" );
        }

        @Test
        void testIfVarNotExists() throws Exception {
            savePage( "TestIf9", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIf9" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin var='nonexistentvar' exists='true'\n\nShould not see}" );

            assertEquals( "", res, "Should hide body when variable does not exist" );
        }

        @Test
        void testIfGroupAdmin() throws Exception {
            savePage( "TestIfGrp", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIfGrp" );
            final Context ctx = getJanneBasedContext( page );

            // Janne is not an Admin, so this should not match
            final String res = manager.execute( ctx,
                    "{IfPlugin group='Admin'\n\nAdmin content}" );

            assertEquals( "", res, "Janne should not match Admin group" );
        }

        @Test
        void testIfGroupNegated() throws Exception {
            savePage( "TestIfGrpNeg", "dummy" );

            final Page page = engine.getManager( PageManager.class ).getPage( "TestIfGrpNeg" );
            final Context ctx = getJanneBasedContext( page );

            final String res = manager.execute( ctx,
                    "{IfPlugin group='!Admin'\n\nNot admin content}" );

            assertEquals( "<p>Not admin content</p>\n", res, "Negated Admin group should match for Janne" );
        }
    }

    // ========================================================================
    //  InsertPage tests (augment existing, targeting maxlength, section, default)
    // ========================================================================
    @Nested
    class InsertPageAdditionalTests {

        static final TestEngine engine = TestEngine.build();
        static final PluginManager manager = engine.getManager( PluginManager.class );

        private final List<String> createdPages = new ArrayList<>();

        @AfterEach
        void tearDown() {
            createdPages.forEach( engine::deleteTestPage );
            createdPages.clear();
        }

        private void savePage( final String name, final String content ) throws WikiException {
            engine.saveText( name, content );
            createdPages.add( name );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testInsertExistingPage() throws Exception {
            savePage( "InnerPage", "Inserted content here" );
            savePage( "OuterPage", "[{InsertPage page='InnerPage'}]" );

            final String html = engine.getManager( RenderingManager.class ).getHTML( "OuterPage" );

            assertAll(
                    () -> assertTrue( html.contains( "inserted-page" ), "Should have inserted-page div" ),
                    () -> assertTrue( html.contains( "Inserted content here" ), "Should contain inner page content" )
            );
        }

        @Test
        void testInsertNonExistentPage() throws Exception {
            savePage( "OuterPage2", "dummy" );

            final Context ctx = contextFor( "OuterPage2" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage WHERE page='NoSuchPage'}" );

            assertTrue( res.contains( "no page called" ) || res.contains( "create it" ),
                    "Should show message about missing page" );
        }

        @Test
        void testInsertWithDefaultParameter() throws Exception {
            savePage( "OuterPage3", "dummy" );

            final Context ctx = contextFor( "OuterPage3" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage WHERE page='NoSuchPage' default='Fallback text'}" );

            assertEquals( "Fallback text", res, "Should show default text for non-existent page" );
        }

        @Test
        void testInsertWithMaxlength() throws Exception {
            savePage( "LongPage", "A".repeat( 100 ) );
            savePage( "OuterPage4", "dummy" );

            final Context ctx = contextFor( "OuterPage4" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage WHERE page='LongPage' maxlength=10}" );

            assertAll(
                    () -> assertTrue( res.contains( "inserted-page" ), "Should have inserted-page div" ),
                    () -> assertTrue( res.contains( "..." ), "Should truncate with ellipsis" ),
                    () -> assertTrue( res.contains( "More" ) || res.contains( "more" ),
                            "Should have a 'more' link" )
            );
        }

        @Test
        void testInsertWithSection() throws Exception {
            savePage( "SectionedPage", "First section\n\n----\n\nSecond section\n\n----\n\nThird section" );
            savePage( "OuterPage5", "dummy" );

            final Context ctx = contextFor( "OuterPage5" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage WHERE page='SectionedPage' section=2}" );

            assertAll(
                    () -> assertTrue( res.contains( "inserted-page" ), "Should have inserted-page div" ),
                    () -> assertTrue( res.contains( "Second section" ), "Should contain section 2" )
            );
        }

        @Test
        void testInsertWithCustomClass() throws Exception {
            savePage( "InnerClassPage", "Some content" );
            savePage( "OuterPage6", "dummy" );

            final Context ctx = contextFor( "OuterPage6" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage WHERE page='InnerClassPage' class='my-custom-class'}" );

            assertTrue( res.contains( "my-custom-class" ), "Should include custom class" );
        }

        @Test
        void testInsertWithStyle() throws Exception {
            savePage( "InnerStylePage", "Some content" );
            savePage( "OuterPage7", "dummy" );

            final Context ctx = contextFor( "OuterPage7" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage WHERE page='InnerStylePage' style='color: red'}" );

            assertTrue( res.contains( "style=\"color: red" ), "Should include custom style" );
        }

        @Test
        void testInsertNoPageParameter() throws Exception {
            savePage( "OuterPage8", "dummy" );

            final Context ctx = contextFor( "OuterPage8" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.InsertPage}" );

            assertTrue( res.contains( "You have to define a page" ), "Should show error for missing page param" );
        }
    }

    // ========================================================================
    //  UnusedPagesPlugin tests (augment existing, targeting missed branches)
    // ========================================================================
    @Nested
    class UnusedPagesPluginAdditionalTests {

        static final TestEngine engine = TestEngine.build(
                with( "wikantik.cache.enable", "false" ) );
        static final PluginManager manager = engine.getManager( PluginManager.class );

        private final List<String> createdPages = new ArrayList<>();

        @AfterEach
        void tearDown() {
            createdPages.forEach( engine::deleteTestPage );
            createdPages.clear();
            TestEngine.emptyWorkDir();
        }

        private void savePage( final String name, final String content ) throws WikiException {
            engine.saveText( name, content );
            createdPages.add( name );
        }

        private Context contextFor( final String pageName ) {
            return Wiki.context().create( engine,
                    HttpMockFactory.createHttpRequest(),
                    Wiki.contents().page( engine, pageName ) );
        }

        @Test
        void testOrphanedPagesAppearInList() throws Exception {
            savePage( "OrphanX", "Orphan page X content." );
            savePage( "OrphanY", "Orphan page Y content." );

            final Context ctx = contextFor( "OrphanX" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.UnusedPagesPlugin}" );

            assertAll(
                    () -> assertTrue( res.contains( "OrphanX" ), "Should list OrphanX" ),
                    () -> assertTrue( res.contains( "OrphanY" ), "Should list OrphanY" )
            );
        }

        @Test
        void testShowCount() throws Exception {
            savePage( "OrphanCount1", "Orphan 1." );
            savePage( "OrphanCount2", "Orphan 2." );

            final Context ctx = contextFor( "OrphanCount1" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.UnusedPagesPlugin WHERE show=count}" );

            // Should return a number, which is the count of unused pages
            assertNotNull( res, "Should not be null" );
            assertFalse( res.isEmpty(), "Should not be empty for count mode" );
        }

        @Test
        void testShowCountWithLastModified() throws Exception {
            savePage( "OrphanLM1", "Orphan LM." );

            final Context ctx = contextFor( "OrphanLM1" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.UnusedPagesPlugin WHERE show=count showLastModified=true}" );

            assertNotNull( res, "Should not be null" );
            // The format is "N (date)" when showLastModified is true
            assertTrue( res.contains( "(" ), "Should contain date in parentheses when showLastModified=true" );
        }

        @Test
        void testExcludeAttachments() throws Exception {
            savePage( "OrphanAttTest", "Content." );

            final Context ctx = contextFor( "OrphanAttTest" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.UnusedPagesPlugin WHERE excludeattachments=true}" );

            assertNotNull( res, "Should not be null with excludeattachments" );
        }

        @Test
        void testWithExcludePattern() throws Exception {
            savePage( "UnusedExclA", "Content A." );
            savePage( "UnusedExclB", "Content B." );

            final Context ctx = contextFor( "UnusedExclA" );
            final String res = manager.execute( ctx,
                    "{INSERT com.wikantik.plugin.UnusedPagesPlugin WHERE exclude='UnusedExclB'}" );

            assertTrue( res.contains( "UnusedExclA" ), "Should still contain non-excluded orphan" );
            assertFalse( res.contains( "UnusedExclB" ), "Should exclude matching page" );
        }
    }
}
