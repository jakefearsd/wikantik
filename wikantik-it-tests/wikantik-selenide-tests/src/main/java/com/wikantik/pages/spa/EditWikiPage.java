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
package com.wikantik.pages.spa;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.environment.PlatformKeys;
import com.wikantik.pages.Page;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * Page object for the React SPA page editor (the {@code PageEditor} component).
 *
 * <p>The editor route is {@code /edit/<pageName>}, served by the SPA routing
 * filter. The component renders a two-pane layout with a CodeMirror 6 editor
 * ({@code [data-testid=editor-textarea] .cm-content}) and a markdown preview.
 * Save/Cancel buttons are located in the toolbar.
 */
public class EditWikiPage implements SpaPage {

    /**
     * Open a given page for edition.
     *
     * @param pageName Wiki page name to edit (new or existing).
     * @return {@link EditWikiPage} instance, to allow chaining of actions.
     */
    public static EditWikiPage open( final String pageName ) {
        final EditWikiPage page = Page.withUrl( Page.baseUrl() + "/edit/" + pageName ).openAs( new EditWikiPage() );
        $( "[data-testid=page-editor]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        $( "[data-testid=editor-textarea]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return page;
    }

    /**
     * Press the cancel button and discard the edit.
     *
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage cancel() {
        $( "[data-testid=editor-cancel]" ).click();
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return new ViewWikiPage();
    }

    /**
     * Replaces the page contents with the given text and saves.
     *
     * @param text text to save.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage saveText( final String text ) {
        return saveText( text, text );
    }

    /**
     * Replaces the page contents with the given text and saves, verifying
     * the live preview shows the expected substring before submitting.
     *
     * @param text full text to save (may include wiki directives).
     * @param preview substring expected to appear in the preview pane after
     *                rendering (directives like {@code [{ALLOW …}]} will not
     *                appear in the preview).
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage saveText( final String text, final String preview ) {
        // The editor is now a CodeMirror 6 instance. The wrapper div carries
        // data-testid="editor-textarea" but is not an <input> or <textarea>,
        // so the old ReactInputs.setTextareaValue() JS prototype trick throws
        // "Illegal invocation". Drive CodeMirror via its contenteditable
        // .cm-content element instead: click to focus, select-all, delete any
        // existing content, then send the new text. CodeMirror handles keyboard
        // events natively and fires its onChange prop, updating React state.
        final SelenideElement cmContent = $( "[data-testid=editor-textarea] .cm-content" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        cmContent.click();
        // CodeMirror routes keyboard events through its focused editor only, and
        // the click's focus can lag under machine load. Without this wait the
        // select-all chord lands unfocused and silently no-ops, the DELETE
        // deletes nothing, and the new text APPENDS to the old content — which
        // the preview check below cannot catch (it is a contains-, not equals-check).
        $( "[data-testid=editor-textarea] .cm-editor" )
            .shouldHave( Condition.cssClass( "cm-focused" ), Duration.ofSeconds( 5 ) );
        cmContent.sendKeys( PlatformKeys.selectAll() );
        cmContent.sendKeys( Keys.DELETE );
        // Prove the buffer is empty before typing the replacement.
        cmContent.shouldHave( Condition.exactText( "" ), Duration.ofSeconds( 5 ) );
        cmContent.sendKeys( text );

        // Prove the typed text actually landed before we sink more time into the
        // preview check and Save below. This is the check that would have caught
        // the macOS select-all bug directly (old content left in the buffer) at
        // its source, instead of it surfacing several calls later as a confusing
        // exact-text assertion failure on rendered output. Read the buffer via JS
        // rather than Selenide's own text extraction — Selenide can normalise/
        // collapse whitespace on a contenteditable, which would mask a real
        // mismatch with a false pass. CodeMirror 6 renders each LOGICAL line as
        // its own .cm-line div (visual wrapping never adds one), so innerText
        // already maps multi-line text to newline-separated content; we still
        // normalise line endings and trim only trailing whitespace so a harmless
        // trailing-newline difference doesn't fail the check.
        final String expectedBuffer = normalizeTrailing( text );
        try {
            new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 5 ) )
                .until( driver -> normalizeTrailing( editorBufferText( cmContent ) ).equals( expectedBuffer ) );
        } catch ( final TimeoutException e ) {
            throw new AssertionError(
                "CodeMirror buffer did not match the text just typed, 5s after sendKeys() returned. "
                + "This is the failure mode of the macOS select-all bug (old content left in the "
                + "buffer — see PlatformKeys) or a dropped/duplicated keystroke.\n"
                + "Typed:  [" + text + "]\n"
                + "Buffer: [" + editorBufferText( cmContent ) + "]", e );
        }

        // The preview pane is a live-updated ReactMarkdown render; give it
        // a moment to reflect the new content before clicking Save.
        $( ".editor-preview .article-prose" )
            .shouldBe( Condition.text( preview ), Duration.ofSeconds( 5 ) );

        $( "[data-testid=editor-save]" ).click();

        // Wait for the router to navigate away from /edit/ and the viewer to
        // mount. Use both URL and testid checks: some tests save under the
        // same page name where the new view's testid flips on but URL also
        // changes back to /wiki/.
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> !driver.getCurrentUrl().contains( "/edit/" ) );

        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return new ViewWikiPage();
    }

    /**
     * Reads the raw markdown source currently held by the CodeMirror buffer,
     * via {@code innerText} on its contenteditable {@code .cm-content} element.
     *
     * @param cmContent the {@code .cm-content} element.
     * @return the buffer's current text.
     */
    private static String editorBufferText( final SelenideElement cmContent ) {
        return Selenide.executeJavaScript( "return arguments[0].innerText;", cmContent );
    }

    /**
     * Normalises line endings and strips trailing whitespace, so a harmless
     * trailing-newline/whitespace difference does not fail a buffer-content
     * comparison. Deliberately does not touch leading or interior whitespace —
     * that is exactly what a dropped/duplicated keystroke would corrupt.
     *
     * @param value the text to normalise.
     * @return the normalised text.
     */
    private static String normalizeTrailing( final String value ) {
        // Null-safe: this runs inside a WebDriverWait predicate, which does not
        // swallow NPEs — a null buffer read would surface as a bare
        // NullPointerException instead of the diagnostic AssertionError below.
        return value == null ? "" : value.replace( "\r\n", "\n" ).stripTrailing();
    }

}
