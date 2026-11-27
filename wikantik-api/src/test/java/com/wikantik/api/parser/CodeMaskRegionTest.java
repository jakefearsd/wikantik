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
package com.wikantik.api.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins exactly which offsets {@link CodeMask} marks as code. Each case is the Markdown with the expected code
 * regions bracketed by the guillemets {@code << >>}, which the helper strips to build the input and the expected
 * mask; a bracketed region may span newlines.
 */
class CodeMaskRegionTest {

    private static final char OPEN = '«';
    private static final char CLOSE = '»';

    private static String render( final String text, final boolean[] mask ) {
        final StringBuilder sb = new StringBuilder();
        boolean open = false;
        for ( int i = 0; i < text.length(); i++ ) {
            if ( mask[ i ] && !open ) {
                sb.append( OPEN );
                open = true;
            } else if ( !mask[ i ] && open ) {
                sb.append( CLOSE );
                open = false;
            }
            sb.append( text.charAt( i ) );
        }
        if ( open ) {
            sb.append( CLOSE );
        }
        return sb.toString();
    }

    private static String strip( final String marked ) {
        return marked.replace( String.valueOf( OPEN ), "" ).replace( String.valueOf( CLOSE ), "" );
    }

    /** Everything CodeMask.of marks, rendered with guillemets around each masked run. */
    private static void assertMask( final String marked ) {
        final String text = strip( marked );
        assertEquals( marked, render( text, CodeMask.of( text ) ), "input: " + text.replace( "\n", "\\n" ) );
    }

    private static void assertFenced( final String marked ) {
        final String text = strip( marked );
        assertEquals( marked, render( text, CodeMask.fenced( text ) ), "input: " + text.replace( "\n", "\\n" ) );
    }

    // ---------------------------------------------------------------- inline code

    @Test
    void anInlineSpanIncludesItsBackticksAndStopsExactlyAtTheCloser() {
        assertMask( "a «`x`» b" );
        assertMask( "«`x`»y" );
        assertMask( "«``a`b``» c" );
    }

    @Test
    void anUnpairedBacktickRunMasksNothing() {
        assertMask( "a `x b" );
        assertMask( "a ``x` b" );
    }

    @Test
    void aCodeSpanMayCrossALineBreakInsideOneParagraph() {
        assertMask( "a «`b\nc`» d" );
        assertMask( "> a «`b\n> c`» d" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "a `b\n\nc` d",          // blank line ends the paragraph
            "a `b\n# c` d",          // ATX heading starts a new block
            "a `b\n- c` d",          // list item starts a new block
            "a `b\n1. c` d",
            "a `b\n| c` d",          // table row (pipe at index 0)
            "a `b\nx | c` d",        // table row (pipe later)
            "a `b\n---\nc` d",       // thematic break / setext underline closes the run
            "a `b\n===\nc` d",
            "a `b\n> c` d",          // block-quote marker appears
            "> a `b\nc` d",          // block-quote marker disappears
            "# a `b\nc` d",          // a heading line is a block by itself
            "| a `b |\nc` d",        // so is a table row
            "a `b\n***\nc` d",
    } )
    void aCodeSpanDoesNotCrossABlockBoundary( final String text ) {
        assertMask( text );
    }

    @Test
    void inlineCodeOnTheSameLineAsABlockMarkerStillMasks() {
        assertMask( "# a «`b`» c" );
        assertMask( "- a «`b`» c" );
        assertMask( "| a «`b`» |" );
    }

    // ---------------------------------------------------------------- fenced blocks

    @Test
    void aFencedBlockMasksEveryLineIncludingTheFenceLinesButNotTheNewlines() {
        assertMask( "«```»\n«code»\n«```»\nafter" );
        assertMask( "«~~~»\n«code»\n«~~~»\nafter" );
        assertFenced( "«```»\n«code»\n«```»\nafter" );
    }

    @Test
    void anUnclosedFenceRunsToTheEndOfTheBody() {
        assertMask( "«```»\n«`x`»\n«more»" );
    }

    @Test
    void aFenceClosesOnlyOnALongEnoughRunOfTheSameCharacterWithNothingAfterIt() {
        assertMask( "«````»\n«```»\n«x»\n«````»\nc" );
        assertMask( "«```»\n«~~~»\n«```»\nc" );
        assertMask( "«```»\n«``` x»\n«```»\nc" );
        assertMask( "«~~~»\n«```»\n«~~~~»\nc" );
    }

    @Test
    void aClosingFenceMayBeIndentedByUpToThreeSpaces() {
        assertMask( "«```»\n«x»\n«   ```»\nafter" );
        assertMask( "«```»\n«x»\n«    ```»\n«y»\n«```»\nafter" );
    }

    @Test
    void anOpeningFenceMayBeIndentedByUpToThreeSpaces() {
        assertMask( "«   ```»\n«x»\n«   ```»\nafter" );
        // four spaces after a paragraph line is a lazy continuation, not a fence: the lone run masks nothing
        assertMask( "p\n    ```\nx" );
    }

