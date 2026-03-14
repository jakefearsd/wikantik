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
package org.apache.wiki.references;

import org.apache.wiki.TestEngine;
import org.apache.wiki.parser.markdown.MarkdownParser;
import org.apache.wiki.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.apache.wiki.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the MarkdownParser correctly feeds discovered links into the
 * mutator chains so that ReferenceManager tracks Markdown links.
 */
public class MarkdownReferenceManagerTest {

    TestEngine engine = TestEngine.build(
            with( "jspwiki.renderingManager.markupParser", MarkdownParser.class.getName() ),
            with( "jspwiki.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            with( "jspwiki.fileSystemProvider.pageDir", "./target/md-ref-pageDir" )
    );

    ReferenceManager mgr = engine.getManager( ReferenceManager.class );

    @AfterEach
    public void tearDown() {
        engine.stop();
    }

    @Test
    void testMarkdownLocalLinksTracked() throws Exception {
        engine.saveText( "MdLinkSource", "Check out [OtherPage](OtherPage) for details." );

        final Collection< String > refersTo = mgr.findRefersTo( "MdLinkSource" );
        assertTrue( refersTo.contains( "OtherPage" ), "OtherPage should be in refersTo: " + refersTo );
    }

    @Test
    void testMarkdownExternalLinksNotLocal() throws Exception {
        engine.saveText( "MdExtLink", "Visit [example](https://example.com) for more." );

        final Collection< String > refersTo = mgr.findRefersTo( "MdExtLink" );
        assertFalse( refersTo.contains( "https://example.com" ),
                "External links should not appear in local refersTo" );
    }

    @Test
    void testMarkdownAnchorLinksIgnored() throws Exception {
        engine.saveText( "MdAnchorLink", "Jump to [top](#top) of the page." );

        final Collection< String > refersTo = mgr.findRefersTo( "MdAnchorLink" );
        // Anchor links should not create page references
        assertTrue( refersTo.isEmpty(), "Anchor-only links should not track as page references: " + refersTo );
    }

    @Test
    void testMarkdownBacklinksWork() throws Exception {
        engine.saveText( "MdPageA", "Link to [MdPageB](MdPageB)." );
        engine.saveText( "MdPageB", "This is page B." );

        final Collection< String > referrers = mgr.findReferrers( "MdPageB" );
        assertNotNull( referrers, "referrers should not be null" );
        assertTrue( referrers.contains( "MdPageA" ), "MdPageA should be a referrer of MdPageB: " + referrers );
    }

    @Test
    void testMarkdownBrokenLinksDetected() throws Exception {
        engine.saveText( "MdBrokenSource", "Link to [NonExistentPage](NonExistentPage)." );

        final Collection< String > uncreated = mgr.findUncreated();
        assertTrue( uncreated.contains( "NonExistentPage" ),
                "NonExistentPage should appear in uncreated (broken links): " + uncreated );
    }

    @Test
    void testMarkdownSectionLinksExtractPageName() throws Exception {
        engine.saveText( "MdSectionLink", "See [details](TargetPage#heading-two)." );

        final Collection< String > refersTo = mgr.findRefersTo( "MdSectionLink" );
        assertTrue( refersTo.contains( "TargetPage" ),
                "Section link should track page name without fragment: " + refersTo );
    }

    @Test
    void testMarkdownMultipleLinksTracked() throws Exception {
        engine.saveText( "MdMultiLink",
                "Link to [PageOne](PageOne) and [PageTwo](PageTwo) and [ext](https://foo.bar)." );

        final Collection< String > refersTo = mgr.findRefersTo( "MdMultiLink" );
        assertTrue( refersTo.contains( "PageOne" ), "Should contain PageOne: " + refersTo );
        assertTrue( refersTo.contains( "PageTwo" ), "Should contain PageTwo: " + refersTo );
        assertFalse( refersTo.contains( "https://foo.bar" ), "Should not contain external link" );
    }

    @Test
    void testMarkdownPageBecomesUnreferenced() throws Exception {
        engine.saveText( "MdTarget", "Target content." );
        engine.saveText( "MdLinker", "Link to [MdTarget](MdTarget)." );

        Collection< String > unreferenced = mgr.findUnreferenced();
        assertFalse( unreferenced.contains( "MdTarget" ), "MdTarget should be referenced" );

        // Remove the link
        engine.saveText( "MdLinker", "No more links." );

        unreferenced = mgr.findUnreferenced();
        assertTrue( unreferenced.contains( "MdTarget" ),
                "MdTarget should become unreferenced after link removed" );
    }
}
