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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeWikiEmbedRenderingTest {

    private static final String HOST = "EmbHost";

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.translatorReader.matchEnglishPlurals", "true" ),
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test void standaloneEmbedBecomesABlock() throws Exception {
        engine.saveText( "EmbBody", "Embedded **text**.\n" );
        final String html = translate( "Before.\n\n![[EmbBody]]\n\nAfter.\n" );
        assertTrue( html.contains( "<div class=\"wiki-embed\" data-embed=\"EmbBody\">" ), html );
        assertTrue( html.contains( "<strong>text</strong>" ), html );
        assertFalse( html.contains( "<p><div" ), html );
    }

    @Test void consecutiveEmbedLinesBecomeConsecutiveBlocks() throws Exception {
        engine.saveText( "EmbA", "aaa\n" );
        engine.saveText( "EmbB", "bbb\n" );
        final String html = translate( "![[EmbA]]\n![[EmbB]]\n" );
        assertTrue( html.contains( "data-embed=\"EmbA\"" ), html );
        assertTrue( html.indexOf( "data-embed=\"EmbA\"" ) < html.indexOf( "data-embed=\"EmbB\"" ), html );
    }

    @Test void inlineEmbedRendersAsALink() throws Exception {
        engine.saveText( "EmbBody", "x\n" );
        assertTrue( translate( "see ![[EmbBody]] here" ).contains( "class=\"wikipage\"" ) );
    }

    @Test void selfEmbedStopsTheLoop() throws Exception {
        engine.saveText( "EmbSelf", "![[EmbSelf]]\n" );
        assertTrue( renderPage( "EmbSelf" ).contains( "Embed loop stopped" ) );
    }

    @Test void mutualEmbedsStopTheLoop() throws Exception {
        engine.saveText( "EmbMutA", "A body\n\n![[EmbMutB]]\n" );
        engine.saveText( "EmbMutB", "B body\n\n![[EmbMutA]]\n" );
        final String html = renderPage( "EmbMutA" );
        assertTrue( html.contains( "B body" ), html );
        assertTrue( html.contains( "Embed loop stopped" ), html );
    }

    @Test void depthIsCappedAtThree() throws Exception {
        for ( int i = 1; i <= 4; i++ ) {
            engine.saveText( "EmbDepth" + i, "level" + i + "\n\n![[EmbDepth" + ( i + 1 ) + "]]\n" );
        }
        engine.saveText( "EmbDepth5", "level5\n" );
        final String html = renderPage( "EmbDepth1" );
        assertTrue( html.contains( "level4" ), html );
        assertFalse( html.contains( "level5" ), html );
        assertTrue( html.contains( "Embed loop stopped" ), html );
    }

    @Test void aRenderWithAnEmbedIsFlaggedViewerSensitive() throws Exception {
        engine.saveText( "EmbBody", "x\n" );
        final Context ctx = hostContext();
        render( ctx, "![[EmbBody]]\n" );
        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
    }

    @Test void aRenderWithOnlyLinksIsNotViewerSensitive() throws Exception {
        final Context ctx = hostContext();
        render( ctx, "[[EmbBody]]\n" );
        assertNull( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
    }

    @Test void attachmentUrlWithSpecialCharactersIsEscaped() throws Exception {
        engine.saveText( HOST, "x" );
        final Attachment att = Wiki.contents().attachment( engine, HOST, "a&b\"c.png" );
        att.setAuthor( "FirstPost" );
        engine.getManager( AttachmentManager.class ).storeAttachment( att, engine.makeAttachmentFile() );
        final String html = translate( "![[" + HOST + "/a&b\"c.png]]" );
        assertFalse( html.contains( "src=\"/test/attach/EmbHost/a&b\"" ), html );
        assertTrue( html.contains( "<img" ), html );
        assertFalse( html.matches( "(?s).*src=\"[^\"]*\"[^\"]*c\\.png\".*" ), html );
    }

    @Test void wikilinkInsideACalloutIsANativeLinkAndTheCalloutSurvives() throws Exception {
        engine.saveText( "EmbCalloutTarget", "x\n" );
        final String html = translate( "> [!note]\n> see [[EmbCalloutTarget]]\n" );
        assertTrue( html.contains( "callout" ), html );
        assertTrue( html.contains( "<a href=\"/test/wiki/EmbCalloutTarget\" class=\"wikipage\">EmbCalloutTarget</a>" ), html );
    }

    @Test void wikilinkInsideAFootnoteIsANativeLink() throws Exception {
        engine.saveText( "EmbFootTarget", "x\n" );
        final String html = translate( "Text[^1]\n\n[^1]: see [[EmbFootTarget]]\n" );
        assertTrue( html.contains( "<a href=\"/test/wiki/EmbFootTarget\" class=\"wikipage\">EmbFootTarget</a>" ), html );
    }

    private Context hostContext() throws Exception {
        return contextFor( HOST );
    }

    private Context contextFor( final String name ) throws Exception {
        final Page p = Wiki.contents().page( engine, name );
        return Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), p );
    }

    private String render( final Context context, final String src ) throws Exception {
        final MarkdownParser tr = new MarkdownParser( context, new BufferedReader( new StringReader( src ) ) );
        return new MarkdownRenderer( context, tr.parse() ).getString();
    }

    private String renderPage( final String name ) throws Exception {
        return render( contextFor( name ), engine.getManager( com.wikantik.api.managers.PageManager.class ).getPureText( name, -1 ) );
    }

    private String translate( final String src ) throws Exception {
        engine.saveText( HOST, src );
        return render( hostContext(), src );
    }
}
