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
import com.wikantik.its.EditorMode;
import com.wikantik.its.environment.PlatformKeys;
import com.wikantik.pages.Page;
import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

import static com.codeborne.selenide.Selenide.$;

/**
 * Page object for the React SPA page editor (the {@code PageEditor} component).
 *
 * <p>The editor route is {@code /edit/<pageName>}, served by the SPA routing
 * filter. The component renders a two-pane layout with a CodeMirror 6 editor
 * ({@code [data-testid=editor-textarea] .cm-content}) and a markdown preview.
 * Save/Cancel buttons are located in the toolbar.
 *
 * <p>The editor runs in one of the {@link EditorMode}s. In live mode the screen hides markup off the caret's line,
 * so what the editor <em>holds</em> is read from the CodeMirror document ({@link #documentText()}), never from the
 * rendered text.
 */
public class EditWikiPage implements SpaPage {

    private static final Logger LOG = LoggerFactory.getLogger( EditWikiPage.class );

    /** The body line of a new page's boilerplate ({@code # Name\n\nWrite your content here.}). */
    public static final String NEW_PAGE_BOILERPLATE = "Write your content here";

    private static final String CONTENT = "[data-testid=editor-textarea] .cm-content";
    private static final String EDITOR = "[data-testid=editor-textarea] .cm-editor";
    /** The toolbar's Live toggle; its {@code aria-pressed} mirrors whether live preview is active. */
    private static final String LIVE_TOGGLE = "[data-testid=page-editor] #toggle-live-preview";
    private static final Duration MODE_WAIT = Duration.ofSeconds( 10 );

    /**
     * Resolves the CodeMirror {@code EditorView} behind the page editor, as {@code view}, through the DOM
     * back-reference @codemirror/view keeps on its content element ({@code .cm-content.cmTile} is the document
     * tile, whose {@code view} — or its {@code root.view} — is the {@code EditorView}; the same path
     * {@code EditorView.findFromDOM} takes). There is no fallback: if the internals move, this throws.
     */
    private static final String RESOLVE_VIEW = """
        const host = document.querySelector('[data-testid=editor-textarea] .cm-content');
        if (!host) throw new Error('no [data-testid=editor-textarea] .cm-content on the page');
        const tile = host.cmTile;
        const view = tile && (tile.view || (tile.root && tile.root.view));
        if (!view || !view.state || !view.state.doc) {
          throw new Error('.cm-content has no CodeMirror view back-reference (cmTile.view / cmTile.root.view); '
            + 'did the @codemirror/view internals change?');
        }
        """;

    /** Set by {@link #pinMode} and consumed by the IT cleanup extension, which then clears the server preference. */
    private static volatile boolean modePinned;

    /**
     * Open a given page for edition in {@link EditorMode#SOURCE source} mode.
     *
     * @param pageName Wiki page name to edit (new or existing).
     * @return {@link EditWikiPage} instance, to allow chaining of actions.
     */
    public static EditWikiPage open( final String pageName ) {
        return openInMode( pageName, EditorMode.SOURCE );
    }

