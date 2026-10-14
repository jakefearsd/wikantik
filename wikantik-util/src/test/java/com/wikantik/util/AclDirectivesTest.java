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
package com.wikantik.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link AclDirectives#strip(String)} removes exactly the access-rule directives
 * {@code DefaultAclManager} would enforce (plus {@code DENY}), outside code, and nothing else.
 * The cross-check against the real {@code DefaultAclManager} lives in wikantik-main
 * ({@code AclDirectivesParityTest}), which this module cannot depend on.
 */
class AclDirectivesTest {

    private static final String LOGIN = "alice@example.test";

    // ---- degenerate input ----

    @Test
    void nullIsReturnedAsNull() {
        assertNull( AclDirectives.strip( null ) );
        assertNull( AclDirectives.strip( null, " " ) );
    }

    @Test
    void emptyIsReturnedAsEmpty() {
        assertEquals( "", AclDirectives.strip( "" ) );
    }

    @Test
    void textWithoutDirectivesIsReturnedUnchangedAndSameInstance() {
        final String text = "# Title\n\nJust prose with a [link](x), `code` and [{TableOfContents}].\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void nullReplacementIsRejected() {
        assertThrows( NullPointerException.class, () -> AclDirectives.strip( "x", null ) );
    }

    // ---- the forms that count as a directive ----

    @Test
    void allowDirectiveIsRemoved() {
        assertEquals( "\nBody.", AclDirectives.strip( "[{ALLOW edit " + LOGIN + "}]\nBody." ) );
    }

    @Test
    void denyDirectiveIsRemoved() {
        assertEquals( "Body.", AclDirectives.strip( "[{DENY view " + LOGIN + "}]Body." ) );
    }

    @Test
    void normalisedFormWithEmptyParenthesesIsRemovedWithItsParentheses() {
        assertEquals( "\nBody.", AclDirectives.strip( "[{ALLOW view All}]()\nBody." ) );
    }

