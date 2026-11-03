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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeMaskTest {

    @Test
    void fencedMarksOnlyFenceLines() {
        final String md = "a `x`\n```\nb\n```\n\n    indented\n";
        final boolean[] all = CodeMask.of( md );
        final boolean[] fenced = CodeMask.fenced( md );
        assertTrue( all[ md.indexOf( "x" ) ] );
        assertFalse( fenced[ md.indexOf( "x" ) ] );
        assertTrue( fenced[ md.indexOf( "b" ) ] );
        assertTrue( fenced[ md.indexOf( "```" ) ] );
        assertTrue( all[ md.indexOf( "indented" ) ] );
        assertFalse( fenced[ md.indexOf( "indented" ) ] );
        assertFalse( fenced[ 0 ] );
    }

    /**
     * Unmatched backtick runs of distinct lengths each used to rescan the rest of the line (O(n^1.5) on one long
     * line: ~100 ms per 256 KB note per call, five calls per imported note). Pairing is now precomputed per line.
     */
    @Test
    void inlineCodeScanIsLinearOnUnmatchedRunStaircase() {
        final StringBuilder sb = new StringBuilder();
        for ( int k = 1; sb.length() < 16_000_000; k++ ) {
            sb.append( "`".repeat( k ) ).append( 'a' );
        }
        final String line = sb.toString();
        final boolean[] mask = assertTimeoutPreemptively( java.time.Duration.ofSeconds( 2 ), () -> CodeMask.of( line ) );
        assertFalse( mask[ line.length() - 1 ] );
    }

    @Test
    void inlineCodePairsTheNextRunOfEqualLengthSkippingEnclosedRuns() {
        final String md = "a ``x ` y`` b `z` c ``` d `e";
        final boolean[] m = CodeMask.of( md );
        assertTrue( m[ md.indexOf( "x" ) ] && m[ md.indexOf( "y" ) ] );
        assertFalse( m[ md.indexOf( "b" ) ] );
        assertTrue( m[ md.indexOf( "z" ) ] );
        assertFalse( m[ md.indexOf( "d" ) ] );
        assertFalse( m[ md.indexOf( "e" ) ] );
    }

    @Test
    void aFenceInAListItemEndsAtTheFirstLineIndentedLessThanTheItemContent() {
        final String md = "3. step:\n\n    ```bash\n    run\n  after\n    ```\n";
        final boolean[] m = CodeMask.of( md );
        assertTrue( m[ md.indexOf( "run" ) ] );
        assertFalse( m[ md.indexOf( "after" ) ] );
    }

    @Test
    void aFenceInAListItemSurvivesBlankLinesAndContentIndentedLines() {
        final String md = "- item\n  ```\n  one\n\n  two\n  ```\nout";
        final boolean[] m = CodeMask.of( md );
        assertTrue( m[ md.indexOf( "one" ) ] && m[ md.indexOf( "two" ) ] );
        assertFalse( m[ md.indexOf( "out" ) ] );
    }

    @Test
    void aFenceInANestedListItemEndsWithTheInnerItemNotTheOuterOne() {
        final String md = "- a\n  - b\n    ```\n    code\n  outer text\n    ```\n";
        final boolean[] m = CodeMask.of( md );
        assertTrue( m[ md.indexOf( "code" ) ] );
        assertFalse( m[ md.indexOf( "outer" ) ] );
    }

    /** Prod pages are almost all CRLF (Obsidian/Windows imports): a list item ends a code-span run whatever the line ending. */
    @Test
    void crlfPagesMaskExactlyWhatTheirLfFormMasks() {
        final String[] cases = {
            "   - ``[`[link]``()]` to ``[link]`()`\n   - `# Header` to `!!! Header`\n",   // prod ContentFeedback
            "- a `x\n- b` y\n",
            "1. one `span\n2. two` text\n",
            "a `span\ncrosses` a line\n",
            "- item\n\n      indented code\n\nafter\n",
            "- a\n  - b\n    ```\n    code\n  outer text\n    ```\n",
        };
        for ( final String lf : cases ) {
            final String crlf = lf.replace( "\n", "\r\n" );
            final boolean[] want = CodeMask.of( lf );
            final boolean[] got = CodeMask.of( crlf );
            for ( int i = 0, j = 0; i < crlf.length(); i++ ) {
                if ( crlf.charAt( i ) == '\r' ) {
                    continue;
                }
                assertEquals( want[ j ], got[ i ], "offset " + i + " of " + crlf.replace( "\r\n", "⏎" ) );
                j++;
            }
        }
    }

    /** Inside a list item, indented code starts four columns past the item's content column (prod FullOAuth). */
    @Test
    void indentedCodeInAListItemIsFourColumnsPastItsContent() {
        final String code = "- item\n\n      1. \"quoted\"\n";
        assertTrue( CodeMask.of( code )[ code.indexOf( "quoted" ) ] );
        final String para = "- item\n\n    continued\n";
        assertFalse( CodeMask.of( para )[ para.indexOf( "continued" ) ] );
        final String nested = "1. item\n\n       seven\n";
        assertTrue( CodeMask.of( nested )[ nested.indexOf( "seven" ) ] );
    }

    /** An HTML comment block (CommonMark HTML block type 2) holds no code, even across lines (prod Main's header). */
    @Test
    void anHtmlCommentBlockHoldsNoCode() {
        final String md = "<!-- regenerate with\n     `java -cp x.jar \\\n          Cli --write`\n     to regenerate. -->\n\nafter `code` here\n";
        final boolean[] m = CodeMask.of( md );
        assertFalse( m[ md.indexOf( "java" ) ] );
        assertFalse( m[ md.indexOf( "Cli" ) ] );
        assertTrue( m[ md.indexOf( "code" ) ] );
        final String oneLine = "<!-- `x` -->\n`y`\n";
        assertFalse( CodeMask.of( oneLine )[ oneLine.indexOf( "x" ) ] );
        assertTrue( CodeMask.of( oneLine )[ oneLine.indexOf( "y" ) ] );
        final String interrupts = "para `a\n<!-- b` -->\n";
        final boolean[] mi = CodeMask.of( interrupts );   // the comment interrupts the paragraph: its backtick pairs with nothing
        assertFalse( mi[ interrupts.indexOf( "a" ) ] );
        assertFalse( mi[ interrupts.indexOf( "b" ) ] );
    }
}