    /**
     * Open a page for edition with the editor mode pinned first (in this browser, and on the signed-in user's profile
     * when there is one, so the server preference cannot override the pin), then assert the editor really is in that
     * mode.
     *
     * @param pageName Wiki page name to edit (new or existing).
     * @param mode the editor mode to open in.
     * @return {@link EditWikiPage} instance, to allow chaining of actions.
     */
    public static EditWikiPage openInMode( final String pageName, final EditorMode mode ) {
        pinMode( mode );
        final EditWikiPage page = Page.withUrl( Page.baseUrl() + "/edit/" + pageName ).openAs( new EditWikiPage() );
        $( "[data-testid=page-editor]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        $( "[data-testid=editor-textarea]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return page.assertMode( mode );
    }

    /**
     * Asserts the mounted editor is in {@code mode}, with a positive signal for every mode: first the toolbar's Live
     * toggle reports the mode the editor resolved ({@code aria-pressed}, driven by the same {@code liveActive} state
     * that configures CodeMirror — {@code "true"} only for live), waited for until it holds; then {@code .cm-editor}
     * carries {@code cm-live-preview} exactly when the mode is live. Waiting on the toggle first means a SOURCE check
     * cannot pass on a bare absence while the editor is still settling into live.
     *
     * @param mode the expected mode.
     * @return this page object.
     */
    public EditWikiPage assertMode( final EditorMode mode ) {
        final SelenideElement editor = $( EDITOR ).shouldBe( Condition.visible, MODE_WAIT );
        final boolean live = mode == EditorMode.LIVE;
        $( LIVE_TOGGLE ).shouldBe( Condition.visible, MODE_WAIT )
            .shouldHave( Condition.attribute( "aria-pressed", String.valueOf( live ) ), MODE_WAIT );
        if ( live ) {
            editor.shouldHave( Condition.cssClass( "cm-live-preview" ), MODE_WAIT );
        } else {
            editor.shouldNotHave( Condition.cssClass( "cm-live-preview" ), MODE_WAIT );
        }
        return this;
    }

    /** Pins {@link EditorMode#SOURCE}; see {@link #pinMode}. */
    public static void pinSourceMode() {
        pinMode( EditorMode.SOURCE );
    }

    /**
     * Pins the editor mode so each IT is self-sufficient: whatever an earlier test left in this browser or on the
     * user's profile, the editor opens in {@code mode}. A signed-in session also gets the mode PUT to
     * {@code /api/auth/profile} (the server preference wins over the browser's); an anonymous session's 401 is
     * expected and only logged, any other failure throws. Either way the pin is recorded, so the IT cleanup extension
     * clears the server preference after the test (a signed-out local choice is migrated into the profile at the
     * next sign-in).
     */
    public static void pinMode( final EditorMode mode ) {
        final String base = Page.baseUrl();
        if ( !WebDriverRunner.hasWebDriverStarted() || !WebDriverRunner.url().startsWith( base ) ) {
            Selenide.open( base + "/" );
        }
        modePinned = true;
        final String script = """
            const cb = arguments[arguments.length - 1];
            const mode = arguments[0];
            fetch(arguments[1], { method: 'PUT',
              headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
              credentials: 'same-origin', body: JSON.stringify({ editorMode: mode }) })
              .then(r => {
                // Mirror what the app records: an account's value after a successful save, else a local choice.
                localStorage.setItem('wikantik.editor.mode', mode);
                localStorage.setItem('wikantik.editor.mode.src', r.ok ? 'server' : 'local');
                return r.text().then(body => cb({ status: r.status, url: r.url, page: location.href, body: body.slice(0, 300) }));
              })
              .catch(err => cb({ status: -1, error: String(err) }));
            """;
        // The URL comes from the configured base, not window.__WIKANTIK_BASE__: only SPA documents define that, and the
        // driver may be on any same-origin page (e.g. the context root), where a root-relative URL misses the webapp.
        final Object r = Selenide.executeAsyncJavaScript( script, mode.id(), base + "/api/auth/profile" );
        final int status = r instanceof Map< ?, ? > m && m.get( "status" ) instanceof Number n ? n.intValue() : -1;
        if ( status == 401 ) {
            LOG.info( "pinMode({}): anonymous session (401 from PUT /api/auth/profile); pinned in this browser only", mode.id() );
        } else if ( status < 200 || status >= 300 ) {
            throw new IllegalStateException( "pinMode(" + mode.id() + "): PUT /api/auth/profile failed: " + r );
        }
    }

    /**
     * Whether a mode was pinned since the last call, resetting the flag. For the IT cleanup extension.
     *
     * @return {@code true} when {@link #pinMode} ran since the last call.
     */
    public static boolean takeModePinned() {
        final boolean pinned = modePinned;
        modePinned = false;
        return pinned;
    }

    /**
     * The document the editor holds ({@code view.state.doc.toString()}), whatever the mode shows on screen.
     *
     * @return the CodeMirror document text.
     * @throws IllegalStateException when the editor's CodeMirror state cannot be reached.
     */
    public static String documentText() {
        return evaluateOnView( "return view.state.doc.toString();" );
    }

    /**
     * Runs {@code body} with {@code view} bound to the page editor's CodeMirror {@code EditorView} and returns its
     * result. Throws (never falls back to rendered text) when the view cannot be reached.
     *
     * @param body JavaScript statements using {@code view} (and {@code arguments}); must {@code return} the result.
     * @param args script arguments.
     * @param <T> the result type.
     * @return the script's result.
     */
    public static < T > T evaluateOnView( final String body, final Object... args ) {
        try {
            return Selenide.executeJavaScript( RESOLVE_VIEW + body, args );
        } catch ( final JavascriptException e ) {
            throw new IllegalStateException( "Could not read the page editor's CodeMirror state: " + e.getMessage(), e );
        }
    }

    /**
     * The caret's document offset ({@code state.selection.main.head}).
     *
     * @return the main selection's head.
     * @throws IllegalStateException when the editor's CodeMirror state cannot be reached.
     */
    public static int caretOffset() {
        final Object head = evaluateOnView( "return view.state.selection.main.head;" );
        if ( !( head instanceof Number n ) ) {
            throw new IllegalStateException( "state.selection.main.head was not a number: " + head );
        }
        return n.intValue();
    }

    /**
     * Focuses the editor and puts a collapsed caret at document offset {@code pos} (a selection-only transaction: the
     * document is untouched), then waits until the editor reports focus, so key presses sent with
     * {@link #pressKeys} land in it at that caret.
     *
     * @param pos document offset, {@code 0..doc.length}.
     */
    public static void setCaret( final int pos ) {
        evaluateOnView( """
            const pos = arguments[0];
            if (pos < 0 || pos > view.state.doc.length) {
              throw new Error('caret ' + pos + ' outside the document (length ' + view.state.doc.length + ')');
            }
            view.focus();
            view.dispatch({ selection: { anchor: pos }, scrollIntoView: true });
            return null;
            """, pos );
        $( EDITOR ).shouldHave( Condition.cssClass( "cm-focused" ), Duration.ofSeconds( 5 ) );
        final int head = caretOffset();
        if ( head != pos ) {
            throw new IllegalStateException( "setCaret(" + pos + ") left the caret at " + head );
        }
    }

    /**
     * The on-screen text of the editor line ({@code .cm-line}) that renders document offset {@code pos} — what the
     * reader sees there (hidden markup absent, widgets as their text), as opposed to {@link #documentText()}.
     *
     * @param pos a document offset on a line CodeMirror has drawn.
     * @return that line element's {@code textContent}.
     */
    public static String renderedLineText( final int pos ) {
        return evaluateOnView( """
            const { node } = view.domAtPos(arguments[0]);
            const start = node.nodeType === 1 ? node : node.parentElement;
            const line = start && start.closest('.cm-line');
            if (!line) throw new Error('no .cm-line renders document offset ' + arguments[0]);
            return line.textContent;
            """, pos );
    }

    /**
     * Sends key presses to whatever has focus — the editor after {@link #setCaret} — without moving the caret
     * (WebDriver's element {@code sendKeys} would first move a contenteditable's caret to its end). Fails when the
     * editor does not hold focus, so a press can never silently land elsewhere.
     *
     * @param keys the keys to press, in order.
     */
    public static void pressKeys( final CharSequence... keys ) {
        requireEditorFocus();
        Selenide.actions().sendKeys( keys ).perform();
    }

    /**
     * Presses {@code Mod+key} (Command on macOS, Control elsewhere) with the editor focused.
     *
     * @param key the key pressed with {@code Mod}, e.g. {@code "z"}.
     */
    public static void pressMod( final String key ) {
        requireEditorFocus();
        Selenide.actions().keyDown( PlatformKeys.mod() ).sendKeys( key ).keyUp( PlatformKeys.mod() ).perform();
    }

    private static void requireEditorFocus() {
        final Object focused = evaluateOnView( "return view.hasFocus;" );
        if ( !Boolean.TRUE.equals( focused ) ) {
            throw new IllegalStateException( "the page editor does not have keyboard focus (view.hasFocus=" + focused + ")" );
        }
    }

    /**
     * Waits until the editor document contains {@code expected}.
     *
     * @param expected substring of the document.
     * @param timeout how long to wait.
     * @return the document text that matched.
     */
    public static String awaitDocumentContaining( final String expected, final Duration timeout ) {
        return awaitDocument( doc -> doc.contains( expected ), "contain [" + expected + "]", timeout );
    }

    /**
     * Waits until the editor document equals {@code expected}, ignoring line-ending style and trailing whitespace.
     *
     * @param expected the whole document.
     * @param timeout how long to wait.
     * @return the document text that matched.
     */
    public static String awaitDocumentEquals( final String expected, final Duration timeout ) {
        final String want = normalizeTrailing( expected );
        return awaitDocument( doc -> normalizeTrailing( doc ).equals( want ), "equal [" + expected + "]", timeout );
    }

    /**
     * Polls {@link #documentText()} until {@code condition} holds; fails with the last document read otherwise.
     *
     * @param condition what the document must satisfy.
     * @param description the condition, for the failure message ("contain [x]").
     * @param timeout how long to wait.
     * @return the document text that matched.
     */
    public static String awaitDocument( final Predicate< String > condition, final String description, final Duration timeout ) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        String doc = documentText();
        while ( !condition.test( doc ) && System.currentTimeMillis() < deadline ) {
            Selenide.sleep( 100 );
            doc = documentText();
        }
        if ( !condition.test( doc ) ) {
            throw new AssertionError( "editor document did not " + description + " within " + timeout + "; it was:\n[" + doc + "]" );
        }
        return doc;
    }

