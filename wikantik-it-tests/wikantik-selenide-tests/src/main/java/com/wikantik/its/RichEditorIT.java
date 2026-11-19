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
import com.wikantik.its.environment.PlatformKeys;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

import static com.codeborne.selenide.CollectionCondition.exactTexts;
import static com.codeborne.selenide.CollectionCondition.size;
import static com.codeborne.selenide.Condition.cssClass;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exactValue;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.focused;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rich mode in a real browser: block markers stay hidden with the caret on their line (a heading's {@code #}, a list
 * marker, a code block's fences, which are drawn as a header with a language picker), the toolbar's mode control
 * switches to Source, and saving a page that was only looked at stores nothing new. A link stays a chip under the
 * caret, and its card retargets it. A table is edited as a grid: a click and Tab move between its cells, the controls
 * add a column, and what is saved is the Markdown table the reader shows.
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
    /** {@link #BODY} plus a wikilink (no display text: an aliased link counts towards the legacy-syntax score). */
    private static final String OLD_LINK = "[[RichEditorOldTarget]]";
    private static final String NEW_LINK = "[[RichEditorNewTarget]]";
    private static final String LINK_LINE = "See " + OLD_LINK + " for the plan.";
    private static final String LINK_BODY = BODY + "\n" + LINK_LINE + "\n";
    private static final String LINK_CHIP = CONTENT + " .cm-lp-linkchip";
    private static final String LINK_CARD = "[data-testid=link-card]";
    private static final String CARD_PAGE_FIELD = LINK_CARD + " #link-card-target";
    private static final String CARD_URL_FIELD = LINK_CARD + " #link-card-url";
    private static final String CARD_TEXT_FIELD = LINK_CARD + " #link-card-text";
    private static final String FENCE_PICKER = CONTENT + " .cm-lp-fence-header select";
    private static final String CALLOUT_PICKER = CONTENT + " .cm-lp-callout-title-widget select";
    /** A table the grid draws (whole rows, outer pipes), after {@link #BODY}. */
    private static final String TABLE = "| Name | Count |\n| --- | --- |\n| apple | 1 |\n| pear | 2 |\n";
    private static final String TABLE_BODY = BODY + "\n" + TABLE;
    private static final String GRID_CELL = CONTENT + " .cm-lp-table-row .cm-lp-cell";
    /** The table controls' toolbar, drawn in the editor's scroller while the caret is in a grid table. */
    private static final String TABLE_CONTROLS = "[data-testid=page-editor] [role=toolbar][aria-label=Table]";

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

    // ---- the link card ----

    @Test
    void aWikilinkRetargetedThroughItsCardIsSavedWithOnlyItsPageChanged() throws Exception {
        RestSeedHelper.writePage( PAGE, LINK_BODY );
        final String seeded = fetchPage().get( "content" ).getAsString();
        EditWikiPage.openInMode( PAGE, EditorMode.LIVE );
        $( EDITOR ).shouldHave( cssClass( "cm-live-preview" ), WAIT );
        EditWikiPage.awaitDocumentEquals( LINK_BODY, WAIT );

        // The caret on the link's own line, in its text and right at the link's end: the link stays a chip.
        final int lineFrom = LINK_BODY.indexOf( LINK_LINE );
        for ( final int pos : List.of( lineFrom + 2, lineFrom + LINK_LINE.indexOf( OLD_LINK ) + OLD_LINK.length() ) ) {
            EditWikiPage.setCaret( pos );
            assertEquals( LINK_LINE.replace( OLD_LINK, "RichEditorOldTarget" ), EditWikiPage.renderedLineText( pos ),
                "the link's line, with the caret on it at offset " + pos + ", shows the link as a chip" );
        }

        // A click on the chip opens its card, focused on the page field.
        $( LINK_CHIP ).shouldBe( visible, WAIT ).shouldHave( text( "RichEditorOldTarget" ) ).click();
        $( LINK_CARD ).shouldBe( visible, WAIT );
        $( CARD_PAGE_FIELD ).shouldBe( focused, WAIT ).shouldHave( exactValue( "RichEditorOldTarget" ) );
        assertEquals( LINK_BODY, EditWikiPage.documentText(), "opening the card must not change the document" );

        // As a user would: click into the field, select all, type the new name; Enter commits the card.
        // The chord goes through the element's sendKeys, which holds the modifier down for the "a"; an Actions
        // sendKeys presses and releases each key of the chord in turn, so the "a" would be typed into the field.
        $( CARD_PAGE_FIELD ).click();
        $( CARD_PAGE_FIELD ).shouldBe( focused, WAIT );
        $( CARD_PAGE_FIELD ).sendKeys( PlatformKeys.selectAll() );
        $( CARD_PAGE_FIELD ).sendKeys( "RichEditorNewTarget" );
        $( CARD_PAGE_FIELD ).shouldHave( exactValue( "RichEditorNewTarget" ), WAIT );
        assertEquals( LINK_BODY, EditWikiPage.documentText(), "typing in the card must not change the document" );
        Selenide.actions().sendKeys( Keys.ENTER ).perform();

        final String retargeted = LINK_BODY.replace( OLD_LINK, NEW_LINK );
        EditWikiPage.awaitDocumentEquals( retargeted, WAIT );
        $( LINK_CARD ).shouldNot( exist, WAIT );
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT ); // the focus is back in the text
        $( LINK_CHIP ).shouldHave( text( "RichEditorNewTarget" ), WAIT );

        // One undo step restores the page; redo brings the new target back.
        EditWikiPage.pressMod( "z" );
        EditWikiPage.awaitDocumentEquals( LINK_BODY, WAIT );
        Selenide.actions().keyDown( PlatformKeys.mod() ).keyDown( Keys.SHIFT ).sendKeys( "z" ).keyUp( Keys.SHIFT )
            .keyUp( PlatformKeys.mod() ).perform();
        EditWikiPage.awaitDocumentEquals( retargeted, WAIT );

        EditWikiPage.publish();
        new WebDriverWait( WebDriverRunner.getWebDriver(), WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT );
        assertEquals( seeded.replace( OLD_LINK, NEW_LINK ), fetchPage().get( "content" ).getAsString(),
            "the saved source differs from the seeded one in the link's page name only" );
    }

    @Test
    void insertLinkOnASelectionWritesNothingUntilItsCardIsCommitted() throws Exception {
        final String seeded = fetchPage().get( "content" ).getAsString();
        EditWikiPage.openInMode( PAGE, EditorMode.LIVE );
        $( EDITOR ).shouldHave( cssClass( "cm-live-preview" ), WAIT );
        EditWikiPage.awaitDocumentEquals( BODY, WAIT );
        final int from = BODY.indexOf( "Closing words" );
        final int to = from + "Closing".length();
        final String address = "https://example.org/plan";

        // Mod-K on the selection: the card, on the address, with the selected text; the page is not touched.
        select( from, to );
        EditWikiPage.pressMod( "k" );
        $( LINK_CARD ).shouldBe( visible, WAIT );
        $( CARD_URL_FIELD ).shouldBe( focused, WAIT ).shouldHave( exactValue( "" ) );
        $( CARD_TEXT_FIELD ).shouldHave( exactValue( "Closing" ) );
        Selenide.actions().sendKeys( "https://example.org/never" ).perform();
        $( CARD_URL_FIELD ).shouldHave( exactValue( "https://example.org/never" ), WAIT );
        assertEquals( BODY, EditWikiPage.documentText(), "typing in the card must not change the document" );

        // Escape: nothing was written, and the focus is back in the text.
        Selenide.actions().sendKeys( Keys.ESCAPE ).perform();
        $( LINK_CARD ).shouldNot( exist, WAIT );
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT );
        assertEquals( BODY, EditWikiPage.documentText(), "Escape must leave the document as it was" );

        // Again, committed with Enter: the selection becomes a link, in one change.
        select( from, to );
        EditWikiPage.pressMod( "k" );
        $( CARD_URL_FIELD ).shouldBe( focused, WAIT );
        Selenide.actions().sendKeys( address ).perform();
        $( CARD_URL_FIELD ).shouldHave( exactValue( address ), WAIT );
        Selenide.actions().sendKeys( Keys.ENTER ).perform();
        final String linked = BODY.replace( "Closing words", "[Closing](" + address + ") words" );
        EditWikiPage.awaitDocumentEquals( linked, WAIT );
        $( LINK_CARD ).shouldNot( exist, WAIT );
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT );
        $( LINK_CHIP ).shouldBe( visible, WAIT ).shouldHave( text( "Closing" ) );

        // One undo step: nothing but the link was put in the history.
        EditWikiPage.pressMod( "z" );
        EditWikiPage.awaitDocumentEquals( BODY, WAIT );
        Selenide.actions().keyDown( PlatformKeys.mod() ).keyDown( Keys.SHIFT ).sendKeys( "z" ).keyUp( Keys.SHIFT )
            .keyUp( PlatformKeys.mod() ).perform();
        EditWikiPage.awaitDocumentEquals( linked, WAIT );

        EditWikiPage.publish();
        new WebDriverWait( WebDriverRunner.getWebDriver(), WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT );
        assertEquals( seeded.replace( "Closing words", "[Closing](" + address + ") words" ),
            fetchPage().get( "content" ).getAsString(), "the saved source is the seeded one with the selection made a link" );
    }

    // ---- the table grid ----

    @Test
    void aTableEditedInTheGridAndGivenAColumnIsSavedAsMarkdownAndShownByTheReader() throws Exception {
        RestSeedHelper.writePage( PAGE, TABLE_BODY );
        final String seeded = fetchPage().get( "content" ).getAsString();
        EditWikiPage.openInMode( PAGE, EditorMode.LIVE );
        $( EDITOR ).shouldHave( cssClass( "cm-live-preview" ), WAIT );
        EditWikiPage.awaitDocumentEquals( TABLE_BODY, WAIT );

        // A click on a cell's text puts the caret in that cell; what is typed goes in at the caret.
        $$( GRID_CELL ).findBy( exactText( "apple" ) ).shouldBe( visible, WAIT ).click();
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT );
        final int cellFrom = TABLE_BODY.indexOf( "apple" );
        final int into = EditWikiPage.caretOffset() - cellFrom;
        assertTrue( into >= 0 && into <= "apple".length(), "the caret is in the clicked cell's text (offsets "
            + cellFrom + " to " + ( cellFrom + "apple".length() ) + "); it is at " + ( cellFrom + into ) );
        EditWikiPage.pressKeys( "X" );
        final String apple = "apple".substring( 0, into ) + "X" + "apple".substring( into );
        EditWikiPage.awaitDocumentEquals( TABLE_BODY.replace( "| apple |", "| " + apple + " |" ), WAIT );

        // Tab: the start of the next cell.
        EditWikiPage.pressKeys( Keys.TAB );
        EditWikiPage.pressKeys( "9" );
        EditWikiPage.awaitDocumentEquals( TABLE_BODY.replace( "| apple | 1 |", "| " + apple + " | 91 |" ), WAIT );

        // "+ col" in the controls adds an empty column right of the caret's and puts the caret in its cell of this row.
        $( TABLE_CONTROLS ).shouldBe( visible, WAIT );
        $( TABLE_CONTROLS + " button[aria-label='Insert column right']" ).shouldBe( visible, WAIT ).click();
        final String widened = "| Name | Count |  |\n| --- | --- | --- |\n| " + apple + " | 91 |  |\n| pear | 2 |  |\n";
        EditWikiPage.awaitDocumentEquals( TABLE_BODY.replace( TABLE, widened ), WAIT );
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT ); // the press on the control left the focus in the text
        EditWikiPage.pressKeys( "ripe" );
        final String edited = widened.replace( "| 91 |  |", "| 91 | ripe |" );
        EditWikiPage.awaitDocumentEquals( TABLE_BODY.replace( TABLE, edited ), WAIT );
        $( CONTENT + " .cm-lp-table-block" ).shouldNot( exist ); // still the grid: every row has its three cells

        EditWikiPage.publish();
        new WebDriverWait( WebDriverRunner.getWebDriver(), WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT );
        assertTrue( seeded.contains( TABLE ), "the seeded page holds the table as written; it is:\n" + seeded );
        assertEquals( seeded.replace( TABLE, edited ), fetchPage().get( "content" ).getAsString(),
            "the saved source differs from the seeded one in the edited cells and the new column only" );

        // The reader, loaded afresh, shows the table with its third column.
        Selenide.refresh();
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT );
        final String readerTable = "[data-testid=page-view] .article-prose table";
        $( readerTable ).shouldBe( visible, WAIT );
        $$( readerTable + " thead th" ).shouldHave( size( 3 ), WAIT );
        $$( readerTable + " tbody tr" ).shouldHave( size( 2 ), WAIT );
        $$( readerTable + " tbody tr" ).first().$$( "td" ).shouldHave( exactTexts( apple, "91", "ripe" ), WAIT );
    }

    /** Selects the document range {@code [from, to)} with the editor focused (both ends in content, on one line). */
    private static void select( final int from, final int to ) {
        EditWikiPage.evaluateOnView( """
            view.focus();
            view.dispatch({ selection: { anchor: arguments[0], head: arguments[1] }, scrollIntoView: true });
            return null;
            """, from, to );
        $( EDITOR ).shouldHave( cssClass( "cm-focused" ), WAIT );
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
