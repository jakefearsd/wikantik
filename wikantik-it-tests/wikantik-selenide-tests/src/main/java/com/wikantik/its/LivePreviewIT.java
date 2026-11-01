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
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Map;

import static com.codeborne.selenide.Condition.cssClass;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Browser IT for editor live preview: GFM tables render as a widget, plugin and footnote
 * syntax render as chips, and the chosen editor mode persists per user on the server.
 */
public class LivePreviewIT extends WithIntegrationTestSetup {

    private static final String PAGE = "LivePreviewIT";
    private static final Duration WAIT = Duration.ofSeconds( 15 );
    // Must stay recognisably Markdown: WikiToMarkdownConverter.isLikelyWikiSyntax scores [{TableOfContents}] at 2 of
    // a threshold 3, so one more wiki marker (e.g. __bold__, ''x'') would flip the page to legacy syntax and
    // disable live mode.
    private static final String BODY = "# Live Preview\n\n"
        + "| Alpha | Beta |\n| --- | --- |\n| one | two |\n| three | four |\n\n"
        + "[{TableOfContents}]\n\n"
        + "Footnoted claim.[^1]\n\n"
        + "[^1]: Note text.\n";

    @BeforeEach
    void login() throws Exception {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();
        // Other ITs pin source mode, which useEditorMode migrates to this user's server preference; start clean.
        putEditorMode( "null" );
        RestSeedHelper.writePage( PAGE, BODY );
    }

    @AfterEach
    void cleanup() {
        try {
            putEditorMode( "null" );
        } finally {
            RestSeedHelper.deletePageQuietly( PAGE );
        }
    }

    @Test
    void liveModeRendersTableAndChipsAndRevealsSourceOnClick() {
        EditWikiPage.openInMode( PAGE, "live" );
        $( "[data-testid=page-editor] .cm-editor" ).shouldHave( cssClass( "cm-live-preview" ), WAIT );

        $( ".cm-lp-table-block table.cm-lp-table thead th" ).shouldBe( visible, WAIT ).shouldHave( text( "Alpha" ) );
        $( "span.cm-lp-chip.cm-lp-chip-plugin" ).shouldBe( visible, WAIT ).shouldHave( text( "⚙ TableOfContents" ) );
        $( "span.cm-lp-footnote-ref" ).shouldBe( visible, WAIT ).shouldHave( exactText( "1" ) );

        // Clicking a body row puts the caret on its source line, which swaps the widget for raw text.
        $( ".cm-lp-table-block table.cm-lp-table tbody tr" ).shouldBe( visible, WAIT ).click();
        $( "[data-testid=page-editor] .cm-content" ).shouldHave( text( "| one | two |" ), WAIT );
    }

    @Test
    void editorModePersistsOnTheServerPerUser() {
        EditWikiPage.openInMode( PAGE, "live" );
        final var editor = $( "[data-testid=page-editor] .cm-editor" );
        editor.shouldHave( cssClass( "cm-live-preview" ), WAIT );

        // Toggle live -> source via the toolbar button.
        $( "[aria-label^='Live preview']" ).click();
        editor.shouldNotHave( cssClass( "cm-live-preview" ), WAIT );
        awaitServerMode( "source" );

        // Drop the browser-side memory so only the server preference can restore the mode.
        Selenide.executeJavaScript( "localStorage.removeItem('wikantik.editor.mode')" );
        Selenide.refresh();
        $( "[data-testid=page-editor] .cm-editor" ).shouldBe( visible, WAIT );
        $( "[data-testid=page-editor] .cm-content" ).shouldHave( text( "| Alpha | Beta |" ), WAIT );
        $( "[data-testid=page-editor] .cm-editor" ).shouldNotHave( cssClass( "cm-live-preview" ) );
    }

    /** Waits until GET /api/auth/profile reports the given editorMode. */
    private static void awaitServerMode( final String expected ) {
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            fetch(base + '/api/auth/profile', { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
              .then(r => r.json()).then(j => cb({ mode: j.editorMode === undefined ? null : j.editorMode }))
              .catch(err => cb({ mode: 'ERR ' + err }));
            """;
        final Object[] last = new Object[1];
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) ).until( d -> {
            final Object r = Selenide.executeAsyncJavaScript( script );
            last[0] = r instanceof Map< ?, ? > m ? m.get( "mode" ) : r;
            return expected.equals( last[0] );
        } );
        assertEquals( expected, last[0] );
    }

    /** PUT /api/auth/profile with the given raw JSON editorMode value (e.g. {@code null}). */
    private static void putEditorMode( final String jsonValue ) {
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            fetch(base + '/api/auth/profile', { method: 'PUT',
              headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
              credentials: 'same-origin', body: '{"editorMode":' + arguments[0] + '}' })
              .then(r => cb({ status: r.status })).catch(err => cb({ status: -1, error: String(err) }));
            """;
        final Object r = Selenide.executeAsyncJavaScript( script, jsonValue );
        if ( !( r instanceof Map< ?, ? > m ) || !( m.get( "status" ) instanceof Number n ) || n.intValue() >= 300 ) {
            throw new IllegalStateException( "PUT /api/auth/profile failed: " + r );
        }
    }
}
