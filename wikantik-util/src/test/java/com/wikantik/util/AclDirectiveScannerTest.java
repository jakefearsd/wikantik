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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AclDirectiveScanner} must be the regular expression {@code DefaultAclManager} used to enforce access rules,
 * match for match, without that expression's super-linear running time. The original expressions are kept here, as
 * the oracle, and compared on tens of thousands of seeded random inputs built from the characters and fragments
 * that make the expression interesting (every line terminator, whitespace that is and is not {@code \s}, closers
 * and half-closers, permission prefixes, nested openers).
 */
class AclDirectiveScannerTest {

    /** {@code DefaultAclManager}'s expression, verbatim ({@code ACL_REGEX} with {@code PERM_REGEX} expanded). */
    private static final Pattern ORACLE_ALLOW = Pattern.compile(
            "\\[\\{\\s*ALLOW\\s+(comment|delete|edit|modify|rename|upload|view)\\s*(.*?)\\s*\\}\\]" );

    /** The same with {@code DENY} accepted, as the util pass's expression had it (group 1 added for the head). */
    private static final Pattern ORACLE_ANY = Pattern.compile(
            "\\[\\{\\s*(ALLOW|DENY)\\s+(comment|delete|edit|modify|rename|upload|view)\\s*(.*?)\\s*\\}\\]" );

    /**
     * Wide mode is the same expression with {@code .} allowed to match a line terminator, less every match that contains
     * a blank line (the renderer's link label ends there): {@link #oracleFindFrom} skips such a match and looks again
     * just past its start.
     */
    private static final Pattern ORACLE_ALLOW_WIDE = Pattern.compile( ORACLE_ALLOW.pattern(), Pattern.DOTALL );
    private static final Pattern ORACLE_ANY_WIDE = Pattern.compile( ORACLE_ANY.pattern(), Pattern.DOTALL );

    /** A blank line: a line ending ({@code \r\n} is one), then only spaces and tabs, then another line ending. */
    private static final Pattern BLANK_LINE = Pattern.compile( "(?:\\r\\n|\\r(?!\\n)|\\n)[ \\t]*[\\r\\n]" );

    private static final long SEED = 0x5CA77E4L;
    private static final int RANDOM_INPUTS = 40_000;

    private static final String[] FRAGMENTS = {
            "[{", "[{", "[{ALLOW edit ", "[{ALLOW view", "[{DENY view ", "[{ DENY comment", "[{ALLOW  upload\t",
            "[{ALLOWED edit ", "[{allow edit ", "[{ALLOW editor x", "[{ALLOW fly ", "[{ALLOW}", "ALLOW", "DENY",
            "edit", "view", "comment", "delete", "modify", "rename", "upload", "ed", "e",
            " ", "  ", "\t", "\n", "\r", "\r\n", "\n\n", "\n \t\n", "\r\n\r\n", "\u0085", "\u2028", "\u2029", "\u000B", "\f", "\u00a0", "\u2003",
            "}]", "}]", "}", "]", "}}]", "}]]", "}\n]", "} ]",
            "x", "alice@example.test", ",", "Admin,Bob", "(", ")", "()", "`", "[", "{", "[{TableOfContents}]" };

    private record Hit( int start, int end, String keyword, String permission, int principalsStart, int principalsEnd ) {
    }

    private static Pattern oraclePattern( final boolean includeDeny, final boolean wide ) {
        if ( wide ) {
            return includeDeny ? ORACLE_ANY_WIDE : ORACLE_ALLOW_WIDE;
        }
        return includeDeny ? ORACLE_ANY : ORACLE_ALLOW;
    }

    private static Hit hit( final Matcher m, final boolean includeDeny ) {
        final int g = includeDeny ? 1 : 0;
        return new Hit( m.start(), m.end(), includeDeny ? m.group( 1 ) : "ALLOW", m.group( 1 + g ),
                m.start( 2 + g ), m.end( 2 + g ) );
    }

    private static List< Hit > oracle( final String text, final boolean includeDeny ) {
        return oracle( text, includeDeny, false );
    }

    private static List< Hit > oracle( final String text, final boolean includeDeny, final boolean wide ) {
        final List< Hit > hits = new ArrayList<>();
        for ( Hit h = oracleFindFrom( text, 0, includeDeny, wide ); h != null; h = oracleFindFrom( text, h.end(), includeDeny, wide ) ) {
            hits.add( h );
        }
        return hits;
    }

