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
package com.wikantik.parser.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #129: flexmark's heading factory rebuilds the open paragraph's text on every line that reaches block-start
 * matching, before it tests the line, so a long paragraph of lines that start with a non-letter costs quadratic time.
 * {@link FlexmarkParsers} builds parsers that only ask flexmark's heading factory about lines that could start a heading,
 * and must parse every input exactly as flexmark's own parser does.
 */
class FlexmarkParsersTest {

    /** Lines that flexmark's own heading factory may turn into a heading, or must refuse, around paragraphs and containers. */
    private static final List< String > HEADING_CASES = List.of(
            "# a", "#a", "## a ##", "###### a", "####### a", "   # a", "    # a", "#\ta", "#", "# ", "#\n", "# a #", "# a ##   ",
            "# a#", "\\# a", "a\n# b", "a\n#b", "a\n===", "a\n---", "a\n=", "a\n-", "a\nb\n===", "a\n   ===  ", "a\n    ===",
            "a\n= =", "a\n-- -", "a\n- - -", "* * *", "- - -", "a  \n---", "===", "---", "= a", "- a\n---", "- a\n  ---",
            "- a\n# b", "- a\n  # b", "- a\n  b\n  ===", "1. a\n=", "1. a\n   ===", "> a\n> ---", "> a\n---", "> a\n> # b",
            "> a\n> ===", ">> a\n>> ===", "> - a\n>   ===", "```\n# a\n```", "```\na\n===\n```", "~~~\n# a\n~~~",
            "a\n# b\nc\n===", "<div>\n# a\n</div>", "| a |\n|---|\n# b", "| a |\n| --- |\n| b |\n===", "a\n\n===",
            "# a\n===", "a\n===\n---", "[x]: /u\n===", "[x]: /u\n# b", "a\n%x\n===", "> %x\n> %y\n> ===", "> %x\n> # y",
            "- a\n\n  b\n  ---", "a\n<!-- c -->\n===", "    a\n===", "a\n\t===", "a\r\n===\r\n", "# a\r\nb\r\n---\r\n",
            "> a\r\n> ---\r\n", "$$\nx\n$$\n===", "[[A]]\n===", "#tag and text\n# heading", "#t\n> #x\n> #y\n> ---",
            "a\n##", "a\n#######x", "a\n## ", "a\n#\t", "a\n#x#", " # a", "  ## a", "a\n--", "a\n==", "- a\n  b\n  # c" );

    private static final String[] LEADS = { "%x", "1x", "!x", ".x", "#x", "-x", "=x", "(x", "|x", "*x*", "`x`", "\\x" };

    @Test
    void headingsParseAsFlexmarksOwnParserReadsThem() {
        for ( final DataHolder options : List.< DataHolder >of( new MutableDataSet(), MarkdownDocument.structuralOptions(),
                new MutableDataSet().set( Parser.HEADING_NO_ATX_SPACE, true ),
                new MutableDataSet().set( Parser.HEADING_NO_EMPTY_HEADING_WITHOUT_SPACE, true ),
                new MutableDataSet().set( Parser.HEADING_SETEXT_MARKER_LENGTH, 3 ),
                new MutableDataSet().set( Parser.HEADING_NO_LEAD_SPACE, true ),
                new MutableDataSet().set( Parser.HEADING_CAN_INTERRUPT_ITEM_PARAGRAPH, false ),
                new MutableDataSet().set( Parser.HEADING_PARSER, false ) ) ) {
            final HtmlRenderer renderer = HtmlRenderer.builder( options ).build();
            final Parser flexmark = Parser.builder( options ).build();
            final Parser ours = FlexmarkParsers.builder( options ).build();
            for ( final String text : HEADING_CASES ) {
                assertEquals( renderer.render( flexmark.parse( text ) ), renderer.render( ours.parse( text ) ),
                        () -> "differs from flexmark on " + text.replace( "\n", "\\n" ) );
            }
        }
    }

