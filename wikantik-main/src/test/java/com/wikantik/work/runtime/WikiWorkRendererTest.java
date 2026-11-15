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
        engine = TestEngine.build( TestEngine.with( "wikantik.translatorReader.matchEnglishPlurals", "true" ) );
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
        engine.saveText( "AttPage", "x" );
        assertIdenticalOnceTargetExists( "get [[AttPage/file.pdf]]",
            () -> engine.addAttachment( "AttPage", "file.pdf", new byte[] { 1, 2, 3 } ) );
    }

    @Test
    void attachmentMarkdownLink() throws Exception {
        engine.saveText( "AttMdPage", "x" );
        assertIdenticalOnceTargetExists( "get [doc](AttMdPage/file.pdf)",
            () -> engine.addAttachment( "AttMdPage", "file.pdf", new byte[] { 1, 2, 3 } ) );
    }

    @Test
    void imageEmbedOfAnAttachment() throws Exception {
        engine.saveText( "ImgPage", "x" );
        assertIdenticalOnceTargetExists( "![[ImgPage/img.png]]",
            () -> engine.addAttachment( "ImgPage", "img.png", new byte[] { 1, 2, 3 } ) );
    }

    @Test
    void markdownImageOfAnAttachment() throws Exception {
        engine.saveText( "ImgMdPage", "x" );
        assertIdenticalOnceTargetExists( "![alt](ImgMdPage/img.png)",
            () -> engine.addAttachment( "ImgMdPage", "img.png", new byte[] { 1, 2, 3 } ) );
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
    void renderedLinksStillLookLikeLinks() {
        final String html = renderer.toHtml( "see [[Some Page]] and [t](Other)" );
        assertTrue( html.contains( "class=\"wikipage\"" ), html );
        assertTrue( html.contains( ">Some Page</a>" ), html );
        assertTrue( html.contains( ">t</a>" ), html );
    }
}