    /**
     * Focuses the editor and empties its document via the keyboard (select-all, Delete), proving it empty through the
     * document. Callers editing a NEW page first wait for {@link #NEW_PAGE_BOILERPLATE}, so the boilerplate's async
     * load cannot land after the clear.
     *
     * @return the focused, empty {@code .cm-content} element, ready for {@code sendKeys}.
     */
    public static SelenideElement clearDocument() {
        // CodeMirror is driven through its contenteditable .cm-content: it handles keyboard events natively and fires
        // its onChange prop, updating React state (the wrapper is not an <input>/<textarea>, so value setters don't work).
        final SelenideElement cmContent = $( CONTENT ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        cmContent.click();
        // CodeMirror routes keyboard events through its focused editor only, and the click's focus can lag under
        // machine load. Without this wait the select-all chord lands unfocused and silently no-ops, the DELETE deletes
        // nothing, and new text APPENDS to the old content.
        $( EDITOR ).shouldHave( Condition.cssClass( "cm-focused" ), Duration.ofSeconds( 5 ) );
        cmContent.sendKeys( PlatformKeys.selectAll() );
        cmContent.sendKeys( Keys.DELETE );
        awaitDocumentEquals( "", Duration.ofSeconds( 5 ) );
        return cmContent;
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
        final SelenideElement cmContent = clearDocument();
        cmContent.sendKeys( text );

        // Prove the typed text actually landed before we sink more time into the preview check and Save below. This
        // is the check that catches the macOS select-all bug (old content left in the buffer — see PlatformKeys) or a
        // dropped/duplicated keystroke at its source, instead of it surfacing later as a confusing exact-text failure
        // on rendered output. It reads the CodeMirror document, so it holds in live mode too, where the screen hides
        // markup.
        try {
            awaitDocumentEquals( text, Duration.ofSeconds( 5 ) );
        } catch ( final AssertionError e ) {
            throw new AssertionError( "CodeMirror document did not match the text just typed, 5s after sendKeys() returned "
                + "(old content left behind, or a dropped/duplicated keystroke). Typed: [" + text + "]; " + e.getMessage(), e );
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
     * Normalises line endings and strips trailing whitespace, so a harmless
     * trailing-newline/whitespace difference does not fail a document
     * comparison. Deliberately does not touch leading or interior whitespace —
     * that is exactly what a dropped/duplicated keystroke would corrupt.
     *
     * @param value the text to normalise.
     * @return the normalised text.
     */
    private static String normalizeTrailing( final String value ) {
        return value == null ? "" : value.replace( "\r\n", "\n" ).stripTrailing();
    }

}
