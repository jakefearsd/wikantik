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
package com.wikantik.render.markdown;

import com.wikantik.markdown.extensions.blockids.BlockOrdinals;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.parser.markdown.MarkdownParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class MarkdownRendererTest {

    List< String > created = new ArrayList<>();

    static final String PAGE_NAME = "testpage";

    TestEngine testEngine = TestEngine.build( TestEngine.with( "wikantik.translatorReader.matchEnglishPlurals", "true" ),
                                              TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
                                              TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
                                              TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
                                              TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );

    @Test
    public void testMarkupSimpleMarkdown() throws Exception {
        final String src = "This should be a **bold**";

        Assertions.assertEquals( "<p>This should be a <strong>bold</strong></p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionSelfViewLink() throws Exception {
    	newPage( "MarkupExtensionSelfViewLink" );
        final String src = "This should be a [MarkupExtensionSelfViewLink]()";

        Assertions.assertEquals( "<p>This should be a <a href=\"/test/wiki/MarkupExtensionSelfViewLink\" class=\"wikipage\">MarkupExtensionSelfViewLink</a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testMarkupExtensionSelfEditLink() throws Exception {
        final String src = "This should be a [self<->link]()";

        Assertions.assertEquals( "<p>This should be a <a href=\"/test/edit/self%3C-%3Elink\" title=\"Create &#34;self&lt;-&gt;link&#34;\" class=\"createpage\">self&lt;-&gt;link</a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testMarkupExtensionExternalLink() throws Exception {
        testEngine.getWikiProperties().setProperty( "wikantik.translatorReader.useOutlinkImage", "true" );
        final String src = "This should be an [external link](https://wikantik.com)";

        Assertions.assertEquals( "<p>This should be an <a href=\"https://wikantik.com\" class=\"external\">external link</a><img class=\"outlink\" alt=\"\" src=\"/test/images/out.png\" /></p>\n",
                                 translate( src ) );
        testEngine.getWikiProperties().remove( "wikantik.translatorReader.useOutlinkImage" );
    }

    @Test
    public void testMarkupExtensionHtmlInLinks() throws Exception {
        testEngine.getWikiProperties().setProperty( "wikantik.translatorReader.useOutlinkImage", "true" );
        final String src = "This should be an [external <strong>link</strong>](https://wikantik.com)";

        // With allowHTML=true (the default), HTML inside link text is preserved
        Assertions.assertEquals( "<p>This should be an <a href=\"https://wikantik.com\" class=\"external\">external <strong>link</strong></a><img class=\"outlink\" alt=\"\" src=\"/test/images/out.png\" /></p>\n",
                translate( src ) );
        testEngine.getWikiProperties().remove( "wikantik.translatorReader.useOutlinkImage" );
    }

    @Test
    public void testAttachmentLink1() throws Exception {
        newPage( "Hyperlink" );
        // With allowHTML=true (the default), raw HTML in link text is preserved
        final String expected = "<p>This should be a <a href=\"/test/attach/Link%20%3Cstrong%3Ebold%3C/strong%3E\" class=\"attachment\">Link <strong>bold</strong></a></p>\n";
        Assertions.assertEquals( expected, translate( "This should be a [Link <strong>bold</strong>]()" ) );
    }

    @Test
    public void testMarkupExtensionHtmlAllowsMDInsideLinks() throws Exception {
        newPage( "Hyperlink" );
        final String expected = "<p>This should be a <a href=\"/test/edit/Link%20**bold**\" title=\"Create &#34;Link **bold**&#34;\" class=\"createpage\">Link <strong>bold</strong></a></p>\n";
        Assertions.assertEquals( expected, translate( "This should be a [Link **bold**]()" ) );
    }

    @Test
    public void testMarkupExtensionAllowsHtmlFromPlugins() throws Exception {
        final String src = "<strong>string</strong> [{SamplePlugin text=test tag=strong}]()";
        // With allowHTML=true (the default), raw HTML in source is preserved
        Assertions.assertEquals( "<p><strong>string</strong> <strong>test</strong></p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionInterWikiLink() throws Exception {
        final String src = "This should be an [interwiki link](Wikantik:About)";

        Assertions.assertEquals( "<p>This should be an <a href=\"http://wiki.wikantik.com/Wiki.jsp?page&#61;About\" class=\"interwiki\">interwiki link</a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testMarkupExtensionWrongInterWikiLink() throws Exception {
        final String src = "This should be an [interwiki link](JSPWiko:About)";

        Assertions.assertEquals( "<p>This should be an <span class=\"error\">No InterWiki reference defined in properties for Wiki called &#34;JSPWiko&#34;!</span></p>\n",
                                 translate( src ) );
    }

    /** GFM autolinks: a bare URL links on the published page, as GitHub, Obsidian and the editor show it. */
    @Test
    public void testBareUrlIsLinked() throws Exception {
        Assertions.assertEquals( "<p>Visit <a href=\"https://wikantik.com/a\" class=\"external\">https://wikantik.com/a</a> today.</p>\n",
                                 translate( "Visit https://wikantik.com/a today." ) );
    }

    @Test
    public void testBareWwwUrlIsLinkedOverHttp() throws Exception {
        Assertions.assertEquals( "<p>Visit <a href=\"http://www.wikantik.com\" class=\"external\">www.wikantik.com</a> today.</p>\n",
                                 translate( "Visit www.wikantik.com today." ) );
    }

    @Test
    public void testBareEmailIsLinkedAsMailto() throws Exception {
        // flexmark writes the @ of every mail link as &#64; (the same character to a browser)
        Assertions.assertEquals( "<p>Mail <a href=\"mailto:someone&#64;wikantik.com\" class=\"external\">someone&#64;wikantik.com</a> today.</p>\n",
                                 translate( "Mail someone@wikantik.com today." ) );
    }

    /** An autolinked URL is decorated exactly like the same URL written as an external link (class, rel, outlink icon). */
    @Test
    public void testBareUrlDecoratedLikeAnExternalLink() throws Exception {
        testEngine.getWikiProperties().setProperty( "wikantik.translatorReader.useOutlinkImage", "true" );
        testEngine.getWikiProperties().setProperty( "wikantik.translatorReader.useRelNofollow", "true" );
        try {
            Assertions.assertEquals( translate( "Go [https://wikantik.com/a](https://wikantik.com/a) now." ),
                                     translate( "Go https://wikantik.com/a now." ) );
            Assertions.assertEquals( translate( "Go [https://wikantik.com/a](https://wikantik.com/a) now." ),
                                     translate( "Go <https://wikantik.com/a> now." ) );
            Assertions.assertEquals( translate( "Mail [a@wikantik.com](mailto:a@wikantik.com) now." ),
                                     translate( "Mail a@wikantik.com now." ) );
            final String bare = translate( "Go https://wikantik.com/a now." );
            Assertions.assertTrue( bare.contains( "class=\"outlink\"" ), bare );
            Assertions.assertTrue( bare.contains( "rel=\"nofollow\"" ), bare );
        } finally {
            testEngine.getWikiProperties().remove( "wikantik.translatorReader.useOutlinkImage" );
            testEngine.getWikiProperties().remove( "wikantik.translatorReader.useRelNofollow" );
        }
    }

    /** Trailing punctuation stays outside the link, a balanced parenthesis inside it (GFM, as remark-gfm reads it). */
    @Test
    public void testBareUrlTrailingPunctuation() throws Exception {
        Assertions.assertEquals( "<p>See <a href=\"https://wikantik.com/a\" class=\"external\">https://wikantik.com/a</a>.</p>\n",
                                 translate( "See https://wikantik.com/a." ) );
        Assertions.assertEquals( "<p>(see <a href=\"https://wikantik.com/a\" class=\"external\">https://wikantik.com/a</a>)</p>\n",
                                 translate( "(see https://wikantik.com/a)" ) );
        final String balanced = translate( "See https://en.wikipedia.org/wiki/Foo_(bar)." );
        Assertions.assertTrue( balanced.contains( ">https://en.wikipedia.org/wiki/Foo_(bar)</a>.</p>" ), balanced );
        Assertions.assertEquals( translate( "See [https://en.wikipedia.org/wiki/Foo_(bar)](https://en.wikipedia.org/wiki/Foo_(bar))." ), balanced );
    }

    /**
     * Only web addresses and email addresses autolink, as remark-gfm (the editor preview) and GitHub do: any other
     * {@code scheme://} text stays text (the sanitizer would strip such an href anyway, leaving a dead link).
     */
    @Test
    public void testOtherSchemesAreNotAutolinked() throws Exception {
        for( final String url : List.of( "ftp://files.wikantik.com/a", "redis://cache.wikantik.com:6379", "cite://Page/x",
                                         "JavaScript://wikantik.com/a", "s3://bucket.wikantik.com/key" ) ) {
            Assertions.assertEquals( "<p>Go " + url + " now.</p>\n", translate( "Go " + url + " now." ), url );
        }
        Assertions.assertEquals( "<p>Go <a href=\"HTTP://WIKANTIK.COM\" class=\"external\">HTTP://WIKANTIK.COM</a> now.</p>\n",
                                 translate( "Go HTTP://WIKANTIK.COM now." ) );
    }

    /** {@code mailto:a@b.co} as text: the address links, the {@code mailto:} prefix stays text (remark-gfm reads it so). */
    @Test
    public void testBareMailtoLinksTheAddress() throws Exception {
        Assertions.assertEquals( "<p>Mail mailto:<a href=\"mailto:a&#64;wikantik.com\" class=\"external\">a&#64;wikantik.com</a> now.</p>\n",
                                 translate( "Mail mailto:a@wikantik.com now." ) );
    }

    /** A bare image URL is a link, never an inlined image, even with image inlining on (GFM and the editor link it). */
    @Test
    public void testBareImageUrlStaysALink() throws Exception {
        final String html = translate( "See https://wikantik.com/a.png now." );
        Assertions.assertTrue( html.contains( "<a href=\"https://wikantik.com/a.png\" class=\"external\">https://wikantik.com/a.png</a>" ), html );
        Assertions.assertFalse( html.contains( "<img class=\"inline\"" ), html );
        Assertions.assertTrue( translate( "See [x](https://wikantik.com/a.png) now." ).contains( "<img" ),
                               "the explicit link is inlined, so inlining is on for this engine" );
    }

    @Test
    public void testUrlsInCodeAndLinksAreNotAutolinked() throws Exception {
        Assertions.assertEquals( "<p>Run <code>curl https://wikantik.com/a</code> now.</p>\n",
                                 translate( "Run `curl https://wikantik.com/a` now." ) );
        Assertions.assertEquals( "<pre><code>https://wikantik.com/a\n</code></pre>\n", translate( "```\nhttps://wikantik.com/a\n```" ) );
        Assertions.assertEquals( "<p>See <a href=\"https://wikantik.com/b\" class=\"external\">https://wikantik.com/a</a>.</p>\n",
                                 translate( "See [https://wikantik.com/a](https://wikantik.com/b)." ) );
    }

    /** A URL inside a native wikilink or a plugin line belongs to that markup, never to an autolink. */
    @Test
    public void testUrlsInWikiLinksAndPluginsAreNotAutolinked() throws Exception {
        newPage( "AutolinkTarget" );
        final String wiki = translate( "See [[AutolinkTarget|docs at https://wikantik.com/a]] now." );
        Assertions.assertEquals( 1, wiki.split( "<a ", -1 ).length - 1, wiki );
        Assertions.assertTrue( wiki.contains( ">docs at https://wikantik.com/a</a>" ), wiki );
        final String plugin = translate( "[{SET site='https://wikantik.com/a'}]\n\nThe site is [{$site}]." );
        Assertions.assertEquals( "<p>The site is https://wikantik.com/a.</p>\n", plugin.replace( "<p></p>\n", "" ) );
    }

    @Test
    public void testMarkupLinkWithCustomAttributes() throws Exception {
        final String src = "This should be a [link with custom attributes](http://google.com){target=blank}";

        Assertions.assertEquals( "<p>This should be a <a href=\"http://google.com\" class=\"external\" target=\"blank\" rel=\"noopener noreferrer\">link with custom attributes</a></p>\n", translate( src ) );
    }

    @Test
    public void testMarkupPWithCustomAttributes() throws Exception {
        // {..} are separated from the link, so they apply to the nearest p or span containing them
        final String src0 = "This should be a [link](http://google.com) {style='background-color:#ddd'}";
        Assertions.assertEquals( "<p style=\"background-color:#ddd\">This should be a <a href=\"http://google.com\" class=\"external\">link</a></p>\n", translate( src0 ) );

        final String src1 = "This should be a [link](http://google.com) {#a1}";
        Assertions.assertEquals( "<p id=\"a1\">This should be a <a href=\"http://google.com\" class=\"external\">link</a></p>\n", translate( src1 ) );

        final String src2 = "This should be a [link](http://google.com) {.warning}";
        Assertions.assertEquals( "<p class=\"warning\">This should be a <a href=\"http://google.com\" class=\"external\">link</a></p>\n", translate( src2 ) );
    }

    @Test
    public void testMarkupDefinitionList() throws Exception {
        final String src = "Definition Term\n" +
                           ": definition description";

        Assertions.assertEquals( "<dl>\n<dt>Definition Term</dt>\n<dd>definition description</dd>\n</dl>\n", translate( src ) );
    }

    @Test
    public void testMarkupTable() throws Exception {
        final String src = "|  a  |  b  |  c  \n" +
                           "|:--- |:---:|---  \n" +
                           "| d   | e   | f   \n" +
                           "||| g, h and f ";

        Assertions.assertEquals( "<table><thead><tr><th align=\"left\">a</th><th align=\"center\">b</th><th>c</th></tr></thead>" +
                                 "<tbody><tr><td align=\"left\">d</td><td align=\"center\">e</td><td>f</td></tr>" +
                                 "<tr><td align=\"left\" colspan=\"2\"></td><td>g, h and f</td></tr></tbody></table>\n", translate( src ) );
    }

    /** GFM (and Obsidian, and the editor's live preview and preview pane) accept a delimiter cell of a single dash. */
    @Test
    public void testMarkupTableShortDelimiterRow() throws Exception {
        Assertions.assertEquals( "<table><thead><tr><th>a</th><th>b</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>\n",
                                 translate( "| a | b |\n|--|-|\n| 1 | 2 |" ) );
        Assertions.assertEquals( "<table><thead><tr><th>a</th><th align=\"center\">b</th></tr></thead><tbody><tr><td>1</td><td align=\"center\">2</td></tr></tbody></table>\n",
                                 translate( "| a | b |\n| - | :-: |\n| 1 | 2 |" ) );
    }

    /** A delimiter row with no header row above it is not a table (GFM, remark-gfm, the editor): its text stays. */
    @Test
    public void testHeaderlessDelimiterRowIsNotATable() throws Exception {
        Assertions.assertEquals( "<p>|-|</p>\n", translate( "|-|" ) );
        Assertions.assertEquals( "<p>| - |</p>\n", translate( "| - |" ) );
        Assertions.assertEquals( "<ul><li>| -</li></ul>\n", translate( "- | -" ) );
    }

    @Test
    public void testMarkupTableMissingNewlineAfterSeparator() throws Exception {
        // Malformed: separator and first data row on same line
        final String broken = "| Age | VPW % | Withdrawal |\n" +
                              "|-----|-------|------------|| 55 | 3.5% | $35,000 |";
        final String brokenResult = translate( broken );
        // Flexmark does NOT produce a <tbody> — the data row is lost
        Assertions.assertFalse( brokenResult.contains( "<tbody>" ),
            "Malformed table (no newline after separator) should not produce tbody" );

        // Correct version: newline separates header separator from data
        final String fixed = "| Age | VPW % | Withdrawal |\n" +
                             "|-----|-------|------------|\n" +
                             "| 55  | 3.5%  | $35,000    |";
        final String fixedResult = translate( fixed );
        // Proper table renders with tbody containing the data row
        Assertions.assertTrue( fixedResult.contains( "<tbody>" ),
            "Well-formed table should produce tbody" );
        Assertions.assertTrue( fixedResult.contains( "<td>55</td>" ),
            "Well-formed table should contain data cells" );
    }

    @Test
    public void testMarkupExtensionACL() throws Exception {
        final String src = "[{ALLOW view PerryMason}]() This should be visible if the ACL allows you to see it";
        // text is seen because although ACL is added to the page, it is not applied while parsing / rendering
        Assertions.assertEquals( "<p> This should be visible if the ACL allows you to see it</p>\n", translate( src ) );
        // in any case, we also check that the created wikipage has the ACL added
        Assertions.assertEquals( "  user = PerryMason: ((\"com.wikantik.auth.permissions.PagePermission\",\"Wikantik:testpage\",\"view\"))\n",
                                 ( testEngine.getManager( PageManager.class ).getPage( PAGE_NAME ) ).getAcl().toString() );
    }

    @Test
    public void testMarkupExtensionMetadata() throws Exception {
        final String src = "[{SET Perry='Mason'}]() Some text after setting metadata";
        Assertions.assertEquals( "<p> Some text after setting metadata</p>\n", translate( src ) );
        Assertions.assertEquals( "Mason", testEngine.getManager( PageManager.class ).getPage( PAGE_NAME ).getAttribute( "Perry" ) );
    }

    @Test
    public void testMarkupExtensionPlugin() throws Exception {
        final String src = "<strong>string</strong> [{SamplePlugin text=test}]()";
        // With allowHTML=true (the default), raw HTML is preserved
        Assertions.assertEquals( "<p><strong>string</strong> test</p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionPluginWithoutTrailingParens() throws Exception {
        final String src = "[{SamplePlugin text=test}]";
        Assertions.assertEquals( "<p>test</p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionACLWithoutTrailingParens() throws Exception {
        final String src = "[{ALLOW view PerryMason}] This should be visible if the ACL allows you to see it";
        Assertions.assertEquals( "<p> This should be visible if the ACL allows you to see it</p>\n", translate( src ) );
        Assertions.assertEquals( "  user = PerryMason: ((\"com.wikantik.auth.permissions.PagePermission\",\"Wikantik:testpage\",\"view\"))\n",
                                 ( testEngine.getManager( PageManager.class ).getPage( PAGE_NAME ) ).getAcl().toString() );
    }

    @Test
    public void testMarkupExtensionVariableWithoutTrailingParens() throws Exception {
        final String src = "Variable: [{$applicationname}]";
        Assertions.assertEquals( "<p>Variable: Wikantik</p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionMetadataWithoutTrailingParens() throws Exception {
        final String src = "[{SET Perry='Mason'}] Some text after setting metadata";
        Assertions.assertEquals( "<p> Some text after setting metadata</p>\n", translate( src ) );
        Assertions.assertEquals( "Mason", testEngine.getManager( PageManager.class ).getPage( PAGE_NAME ).getAttribute( "Perry" ) );
    }

    @Test
    public void testMarkupExtensionTOCPluginGetsSubstitutedWithMDTocExtension() throws Exception {
        final String src = "[{TableOfContents}]()\n" +
                           "# Header 1\n" +
                           "## Header 2\n" +
                           "## Header 2\n";
        Assertions.assertEquals( "<p></p><div class=\"toc\">\n" +
                                 "<div class=\"collapsebox\">\n" +
                                 "<h4 id=\"section-TOC\">Table of Contents</h4>\n" +
                                 "<ul><li><a href=\"#header-1\">Header 1</a>\n" +
                                 "<ul><li><a href=\"#header-2\">Header 2</a></li><li><a href=\"#header-2-1\">Header 2</a></li></ul>\n" +
                                 "</li></ul>\n" +
                                 "</div>\n" +
                                 "</div>\n\n" +
                                 "<h1 id=\"header-1\">Header 1</h1>\n" +
                                 "<h2 id=\"header-2\">Header 2</h2>\n" +
                                "<h2 id=\"header-2-1\">Header 2</h2>\n", translate( src ) );
    }

    @Test
    public void testTocPluginHonorsTitleParameter() throws Exception {
        final String src = "[{TableOfContents title='My Contents'}]()\n" +
                            "# Header 1\n";
        final String html = translate( src );
        Assertions.assertTrue( html.contains( "<h4 id=\"section-TOC\">My Contents</h4>" ),
                "TOC should use the custom title; got: " + html );
    }

    @Test
    public void testTocPluginHonorsNumberedParameter() throws Exception {
        final String src = "[{TableOfContents numbered=true}]()\n" +
                            "# Header 1\n" +
                            "## Header 2\n";
        final String html = translate( src );
        Assertions.assertTrue( html.contains( "<ol>" ),
                "Numbered TOC should render an ordered list; got: " + html );
    }

    @Test
    public void testTocPluginNumberedParameterAcceptsYes() throws Exception {
        final String src = "[{TableOfContents numbered=yes}]()\n" +
                            "# Header 1\n" +
                            "## Header 2\n";
        final String html = translate( src );
        Assertions.assertTrue( html.contains( "<ol>" ),
                "numbered=yes should also render an ordered list; got: " + html );
    }

    @Test
    public void testTocPluginTitleParameterIsEntityEscaped() throws Exception {
        final String src = "[{TableOfContents title='<b>bold</b>'}]()\n" +
                            "# Header 1\n";
        final String html = translate( src );
        Assertions.assertTrue( html.contains( "<h4 id=\"section-TOC\">&lt;b&gt;bold&lt;/b&gt;</h4>" ),
                "TOC title should be entity-escaped; got: " + html );
    }

    @Test
    public void testMarkupExtensionNonExistentPlugin() throws Exception {
        final String src = "[{PampleSlugin text=test}]()";
        Assertions.assertEquals( "<p><span class=\"error\">Wikantik : testpage - Plugin insertion failed: Could not find plugin PampleSlugin</span></p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionVariable0() throws Exception {
        final String src = "Some text with some pre-set variable: [{$applicationname}]()";
        Assertions.assertEquals( "<p>Some text with some pre-set variable: Wikantik</p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionVariable1() throws Exception {
        final String src = "[{SET Perry='Mason'}]() Some text after setting some metadata: [{$Perry}]()";
        Assertions.assertEquals( "<p> Some text after setting some metadata: Mason</p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionFootnote0() throws Exception {
        final String src = "Footnote[1]()";
        Assertions.assertEquals( "<p>Footnote<a href=\"#ref-testpage-1\" class=\"footnoteref\">[1]</a></p>\n", translate( src ) );
    }

    @Test
    public void testMarkupExtensionFootnoteMD() throws Exception {
        final String src = "text [^footnote] embedded.\n\n" +
        	               "[^footnote]: footnote text\n" +
                           "with continuation";
        Assertions.assertEquals( "<p>text <sup id=\"fnref-1\"><a class=\"footnoteref\" href=\"#fn-1\">1</a></sup> embedded.</p>\n" +
        		                 "<div class=\"footnotes\">\n" +
        		                 "<hr />\n" +
        		                 "<ol><li id=\"fn-1\">\n" +
        		                 "<p>footnote text\n" +
        		                 "with continuation</p>\n" +
        		                 "<a href=\"#fnref-1\" class=\"footnote-backref\">↩</a>\n" +
        		                 "</li></ol>\n" +
        		                 "</div>\n", translate( src ) );
    }

    @Test
    public void testFragmentOnlyLinkPreservesAnchor() throws Exception {
        // [text](#anchor) must render as an in-page fragment link, not be
        // URL-encoded and wrapped as /wiki/%23anchor (a bogus wiki page).
        final String src = "[Overview](#1-overview)";
        Assertions.assertEquals( "<p><a href=\"#1-overview\" class=\"footnote\">Overview</a></p>\n", translate( src ) );
    }

    @Test
    public void testAttachmentLink0() throws Exception {
        final String src = "This should be an [attachment link](Test/TestAtt.txt)";
        newPage( "Test" );

        final Attachment att = Wiki.contents().attachment( testEngine, "Test", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );
        testEngine.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

        Assertions.assertEquals( "<p>This should be an <a href=\"/test/attach/Test/TestAtt.txt\" class=\"attachment\">attachment link</a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testAttachmentLinkBareFilename() throws Exception {
        final String src = "Download the [report](TestAtt.txt)";
        newPage( "Test" );

        final Attachment att = Wiki.contents().attachment( testEngine, "Test", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );
        testEngine.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

        final Page p = Wiki.contents().page( testEngine, "Test" );
        Assertions.assertEquals( "<p>Download the <a href=\"/test/attach/Test/TestAtt.txt\" class=\"attachment\">report</a></p>\n",
                                 translate( p, src ) );
    }

    @Test
    public void testInlineImages() throws Exception {
        final String src = "Link [test](http://www.ecyrd.com/test.png)";

        Assertions.assertEquals( "<p>Link <img class=\"inline\" src=\"http://www.ecyrd.com/test.png\" alt=\"test\" /></p>\n", translate( src ) );
    }

    @Test
    public void testInlineImages2() throws Exception {
        final String src = "Link [test](http://www.ecyrd.com/test.ppm)";

        Assertions.assertEquals( "<p>Link <a href=\"http://www.ecyrd.com/test.ppm\" class=\"external\">test</a></p>\n", translate( src ) );
    }

    @Test
    public void testInlineImages3() throws Exception {
        final String src = "Link [test](http://images.com/testi)";

        Assertions.assertEquals( "<p>Link <img class=\"inline\" src=\"http://images.com/testi\" alt=\"test\" /></p>\n", translate( src ) );
    }

    @Test
    public void testInlineImages4() throws Exception {
        final String src = "Link [test](http://foobar.jpg)";

        Assertions.assertEquals( "<p>Link <img class=\"inline\" src=\"http://foobar.jpg\" alt=\"test\" /></p>\n", translate( src ) );
    }

    // No link text should be just embedded link.
    @Test
    public void testInlineImagesLink2() throws Exception {
        final String src = "Link [http://foobar.jpg]()";

        Assertions.assertEquals( "<p>Link <img class=\"inline\" src=\"http://foobar.jpg\" alt=\"http://foobar.jpg\" /></p>\n", translate( src ) );
    }

    @Test
    public void testInlineImagesLink() throws Exception {
        final String src = "Link [http://link.to/](http://foobar.jpg)";

        Assertions.assertEquals( "<p>Link <a href=\"http://link.to/\" class=\"external\"><img class=\"inline\" src=\"http://foobar.jpg\" alt=\"http://link.to/\" /></a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testInlineImagesLink3() throws Exception {
        final String src = "Link [SandBox](http://foobar.jpg)";

        newPage( "SandBox" );

        Assertions.assertEquals( "<p>Link <a href=\"/test/wiki/SandBox\" class=\"wikipage\"><img class=\"inline\" src=\"http://foobar.jpg\" alt=\"SandBox\" /></a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testHeadersWithSameNameGetIdWithCounter() throws Exception {
        final String src = "### Awesome H3\n" +
                           "### Awesome H3";

        Assertions.assertEquals( "<h3 id=\"awesome-h3\">Awesome H3</h3>\n" +
                                 "<h3 id=\"awesome-h3-1\">Awesome H3</h3>\n",
                                 translate( src ) );
    }

    @Test
    public void testLinkAtStartOfParagraphPreservesOrder() throws Exception {
        newPage( "TargetPage" );
        final String src = "[TargetPage]() followed by text";

        Assertions.assertEquals(
                "<p><a href=\"/test/wiki/TargetPage\" class=\"wikipage\">TargetPage</a> followed by text</p>\n",
                translate( src ) );
    }

    @Test
    public void testLinkAtStartOfListItemPreservesSpacing() throws Exception {
        newPage( "MyPage" );
        final String src = "- [MyPage]() — some description";

        final String result = translate( src );

        // The link must appear before the description text, with a space separating them
        Assertions.assertTrue( result.contains( ">MyPage</a> —" ),
                "Link should precede description with a space, but got: " + result );
    }

    @Test
    public void testFrontmatterSuppressedFromOutput() throws Exception {
        final String src = "---\ntype: concept\ntags: [ai, wiki]\n---\nThis is the body.";

        final String result = translate( src );

        Assertions.assertEquals( "<p>This is the body.</p>\n", result );
    }

    @Test
    public void testFrontmatterMetadataSetAsPageAttributes() throws Exception {
        final String src = "---\ntype: concept\ntags: [ai, wiki]\n---\nBody text.";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        translate( p, src );

        Assertions.assertEquals( "concept", p.getAttribute( "type" ) );
        Assertions.assertEquals( List.of( "ai", "wiki" ), p.getAttribute( "tags" ) );
    }

    @Test
    public void testNoFrontmatterRendersNormally() throws Exception {
        final String src = "Just a regular paragraph.";

        Assertions.assertEquals( "<p>Just a regular paragraph.</p>\n", translate( src ) );
    }

    @Test
    public void testHorizontalRuleInBodyNotStripped() throws Exception {
        final String src = "Some text\n\n---\n\nMore text.";

        final String result = translate( src );

        Assertions.assertTrue( result.contains( "<hr" ) );
        Assertions.assertTrue( result.contains( "Some text" ) );
        Assertions.assertTrue( result.contains( "More text." ) );
    }

    @Test
    public void testEmptyFrontmatter() throws Exception {
        final String src = "---\n---\nBody after empty frontmatter.";

        Assertions.assertEquals( "<p>Body after empty frontmatter.</p>\n", translate( src ) );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void testFrontmatterRichTypesPreserved() throws Exception {
        final String src = "---\ntags: [java, mcp]\nauthor:\n  name: Claude\n  role: AI\n---\nContent.";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        translate( p, src );

        Assertions.assertEquals( List.of( "java", "mcp" ), p.getAttribute( "tags" ) );
        final Map< String, Object > author = ( Map< String, Object > ) p.getAttribute( "author" );
        Assertions.assertEquals( "Claude", author.get( "name" ) );
        Assertions.assertEquals( "AI", author.get( "role" ) );
    }

    @Test
    public void testPluginOutputWithPageLinksRendersAsHtmlLinks() throws Exception {
        // Create pages that reference a non-existent page — this sets up conditions
        // for UndefinedPagesPlugin to produce a list of page links
        newPage( "LinkSource" );
        testEngine.saveText( "LinkSource", "[MissingTarget]()" );

        // Invoke the plugin that generates a list of page name links
        final String src = "[{UndefinedPagesPlugin}]()";
        final String result = translate( src );

        // The plugin output should contain proper HTML links, not raw wiki-syntax [text|url]
        Assertions.assertFalse( result.contains( "[MissingTarget|MissingTarget]" ),
                "Plugin output should not contain wiki-syntax links, got: " + result );
        Assertions.assertTrue( result.contains( "<a " ),
                "Plugin output should contain HTML anchor tags, got: " + result );
    }

    @Test
    public void testUnusedPagesPluginRendersAsHtmlLinks() throws Exception {
        // Create a page with no inbound links (orphan) — UnusedPagesPlugin should list it
        newPage( "OrphanPage" );

        final String src = "[{UnusedPagesPlugin}]()";
        final String result = translate( src );

        // Should render as HTML links, not wiki syntax
        Assertions.assertFalse( result.contains( "[OrphanPage|" ),
                "Plugin output should not contain wiki-syntax links, got: " + result );
    }

    @Test
    public void testVariableAsOnlyContentInParagraph() throws Exception {
        // BUG: When a variable is the sole content of a paragraph (no siblings),
        // addContent() failed to unlink the original node, producing both the
        // unresolved link AND the resolved value in the output.
        final String src = "[{$applicationname}]";
        final String result = translate( src );
        Assertions.assertEquals( "<p>Wikantik</p>\n", result,
                "Variable as sole paragraph content should render only the value, not a broken link" );
    }

    @Test
    public void testVariableOnItsOwnLine() throws Exception {
        // Same bug as above — standalone variable on a line between other content
        final String src = "Before\n\n[{$applicationname}]\n\nAfter";
        final String result = translate( src );
        Assertions.assertTrue( result.contains( "<p>Wikantik</p>" ),
                "Variable on its own line should render as its value, got: " + result );
        Assertions.assertFalse( result.contains( "createpage" ),
                "Variable should not render as a create-page link, got: " + result );
    }

    @Test
    public void testInlineMathRendersWithMathInlineClass() throws Exception {
        final String src = "The formula $x^2 + y^2 = r^2$ is well known.";
        final String result = translate( src );
        Assertions.assertTrue( result.contains( "class=\"math-inline\"" ),
                "Inline math should render with class math-inline, got: " + result );
        // + and = may be HTML-encoded by the sanitizer — browsers decode entities, so this is correct
        Assertions.assertTrue( result.contains( "x^2" ) && result.contains( "r^2" ),
                "Inline math should preserve LaTeX content, got: " + result );
    }

    @Test
    public void testDisplayMathRendersWithMathDisplayClass() throws Exception {
        final String src = "Text before\n\n$$\n\\int_0^1 f(x)\\,dx\n$$\n\nText after";
        final String result = translate( src );
        Assertions.assertTrue( result.contains( "math-display" ),
                "Display math should render with class math-display, got: " + result );
        Assertions.assertTrue( result.contains( "\\int_0^1 f(x)\\,dx" ),
                "Display math should preserve LaTeX content, got: " + result );
    }

    @Test
    public void testFencedMathBlockRendersWithMathDisplayClass() throws Exception {
        final String src = "Text before\n\n```math\n\\sum_{i=1}^{n} i\n```\n\nText after";
        final String result = translate( src );
        Assertions.assertTrue( result.contains( "math-display" ),
                "Fenced math block should render with class math-display, got: " + result );
        // = may be HTML-encoded by the sanitizer — browsers decode entities, so this is correct
        Assertions.assertTrue( result.contains( "\\sum_" ) && result.contains( "^{n} i" ),
                "Fenced math block should preserve LaTeX content, got: " + result );
    }

    @Test
    public void testEscapedDollarSignNotParsedAsMath() throws Exception {
        final String src = "The price is \\$100.";
        final String result = translate( src );
        Assertions.assertFalse( result.contains( "math-inline" ),
                "Escaped dollar sign should not be parsed as math, got: " + result );
    }

    @Test
    public void testMathInListItem() throws Exception {
        final String src = "- Item with $E = mc^2$ formula";
        final String result = translate( src );
        Assertions.assertTrue( result.contains( "math-inline" ),
                "Math inside list item should render, got: " + result );
        Assertions.assertTrue( result.contains( "<li>" ),
                "List structure should be preserved, got: " + result );
    }

    @Test
    public void testMathInBlockquote() throws Exception {
        final String src = "> A quote with $\\alpha + \\beta$ math";
        final String result = translate( src );
        Assertions.assertTrue( result.contains( "math-inline" ),
                "Math inside blockquote should render, got: " + result );
        Assertions.assertTrue( result.contains( "<blockquote>" ),
                "Blockquote structure should be preserved, got: " + result );
    }

    @Test
    public void testStrikethroughDoubleTilde() throws Exception {
        Assertions.assertEquals( "<p>x <del>a</del> y</p>\n", translate( "x ~~a~~ y" ) );
    }

    @Test
    public void testSingleTildeStaysLiteral() throws Exception {
        Assertions.assertEquals( "<p>~5 min to ~10 min and ~a~</p>\n", translate( "~5 min to ~10 min and ~a~" ) );
    }

    @Test
    public void testWhitespaceFlankedAndLoneDoubleTildeStayLiteral() throws Exception {
        Assertions.assertEquals( "<p>~~ a ~~</p>\n", translate( "~~ a ~~" ) );
        final String table = translate( "| a | b |\n|---|---|\n| ~~ | fuzzy |\n" );
        Assertions.assertTrue( table.contains( "~~" ) && !table.contains( "<del>" ), table );
    }

    @Test
    public void testDoubleTildeInCodeStaysLiteral() throws Exception {
        Assertions.assertEquals( "<p>use <code>~~a~~</code> here</p>\n", translate( "use `~~a~~` here" ) );
        final String fence = translate( "```\n~~a~~\n```\n" );
        Assertions.assertTrue( fence.contains( "~~a~~" ) && !fence.contains( "<del>" ), fence );
    }

    @Test
    public void testTaskListItems() throws Exception {
        final String chk = "<input type=\"checkbox\" class=\"task-list-item-checkbox\" disabled=\"\" />";
        final String done = "<input type=\"checkbox\" class=\"task-list-item-checkbox\" checked=\"\" disabled=\"\" />";
        Assertions.assertEquals( "<ul><li class=\"task-list-item\">" + chk + " todo</li>"
                + "<li class=\"task-list-item\">" + done + " done</li></ul>\n",
                translate( "- [ ] todo\n- [x] done\n" ) );
        Assertions.assertTrue( translate( "- [X] big" ).contains( "checked=\"\"" ) );
        final String ol = translate( "1. [ ] first\n2. [x] second\n" );
        Assertions.assertTrue( ol.startsWith( "<ol>" ) && ol.contains( "<li class=\"task-list-item\">" + chk + " first</li>" ), ol );
    }

    @Test
    public void testTaskItemWithNativeWikiLink() throws Exception {
        newPage( "TaskTarget" );
        final String result = translate( "- [ ] see [[TaskTarget]]\n" );
        Assertions.assertTrue( result.contains( "task-list-item-checkbox" ) && result.contains( "class=\"wikipage\"" ), result );
    }

    @Test
    public void testDollarEscapeAndCurrencyRendering() throws Exception {
        final String esc = translate( "\\$5 and $x$" );
        Assertions.assertTrue( esc.contains( "$5" ) && esc.contains( "<span class=\"math-inline\">x</span>" ), esc );
        Assertions.assertFalse( translate( "$5 and $10" ).contains( "math-inline" ) );
        final String co = translate( "$c_o = \\$1.00$" );
        Assertions.assertEquals( 1, co.split( "math-inline", -1 ).length - 1, co );
        Assertions.assertTrue( co.contains( "c_o" ) && co.contains( "\\$1.00</span>" ), co );
    }

    @AfterEach
    public void tearDown() {
        created.clear();
        testEngine.stop();
    }

    String translate( final String src ) throws Exception {
        return translate( Wiki.contents().page( testEngine, PAGE_NAME ), src );
    }

    String translate( final Engine e, final String src ) throws Exception {
        return translate( e, Wiki.contents().page( testEngine, PAGE_NAME ), src );
    }

    String translate( final Page p, final String src ) throws Exception {
        return translate( testEngine, p, src );
    }

    String translate( final Engine e, final Page p, final String src ) throws Exception {
        final Context context = Wiki.context().create( e, HttpMockFactory.createHttpRequest(), p );
        final MarkdownParser tr = new MarkdownParser( context, new BufferedReader( new StringReader( src ) ) );
        final MarkdownRenderer conv = new MarkdownRenderer( context, tr.parse() );
        newPage( p.getName(), src );

        return BlockOrdinals.strip( conv.getString() );
    }

    void newPage( final String name ) throws WikiException {
        newPage( name, "<test>" );
    }

    void newPage( final String name, final String text ) throws WikiException {
        testEngine.saveText( name, text );
        created.add( name );
    }

}
