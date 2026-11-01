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
package com.wikantik.its;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openqa.selenium.By;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Obsidian block references in a real browser: the reader's copy-block-link button assigns an id and copies
 * {@code [[Page#^id]]}; a {@code #^id} URL scrolls to the block and flashes it; and {@code [[Page#^} completion in the
 * editor inserts a block link (in every {@link EditorMode}; the reader-side tests have no editor and run once).
 *
 * <p>Headless Chrome has no system clipboard, so a shim installed before the click records what the page writes
 * (both the {@code writeText} and the {@code ClipboardItem} paths) into {@code window.__copied}.</p>
 */
public class BlockRefsIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 30 );
    private static final long TS = System.currentTimeMillis();
    private static final String COPY = "BlockCopy" + TS;
    private static final String LAND = "BlockLand" + TS;
    private static final String SOURCE = "BlockSource" + TS;

    @BeforeAll
    static void seed() throws Exception {
        RestSeedHelper.writePage( COPY, "First block of copy page\n\nSecond block of copy page\n" );
        final StringBuilder filler = new StringBuilder();
        for ( int i = 0; i < 80; i++ ) {
            filler.append( "Filler paragraph number " ).append( i ).append( "\n\n" );
        }
        RestSeedHelper.writePage( LAND, filler + "Landing paragraph ^land1\n\nTrailing paragraph\n" );
        RestSeedHelper.writePage( SOURCE, "Allocation strategy notes ^alloc1\n\nUnrelated closing paragraph\n" );
    }

    @AfterAll
    static void cleanUp() {
        for ( final String n : new String[] { COPY, LAND, SOURCE } ) {
            RestSeedHelper.deletePageQuietly( n );
        }
    }

    @Test
    void copyButtonAssignsAnIdAndCopiesTheBlockLink() {
        ViewWikiPage.open( COPY );
        final SelenideElement block = $( By.xpath( "//*[@data-block][contains(normalize-space(.),'Second block of copy page')]" ) )
            .shouldBe( Condition.visible, WAIT );
        installClipboardShim();
        block.hover();
        block.$( ".block-link-btn" ).shouldBe( Condition.visible, WAIT ).click();

        final String copied = awaitCopied();
        final Matcher m = Pattern.compile( "\\[\\[" + COPY + "#\\^([a-z0-9]{6})\\]\\]" ).matcher( copied );
        assertTrue( m.matches(), "clipboard must hold exactly [[Page#^id]] but was: " + copied );
        final String id = m.group( 1 );

        Selenide.refresh();
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, WAIT );
        $( By.xpath( "//*[@id='^" + id + "']" ) ).shouldBe( Condition.exist, WAIT )
            .shouldHave( Condition.partialText( "Second block of copy page" ) );
    }

    @Test
    void blockFragmentScrollsToAndFlashesTheBlock() {
        // Open without a hash and watch for the flash class, so the 2s flash window cannot be missed by polling.
        ViewWikiPage.open( LAND );
        $( By.xpath( "//*[@id='^land1']" ) ).shouldBe( Condition.exist, WAIT );
        Selenide.executeJavaScript(
            "window.__flashed = null;"
            + "const seen = (el) => { if (el.classList && el.classList.contains('block-target-flash')) window.__flashed = el.id; };"
            + "new MutationObserver((muts) => muts.forEach((m) => seen(m.target)))"
            + ".observe(document, { attributes: true, attributeFilter: ['class'], subtree: true });"
            + "location.hash = '#^land1';" );
        final long deadline = System.currentTimeMillis() + 30_000;
        Object flashed = Selenide.executeJavaScript( "return window.__flashed;" );
        while ( flashed == null && System.currentTimeMillis() < deadline ) {
            Selenide.sleep( 50 );
            flashed = Selenide.executeJavaScript( "return window.__flashed;" );
        }
        assertEquals( "^land1", flashed, "the landed block must gain block-target-flash" );

        final SelenideElement target = $( By.xpath( "//*[@id='^land1']" ) );
        target.shouldHave( Condition.partialText( "Landing paragraph" ) );
        final long viewDeadline = System.currentTimeMillis() + 15_000;
        Boolean inView = Boolean.FALSE;
        while ( !Boolean.TRUE.equals( inView ) && System.currentTimeMillis() < viewDeadline ) {
            inView = Selenide.executeJavaScript(
                "const r = arguments[0].getBoundingClientRect();"
                + "return r.top >= 0 && r.bottom <= window.innerHeight;", target );
            if ( !Boolean.TRUE.equals( inView ) ) {
                Selenide.sleep( 100 );
            }
        }
        assertEquals( Boolean.TRUE, inView, "the block must be scrolled into view" );
    }

    @Test
    void copyingABlockThatAlreadyHasAnIdMakesNoAssignRequest() {
        ViewWikiPage.open( SOURCE );
        final SelenideElement block = $( By.xpath( "//*[@id='^alloc1']" ) ).shouldBe( Condition.visible, WAIT );
        installClipboardShim();
        block.hover();
        block.$( ".block-link-btn" ).shouldBe( Condition.visible, WAIT ).click();

        assertEquals( "[[" + SOURCE + "#^alloc1]]", awaitCopied() );
        final Object posts = Selenide.executeJavaScript( "return window.__posts.length;" );
        assertEquals( 0L, ( ( Number ) posts ).longValue(), "an existing id must not trigger a POST to /blocks" );
    }

    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void editorCompletionInsertsABlockLink( final EditorMode mode ) {
        EditWikiPage.openInMode( "BlockEdit" + mode.name() + TS, mode );
        EditWikiPage.awaitDocumentContaining( EditWikiPage.NEW_PAGE_BOILERPLATE, Duration.ofSeconds( 10 ) );
        final SelenideElement content = EditWikiPage.clearDocument();
        content.sendKeys( "[[" + SOURCE + "#^Al" );
        $( ".cm-tooltip-autocomplete" ).shouldBe( Condition.visible, WAIT )
            .shouldHave( Condition.text( "Allocation strategy notes" ), WAIT );
        $( ".cm-tooltip-autocomplete li[role=option]" ).shouldHave( Condition.text( "Allocation" ), WAIT ).click();

        EditWikiPage.awaitDocumentContaining( "[[" + SOURCE + "#^alloc1]]", Duration.ofSeconds( 10 ) );
    }

    private static void installClipboardShim() {
        Selenide.executeJavaScript(
            "window.__copied = null;"
            + "const shim = {"
            + "  writeText: async (t) => { window.__copied = String(t); },"
            + "  write: async (items) => { const b = await items[0].getType('text/plain'); window.__copied = await b.text(); }"
            + "};"
            + "Object.defineProperty(navigator, 'clipboard', { value: shim, configurable: true });"
            + "window.__posts = [];"
            + "const realFetch = window.fetch.bind(window);"
            + "window.fetch = (input, init) => {"
            + "  const method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();"
            + "  const url = String((input && input.url) || input);"
            + "  if (method === 'POST' && url.includes('/blocks')) window.__posts.push(url);"
            + "  return realFetch(input, init);"
            + "};" );
    }

    private static String awaitCopied() {
        final long deadline = System.currentTimeMillis() + 15_000;
        Object copied = Selenide.executeJavaScript( "return window.__copied;" );
        while ( copied == null && System.currentTimeMillis() < deadline ) {
            Selenide.sleep( 100 );
            copied = Selenide.executeJavaScript( "return window.__copied;" );
        }
        assertNotNull( copied, "the copy button must write to the clipboard" );
        return copied.toString();
    }
}
