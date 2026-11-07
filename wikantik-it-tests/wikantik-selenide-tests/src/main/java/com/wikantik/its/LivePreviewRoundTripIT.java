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

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The whole-page round trip in a real browser: the live-preview kitchen-sink page opens in live mode, every part of it
 * is drawn on the way to the bottom, the editor survives a source/live toggle, and saving without an edit changes
 * nothing on the server.
 */
public class LivePreviewRoundTripIT extends WithIntegrationTestSetup {

    private static final Logger LOG = LoggerFactory.getLogger( LivePreviewRoundTripIT.class );

    private static final String PAGE = "LivePreviewRoundTripIT";
    private static final Duration WAIT = Duration.ofSeconds( 15 );
    /** The frontend's kitchen-sink fixture, read from the checkout so the IT and the vitest suite share one page. */
    private static final String FIXTURE = "wikantik-frontend/src/utils/__fixtures__/live-preview-kitchen-sink.md";
    private static final String SCROLLER = "[data-testid=editor-textarea] .cm-scroller";
    private static final int MAX_SCROLL_STEPS = 200;

    /** One widget class per kind the kitchen sink must draw somewhere between its top and bottom. */
    private static final List< String > REQUIRED_WIDGETS = List.of(
        "cm-lp-table-block",   // table
        "cm-lp-chip",          // plugin / footnote / block-id chips
        "cm-lp-footnote-ref",  // footnote reference
        "cm-lp-math",          // math (inline and display)
        "cm-lp-embed",         // ![[Other]] embed box — present whether the target loads, is missing or restricted
        "cm-lp-fence-block",   // rendered fence (mermaid)
        "cm-lp-image-wrap" );  // ![alt](pic.png)

    private String body;

    @BeforeEach
    void login() throws Exception {
        body = readFixture();
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();
        RestSeedHelper.writePage( PAGE, body );
    }

    @AfterEach
    void cleanup() {
        RestSeedHelper.deletePageQuietly( PAGE );
    }

    @Test
    void kitchenSinkSurvivesLivePreviewScrollToggleAndAnUneditedSave() throws Exception {
        final JsonObject seeded = fetchPage();
        final int seededVersion = seeded.get( "version" ).getAsInt();

        EditWikiPage.openInMode( PAGE, EditorMode.LIVE );
        EditWikiPage.awaitDocumentEquals( body, WAIT );

        final Set< String > widgets = scrollToTheEndCollectingWidgets();
        for ( final String required : REQUIRED_WIDGETS ) {
            assertTrue( widgets.contains( required ), "live preview never drew ." + required + " while scrolling the "
                + "kitchen sink top to bottom; widget classes seen: " + widgets );
        }

        // Mod+E twice with the editor focused: source, then live again.
        EditWikiPage.setCaret( 0 );
        EditWikiPage.pressMod( "e" );
        new EditWikiPage().assertMode( EditorMode.SOURCE );
        EditWikiPage.pressMod( "e" );
        new EditWikiPage().assertMode( EditorMode.LIVE );

        assertEquals( body, EditWikiPage.documentText(), "after scrolling and two mode toggles the editor document must "
            + "still be the seeded page, byte for byte" );

        EditWikiPage.publish();
        new WebDriverWait( WebDriverRunner.getWebDriver(), WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT );

        // The editor always PUTs on Save; the server (DefaultPageLifecycle.saveText) stores nothing when the text it
        // would store equals the current version's. So the stronger statement holds: no new version, same text.
        final JsonObject saved = fetchPage();
        assertEquals( seededVersion, saved.get( "version" ).getAsInt(), "an unedited save must not create a version" );
        assertEquals( seeded.get( "content" ).getAsString(), saved.get( "content" ).getAsString(),
            "an unedited save must leave the stored text byte-identical" );
        assertEquals( seeded.get( "contentHash" ).getAsString(), saved.get( "contentHash" ).getAsString() );
    }

