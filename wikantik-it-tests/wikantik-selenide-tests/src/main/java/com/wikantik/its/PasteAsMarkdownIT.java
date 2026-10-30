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
import com.wikantik.its.environment.PlatformKeys;
import com.wikantik.pages.spa.EditWikiPage;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paste-as-Markdown in the page editor, in a real browser: a paste whose clipboard carries rich HTML (here, the
 * shape Google Docs puts on the clipboard) lands in the CodeMirror source as Markdown, the server renders that
 * Markdown back into the same structure, Mod-Shift-V pastes the plain text instead, and a URL pasted over a
 * selection links it.
 *
 * <p>The paste is a synthetic {@code ClipboardEvent} carrying a real {@code DataTransfer}: headless Chrome has no
 * system clipboard to copy from, but the event reaches CodeMirror's handler chain exactly as a user's paste does.
 * Buffer assertions read the source-mode editor's (the default mode's) lines.</p>
 */
public class PasteAsMarkdownIT extends WithIntegrationTestSetup {

    private static final String DOCS_HTML = "<meta charset=\"utf-8\"><b style=\"font-weight:normal;\" id=\"docs-internal-guid-it\">"
        + "<h2 dir=\"ltr\"><span style=\"font-weight:400\">Pasted Plan</span></h2>"
        + "<p dir=\"ltr\"><span style=\"font-weight:400\">Ship </span><span style=\"font-weight:700\">three</span>"
        + "<span style=\"font-weight:400\"> features, see </span><a href=\"https://example.com/roadmap\"><span>the roadmap</span></a></p>"
        + "<ul><li aria-level=\"1\"><p role=\"presentation\"><span>Design</span></p></li>"
        + "<li aria-level=\"1\"><p role=\"presentation\"><span>Launch</span></p></li></ul>"
        + "<div><table><tbody><tr><td><p><span style=\"font-weight:700\">Owner</span></p></td><td><p><span style=\"font-weight:700\">Due</span></p></td></tr>"
        + "<tr><td><p><span>Platform | Infra</span></p></td><td><p><span>March</span></p></td></tr></tbody></table></div></b>";
    private static final String DOCS_TEXT = "Pasted Plan\nShip three features, see the roadmap\nDesign\nLaunch\nOwner\tDue\nPlatform | Infra\tMarch";
    private static final String DOCS_MARKDOWN = String.join( "\n",
        "## Pasted Plan",
        "",
        "Ship **three** features, see [the roadmap](https://example.com/roadmap)",
        "",
        "- Design",
        "- Launch",
        "",
        "| Owner | Due |",
        "| --- | --- |",
        "| Platform \\| Infra | March |" );

    @Test
    void richPasteBecomesMarkdownAndRendersBack() {
        final String pageName = "PasteAsMarkdown" + System.currentTimeMillis();
        EditWikiPage.open( pageName );
        final SelenideElement content = clearedEditor();

        assertTrue( paste( content, DOCS_TEXT, DOCS_HTML ), "a converted paste must take over the browser's default paste" );
        awaitBuffer( content, DOCS_MARKDOWN );
        $( ".wk-toast-message" ).shouldHave( Condition.text( "Pasted as Markdown" ), Duration.ofSeconds( 5 ) );

        $( "[data-testid=editor-save]" ).click();
        final SelenideElement view = $( "[data-testid=page-view]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        view.$( "h2" ).shouldHave( Condition.text( "Pasted Plan" ) );
        view.$( "strong" ).shouldHave( Condition.exactText( "three" ) );
        view.$( "a[href='https://example.com/roadmap']" ).shouldHave( Condition.exactText( "the roadmap" ) );
        view.$( "ul li" ).shouldHave( Condition.exactText( "Design" ) );
        view.$( "table th" ).shouldHave( Condition.exactText( "Owner" ) );
        view.$( "table td" ).shouldHave( Condition.exactText( "Platform | Infra" ) );
    }

    @Test
    void modShiftVPastesThePlainText() {
        EditWikiPage.open( "PastePlain" + System.currentTimeMillis() );
        final SelenideElement content = clearedEditor();

        Selenide.executeJavaScript(
            "arguments[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, "
            + "bubbles: true, cancelable: true }));", content );
        paste( content, DOCS_TEXT, DOCS_HTML );
        awaitBuffer( content, DOCS_TEXT );
    }

    @Test
    void urlPastedOverASelectionLinksIt() {
        EditWikiPage.open( "PasteLink" + System.currentTimeMillis() );
        final SelenideElement content = clearedEditor();
        content.sendKeys( "the docs" );
        awaitBuffer( content, "the docs" );
        content.sendKeys( PlatformKeys.selectAll() );

        paste( content, "https://example.com/docs", "" );
        awaitBuffer( content, "[the docs](https://example.com/docs)" );
    }

    /** Waits for the new-page boilerplate, focuses the editor and empties it. */
    private static SelenideElement clearedEditor() {
        final SelenideElement content = $( "[data-testid=editor-textarea] .cm-content" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) )
            .shouldHave( Condition.text( "Write your content here" ), Duration.ofSeconds( 10 ) );
        content.click();
        $( "[data-testid=editor-textarea] .cm-editor" ).shouldHave( Condition.cssClass( "cm-focused" ), Duration.ofSeconds( 5 ) );
        content.sendKeys( PlatformKeys.selectAll() );
        content.sendKeys( Keys.DELETE );
        content.shouldHave( Condition.exactText( "" ), Duration.ofSeconds( 5 ) );
        return content;
    }

    /** Dispatches a paste carrying text/plain and (when non-empty) text/html; returns whether it was default-prevented. */
    private static boolean paste( final SelenideElement content, final String text, final String html ) {
        final Boolean prevented = Selenide.executeJavaScript(
            "const dt = new DataTransfer();"
            + "dt.setData('text/plain', arguments[1]);"
            + "if (arguments[2]) dt.setData('text/html', arguments[2]);"
            + "const ev = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true });"
            + "arguments[0].dispatchEvent(ev);"
            + "return ev.defaultPrevented;", content, text, html );
        return Boolean.TRUE.equals( prevented );
    }

    private static void awaitBuffer( final SelenideElement content, final String expected ) {
        final long deadline = System.currentTimeMillis() + 5_000;
        String actual = buffer( content );
        while ( !expected.equals( actual ) && System.currentTimeMillis() < deadline ) {
            Selenide.sleep( 100 );
            actual = buffer( content );
        }
        assertEquals( expected, actual, "CodeMirror source after the paste" );
    }

    private static String buffer( final SelenideElement content ) {
        // One .cm-line per logical line; textContent (not innerText) so an empty line is "" rather than a <br>'s
        // extra newline. Short documents only — CodeMirror renders just the viewport's lines.
        final String text = Selenide.executeJavaScript(
            "return Array.from(arguments[0].querySelectorAll('.cm-line')).map(l => l.textContent).join('\\n');", content );
        return text == null ? null : text.stripTrailing();
    }
}