    private static Hit hit( final AclDirectiveScanner.Span s ) {
        return s == null ? null
                : new Hit( s.start(), s.end(), s.keyword(), s.permission(), s.principalsStart(), s.principalsEnd() );
    }

    private static List< Hit > scanned( final String text, final boolean includeDeny ) {
        return scanned( text, includeDeny, false );
    }

    private static List< Hit > scanned( final String text, final boolean includeDeny, final boolean wide ) {
        final List< Hit > hits = new ArrayList<>();
        for ( final AclDirectiveScanner.Span s : AclDirectiveScanner.findAll( text, includeDeny, wide ) ) {
            hits.add( hit( s ) );
        }
        return hits;
    }

    /** What {@code Matcher.find( from )} reports on the oracle: the first match that starts at or after {@code from}. */
    private static Hit oracleFindFrom( final String text, final int from, final boolean includeDeny, final boolean wide ) {
        final Matcher m = oraclePattern( includeDeny, wide ).matcher( text );
        int at = from;
        while ( at <= text.length() && m.find( at ) ) {
            if ( !wide || !BLANK_LINE.matcher( m.group() ).find() ) {
                return hit( m, includeDeny );
            }
            at = m.start() + 1;
        }
        return null;
    }

    private static String printable( final String text ) {
        final StringBuilder sb = new StringBuilder();
        for ( final char c : text.toCharArray() ) {
            if ( c >= 0x20 && c < 0x7F ) {
                sb.append( c );
            } else {
                sb.append( String.format( "\\u%04x", ( int ) c ) );
            }
        }
        return sb.toString();
    }

    // ---- the differential

    @Test
    void matchesTheOriginalExpressionOnSeededRandomInputsInAllowOnlyMode() {
        final Random random = new Random( SEED );
        for ( int i = 0; i < RANDOM_INPUTS; i++ ) {
            final String text = randomText( random );
            assertEquals( oracle( text, false ), scanned( text, false ),
                    "input #" + i + " (seed " + SEED + "): " + printable( text ) );
        }
    }

    @Test
    void matchesTheOriginalExpressionOnSeededRandomInputsWithDenyAccepted() {
        final Random random = new Random( SEED + 1 );
        for ( int i = 0; i < RANDOM_INPUTS; i++ ) {
            final String text = randomText( random );
            assertEquals( oracle( text, true ), scanned( text, true ),
                    "input #" + i + " (seed " + ( SEED + 1 ) + "): " + printable( text ) );
        }
    }

    @Test
    void wideModeIsTheOriginalExpressionCompiledWithDotAllLessMatchesThatSpanABlankLine() {
        final Random random = new Random( SEED + 2 );
        for ( int i = 0; i < RANDOM_INPUTS; i++ ) {
            final String text = randomText( random );
            for ( final boolean includeDeny : new boolean[]{ false, true } ) {
                assertEquals( oracle( text, includeDeny, true ), scanned( text, includeDeny, true ),
                        "input #" + i + " (seed " + ( SEED + 2 ) + ", deny " + includeDeny + "): " + printable( text ) );
            }
        }
    }

    @Test
    void searchingFromAnyPointFindsWhatMatcherFindFromFinds() {
        // The util pass drives the scanner with next( end ) after a removal and next( start + 1 ) after a skipped
        // candidate; both must agree with Matcher.find( from ) on the original expression, in either mode.
        final Random random = new Random( SEED + 3 );
        for ( int i = 0; i < RANDOM_INPUTS / 2; i++ ) {
            final String text = randomText( random );
            for ( final boolean wide : new boolean[]{ false, true } ) {
                final boolean includeDeny = random.nextBoolean();
                final AclDirectiveScanner scanner = new AclDirectiveScanner( text, includeDeny, wide );
                int from = 0;
                int steps = 0;
                while ( true ) {
                    final Hit expected = oracleFindFrom( text, from, includeDeny, wide );
                    final Hit actual = hit( scanner.next( from ) );
                    assertEquals( expected, actual, "input #" + i + " (seed " + ( SEED + 3 ) + ", wide " + wide
                            + ", deny " + includeDeny + ") from " + from + " after " + steps + " steps: " + printable( text ) );
                    if ( expected == null ) {
                        break;
                    }
                    from = random.nextBoolean() ? expected.end() : expected.start() + 1;
                    steps++;
                }
            }
        }
    }