    /**
     * Scrolls the editor's scroller from the top to the bottom one viewport at a time, collecting every
     * {@code cm-lp-*} class drawn on the way, until CodeMirror has rendered the document's last line — asserted, so a
     * scroll that stops short of the end (viewport virtualisation) fails here rather than passing on what it saw.
     */
    private static Set< String > scrollToTheEndCollectingWidgets() {
        final Map< ?, ? > size = Selenide.executeJavaScript( """
            const s = document.querySelector(arguments[0]);
            if (!s) throw new Error('no editor scroller ' + arguments[0]);
            // CodeMirror only draws what is inside the browser window: with collaborative editing on, the taller
            // toolbar leaves the editor at the fold of a 768px window, so bring it fully into view first.
            s.scrollIntoView({ block: 'start' });
            s.scrollTop = 0;
            return { scrollHeight: s.scrollHeight, clientHeight: s.clientHeight };
            """, SCROLLER );
        final long scrollHeight = ( ( Number ) size.get( "scrollHeight" ) ).longValue();
        final long clientHeight = ( ( Number ) size.get( "clientHeight" ) ).longValue();
        assertTrue( scrollHeight > clientHeight, "the kitchen sink should be taller than the editor's viewport "
            + "(scrollHeight " + scrollHeight + ", clientHeight " + clientHeight + "), or scrolling proves nothing" );

        final Set< String > seen = new TreeSet<>();
        boolean renderedInitially = false;
        for ( int step = 0; step < MAX_SCROLL_STEPS; step++ ) {
            Selenide.sleep( 250 ); // CodeMirror re-measures its viewport on the next animation frames after a scroll
            seen.addAll( drawnWidgetClasses() );
            final Map< ?, ? > last = lastLineState();
            if ( step == 0 ) {
                renderedInitially = Boolean.TRUE.equals( last.get( "rendered" ) );
            }
            final Map< ?, ? > pos = Selenide.executeJavaScript( """
                const s = document.querySelector(arguments[0]);
                return { top: s.scrollTop, max: s.scrollHeight - s.clientHeight };
                """, SCROLLER );
            final boolean atBottom = ( ( Number ) pos.get( "top" ) ).doubleValue() >= ( ( Number ) pos.get( "max" ) ).doubleValue() - 1;
            if ( atBottom && Boolean.TRUE.equals( last.get( "rendered" ) ) ) {
                LOG.info( "Reached the kitchen sink's last line after {} scroll step(s) (rendered before scrolling: {}); "
                    + "widget classes seen: {}", step, renderedInitially, seen );
                return seen;
            }
            Selenide.executeJavaScript( """
                const s = document.querySelector(arguments[0]);
                s.scrollTop = Math.min(s.scrollTop + s.clientHeight, s.scrollHeight - s.clientHeight);
                """, SCROLLER );
        }
        return fail( "scrolled " + MAX_SCROLL_STEPS + " viewports without CodeMirror rendering the document's last line: "
            + lastLineState() + "; widget classes seen: " + seen );
    }

    /** Every {@code cm-lp-*} class on an element inside the editor content right now. */
    private static List< String > drawnWidgetClasses() {
        return Selenide.executeJavaScript( """
            const seen = new Set();
            document.querySelectorAll('[data-testid=editor-textarea] .cm-content [class*="cm-lp-"]').forEach((el) =>
              el.classList.forEach((c) => { if (c.startsWith('cm-lp-')) seen.add(c); }));
            return [...seen];
            """ );
    }

    /**
     * Whether CodeMirror has drawn the end of the document: its viewport reaches the end of the document, the DOM
     * position of the document's end sits in a connected {@code .cm-line} showing that line's text, and — because the
     * fixture ends with a newline, so its last line is empty and that comparison alone is vacuous — the last
     * non-empty line ({@code "Final line"}) is drawn too, in a connected {@code .cm-line} showing exactly its text.
     */
    private static Map< ?, ? > lastLineState() {
        return EditWikiPage.evaluateOnView( """
            const doc = view.state.doc;
            const last = doc.line(doc.lines);
            let lastText = last;
            while (lastText.number > 1 && lastText.text.trim() === '') lastText = doc.line(lastText.number - 1);
            const inViewport = view.viewport.to >= doc.length;
            const drawnLine = (pos) => {
              const { node } = view.domAtPos(pos);
              const start = node.nodeType === 1 ? node : node.parentElement;
              const line = start && start.closest('.cm-line');
              return line && line.isConnected && view.contentDOM.contains(line) ? line.textContent : null;
            };
            const lineText = inViewport ? drawnLine(doc.length) : null;
            const lastTextDrawn = inViewport && lastText.from >= view.viewport.from ? drawnLine(lastText.to) : null;
            return { rendered: inViewport && lineText === last.text && lastText.text.trim() !== ''
                               && lastTextDrawn === lastText.text,
                     viewportTo: view.viewport.to, docLength: doc.length, lastLine: last.number,
                     lastLineText: last.text, drawnText: lineText, lastNonEmptyLine: lastText.number,
                     lastNonEmptyText: lastText.text, lastNonEmptyDrawn: lastTextDrawn };
            """ );
    }

    private static JsonObject fetchPage() throws Exception {
        return JsonParser.parseString( RestSeedHelper.get( "/api/pages/" + PAGE ) ).getAsJsonObject();
    }

    /** Finds {@link #FIXTURE} by walking up from the IT module's working directory to the checkout root. */
    private static String readFixture() throws IOException {
        for ( Path dir = Path.of( System.getProperty( "user.dir" ) ).toAbsolutePath(); dir != null; dir = dir.getParent() ) {
            final Path candidate = dir.resolve( FIXTURE );
            if ( Files.isRegularFile( candidate ) ) {
                return Files.readString( candidate, StandardCharsets.UTF_8 );
            }
        }
        throw new IllegalStateException( "could not find " + FIXTURE + " above " + System.getProperty( "user.dir" ) );
    }
}
