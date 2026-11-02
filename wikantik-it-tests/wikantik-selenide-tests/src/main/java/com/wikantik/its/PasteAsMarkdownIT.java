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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paste-as-Markdown in the page editor, in a real browser: a paste whose clipboard carries rich HTML (here, the
 * shape Google Docs puts on the clipboard) lands in the CodeMirror source as Markdown, the server renders that
 * Markdown back into the same structure, Mod-Shift-V pastes the plain text instead, and a URL pasted over a
 * selection links it.
 *
 * <p>The paste is a synthetic {@code ClipboardEvent} carrying a real {@code DataTransfer}: headless Chrome has no
 * system clipboard to copy from, but the event reaches CodeMirror's handler chain exactly as a user's paste does.
 * Every test runs in each {@link EditorMode}; assertions read the CodeMirror document, never the screen (live mode
 * hides the pasted markup off the caret's line).</p>
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

    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void richPasteBecomesMarkdownAndRendersBack( final EditorMode mode ) {
        final String pageName = "PasteAsMarkdown" + mode.name() + System.currentTimeMillis();
        EditWikiPage.openInMode( pageName, mode );
        final SelenideElement content = clearedEditor();

        assertTrue( paste( content, DOCS_TEXT, DOCS_HTML ), "a converted paste must take over the browser's default paste" );
        awaitDocument( DOCS_MARKDOWN );
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

    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void modShiftVPastesThePlainText( final EditorMode mode ) {
        EditWikiPage.openInMode( "PastePlain" + mode.name() + System.currentTimeMillis(), mode );
        final SelenideElement content = clearedEditor();

        Selenide.executeJavaScript(
            "arguments[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, "
            + "bubbles: true, cancelable: true }));", content );
        paste( content, DOCS_TEXT, DOCS_HTML );
        awaitDocument( DOCS_TEXT );
    }

    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void urlPastedOverASelectionLinksIt( final EditorMode mode ) {
        EditWikiPage.openInMode( "PasteLink" + mode.name() + System.currentTimeMillis(), mode );
        final SelenideElement content = clearedEditor();
        content.sendKeys( "the docs" );
        awaitDocument( "the docs" );
        content.sendKeys( PlatformKeys.selectAll() );

        paste( content, "https://example.com/docs", "" );
        awaitDocument( "[the docs](https://example.com/docs)" );
    }

    /** A 1x1 PNG, the shape Google Docs / Word embed as a {@code data:} URI. */
    private static final String PNG_DATA_URI = "data:image/png;base64,"
        + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    private static final Pattern UPLOADING_PLACEHOLDER = Pattern.compile( "!\\[Uploading pasted-image-[^\\]]*\\.png\u2026\\]\\(\\)" );
    private static final Pattern IMAGE_MARKUP = Pattern.compile( "!\\[pasted-image-[^\\]]*\\]\\((pasted-image-[^)]*\\.png)\\)" );

    /**
     * An embedded {@code data:} image rides along with the pasted HTML: it lands in the body at its own position as an
     * upload placeholder, and after "Save and upload" (a new page must exist before it can hold attachments) the
     * placeholder is the attachment markup and the page's attachment list holds {@code pasted-image-*.png}.
     */
    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void pastedDataUriImageBecomesAnAttachment( final EditorMode mode ) {
        final String pageName = "PasteImage" + mode.name() + System.currentTimeMillis();
        EditWikiPage.openInMode( pageName, mode );
        final SelenideElement content = clearedEditor();

        final String html = "<meta charset=\"utf-8\"><b style=\"font-weight:normal;\" id=\"docs-internal-guid-it-img\">"
            + "<h2 dir=\"ltr\"><span>Pasted Picture</span></h2>"
            + "<p dir=\"ltr\"><span>Before the picture</span></p>"
            + "<p dir=\"ltr\"><img src=\"" + PNG_DATA_URI + "\" width=\"1\" height=\"1\"></p>"
            + "<p dir=\"ltr\"><span>After the picture</span></p></b>";
        assertTrue( paste( content, "Pasted Picture\nBefore the picture\nAfter the picture", html ),
            "a converted paste must take over the browser's default paste" );

        // The placeholder sits between the two paragraphs, where the image was.
        final String pasted = EditWikiPage.awaitDocument( doc -> UPLOADING_PLACEHOLDER.matcher( doc ).find(),
            "contain an upload placeholder", Duration.ofSeconds( 5 ) );
        final int before = pasted.indexOf( "Before the picture" );
        final int placeholder = pasted.indexOf( "![Uploading " );
        final int after = pasted.indexOf( "After the picture" );
        assertTrue( before >= 0 && before < placeholder && placeholder < after,
            "the placeholder must sit between the paragraphs; the document was:\n[" + pasted + "]" );

        // A new page cannot hold attachments yet: the editor offers to save first, then uploads.
        $( "[data-testid=upload-needs-save] button.btn-primary" ).shouldBe( Condition.visible, Duration.ofSeconds( 5 ) ).click();

        final String uploaded = EditWikiPage.awaitDocument(
            doc -> IMAGE_MARKUP.matcher( doc ).find() && !doc.contains( "Uploading" ),
            "link the uploaded image in place of the placeholder", Duration.ofSeconds( 20 ) );
        final Matcher markup = IMAGE_MARKUP.matcher( uploaded );
        assertTrue( markup.find() );
        final String fileName = markup.group( 1 );

        assertTrue( awaitAttachment( pageName, fileName ),
            "the page's attachment list must contain " + fileName + " after the upload" );
    }

    /** Polls {@code GET /api/attachments/<page>} from the signed-in browser until it lists {@code fileName}. */
    private static boolean awaitAttachment( final String pageName, final String fileName ) {
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            fetch(base + '/api/attachments/' + encodeURIComponent(arguments[0]), {
                headers: { 'Accept': 'application/json' }, credentials: 'same-origin'
            })
            .then(r => r.ok ? r.json() : { attachments: [] })
            .then(j => cb((j.attachments || []).map(a => a.fileName)))
            .catch(() => cb([]));
            """;
        final long deadline = System.currentTimeMillis() + 10_000;
        while ( System.currentTimeMillis() < deadline ) {
            final Object names = Selenide.executeAsyncJavaScript( script, pageName );
            if ( names instanceof java.util.List< ? > list && list.contains( fileName ) ) {
                return true;
            }
            Selenide.sleep( 250 );
        }
        return false;
    }

    /** Waits for the new-page boilerplate, focuses the editor and empties it. */
    private static SelenideElement clearedEditor() {
        EditWikiPage.awaitDocumentContaining( EditWikiPage.NEW_PAGE_BOILERPLATE, Duration.ofSeconds( 10 ) );
        return EditWikiPage.clearDocument();
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

    private static void awaitDocument( final String expected ) {
        EditWikiPage.awaitDocumentEquals( expected, Duration.ofSeconds( 5 ) );
    }
}
