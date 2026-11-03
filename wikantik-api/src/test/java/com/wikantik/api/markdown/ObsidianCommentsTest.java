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
package com.wikantik.api.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ObsidianCommentsTest {

    @Test
    void removesAnInlineComment() {
        assertEquals( "a  b", ObsidianComments.strip( "a %%hidden%% b" ) );
    }

    @Test
    void aMultiLineCommentLeavesTheSameNumberOfNewlines() {
        final String out = ObsidianComments.strip( "a %%x\ny\nz%% b\nnext" );
        assertEquals( "a \n\n b\nnext", out );
        assertEquals( "a %%x\ny\nz%% b\nnext".chars().filter( c -> c == '\n' ).count(), out.chars().filter( c -> c == '\n' ).count() );
    }

    @Test
    void anUnterminatedCommentStaysLiteral() {
        assertEquals( "a %%b\nmore text", ObsidianComments.strip( "a %%b\nmore text" ) );
    }

    @Test
    void codeFencesAndMermaidCommentsAreUntouched() {
        final String mermaid = "```mermaid\ngraph TD\n%% a mermaid comment\nA-->B\n```\n";
        assertSame( mermaid, ObsidianComments.strip( mermaid ) );
        assertEquals( "`%%x%%` ", ObsidianComments.strip( "`%%x%%` %%y%%" ) );
    }

    @Test
    void mathIsUntouched() {
        assertEquals( "$a %%b%% c$ ", ObsidianComments.strip( "$a %%b%% c$ %%d%%" ) );
        final String block = "$$\nx %% tex\ny %%\n$$\n";
        assertSame( block, ObsidianComments.strip( block ) );
    }

    @Test
    void textWithoutACommentIsReturnedAsIsAndNullIsNull() {
        final String text = "plain 100% text\n";
        assertSame( text, ObsidianComments.strip( text ) );
        assertNull( ObsidianComments.strip( null ) );
    }

    /** The closer, like the opener, is never read inside code or math (the reviewer's three repros). */
    @Test
    void aMarkerInsideCodeOrMathNeverClosesAComment() {
        final String fence = "Rates rose 100%% overnight.\n\n```mermaid\ngraph TD\n%% a mermaid comment\nA-->B\n```\n\nAfter.";
        assertSame( fence, ObsidianComments.strip( fence ) );
        final String span = "Use 50%% here, then `%%` literal";
        assertSame( span, ObsidianComments.strip( span ) );
        final String math = "50%% off $a %% b$";
        assertSame( math, ObsidianComments.strip( math ) );
        assertEquals( "a  b", ObsidianComments.strip( "a %%x `%%` y%% b" ), "a masked %% is skipped, the next one closes" );
    }

    @Test
    void fencesInsideBlockquotesAndCalloutsAreCode() {
        final String quoted = "> ```mermaid\n> graph TD\n> %% c %%\n> ```\n";
        assertSame( quoted, ObsidianComments.strip( quoted ) );
        final String callout = "> [!note]\n> ```mermaid\n> %% c %%\n> %% d %%\n> ```";
        assertSame( callout, ObsidianComments.strip( callout ) );
        assertEquals( "> > ~~~\n> > %%x%%\n> > ~~~\n z", ObsidianComments.strip( "> > ~~~\n> > %%x%%\n> > ~~~\n%%y%% z" ) );
        assertEquals( "> ```\n> code\n\n x", ObsidianComments.strip( "> ```\n> code\n\n%%gone%% x" ),
                "a quoted fence ends with its quote" );
        assertEquals( "> a  b", ObsidianComments.strip( "> a %%x%% b" ) );
    }

    @Test
    void inlineDisplayMathIsMath() {
        assertEquals( "a $$x %% y %% z$$  d", ObsidianComments.strip( "a $$x %% y %% z$$ %%c%% d" ) );
        assertEquals( "$$a \n\n c$$", ObsidianComments.strip( "$$a %%x\n\nb%% c$$" ), "inline $$ never spans a blank line" );
    }

    @Test
    void rangesLocateEachCommentDelimitersIncluded() {
        final String text = "a %%x%% b %%y\nz%% c `%%k%%`";
        final java.util.List< String > found = new java.util.ArrayList<>();
        for ( final int[] r : ObsidianComments.ranges( text ) ) {
            found.add( text.substring( r[ 0 ], r[ 1 ] ) );
        }
        assertEquals( java.util.List.of( "%%x%%", "%%y\nz%%" ), found );
        assertEquals( java.util.List.of(), ObsidianComments.ranges( "a %%b" ) );
        assertEquals( java.util.List.of(), ObsidianComments.ranges( null ) );
    }

    /**
     * Each input below has 80k-ish markers, backticks, dollars, fences or quote levels: a scan that is quadratic in any of
     * them takes minutes, a linear one milliseconds.
     */
    @Test
    void adversarialInputsStayLinear() {
        final int n = 80_000;
        final String[] inputs = {
            "%%`".repeat( n ),
            "`%%".repeat( n ),
            "%%x$".repeat( n ),
            "$a ".repeat( n ) + "%%",
            "%% " + "`%%` ".repeat( n ),
            "%%x ".repeat( n ),
            "$$ ".repeat( n ) + "%%",
            "$$x %% ".repeat( n ),
            "```\n%%\n```\n".repeat( n / 4 ),
            "> ```\n> %%\n> ```\n".repeat( n / 4 ),
            "%%\n> ```\n".repeat( n / 4 ),
            ">".repeat( n ) + " ```\n%%x%%\n",
            "> ".repeat( n / 2 ) + "~~~\n" + "> %%x%%\n".repeat( n / 4 ),
        };
        for ( final String text : inputs ) {
            assertTimeoutPreemptively( java.time.Duration.ofSeconds( 3 ), () -> ObsidianComments.strip( text ),
                    () -> "quadratic on " + text.substring( 0, 12 ).replace( "\n", "\\n" ) + "…" );
        }
    }

    @Test
    void aHugeRunOfUnpairedMarkersIsLinear() {
        final String text = "%% x " + ( "a $b " ).repeat( 20_000 ) + "\n%%\n";
        assertTimeoutPreemptively( java.time.Duration.ofSeconds( 5 ), () -> ObsidianComments.strip( text ) );
    }

    @Test
    void aDollarInOneTableRowNeverPairsWithOneInTheNextRow() {
        final String text = "| a | b |\n|---|---|\n| $5 | x |\n| %%c%% | y$z |\n";
        assertEquals( "| a | b |\n|---|---|\n| $5 | x |\n|  | y$z |\n", ObsidianComments.strip( text ) );
    }

    @Test
    void mathAcrossPlainProseLinesStillMasksAMarker() {
        assertEquals( "see $a\n%%x%% b$ end", ObsidianComments.strip( "see $a\n%%x%% b$ end" ) );
    }

    @Test
    void aPipeLessTableIsATableAndAPipeLessLineAfterItIsNot() {
        final String table = "Item | Price\n--- | ---\nw | $5\n%%c%% g | a$b\n";
        assertEquals( "Item | Price\n--- | ---\nw | $5\n g | a$b\n", ObsidianComments.strip( table ) );
        final String after = "A | B\n--- | ---\nx | y\n$a\n%%c%% b$ end\n";
        assertEquals( after, ObsidianComments.strip( after ), "a pipe-less line ends the table, so math may span it" );
    }

    @Test
    void mathNeverCrossesACellBorder() {
        assertEquals( "| A | B |\n|---|---|\n| $a  | b$ |\n", ObsidianComments.strip( "| A | B |\n|---|---|\n| $a %%x%% | b$ |\n" ) );
        assertEquals( "| A |\n|---|\n| $a\\|%%x%%b$ |\n", ObsidianComments.strip( "| A |\n|---|\n| $a\\|%%x%%b$ |\n" ) );
    }
}
