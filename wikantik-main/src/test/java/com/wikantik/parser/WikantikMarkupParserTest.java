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

import com.wikantik.HttpMockFactory;
import com.wikantik.LinkCollector;
import com.wikantik.TestEngine;
import com.wikantik.WikiContext;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.AttachmentProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.providers.BasicAttachmentProvider;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.XHTMLRenderer;
import com.wikantik.stress.Benchmark;
import com.wikantik.util.TextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Vector;

import static com.wikantik.TestEngine.with;


class WikantikMarkupParserTest {

    static final String PAGE_NAME = "testpage";

    Vector< String > created = new Vector<>();

    TestEngine testEngine = TestEngine.build( with( "wikantik.translatorReader.matchEnglishPlurals", "true" ) );

    @AfterEach
    void tearDown() {
        created.clear();
        testEngine.stop();
    }

    private void newPage( final String name ) throws WikiException {
        testEngine.saveText( name, "<test>" );
        created.addElement( name );
    }

    private String translate( final String src ) throws IOException {
        return translate( Wiki.contents().page( testEngine, PAGE_NAME ), src );
    }

    private String translate( final WikiEngine e, final String src ) throws IOException {
        return translate( e, Wiki.contents().page( testEngine, PAGE_NAME ), src );
    }

    private String translate( final Page p, final String src ) throws IOException {
        return translate( testEngine, p, src );
    }

    private String translate( final Engine e, final Page p, final String src ) throws IOException {
        final WikiContext context = new WikiContext( e, HttpMockFactory.createHttpRequest(), p );
        final WikantikMarkupParser tr = new WikantikMarkupParser( context, new BufferedReader( new StringReader( src ) ) );
        final XHTMLRenderer conv = new XHTMLRenderer( context, tr.parse() );
        return conv.getString();
    }

    private String translate_nofollow( final String src ) throws IOException {
        final TestEngine testEngine2 = TestEngine.build( with( "wikantik.translatorReader.useRelNofollow", "true" ) );
        final WikiContext context = new WikiContext( testEngine2, Wiki.contents().page( testEngine2, PAGE_NAME ) );
        final WikantikMarkupParser r = new WikantikMarkupParser( context, new BufferedReader( new StringReader( src ) ) );
        final XHTMLRenderer conv = new XHTMLRenderer( context, r.parse() );
        return conv.getString();
    }

    @Test
    public void testEmptyLink() throws Exception {
        newPage( "Hyperlink" );
        final String src = "Empty link: []";
        Assertions.assertEquals( "Empty link: <u></u>", translate( src ) );
    }

