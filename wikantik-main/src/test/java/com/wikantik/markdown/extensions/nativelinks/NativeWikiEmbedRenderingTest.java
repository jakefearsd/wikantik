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
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.auth.Users;
import jakarta.servlet.http.HttpServletRequest;
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

    @Test void embeddingARestrictedPageDoesNotChangeHostAcl() throws Exception {
        engine.saveText( "EmbHostPlain", "intro\n\n![[EmbSecret]]\n" );
        engine.saveText( "EmbSecret", "[{ALLOW view Admin}]\n\nclassified\n" );
        final PermissionFilter pf = new PermissionFilter( engine );
        final Session guest = WikiSessionTest.anonymousSession( engine );
        assertTrue( pf.canAccessQuietly( guest, "EmbHostPlain", "view" ) );

        final String html = renderHostAsAdmin( "EmbHostPlain" );

        assertTrue( html.contains( "classified" ), "admin sees the embedded body: " + html );
        assertTrue( pf.canAccessQuietly( guest, "EmbHostPlain", "view" ),
                "rendering an embed must not write the embedded page's ACL onto the host" );
    }

    @Test void embeddingAnOpenPageCannotWidenHostAcl() throws Exception {
        engine.saveText( "EmbHostLocked", "[{ALLOW view Admin}]\n\n![[EmbOpen]]\n" );
        engine.saveText( "EmbOpen", "[{ALLOW view All}]\n\nopen text\n" );
        final PermissionFilter pf = new PermissionFilter( engine );
        final Session guest = WikiSessionTest.anonymousSession( engine );
        assertFalse( pf.canAccessQuietly( guest, "EmbHostLocked", "view" ) );

        renderHostAsAdmin( "EmbHostLocked" );

        assertFalse( pf.canAccessQuietly( guest, "EmbHostLocked", "view" ),
                "an embedded page's ACL must not widen the host's ACL" );
    }

    // ----- F2: render-time embeds, budget, plugin-off, same-page, containers -----

    @Test void parsingAnEmbedRendersNothingAndLeavesTheContextCacheable() throws Exception {
        engine.saveText( "EmbBody", "x\n" );
        final Context ctx = hostContext();
        new MarkdownParser( ctx, new BufferedReader( new StringReader( "![[EmbBody]]\n" ) ) ).parse();
        assertNull( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ),
                "a parse (metadata refresh, reference scan, cached document) must not render the embed" );
    }

    @Test void aDocumentParsedOnceIsRenderedPerViewer() throws Exception {
        engine.saveText( "EmbHostShared", "intro\n\n![[EmbSecretShared]]\n" );
        engine.saveText( "EmbSecretShared", "[{ALLOW view Admin}]\n\nclassified\n" );
        final Context admin = adminContext( "EmbHostShared" );
        final com.wikantik.parser.WikiDocument doc = new MarkdownParser( admin, new BufferedReader(
                new StringReader( engine.getManager( PageManager.class ).getPureText( "EmbHostShared", -1 ) ) ) ).parse();

        final String adminHtml = new MarkdownRenderer( admin, doc ).getString();
        final String guestHtml = new MarkdownRenderer( guestContext( "EmbHostShared" ), doc ).getString();

        assertTrue( adminHtml.contains( "classified" ), adminHtml );
        assertFalse( guestHtml.contains( "classified" ), "a cached document must not carry the parser's view: " + guestHtml );
        assertTrue( guestHtml.contains( "wiki-embed-restricted" ), guestHtml );
    }

    @Test void thirtySiblingEmbedsRenderTwentyFiveBlocksAndFiveLinks() throws Exception {
        final StringBuilder src = new StringBuilder();
        for ( int i = 0; i < 30; i++ ) {
            engine.saveText( "EmbFan" + i, "fan-body-" + i + "\n" );
            src.append( "![[EmbFan" ).append( i ).append( "]]\n" );
        }
        final String html = translate( src.toString() );
        assertEquals( 25, count( html, "class=\"wiki-embed\"" ), html );
        assertEquals( 25, count( html, "fan-body-" ), html );
        for ( int i = 25; i < 30; i++ ) {
            assertTrue( html.contains( "<a href=\"/test/wiki/EmbFan" + i + "\" class=\"wikipage\">EmbFan" + i + "</a>" ), html );
        }
    }

    @Test void nestedFanOutIsBoundedByTheBudget() throws Exception {
        engine.saveText( "EmbNestLeaf", "nest-leaf\n" );
        final StringBuilder host = new StringBuilder();
        for ( int m = 0; m < 6; m++ ) {
            engine.saveText( "EmbNestMid" + m, "![[EmbNestLeaf]]\n".repeat( 10 ) );
            host.append( "![[EmbNestMid" ).append( m ).append( "]]\n" );
        }
        assertEquals( 25, count( translate( host.toString() ), "class=\"wiki-embed\"" ) );
    }

    @Test void repeatedFanOutCannotMultiplyTheOutput() throws Exception {
        engine.saveText( "EmbBoomR", "boom-leaf\n" );
        engine.saveText( "EmbBoomQ", "![[EmbBoomR]]\n".repeat( 30 ) );
        final String html = translate( "![[EmbBoomQ]]\n".repeat( 30 ) );
        assertEquals( 25, count( html, "class=\"wiki-embed\"" ), html );
        assertTrue( html.contains( "<a href=\"/test/wiki/EmbBoomQ\" class=\"wikipage\">EmbBoomQ</a>" ), html );
    }

    @Test void repeatedEmbedsOfOnePageAllRender() throws Exception {
        engine.saveText( "EmbRepeat", "repeat-body\n" );
        final String html = translate( "![[EmbRepeat]]\n![[EmbRepeat]]\n![[EmbRepeat]]\n" );
        assertEquals( 3, count( html, "repeat-body" ), html );
    }

    @Test void withPluginsDisabledAnEmbedIsAPlainLink() throws Exception {
        engine.saveText( "EmbBody", "Embedded text.\n" );
        engine.saveText( HOST, "![[EmbBody]]\n" );
        final Context ctx = hostContext();
        ctx.setVariable( Context.VAR_EXECUTE_PLUGINS, Boolean.FALSE );
        final String html = render( ctx, "![[EmbBody]]\n" );
        assertFalse( html.contains( "wiki-embed" ), html );
        assertFalse( html.contains( "Embedded text" ), html );
        assertTrue( html.contains( "<a href=\"/test/wiki/EmbBody\" class=\"wikipage\">EmbBody</a>" ), html );
        assertNull( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
    }

    @Test void samePageEmbedIsTheSameAnchorLinkAsASamePageLink() throws Exception {
        final String link = translate( "Intro.\n\n[[#Usage]]\n\n## Usage\n\nRun.\n" );
        final String embed = translate( "Intro.\n\n![[#Usage]]\n\n## Usage\n\nRun.\n" );
        assertFalse( embed.contains( "loop" ), embed );
        assertTrue( embed.contains( "href=\"#usage\"" ), embed );
        assertEquals( link, embed );
    }

    @Test void embedInsideACalloutRendersAsABlockInTheCallout() throws Exception {
        engine.saveText( "EmbBody", "Embedded **text**.\n" );
        final String html = translate( "> [!note]\n> ![[EmbBody]]\n" );
        assertTrue( html.contains( "callout" ), html );
        final int callout = html.indexOf( "callout" );
        final int block = html.indexOf( "<div class=\"wiki-embed\" data-embed=\"EmbBody\">" );
        assertTrue( block > callout, html );
        assertTrue( html.contains( "<strong>text</strong>" ), html );
        assertFalse( html.contains( "<p><div" ), html );
    }

    @Test void embedInsideAListItemRendersAsABlockInTheItem() throws Exception {
        engine.saveText( "EmbBody", "Embedded **text**.\n" );
        final String html = translate( "- first\n- ![[EmbBody]]\n- last\n" );
        final int item = html.indexOf( "<li>", html.indexOf( "first" ) );
        final int block = html.indexOf( "<div class=\"wiki-embed\" data-embed=\"EmbBody\">" );
        assertTrue( item >= 0 && block > item && block < html.indexOf( "last" ), html );
        assertTrue( html.contains( "<strong>text</strong>" ), html );
    }

    private static int count( final String haystack, final String needle ) {
        int n = 0;
        for ( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + needle.length() ) ) {
            n++;
        }
        return n;
    }

    private Context adminContext( final String name ) throws Exception {
        final HttpServletRequest request = ownSessionRequest( "acl-admin-" );
        final Session admin = com.wikantik.api.spi.Wiki.session().find( engine, request );
        engine.getManager( AuthenticationManager.class ).login( admin, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( admin.isAuthenticated() );
        return Wiki.context().create( engine, request, engine.getManager( PageManager.class ).getPage( name ) );
    }

    /** A never-logged-in viewer (the shared mock session is polluted by saveText's login). */
    private Context guestContext( final String name ) {
        final HttpServletRequest request = ownSessionRequest( "guest-" );
        assertFalse( com.wikantik.api.spi.Wiki.session().find( engine, request ).isAuthenticated() );
        return Wiki.context().create( engine, request, engine.getManager( PageManager.class ).getPage( name ) );
    }

    private static HttpServletRequest ownSessionRequest( final String idPrefix ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final jakarta.servlet.http.HttpSession http = org.mockito.Mockito.mock( jakarta.servlet.http.HttpSession.class );
        org.mockito.Mockito.doReturn( idPrefix + System.nanoTime() ).when( http ).getId();
        org.mockito.Mockito.doReturn( http ).when( request ).getSession();
        return request;
    }

    private String renderHostAsAdmin( final String name ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final jakarta.servlet.http.HttpSession http = org.mockito.Mockito.mock( jakarta.servlet.http.HttpSession.class );
        org.mockito.Mockito.doReturn( "acl-admin-" + System.nanoTime() ).when( http ).getId();
        org.mockito.Mockito.doReturn( http ).when( request ).getSession();
        final Session admin = com.wikantik.api.spi.Wiki.session().find( engine, request );
        engine.getManager( AuthenticationManager.class ).login( admin, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( admin.isAuthenticated() );
        final Page host = engine.getManager( PageManager.class ).getPage( name );
        final Context ctx = Wiki.context().create( engine, request, host );
        return render( ctx, engine.getManager( PageManager.class ).getPureText( name, -1 ) );
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
