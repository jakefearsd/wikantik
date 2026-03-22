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
package com.wikantik.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownLinkScannerTest {

    // --- classifyLink ---

    @Test
    void testClassifyLocalLink() {
        assertEquals( "local", MarkdownLinkScanner.classifyLink( "SomePage" ) );
    }

    @Test
    void testClassifyHttpLink() {
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "http://example.com" ) );
    }

    @Test
    void testClassifyHttpsLink() {
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "https://example.com/path" ) );
    }

    @Test
    void testClassifyFtpLink() {
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "ftp://files.example.com" ) );
    }

    @Test
    void testClassifyMailtoLink() {
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "mailto:user@example.com" ) );
    }

    @Test
    void testClassifyAnchorLink() {
        assertEquals( "anchor", MarkdownLinkScanner.classifyLink( "#section-name" ) );
    }

    // --- findLocalLinks ---

    @Test
    void testFindLocalLinksNull() {
        assertEquals( Set.of(), MarkdownLinkScanner.findLocalLinks( null ) );
    }

    @Test
    void testFindLocalLinksEmpty() {
        assertEquals( Set.of(), MarkdownLinkScanner.findLocalLinks( "" ) );
    }

    @Test
    void testFindLocalLinksNoLinks() {
        assertEquals( Set.of(), MarkdownLinkScanner.findLocalLinks( "Plain text with no links." ) );
    }

    @Test
    void testFindLocalLinksFiltersExternal() {
        final String text = "See [Google](https://google.com) and [page](WikiPage).";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "WikiPage" ), locals );
    }

    @Test
    void testFindLocalLinksFiltersAnchors() {
        final String text = "Jump to [top](#top) or read [about](AboutPage).";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "AboutPage" ), locals );
    }

    @Test
    void testFindLocalLinksFiltersMailto() {
        final String text = "Email [admin](mailto:admin@example.com) or see [help](HelpPage).";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "HelpPage" ), locals );
    }

    @Test
    void testFindLocalLinksDeduplicate() {
        final String text = "See [page](WikiPage) and [the page again](WikiPage).";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( 1, locals.size() );
        assertTrue( locals.contains( "WikiPage" ) );
    }

    @Test
    void testFindLocalLinksMultiple() {
        final String text = "See [A](PageA), [B](PageB), and [C](PageC).";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "PageA", "PageB", "PageC" ), locals );
    }

    @Test
    void testFindLocalLinksStripsAnchors() {
        final String text = "See [section](PageName#overview) and [another](OtherPage#details).";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "PageName", "OtherPage" ), locals );
    }

    @Test
    void testFindLocalLinksAnchorOnlyLinkExcluded() {
        // A link to just #anchor (no page name) should be excluded
        final String text = "Jump to [top](#top).";
        assertTrue( MarkdownLinkScanner.findLocalLinks( text ).isEmpty() );
    }

    @Test
    void testFindLocalLinksEmptyTargetUsesText() {
        // Wikantik convention: [PageName]() — empty target, text is the page name
        final String text = "See [Foobar]() and [TestPage]().";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "Foobar", "TestPage" ), locals );
    }

    @Test
    void testFindLocalLinksEmptyTargetSkipsPlugins() {
        // Plugin syntax [{PluginName}]() should NOT be treated as a page link
        final String text = "[{TableOfContents}]() and [RealPage]()";
        final Set< String > locals = MarkdownLinkScanner.findLocalLinks( text );
        assertEquals( Set.of( "RealPage" ), locals );
    }

    @Test
    void testFindLocalLinksEmptyTargetSkipsVariables() {
        final String text = "[{$applicationname}]() some text";
        assertTrue( MarkdownLinkScanner.findLocalLinks( text ).isEmpty() );
    }

    // --- scanAll ---

    @Test
    void testScanAllNull() {
        assertTrue( MarkdownLinkScanner.scanAll( null ).isEmpty() );
    }

    @Test
    void testScanAllEmpty() {
        assertTrue( MarkdownLinkScanner.scanAll( "" ).isEmpty() );
    }

    @Test
    void testScanAllMixedLinks() {
        final String text = "See [page](WikiPage), [Google](https://google.com), and [top](#top).";
        final List< Map< String, String > > links = MarkdownLinkScanner.scanAll( text );

        assertEquals( 3, links.size() );

        assertEquals( "WikiPage", links.get( 0 ).get( "target" ) );
        assertEquals( "page", links.get( 0 ).get( "text" ) );
        assertEquals( "local", links.get( 0 ).get( "type" ) );

        assertEquals( "https://google.com", links.get( 1 ).get( "target" ) );
        assertEquals( "Google", links.get( 1 ).get( "text" ) );
        assertEquals( "external", links.get( 1 ).get( "type" ) );

        assertEquals( "#top", links.get( 2 ).get( "target" ) );
        assertEquals( "top", links.get( 2 ).get( "text" ) );
        assertEquals( "anchor", links.get( 2 ).get( "type" ) );
    }

    @Test
    void testScanAllMailtoClassifiedAsExternal() {
        final String text = "Contact [support](mailto:help@example.com).";
        final List< Map< String, String > > links = MarkdownLinkScanner.scanAll( text );
        assertEquals( 1, links.size() );
        assertEquals( "external", links.get( 0 ).get( "type" ) );
    }

    @Test
    void testScanAllEmptyLinkText() {
        final String text = "An [](EmptyTextPage) link.";
        final List< Map< String, String > > links = MarkdownLinkScanner.scanAll( text );
        assertEquals( 1, links.size() );
        assertEquals( "", links.get( 0 ).get( "text" ) );
        assertEquals( "EmptyTextPage", links.get( 0 ).get( "target" ) );
    }
}