    @Test
    void glueTogetherNormalisedDirectivesAreAllRemoved() {
        // Shape found in docs/wikantik-pages/OLD/CSSThemeDark/1.txt
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit Admin}]()[{ALLOW view All}]()" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "comment", "delete", "edit", "modify", "rename", "upload", "view" } )
    void everyPagePermissionIsRecognised( final String permission ) {
        assertEquals( "x", AclDirectives.strip( "x[{ALLOW " + permission + " " + LOGIN + "}]" ) );
        assertEquals( "x", AclDirectives.strip( "x[{DENY " + permission + " " + LOGIN + "}]" ) );
    }

    @Test
    void multiplePrincipalsAreRemovedAsOneDirective() {
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit Admin," + LOGIN + ",Editors}]" ) );
    }

    @Test
    void whitespaceVariantsAreRemoved() {
        assertEquals( "x  y", AclDirectives.strip( "x [{  ALLOW   edit   " + LOGIN + "  }] y" ) );
    }

    @Test
    void aDirectiveMayBreakAcrossLinesAfterThePermissionJustLikeTheAclManagerReadsIt() {
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit\n" + LOGIN + "}]" ) );
    }

    @Test
    void aClosingBraceInsidePrincipalsDoesNotEndTheDirectiveEarly() {
        // DefaultAclManager's lazy (.*?) runs to the first "}]", so the principal is "a}b".
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit a}b}]" ) );
    }

    // ---- position and multiplicity ----

    @Test
    void severalDirectivesOnOneLineAreAllRemoved() {
        assertEquals( "a  b  c",
                AclDirectives.strip( "a [{ALLOW view All}] b [{ALLOW edit Admin," + LOGIN + "}] c" ) );
    }

    @Test
    void directiveAtTheStartAndAtTheEndIsRemoved() {
        assertEquals( "middle", AclDirectives.strip( "[{ALLOW view All}]middle[{ALLOW edit " + LOGIN + "}]" ) );
    }

    @Test
    void aDirectiveOnItsOwnLineLeavesAnEmptyLineAndNothingElseChanges() {
        assertEquals( "---\ntitle: T\n---\n\n[{TableOfContents}]\n\n# H\r\n",
                AclDirectives.strip( "---\ntitle: T\n---\n[{ALLOW edit " + LOGIN + "}]\n[{TableOfContents}]\n\n# H\r\n" ) );
    }

    @Test
    void windowsLineEndingsAroundADirectiveSurvive() {
        assertEquals( "\r\n# Title\r\n", AclDirectives.strip( "[{ALLOW view All}]\r\n# Title\r\n" ) );
    }

    @Test
    void otherPluginSpansAreLeftByteIdentical() {
        final String text = "[{TableOfContents}]\n[{SET foo='bar'}]\n[{Image src='x.png' caption='a'}]\n"
                + "[{INSERT Foo}]\n[{$pagename}]\n";
        assertSame( text, AclDirectives.strip( text ) );
        assertEquals( "[{TableOfContents}]\n\n[{Image src='x.png'}]",
                AclDirectives.strip( "[{TableOfContents}]\n[{ALLOW edit " + LOGIN + "}]\n[{Image src='x.png'}]" ) );
    }

    @Test
    void strippingIsIdempotent() {
        final String once = AclDirectives.strip( "[{ALLOW view All}]() x [{DENY edit " + LOGIN + "}] `[{ALLOW edit a}]`" );
        assertEquals( once, AclDirectives.strip( once ) );
    }

    @Test
    void removalCannotSpliceTheSurroundingTextIntoAFreshDirective() {
        // Removing the inner directive joins "[{ALLOW vi" and "ew a}]" into another one.
        assertEquals( "", AclDirectives.strip( "[{ALLOW vi[{ALLOW view a}]ew a}]" ) );
    }

    // ---- text that is not a directive stays ----

    @ParameterizedTest
    @ValueSource( strings = {
            "[{ALLOW edit alice@example.test",       // never closed
            "[{ALLOW edit alice@example.test}",      // single closing brace
            "[ALLOW edit alice@example.test]",       // not a plugin span
            "{ALLOW edit alice@example.test}",       // no square brackets
            "[{ALLOW}]",                              // no permission
            "[{ALLOW fly alice@example.test}]",      // not a page permission
            "[{allow edit alice@example.test}]",     // directives are case-sensitive
            "[{ ALLOWED edit alice@example.test}]",  // a different word
    } )
    void malformedOrForeignBracketsAreKept( final String text ) {
        assertSame( text, AclDirectives.strip( text ) );
    }

    // ---- fenced code blocks ----

    @Test
    void directiveInsideABacktickFenceIsKept() {
        final String text = "Example:\n\n```\n[{ALLOW edit " + LOGIN + "}]\n```\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void directiveInsideATildeFenceIsKept() {
        final String text = "~~~\n[{ALLOW edit " + LOGIN + "}]\n~~~\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void fenceWithAnInfoStringAndLongerClosingRunIsHonoured() {
        final String text = "````markdown\n```\n[{ALLOW edit " + LOGIN + "}]\n```\n````\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void aShorterRunDoesNotCloseAFenceSoTheFenceIsNeverClosedAndIsNotTrusted() {
        assertEquals( "````\n```\n\n",
                AclDirectives.strip( "````\n```\n[{ALLOW edit " + LOGIN + "}]\n" ) );
    }

    @Test
    void anUnclosedFenceIsNotTrustedAsCode() {
        // The renderer would treat the rest of the page as code; we hide the directive regardless (fail closed).
        assertEquals( "```\ncode\n\nmore", AclDirectives.strip( "```\ncode\n[{ALLOW edit " + LOGIN + "}]\nmore" ) );
    }

    @Test
    void fenceIndentedByThreeSpacesIsAFence() {
        final String text = "   ```\n[{ALLOW edit " + LOGIN + "}]\n   ```\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void fenceIndentedByFourSpacesIsNotAFence() {
        assertEquals( "    ```\n\n    ```\n",
                AclDirectives.strip( "    ```\n[{ALLOW edit " + LOGIN + "}]\n    ```\n" ) );
    }

    @Test
    void directiveAfterAClosedFenceIsRemovedAndTheFenceIsUntouched() {
        assertEquals( "```\n[{ALLOW edit a}]\n```\n\nbody",
                AclDirectives.strip( "```\n[{ALLOW edit a}]\n```\n[{ALLOW edit " + LOGIN + "}]\nbody" ) );
    }

    @Test
    void directiveBeforeAFenceIsRemoved() {
        assertEquals( "\n```\ncode\n```\n",
                AclDirectives.strip( "[{ALLOW edit " + LOGIN + "}]\n```\ncode\n```\n" ) );
    }

    // ---- inline code spans ----

    @Test
    void directiveInsideAnInlineCodeSpanIsKept() {
        final String text = "Restrict editing with `[{ALLOW edit " + LOGIN + "}]`.";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void directiveInsideADoubleBacktickSpanIsKept() {
        final String text = "Code: ``[{ALLOW view Admin}]`` end.";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void aRealDirectiveBesideACodeExampleIsRemovedAndTheExampleKept() {
        assertEquals( " syntax is `[{ALLOW edit Bob}]`.",
                AclDirectives.strip( "[{ALLOW view Admin}] syntax is `[{ALLOW edit Bob}]`." ) );
    }

    @Test
    void directiveBetweenTwoCodeSpansIsRemoved() {
        assertEquals( "`a`  `c`", AclDirectives.strip( "`a` [{ALLOW edit Bob}] `c`" ) );
    }

    @Test
    void directiveJustAfterACodeSpanIsRemoved() {
        assertEquals( "The `pattern` then .", AclDirectives.strip( "The `pattern` then [{ALLOW edit Admin}]." ) );
    }

    @Test
    void aBacktickSpanThatWrapsOntoAnotherLineIsNotTrustedAsCode() {
        assertEquals( "see `first\n` end", AclDirectives.strip( "see `first\n[{ALLOW edit " + LOGIN + "}]` end" ) );
    }

    @Test
    void backticksInAnIndentedCodeLineCannotPairWithALaterLineAndHideADirective() {
        assertEquals( "    echo `date\n and `x`",
                AclDirectives.strip( "    echo `date\n[{ALLOW edit " + LOGIN + "}] and `x`" ) );
    }

    @Test
    void aBacktickSpanDoesNotCrossABlankLine() {
        // The lone backticks are literal text; the directive between them is a real one.
        assertEquals( "a ` b\n\n\n\nc ` d",
                AclDirectives.strip( "a ` b\n\n[{ALLOW edit " + LOGIN + "}]\n\nc ` d" ) );
    }

    @Test
    void anUnmatchedBacktickRunHidesNothing() {
        assertEquals( "a ` b  c", AclDirectives.strip( "a ` b [{ALLOW edit " + LOGIN + "}] c" ) );
    }

    @Test
    void runsOfDifferentLengthDoNotPairUp() {
        // "``" opens, the single "`" does not close it, so the directive is outside code.
        assertEquals( "`` ` ", AclDirectives.strip( "`` ` [{ALLOW edit " + LOGIN + "}]" ) );
    }

    @Test
    void escapedBackticksAreLiteralAndDoNotFormASpan() {
        assertEquals( "\\`\\`", AclDirectives.strip( "\\`[{ALLOW edit " + LOGIN + "}]\\`" ) );
    }

    // ---- a candidate that starts in code must not hide a real directive that begins inside it ----

    @Test
    void aDenyCandidateStartingInsideACodeSpanDoesNotSwallowARealAllowAfterIt() {
        // The lazy run from "[{DENY" reaches the closer of the ALLOW directive; only the DENY head lies in code.
        assertEquals( "`[{DENY view x` ",
                AclDirectives.strip( "`[{DENY view x` [{ALLOW edit " + LOGIN + "}]" ) );
    }

    @Test
    void aDirectiveBeginningInsideACodeCandidateStillGoesWhenTheCandidateEndsAfterIt() {
        assertEquals( "`[{ALLOW view x`  end", AclDirectives.strip( "`[{ALLOW view x` [{ALLOW edit " + LOGIN + "}] end" ) );
    }

    // ---- the multi-line shape: the renderer reads a link label that spans lines as readily as a one-line one ----

    @Test
    void aDirectiveWrappedOverSeveralLinesIsRemoved() {
        assertEquals( "\nBody.", AclDirectives.strip( "[{ALLOW edit alice@example.test,\n bob@example.test}]\nBody." ) );
        assertEquals( "x", AclDirectives.strip( "x[{DENY view alice@example.test,\r\n  bob@example.test\r\n}]" ) );
    }

    @Test
    void aWrappedDirectiveGoesWithItsEmptyParenthesesAndTheRestOfTheTextIsKept() {
        assertEquals( "a  b", AclDirectives.strip( "a [{ALLOW view All,\nAdmin}]() b" ) );
    }

    @Test
    void aWrappedDirectiveInAClosedFenceIsDocumentationAndStays() {
        final String fenced = "```\n[{ALLOW edit a,\n b}]\n```\n";
        assertSame( fenced, AclDirectives.strip( fenced ) );
    }

    @Test
    void aWrappedDirectiveInAMultiLineBacktickSpanIsNotTrustedAsCode() {
        // The scanner does not trust a span that wraps onto a second line (a real parser might pair it differently).
        assertEquals( "Write it as `` if you must.", AclDirectives.strip( "Write it as `[{ALLOW edit a,\n b}]` if you must." ) );
    }

    @Test
    void anOpenerWithAPermissionButNoCloserAtAllIsKept() {
        final String text = "[{ALLOW edit alice@example.test,\n bob@example.test";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void aLineWrappedDirectiveRunsToTheFirstCloserSoOnlyThatSpanGoes() {
        assertEquals( "head  tail [{TableOfContents}]",
                AclDirectives.strip( "head [{ALLOW edit a,\nb}] tail [{TableOfContents}]" ) );
    }

    // ---- running time on a page with very many code spans and very many candidates ----

    @Test
    void manyCodeSpansAndManyCandidatesAreCheckedInLinearithmicTime() {
        // Checking each candidate against every code range is quadratic: ~150k x 150k on this 3 MB text.
        final String unit = "`x` [{ALLOW view a}] ";
        final String text = unit.repeat( 150_000 );
        final String expected = "`x`  ".repeat( 150_000 );
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> assertEquals( expected, AclDirectives.strip( text ) ) );
    }

    // ---- documented limitation: indented code blocks fail closed ----

    @Test
    void directiveInAnIndentedCodeBlockIsRemovedBecauseTheScannerErrsOnTheSideOfHiding() {
        assertEquals( "Before.\n\n    \n\nAfter.\n",
                AclDirectives.strip( "Before.\n\n    [{ALLOW view Admin}]\n\nAfter.\n" ) );
    }

    // ---- replacement overload ----

    @Test
    void replacementTakesTheDirectivesPlaceAndKeepsNeighbouringWordsApart() {
        assertEquals( "a b", AclDirectives.strip( "a[{ALLOW view All}]b", " " ) );
        assertEquals( "a b", AclDirectives.strip( "a[{ALLOW view All}]()b", " " ) );
    }

    @Test
    void replacementLeavesCodeAlone() {
        final String text = "`[{ALLOW view All}]`";
        assertSame( text, AclDirectives.strip( text, " " ) );
    }
}
