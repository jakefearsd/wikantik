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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;
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
                                              TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

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

        Assertions.assertEquals( "<p>This should be a <a href=\"/test/Edit.jsp?page=self%3C-%3Elink\" title=\"Create &quot;self&lt;-&gt;link&quot;\" class=\"createpage\">self&lt;-&gt;link</a></p>\n",
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
        final String expected = "<p>This should be a <a href=\"/test/attach/Link%20%3Cstrong%3Ebold%3C/strong%3E\" class=\"attachment\">Link <strong>bold</strong></a><a href=\"/test/PageInfo.jsp?page=Link%20%3Cstrong%3Ebold%3C/strong%3E\" class=\"infolink\"><img src=\"/test/images/attachment_small.png\" border=\"0\" alt=\"(info)\" /></a></p>\n";
        Assertions.assertEquals( expected, translate( "This should be a [Link <strong>bold</strong>]()" ) );
    }

    @Test
    public void testMarkupExtensionHtmlAllowsMDInsideLinks() throws Exception {
        newPage( "Hyperlink" );
        final String expected = "<p>This should be a <a href=\"/test/Edit.jsp?page=Link%20**bold**\" title=\"Create &quot;Link **bold**&quot;\" class=\"createpage\">Link <strong>bold</strong></a></p>\n";
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

        Assertions.assertEquals( "<p>This should be an <a href=\"http://wiki.wikantik.com/Wiki.jsp?page=About\" class=\"interwiki\">interwiki link</a></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testMarkupExtensionWrongInterWikiLink() throws Exception {
        final String src = "This should be an [interwiki link](JSPWiko:About)";

        Assertions.assertEquals( "<p>This should be an <span class=\"error\">No InterWiki reference defined in properties for Wiki called \"JSPWiko\"!</span></p>\n",
                                 translate( src ) );
    }

    @Test
    public void testMarkupLinkWithCustomAttributes() throws Exception {
        final String src = "This should be a [link with custom attributes](http://google.com){target=blank}";

        Assertions.assertEquals( "<p>This should be a <a href=\"http://google.com\" class=\"external\" target=\"blank\">link with custom attributes</a></p>\n", translate( src ) );
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

        Assertions.assertEquals( "<table>\n<thead>\n<tr><th align=\"left\">a</th><th align=\"center\">b</th><th>c</th></tr>\n</thead>\n" +
                                 "<tbody>\n<tr><td align=\"left\">d</td><td align=\"center\">e</td><td>f</td></tr>\n" +
                                 "<tr><td align=\"left\" colspan=\"2\"></td><td>g, h and f</td></tr>\n</tbody>\n</table>\n", translate( src ) );
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
        Assertions.assertEquals( "<p><div class=\"toc\">\n" +
                                 "<div class=\"collapsebox\">\n" +
                                 "<h4 id=\"section-TOC\">Table of Contents</h4>\n" +
                                 "<ul>\n" +
                                 "<li><a href=\"#header-1\">Header 1</a>\n" +
                                 "<ul>\n" +
                                 "<li><a href=\"#header-2\">Header 2</a></li>\n" +
                                 "<li><a href=\"#header-2-1\">Header 2</a></li>\n" +
                                 "</ul>\n" +
                                 "</li>\n" +
                                 "</ul>\n" +
                                 "</div>\n" +
                                 "</div>\n" +
                                 "</p>\n" +
                                 "<h1 id=\"header-1\">Header 1</h1>\n" +
                                 "<h2 id=\"header-2\">Header 2</h2>\n" +
                                "<h2 id=\"header-2-1\">Header 2</h2>\n", translate( src ) );
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
        		                 "<ol>\n" +
        		                 "<li id=\"fn-1\">\n" +
        		                 "<p>footnote text\n" +
        		                 "with continuation</p>\n" +
        		                 "<a href=\"#fnref-1\" class=\"footnote-backref\">&#8617;</a>\n" +
        		                 "</li>\n" +
        		                 "</ol>\n" +
        		                 "</div>\n", translate( src ) );
    }

    @Test
    public void testAttachmentLink0() throws Exception {
        final String src = "This should be an [attachment link](Test/TestAtt.txt)";
        newPage( "Test" );

        final Attachment att = Wiki.contents().attachment( testEngine, "Test", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );
        testEngine.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

        Assertions.assertEquals( "<p>This should be an <a href=\"/test/attach/Test/TestAtt.txt\" class=\"attachment\">attachment link</a>" +
                                 "<a href=\"/test/PageInfo.jsp?page=Test/TestAtt.txt\" class=\"infolink\">" +
                                   "<img src=\"/test/images/attachment_small.png\" border=\"0\" alt=\"(info)\" />" +
                                 "</a></p>\n",
                                 translate( src ) );
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

        return conv.getString();
    }

    void newPage( final String name ) throws WikiException {
        newPage( name, "<test>" );
    }

    void newPage( final String name, final String text ) throws WikiException {
        testEngine.saveText( name, text );
        created.add( name );
    }

}