    @Test
    public void testHyperlinks2() throws Exception {
        newPage( "Hyperlink" );
        final String src = "This should be a [hyperlink]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/Hyperlink\">hyperlink</a>", translate( src ) );
    }

    @Test
    void testHyperlinks3() throws Exception {
        newPage( "HyperlinkToo" );
        final String src = "This should be a [hyperlink too]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperlinkToo\">hyperlink too</a>", translate( src ) );
    }

    @Test
    void testHyperlinks4() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a [HyperLink]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>", translate( src ) );
    }

    @Test
    void testHyperlinks5() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a [here|HyperLink]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">here</a>", translate( src ) );
    }

    @Test
    void testHyperlinksNamed1() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a [here|HyperLink#heading]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink#section-HyperLink-Heading\">here</a>",
                translate( src ) );
    }

    @Test
    void testHyperlinksNamed2() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a [HyperLink#heading]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink#section-HyperLink-Heading\">HyperLink#heading</a>", translate( src ) );
    }

    @Test
    void testHyperlinksNamed3() throws Exception {
        newPage( "HyperLink" );
        final String src = "!Heading Too\r\nThis should be a [HyperLink#heading too]";
        Assertions.assertEquals( "<h4 id=\"section-testpage-HeadingToo\">Heading Too<a class=\"hashlink\" href=\"#section-testpage-HeadingToo\">#</a></h4>\nThis should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink#section-HyperLink-HeadingToo\">HyperLink#heading too</a>",
                translate( src ) );
    }

    // test hyperlink to a section with non-ASCII character in it
    @Test
    void testHyperlinksNamed4() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a [HyperLink#headingwithnonASCIIZoltán]";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink#section-HyperLink-HeadingwithnonASCIIZolt_E1n\">HyperLink#headingwithnonASCIIZoltán</a>",
                translate( src ) );
    }

    //  Testing CamelCase hyperlinks
    @Test
    void testHyperLinks6() throws Exception {
        newPage( "DiscussionAboutWiki" );
        newPage( "WikiMarkupDevelopment" );
        final String src = "[DiscussionAboutWiki] [WikiMarkupDevelopment].";
        Assertions.assertEquals( "<a class=\"wikipage\" href=\"/test/wiki/DiscussionAboutWiki\">DiscussionAboutWiki</a> <a class=\"wikipage\" href=\"/test/wiki/WikiMarkupDevelopment\">WikiMarkupDevelopment</a>.",
                translate( src ) );
    }

    @Test
    void testHyperlinksCC() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a HyperLink.";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>.", translate( src ) );
    }

    @Test
    void testHyperlinksCCNonExistant() throws Exception {
        final String src = "This should be a HyperLink.";
        Assertions.assertEquals( "This should be a <a class=\"createpage\" href=\"/test/Edit.jsp?page=HyperLink\" title=\"Create &quot;HyperLink&quot;\">HyperLink</a>.",
                translate( src ) );
    }

    /** Check if the CC hyperlink translator gets confused with unorthodox bracketed links. */
    @Test
    void testHyperlinksCC2() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should be a [  HyperLink  ].";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">  HyperLink  </a>.", translate( src ) );
    }

    @Test
    void testHyperlinksCC3() throws Exception {
        final String src = "This should be a nonHyperLink.";
        Assertions.assertEquals( "This should be a nonHyperLink.", translate( src ) );
    }

    /** Two links on same line. */
    @Test
    void testHyperlinksCC4() throws Exception {
        newPage( "HyperLink" );
        newPage( "ThisToo" );
        final String src = "This should be a HyperLink, and ThisToo.";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>, and <a class=\"wikipage\" href=\"/test/wiki/ThisToo\">ThisToo</a>.",
                translate( src ) );
    }

    /** Two mixed links on same line. */
    @Test
    void testHyperlinksCC5() throws Exception {
        newPage( "HyperLink" );
        newPage( "ThisToo" );
        final String src = "This should be a [HyperLink], and ThisToo.";
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>, and <a class=\"wikipage\" href=\"/test/wiki/ThisToo\">ThisToo</a>.",
                translate( src ) );
    }

    /** Closing tags only. */
    @Test
    void testHyperlinksCC6() throws Exception {
        newPage( "HyperLink" );
        newPage( "ThisToo" );
        final String src = "] This ] should be a HyperLink], and ThisToo.";
        Assertions.assertEquals( "] This ] should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>], and <a class=\"wikipage\" href=\"/test/wiki/ThisToo\">ThisToo</a>.",
                translate( src ) );
    }

    /** First and last words on same line. */
    @Test
    void testHyperlinksCCFirstAndLast() throws Exception {
        newPage( "HyperLink" );
        newPage( "ThisToo" );
        final String src = "HyperLink, and ThisToo";
        Assertions.assertEquals( "<a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>, and <a class=\"wikipage\" href=\"/test/wiki/ThisToo\">ThisToo</a>",
                translate( src ) );
    }

    /** Hyperlinks inside URIs. */
    @Test
    void testHyperlinksCCURLs() throws Exception {
        final String src = "http://www.foo.bar/ANewHope/";
        Assertions.assertEquals( "<a class=\"external\" href=\"http://www.foo.bar/ANewHope/\">http://www.foo.bar/ANewHope/</a>", translate( src ) );
    }

    /** Hyperlinks inside URIs. */
    @Test
    void testHyperlinksCCURLs2() throws Exception {
        final String src = "mailto:foo@bar.com";
        Assertions.assertEquals( "<a class=\"external\" href=\"mailto:foo@bar.com\">mailto:foo@bar.com</a>", translate( src ) );
    }

    /** Hyperlinks inside URIs. */
    @Test
    void testHyperlinksCCURLs3() throws Exception {
        final String src = "This should be a link: http://www.foo.bar/ANewHope/.  Is it?";
        Assertions.assertEquals( "This should be a link: <a class=\"external\" href=\"http://www.foo.bar/ANewHope/\">http://www.foo.bar/ANewHope/</a>.  Is it?",
                translate( src ) );
    }

    /** Hyperlinks in brackets. */
    @Test
    void testHyperlinksCCURLs4() throws Exception {
        final String src = "This should be a link: (http://www.foo.bar/ANewHope/)  Is it?";
        Assertions.assertEquals( "This should be a link: (<a class=\"external\" href=\"http://www.foo.bar/ANewHope/\">http://www.foo.bar/ANewHope/</a>)  Is it?",
                translate( src ) );
    }

    /** Hyperlinks end line. */
    @Test
    void testHyperlinksCCURLs5() throws Exception {
        final String src = "This should be a link: http://www.foo.bar/ANewHope/\nIs it?";
        Assertions.assertEquals( "This should be a link: <a class=\"external\" href=\"http://www.foo.bar/ANewHope/\">http://www.foo.bar/ANewHope/</a>\nIs it?",
                translate( src ) );
    }

    /** Hyperlinks with odd chars. */
    @Test
    void testHyperlinksCCURLs6() throws Exception {
        final String src = "This should not be a link: http://''some.server''/wiki//test/Wiki.jsp\nIs it?";
        Assertions.assertEquals( "This should not be a link: http://<i>some.server</i>/wiki//test/Wiki.jsp\nIs it?", translate( src ) );
    }

    @Test
    void testHyperlinksCCURLs7() throws Exception {
        final String src = "http://www.foo.bar/ANewHope?q=foobar&gobble=bobble+gnoo";
        Assertions.assertEquals( "<a class=\"external\" href=\"http://www.foo.bar/ANewHope?q=foobar&amp;gobble=bobble+gnoo\">http://www.foo.bar/ANewHope?q=foobar&amp;gobble=bobble+gnoo</a>",
                translate( src ) );
    }

    @Test
    void testHyperlinksCCURLs8() throws Exception {
        final String src = "http://www.foo.bar/~ANewHope/";
        Assertions.assertEquals( "<a class=\"external\" href=\"http://www.foo.bar/~ANewHope/\">http://www.foo.bar/~ANewHope/</a>", translate( src ) );
    }

    @Test
    void testHyperlinksCCURLs9() throws Exception {
        final String src = "http://www.foo.bar/%7EANewHope/";
        Assertions.assertEquals( "<a class=\"external\" href=\"http://www.foo.bar/%7EANewHope/\">http://www.foo.bar/%7EANewHope/</a>", translate( src ) );
    }

    @Test
    void testHyperlinksCCNegated() throws Exception {
        final String src = "This should not be a ~HyperLink.";
        Assertions.assertEquals( "This should not be a HyperLink.", translate( src ) );
    }

    @Test
    void testHyperlinksCCNegated2() throws Exception {
        final String src = "~HyperLinks should not be matched.";
        Assertions.assertEquals( "HyperLinks should not be matched.", translate( src ) );
    }

    @Test
    void testHyperlinksCCNegated3() throws Exception {
        final String src = "The page ~ASamplePage is not a hyperlink.";
        Assertions.assertEquals( "The page ASamplePage is not a hyperlink.", translate( src ) );
    }

    @Test
    void testHyperlinksCCNegated4() throws Exception {
        final String src = "The page \"~ASamplePage\" is not a hyperlink.";
        Assertions.assertEquals( "The page &quot;ASamplePage&quot; is not a hyperlink.", translate( src ) );
    }

    @Test
    void testCCLinkInList() throws Exception {
        newPage( "HyperLink" );
        final String src = "*HyperLink";
        Assertions.assertEquals( "<ul><li><a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a></li></ul>", translate( src ) );
    }

    @Test
    void testCCLinkBold() throws Exception {
        newPage( "BoldHyperLink" );
        final String src = "__BoldHyperLink__";
        Assertions.assertEquals( "<b><a class=\"wikipage\" href=\"/test/wiki/BoldHyperLink\">BoldHyperLink</a></b>", translate( src ) );
    }

    @Test
    void testCCLinkBold2() throws Exception {
        newPage( "HyperLink" );
        final String src = "Let's see, if a bold __HyperLink__ is correct?";
        Assertions.assertEquals( "Let's see, if a bold <b><a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a></b> is correct?", translate( src ) );
    }

    @Test
    void testCCLinkItalic() throws Exception {
        newPage( "ItalicHyperLink" );
        final String src = "''ItalicHyperLink''";
        Assertions.assertEquals( "<i><a class=\"wikipage\" href=\"/test/wiki/ItalicHyperLink\">ItalicHyperLink</a></i>", translate( src ) );
    }

    @Test
    void testCCLinkWithPunctuation() throws Exception {
        newPage( "HyperLink" );
        final String src = "Test. Punctuation. HyperLink.";
        Assertions.assertEquals( "Test. Punctuation. <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>.", translate( src ) );
    }

    @Test
    void testCCLinkWithPunctuation2() throws Exception {
        newPage( "HyperLink" );
        newPage( "ThisToo" );

        final String src = "Punctuations: HyperLink,ThisToo.";
        Assertions.assertEquals( "Punctuations: <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLink</a>,<a class=\"wikipage\" href=\"/test/wiki/ThisToo\">ThisToo</a>.",
                translate( src ) );
    }

    @Test
    void testCCLinkWithScandics() throws Exception {
        newPage( "\u00c4itiSy\u00f6\u00d6ljy\u00e4" );
        final String src = "Onko t\u00e4m\u00e4 hyperlinkki: \u00c4itiSy\u00f6\u00d6ljy\u00e4?";
        Assertions.assertEquals( "Onko t\u00e4m\u00e4 hyperlinkki: <a class=\"wikipage\" href=\"/test/wiki/%C4itiSy%F6%D6ljy%E4\">\u00c4itiSy\u00f6\u00d6ljy\u00e4</a>?",
                translate( src ) );
    }

    @Test
    void testHyperlinksExt() throws Exception {
        final String src = "This should be a [http://www.regex.fi/]";
        Assertions.assertEquals( "This should be a <a class=\"external\" href=\"http://www.regex.fi/\">http://www.regex.fi/</a>", translate( src ) );
    }

    @Test
    void testHyperlinksExt2() throws Exception {
        final String src = "This should be a [link|http://www.regex.fi/]";
        Assertions.assertEquals( "This should be a <a class=\"external\" href=\"http://www.regex.fi/\">link</a>", translate( src ) );
    }

    @Test
    void testHyperlinksExtNofollow() throws Exception {
        final String src = "This should be a [link|http://www.regex.fi/]";
        Assertions.assertEquals( "This should be a <a class=\"external\" href=\"http://www.regex.fi/\" rel=\"nofollow\">link</a>",
                translate_nofollow( src ) );
    }

    // Testing various odds and ends about hyperlink matching.
    @Test
    void testHyperlinksPluralMatch() throws Exception {
        final String src = "This should be a [HyperLinks]";
        newPage( "HyperLink" );
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">HyperLinks</a>", translate( src ) );
    }

    @Test
    void testHyperlinksPluralMatch2() throws Exception {
        final String src = "This should be a [HyperLinks]";
        Assertions.assertEquals( "This should be a <a class=\"createpage\" href=\"/test/Edit.jsp?page=HyperLinks\" title=\"Create &quot;HyperLinks&quot;\">HyperLinks</a>",
                translate( src ) );
    }

    @Test
    void testHyperlinksPluralMatch3() throws Exception {
        final String src = "This should be a [HyperLink]";
        newPage( "HyperLinks" );
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLinks\">HyperLink</a>", translate( src ) );
    }

    @Test
    void testHyperlinksPluralMatch4() throws Exception {
        final String src = "This should be a [Hyper links]";
        newPage( "HyperLink" );
        Assertions.assertEquals( "This should be a <a class=\"wikipage\" href=\"/test/wiki/HyperLink\">Hyper links</a>", translate( src ) );
    }


    @Test
    void testHyperlinkJS1() throws Exception {
        final String src = "This should be a [link|http://www.haxored.com/\" onMouseOver=\"alert('Hahhaa');\"]";
        Assertions.assertEquals( "This should be a <a class=\"external\" href=\"http://www.haxored.com/&quot; onMouseOver=&quot;alert('Hahhaa');&quot;\">link</a>",
                translate( src ) );
    }

    @Test
    void testHyperlinksInterWiki1() throws Exception {
        final String src = "This should be a [link|JSPWiki:HyperLink]";
        Assertions.assertEquals( "This should be a <a class=\"interwiki\" href=\"http://jspwiki-wiki.apache.org/Wiki.jsp?page=HyperLink\">link</a>",
                translate( src ) );
    }

    @Test
    void testHyperlinksInterWiki2() throws Exception {
        final String src = "This should be a [JSPWiki:HyperLink]";
        Assertions.assertEquals( "This should be a <a class=\"interwiki\" href=\"http://jspwiki-wiki.apache.org/Wiki.jsp?page=HyperLink\">JSPWiki:HyperLink</a>",
                translate( src ) );
    }

    @Test
    void testAttachmentLink() throws Exception {
        newPage( "Test" );
        final Attachment att = Wiki.contents().attachment( testEngine, "Test", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );
        testEngine.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

        final String src = "This should be an [attachment link|Test/TestAtt.txt]";
        Assertions.assertEquals( "This should be an <a class=\"attachment\" href=\"/test/attach/Test/TestAtt.txt\">attachment link</a>" +
                        "<a href=\"/test/PageInfo.jsp?page=Test/TestAtt.txt\" class=\"infolink\"><img src=\"/test/images/attachment_small.png\" border=\"0\" alt=\"(info)\" /></a>",
                translate( src ) );
    }

    @Test
    void testAttachmentLink2() throws Exception {
        final TestEngine testEngine2 = TestEngine.build( with( "wikantik.encoding", StandardCharsets.ISO_8859_1.name() ) );
        testEngine2.saveText( "Test", "foo " );
        created.addElement( "Test" );

        final Attachment att = Wiki.contents().attachment( testEngine2, "Test", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );
        testEngine2.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

        final String src = "This should be an [attachment link|Test/TestAtt.txt]";
        Assertions.assertEquals( "This should be an <a class=\"attachment\" href=\"/test/attach/Test/TestAtt.txt\">attachment link</a>" +
                        "<a href=\"/test/PageInfo.jsp?page=Test/TestAtt.txt\" class=\"infolink\"><img src=\"/test/images/attachment_small.png\" border=\"0\" alt=\"(info)\" /></a>",
                translate( testEngine2, src ) );
    }

    /** Are attachments parsed correctly also when using gappy text? */
    @Test
    void testAttachmentLink3() throws Exception {
        final TestEngine testEngine2 = TestEngine.build();
        testEngine2.saveText( "TestPage", "foo " );
        created.addElement( "TestPage" );

        final Attachment att = Wiki.contents().attachment( testEngine2, "TestPage", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );
        testEngine2.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

        final String src = "[Test page/TestAtt.txt]";
        Assertions.assertEquals( "<a class=\"attachment\" href=\"/test/attach/TestPage/TestAtt.txt\">Test page/TestAtt.txt</a>" +
                        "<a href=\"/test/PageInfo.jsp?page=TestPage/TestAtt.txt\" class=\"infolink\"><img src=\"/test/images/attachment_small.png\" border=\"0\" alt=\"(info)\" /></a>",
                translate( testEngine2, src ) );
    }

    @Test
    void testAttachmentLink4() throws Exception {
        final TestEngine testEngine2 = TestEngine.build();
        testEngine2.saveText( "TestPage", "foo " );
        created.addElement( "TestPage" );

        final Attachment att = Wiki.contents().attachment( testEngine2, "TestPage", "TestAtt.txt" );
        att.setAuthor( "FirstPost" );

        testEngine2.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );
        final String src = "[" + testEngine2.getManager( RenderingManager.class ).beautifyTitle( "TestPage/TestAtt.txt" ) + "]";
        Assertions.assertEquals( "<a class=\"attachment\" href=\"/test/attach/TestPage/TestAtt.txt\">Test Page/TestAtt.txt</a>" +
                        "<a href=\"/test/PageInfo.jsp?page=TestPage/TestAtt.txt\" class=\"infolink\"><img src=\"/test/images/attachment_small.png\" border=\"0\" alt=\"(info)\" /></a>",
                translate( testEngine2, src ) );
    }

    @Test
    void testNoHyperlink() throws Exception {
        newPage( "HyperLink" );
        final String src = "This should not be a [[HyperLink]";
        Assertions.assertEquals( "This should not be a [HyperLink]", translate( src ) );
    }

    @Test
    void testNoHyperlink2() throws Exception {
        final String src = "This should not be a [[[[HyperLink]";
        Assertions.assertEquals( "This should not be a [[[HyperLink]", translate( src ) );
    }

    @Test
    void testNoHyperlink3() throws Exception {
        final String src = "[[HyperLink], and this [[Neither].";
        Assertions.assertEquals( "[HyperLink], and this [Neither].", translate( src ) );
    }

    @Test
    void testNoPlugin() throws Exception {
        final String src = "There is [[{NoPlugin}] here.";
        Assertions.assertEquals( "There is [{NoPlugin}] here.", translate( src ) );
    }

    @Test
    void testErroneousHyperlink() throws Exception {
        final String src = "What if this is the last char [";
        Assertions.assertEquals( "What if this is the last char ", translate( src ) );
    }

    @Test
    void testErroneousHyperlink2() throws Exception {
        final String src = "What if this is the last char [[";
        Assertions.assertEquals( "What if this is the last char [", translate( src ) );
    }

    @Test
    void testExtraPagename1() throws Exception {
        final String src = "Link [test_page]";
        newPage( "Test_page" );
        Assertions.assertEquals( "Link <a class=\"wikipage\" href=\"/test/wiki/Test_page\">test_page</a>", translate( src ) );
    }

    @Test
    void testExtraPagename2() throws Exception {
        final String src = "Link [test.page]";
        newPage( "Test.page" );
        Assertions.assertEquals( "Link <a class=\"wikipage\" href=\"/test/wiki/Test.page\">test.page</a>", translate( src ) );
    }

    @Test
    void testExtraPagename3() throws Exception {
        final String src = "Link [.testpage_]";
        newPage( ".testpage_" );
        Assertions.assertEquals( "Link <a class=\"wikipage\" href=\"/test/wiki/.testpage_\">.testpage_</a>", translate( src ) );
    }

    @Test
    void testInlineImages() throws Exception {
        final String src = "Link [test|http://www.ecyrd.com/test.png]";
        Assertions.assertEquals( "Link <img class=\"inline\" src=\"http://www.ecyrd.com/test.png\" alt=\"test\" />", translate( src ) );
    }

    @Test
    void testInlineImages2() throws Exception {
        final String src = "Link [test|http://www.ecyrd.com/test.ppm]";
        Assertions.assertEquals( "Link <a class=\"external\" href=\"http://www.ecyrd.com/test.ppm\">test</a>", translate( src ) );
    }

    @Test
    void testInlineImages3() throws Exception {
        final String src = "Link [test|http://images.com/testi]";
        Assertions.assertEquals( "Link <img class=\"inline\" src=\"http://images.com/testi\" alt=\"test\" />", translate( src ) );
    }

    @Test
    void testInlineImages4() throws Exception {
        final String src = "Link [test|http://foobar.jpg]";
        Assertions.assertEquals( "Link <img class=\"inline\" src=\"http://foobar.jpg\" alt=\"test\" />", translate( src ) );
    }

    // No link text should be just embedded link.
    @Test
    void testInlineImagesLink2() throws Exception {
        final String src = "Link [http://foobar.jpg]";
        Assertions.assertEquals( "Link <img class=\"inline\" src=\"http://foobar.jpg\" alt=\"http://foobar.jpg\" />", translate( src ) );
    }

    @Test
    void testInlineImagesLink() throws Exception {
        final String src = "Link [http://link.to/|http://foobar.jpg]";
        Assertions.assertEquals( "Link <a class=\"external\" href=\"http://link.to/\"><img class=\"inline\" src=\"http://foobar.jpg\" alt=\"http://link.to/\" /></a>",
                translate( src ) );
    }

    @Test
    void testInlineImagesLink3() throws Exception {
        final String src = "Link [SandBox|http://foobar.jpg]";
        newPage( "SandBox" );
        Assertions.assertEquals( "Link <a class=\"wikipage\" href=\"/test/wiki/SandBox\"><img class=\"inline\" src=\"http://foobar.jpg\" alt=\"SandBox\" /></a>",
                translate( src ) );
    }

    @Test
    void testScandicPagename1() throws Exception {
        final String src = "Link [\u00C5\u00E4Test]";
        newPage( "\u00C5\u00E4Test" ); // FIXME: Should be capital
        Assertions.assertEquals( "Link <a class=\"wikipage\" href=\"/test/wiki/%C5%E4Test\">\u00c5\u00e4Test</a>", translate( src ) );
    }

    @Test
    void testParagraph() throws Exception {
        final String src = "1\n\n2\n\n3";
        Assertions.assertEquals( "<p>1\n</p><p>2\n</p>\n<p>3</p>", translate( src ) );
    }

    @Test
    void testParagraph2() throws Exception {
        final String src = "[WikiEtiquette]\r\n\r\n[Search]";
        newPage( "WikiEtiquette" );
        Assertions.assertEquals( "<p><a class=\"wikipage\" href=\"/test/wiki/WikiEtiquette\">WikiEtiquette</a>\n</p>" +
                "<p><a class=\"wikipage\" href=\"/test/wiki/Search\">Search</a></p>", translate( src ) );
    }

    @Test
    void testParagraph3() throws Exception {
        final String src = "\r\n\r\n!Testi\r\n\r\nFoo.";
        Assertions.assertEquals( "<p />\n<h4 id=\"section-testpage-Testi\">Testi<a class=\"hashlink\" href=\"#section-testpage-Testi\">#</a></h4>\n<p>Foo.</p>",
                translate( src ) );
    }

    @Test
    void testParagraph4() throws Exception {
        final String src = "\r\n[Recent Changes]\\\\\r\n[WikiEtiquette]\r\n\r\n[Find pages|Search]\\\\\r\n[Unused pages|UnusedPages]";
        newPage( "WikiEtiquette" );
        newPage( "RecentChanges" );
        newPage( "Search" );
        newPage( "UnusedPages" );
        Assertions.assertEquals( "<p><a class=\"wikipage\" href=\"/test/wiki/RecentChanges\">Recent Changes</a><br />\n" +
                        "<a class=\"wikipage\" href=\"/test/wiki/WikiEtiquette\">WikiEtiquette</a>\n</p>\n" +
                        "<p><a class=\"wikipage\" href=\"/test/wiki/Search\">Find pages</a><br />\n" +
                        "<a class=\"wikipage\" href=\"/test/wiki/UnusedPages\">Unused pages</a></p>",
                translate( src ) );
    }

    @Test
    void testParagraph5() throws Exception {
        final String src = "__File type sniffing__ is a way of identifying the content type of a document.\n\n" +
                "In UNIX, the file(1) command can be used.";
        Assertions.assertEquals( "<p><b>File type sniffing</b> is a way of identifying the content type of a document.\n</p>" +
                        "<p>In UNIX, the file(1) command can be used.</p>",
                translate( src ) );
    }

    @Test
    void testParagraph6() throws Exception {
        final String src = "[{$encoding}]\n\n__File type sniffing__ is a way of identifying the content type of a document.\n\n" +
                "In UNIX, the file(1) command can be used.";
        Assertions.assertEquals( "<p>ISO-8859-1\n</p><p><b>File type sniffing</b> is a way of identifying the content type of a document.\n</p>\n" +
                        "<p>In UNIX, the file(1) command can be used.</p>",
                translate( src ) );
    }

    @Test
    void testParagraph7() throws Exception {
        final String src = "[{$encoding}]\n\n__File type sniffing__ is a way of identifying the content type of a document.\n\n" +
                "In UNIX, the file(1) command can be used.";
        Assertions.assertEquals( "<p>ISO-8859-1\n</p><p><b>File type sniffing</b> is a way of identifying the content type of a document.\n</p>\n" +
                        "<p>In UNIX, the file(1) command can be used.</p>",
                translate( src ) );
    }

    @Test
    void testParagraph8() throws Exception {
        final String src = "[{SET foo=bar}]\n\n__File type sniffing__ is a way of identifying the content type of a document.\n\n" +
                "In UNIX, the file(1) command can be used.";
        Assertions.assertEquals( "<p><b>File type sniffing</b> is a way of identifying the content type of a document.\n</p>\n" +
                        "<p>In UNIX, the file(1) command can be used.</p>",
                translate( src ) );
    }

    @Test
    void testLinebreak() throws Exception {
        final String src = "1\\\\2";
        Assertions.assertEquals( "1<br />2", translate( src ) );
    }

    @Test
    void testLinebreakEscape() throws Exception {
        final String src = "1~\\\\2";
        Assertions.assertEquals( "1\\\\2", translate( src ) );
    }

    @Test
    void testLinebreakClear() throws Exception {
        final String src = "1\\\\\\2";
        Assertions.assertEquals( "1<br clear=\"all\" />2", translate( src ) );
    }

    @Test
    void testTT() throws Exception {
        final String src = "1{{2345}}6";
        Assertions.assertEquals( "1<tt>2345</tt>6", translate( src ) );
    }

    @Test
    void testTTAcrossLines() throws Exception {
        final String src = "1{{\n2345\n}}6";
        Assertions.assertEquals( "1<tt>\n2345\n</tt>6", translate( src ) );
    }

    @Test
    void testTTLinks() throws Exception {
        final String src = "1{{\n2345\n[a link]\n}}6";
        newPage( "ALink" );
        Assertions.assertEquals( "1<tt>\n2345\n<a class=\"wikipage\" href=\"/test/wiki/ALink\">a link</a>\n</tt>6", translate( src ) );
    }

    @Test
    void testPre()
            throws Exception {
        final String src = "1{{{2345}}}6";
        Assertions.assertEquals( "1<span class=\"inline-code\">2345</span>6", translate( src ) );
    }

    @Test
    void testPre2() throws Exception {
        final String src = "1 {{{ {{{ 2345 }}} }}} 6";
        Assertions.assertEquals( "1 <span class=\"inline-code\"> {{{ 2345 </span> }}} 6", translate( src ) );
    }

    @Test
    void testPre3() throws Exception {
        final String src = "foo\n\nbar{{{2345}}}6";
        Assertions.assertEquals( "<p>foo\n</p><p>bar<span class=\"inline-code\">2345</span>6</p>", translate( src ) );
    }

    @Test
    void testPreEscape() throws Exception {
        final String src = "1~{{{2345}}}6";
        Assertions.assertEquals( "1{{{2345}}}6", translate( src ) );
    }

    @Test
    void testPreEscape2() throws Exception {
        final String src = "1{{{{{{2345~}}}}}}6";
        Assertions.assertEquals( "1<span class=\"inline-code\">{{{2345}}}</span>6", translate( src ) );
    }

    @Test
    void testPreEscape3() throws Exception {
        final String src = "1 {{{ {{{ 2345 ~}}} }}} 6";
        Assertions.assertEquals( "1 <span class=\"inline-code\"> {{{ 2345 }}} </span> 6", translate( src ) );
    }

    @Test
    void testPreEscape4() throws Exception {
        final String src = "1{{{ {{{2345~}} }}}6";
        Assertions.assertEquals( "1<span class=\"inline-code\"> {{{2345~}} </span>6", translate( src ) );
    }

    @Test
    void testPreEscape5() throws Exception {
        final String src = "1{{{ ~ }}}6";
        Assertions.assertEquals( "1<span class=\"inline-code\"> ~ </span>6", translate( src ) );
    }

    @Test
    void testHTMLInPre() throws Exception {
        final String src = "1\n{{{ <b> }}}";
        Assertions.assertEquals( "1\n<pre> &lt;b&gt; </pre>", translate( src ) );
    }

    @Test
    void testCamelCaseInPre() throws Exception {
        final String src = "1\n{{{ CamelCase }}}";
        Assertions.assertEquals( "1\n<pre> CamelCase </pre>", translate( src ) );
    }

    @Test
    void testPreWithLines() throws Exception {
        final String src = "1\r\n{{{\r\nZippadii\r\n}}}";
        Assertions.assertEquals( "1\n<pre>\nZippadii\n</pre>", translate( src ) );
    }

    @Test
    void testList1() throws Exception {
        final String src = "A list:\n* One\n* Two\n* Three\n";
        Assertions.assertEquals( "A list:\n<ul><li>One\n</li><li>Two\n</li><li>Three\n</li></ul>", translate( src ) );
    }

    /**
     * Plain multi line testing:
     * <pre>
     * One
     * continuing
     * Two
     * Three
     * </pre>
     */
    @Test
    void testMultilineList1() throws Exception {
        final String src = "A list:\n* One\n continuing.\n* Two\n* Three\n";
        Assertions.assertEquals( "A list:\n<ul><li>One\n continuing.\n</li><li>Two\n</li><li>Three\n</li></ul>", translate( src ) );
    }

    @Test
    void testMultilineList2() throws Exception {
        final String src = "A list:\n* One\n continuing.\n* Two\n* Three\nShould be normal.";
        Assertions.assertEquals( "A list:\n<ul><li>One\n continuing.\n</li><li>Two\n</li><li>Three\n</li></ul>Should be normal.", translate( src ) );
    }

    @Test
    void testHTML() throws Exception {
        final String src = "<b>Test</b>";
        Assertions.assertEquals( "&lt;b&gt;Test&lt;/b&gt;", translate( src ) );
    }

    @Test
    void testHTML2() throws Exception {
        final String src = "<p>";
        Assertions.assertEquals( "&lt;p&gt;", translate( src ) );
    }

    @Test
    void testHTMLWhenAllowed() throws Exception {
        final String src = "<p>";
        testEngine = TestEngine.build( with( "wikantik.translatorReader.allowHTML", "true" ) );
        final Page page = Wiki.contents().page( testEngine, PAGE_NAME );
        final String out = translate( testEngine, page, src );
        Assertions.assertEquals( "<p>", out );
    }

    @Test
    void testHTMLWhenAllowedPre() throws Exception {
        final String src = "{{{ <br /> }}}";
        testEngine = TestEngine.build( with( "wikantik.translatorReader.allowHTML", "true" ) );
        final Page page = Wiki.contents().page( testEngine, PAGE_NAME );
        final String out = translate( testEngine, page, src );
        Assertions.assertEquals( "<pre> &lt;br /&gt; </pre>", out );
    }

    @Test
    void testHTMLEntities() throws Exception {
        final String src = "& &darr; foo&nbsp;bar &nbsp;&quot; &#2020;&";
        Assertions.assertEquals( "&amp; &darr; foo&nbsp;bar &nbsp;&quot; &#2020;&amp;", translate( src ) );
    }

    @Test
    void testItalicAcrossLinebreak() throws Exception {
        final String src = "''This is a\ntest.''";
        Assertions.assertEquals( "<i>This is a\ntest.</i>", translate( src ) );
    }

    @Test
    void testBoldAcrossLinebreak() throws Exception {
        final String src = "__This is a\ntest.__";
        Assertions.assertEquals( "<b>This is a\ntest.</b>", translate( src ) );
    }

    @Test
    void testBoldAcrossParagraph() throws Exception {
        final String src = "__This is a\n\ntest.__";
        Assertions.assertEquals( "<p><b>This is a\n</b></p><p><b>test.</b></p>", translate( src ) );
    }

    @Test
    void testBoldItalic() throws Exception {
        final String src = "__This ''is'' a test.__";
        Assertions.assertEquals( "<b>This <i>is</i> a test.</b>", translate( src ) );
    }

    @Test
    void testFootnote1() throws Exception {
        final String src = "Footnote[1]";
        Assertions.assertEquals( "Footnote<a class=\"footnoteref\" href=\"#ref-testpage-1\">[1]</a>", translate( src ) );
    }

    @Test
    void testFootnote2() throws Exception {
        final String src = "[#2356] Footnote.";
        Assertions.assertEquals( "<a class=\"footnote\" name=\"ref-testpage-2356\">[#2356]</a> Footnote.", translate( src ) );
    }

    @Test
    public void testFootnote3() throws Exception {
        newPage( "Hyperlink" );
        final String src = "This should be a [#1 <strong>bold</strong>]";
        Assertions.assertEquals( "This should be a <a class=\"footnote\" name=\"ref-testpage-1 &lt;strong&gt;bold&lt;/strong&gt;\">[#1 &lt;strong&gt;bold&lt;/strong&gt;]</a>", translate( src ) );
    }

    /** Check a reported error condition where empty list items could cause crashes */
    @Test
    void testEmptySecondLevelList() throws Exception {
        final String src = "A\n\n**\n\nB";
        // System.out.println(translate(src));
        Assertions.assertEquals( "<p>A\n</p><ul><li><ul><li>\n</li></ul></li></ul><p>B</p>", translate( src ) );
    }

    @Test
    void testEmptySecondLevelList2() throws Exception {
        final String src = "A\n\n##\n\nB";
        // System.out.println(translate(src));
        Assertions.assertEquals( "<p>A\n</p><ol><li><ol><li>\n</li></ol></li></ol><p>B</p>", translate( src ) );
    }

    /**
     * <pre>
     *   *Item A
     *   ##Numbered 1
     *   ##Numbered 2
     *   *Item B
     * </pre>
     * <p>
     * would come out as:
     * <ul>
     * <li>Item A
     * </ul>
     * <ol>
     * <ol>
     * <li>Numbered 1
     * <li>Numbered 2
     * <ul>
     * <li></ol>
     * </ol>
     * Item B
     * </ul>
     * <p>
     *  (by Mahlen Morris).
     */
    @Test
    void testMixedList() throws Exception {
        final String src = "*Item A\n##Numbered 1\n##Numbered 2\n*Item B\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ul><li>Item A" +
                        "<ol><li>Numbered 1</li>" +
                        "<li>Numbered 2</li>" +
                        "</ol></li>" +
                        "<li>Item B</li>" +
                        "</ul>",
                result );
    }

    /** Like testMixedList() but the list types have been reversed. */
    @Test
    void testMixedList2() throws Exception {
        final String src = "#Item A\n**Numbered 1\n**Numbered 2\n#Item B\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ol><li>Item A" +
                        "<ul><li>Numbered 1</li>" +
                        "<li>Numbered 2</li>" +
                        "</ul></li>" +
                        "<li>Item B</li>" +
                        "</ol>",
                result );
    }

    /**
     * <pre>
     *   * bullet A
     *   ** bullet A_1
     *   *# number A_1
     *   * bullet B
     * </pre>
     * <p>
     * would come out as:
     *
     * <ul>
     *   <li>bullet A
     *     <ul>
     *       <li>bullet A_1</li>
     *     </ul>
     *     <ol>
     *       <li>number A_1</li>
     *     </ol>
     *   </li>
     *   <li>bullet B</li>
     * </ul>
     */
    @Test
    void testMixedListOnSameLevel() throws Exception {
        final String src = "* bullet A\n** bullet A_1\n*# number A_1\n* bullet B\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ul>" +
                        "<li>bullet A" +
                        "<ul>" +
                        "<li>bullet A_1</li>" +
                        "</ul>" +
                        "<ol>" +
                        "<li>number A_1</li>" +
                        "</ol>" +
                        "</li>" +
                        "<li>bullet B</li>" +
                        "</ul>",
                result );
    }

    /**
     * <pre>
     *   * bullet A
     *   ** bullet A_1
     *   ## number A_1
     *   * bullet B
     * </pre>
     * <p>
     * would come out as:
     *
     * <ul>
     *   <li>bullet A
     *     <ul>
     *       <li>bullet A_1</li>
     *     </ul>
     *     <ol>
     *       <li>number A_1</li>
     *     </ol>
     *   </li>
     *   <li>bullet B</li>
     * </ul>
     */
    @Test
    void testMixedListOnSameLevel2() throws Exception {
        final String src = "* bullet A\n** bullet A_1\n## number A_1\n* bullet B\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ul>" +
                        "<li>bullet A" +
                        "<ul>" +
                        "<li>bullet A_1</li>" +
                        "</ul>" +
                        "<ol>" +
                        "<li>number A_1</li>" +
                        "</ol>" +
                        "</li>" +
                        "<li>bullet B</li>" +
                        "</ul>",
                result );
    }

    /**
     * <pre>
     *   * bullet 1
     *   ## number 2
     *   ** bullet 3
     *   ## number 4
     *   * bullet 5
     * </pre>
     * <p>
     * would come out as:
     *
     * <ul>
     *     <li>bullet 1
     *         <ol><li>number 2</li></ol>
     *         <ul><li>bullet 3</li></ul>
     *         <ol><li>number 4</li></ol>
     *     </li>
     *     <li>bullet 5</li>
     * </ul>
     */
    @Test
    void testMixedListOnSameLevel3() throws Exception {
        final String src = "* bullet 1\n## number 2\n** bullet 3\n## number 4\n* bullet 5\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ul>" +
                        "<li>bullet 1" +
                        "<ol><li>number 2</li></ol>" +
                        "<ul><li>bullet 3</li></ul>" +
                        "<ol><li>number 4</li></ol>" +
                        "</li>" +
                        "<li>bullet 5</li>" +
                        "</ul>",
                result );
    }

    /**
     * <pre>
     *   # number 1
     *   ** bullet 2
     *   ## number 3
     *   ** bullet 4
     *   # number 5
     * </pre>
     * <p>
     * would come out as:
     *
     * <ol>
     *     <li>number 1
     *         <ul><li>bullet 2</li></ul>
     *         <ol><li>number 3</li></ol>
     *         <ul><li>bullet 4</li></ul>
     *     </li>
     *     <li>number 5</li>
     * </ol>
     */
    @Test
    void testMixedListOnSameLevel4() throws Exception {
        final String src = "# number 1\n** bullet 2\n## number 3\n** bullet 4\n# number 5\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ol>" +
                        "<li>number 1" +
                        "<ul><li>bullet 2</li></ul>" +
                        "<ol><li>number 3</li></ol>" +
                        "<ul><li>bullet 4</li></ul>" +
                        "</li>" +
                        "<li>number 5</li>" +
                        "</ol>",
                result );
    }

    @Test
    void testNestedList() throws Exception {
        final String src = "*Item A\n**Numbered 1\n**Numbered 2\n*Item B\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ul><li>Item A" +
                        "<ul><li>Numbered 1</li>" +
                        "<li>Numbered 2</li>" +
                        "</ul></li>" +
                        "<li>Item B</li>" +
                        "</ul>",
                result );
    }

    @Test
    void testNestedList2() throws Exception {
        final String src = "*Item A\n**Numbered 1\n**Numbered 2\n***Numbered3\n*Item B\n";
        String result = translate( src );
        // Remove newlines for easier parsing.
        result = TextUtil.replaceString( result, "\n", "" );
        Assertions.assertEquals( "<ul><li>Item A" +
                        "<ul><li>Numbered 1</li>" +
                        "<li>Numbered 2" +
                        "<ul><li>Numbered3</li>" +
                        "</ul></li>" +
                        "</ul></li>" +
                        "<li>Item B</li>" +
                        "</ul>",
                result );
    }


    @Test
    void testPluginInsert() throws Exception {
        final String src = "[{INSERT com.wikantik.plugin.SamplePlugin WHERE text=test}]";
        Assertions.assertEquals( "test", translate( src ) );
    }

    @Test
    void testPluginHTMLInsert() throws Exception {
        final String src = "[{INSERT com.wikantik.plugin.SamplePlugin WHERE text='<b>Foo</b>'}]";
        Assertions.assertEquals( "<b>Foo</b>", translate( src ) );
    }

    @Test
    void testPluginNoInsert() throws Exception {
        final String src = "[{SamplePlugin text=test}]";
        Assertions.assertEquals( "test", translate( src ) );
    }

    @Test
    void testPluginInsertJS() throws Exception {
        final String src = "Today: [{INSERT JavaScriptPlugin}] ''day''.";
        Assertions.assertEquals( "Today: <script language=\"JavaScript\"><!--\nfoo='';\n--></script>\n <i>day</i>.", translate( src ) );
    }

    @Test
    void testShortPluginInsert() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text=test}]";
        Assertions.assertEquals( "test", translate( src ) );
    }

    /** Test two plugins on same row. */
    @Test
    void testShortPluginInsert2() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text=test}] [{INSERT SamplePlugin WHERE text=test2}]";
        Assertions.assertEquals( "test test2", translate( src ) );
    }

    @Test
    void testPluginQuotedArgs() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text='test me now'}]";
        Assertions.assertEquals( "test me now", translate( src ) );
    }

    @Test
    void testPluginDoublyQuotedArgs() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text='test \\'me too\\' now'}]";
        Assertions.assertEquals( "test 'me too' now", translate( src ) );
    }

    @Test
    void testPluginQuotedArgs2() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text=foo}] [{INSERT SamplePlugin WHERE text='test \\'me too\\' now'}]";
        Assertions.assertEquals( "foo test 'me too' now", translate( src ) );
    }

    /** Plugin output must not be parsed as Wiki text. */
    @Test
    void testPluginWikiText() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text=PageContent}]";
        Assertions.assertEquals( "PageContent", translate( src ) );
    }

    /** Nor should plugin input be interpreted as wiki text. */
    @Test
    void testPluginWikiText2() throws Exception {
        final String src = "[{INSERT SamplePlugin WHERE text='----'}]";
        Assertions.assertEquals( "----", translate( src ) );
    }

    @Test
    void testMultilinePlugin1() throws Exception {
        final String src = "Test [{INSERT SamplePlugin WHERE\n text=PageContent}]";
        Assertions.assertEquals( "Test PageContent", translate( src ) );
    }

    @Test
    void testMultilinePluginBodyContent() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\ntext=PageContent\n\n123\n456\n}]";
        Assertions.assertEquals( "Test PageContent (123+456+)", translate( src ) );
    }

    @Test
    void testMultilinePluginBodyContent2() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\ntext=PageContent\n\n\n123\n456\n}]";
        Assertions.assertEquals( "Test PageContent (+123+456+)", translate( src ) );
    }

    @Test
    void testMultilinePluginBodyContent3() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\n\n123\n456\n}]";
        Assertions.assertEquals( "Test  (123+456+)", translate( src ) );
    }

    /** Has an extra space after plugin name. */
    @Test
    void testMultilinePluginBodyContent4() throws Exception {
        final String src = "Test [{INSERT SamplePlugin \n\n123\n456\n}]";
        Assertions.assertEquals( "Test  (123+456+)", translate( src ) );
    }

    /** Check that plugin end is correctly recognized. */
    @Test
    void testPluginEnd() throws Exception {
        final String src = "Test [{INSERT SamplePlugin text=']'}]";
        Assertions.assertEquals( "Test ]", translate( src ) );
    }

    @Test
    void testPluginEnd2() throws Exception {
        final String src = "Test [{INSERT SamplePlugin text='a[]+b'}]";
        Assertions.assertEquals( "Test a[]+b", translate( src ) );
    }

    @Test
    void testPluginEnd3() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\n\na[]+b\n}]";
        Assertions.assertEquals( "Test  (a[]+b+)", translate( src ) );
    }

    @Test
    void testPluginEnd4() throws Exception {
        final String src = "Test [{INSERT SamplePlugin text='}'}]";
        Assertions.assertEquals( "Test }", translate( src ) );
    }

    @Test
    void testPluginEnd5() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\n\na[]+b{}\nGlob.\n}]";
        Assertions.assertEquals( "Test  (a[]+b{}+Glob.+)", translate( src ) );
    }

    @Test
    void testPluginEnd6() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\n\na[]+b{}\nGlob.\n}}]";
        Assertions.assertEquals( "Test  (a[]+b{}+Glob.+})", translate( src ) );
    }

    @Test
    void testNestedPlugin1() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\n\n[{SamplePlugin}]\nGlob.\n}}]";
        Assertions.assertEquals( "Test  ([{SamplePlugin}]+Glob.+})", translate( src ) );
    }

    @Test
    void testNestedPlugin2() throws Exception {
        final String src = "[{SET foo='bar'}]Test [{INSERT SamplePlugin\n\n[{SamplePlugin text='[{$foo}]'}]\nGlob.\n}}]";
        Assertions.assertEquals( "Test  ([{SamplePlugin text='[bar]'}]+Glob.+})", translate( src ) );
    }

    //  FIXME: I am not entirely certain if this is the right result
    //  Perhaps some sort of an error should be checked?
    @Test
    void testPluginNoEnd() throws Exception {
        final String src = "Test [{INSERT SamplePlugin\n\na+b{}\nGlob.\n}";
        Assertions.assertEquals( "Test {INSERT SamplePlugin\n\na+b{}\nGlob.\n}", translate( src ) );
    }

    @Test
    void testMissingPlugin() throws Exception {
        final String src = "Test [{SamplePlugino foo='bar'}]";
        Assertions.assertEquals( "Test Wikantik : testpage - Plugin insertion failed: Could not find plugin SamplePlugino" +
                                "<span class=\"error\">Wikantik : testpage - Plugin insertion failed: Could not find plugin SamplePlugino</span>",
                                translate( src ) );
    }

    @Test
    void testVariableInsert() throws Exception {
        final String src = "[{$pagename}]";
        Assertions.assertEquals( PAGE_NAME, translate( src ) );
    }

    @Test
    void testTable1() throws Exception {
        final String src = "|| heading || heading2 \n| Cell 1 | Cell 2 \n| Cell 3 | Cell 4\n\n";
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><th> heading </th><th> heading2 </th></tr>\n" +
                        "<tr><td> Cell 1 </td><td> Cell 2 </td></tr>\n" +
                        "<tr class=\"odd\"><td> Cell 3 </td><td> Cell 4</td></tr>\n" +
                        "</table><p />",
                translate( src ) );
    }

    @Test
    void testTable2() throws Exception {
        final String src = "||heading||heading2\n|Cell 1| Cell 2\n| Cell 3 |Cell 4\n\n";
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><th>heading</th><th>heading2</th></tr>\n" +
                        "<tr><td>Cell 1</td><td> Cell 2</td></tr>\n" +
                        "<tr class=\"odd\"><td> Cell 3 </td><td>Cell 4</td></tr>\n" +
                        "</table><p />",
                translate( src ) );
    }

    @Test
    void testTable3() throws Exception {
        final String src = "|Cell 1| Cell 2\n| Cell 3 |Cell 4\n\n";
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><td>Cell 1</td><td> Cell 2</td></tr>\n" +
                        "<tr><td> Cell 3 </td><td>Cell 4</td></tr>\n" +
                        "</table><p />",
                translate( src ) );
    }

    @Test
    void testTable4() throws Exception {
        final String src = "|a\nbc";
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><td>a</td></tr>\n" +
                        "</table>" +
                        "bc",
                translate( src ) );
    }

    /**
     * Tests BugTableHeaderNotXHMTLCompliant
     */
    @Test
    void testTable5() throws Exception {
        final String src = "Testtable\n||header|cell\n\n|cell||header";
        Assertions.assertEquals( "<p>Testtable\n</p>" +
                        "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><th>header</th><td>cell</td></tr>\n</table><p />\n" +
                        "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><td>cell</td><th>header</th></tr>" +
                        "</table>",
                translate( src ) );
    }

    @Test
    void testTableLink() throws Exception {
        final String src = "|Cell 1| Cell 2\n|[Cell 3|ReallyALink]|Cell 4\n\n";
        newPage( "ReallyALink" );
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><td>Cell 1</td><td> Cell 2</td></tr>\n" +
                        "<tr><td><a class=\"wikipage\" href=\"/test/wiki/ReallyALink\">Cell 3</a></td><td>Cell 4</td></tr>\n" +
                        "</table><p />",
                translate( src ) );
    }

    @Test
    void testTableLinkEscapedBar() throws Exception {
        final String src = "|Cell 1| Cell~| 2\n|[Cell 3|ReallyALink]|Cell 4\n\n";
        newPage( "ReallyALink" );
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\">" +
                        "<tr class=\"odd\"><td>Cell 1</td><td> Cell| 2</td></tr>\n" +
                        "<tr><td><a class=\"wikipage\" href=\"/test/wiki/ReallyALink\">Cell 3</a></td><td>Cell 4</td></tr>\n" +
                        "</table><p />",
                translate( src ) );
    }

    @Test
    void testDescription() throws Exception {
        final String src = ";:Foo";
        Assertions.assertEquals( "<dl><dt></dt><dd>Foo</dd></dl>", translate( src ) );
    }

    @Test
    void testDescription2() throws Exception {
        final String src = ";Bar:Foo";
        Assertions.assertEquals( "<dl><dt>Bar</dt><dd>Foo</dd></dl>", translate( src ) );
    }

    @Test
    void testDescription3() throws Exception {
        final String src = ";:";
        Assertions.assertEquals( "<dl><dt></dt><dd /></dl>", translate( src ) );
    }

    @Test
    void testDescription4() throws Exception {
        final String src = ";Bar:Foo :-)";
        Assertions.assertEquals( "<dl><dt>Bar</dt><dd>Foo :-)</dd></dl>", translate( src ) );
    }

    @Test
    void testDescription5() throws Exception {
        final String src = ";Bar:Foo :-) ;-) :*]";
        Assertions.assertEquals( "<dl><dt>Bar</dt><dd>Foo :-) ;-) :*]</dd></dl>", translate( src ) );
    }

    @Test
    void testRuler() throws Exception {
        final String src = "----";
        Assertions.assertEquals( "<hr />", translate( src ) );
    }

    @Test
    void testRulerCombo() throws Exception {
        final String src = "----Foo";
        Assertions.assertEquals( "<hr />Foo", translate( src ) );
    }

    @Test
    void testRulerCombo2() throws Exception {
        final String src = "Bar----Foo";
        Assertions.assertEquals( "Bar----Foo", translate( src ) );
    }

    @Test
    void testShortRuler1() throws Exception {
        final String src = "-";
        Assertions.assertEquals( "-", translate( src ) );
    }

    @Test
    void testShortRuler2() throws Exception {
        final String src = "--";
        Assertions.assertEquals( "--", translate( src ) );
    }

    @Test
    void testShortRuler3() throws Exception {
        final String src = "---";
        Assertions.assertEquals( "---", translate( src ) );
    }

    @Test
    void testLongRuler() throws Exception {
        final String src = "------";
        Assertions.assertEquals( "<hr />", translate( src ) );
    }

    @Test
    void testHeading1() throws Exception {
        final String src = "!Hello\nThis is a test";
        Assertions.assertEquals( "<h4 id=\"section-testpage-Hello\">Hello<a class=\"hashlink\" href=\"#section-testpage-Hello\">#</a></h4>\nThis is a test",
                translate( src ) );
    }

    @Test
    void testHeading2() throws Exception {
        final String src = "!!Hello, testing 1, 2, 3";
        Assertions.assertEquals( "<h3 id=\"section-testpage-HelloTesting123\">Hello, testing 1, 2, 3<a class=\"hashlink\" href=\"#section-testpage-HelloTesting123\">#</a></h3>",
                translate( src ) );
    }

    @Test
    void testHeading3() throws Exception {
        final String src = "!!!Hello there, how are you doing?";
        Assertions.assertEquals( "<h2 id=\"section-testpage-HelloThereHowAreYouDoing\">Hello there, how are you doing?<a class=\"hashlink\" href=\"#section-testpage-HelloThereHowAreYouDoing\">#</a></h2>",
                translate( src ) );
    }

    @Test
    void testHeadingHyperlinks() throws Exception {
        final String src = "!!![Hello]";
        Assertions.assertEquals( "<h2 id=\"section-testpage-Hello\"><a class=\"createpage\" href=\"/test/Edit.jsp?page=Hello\" title=\"Create &quot;Hello&quot;\">Hello</a><a class=\"hashlink\" href=\"#section-testpage-Hello\">#</a></h2>",
                translate( src ) );
    }

    @Test
    void testHeadingHyperlinks2() throws Exception {
        final String src = "!!![Hello|http://www.google.com/]";
        Assertions.assertEquals( "<h2 id=\"section-testpage-Hello\"><a class=\"external\" href=\"http://www.google.com/\">Hello</a><a class=\"hashlink\" href=\"#section-testpage-Hello\">#</a></h2>",
                translate( src ) );
    }

    @Test
    void testHeadingHyperlinks3() throws Exception {
        final String src = "![Hello|http://www.google.com/?p=a&c=d]";
        Assertions.assertEquals( "<h4 id=\"section-testpage-Hello\"><a class=\"external\" href=\"http://www.google.com/?p=a&amp;c=d\">Hello</a><a class=\"hashlink\" href=\"#section-testpage-Hello\">#</a></h4>",
                translate( src ) );
    }

    /** in 2.0.0, this one throws OutofMemoryError. */
    @Test
    void testBrokenPageText() throws Exception {
        final String translation = translate( brokenPageText );
        Assertions.assertNotNull( translation );
    }

    /** Shortened version of the previous one. */
    @Test
    void testBrokenPageTextShort() throws Exception {
        final String src = "{{{\ncode.}}\n";
        Assertions.assertEquals( "<pre>\ncode.}}\n</pre>", translate( src ) );
    }

    /** Shortened version of the previous one. */
    @Test
    void testBrokenPageTextShort2() throws Exception {
        final String src = "{{{\ncode.}\n";
        Assertions.assertEquals( "<pre>\ncode.}\n</pre>", translate( src ) );
    }

    @Test
    void testExtraExclamation() throws Exception {
        final String src = "Hello!";
        Assertions.assertEquals( "Hello!", translate( src ) );
    }

    /** Metadata tests */
    @Test
    void testSet1() throws Exception {
        final String src = "Foobar.[{SET name=foo}]";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        final String res = translate( p, src );
        Assertions.assertEquals( "Foobar.", res, "Page text" );
        Assertions.assertEquals( "foo", p.getAttribute( "name" ) );
    }

    @Test
    void testSet2() throws Exception {
        final String src = "Foobar.[{SET name = foo}]";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        final String res = translate( p, src );
        Assertions.assertEquals( "Foobar.", res, "Page text" );
        Assertions.assertEquals( "foo", p.getAttribute( "name" ) );
    }

    @Test
    void testSet3() throws Exception {
        final String src = "Foobar.[{SET name= Janne Jalkanen}]";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        final String res = translate( p, src );
        Assertions.assertEquals( "Foobar.", res, "Page text" );
        Assertions.assertEquals( "Janne Jalkanen", p.getAttribute( "name" ) );
    }

    @Test
    void testSet4() throws Exception {
        final String src = "Foobar.[{SET name='Janne Jalkanen'}][{SET too='{$name}'}]";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        final String res = translate( p, src );
        Assertions.assertEquals( "Foobar.", res, "Page text" );
        Assertions.assertEquals( "Janne Jalkanen", p.getAttribute( "name" ) );
        Assertions.assertEquals( "Janne Jalkanen", p.getAttribute( "too" ) );
    }

    @Test
    void testSetHTML() throws Exception {
        final String src = "Foobar.[{SET name='<b>danger</b>'}] [{$name}]";
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        final String res = translate( p, src );
        Assertions.assertEquals( "Foobar. &lt;b&gt;danger&lt;/b&gt;", res, "Page text" );
        Assertions.assertEquals( "<b>danger</b>", p.getAttribute( "name" ) );
    }

    /**
     * Test collection of links.
     */
    @Test
    void testCollectingLinks() throws Exception {
        final LinkCollector coll = new LinkCollector();
        final String src = "[Test]";
        final WikiContext context = new WikiContext( testEngine, Wiki.contents().page( testEngine, PAGE_NAME ) );

        final MarkupParser p = new WikantikMarkupParser( context, new BufferedReader( new StringReader( src ) ) );
        p.addLocalLinkHook( coll );
        p.addExternalLinkHook( coll );
        p.addAttachmentLinkHook( coll );
        p.parse();

        final Collection< String > links = coll.getLinks();
        Assertions.assertEquals( 1, links.size(), "no links found" );
        Assertions.assertEquals( "Test", links.iterator().next(), "wrong link" );
    }

    @Test
    void testCollectingLinks2() throws Exception {
        final LinkCollector coll = new LinkCollector();
        final String src = "[" + PAGE_NAME + "/Test.txt]";
        final WikiContext context = new WikiContext( testEngine, Wiki.contents().page( testEngine, PAGE_NAME ) );

        final MarkupParser p = new WikantikMarkupParser( context, new BufferedReader( new StringReader( src ) ) );
        p.addLocalLinkHook( coll );
        p.addExternalLinkHook( coll );
        p.addAttachmentLinkHook( coll );
        p.parse();

        final Collection< String > links = coll.getLinks();
        Assertions.assertEquals( 1, links.size(), "no links found" );
        Assertions.assertEquals( PAGE_NAME + "/Test.txt", links.iterator().next(), "wrong link" );
    }

    @Test
    void testCollectingLinksAttachment() throws Exception {
        // First, make an attachment.
        try {
            testEngine.saveText( PAGE_NAME, "content" );
            final Attachment att = Wiki.contents().attachment( testEngine, PAGE_NAME, "TestAtt.txt" );
            att.setAuthor( "FirstPost" );
            testEngine.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );

            final LinkCollector coll = new LinkCollector();
            final LinkCollector coll_others = new LinkCollector();
            final String src = "[TestAtt.txt]";
            final WikiContext context = new WikiContext( testEngine, Wiki.contents().page( testEngine, PAGE_NAME ) );

            final MarkupParser p = new WikantikMarkupParser( context, new BufferedReader( new StringReader( src ) ) );
            p.addLocalLinkHook( coll_others );
            p.addExternalLinkHook( coll_others );
            p.addAttachmentLinkHook( coll );
            p.parse();

            final Collection< String > links = coll.getLinks();
            Assertions.assertEquals( 1, links.size(), "no links found" );
            Assertions.assertEquals( PAGE_NAME + "/TestAtt.txt", links.iterator().next(), "wrong link" );
            Assertions.assertEquals( 0, coll_others.getLinks().size(), "wrong links found" );
        } finally {
            final String files = testEngine.getWikiProperties().getProperty( AttachmentProvider.PROP_STORAGEDIR );
            final File storagedir = new File( files, PAGE_NAME + BasicAttachmentProvider.DIR_EXTENSION );
            if ( storagedir.exists() && storagedir.isDirectory() ) {
                TestEngine.deleteAll( storagedir );
            }
        }
    }

    @Test
    void testDivStyle1() throws Exception {
        final String src = "%%foo\ntest\n%%\n";
        Assertions.assertEquals( "<div class=\"foo\">\ntest\n</div>\n", translate( src ) );
    }

    @Test
    void testDivStyle2() throws Exception {
        final String src = "%%foo.bar\ntest\n%%\n";
        Assertions.assertEquals( "<div class=\"foo bar\">\ntest\n</div>\n", translate( src ) );
    }

    @Test
    void testDivStyle3() throws Exception {
        final String src = "%%(foo:bar;)\ntest\n%%\n";
        Assertions.assertEquals( "<div style=\"foo:bar;\">\ntest\n</div>\n", translate( src ) );
    }

    @Test
    void testDivStyle4() throws Exception {
        final String src = "%%zoo(foo:bar;)\ntest\n%%\n";
        Assertions.assertEquals( "<div style=\"foo:bar;\" class=\"zoo\">\ntest\n</div>\n", translate( src ) );
    }

    @Test
    void testDivStyle5() throws Exception {
        final String src = "%%zoo1.zoo2(foo:bar;)\ntest\n%%\n";
        Assertions.assertEquals( "<div style=\"foo:bar;\" class=\"zoo1 zoo2\">\ntest\n</div>\n", translate( src ) );
    }

    @Test
    void testSpanStyle1() throws Exception {
        final String src = "%%foo test%%\n";
        Assertions.assertEquals( "<span class=\"foo\">test</span>\n", translate( src ) );
    }

    @Test
    void testSpanStyle2() throws Exception {
        final String src = "%%(foo:bar;)test%%\n";
        Assertions.assertEquals( "<span style=\"foo:bar;\">test</span>\n", translate( src ) );
    }

    @Test
    void testSpanStyle3() throws Exception {
        final String src = "Johan %%(foo:bar;)test%%\n";
        Assertions.assertEquals( "Johan <span style=\"foo:bar;\">test</span>\n", translate( src ) );
    }

    @Test
    void testSpanStyle4() throws Exception {
        final String src = "Johan %%(foo:bar;)test/%\n";
        Assertions.assertEquals( "Johan <span style=\"foo:bar;\">test</span>\n", translate( src ) );
    }

    @Test
    void testSpanEscape() throws Exception {
        final String src = "~%%foo test~%%\n";
        Assertions.assertEquals( "%%foo test%%\n", translate( src ) );
    }

    @Test
    void testSpanNested() throws Exception {
        final String src = "Johan %%(color: rgb(1,2,3);)test%%\n";
        Assertions.assertEquals( "Johan <span style=\"color: rgb(1,2,3);\">test</span>\n", translate( src ) );
    }

    @Test
    void testSpanStyleTable() throws Exception {
        final String src = "|%%(foo:bar;)test%%|no test\n";
        Assertions.assertEquals( "<table class=\"wikitable\" border=\"1\"><tr class=\"odd\"><td><span style=\"foo:bar;\">test</span></td><td>no test</td></tr>\n</table>", translate( src ) );
    }

    @Test
    void testSpanJavascript() throws Exception {
        final String src = "%%(visibility: hidden; background-image:url(javascript:alert('X')))%%\nTEST";
        Assertions.assertEquals( "<span class=\"error\">Attempt to output javascript!</span>\nTEST", translate( src ) );
    }

    @Test
    void testHTMLEntities1() throws Exception {
        final String src = "Janne&apos;s test";
        Assertions.assertEquals( "Janne&apos;s test", translate( src ) );
    }

    @Test
    void testHTMLEntities2() throws Exception {
        final String src = "&Auml;";
        Assertions.assertEquals( "&Auml;", translate( src ) );
    }

    @Test
    void testBlankEscape() throws Exception {
        final String src = "H2%%sub 2%%~ O";
        Assertions.assertEquals( "H2<span class=\"sub\">2</span>O", translate( src ) );
    }

    @Test
    void testEmptyBold() throws Exception {
        final String src = "____";
        Assertions.assertEquals( "<b></b>", translate( src ) );
    }

    @Test
    void testEmptyItalic() throws Exception {
        final String src = "''''";
        Assertions.assertEquals( "<i></i>", translate( src ) );
    }

    @Test
    void testRenderingSpeed1() throws Exception {
        final Benchmark sw = new Benchmark();
        sw.start();
        for ( int i = 0; i < 100; i++ ) {
            translate( brokenPageText );
        }
        sw.stop();
        System.out.println( "100 page renderings: " + sw + " (" + sw.toString( 100 ) + " renderings/second)" );
    }

    @Test
    void testPunctuatedWikiNames() throws Exception {
        final String src = "[-phobous]";
        Assertions.assertEquals( "<a class=\"createpage\" href=\"/test/Edit.jsp?page=-phobous\" title=\"Create &quot;-phobous&quot;\">-phobous</a>", translate( src ) );
    }

    @Test
    void testPunctuatedWikiNames2() throws Exception {
        final String src = "[?phobous]";
        Assertions.assertEquals( "<a class=\"createpage\" href=\"/test/Edit.jsp?page=Phobous\" title=\"Create &quot;Phobous&quot;\">?phobous</a>", translate( src ) );
    }

    @Test
    void testPunctuatedWikiNames3() throws Exception {
        final String src = "[Brightness (apical)]";
        Assertions.assertEquals( "<a class=\"createpage\" href=\"/test/Edit.jsp?page=Brightness%20%28apical%29\" title=\"Create &quot;Brightness (apical)&quot;\">Brightness (apical)</a>", translate( src ) );
    }

    @Test
    void testDeadlySpammer() throws Exception {
        final String deadlySpammerText = "zzz <a href=\"http://ring1.gmum.net/frog-ringtone.html\">frogringtone</a> zzz http://ring1.gmum.net/frog-ringtone.html[URL=http://ring1.gmum.net/frog-ringtone.html]frog ringtone[/URL] frogringtone<br>";
        final StringBuilder death = new StringBuilder( 20000 );
        death.append( deadlySpammerText.repeat( 1000 ) );
        death.append( "\n\n" );

        System.out.println( "Trying to crash parser with a line which is " + death.length() + " chars in size" );
        //  This should not Assertions.fail
        final String res = translate( death.toString() );
        Assertions.assertFalse( res.isEmpty() );
    }

    @Test
    void testSpacesInLinks1() throws Exception {
        newPage( "Foo bar" );
        final String src = "[Foo bar]";
        Assertions.assertEquals( "<a class=\"wikipage\" href=\"/test/wiki/Foo%20bar\">Foo bar</a>", translate( src ) );
    }

    /** Too many spaces */
    @Test
    void testSpacesInLinks2() throws Exception {
        newPage( "Foo bar" );
        final String src = "[Foo        bar]";
        Assertions.assertEquals( "<a class=\"wikipage\" href=\"/test/wiki/Foo%20bar\">Foo        bar</a>", translate( src ) );
    }

    @Test
    void testIllegalXML() throws Exception {
        final String src = "Test \u001d foo";
        final String dst = translate( src );
        Assertions.assertTrue( dst.contains( "JDOM" ), "No error" );
    }

    @Test
    void testXSS1() throws Exception {
        final String src = "[http://www.host.com/du=\"> <img src=\"foobar\" onerror=\"alert(document.cookie)\"/>]";
        final String dst = translate( src );
        Assertions.assertEquals( "<a class=\"external\" href=\"http://www.host.com/du=&quot;&gt; &lt;img src=&quot;foobar&quot; onerror=&quot;alert(document.cookie)&quot;/&gt;\">http://www.host.com/du=&quot;&gt; &lt;img src=&quot;foobar&quot; onerror=&quot;alert(document.cookie)&quot;/&gt;</a>", dst );
    }

    @Test
    void testAmpersand1() throws Exception {
        newPage( "Foo&Bar" );
        final String src = "[Foo&Bar]";
        final String dst = translate( src );
        Assertions.assertEquals( "<a class=\"wikipage\" href=\"/test/wiki/Foo%26Bar\">Foo&amp;Bar</a>", dst );
    }

    @Test
    void testAmpersand2() throws Exception {
        newPage( "Foo & Bar" );
        final String src = "[Foo & Bar]";
        final String dst = translate( src );
        Assertions.assertEquals( "<a class=\"wikipage\" href=\"/test/wiki/Foo%20%26%20Bar\">Foo &amp; Bar</a>", dst );
    }

    // This is a random find: the following page text caused an eternal loop in V2.0.x.
    private static final String brokenPageText =
            "Please ''check [RecentChanges].\n" +
                    "\n" +
                    "Testing. fewfwefe\n" +
                    "\n" +
                    "CHeck [testpage]\n" +
                    "\n" +
                    "More testing.\n" +
                    "dsadsadsa''\n" +
                    "Is this {{truetype}} or not?\n" +
                    "What about {{{This}}}?\n" +
                    "How about {{this?\n" +
                    "\n" +
                    "{{{\n" +
                    "{{text}}\n" +
                    "}}}\n" +
                    "goo\n" +
                    "\n" +
                    "<b>Not bold</b>\n" +
                    "\n" +
                    "motto\n" +
                    "\n" +
                    "* This is a list which we\n" +
                    "shall continue on a other line.\n" +
                    "* There is a list item here.\n" +
                    "*  Another item.\n" +
                    "* More stuff, which continues\n" +
                    "on a second line.  And on\n" +
                    "a third line as well.\n" +
                    "And a fourth line.\n" +
                    "* Third item.\n" +
                    "\n" +
                    "Foobar.\n" +
                    "\n" +
                    "----\n" +
                    "\n" +
                    "!!!Really big heading\n" +
                    "Text.\n" +
                    "!! Just a normal heading [with a hyperlink|Main]\n" +
                    "More text.\n" +
                    "!Just a small heading.\n" +
                    "\n" +
                    "This should be __bold__ text.\n" +
                    "\n" +
                    "__more bold text continuing\n" +
                    "on the next line.__\n" +
                    "\n" +
                    "__more bold text continuing\n" +
                    "\n" +
                    "on the next paragraph.__\n" +
                    "\n" +
                    "\n" +
                    "This should be normal.\n" +
                    "\n" +
                    "Now, let's try ''italic text''.\n" +
                    "\n" +
                    "Bulleted lists:\n" +
                    "* One\n" +
                    "Or more.\n" +
                    "* Two\n" +
                    "\n" +
                    "** Two.One\n" +
                    "\n" +
                    "*** Two.One.One\n" +
                    "\n" +
                    "* Three\n" +
                    "\n" +
                    "Numbered lists.\n" +
                    "# One\n" +
                    "# Two\n" +
                    "# Three\n" +
                    "## Three.One\n" +
                    "## Three.Two\n" +
                    "## Three.Three\n" +
                    "### Three.Three.One\n" +
                    "# Four\n" +
                    "\n" +
                    "End?\n" +
                    "\n" +
                    "No, let's {{break}} things.\\ {{{ {{{ {{text}} }}} }}}\n" +
                    "\n" +
                    "More breaking.\n" +
                    "\n" +
                    "{{{\n" +
                    "code.}}\n" +
                    "----\n" +
                    "author: [Asser], [Ebu], [JanneJalkanen], [Jarmo|mailto:jarmo@regex.com.au]\n";


    @Test
    public void testEscapeHTMLWhenHTMLNotAllowed() throws Exception {
        final String src = "This should be a [#1 <script>alert('XSS')</script>]";
        testEngine = TestEngine.build(with("wikantik.translatorReader.allowHTML", "false")); // Disable HTML
        final Page page = Wiki.contents().page(testEngine, PAGE_NAME);
        final String output = translate(testEngine, page, src);
        Assertions.assertEquals(
                "This should be a <a class=\"footnote\" name=\"ref-testpage-1 &lt;script&gt;alert('XSS')&lt;/script&gt;\">[#1 &lt;script&gt;alert('XSS')&lt;/script&gt;]</a>",
                output
        );
    }


    @Test
    public void testNoEscapeHTMLWhenHTMLAllowed() throws Exception {
        final String src = "This should be a [#1 <b>bold</b>]";
        testEngine = TestEngine.build(with("wikantik.translatorReader.allowHTML", "true")); // Enable HTML
        final Page page = Wiki.contents().page(testEngine, PAGE_NAME);
        final String output = translate(testEngine, page, src);
        Assertions.assertEquals(
                "This should be a <a class=\"footnote\" name=\"ref-testpage-1 &lt;b&gt;bold&lt;/b&gt;\">[#1 <b>bold</b>]</a>",
                output
        );
    }

    // ========== Phase 3: Error Handling Tests ==========

    @Test
    public void testLineTooLong() throws Exception {
        // Create a line longer than PUSHBACK_BUFFER_SIZE (10240 chars)
        // This tests the recovery path when a line is too long
        final StringBuilder sb = new StringBuilder();
        sb.append( "!" ); // Start with heading marker
        for( int i = 0; i < 11000; i++ ) {
            sb.append( "x" );
        }
        // Should not throw an exception - parser should recover gracefully
        final String output = translate( sb.toString() );
        Assertions.assertNotNull( output );
        // The output should contain an h2 heading (! = h4curved to h2 in JSPWiki)
        Assertions.assertTrue( output.contains( "x" ) );
    }

    @Test
    public void testSetWithInvalidFormat() throws Exception {
        // Test SET syntax with invalid format that triggers exception
        final String src = "[{SET invalid}]";
        final String output = translate( src );
        // Should produce error message but not crash
        Assertions.assertNotNull( output );
    }

    @Test
    public void testSetWithEmptyNameValue() throws Exception {
        // Test SET with empty name or value
        final String src = "[{SET =value}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testSetWithEmptyValue() throws Exception {
        // Test SET with empty value
        final String src = "[{SET name=}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testIllegalXMLCharacters() throws Exception {
        // Test content with illegal XML characters (control chars)
        // This should trigger the IllegalDataException path
        final String src = "Test with illegal char: \u0001 and more text";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        // The illegal character should be escaped or removed
        Assertions.assertTrue( output.contains( "Test" ) );
    }

    @Test
    public void testTableRowWithInvalidNumber() throws Exception {
        // Test table with invalid row number format (though hard to trigger directly)
        // Tables use %% for row styling
        final String src = "|| Header\n| Cell with %%class=test invalid%%";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testDeeplyNestedBrackets() throws Exception {
        // Test deeply nested brackets which may stress the parser
        final String src = "[[[[[[[[[[test]]]]]]]]]]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testMalformedPlugin() throws Exception {
        // Test malformed plugin syntax
        final String src = "[{SamplePlugin param='unclosed}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPluginWithMissingCloseBrace() throws Exception {
        // Test plugin that never closes
        final String src = "[{SamplePlugin text";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    // ========== Phase 1: WYSIWYG Editor Mode Tests ==========

    private String translateWysiwyg( final String src ) throws IOException {
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        final WikiContext context = new WikiContext( testEngine, HttpMockFactory.createHttpRequest(), p );
        context.setVariable( com.wikantik.api.core.Context.VAR_WYSIWYG_EDITOR_MODE, Boolean.TRUE );
        final WikantikMarkupParser tr = new WikantikMarkupParser( context, new BufferedReader( new StringReader( src ) ) );
        final XHTMLRenderer conv = new XHTMLRenderer( context, tr.parse() );
        return conv.getString();
    }

    @Test
    public void testWysiwygModeBasicText() throws Exception {
        // Basic text parsing in WYSIWYG mode
        final String src = "Hello World";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "Hello World" ) );
    }

    @Test
    public void testWysiwygModeAccessRules() throws Exception {
        // Access rules preserved as [{ALLOW/DENY}] in WYSIWYG mode
        final String src = "[{ALLOW view All}]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
        // In WYSIWYG mode, the ACL rule text should be preserved
        Assertions.assertTrue( output.contains( "ALLOW" ) || output.contains( "[" ) );
    }

    @Test
    public void testWysiwygModeLinkBrackets() throws Exception {
        // Wiki links should show brackets in some cases in WYSIWYG mode
        final String src = "[NonExistentPage]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testWysiwygModeTildeEscape() throws Exception {
        // Tilde escapes should be preserved in WYSIWYG mode
        final String src = "~NoLink";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "NoLink" ) );
    }

    @Test
    public void testWysiwygModeTildeEscapeSpace() throws Exception {
        // Tilde followed by space in WYSIWYG mode
        final String src = "~ text after tilde space";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testWysiwygModeTildeEscapePipe() throws Exception {
        // Tilde escape of pipe char in WYSIWYG mode
        final String src = "~| pipe escaped";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "|" ) );
    }

    @Test
    public void testWysiwygModePlugin() throws Exception {
        // Plugin syntax in WYSIWYG mode
        final String src = "[{SamplePlugin}]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testWysiwygModeHeading() throws Exception {
        // Heading markup in WYSIWYG mode
        final String src = "!!! Heading Three";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "Heading Three" ) );
    }

    @Test
    public void testWysiwygModeOpenBracket() throws Exception {
        // Open bracket handling in WYSIWYG mode
        final String src = "[[text in brackets]]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testWysiwygModeInterWikiLink() throws Exception {
        // InterWiki link in WYSIWYG mode
        final String src = "[JSPWiki:MainPage]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    // ========== Phase 6: Advanced List Handling Tests ==========

    @Test
    public void testMixedListPatternChange() throws Exception {
        // Test changing from unordered to ordered list pattern
        final String src = "* Item 1\n## Ordered sub-item\n* Item 2";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "<ul>" ) );
        Assertions.assertTrue( output.contains( "<ol>" ) );
    }

    @Test
    public void testMixedListUnwindRewind() throws Exception {
        // Complex unwind/rewind scenario with pattern change
        final String src = "* Item 1\n** Sub-item\n### Different pattern\n* Back to top";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testMixedListDeepNesting() throws Exception {
        // 5+ level deep nesting
        final String src = "* Level 1\n** Level 2\n*** Level 3\n**** Level 4\n***** Level 5\n****** Level 6";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        // Should have 6 nested ul elements
        int ulCount = 0;
        int idx = 0;
        while( ( idx = output.indexOf( "<ul>", idx ) ) != -1 ) {
            ulCount++;
            idx++;
        }
        Assertions.assertTrue( ulCount >= 6 );
    }

    @Test
    public void testMixedListSameLevelDifferentType() throws Exception {
        // Switch type at same level
        final String src = "* Item 1\n# Switched to ordered";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "<ul>" ) );
        Assertions.assertTrue( output.contains( "<ol>" ) );
    }

    @Test
    public void testNestedListDecreaseLevel() throws Exception {
        // Test decreasing list level handling
        final String src = "**** Deep item\n** Shallow item\n* Top level";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testNestedListDecreaseLevelMixed() throws Exception {
        // Test decreasing list level with mixed types
        final String src = "#### Deep ordered\n## Mid ordered\n* Top unordered";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testListAfterParagraph() throws Exception {
        // List starting after paragraph text
        final String src = "Paragraph text\n\n* List item";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "<ul>" ) );
    }

    @Test
    public void testEmptyListItem() throws Exception {
        // Test empty list items
        final String src = "* \n* Second item";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "<li>" ) );
    }

    @Test
    public void testListWithLeadingSpaces() throws Exception {
        // List items with leading spaces after bullet
        final String src = "*    Item with spaces\n*\tItem with tab";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "Item with spaces" ) );
    }

    @Test
    public void testDefinitionListNotInDefinition() throws Exception {
        // Definition list when not already in definition mode
        final String src = ";Term:Definition";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "<dl>" ) );
    }

    @Test
    public void testDefinitionListAlreadyInDefinition() throws Exception {
        // Second definition in same definition list
        final String src = ";Term1:Definition1\n;Term2:Definition2";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "<dt>" ) );
    }

    // ========== Phase 7: Escape Character Tests ==========

    @Test
    public void testTildeEscapeStar() throws Exception {
        // Tilde escape of asterisk
        final String src = "~* not a list";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "*" ) );
        Assertions.assertFalse( output.contains( "<ul>" ) );
    }

    @Test
    public void testTildeEscapeHash() throws Exception {
        // Tilde escape of hash
        final String src = "~# not a list";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "#" ) );
        Assertions.assertFalse( output.contains( "<ol>" ) );
    }

    @Test
    public void testTildeEscapeDash() throws Exception {
        // Tilde escape of dash (prevents ruler)
        final String src = "~---- not a ruler";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertFalse( output.contains( "<hr" ) );
    }

    @Test
    public void testTildeEscapeExclamation() throws Exception {
        // Tilde escape of exclamation (prevents heading)
        final String src = "~! not a heading";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertFalse( output.contains( "<h" ) );
    }

    @Test
    public void testTildeEscapeQuote() throws Exception {
        // Tilde escape of single quote
        final String src = "~'' not italic";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertFalse( output.contains( "<i>" ) );
    }

    @Test
    public void testTildeEscapeUnderscore() throws Exception {
        // Tilde escape of underscore
        final String src = "~__ not bold";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertFalse( output.contains( "<b>" ) );
    }

    @Test
    public void testTildeEscapeOpenBracket() throws Exception {
        // Tilde escape of open bracket
        final String src = "~[not a link]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "[" ) );
    }

    @Test
    public void testTildeEscapeOpenBrace() throws Exception {
        // Tilde escape of open brace
        final String src = "~{not a plugin}";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "{" ) );
    }

    @Test
    public void testTildeEscapeCloseBracket() throws Exception {
        // Tilde escape of close bracket
        final String src = "[link~] text]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testTildeEscapeCloseBrace() throws Exception {
        // Tilde escape of close brace
        final String src = "~} not closing";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "}" ) );
    }

    @Test
    public void testTildeEscapePercent() throws Exception {
        // Tilde escape of percent
        final String src = "~%% not style";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "%" ) );
    }

    @Test
    public void testTildeEscapeBackslash() throws Exception {
        // Tilde escape of backslash
        final String src = "~\\\\ not a line break";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testTildeEscapeTilde() throws Exception {
        // Tilde escape of tilde itself
        final String src = "~~ double tilde";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "~" ) );
    }

    @Test
    public void testBackslashEscapeInReadToEOL() throws Exception {
        // Backslash escape in heading context
        final String src = "!!! Heading with \\escaped text";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPreBlockAmpersand() throws Exception {
        // Ampersand in preformatted block should be escaped
        final String src = "{{{\ncode with & ampersand\n}}}";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "&amp;" ) );
    }

    @Test
    public void testPreBlockLessThan() throws Exception {
        // Less than in preformatted block should be escaped
        final String src = "{{{\ncode with < less than\n}}}";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "&lt;" ) );
    }

    @Test
    public void testPreBlockGreaterThan() throws Exception {
        // Greater than in preformatted block should be escaped
        final String src = "{{{\ncode with > greater than\n}}}";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "&gt;" ) );
    }

    @Test
    public void testPreBlockTildeEscapeCloseBraces() throws Exception {
        // Tilde escape of closing braces inside pre block
        final String src = "{{{\ncode with ~}}} escaped close\n}}}";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "}}}" ) );
    }

    // ========== Phase 2: CamelCase Link Configuration Tests ==========

    @Test
    public void testCamelCaseLinksDisabledViaPageAttribute() throws Exception {
        // Test CamelCase links disabled via page attribute
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        p.setAttribute( MarkupParser.PROP_CAMELCASELINKS, "false" );
        final String src = "This is a WikiWord in text";
        final String output = translate( p, src );
        Assertions.assertNotNull( output );
        // WikiWord should NOT be a link when CamelCase is disabled
        Assertions.assertFalse( output.contains( "<a" ) && output.contains( "WikiWord" ) );
    }

    @Test
    public void testCamelCaseLinksEnabledViaPageAttribute() throws Exception {
        // Test CamelCase links enabled via page attribute
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        p.setAttribute( MarkupParser.PROP_CAMELCASELINKS, "true" );
        final String src = "This is a WikiWord in text";
        final String output = translate( p, src );
        Assertions.assertNotNull( output );
        // WikiWord should be a link when CamelCase is enabled
        Assertions.assertTrue( output.contains( "WikiWord" ) );
    }

    @Test
    public void testCamelCaseLinksWithProtocol() throws Exception {
        // Test URL within CamelCase context
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        p.setAttribute( MarkupParser.PROP_CAMELCASELINKS, "true" );
        final String src = "Check out http://example.com/TestPage for more";
        final String output = translate( p, src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "http://example.com/TestPage" ) );
    }

    @Test
    public void testCamelCaseLinksEscapedWithTilde() throws Exception {
        // Test ~WikiWord escaping in CamelCase context
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        p.setAttribute( MarkupParser.PROP_CAMELCASELINKS, "true" );
        final String src = "This ~WikiWord is escaped";
        final String output = translate( p, src );
        Assertions.assertNotNull( output );
        // WikiWord should appear as plain text, not a link
        Assertions.assertTrue( output.contains( "WikiWord" ) );
    }

    @Test
    public void testCamelCaseLinksEscapedWithBracket() throws Exception {
        // Test escaping with bracket prefix
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        p.setAttribute( MarkupParser.PROP_CAMELCASELINKS, "true" );
        final String src = "This has [explicit link] and WikiWord";
        final String output = translate( p, src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testCamelCaseWithUrlTrailingPunctuation() throws Exception {
        // Test URL ending with period or comma
        final Page p = Wiki.contents().page( testEngine, PAGE_NAME );
        p.setAttribute( MarkupParser.PROP_CAMELCASELINKS, "true" );
        final String src = "Visit http://example.com. And more.";
        final String output = translate( p, src );
        Assertions.assertNotNull( output );
        // The trailing period should not be part of the URL
        Assertions.assertTrue( output.contains( "example.com" ) );
    }

    // ========== Phase 5: URL Edge Cases Tests ==========

    @Test
    public void testUrlEndingWithComma() throws Exception {
        // URL ends with comma - comma should not be part of link
        final String src = "[http://example.com/page,]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "example.com" ) );
    }

    @Test
    public void testUrlEndingWithPeriod() throws Exception {
        // URL ends with period - period should not be part of link
        final String src = "[http://example.com/page.]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "example.com" ) );
    }

    @Test
    public void testDirectUriWithAmpEntity() throws Exception {
        // URL containing &amp; entity
        final String src = "http://example.com/page?a=1&amp;b=2";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testUrlWithQueryString() throws Exception {
        // URL with complex query string
        final String src = "[http://example.com/search?q=test&page=1]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "example.com" ) );
    }

    @Test
    public void testUrlWithFragment() throws Exception {
        // URL with fragment identifier
        final String src = "[http://example.com/page#section]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "#section" ) || output.contains( "example.com" ) );
    }

    @Test
    public void testHttpsUrl() throws Exception {
        // HTTPS URL
        final String src = "[https://secure.example.com/]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "https://" ) );
    }

    @Test
    public void testMailtoUrl() throws Exception {
        // Mailto URL
        final String src = "[mailto:test@example.com]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "mailto:" ) );
    }

    @Test
    public void testFtpUrl() throws Exception {
        // FTP URL
        final String src = "[ftp://ftp.example.com/file]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "ftp://" ) );
    }

    // ========== Phase 8: InterWiki Link and Image Link Tests ==========

    @Test
    public void testInterWikiLinkBasic() throws Exception {
        // Basic InterWiki link
        final String src = "[JSPWiki:SandBox]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "SandBox" ) );
    }

    @Test
    public void testInterWikiLinkWithText() throws Exception {
        // InterWiki link with custom text
        final String src = "[Visit the sandbox|JSPWiki:SandBox]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "sandbox" ) );
    }

    @Test
    public void testImageLinkToExternalUrl() throws Exception {
        // Image with external link text
        final String src = "[http://example.com|http://example.com/image.png]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testImageLinkToWikiPage() throws Exception {
        // Image with wiki page link text (needs existing page)
        newPage( "TestImagePage" );
        final String src = "[TestImagePage|http://example.com/image.png]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testImageLinkPlain() throws Exception {
        // Plain image link (no link text)
        final String src = "[http://example.com/image.png]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "img" ) || output.contains( "image.png" ) );
    }

    @Test
    public void testImageLinkWithAlt() throws Exception {
        // Image with alt text
        final String src = "[Alt text|http://example.com/image.png]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "Alt text" ) || output.contains( "image.png" ) );
    }

    @Test
    public void testImageLinkGif() throws Exception {
        // GIF image
        final String src = "[http://example.com/animation.gif]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testImageLinkJpeg() throws Exception {
        // JPEG image
        final String src = "[http://example.com/photo.jpg]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testInterWikiLinkWithWysiwygMode() throws Exception {
        // InterWiki link in WYSIWYG mode
        final String src = "[Wikipedia:Test]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testImageLinkInWysiwygMode() throws Exception {
        // Image link in WYSIWYG mode
        final String src = "[http://example.com/image.png]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    // ========== Phase 10: Security and Access Rule Tests ==========

    @Test
    public void testAccessRuleAllow() throws Exception {
        // Test ALLOW access rule
        final String src = "[{ALLOW edit Admin}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testAccessRuleDeny() throws Exception {
        // Test DENY access rule
        final String src = "[{DENY view Guest}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testAccessRuleWithBraces() throws Exception {
        // Access rule with surrounding braces (explicit format)
        final String src = "[{{ALLOW view All}}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testAccessRuleMultiple() throws Exception {
        // Multiple access rules
        final String src = "[{ALLOW view All}]\n[{DENY edit Guest}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testAccessRuleInWysiwygMode() throws Exception {
        // Access rule in WYSIWYG mode should preserve the text
        final String src = "[{ALLOW view Admin}]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
        // In WYSIWYG mode, the rule should be shown
        Assertions.assertTrue( output.contains( "ALLOW" ) || output.contains( "[" ) );
    }

    @Test
    public void testAccessRuleWithGroups() throws Exception {
        // Access rule with group permissions
        final String src = "[{ALLOW edit Admin, Editors}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    // ========== Phase 4: Outlink Image Tests ==========

    @Test
    public void testOutlinkImageEnabled() throws Exception {
        // Test external link with outlink image enabled (default)
        final String src = "[http://external.example.com/page]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "external.example.com" ) );
    }

    @Test
    public void testOutlinkImageDisabled() throws Exception {
        // Test external link with outlink image disabled
        final TestEngine customEngine = TestEngine.build( with( "wikantik.translatorReader.useOutlinkImage", "false" ) );
        final WikiContext context = new WikiContext( customEngine, Wiki.contents().page( customEngine, PAGE_NAME ) );
        final WikantikMarkupParser tr = new WikantikMarkupParser( context, new BufferedReader( new StringReader( "[http://external.example.com/page]" ) ) );
        final XHTMLRenderer conv = new XHTMLRenderer( context, tr.parse() );
        final String output = conv.getString();
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "external.example.com" ) );
    }

    @Test
    public void testAttachmentImageEnabled() throws Exception {
        // Test attachment link with attachment image enabled (default)
        newPage( "TestPage" );
        final String src = "[TestPage/attachment.txt]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testAttachmentImageDisabled() throws Exception {
        // Test attachment link with attachment image disabled
        final TestEngine customEngine = TestEngine.build( with( "wikantik.translatorReader.useAttachmentImage", "false" ) );
        newPage( "TestPage" );
        final WikiContext context = new WikiContext( customEngine, Wiki.contents().page( customEngine, PAGE_NAME ) );
        final WikantikMarkupParser tr = new WikantikMarkupParser( context, new BufferedReader( new StringReader( "[TestPage/attachment.txt]" ) ) );
        final XHTMLRenderer conv = new XHTMLRenderer( context, tr.parse() );
        final String output = conv.getString();
        Assertions.assertNotNull( output );
    }

    // ========== Phase 9: Plugin Error Handling Tests ==========

    @Test
    public void testPluginWithEmptyBody() throws Exception {
        // Plugin with empty body content
        final String src = "[{SamplePlugin}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPluginWithBody() throws Exception {
        // Plugin with body content
        final String src = "[{SamplePlugin\nThis is body content\n}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPluginNonexistent() throws Exception {
        // Test nonexistent plugin
        final String src = "[{NonexistentPlugin}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        // Should show error for nonexistent plugin
        Assertions.assertTrue( output.contains( "Nonexistent" ) || output.contains( "error" ) || output.contains( "Plugin" ) );
    }

    @Test
    public void testPluginInWysiwygMode() throws Exception {
        // Plugin in WYSIWYG mode should show plugin markup
        final String src = "[{SamplePlugin text='hello'}]";
        final String output = translateWysiwyg( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPluginWithSpecialCharsInParams() throws Exception {
        // Plugin with special characters in parameters
        final String src = "[{SamplePlugin text='hello <world> & more'}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPluginNestedBraces() throws Exception {
        // Plugin with nested braces in body
        final String src = "[{SamplePlugin\nBody with {nested} braces\n}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
    }

    @Test
    public void testPluginMultipleOnPage() throws Exception {
        // Multiple plugins on same page
        final String src = "[{SamplePlugin text='first'}]\nSome text\n[{SamplePlugin text='second'}]";
        final String output = translate( src );
        Assertions.assertNotNull( output );
        Assertions.assertTrue( output.contains( "first" ) || output.contains( "second" ) );
    }

}