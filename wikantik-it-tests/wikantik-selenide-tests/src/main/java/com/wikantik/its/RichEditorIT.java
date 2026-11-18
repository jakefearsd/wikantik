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
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

import static com.codeborne.selenide.Condition.cssClass;
import static com.codeborne.selenide.Condition.focused;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rich mode in a real browser: block markers stay hidden with the caret on their line (a heading's {@code #}, a list
 * marker, a code block's fences, which are drawn as a header with a language picker), the toolbar's mode control
 * switches to Source, and saving a page that was only looked at stores nothing new.
 */
public class RichEditorIT extends WithIntegrationTestSetup {

    private static final String PAGE = "RichEditorIT";
    private static final Duration WAIT = Duration.ofSeconds( 15 );
    private static final String CONTENT = "[data-testid=page-editor] .cm-content";

    private static final String HEADING = "Rich Heading";
    private static final String ITEM = "first item";
    private static final String CODE = "int answer = 42;";
    // Recognisably Markdown (see LivePreviewIT.BODY): nothing here scores as legacy wiki syntax, which would keep the
    // page in Source mode. No "#", "-" or backtick appears outside the three markers under test.
    private static final String BODY = "# " + HEADING + "\n\n"
        + "Intro paragraph.\n\n"
        + "- " + ITEM + "\n- second item\n\n"
        + "```java\n" + CODE + "\n```\n\n"
        + "Closing words.\n";
    /** {@link #BODY} plus a titled callout, for the header pickers. */
    private static final String PICKER_BODY = BODY + "\n> [!note] Heads up\n> Callout body.\n";
    private static final String EDITOR = "[data-testid=page-editor] .cm-editor";
    private static final String FENCE_PICKER = CONTENT + " .cm-lp-fence-header select";
    private static final String CALLOUT_PICKER = CONTENT + " .cm-lp-callout-title-widget select";

    @BeforeEach
    void login() throws Exception {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();
        RestSeedHelper.writePage( PAGE, BODY );
    }

    @AfterEach
    void cleanup() {
        RestSeedHelper.deletePageQuietly( PAGE );
    }

    @Test
    void markersStayHiddenUnderTheCaretAndAnUneditedSaveStoresNothing() throws Exception {
        final JsonObject seeded = fetchPage();
        final int seededVersion = seeded.get( "version" ).getAsInt();

        EditWikiPage.openInMode( PAGE, EditorMode.LIVE );
        $( "[data-testid=page-editor] .cm-editor" ).shouldHave( cssClass( "cm-live-preview" ), WAIT );
        EditWikiPage.awaitDocumentEquals( BODY, WAIT );
        // The code block's opening fence is drawn as a header whose picker shows the language.
        $( CONTENT + " .cm-lp-fence-header select" ).shouldBe( visible, WAIT ).shouldHave( value( "java" ) );

        // Carets go into the lines' text: Rich mode moves a caret out of a hidden marker, and setCaret asserts it stayed.
        for ( final String onLine : List.of( HEADING, ITEM, CODE ) ) {
            final int pos = BODY.indexOf( onLine ) + 3;
            EditWikiPage.setCaret( pos );
            final String line = EditWikiPage.renderedLineText( pos );
            assertTrue( line.endsWith( onLine ), "the caret's line should show its text; it shows: " + line );
            assertNoMarkerShows( "with the caret on \"" + onLine + "\"" );
        }
        assertEquals( HEADING, EditWikiPage.renderedLineText( BODY.indexOf( HEADING ) ),
            "the heading line shows its text only, with the caret on it or not" );

        // Source with the toolbar's mode control: the markers are text again.
        $( "[data-testid=editor-mode] [data-mode=source]" ).click();
        new EditWikiPage().assertMode( EditorMode.SOURCE );
        $( "[data-testid=page-editor] .cm-editor" ).shouldNotHave( cssClass( "cm-live-preview" ), WAIT );
        $( CONTENT ).shouldHave( text( "# " + HEADING ), WAIT ).shouldHave( text( "```java" ) ).shouldHave( text( "- " + ITEM ) );

        assertEquals( BODY, EditWikiPage.documentText(), "moving the caret through Rich mode and switching to Source "
            + "must leave the editor document the seeded page, byte for byte" );

        EditWikiPage.publish();
        new WebDriverWait( WebDriverRunner.getWebDriver(), WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT );

        // As LivePreviewRoundTripIT: the server stores nothing when the text equals the current version's.
        final JsonObject saved = fetchPage();
        assertEquals( seededVersion, saved.get( "version" ).getAsInt(), "an unedited save must not create a version" );
        assertEquals( seeded.get( "content" ).getAsString(), saved.get( "content" ).getAsString(),
            "an unedited save must leave the stored text byte-identical" );
        assertEquals( seeded.get( "contentHash" ).getAsString(), saved.get( "contentHash" ).getAsString() );
    }

    // ---- header pickers: the write guard rests on the browser's real event order, so it is driven with real input ----
    // Selenide's selectOption sets the value by script and would be (rightly) refused as a change nobody opened the
    // picker for; these tests open the picker with a real click or key press and choose with real key presses.

    @Test
    void aLanguagePickedAfterOpeningThePickerWithThePointerChangesOnlyTheInfoString() throws Exception {
        openPickerPage();
        EditWikiPage.setCaret( PICKER_BODY.indexOf( CODE ) + 3 );
        $( FENCE_PICKER ).shouldBe( visible, WAIT ).shouldHave( value( "java" ) ).click();
        // The list is open on "java"; the next option is "javascript".
        Selenide.actions().sendKeys( Keys.ARROW_DOWN, Keys.ENTER ).perform();
        EditWikiPage.awaitDocumentEquals( PICKER_BODY.replace( "```java\n", "```javascript\n" ), WAIT );
        $( FENCE_PICKER ).shouldHave( value( "javascript" ), WAIT );
    }

    @Test
    void aLanguagePickedWithTheKeyboardAloneChangesOnlyTheInfoString() throws Exception {
        openPickerPage();
        EditWikiPage.setCaret( PICKER_BODY.indexOf( CODE ) + 3 );
        $( FENCE_PICKER ).shouldBe( visible, WAIT ).shouldHave( value( "java" ) );
        Selenide.executeJavaScript( "document.querySelector(arguments[0]).focus();", FENCE_PICKER );
        $( FENCE_PICKER ).shouldBe( focused, WAIT );
        Selenide.actions().sendKeys( Keys.ENTER ).perform();          // opens the list
        Selenide.actions().sendKeys( Keys.ARROW_DOWN, Keys.ENTER ).perform(); // "javascript", taken
        EditWikiPage.awaitDocumentEquals( PICKER_BODY.replace( "```java\n", "```javascript\n" ), WAIT );
        $( FENCE_PICKER ).shouldHave( value( "javascript" ), WAIT );
    }

    @Test
    void aDismissedPickerDoesNotTakeTheKeysMeantForTheEditor() throws Exception {
        openPickerPage();
        EditWikiPage.setCaret( PICKER_BODY.indexOf( CODE ) + 3 );
        $( FENCE_PICKER ).shouldBe( visible, WAIT ).shouldHave( value( "java" ) ).click();
        Selenide.actions().sendKeys( Keys.ESCAPE ).perform();
        // Meant for the editor: move the caret, type a letter. Neither may reach the picker as a choice.
        Selenide.actions().sendKeys( Keys.ARROW_DOWN ).perform();
        Selenide.actions().sendKeys( "Z" ).perform();
        final String doc = EditWikiPage.awaitDocument( d -> d.contains( "Z" ), "the typed Z in the editor's text", WAIT );
        assertTrue( doc.contains( "```java\n" ), "the fence's info string must be unchanged; document:\n" + doc );
        assertEquals( PICKER_BODY, doc.replaceFirst( "Z", "" ), "exactly one character, the typed Z, may have been "
            + "added to the document" );
        $( FENCE_PICKER ).shouldHave( value( "java" ) );
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT );
    }

    @Test
    void aCalloutTypePickedWithThePointerChangesOnlyTheWordInTheMarker() throws Exception {
        openPickerPage();
        EditWikiPage.setCaret( PICKER_BODY.indexOf( "Heads up" ) + 3 );
        $( CALLOUT_PICKER ).shouldBe( visible, WAIT ).shouldHave( value( "note" ) ).click();
        // The list is open on "Note"; the next option is "Tip".
        Selenide.actions().sendKeys( Keys.ARROW_DOWN, Keys.ENTER ).perform();
        EditWikiPage.awaitDocumentEquals( PICKER_BODY.replace( "[!note]", "[!tip]" ), WAIT );
        $( CALLOUT_PICKER ).shouldHave( value( "tip" ), WAIT );
    }

    /** Seeds the page with a code block and a callout and opens it in Rich mode, fully loaded. */
    private static void openPickerPage() throws Exception {
        RestSeedHelper.writePage( PAGE, PICKER_BODY );
        EditWikiPage.openInMode( PAGE, EditorMode.LIVE );
        $( EDITOR ).shouldHave( cssClass( "cm-live-preview" ), WAIT );
        EditWikiPage.awaitDocumentEquals( PICKER_BODY, WAIT );
    }

    /** No drawn editor line shows a {@code #}, a backtick or a leading list dash (the page has none outside its markers). */
    private static void assertNoMarkerShows( final String when ) {
        final List< String > lines = EditWikiPage.evaluateOnView( """
            // A picker's option labels are not line text: read each line without its <select>s.
            return [...view.contentDOM.querySelectorAll('.cm-line')].map((line) => {
              const copy = line.cloneNode(true);
              copy.querySelectorAll('select').forEach((s) => s.remove());
              return copy.textContent;
            });
            """ );
        assertTrue( lines.size() >= 9, "the whole page should be drawn (9 non-trailing lines); drawn lines: " + lines );
        for ( final String line : lines ) {
            assertFalse( line.contains( "#" ), "a heading marker shows " + when + ": " + lines );
            assertFalse( line.contains( "`" ), "a code fence shows " + when + ": " + lines );
            assertFalse( line.startsWith( "-" ), "a list marker shows " + when + ": " + lines );
        }
    }

    private static JsonObject fetchPage() throws Exception {
        return JsonParser.parseString( RestSeedHelper.get( "/api/pages/" + PAGE ) ).getAsJsonObject();
    }
}
