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
package com.wikantik.auth.acl;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import com.wikantik.util.AclDirectiveScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link AclDirectiveText#strip} is the guarantee: whatever {@link DefaultAclManager} enforces in a text is gone from
 * the result, by construction (the facade removes exactly what the ACL manager would enforce, with its parser, and
 * only then runs the conservative util pass). The cases that broke the util pass on its own — found by review — are
 * fixtures here, each checked for the invariant against the real {@code DefaultAclManager.aclDirectives}, and a
 * seeded differential run repeats the check on tens of thousands of token soups.
 */
class AclDirectiveTextTest {

    private static final String LOGIN = "alice@example.test";
    private static final String D = "[{ALLOW edit " + LOGIN + "}]";

    // ---- degenerate input

    @Test
    void nullComesBackAsNullAndATextWithoutAnOpenerAsTheSameInstance() {
        assertNull( AclDirectiveText.strip( null ) );
        assertNull( AclDirectiveText.strip( null, " " ) );
        final String plain = "# Title\n\nJust prose with a [link](x) and `code`.\n";
        assertSame( plain, AclDirectiveText.strip( plain ) );
    }

    @Test
    void aNullReplacementIsRejected() {
        assertThrows( NullPointerException.class, () -> AclDirectiveText.strip( "x", null ) );
    }

    @Test
    void pluginsAndDocumentationExamplesAreKeptByteForByteAndTheSameInstance() {
        final String text = "[{TableOfContents}]\n[{SET foo='bar'}]\nUse `" + D + "` like so:\n\n```\n" + D + "\n```\n";
        assertEquals( List.of(), DefaultAclManager.aclDirectives( text ), "fixture: nothing here is enforced" );
        assertSame( text, AclDirectiveText.strip( text ) );
    }

    @Test
    void anEnforcedDirectiveGoesWithItsEmptyParenthesesAndNothingElseMoves() {
        // Alone on its line, the rule goes with the line's terminator: no blank line is left behind.
        assertEquals( "# T\r\n", AclDirectiveText.strip( "[{ALLOW view All}]()\n# T\r\n" ) );
        assertEquals( "a b", AclDirectiveText.strip( "a[{ALLOW view All}]()b", " " ) );
    }

    @Test
    void removalCannotSpliceTheSurroundingTextIntoAFreshDirective() {
        final String out = AclDirectiveText.strip( "[{ALLOW vi[{ALLOW view " + LOGIN + "}]ew bob@example.test}]" );
        assertEquals( "", out );
    }

    // ---- the cases that defeated the util pass on its own

