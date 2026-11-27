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
package com.wikantik.api.livevalues;

import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveValueExpanderTest {

    private static Function< String, Resolution > resolver( final Map< String, Resolution > m ) {
        return name -> m.getOrDefault( name, new Resolution.Unknown() );
    }

    private static final Function< String, Resolution > R = resolver( Map.of(
            "articles", new Resolution.Value( 1142 ),
            "version", new Resolution.Text( "2.8.4" ),
            "tools.admin", new Resolution.Unwired() ) );

    @Test
    void roundsDownAndMarksPlus() {
        assertEquals( "1,100+", LiveValueExpander.expandText( "{{wiki:articles|round=100}}", R ) );
        assertEquals( "1,100", LiveValueExpander.expandText( "{{wiki:articles|round=100}}",
                resolver( Map.of( "articles", new Resolution.Value( 1100 ) ) ) ) );
        assertEquals( "1,142", LiveValueExpander.expandText( "{{wiki:articles}}", R ) );
        assertEquals( "over 1,100+ pages", LiveValueExpander.expandText( "over {{wiki:articles|round=100}} pages", R ) );
    }

    @Test
    void textValueVerbatim() {
        assertEquals( "2.8.4", LiveValueExpander.expandText( "{{wiki:version}}", R ) );
        assertEquals( "2.8.4", LiveValueExpander.expandText( "{{wiki:version|round=10}}", R ) );
    }

    @Test
    void unknownStaysVerbatim() {
        assertEquals( "{{wiki:nope}}", LiveValueExpander.expandText( "{{wiki:nope}}", R ) );
    }

    @Test
    void unwiredUsesElseOrEmpty() {
        assertEquals( "several", LiveValueExpander.expandText( "{{wiki:tools.admin|else=several}}", R ) );
        assertEquals( "", LiveValueExpander.expandText( "{{wiki:tools.admin}}", R ) );
    }

    @Test
    void malformedStaysVerbatim() {
        for( final String s : new String[] { "{{wiki:}}", "{{wiki:articles|round=0}}", "{{wiki:articles|round=x}}",
                "{{wiki:articles", "{{wiki:articles|bogus=1}}", "{{wiki:Articles}}", "{{wiki:articles|round=-5}}" } ) {
            assertEquals( s, LiveValueExpander.expandText( s, R ), s );
            assertEquals( s, LiveValueExpander.expandBody( s, R ), s );
        }
    }

    @Test
    void bodySkipsCode() {
        final String t = "{{wiki:articles}}";
        final String md = String.join( "\n",
                "```",
                t,
                "```",
                "~~~",
                t,
                "~~~",
                "- item",
                "  ```",
                "  " + t,
                "  ```",
                "> ```",
                "> " + t,
                "> ```",
                "",
                "    " + t,
                "",
                "`" + t + "`",
                "``x " + t + " x``",
                "",
                "para " + t );
        final String expected = md.substring( 0, md.lastIndexOf( t ) ) + "1,142";
        assertEquals( expected, LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void longerFenceNeedsLongerClose() {
        final String md = "````\n```\n{{wiki:articles}}\n```\n````\n{{wiki:articles}}";
        assertEquals( "````\n```\n{{wiki:articles}}\n```\n````\n1,142", LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void listContinuationIsNotIndentedCode() {
        final String md = "- item\n\n    {{wiki:articles}}\n";
        assertEquals( "- item\n\n    1,142\n", LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void unmatchedBacktickIsLiteral() {
        assertEquals( "a ` b 1,142", LiveValueExpander.expandBody( "a ` b {{wiki:articles}}", R ) );
    }

    @Test
    void argsAnyOrder() {
        assertEquals( LiveValueExpander.expandText( "{{wiki:articles|else=n/a|round=100}}", R ),
                LiveValueExpander.expandText( "{{wiki:articles|round=100|else=n/a}}", R ) );
    }

    @Test
    void tokenNamesFindsAll() {
        final String text = "x {{wiki:articles}} ```{{wiki:cluster.agentic-ai/rag.pages|round=10}}``` {{wiki:bad|zz=1}}";
        assertEquals( Set.of( "articles", "cluster.agentic-ai/rag.pages", "bad" ), LiveValueExpander.tokenNames( text ) );
        assertTrue( LiveValueExpander.hasToken( text ) );
        assertFalse( LiveValueExpander.hasToken( "no tokens {{wiki:}}" ) );
        assertTrue( LiveValueExpander.BUILT_IN_NAMES.contains( "tools.admin" ) );
    }

    @Test
    void aTokenAtTheVeryStartOfABodyIsExpanded() {
        assertEquals( "1,142", LiveValueExpander.expandBody( "{{wiki:articles}}", R ) );
        assertEquals( "1,142", LiveValueExpander.expandText( "{{wiki:articles}}", R ) );
        assertEquals( "1,142 pages", LiveValueExpander.expandBody( "{{wiki:articles}} pages", R ) );
    }

    @Test
    void roundAcceptsAtMostEighteenDigits() {
        assertEquals( List.of(), LiveValueExpander.malformedTokens( "{{wiki:articles|round=" + "9".repeat( 18 ) + "}}" ) );
        assertEquals( 1, LiveValueExpander.malformedTokens( "{{wiki:articles|round=" + "9".repeat( 19 ) + "}}" ).size() );
    }

    @Test
    void roundMustBeAPositiveWholeNumber() {
        for ( final String bad : new String[]{ "0", "00", "", "abc", "1.5", "-5", "+5", "1e3", "\u0661" } ) {
            assertEquals( 1, LiveValueExpander.malformedTokens( "{{wiki:articles|round=" + bad + "}}" ).size(), bad );
        }
        for ( final String good : new String[]{ "1", "10", "007", "1000000" } ) {
            assertEquals( List.of(), LiveValueExpander.malformedTokens( "{{wiki:articles|round=" + good + "}}" ), good );
        }
    }

    @Test
    void malformedTokensListsStraysAndBadArgumentsInDocumentOrder() {
        final String text = "{{wiki:articles|bogus=1}} ok {{wiki:articles}} then {{wiki:Bad}} and {{wiki:articles|round=0}}";
        assertEquals( List.of( "{{wiki:articles|bogus=1}}", "{{wiki:Bad}} and {{wiki:articles|round=0",
                "{{wiki:articles|round=0}}" ), LiveValueExpander.malformedTokens( text ) );
    }

    @Test
    void malformedTokensOfTextWithNoOpeningIsEmptyAndNullSafe() {
        assertEquals( List.of(), LiveValueExpander.malformedTokens( null ) );
        assertEquals( List.of(), LiveValueExpander.malformedTokens( "no tokens {wiki:x}" ) );
    }

    @Test
    void aTokenAtIndexZeroOfMalformedTextIsReported() {
        assertEquals( List.of( "{{wiki:articles|round=0}}" ), LiveValueExpander.malformedTokens( "{{wiki:articles|round=0}}" ) );
    }

    // ---- MarkdownCodeScanner behaviour through expandBody ----

    private static final String T = "{{wiki:articles}}";

    @Test
    void crlfFencesOpenAndCloseSoTextAfterThemIsExpanded() {
        final String md = "```\r\n" + T + "\r\n```\r\n" + T + "\r\n";
        assertEquals( "```\r\n" + T + "\r\n```\r\n1,142\r\n", LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void aBacktickFenceWhoseInfoStringHoldsABacktickIsNotAFence() {
        assertEquals( "``` a`b\n1,142", LiveValueExpander.expandBody( "``` a`b\n" + T, R ) );
    }

    @Test
    void aTildeFenceMayHoldABacktickInItsInfoString() {
        assertEquals( "~~~ a`b\n" + T + "\n~~~\n1,142", LiveValueExpander.expandBody( "~~~ a`b\n" + T + "\n~~~\n" + T, R ) );
    }

    @Test
    void aFenceClosesOnlyWithTheSameCharacterAndNoInfoText() {
        assertEquals( "```\n" + T + "\n~~~\n" + T + "\n``` x\n" + T + "\n```\n1,142",
                LiveValueExpander.expandBody( "```\n" + T + "\n~~~\n" + T + "\n``` x\n" + T + "\n```\n" + T, R ) );
    }

    @Test
    void indentedCodeNeedsAPrecedingBlankLineAndFourSpacesOrATab() {
        assertEquals( "para\n\n    " + T, LiveValueExpander.expandBody( "para\n\n    " + T, R ) );
        assertEquals( "para\n\n\t" + T, LiveValueExpander.expandBody( "para\n\n\t" + T, R ) );
        assertEquals( "para\n    1,142", LiveValueExpander.expandBody( "para\n    " + T, R ), "lazy continuation" );
        assertEquals( "para\n\n   1,142", LiveValueExpander.expandBody( "para\n\n   " + T, R ), "three spaces" );
    }

    @Test
    void indentedCodeContinuesOverConsecutiveIndentedLinesAndBlankLines() {
        assertEquals( "p\n\n    a\n    " + T + "\n\n    " + T + "\nafter 1,142",
                LiveValueExpander.expandBody( "p\n\n    a\n    " + T + "\n\n    " + T + "\nafter " + T, R ) );
    }

    @Test
    void anIndentedLineInsideAListItemIsNotCodeEvenAfterABlankLine() {
        assertEquals( "1. item\n\n    1,142", LiveValueExpander.expandBody( "1. item\n\n    " + T, R ) );
        assertEquals( "* item\n\n\t1,142", LiveValueExpander.expandBody( "* item\n\n\t" + T, R ) );
    }

    @Test
    void aTopLevelLineEndsTheListContextSoLaterIndentedCodeIsCode() {
        assertEquals( "- item\nplain\n\n    " + T, LiveValueExpander.expandBody( "- item\nplain\n\n    " + T, R ) );
    }

    @Test
    void inlineCodeIsSkippedWhateverPrecedesIt() {
        assertEquals( "see `" + T + "` then 1,142", LiveValueExpander.expandBody( "see `" + T + "` then " + T, R ) );
        assertEquals( "x ``a ` b " + T + "`` " + "1,142", LiveValueExpander.expandBody( "x ``a ` b " + T + "`` " + T, R ) );
        assertEquals( "1,142 `" + T + "` 1,142", LiveValueExpander.expandBody( T + " `" + T + "` " + T, R ) );
    }

    @Test
    void blockQuoteAndListPrefixesDoNotHideFences() {
        assertEquals( "> ```\n> " + T + "\n> ```\n1,142", LiveValueExpander.expandBody( "> ```\n> " + T + "\n> ```\n" + T, R ) );
        assertEquals( "- ```\n  " + T + "\n  ```\n1,142", LiveValueExpander.expandBody( "- ```\n  " + T + "\n  ```\n" + T, R ) );
    }
}
