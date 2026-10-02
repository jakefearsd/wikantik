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
package com.wikantik.markdown.extensions.nativelinks;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeWikiLinkRenderingTest {

    private static final String HOST = "WlRenderHost";

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.translatorReader.matchEnglishPlurals", "true" ),
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    @Test void existingPageLink() throws Exception {
        newPage( "WlTarget" );
        assertEquals( "<p>See <a href=\"/test/wiki/WlTarget\" class=\"wikipage\">WlTarget</a></p>\n", translate( "See [[WlTarget]]" ) );
    }

    @Test void aliasAndHeadingUseTheViewSlug() throws Exception {
        newPage( "WlTarget" );
        assertEquals( "<p><a href=\"/test/wiki/WlTarget#my-heading\" class=\"wikipage\">go</a></p>\n",
                translate( "[[WlTarget#My Heading|go]]" ) );
        assertEquals( "<p><a href=\"/test/wiki/WlTarget#my-heading\" class=\"wikipage\">WlTarget &gt; My Heading</a></p>\n",
                translate( "[[WlTarget#My Heading]]" ) );
    }

    @Test void samePageHeading() throws Exception {
        assertEquals( "<p><a href=\"#intro-part\" class=\"wikipage\">Intro Part</a></p>\n", translate( "[[#Intro Part]]" ) );
    }

    @Test void missingPageIsACreateLink() throws Exception {
        assertEquals( "<p><a href=\"/test/edit/NoSuchWl\" title=\"Create &#34;NoSuchWl&#34;\" class=\"createpage\">NoSuchWl</a></p>\n",
                translate( "[[NoSuchWl]]" ) );
    }

    @Test void leadingWhitespaceStaysLiteral() throws Exception {
        final String html = translate( "if [[ -f \"$x\" ]]; then" );
        assertFalse( html.contains( "<a" ), html );
        assertTrue( html.contains( "[[ -f" ), html );
    }

    @Test void codeIsNeverALink() throws Exception {
        assertFalse( translate( "`[[WlTarget]]`\n\n```\n[[WlTarget]]\n```\n" ).contains( "<a" ) );
    }

    @Test void tableCellEscapedPipe() throws Exception {
        newPage( "WlTarget" );
        final String html = translate( "| a |\n|---|\n| [[WlTarget\\|cell]] |\n" );
        assertTrue( html.contains( "<a href=\"/test/wiki/WlTarget\" class=\"wikipage\">cell</a>" ), html );
    }

    @Test void legacyMarkdownLinksAreUnchanged() throws Exception {
        newPage( "WlTarget" );
        assertEquals( "<p><a href=\"/test/wiki/WlTarget\" class=\"wikipage\">x</a></p>\n", translate( "[x](WlTarget)" ) );
    }

    @Test void attachmentLink() throws Exception {
        newPage( "WlHost" );
        attach( "WlHost", "doc.pdf" );
        assertEquals( "<p><a href=\"/test/attach/WlHost/doc.pdf\" class=\"attachment\">WlHost/doc.pdf</a></p>\n",
                translate( "[[WlHost/doc.pdf]]" ) );
    }

    @Test void attachmentImageEmbedWithWidth() throws Exception {
        newPage( "WlHost" );
        attach( "WlHost", "pic.png" );
        assertEquals( "<p><img class=\"inline\" src=\"/test/attach/WlHost/pic.png\" alt=\"pic.png\" width=\"300\" /></p>\n",
                translate( "![[WlHost/pic.png|300]]" ) );
    }

    @Test void attachmentImageEmbedOnCurrentPage() throws Exception {
        newPage( HOST );
        attach( HOST, "pic.png" );
        final String html = translate( "![[pic.png]]" );
        assertTrue( html.contains( "<img class=\"inline\" src=\"/test/attach/" + HOST + "/pic.png\" alt=\"pic.png\"" ), html );
    }

    @Test void missingAttachmentEmbed() throws Exception {
        assertEquals( "<p><span class=\"wiki-embed-missing\">f.png</span></p>\n", translate( "![[WlHost/f.png]]" ) );
    }

    @Test void missingAttachmentLinkIsAMissingPageLink() throws Exception {
        final String html = translate( "[[WlHost/f.pdf]]" );
        assertTrue( html.contains( "class=\"createpage\"" ), html );
    }

    private void attach( final String owner, final String file ) throws Exception {
        final Attachment att = Wiki.contents().attachment( testEngine, owner, file );
        att.setAuthor( "FirstPost" );
        testEngine.getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() );
    }

    private String translate( final String src ) throws Exception {
        testEngine.saveText( HOST, src );
        final Page p = Wiki.contents().page( testEngine, HOST );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        final MarkdownParser tr = new MarkdownParser( context, new BufferedReader( new StringReader( src ) ) );
        return new MarkdownRenderer( context, tr.parse() ).getString();
    }

    private void newPage( final String name ) throws Exception {
        testEngine.saveText( name, "<test>" );
    }
}