    /** Under the option that refuses {@code #} alone, a paragraph of {@code #} lines must not reach flexmark's factory. */
    @Test
    void aParagraphOfBareHashLinesParsesQuicklyUnderTheNoEmptyHeadingOption() {
        final Parser parser = FlexmarkParsers.builder( new MutableDataSet().set( Parser.HEADING_NO_EMPTY_HEADING_WITHOUT_SPACE, true ) ).build();
        final String text = "> a\n" + "> #\n".repeat( LINES );
        assertTimeoutPreemptively( Duration.ofSeconds( 120 ), () -> {
            final double ratio = costRatio( parser, text, "> a\n" + "> a#\n".repeat( LINES ) );
            assertTrue( ratio < BOUND, () -> "'> #' lines cost " + ratio + "x what '> a#' lines cost" );
        } );
    }

    @Test
    void theDefaultBuilderParsesAsFlexmarksDefaultParser() {
        final HtmlRenderer renderer = HtmlRenderer.builder().build();
        final Parser flexmark = Parser.builder().build();
        final Parser ours = FlexmarkParsers.builder().build();
        for ( final String text : HEADING_CASES ) {
            assertEquals( renderer.render( flexmark.parse( text ) ), renderer.render( ours.parse( text ) ),
                    () -> "differs from flexmark on " + text.replace( "\n", "\\n" ) );
        }
    }

    /**
     * The fix's claim, measured as a ratio so a loaded machine cannot fail it: a paragraph of {@code n} lines that start
     * with a non-letter parses in about the time the same lines led by a letter take (a letter-led line never reaches
     * block-start matching; flexmark itself took ~500x). That baseline rather than a linear bound, because flexmark's one-time build of a block quote
     * paragraph's text is itself mildly superlinear (about 1 s at the 256 KB page cap), which is not this defect.
     */
    @ParameterizedTest
    @ValueSource( strings = { "> ", "> > ", "", "- a\n  " } )
    void aParagraphOfNonLetterLinesParsesAsFastAsOneOfLetterLines( final String container ) {
        final Parser parser = FlexmarkParsers.builder( MarkdownDocument.structuralOptions() ).build();
        final String head = container.startsWith( "- a\n" ) ? "- a\n" : "";
        final String prefix = container.substring( head.length() );
        assertTimeoutPreemptively( Duration.ofSeconds( 120 ), () -> {
            for ( final String lead : LEADS ) {
                final String text = head + ( prefix + lead + "\n" ).repeat( LINES );
                final String letters = head + ( prefix + "a" + lead + "\n" ).repeat( LINES );
                final double ratio = costRatio( parser, text, letters );
                assertTrue( ratio < BOUND, () -> "'" + container.replace( "\n", "\\n" ) + lead + "' lines cost " + ratio
                                               + "x what the same lines led by a letter cost" );
            }
        } );
    }

    private static final int LINES = 4_000;
    /** Measured about 1-1.6 once warm; flexmark's own heading factory gave about 500. */
    private static final double BOUND = 10.0;

    /** @return the best-of-seven cost of {@code text} over that of {@code baseline}, warmed up and timed alternately */
    private static double costRatio( final Parser parser, final String text, final String baseline ) {
        for ( int i = 0; i < 2; i++ ) {
            parser.parse( text );
            parser.parse( baseline );
        }
        double bestText = Double.MAX_VALUE;
        double bestBaseline = Double.MAX_VALUE;
        for ( int i = 0; i < 7; i++ ) {
            bestText = Math.min( bestText, millis( parser, text ) );
            bestBaseline = Math.min( bestBaseline, millis( parser, baseline ) );
        }
        return bestText / Math.max( bestBaseline, 0.5 );
    }

    private static double millis( final Parser parser, final String text ) {
        final long start = System.nanoTime();
        parser.parse( text );
        return ( System.nanoTime() - start ) / 1e6;
    }
}