    @Test
    void theRandomInputsReallyExerciseMatchesAndNearMisses() {
        final Random random = new Random( SEED );
        int withMatch = 0;
        int withHeadButNoMatch = 0;
        for ( int i = 0; i < RANDOM_INPUTS; i++ ) {
            final String text = randomText( random );
            if ( !oracle( text, false ).isEmpty() ) {
                withMatch++;
            } else if ( text.contains( "[{ALLOW" ) ) {
                withHeadButNoMatch++;
            }
        }
        assertTrue( withMatch > RANDOM_INPUTS / 20, "too few inputs contain a directive: " + withMatch );
        assertTrue( withHeadButNoMatch > RANDOM_INPUTS / 50, "too few near-misses: " + withHeadButNoMatch );
    }

    private static String randomText( final Random random ) {
        final int parts = 1 + random.nextInt( 14 );
        final StringBuilder sb = new StringBuilder();
        for ( int p = 0; p < parts; p++ ) {
            sb.append( FRAGMENTS[ random.nextInt( FRAGMENTS.length ) ] );
        }
        return sb.toString();
    }

    // ---- hand-picked shapes (the differential covers them too; these read as documentation)

    @Test
    void reportsTheSpanThePermissionAndThePrincipalsLikeTheExpressionDoes() {
        final String text = "pre [{ ALLOW  edit \t Admin,bob  }] post";
        final List< AclDirectiveScanner.Span > found = AclDirectiveScanner.findAll( text, false );

        assertEquals( 1, found.size() );
        final AclDirectiveScanner.Span span = found.get( 0 );
        assertEquals( "[{ ALLOW  edit \t Admin,bob  }]", text.substring( span.start(), span.end() ) );
        assertEquals( "edit", span.permission() );
        assertEquals( "Admin,bob", text.substring( span.principalsStart(), span.principalsEnd() ) );
    }

    @Test
    void aPermissionPrefixIsAPermissionFollowedByPrincipals() {
        final String text = "[{ALLOW editor x}]";
        final AclDirectiveScanner.Span span = AclDirectiveScanner.findAll( text, false ).get( 0 );
        assertEquals( "edit", span.permission() );
        assertEquals( "or x", text.substring( span.principalsStart(), span.principalsEnd() ) );
    }

    @Test
    void denyIsOnlyFoundWhenAsked() {
        final String text = "[{DENY view bob}]";
        assertTrue( AclDirectiveScanner.findAll( text, false ).isEmpty() );
        assertEquals( "DENY", AclDirectiveScanner.findAll( text, true ).get( 0 ).keyword() );
    }

    @Test
    void searchResumesAtTheEndOfAMatchAndJustPastTheStartOfAMiss() {
        final String text = "[{ALLOW vi[{ALLOW view a}]ew a}]";
        final List< AclDirectiveScanner.Span > found = AclDirectiveScanner.findAll( text, false );
        assertEquals( 1, found.size(), "the outer head is not followed by a permission, so only the inner one matches" );
        assertEquals( "[{ALLOW view a}]", text.substring( found.get( 0 ).start(), found.get( 0 ).end() ) );
    }

    @Test
    void nextCanBeToldToLookPastACandidate() {
        final String text = "[{ALLOW view a}] [{ALLOW edit b}]";
        final AclDirectiveScanner scanner = new AclDirectiveScanner( text, false );
        final AclDirectiveScanner.Span first = scanner.next( 0 );
        final AclDirectiveScanner.Span second = scanner.next( first.start() + 1 );
        assertEquals( 0, first.start() );
        assertEquals( "[{ALLOW edit b}]", text.substring( second.start(), second.end() ) );
        assertNull( scanner.next( second.start() + 1 ) );
    }

    @Test
    void emptyParenthesesFollowingASpanAreReportedByTheHelper() {
        final String text = "[{ALLOW view All}]() x";
        final AclDirectiveScanner.Span span = AclDirectiveScanner.findAll( text, false ).get( 0 );
        assertEquals( span.end() + 2, AclDirectiveScanner.endWithEmptyParentheses( text, span ) );
        final String bare = "[{ALLOW view All}] x";
        final AclDirectiveScanner.Span bareSpan = AclDirectiveScanner.findAll( bare, false ).get( 0 );
        assertEquals( bareSpan.end(), AclDirectiveScanner.endWithEmptyParentheses( bare, bareSpan ) );
    }