    @Test
    void twoBackticksAreNotAFenceAndABacktickFenceInfoStringMayNotContainABacktick() {
        assertMask( "``\nx" );
        assertMask( "~~\nx" );
        assertMask( "``` a`b\nx" );
        assertMask( "«~~~ a`b»\n«x»\n«~~~»" );
        assertMask( "«```java»\n«x»\n«```»" );
    }

    @Test
    void aFenceInterruptsAParagraphAndEndsItsOpenCodeSpan() {
        assertMask( "a `b\n«```»\n«c»\n«```»\nd` e" );
    }

    @Test
    void fencedMarksOnlyFencedBlocks() {
        assertFenced( "a `x`\n«```»\n«b»\n«```»\n\n    indented\n" );
        assertFenced( "«```»\n«x»\n«```»\n`y`" );
    }

    // ---------------------------------------------------------------- fences inside list items

    @Test
    void aFenceInAListItemEndsWithTheItem() {
        assertMask( "- item\n«  ```»\n«  code»\n«  ```»\nafter" );
        // the first non-blank line indented less than the item's content ends item and fence
        assertMask( "- item\n«  ```»\n«  code»\ncode\n`x`".replace( "`x`", "«`x`»" ) );
        assertMask( "- item\n«  ```»\n«  a»\n«»\n«  b»\nafter".replace( "«»", "" ) );
    }

    @Test
    void aFenceAtTheSameColumnAsTheListContentIsInsideTheItemEvenWhenDeeplyIndented() {
        assertMask( "1. item\n\n«      ```»\n«      x»\n«      ```»\nafter" );
    }

    // ---------------------------------------------------------------- indented code

    @Test
    void indentedCodeStartsAfterABlankLineOrAtTheStartOfTheBody() {
        assertMask( "para\n\n«    code»\n\nafter" );
        assertMask( "«    code»\nafter" );
        assertMask( "para\n\n«\tcode»\nafter" );
    }

    @Test
    void indentedCodeNeedsAFullFourColumnsAndCannotInterruptAParagraph() {
        assertMask( "para\n\n   notcode" );
        assertMask( "para\n    cont" );
    }

    @Test
    void indentedCodeContinuesAcrossBlankLines() {
        assertMask( "para\n\n«    a»\n\n«    b»\nafter" );
    }

    @Test
    void indentedCodeIsNotCodeWhenThePrecedingLineWasItselfIndentedTwoOrMore() {
        assertMask( " p\n\n«    x»" );
        assertMask( "  p\n\n    x" );
        assertMask( "   p\n\n    x" );
    }

    @Test
    void insideAListItemCodeStartsFourColumnsPastTheItemContent() {
        assertMask( "- item\n\n«      code»" );
        assertMask( "- item\n\n     five" );
        assertMask( "- item\n\n    four" );
        assertMask( "1. item\n\n       seven".replace( "       seven", "«       seven»" ) );
        assertMask( "1. item\n\n      six" );
    }

    @Test
    void aContinuationLineAtTheItemContentColumnKeepsTheListContext() {
        assertMask( "- a\n  b\n\n«      x»" );
    }

    @Test
    void aLessIndentedLineLeavesTheListContext() {
        assertMask( "- a\nb\n\nc\n\n«    x»" );
    }

    // ---------------------------------------------------------------- HTML comments

    @Test
    void codeInsideAnHtmlCommentBlockIsNotMasked() {
        assertMask( "<!-- start\n`x`\n-->\n«`y`»" );
        assertMask( "<!-- a `x` -->\n«`y`»" );
        assertMask( "<!-- start\n```\n-->\n«`y`»" );
    }

    @Test
    void anHtmlCommentOpenedAndClosedOnOneLineDoesNotSwallowTheNextLine() {
        assertMask( "<!---->\n«`x`»" );
        assertMask( "<!-- x -->\n«`x`»" );
    }

    @Test
    void theHtmlCommentOpenerAndItsCloserMustNotOverlap() {
        // "<!-->" does not contain a closer after its own opener, so the block stays open (current behaviour)
        assertMask( "<!-->\n`x`\n-->\n«`y`»" );
    }

    @Test
    void anHtmlCommentOpensAtUpToThreeSpacesOfIndent() {
        assertMask( "p\n   <!-- c\n`x`\n-->\n«`y`»" );
        assertMask( "p\n    <!-- «`x`»" );
    }

    @Test
    void anHtmlCommentEndsTheOpenParagraphAndItsCodeSpan() {
        assertMask( "text `a\n<!-- c\n-->\nb` more" );
    }

    // ---------------------------------------------------------------- degenerate input

    @Test
    void emptyAndBlankBodiesMaskNothing() {
        assertEquals( 0, CodeMask.of( "" ).length );
        assertMask( "   \n\n  " );
    }

    @Test
    void crlfLineEndingsDoNotBreakListAndFenceDetection() {
        final String text = "- item\r\n  ```\r\n  code\r\n  ```\r\nafter";
        final boolean[] m = CodeMask.of( text );
        assertEquals( true, m[ text.indexOf( "code" ) ] );
        assertEquals( false, m[ text.indexOf( "after" ) ] );
    }
}