    private static Stream< Arguments > reviewerRepros() {
        return Stream.of(
                Arguments.of( "an inline span that pairs across lines in CommonMark", "a `b\nc` " + D + " `d" ),
                Arguments.of( "backticks inside inline HTML", "<a title=\"`\">" + D + "<a title=\"`\">" ),
                Arguments.of( "backticks inside an autolink", "<http://x/`>  " + D + " <http://y/`>" ),
                Arguments.of( "a fence inside a <details> HTML block", "<details>\n```\n" + D + "\n```\n</details>\n" ),
                Arguments.of( "a fence inside a <div> HTML block", "<div>\n```\n" + D + "\n```\n</div>\n" ),
                Arguments.of( "an unclosed fence in a list item, then a later fence",
                        "- item\n  ```\n  code\n\n" + D + "\n\n- other\n  ```\n" ),
                Arguments.of( "a tilde fence whose opener holds a second tilde", "~~~x~\n" + D + "\n~~~\n" ),
                Arguments.of( "a DENY candidate starting in code reaches a real ALLOW", "`[{DENY view x` " + D ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "reviewerRepros" )
    void everyReviewerReproLosesItsEnforcedDirective( final String label, final String body ) {
        final List< String > enforced = DefaultAclManager.aclDirectives( body );
        assertTrue( enforced.contains( D ), "fixture is vacuous: the ACL manager does not enforce the directive in: " + body );

        final String out = AclDirectiveText.strip( body );

        assertInvariant( body, out );
    }

    /** The invariant, against the real {@code DefaultAclManager}: nothing it enforced in {@code body} survives in {@code out}. */
    private static void assertInvariant( final String body, final String out ) {
        for ( final String directive : DefaultAclManager.aclDirectives( body ) ) {
            assertFalse( out.contains( directive ), "enforced directive survived: " + directive + "\nin: " + out );
        }
        assertEquals( List.of(), DefaultAclManager.aclDirectives( out ),
                "the ACL manager still finds a directive in the output: " + out );
    }

    // ---- frontmatter changes which body regions are code: strip the FULL text, never a body alone

    private static Stream< Arguments > frontmatterThatChangesWhatIsCode() {
        return Stream.of(
                // a YAML block-scalar line opens a fence that runs past the closing --- in the full-text parse
                Arguments.of( "an opening fence inside a YAML block scalar",
                        "---\ntitle: X\nnotes: |\n  ```\n---\nIntro paragraph.\n\n```\n" + D + "\n```\n\nBody prose.\n" ),
                // the same with an HTML comment that is still open past the closing ---
                Arguments.of( "an HTML comment opened inside a YAML block scalar",
                        "---\ntitle: X\nnotes: |\n  <!--\n---\nIntro paragraph.\n\n```\n" + D + "\n```\n\nBody prose.\n" ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "frontmatterThatChangesWhatIsCode" )
    void theBodyOfTheStrippedFullTextLosesWhatTheFullTextEnforcesEvenThoughTheBodyAloneLooksLikeDocumentation(
            final String label, final String full ) {
        final String bodyAlone = FrontmatterParser.parse( full ).body();
        assertTrue( DefaultAclManager.aclDirectives( full ).contains( D ), "premise: the full text enforces the rule" );
        assertEquals( List.of(), DefaultAclManager.aclDirectives( bodyAlone ),
                "premise: judged on its own, the body shows the rule inside a code fence" );
        assertTrue( AclDirectiveText.strip( bodyAlone ).contains( LOGIN ),
                "premise: stripping the body alone therefore keeps the login" );

        final ParsedPage page = AclDirectiveText.parseStripped( full );

        assertFalse( page.body().contains( LOGIN ), page.body() );
        assertTrue( page.body().contains( "Body prose." ), page.body() );
        assertEquals( "X", page.metadata().get( "title" ), "the frontmatter is parsed as before" );
    }

    @Test
    void parseStrippedReadsNullAsAnEmptyPage() {
        final ParsedPage page = AclDirectiveText.parseStripped( null );
        assertEquals( "", page.body() );
        assertTrue( page.metadata().isEmpty() );
    }

    // ---- the renderer reads a directive wrapped over several lines; the enforcement scanner does not

    @Test
    void aMultiLineDirectiveIsRemovedAlthoughTheAclManagerDoesNotEnforceItInText() {
        final String text = "[{ALLOW view All}]\n[{ALLOW edit Admin,alice@example.test,\n bob@example.test}]\nBody.";
        assertEquals( List.of( "[{ALLOW view All}]" ), DefaultAclManager.aclDirectives( text ),
                "premise: only the one-line rule is enforced from the text" );

        final String out = AclDirectiveText.strip( text );

        assertEquals( "Body.", out );
    }

    @Test
    void aMultiLineDirectiveInDocumentationCodeIsKept() {
        final String text = "Example:\n\n```\n[{ALLOW edit alice@example.test,\n bob@example.test}]\n```\n";
        assertSame( text, AclDirectiveText.strip( text ) );
    }

    // ---- running time on a page with very many code spans and candidates

    @Test
    void aPageWithManyCodeSpansAndManyCandidatesIsStrippedInReasonableTime() {
        final String text = "`x` [{ALLOW view a}] ".repeat( 150_000 );
        assertTimeoutPreemptively( Duration.ofSeconds( 15 ), () -> assertEquals( "`x`  ".repeat( 150_000 ), AclDirectiveText.strip( text ) ) );
    }

    // ---- seeded differential: token soup against the real ACL manager

    private static final long SEED = 0xAC1D1EC7L;
    private static final int SOUPS = 25_000;
    private static final Pattern SOUP_LOGIN = Pattern.compile( "u\\d+r\\d+@example\\.test" );

    private static final String[] TOKENS = {
            "`", "`", "``", "```", "````", "~~~", "~~~~", "~~~x~", "```js", "   ```", "    ```", "  ~~~",
            "\n", "\n", "\n", "\n\n", "\r\n", " ", "  ", "    ", "\t", "- ", "1. ", "> ", "  - ", "* ",
            "<details>", "</details>", "<div>", "</div>", "<!--", "-->", "<a title=\"", "\">", "<http://x/", ">", "<",
            "text ", "word", "[{TableOfContents}]", "[{SET a='b'}]", "[x](y)", "()", "\\", "`code`", "\\`" };

    @Test
    void nothingTheAclManagerEnforcesSurvivesOnSeededTokenSoup() {
        final Random random = new Random( SEED );
        int soupsWithEnforcedDirectives = 0;
        for ( int i = 0; i < SOUPS; i++ ) {
            final String body = soup( random );
            final List< String > enforced = DefaultAclManager.aclDirectives( body );
            if ( !enforced.isEmpty() ) {
                soupsWithEnforcedDirectives++;
            }
            final String out;
            try {
                out = AclDirectiveText.strip( body );
            } catch ( final RuntimeException e ) {
                throw new AssertionError( "strip threw for soup #" + i + " (seed " + SEED + "): " + printable( body ), e );
            }
            try {
                assertInvariant( body, out );
                for ( final String directive : enforced ) {
                    final Matcher login = SOUP_LOGIN.matcher( directive );
                    while ( login.find() ) {
                        assertFalse( out.contains( login.group() ), "login survived: " + login.group() );
                    }
                }
            } catch ( final AssertionError e ) {
                fail( "soup #" + i + " (seed " + SEED + "): " + printable( body ) + "\n" + e.getMessage() );
            }
        }
        assertTrue( soupsWithEnforcedDirectives > SOUPS / 4,
                "the soups should often hold an enforced directive, got " + soupsWithEnforcedDirectives );
    }

    /** Token soup with uniquely numbered logins (so a surviving login names its directive). */
    private static String soup( final Random random ) {
        final int parts = 3 + random.nextInt( 22 );
        final StringBuilder sb = new StringBuilder();
        for ( int p = 0; p < parts; p++ ) {
            final int pick = random.nextInt( TOKENS.length + 6 );
            if ( pick < TOKENS.length ) {
                sb.append( TOKENS[ pick ] );
            } else {
                sb.append( directive( random, p ) );
            }
        }
        return sb.toString();
    }

    private static String directive( final Random random, final int n ) {
        final String head = random.nextInt( 4 ) == 0 ? "DENY" : "ALLOW";
        final String permission = random.nextBoolean() ? "edit" : "view";
        final String principals = "u" + n + "r" + random.nextInt( 1000 ) + "@example.test";
        return "[{" + head + " " + permission + " " + principals + "}]";
    }

    private static String printable( final String text ) {
        return text.replace( "\n", "\\n" ).replace( "\r", "\\r" ).replace( "\t", "\\t" );
    }

    // ---- the bound is fail-closed

    private static String nested( final int depth ) {
        String s = "[{ALLOW view u0@example.test}]";
        for ( int i = 1; i <= depth; i++ ) {
            s = "[{ALLOW vi" + s + "ew u" + i + "@example.test}]";
        }
        return s;
    }

    @Test
    void textThatKeepsReformingIntoDirectivesDoesNotComeBackWithOneInIt() {
        for ( final int depth : new int[]{ 3, 12, 40, 400 } ) {
            final String body = "keep " + nested( depth ) + " this";
            final String out = AclDirectiveText.strip( body );

            assertEquals( List.of(), DefaultAclManager.aclDirectives( out ), "depth " + depth + ": " + out );
            assertTrue( AclDirectiveScanner.findAll( out, true ).isEmpty(), "depth " + depth + ": " + out );
            assertFalse( out.contains( "example.test" ), "depth " + depth + ": " + out );
        }
    }

    // ---- running time

    @Test
    void shapesThatBrokeTheOriginalExpressionCostNothing() {
        final String spaces = "[{ALLOW edit" + " ".repeat( 200_000 ) + "x";
        final String openers = "[{ALLOW edit alice ".repeat( 40_000 ) + "\nx}]";
        assertTimeoutPreemptively( Duration.ofSeconds( 10 ), () -> {
            assertSame( spaces, AclDirectiveText.strip( spaces ) );
            // The renderer reads the innermost opener as the rule (the closer's "]" closes the nearest "["): that one goes;
            // what is left has no closer, so it is no directive in any reading.
            final String out = AclDirectiveText.strip( openers );
            assertEquals( "[{ALLOW edit alice ".repeat( 39_999 ), out );
            assertEquals( List.of(), DefaultAclManager.aclDirectives( out ) );
        } );
    }

    @Test
    void aLongAccessListIsStillEnforcedAndStillStripped() {
        // No length bound: a bound would silently stop enforcing (and so stop hiding) a long list.
        final String principals = ( "someone.with.a.long.name@example.test," ).repeat( 20_000 ) + "last@example.test";
        final String body = "[{ALLOW view " + principals + "}]\nBody.";
        assertEquals( 1, DefaultAclManager.aclDirectives( body ).size() );
        assertEquals( "Body.", AclDirectiveText.strip( body ) );
    }

    // ---- every shape the ACL manager enforces (the render path's included) goes, and the frontmatter survives

    static Stream< AclDirectiveFixtures.Fixture > enforcementFixtures() {
        return AclDirectiveFixtures.ALL.stream();
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "enforcementFixtures" )
    void nothingEitherReadingFindsSurvivesAndTheFrontmatterStaysWhole( final AclDirectiveFixtures.Fixture f ) {
        final String out = AclDirectiveText.strip( f.text() );
        assertNothingReadable( out );
        if ( f.loginHidden() ) {
            assertFalse( out.contains( "example.test" ), "a rule's principals reached the reader: " + out );
        }
        assertTrue( out.contains( AclDirectiveFixtures.BODY ), out );
        assertEquals( FrontmatterParser.parse( f.text() ).metadata(), FrontmatterParser.parse( out ).metadata(),
                "the frontmatter must survive whole: " + out );
    }

    @Test
    void anUnterminatedOpenerInAFrontmatterValueNeverReachesPastTheFrontmatter() {
        // The re-review's repro: removing "[{ALLOW view ... }]" up to a body closer deleted the closing "---", the YAML
        // above it (verified_by: <login>) became body text and skipped the display-name substitution of metadata.
        final String text = "---\ntitle: Access rules\nverified_by: alice-login\nsummary: How to use [{ALLOW view and "
                + "[{DENY edit rules\n---\nUse `[{ALLOW edit Alice}]` to restrict a page.\n";
        final String out = AclDirectiveText.strip( text );
        final ParsedPage parsed = FrontmatterParser.parse( out );
        assertEquals( "alice-login", parsed.metadata().get( "verified_by" ), out );
        assertFalse( parsed.body().contains( "verified_by" ), out );
        assertSame( text, out, "nothing here is a directive" );
    }

    @Test
    void aOneLineRuleThatSwallowsTheClosingDelimiterIsRemovedButTheDelimiterStays() {
        // "[{ALLOW edit\n---\n}]" is a one-line shape (only whitespace runs cross the line ends): the ACL manager enforces
        // it (principal "---"), so it must go, and the frontmatter must still end where it did.
        final String text = "---\ntitle: X\nverified_by: alice-login\nnote: see [{ALLOW edit\n---\n}] Body.\n";
        assertEquals( 1, DefaultAclManager.aclDirectives( text ).size(), "premise: the rule is enforced" );
        final String out = AclDirectiveText.strip( text );
        final ParsedPage parsed = FrontmatterParser.parse( out );
        assertEquals( "alice-login", parsed.metadata().get( "verified_by" ), out );
        assertFalse( parsed.body().contains( "verified_by" ), out );
        assertEquals( List.of(), DefaultAclManager.aclDirectives( out ), out );
    }

    @Test
    void aRemovalThatWouldSpliceAnEarlierClosingDelimiterDropsTheFrontmatterInsteadOfShowingIt() {
        // "[{ALLOW view x}]---" removed leaves "---" on a line of its own: the frontmatter would end early and the YAML
        // after it (verified_by: <login>) would be read as body. Readers get no frontmatter rather than that.
        final String text = "---\ntitle: X\n[{ALLOW view x}]---\nverified_by: alice-login\n---\nBody.\n";
        final String out = AclDirectiveText.strip( text );
        assertFalse( FrontmatterParser.parse( out ).body().contains( "alice-login" ), out );
        assertFalse( out.contains( "[{ALLOW" ), out );
        assertTrue( FrontmatterParser.parse( out ).body().contains( "Body." ), out );
    }

    /** Neither the ACL manager's text scan nor the render path reads any rule in {@code out}. */
    static void assertNothingReadable( final String out ) {
        assertEquals( List.of(), DefaultAclManager.aclDirectives( out ), "the text scan still reads a rule: " + out );
        assertEquals( List.of(), RenderPathAccessRules.find( out ), "the render path still reads a rule: " + out );
    }

    static Stream< AclDirectiveFixtures.Fixture > enforcementRepros() {
        return AclDirectiveFixtures.ENFORCEMENT_REPROS.stream();
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "enforcementRepros" )
    void theReviewsReprosAreStrippedOfEveryRuleWithoutThrowing( final AclDirectiveFixtures.Fixture f ) {
        final String out = AclDirectiveText.strip( f.text() );
        assertNothingReadable( out );
        assertFalse( out.contains( "ALLOW view Admin" ) || out.contains( "ALLOW edit alice" ), out );
    }

    @Test
    void aRuleInADisplayMathBlockThatAFenceBreaksIsStripped() {
        // Round 1 mapped this rule's span to nothing: the render path read (and enforced) it, nothing removed it.
        final String out = AclDirectiveText.strip( AclDirectiveFixtures.MATH_FENCE.text() );
        assertNothingReadable( out );
        assertFalse( out.contains( LOGIN ), out );
    }

    // ---- a rule alone on its line goes with the line's terminator, so no blank line changes the page's structure

    @Test
    void aRuleAloneOnItsLineInsideAnHtmlBlockLeavesTheBlockWhole() {
        final String text = "<details>\n```\n[{ALLOW edit Admin," + LOGIN + "}]\n```\n</details>\n\nBody.\n";
        assertEquals( "<details>\n```\n```\n</details>\n\nBody.\n", AclDirectiveText.strip( text ) );
    }

    @Test
    void aRuleAloneOnItsLineKeepsItsParagraphOneParagraph() {
        assertEquals( "Intro\nMore", AclDirectiveText.strip( "Intro\n  [{ALLOW view All}]  \nMore" ) );
        assertEquals( "a\r\nb", AclDirectiveText.strip( "a\r\n[{ALLOW view All}]\r\nb" ) );
        assertEquals( "Body", AclDirectiveText.strip( "Body\n[{ALLOW view All}]" ), "on the last line the line end before it goes" );
        assertEquals( "Body", AclDirectiveText.strip( "[{ALLOW view All}] [{ALLOW edit " + LOGIN + "}]\nBody" ),
                "two rules that are all a line holds go with it" );
        assertEquals( "Intro  more", AclDirectiveText.strip( "Intro [{ALLOW view All}] more" ), "a rule inside a line leaves the line" );
        assertEquals( "Intro\nmore", AclDirectiveText.strip( "Intro\n[{ALLOW view All}]\nmore", " " ),
                "the replacement is not needed where the line end keeps the words apart" );
    }
}
