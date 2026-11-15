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

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Work text renders like a page, but display-only: no side effects on pages, references or shared caches. */
class WikiWorkRendererTest {

    private TestEngine engine;
    private WikiWorkRenderer renderer;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        renderer = new WikiWorkRenderer( engine );
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

    @Test
    void linkToMissingPageRendersLikeExistingPage() throws Exception {
        engine.saveText( "RealWorkTarget", "I exist" );
        final String existing = renderer.toHtml( "see [[RealWorkTarget]] and [t](RealWorkTarget)" );
        final String missing = renderer.toHtml( "see [[GhostWorkTarget]] and [t](GhostWorkTarget)" );

        assertEquals( existing.replace( "RealWorkTarget", "X" ), missing.replace( "GhostWorkTarget", "X" ),
            "existence of a page must not be visible in the markup" );
    }

    @Test
    void embedsAreDrawnAsPlainLinksSoTheyRevealNothing() throws Exception {
        engine.saveText( "EmbedHost", "secret page content" );
        final String existing = renderer.toHtml( "![[EmbedHost]]" );
        final String missing = renderer.toHtml( "![[EmbedGhost]]" );
        assertFalse( existing.contains( "secret page content" ), existing );
        assertEquals( existing.replace( "EmbedHost", "X" ), missing.replace( "EmbedGhost", "X" ) );
    }

    @Test
    void embedMarkerInsideAFenceIsLeftAlone() {
        assertEquals( "```\n![[Keep]]\n```\n![x]", WikiWorkRenderer.demoteEmbeds( "```\n![[Keep]]\n```\n![x]" ) );
        assertEquals( "see [[Page]]", WikiWorkRenderer.demoteEmbeds( "see ![[Page]]" ) );
    }

    @Test
    void lowerCasedLinkRendersTheSameForExistingAndMissingPages() throws Exception {
        engine.saveText( "CaseTarget", "I exist" );
        final String existing = renderer.toHtml( "[[casetarget]]" );
        final String missing = renderer.toHtml( "[[caseghost]]" );
        assertEquals( existing.replaceAll( "(?i)casetarget", "X" ), missing.replaceAll( "(?i)caseghost", "X" ) );
    }
}
