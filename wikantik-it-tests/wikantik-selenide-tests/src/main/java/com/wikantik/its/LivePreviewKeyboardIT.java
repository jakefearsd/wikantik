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
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.wikantik.pages.spa.EditWikiPage.caretOffset;
import static com.wikantik.pages.spa.EditWikiPage.documentText;
import static com.wikantik.pages.spa.EditWikiPage.pressKeys;
import static com.wikantik.pages.spa.EditWikiPage.pressMod;
import static com.wikantik.pages.spa.EditWikiPage.renderedLineText;
import static com.wikantik.pages.spa.EditWikiPage.setCaret;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-mode keyboard behaviour around hidden markup and widgets, in a real browser. Every check reads the CodeMirror
 * state through {@link EditWikiPage} — the document text and the caret's document offset
 * ({@code state.selection.main.head}) — never the screen, which in live mode hides markup off the caret's line.
 * Screen reads appear only where the question is what the screen reveals.
 */
public class LivePreviewKeyboardIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 15 );

    /** One line carrying three kinds of hidden markup: emphasis, an aliased wikilink and a plugin chip. */
    private static final String MIXED_LINE = "Intro **bold** then [[Page|alias]] and [{TableOfContents}] end";
    private static final String MIXED_PAGE = "LivePreviewKeyboardMixedIT";
    private static final String MIXED_BODY = "# Mixed markup\n\n" + MIXED_LINE + "\n\nAfter the line\n";

    /** Starts and ends with hidden markup, so its on-screen ends differ from its document ends. */
    private static final String HOME_END_LINE = "**Bold** [[Page]] [{TableOfContents}]";
    private static final String CHIP_PREFIX = "Before [{TableOfContents}]";
    private static final String CHIP_LINE = CHIP_PREFIX + " after";
    private static final String TASK_LINE = "- [ ] open task";
    private static final String TABLE_FIRST_LINE = "| A | B |";

    /**
     * A heading long enough to wrap onto several visual rows, directly above a table (a heading, not a paragraph: a
     * table cannot interrupt a paragraph line, which would become its header row instead).
     */
    private static final String WRAPPED_LINE = "## " + "A long heading that keeps going across the editor pane so that "
        .repeat( 8 ).trim();
    private static final String WRAP_PAGE = "LivePreviewKeyboardWrapIT";
    private static final String WRAP_BODY = "# Wrapping\n\n" + WRAPPED_LINE + "\n"
        + TABLE_FIRST_LINE + "\n| --- | --- |\n| one | two |\n\nAfter the table\n";

    private static final String PAGE = "LivePreviewKeyboardIT";
    // One plugin call and no aliased link: a recognisably-Markdown page (WikiToMarkdownConverter.isLikelyWikiSyntax
    // stays below its threshold), so the editor offers live mode.
    private static final String BODY = "# Keyboard\n\n"
        + HOME_END_LINE + "\n\n"
        + CHIP_LINE + "\n\n"
        + TASK_LINE + "\n\n"
        + "Above the table\n\n"
        + TABLE_FIRST_LINE + "\n| --- | --- |\n| one | two |\n\n"
        + "Last line\n";

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
        RestSeedHelper.deletePageQuietly( MIXED_PAGE );
        RestSeedHelper.deletePageQuietly( WRAP_PAGE );
    }

    @Test
    void arrowKeysStepOneDocumentPositionAcrossRevealedMarkup() throws Exception {
        RestSeedHelper.writePage( MIXED_PAGE, MIXED_BODY );
        final String doc = openLive( MIXED_PAGE, MIXED_BODY );
        final int from = lineStart( doc, MIXED_LINE );
        final int to = from + MIXED_LINE.length();

        // Off the caret's line the markup is hidden: the chip is drawn, and the screen is not the source.
        $( "span.cm-lp-chip.cm-lp-chip-plugin" ).shouldBe( visible, WAIT );
        final String hiddenView = renderedLineText( from );
        assertTrue( !hiddenView.equals( MIXED_LINE ) && !hiddenView.contains( "**" ),
            "off the caret's line the markup should be hidden; the line showed [" + hiddenView + "]" );

        setCaret( from );
        awaitRenderedLine( from, MIXED_LINE );
        for ( int i = 1; i <= MIXED_LINE.length(); i++ ) {
            pressKeys( Keys.ARROW_RIGHT );
            assertEquals( from + i, caretOffset(), "ArrowRight press " + i + " from the line start" );
            assertEquals( MIXED_LINE, renderedLineText( from ), "markup stays revealed on the caret's line (press " + i + ")" );
        }
        assertEquals( to, caretOffset() );
        for ( int i = 1; i <= MIXED_LINE.length(); i++ ) {
            pressKeys( Keys.ARROW_LEFT );
            assertEquals( to - i, caretOffset(), "ArrowLeft press " + i + " from the line end" );
        }
        assertEquals( from, caretOffset() );
        assertEquals( doc, documentText(), "moving the caret must not change the document" );
    }

    @Test
    void endAndHomeLandOnTheLinesDocumentEnds() {
        // With line wrapping on, End/Home stop at a visual wrap point by design, and at the IT's 1366px window the
        // editor pane (beside the preview, the outline rail and the sidebar) wraps even a short line. Give the line
        // room: a wider window, and the rail closed through the app's own remembered choice.
        WebDriverRunner.getWebDriver().manage().window().setSize( new Dimension( 1920, 1080 ) );
        Selenide.executeJavaScript( "localStorage.setItem('wikantik.editor.railOpen', 'false')" );
        final String doc = openLive( PAGE, BODY );
        final int from = lineStart( doc, HOME_END_LINE );
        final int to = from + HOME_END_LINE.length();

        setCaret( from + HOME_END_LINE.indexOf( "[[" ) );
        awaitRenderedLine( from, HOME_END_LINE );
        assertSingleVisualLine( from, to );
        pressKeys( Keys.END );
        assertEquals( to, caretOffset(), "End lands after the line's last source character (the chip's ']')" );
        pressKeys( Keys.HOME );
        assertEquals( from, caretOffset(), "Home lands before the line's first source character (the leading '**')" );
        pressKeys( Keys.END );
        assertEquals( to, caretOffset(), "End again, from the line start" );
        assertEquals( doc, documentText(), "End/Home must not change the document" );
    }

    @Test
    void backspaceRightAfterAChipDeletesOnlyItsClosingBracket() {
        final String doc = openLive( PAGE, BODY );
        final int from = lineStart( doc, CHIP_LINE );
        final int chipEnd = from + CHIP_PREFIX.length();

        awaitRenderedLine( from, "Before ⚙ TableOfContents after" ); // drawn as a chip off the caret's line
        setCaret( chipEnd );
        awaitRenderedLine( from, CHIP_LINE ); // the caret's line shows its source
        pressKeys( Keys.BACK_SPACE );

        final String expected = doc.substring( 0, chipEnd - 1 ) + doc.substring( chipEnd );
        awaitExactDocument( expected );
        assertEquals( doc.length() - 1, documentText().length(), "exactly one character deleted" );
        assertEquals( chipEnd - 1, caretOffset() );
        awaitRenderedLine( from, "Before [{TableOfContents} after" );
    }

    @Test
    void tickingATaskThenUndoRestoresTheExactText() {
        final String doc = openLive( PAGE, BODY );
        final int taskFrom = lineStart( doc, TASK_LINE );
        setCaret( 0 ); // focus the editor on another line, so the task line is drawn with its checkbox

        $( "input.cm-lp-task" ).shouldBe( visible, WAIT ).shouldNotBe( checked ).click();
        final String ticked = doc.substring( 0, taskFrom ) + "- [x] open task" + doc.substring( taskFrom + TASK_LINE.length() );
        awaitExactDocument( ticked );
        $( "input.cm-lp-task" ).shouldBe( checked, WAIT );

        pressMod( "z" );
        awaitExactDocument( doc );
        $( "input.cm-lp-task" ).shouldBe( visible, WAIT ).shouldNotBe( checked, WAIT );
    }

    @Test
    void selectAllThenTypingReplacesTheWholeDocumentAndUndoRestoresIt() {
        final String doc = openLive( PAGE, BODY );
        setCaret( 0 );

        pressMod( "a" );
        pressKeys( "x" );
        awaitExactDocument( "x" );

        pressMod( "z" );
        awaitExactDocument( doc );
    }

    @Test
    void arrowDownIntoATableRevealsItsSourceOnItsFirstLine() {
        final String doc = openLive( PAGE, BODY );
        final int tableFrom = lineStart( doc, TABLE_FIRST_LINE );
        final int lineAbove = tableFrom - 1; // the empty line directly above the table

        $( ".cm-lp-table-block table.cm-lp-table" ).shouldBe( visible, WAIT );
        setCaret( lineAbove );
        $( ".cm-lp-table-block table.cm-lp-table" ).shouldBe( visible, WAIT ); // the line above does not reveal it

        pressKeys( Keys.ARROW_DOWN );
        final int landed = caretOffset();
        assertEquals( tableFrom, landed, "ArrowDown from the line above lands at the start of the table's first line "
            + "(line " + lineNumber( doc, tableFrom ) + "); it landed on line " + lineNumber( doc, landed ) + " ["
            + lineAt( doc, landed ) + "]" );
        awaitRenderedLine( tableFrom, TABLE_FIRST_LINE );
        $( ".cm-lp-table-block" ).shouldNot( exist, WAIT );
        assertEquals( doc, documentText(), "moving into the table must not change the document" );
    }

    /**
     * The wrapped-line edge of the vertical-motion redirect: only the LAST visual row of a wrapped line is next to the
     * table below it. ArrowDown from the first row moves to the line's next row (CodeMirror's own motion) and leaves
     * the table drawn; ArrowDown from the last row lands at the start of the table's first line, revealing it.
     */
    @Test
    void arrowDownOnAWrappedLineAboveATableEntersItOnlyFromTheLastVisualRow() throws Exception {
        RestSeedHelper.writePage( WRAP_PAGE, WRAP_BODY );
        final String doc = openLive( WRAP_PAGE, WRAP_BODY );
        final int from = lineStart( doc, WRAPPED_LINE );
        final int to = from + WRAPPED_LINE.length();
        final int tableFrom = lineStart( doc, TABLE_FIRST_LINE );
        assertEquals( to + 1, tableFrom, "the table's first line must directly follow the wrapped line" );

        $( ".cm-lp-table-block table.cm-lp-table" ).shouldBe( visible, WAIT );
        final int firstRowCaret = from + 3; // just after the revealed "## ", on the first visual row
        setCaret( firstRowCaret );
        awaitRenderedLine( from, WRAPPED_LINE ); // the caret's line shows its source, so its rows are final
        final Map< ?, ? > rows = visualRows( from, to );
        assertTrue( ( ( Number ) rows.get( "rows" ) ).intValue() >= 3, "the heading above the table must wrap onto at "
            + "least three visual rows, or there is no first/last row to tell apart: " + rows );
        assertEquals( 0, rowOf( rows, firstRowCaret ), "the first caret must sit on the first visual row: " + rows );

        pressKeys( Keys.ARROW_DOWN );
        final int afterFirst = caretOffset();
        assertTrue( afterFirst > firstRowCaret && afterFirst <= to, "ArrowDown from the wrapped line's first visual row "
            + "stays on that line; it landed at " + afterFirst + " on line " + lineNumber( doc, afterFirst ) + " ["
            + lineAt( doc, afterFirst ) + "]" );
        assertEquals( 1, rowOf( rows, afterFirst ), "ArrowDown from the first row moves to the second row: " + rows );
        $( ".cm-lp-table-block table.cm-lp-table" ).shouldBe( visible ); // the table is still drawn, not revealed

        final int lastRowStart = ( ( Number ) rows.get( "lastRowStart" ) ).intValue();
        final int lastRowCaret = Math.min( to, lastRowStart + Math.max( 1, ( to - lastRowStart ) / 2 ) );
        setCaret( lastRowCaret );
        assertEquals( ( ( Number ) rows.get( "rows" ) ).intValue() - 1, rowOf( rows, lastRowCaret ),
            "the second caret must sit on the last visual row: " + rows );
        pressKeys( Keys.ARROW_DOWN );
        final int landed = caretOffset();
        assertEquals( tableFrom, landed, "ArrowDown from the wrapped line's last visual row lands at the start of the "
            + "table's first line; it landed on line " + lineNumber( doc, landed ) + " [" + lineAt( doc, landed ) + "]" );
        awaitRenderedLine( tableFrom, TABLE_FIRST_LINE );
        $( ".cm-lp-table-block" ).shouldNot( exist, WAIT );
        assertEquals( doc, documentText(), "moving the caret must not change the document" );
    }

    /**
     * The visual rows CodeMirror draws the line {@code from..to} on: {@code rows} (count), {@code tops} (each row's
     * top relative to the line's first row, ascending — client coordinates shift whenever a key press scrolls the
     * editor) and {@code lastRowStart} (the first document offset drawn on the last row).
     */
    private static Map< ?, ? > visualRows( final int from, final int to ) {
        return EditWikiPage.evaluateOnView( """
            const [from, to] = [arguments[0], arguments[1]];
            const half = view.defaultLineHeight / 2;
            const topOf = (pos, side) => {
              const c = view.coordsAtPos(pos, side);
              if (!c) throw new Error('offset ' + pos + ' is not drawn');
              return c.top;
            };
            const origin = topOf(from, 1);
            const tops = [];
            let lastRowStart = from;
            for (let pos = from; pos <= to; pos++) {
              const top = topOf(pos, pos === to ? -1 : 1) - origin;
              if (!tops.length || top - tops[tops.length - 1] > half) { tops.push(top); lastRowStart = pos; }
            }
            return { from, rows: tops.length, tops, lastRowStart, lineHeight: view.defaultLineHeight,
                     contentWidth: view.contentDOM.clientWidth };
            """, from, to );
    }

    /** The 0-based visual row (of {@code rows}, from {@link #visualRows}) offset {@code pos} is drawn on. */
    private static int rowOf( final Map< ?, ? > rows, final int pos ) {
        final double top = ( ( Number ) EditWikiPage.evaluateOnView( """
            const c = view.coordsAtPos(arguments[0], view.state.selection.main.head === arguments[0]
              ? (view.state.selection.main.assoc || 1) : 1);
            const origin = view.coordsAtPos(arguments[1], 1);
            if (!c || !origin) throw new Error('offset ' + arguments[0] + ' or ' + arguments[1] + ' is not drawn');
            return c.top - origin.top;
            """, pos, ( ( Number ) rows.get( "from" ) ).intValue() ) ).doubleValue();
        final double half = ( ( Number ) rows.get( "lineHeight" ) ).doubleValue() / 2;
        final List< ? > tops = ( List< ? > ) rows.get( "tops" );
        for ( int i = 0; i < tops.size(); i++ ) {
            if ( Math.abs( ( ( Number ) tops.get( i ) ).doubleValue() - top ) < half ) {
                return i;
            }
        }
        throw new IllegalStateException( "offset " + pos + " (top " + top + ") is on none of the rows " + rows );
    }

    /** Opens {@code page} in live mode and returns its document, after proving it is the seeded {@code body}. */
    private static String openLive( final String page, final String body ) {
        EditWikiPage.openInMode( page, EditorMode.LIVE );
        return EditWikiPage.awaitDocumentEquals( body, WAIT );
    }

    /** The document offset where {@code line} starts; it must be a whole line of {@code doc}. */
    private static int lineStart( final String doc, final String line ) {
        final int at = doc.indexOf( "\n" + line + "\n" );
        if ( at < 0 ) {
            throw new IllegalStateException( "no line [" + line + "] in the document:\n" + doc );
        }
        return at + 1;
    }

    /** 1-based line number of document offset {@code pos}. */
    private static int lineNumber( final String doc, final int pos ) {
        return (int) doc.substring( 0, pos ).chars().filter( c -> c == '\n' ).count() + 1;
    }

    /** The text of the document line holding offset {@code pos}. */
    private static String lineAt( final String doc, final int pos ) {
        final int start = doc.lastIndexOf( '\n', pos - 1 ) + 1;
        final int end = doc.indexOf( '\n', pos );
        return doc.substring( start, end < 0 ? doc.length() : end );
    }

    /**
     * Precondition for End/Home checks: the line from {@code from} to {@code to} is drawn on one visual line (with
     * line wrapping on, End/Home stop at wrap points by design — a wrapped line would be a test-setup problem).
     */
    private static void assertSingleVisualLine( final int from, final int to ) {
        final Map< ?, ? > geometry = EditWikiPage.evaluateOnView( """
            const a = view.coordsAtPos(arguments[0], 1);
            const b = view.coordsAtPos(arguments[1], -1);
            if (!a || !b) throw new Error('line ' + arguments[0] + '..' + arguments[1] + ' is not drawn');
            return { oneLine: Math.abs(a.top - b.top) < view.defaultLineHeight / 2, startTop: a.top, endTop: b.top,
                     lineHeight: view.defaultLineHeight, contentWidth: view.contentDOM.clientWidth,
                     charWidth: view.defaultCharacterWidth };
            """, from, to );
        assertTrue( Boolean.TRUE.equals( geometry.get( "oneLine" ) ),
            "the End/Home line must fit on one visual line of the editor; it wraps: " + geometry );
    }

    private static void awaitExactDocument( final String expected ) {
        EditWikiPage.awaitDocument( expected::equals, "equal exactly [" + expected + "]", WAIT );
    }

    /** Waits until the editor line drawing document offset {@code pos} shows exactly {@code expected}. */
    private static void awaitRenderedLine( final int pos, final String expected ) {
        final long deadline = System.currentTimeMillis() + WAIT.toMillis();
        String shown = renderedLineText( pos );
        while ( !expected.equals( shown ) && System.currentTimeMillis() < deadline ) {
            Selenide.sleep( 100 );
            shown = renderedLineText( pos );
        }
        assertEquals( expected, shown, "the editor line at document offset " + pos + " shows" );
    }
}