    @Test
    void nullAndOpenerFreeTextHaveNoDirectives() {
        assertTrue( AclDirectiveScanner.findAll( null, true ).isEmpty() );
        assertTrue( AclDirectiveScanner.findAll( "no openers { here ] }]", true ).isEmpty() );
    }

    // ---- running time: shapes on which the original expression takes minutes

    private static final Duration BUDGET = Duration.ofSeconds( 5 );

    @Test
    void aLongRunOfWhitespaceAfterThePermissionIsLinear() {
        // The original expression is cubic here: \s* (.*?) \s* can each take any part of the run.
        final String text = "[{ALLOW edit" + " ".repeat( 200_000 ) + "x";
        assertTimeoutPreemptively( BUDGET, () -> assertTrue( AclDirectiveScanner.findAll( text, true ).isEmpty() ) );
    }

    @Test
    void aLongRunOfWhitespaceBeforeACloserIsLinear() {
        final String text = "[{ALLOW edit" + " ".repeat( 200_000 ) + "x" + " ".repeat( 200_000 ) + "}]";
        assertTimeoutPreemptively( BUDGET, () -> assertEquals( 1, AclDirectiveScanner.findAll( text, true ).size() ) );
    }

    @Test
    void manyUnterminatedOpenersOnOneLineFollowedByALaterCloserAreLinear() {
        // Quadratic for the original expression: every opener runs to the closer on the next line and fails there.
        final String text = "[{ALLOW edit alice ".repeat( 40_000 ) + "\nx}]";
        assertTimeoutPreemptively( BUDGET, () -> assertTrue( AclDirectiveScanner.findAll( text, true ).isEmpty() ) );
    }

    @Test
    void manyOpenersWithNoCloserAtAllAreLinear() {
        final String text = "[{ALLOW edit ".repeat( 100_000 );
        assertTimeoutPreemptively( BUDGET, () -> assertTrue( AclDirectiveScanner.findAll( text, true ).isEmpty() ) );
    }

    @Test
    void manyDirectivesAreAllFoundInLinearTime() {
        final String text = "[{ALLOW edit alice@example.test}] ".repeat( 100_000 );
        assertTimeoutPreemptively( BUDGET, () -> assertEquals( 100_000, AclDirectiveScanner.findAll( text, true ).size() ) );
    }

    @Test
    void aLineTerminatorHiddenAmongManyCandidatesIsFoundOnceNotPerCandidate() {
        final String text = "[{ALLOW edit a".repeat( 60_000 ) + "\u0085}]";
        assertTimeoutPreemptively( BUDGET, () -> assertTrue( AclDirectiveScanner.findAll( text, true ).isEmpty() ) );
    }

    @Test
    void wideModeNeverRunsAcrossABlankLine() {
        assertEquals( List.of(), scanned( "[{ALLOW edit a,\n\nb}]", true, true ) );
        assertEquals( List.of(), scanned( "[{ALLOW edit a,\r\n \t\r\nb}]", true, true ) );
        assertEquals( List.of(), scanned( "[{\n\nALLOW edit a}]", true, true ) );
        assertEquals( 1, scanned( "[{ALLOW edit a,\r\nb}]", true, true ).size(), "one CRLF is one line ending, not a blank line" );
        // A later opener after the blank line still matches the same closer.
        assertEquals( List.of( new Hit( 17, 35, "ALLOW", "view", 30, 33 ) ),
                scanned( "[{ALLOW edit a,\n\n[{ALLOW view bob}]", true, true ) );
    }

    @Test
    void manyBlankLinesBetweenManyOpenersAndOneCloserAreLinear() {
        final String text = "[{ALLOW edit a,\n\n".repeat( 100_000 ) + "[{ALLOW view z}]";
        assertTimeoutPreemptively( BUDGET, () -> assertEquals( 1, AclDirectiveScanner.findAll( text, true, true ).size() ) );
    }

    @Test
    void theUtilPassIsLinearOnTheSameShapes() {
        final String spaces = "[{ALLOW edit" + " ".repeat( 200_000 ) + "x";
        final String openers = "[{ALLOW edit alice ".repeat( 40_000 ) + "\nx}]";
        assertTimeoutPreemptively( BUDGET, () -> {
            assertEquals( spaces, AclDirectives.strip( spaces ) );
            // The util pass reads the multi-line shape, so the first opener runs to the only closer, at the very end.
            assertEquals( "", AclDirectives.strip( openers ) );
        } );
    }
}
