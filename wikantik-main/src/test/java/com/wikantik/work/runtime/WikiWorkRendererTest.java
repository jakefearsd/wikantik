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
package com.wikantik.work.runtime;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.PageTitleLookup;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Work text renders like a page, but display-only: no side effects on pages, references or shared caches. */
class WikiWorkRendererTest {

    private TestEngine engine;
    private WikiWorkRenderer renderer;

    /** Pages the stubbed title index knows: slug to matchable phrases. Mutated mid-test, so lookups are never cached. */
    private final Map< String, List< String > > indexed = new LinkedHashMap<>();
    private final Map< String, String > canonicalIds = new LinkedHashMap<>();
    private final StructuralIndexService index = mock( StructuralIndexService.class );

    @BeforeEach
    void setUp() {
        engine = TestEngine.build( TestEngine.with( "wikantik.translatorReader.matchEnglishPlurals", "true" ),
            TestEngine.with( "wikantik.interWikiRef.img", "https://img.example/%s" ),
            TestEngine.with( "wikantik.interWikiRef.ext", "https://ext.example/%s" ) );
        engine.setManager( StructuralIndexService.class, index );
        renderer = new WikiWorkRenderer( engine );
    }

    @BeforeEach
    void stubIndex() {
        when( index.titleLookup() ).thenAnswer( inv -> {
            final List< PageTitleLookup.TitleEntry > entries = new ArrayList<>();
            indexed.forEach( ( slug, phrases ) -> entries.add( new PageTitleLookup.TitleEntry( slug, slug, phrases ) ) );
            final PageTitleLookup lookup = mock( PageTitleLookup.class );
            when( lookup.entries() ).thenReturn( entries );
            return Optional.of( lookup );
        } );
        when( index.resolveSlugFromCanonicalId( anyString() ) )
            .thenAnswer( inv -> Optional.ofNullable( canonicalIds.get( (String) inv.getArgument( 0 ) ) ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void rendersMarkdown() {
        final String html = renderer.toHtml( "Some **bold** words\n\n- one\n- two\n" );
        assertTrue( html.contains( "<strong>bold</strong>" ), html );
        assertTrue( html.contains( ">one</li>" ), html );
    }

    @Test
    void scriptTagIsNotExecutableOutput() {
        final String html = renderer.toHtml( "hello <script>alert(1)</script> [x](javascript:alert(2))" );
        assertFalse( html.toLowerCase().contains( "<script" ), html );
        assertFalse( html.toLowerCase().contains( "javascript:" ), html );
    }

    @Test
    void aclDirectiveIsInertText() throws Exception {
        engine.saveText( "OpenPage", "anyone can read this" );
        final Page before = engine.getManager( PageManager.class ).getPage( "OpenPage" );
        assertTrue( before.getAcl() == null || before.getAcl().isEmpty() );

        final String html = renderer.toHtml( "Locked down [{ALLOW view Admin}] now" );

        final Page after = engine.getManager( PageManager.class ).getPage( "OpenPage" );
        assertTrue( after.getAcl() == null || after.getAcl().isEmpty(), "no page gained an access rule" );
        assertFalse( html.contains( "ALLOW" ), "the rule is not shown as an active rule: " + html );
        assertTrue( html.contains( "Locked down" ), html );
    }

    @Test
    void wikilinkDoesNotRegisterReference() throws Exception {
        engine.saveText( "SomePage", "exists" );
        final ReferenceManager refs = engine.getManager( ReferenceManager.class );
        final Set< String > referrersBefore = new HashSet<>( refs.findReferrers( "SomePage" ) );
        final Set< String > uncreatedBefore = new HashSet<>( refs.findUncreated() );

        renderer.toHtml( "see [[SomePage]] and [[NoSuchWorkPage]] and [text](OtherPage)" );

        assertEquals( referrersBefore, new HashSet<>( refs.findReferrers( "SomePage" ) ), "SomePage gained no backlink" );
        assertTrue( refs.findReferrers( "NoSuchWorkPage" ) == null || refs.findReferrers( "NoSuchWorkPage" ).isEmpty() );
        assertEquals( uncreatedBefore, new HashSet<>( refs.findUncreated() ) );
        assertTrue( refs.findRefersTo( WikiWorkRenderer.SCRATCH_PAGE ) == null
            || refs.findRefersTo( WikiWorkRenderer.SCRATCH_PAGE ).isEmpty(), "no work source holds references" );
    }

    @Test
    void outputIsNotCached() {
        final CachingManager caches = engine.getManager( CachingManager.class );
        final Set< java.io.Serializable > docs = new HashSet<>( caches.keys( CachingManager.CACHE_DOCUMENTS ) );
        final Set< java.io.Serializable > htmls = new HashSet<>( caches.keys( CachingManager.CACHE_HTML ) );

        final String first = renderer.toHtml( "first body" );
        final String second = renderer.toHtml( "second body" );

        assertTrue( first.contains( "first body" ), first );
        assertTrue( second.contains( "second body" ), second );
        assertNotEquals( first, second );
        assertEquals( docs, new HashSet<>( caches.keys( CachingManager.CACHE_DOCUMENTS ) ), "document cache untouched" );
        assertEquals( htmls, new HashSet<>( caches.keys( CachingManager.CACHE_HTML ) ), "HTML cache untouched" );
    }

    // ---- existence must be invisible: render, create the target, render the SAME text, expect IDENTICAL output ----

    @FunctionalInterface
    private interface Create { void run() throws Exception; }

    private void assertIdenticalOnceTargetExists( final String text, final Create create ) throws Exception {
        final String before = renderer.toHtml( text );
        create.run();
        final String after = renderer.toHtml( text );
        assertEquals( before, after, "creating the target changed how the text renders" );
    }

    @Test
    void exactNameLink() throws Exception {
        assertIdenticalOnceTargetExists( "see [[ExactAlpha]]", () -> engine.saveText( "ExactAlpha", "x" ) );
    }

    @Test
    void differentCaseLink() throws Exception {
        assertIdenticalOnceTargetExists( "see [[casebeta]]", () -> {
            engine.saveText( "CaseBeta", "x" );
            indexed.put( "CaseBeta", List.of( "casebeta" ) );
        } );
    }

    @Test
    void pluralLink() throws Exception {
        assertIdenticalOnceTargetExists( "see [[PluralGammas]]", () -> engine.saveText( "PluralGamma", "x" ) );
    }

    @Test
    void titlePhraseLink() throws Exception {
        assertIdenticalOnceTargetExists( "see [[Secret Merger Plan]]", () -> {
            engine.saveText( "PrivatePlanX", "x" );
            indexed.put( "PrivatePlanX", List.of( "secret merger plan" ) );
        } );
    }

    @Test
    void aliasLink() throws Exception {
        assertIdenticalOnceTargetExists( "see [[Hush Alias]]", () -> {
            engine.saveText( "AliasHolder", "x" );
            indexed.put( "AliasHolder", List.of( "hush alias" ) );
        } );
    }

    @Test
    void wikilinkWithHeading() throws Exception {
        assertIdenticalOnceTargetExists( "see [[HeadPage#Intro]]", () -> engine.saveText( "HeadPage", "# Intro\ntext" ) );
    }

    @Test
    void markdownLinkWithHeading() throws Exception {
        assertIdenticalOnceTargetExists( "see [t](MdHeadPage#intro)",
            () -> engine.saveText( "MdHeadPage", "# Intro\ntext" ) );
    }

    @Test
    void markdownLinkToAPage() throws Exception {
        assertIdenticalOnceTargetExists( "see [t](MdPlain)", () -> engine.saveText( "MdPlain", "x" ) );
    }

    @Test
    void attachmentWikilink() throws Exception {
        assertIdenticalOnceTargetExists( "get [[AttPage/file.pdf]]", () -> {
            engine.saveText( "AttPage", "x" );
            engine.addAttachment( "AttPage", "file.pdf", new byte[] { 1, 2, 3 } );
        } );
    }

    /**
     * The default pipeline answers an {@code x/y} link with an attachment link whether or not the attachment exists,
     * so before/after equality alone would pass with the guard removed; the shape itself is pinned too.
     */
    @Test
    void attachmentMarkdownLink() throws Exception {
        final String shape = renderer.toHtml( "get [doc](AttMdPage/file.pdf)" );
        assertTrue( shape.contains( "class=\"wikipage\"" ) && !shape.contains( "attach" ),
            "drawn as a plain page link, never as an attachment link: " + shape );
        assertIdenticalOnceTargetExists( "get [doc](AttMdPage/file.pdf)", () -> {
            engine.saveText( "AttMdPage", "x" );
            engine.addAttachment( "AttMdPage", "file.pdf", new byte[] { 1, 2, 3 } );
        } );
    }

    @Test
    void imageEmbedOfAnAttachment() throws Exception {
        assertIdenticalOnceTargetExists( "![[ImgPage/img.png]]", () -> {
            engine.saveText( "ImgPage", "x" );
            engine.addAttachment( "ImgPage", "img.png", new byte[] { 1, 2, 3 } );
        } );
    }

    @Test
    void markdownImageOfAnAttachment() throws Exception {
        final String shape = renderer.toHtml( "![alt](ImgMdPage/img.png)" );
        assertFalse( shape.contains( "attach" ), "the image source is not rewritten to an attachment URL: " + shape );
        assertIdenticalOnceTargetExists( "![alt](ImgMdPage/img.png)", () -> {
            engine.saveText( "ImgMdPage", "x" );
            engine.addAttachment( "ImgMdPage", "img.png", new byte[] { 1, 2, 3 } );
        } );
    }

    @Test
    void pageEmbed() throws Exception {
        assertIdenticalOnceTargetExists( "![[EmbedExisting]]", () -> engine.saveText( "EmbedExisting", "secret content" ) );
    }

    @Test
    void pageEmbedInsideAParagraph() throws Exception {
        assertIdenticalOnceTargetExists( "before ![[EmbedInline]] after",
            () -> engine.saveText( "EmbedInline", "secret content" ) );
    }

    /**
     * A page filter that, like the citation filter, rewrites a reference to what exists. The citation filter's own
     * input ({@code href="cite://..."}) does not survive the HTML sanitiser in this engine, so a stand-in with the same
     * effect proves the property that matters: no page filter runs on work text.
     */
    @Test
    void postTranslateFiltersDoNotRunOnWorkText() throws Exception {
        engine.getManager( com.wikantik.filters.FilterManager.class ).addPageFilter( new com.wikantik.api.filters.PageFilter() {
            @Override
            public String postTranslate( final com.wikantik.api.core.Context context, final String html ) {
                return html.replace( "cite-abc", canonicalIds.getOrDefault( "abc", "cite-abc" ) );
            }
        }, -1001 );
        assertIdenticalOnceTargetExists( "see cite-abc here", () -> {
            engine.saveText( "CitedSlugPage", "x" );
            canonicalIds.put( "abc", "CitedSlugPage" );
        } );
    }

    @Test
    void externalImageLinkWhoseTextIsAPageName() throws Exception {
        assertIdenticalOnceTargetExists( "see [ProbeSecretPage](https://example.com/a.png) end",
            () -> engine.saveText( "ProbeSecretPage", "x" ) );
    }

    @Test
    void interwikiImageLinkWhoseTextIsAPageName() throws Exception {
        assertIdenticalOnceTargetExists( "see [ProbeInterPage](img:a.png) end",
            () -> engine.saveText( "ProbeInterPage", "x" ) );
    }

    @Test
    void interwikiLinkWhoseTextIsAPageName() throws Exception {
        assertIdenticalOnceTargetExists( "see [ProbeInterTwo](ext:Somewhere) end",
            () -> engine.saveText( "ProbeInterTwo", "x" ) );
    }

    /** Every link syntax the pipeline supports; link text and targets are names a lookup could match. */
    private static final String CORPUS = String.join( "\n\n",
        "[[Alpha]]", "[[Alpha|alias text]]", "[[Alpha#Heading]]", "[[Alpha#^blockid]]", "[[#Same Heading]]",
        "[[alpha]]", "[[Secret Merger Plan]]", "![[Alpha]]", "![[Alpha#Heading]]", "![[Alpha/file.pdf]]",
        "![[Alpha/img.png]]", "see ![[Alpha]] inline", "[[Alpha/file.pdf]]",
        "[Alpha](Alpha)", "[t](Alpha)", "[t](Alpha#sec)", "[t](Alpha/file.pdf)", "![a](Alpha/img.png)", "![Alpha](Alpha)",
        "[Alpha](https://host/x.png)", "[Alpha](https://host/page)", "[Alpha](http://host/x.gif)",
        "[Alpha](img:x.png)", "[Alpha](ext:Page)", "[Alpha](ext:x.png)", "![Alpha](https://host/x.png)",
        "[1](#fn)", "[1](1)", "<https://host/auto>", "https://host/bare", "www.example.com", "<a@b.com>",
        "[Alpha](./relative)", "[Alpha](/abs/path)", "[Alpha](../up)", "[Alpha](mailto:a@b.com)" );

    /**
     * The structural guard: with links-as-typed on, none of the entry points that ask "does this page, title,
     * heading or attachment exist" is called, whatever the link syntax.
     */
    @Test
    void noExistenceLookupHappensForAnyLinkSyntax() throws Exception {
        final com.wikantik.WikiEngine spied = org.mockito.Mockito.spy( engine );
        final com.wikantik.api.managers.AttachmentManager realAttachments =
            engine.getManager( com.wikantik.api.managers.AttachmentManager.class );
        final com.wikantik.api.managers.AttachmentManager attachments = org.mockito.Mockito.spy( realAttachments );
        spied.setManager( com.wikantik.api.managers.AttachmentManager.class, attachments );
        final PageManager realPages = engine.getManager( PageManager.class );
        final PageManager pages = org.mockito.Mockito.spy( realPages );
        spied.setManager( PageManager.class, pages );
        try {
            final String html = new WikiWorkRenderer( spied ).toHtml( CORPUS );
            assertTrue( html.contains( "alias text" ), "the corpus really rendered: " + html );
            new WikiWorkRenderer( spied ).toHtml( CORPUS + "\n![s](work-attachment:5)\n[l](work-attachment:6)\n", "PLAT-7" );

            org.mockito.Mockito.verify( spied, org.mockito.Mockito.never() ).getFinalPageName( org.mockito.ArgumentMatchers.any() );
            org.mockito.Mockito.verifyNoInteractions( index );
            org.mockito.Mockito.verify( attachments, org.mockito.Mockito.never() ).getAttachmentInfoName(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
            org.mockito.Mockito.verify( attachments, org.mockito.Mockito.never() ).getAttachmentInfo(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
            org.mockito.Mockito.verify( pages, org.mockito.Mockito.never() ).pageExists( org.mockito.ArgumentMatchers.anyString() );
            org.mockito.Mockito.verify( pages, org.mockito.Mockito.never() ).getPage( org.mockito.ArgumentMatchers.anyString() );
        } finally {
            spied.setManager( com.wikantik.api.managers.AttachmentManager.class, realAttachments );
            spied.setManager( PageManager.class, realPages );
        }
    }

    /** The same corpus, extended to the equality form: create a page named after every link's text and target. */
    @Test
    void wholeCorpusRendersIdenticallyOncePagesExist() throws Exception {
        assertIdenticalOnceTargetExists( CORPUS, () -> {
            for ( final String name : List.of( "Alpha", "ProbeX", "Somewhere" ) ) {
                engine.saveText( name, "# Heading\ntext ^blockid" );
            }
            engine.addAttachment( "Alpha", "file.pdf", new byte[] { 1 } );
            engine.addAttachment( "Alpha", "img.png", new byte[] { 1 } );
            indexed.put( "Alpha", List.of( "alpha", "secret merger plan" ) );
        } );
    }

    @Test
    void renderedLinksStillLookLikeLinks() {
        final String html = renderer.toHtml( "see [[Some Page]] and [t](Other)" );
        assertTrue( html.contains( "class=\"wikipage\"" ), html );
        assertTrue( html.contains( ">Some Page</a>" ), html );
        assertTrue( html.contains( ">t</a>" ), html );
    }

    /** A service whose comments render through {@code renderWith}; the store hands back what it is given. */
    private com.wikantik.work.WorkService commentService( final com.wikantik.WikiEngine renderWith ) throws Exception {
        final com.wikantik.work.WorkStore store = mock( com.wikantik.work.WorkStore.class );
        final java.time.Instant now = java.time.Instant.parse( "2026-11-15T12:00:00Z" );
        final com.wikantik.work.Project plat = new com.wikantik.work.Project( 1, "PLAT", "Platform", "",
            com.wikantik.work.Autonomy.DELEGATED, now, null, null );
        final com.wikantik.work.Item item = new com.wikantik.work.Item( 12, 1, "PLAT-1", com.wikantik.work.WorkLevel.TASK,
            null, null, com.wikantik.work.WorkKind.SOFTWARE, "t", "", com.wikantik.work.WorkStatus.DRAFT, 3, 1, null,
            null, "janne", null, null, null, 1, now, now, null,
            null, null, null, null, null, null, null, null, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.item( "PLAT-1" ) ).thenReturn( Optional.of( item ) );
        when( store.roleFor( org.mockito.ArgumentMatchers.eq( 1L ), org.mockito.ArgumentMatchers.any() ) )
            .thenReturn( Optional.of( com.wikantik.work.WorkRole.MEMBER ) );
        when( store.addComment( org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any( com.wikantik.work.Actor.class ), anyString(), org.mockito.ArgumentMatchers.anyList() ) ).thenAnswer( inv -> new com.wikantik.work.Comment(
                1, 12, "janne", inv.getArgument( 2 ), now, null, null, null ) );
        return new com.wikantik.work.WorkService( store, new WikiWorkRenderer( renderWith ),
            new WikiWorkDirectory( engine ), java.time.Clock.systemUTC() );
    }

    private static final com.wikantik.work.Actor JANNE =
        com.wikantik.work.Actor.human( "janne", Set.of(), false );

    /** Comment text gets the item text's guard: no existence lookup for any link syntax. */
    @Test
    void commentTextDoesNoExistenceLookupEither() throws Exception {
        final com.wikantik.WikiEngine spied = org.mockito.Mockito.spy( engine );
        final PageManager realPages = engine.getManager( PageManager.class );
        final PageManager pages = org.mockito.Mockito.spy( realPages );
        spied.setManager( PageManager.class, pages );
        try {
            final String html = commentService( spied ).addComment( JANNE, "PLAT-1", CORPUS ).bodyHtml();
            assertTrue( html.contains( "alias text" ), "the comment really rendered: " + html );
            org.mockito.Mockito.verify( spied, org.mockito.Mockito.never() ).getFinalPageName( org.mockito.ArgumentMatchers.any() );
            org.mockito.Mockito.verifyNoInteractions( index );
            org.mockito.Mockito.verify( pages, org.mockito.Mockito.never() ).pageExists( anyString() );
            org.mockito.Mockito.verify( pages, org.mockito.Mockito.never() ).getPage( anyString() );
        } finally {
            spied.setManager( PageManager.class, realPages );
        }
    }

    @Test
    void commentTextIsSanitisedAndAMentionNameCannotBecomeMarkup() throws Exception {
        final com.wikantik.work.CommentView v = commentService( engine ).addComment( JANNE, "PLAT-1",
            "hello <script>alert(1)</script> [x](javascript:alert(2)) @[<img src=x onerror=alert(3)>](user:gone) **ok**" );
        final String html = v.bodyHtml().toLowerCase();
        assertFalse( html.contains( "<script" ), html );
        assertFalse( html.contains( "javascript:" ), html );
        assertFalse( html.contains( "<img" ), html );
        assertTrue( html.contains( "<strong>ok</strong>" ), "comments are Markdown: " + html );
    }

    @Test
    void textThatIsAbsentRendersAsNothing() {
        assertEquals( "", renderer.toHtml( null ).trim() );
    }

    private String base() {
        return engine.getBaseURL();
    }

    @Test
    void anAttachmentImageBecomesTheDownloadRoute() {
        final String html = renderer.toHtml( "![shot.png](work-attachment:5)", "PLAT-7" );
        assertTrue( html.contains( "src=\"" + base() + "/api/work/items/PLAT-7/attachments/5\"" ), html );
        assertTrue( html.contains( "alt=\"shot.png\"" ), html );
    }

    @Test
    void anAttachmentLinkBecomesAPlainLinkToTheRoute() {
        final String html = renderer.toHtml( "[log.txt](work-attachment:6)", "PLAT-7" );
        assertTrue( html.contains( "<a href=\"" + base() + "/api/work/items/PLAT-7/attachments/6\">log.txt</a>" ), html );
        assertFalse( html.contains( "error" ), html );
        assertFalse( html.contains( "interwiki" ), html );
    }

    @Test
    void aTraversalTailIsLeftAsTyped() {
        final String html = renderer.toHtml( "[a](work-attachment:5/../6) ![b](work-attachment:../../x)", "PLAT-7" );
        assertFalse( html.contains( "/api/work/" ), html );
    }

    @Test
    void aTokenInCodeIsNotRewritten() {
        final String html = renderer.toHtml( "`work-attachment:5`\n\n```\nwork-attachment:5\n```\n", "PLAT-7" );
        assertTrue( html.contains( "work-attachment:5" ), html );
        assertFalse( html.contains( "/api/work/" ), html );
    }

    @Test
    void theSameTextRendersForEachItemsOwnKey() {
        final String text = "[f](work-attachment:5)";
        final String one = renderer.toHtml( text, "PLAT-7" );
        final String two = renderer.toHtml( text, "OPS-2" );
        assertTrue( one.contains( "/items/PLAT-7/attachments/5" ) && !one.contains( "OPS-2" ), one );
        assertTrue( two.contains( "/items/OPS-2/attachments/5" ) && !two.contains( "PLAT-7" ), two );
    }

    @Test
    void withoutAKeyNothingIsRewritten() {
        assertFalse( renderer.toHtml( "[f](work-attachment:5)" ).contains( "/api/work/" ) );
        assertFalse( renderer.toHtml( "[f](work-attachment:5)", null ).contains( "/api/work/" ) );
        assertFalse( renderer.toHtml( "[f](work-attachment:5)", "not a key" ).contains( "/api/work/" ) );
    }

    @Test
    void anotherUrlCannotBeSmuggledThroughTheToken() {
        final String html = renderer.toHtml( "[f](work-attachment:5\" onmouseover=\"x) [g](https://evil.example/work-attachment:5)",
            "PLAT-7" );
        assertFalse( html.contains( "onmouseover=\"x" ), html );
        assertFalse( html.contains( "/items/PLAT-7/attachments/5\" o" ), html );
    }
}
