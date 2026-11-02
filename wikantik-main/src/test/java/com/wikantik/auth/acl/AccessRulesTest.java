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

import com.wikantik.auth.acl.AccessRules.Locked;
import com.wikantik.auth.acl.AccessRules.Reading;
import com.wikantik.auth.acl.AccessRules.Reason;
import com.wikantik.auth.acl.AccessRules.Rules;
import com.wikantik.auth.acl.AccessRules.Shape;
import com.wikantik.auth.acl.AccessRules.Span;
import com.wikantik.auth.acl.AclDirectiveFixtures.Fixture;
import com.wikantik.auth.acl.AclDirectiveFixtures.Shaped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AccessRules#read}: one reading per shape — the unambiguous ones are rules, an escaped one is text, the rest lock. */
class AccessRulesTest {

    static Stream< Shaped > shapes() {
        return AclDirectiveFixtures.SHAPES.stream();
    }

    static Stream< Fixture > fixtures() {
        return Stream.concat( AclDirectiveFixtures.ALL.stream(), AclDirectiveFixtures.ENFORCEMENT_REPROS.stream() );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "shapes" )
    void eachShapeReadsAsTheDesignSays( final Shaped row ) {
        final Reading reading = AccessRules.read( row.text() );
        if ( row.shape() == null ) {
            assertEquals( new Rules( List.of(), List.of() ), reading, "literal text: no rule, no span" );
        } else if ( !AccessRules.disputed( row.shape() ) ) {
            final Rules rules = assertInstanceOf( Rules.class, reading, "an unambiguous shape does not lock the page" );
            assertEquals( row.ruleLines(), rules.ruleLines(), "the rule lines, in canonical form" );
            assertEquals( List.of( row.shape() ), rules.spans().stream().map( Span::shape ).toList() );
            final Span span = rules.spans().get( 0 );
            final String spanText = row.text().substring( span.start(), span.end() );
            assertTrue( spanText.startsWith( "[{" ) && spanText.endsWith( "}]" ) || spanText.endsWith( "}]()" ), spanText );
        } else {
            final Locked locked = assertInstanceOf( Locked.class, reading, "a disputed shape locks the page" );
            assertEquals( List.of( row.shape() ), locked.reasons().stream().map( Reason::shape ).toList() );
            assertEquals( List.of( row.shape() ), locked.spans().stream().map( Span::shape ).toList() );
        }
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void eachFixtureIsLockedExactlyWhereTheDesignSays( final Fixture f ) {
        final Reading reading = AccessRules.read( f.text() );
        final Set< Shape > locks = AclDirectiveFixtures.EXPECTED_LOCKS.get( f.name() );
        if ( locks == null ) {
            final Rules rules = assertInstanceOf( Rules.class, reading );
            assertEquals( AclDirectiveFixtures.enforced( f ), pairs( rules.ruleLines() ), rules.toString() );
        } else {
            final Locked locked = assertInstanceOf( Locked.class, reading );
            assertEquals( locks, locked.reasons().stream().map( Reason::shape ).collect( Collectors.toSet() ) );
        }
    }

    /** {@code principal:action} pairs of canonical rule lines, as the fixtures record an ACL. */
    private static Set< String > pairs( final List< String > ruleLines ) {
        final Set< String > out = new HashSet<>();
        for ( final String line : ruleLines ) {
            final String[] words = line.split( " ", 3 );
            for ( final String principal : words[ 2 ].split( "," ) ) {
                out.add( principal.trim() + ":" + words[ 1 ] );
            }
        }
        return out;
    }

    /** §5.3: an escaped rule is how an author writes "not markup" — no rule, no lock, but still a span of the text. */
    @Test
    void anEscapedRuleIsLiteralTextBesideARealOne() {
        final String text = "Write \\[{ALLOW view Guest}] to restrict.\n\n[{ALLOW edit Admin}]\n";
        final Rules rules = assertInstanceOf( Rules.class, AccessRules.read( text ) );
        assertEquals( List.of( "ALLOW edit Admin" ), rules.ruleLines() );
        assertEquals( List.of( Shape.ESCAPED, Shape.CANONICAL ), rules.spans().stream().map( Span::shape ).toList() );
        assertEquals( List.of( Shape.CANONICAL ), rules.ruleSpans().stream().map( Span::shape ).toList(),
                "the spans that carry the rule lines, one per line" );
    }

    @Test
    void aWrappedRuleWithCrlfLineEndsJoinsWithSingleSpaces() {
        final Rules rules = assertInstanceOf( Rules.class, AccessRules.read( "[{ALLOW edit Admin,\r\n   bob}]\r\n\r\nBody.\r\n" ) );
        assertEquals( List.of( "ALLOW edit Admin, bob" ), rules.ruleLines() );
    }

    @Test
    void severalRuleShapesAreReadInOrder() {
        final Rules rules = assertInstanceOf( Rules.class, AccessRules.read(
                "[{allow view All}]\n[{ ALLOW EDIT Admin,\n alice}]\n\nText [{ALLOW comment bob}]() end.\n" ) );
        assertEquals( List.of( "ALLOW view All", "ALLOW edit Admin, alice", "ALLOW comment bob" ), rules.ruleLines() );
        assertEquals( List.of( Shape.CASE_VARIANT, Shape.WRAPPED, Shape.CANONICAL ),
                rules.spans().stream().map( Span::shape ).toList() );
    }

    @Test
    void aTextBuiltToBlowUpTheParseIsUnreadableQuickly() {
        final String text = "[{ALLOW edit" + " ".repeat( 200_000 ) + "x\n\nsee [{ALLOW view bob}] here";
        final Reading reading = assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> AccessRules.read( text ) );
        final Locked locked = assertInstanceOf( Locked.class, reading );
        assertEquals( Shape.UNREADABLE, locked.reasons().get( 0 ).shape() );
        assertTrue( locked.spans().stream().allMatch( s -> s.shape() == Shape.UNREADABLE ) && !locked.spans().isEmpty(),
                locked.spans().toString() );
    }

    /** Openers that share one closer give overlapping candidates: reading them may not cost the square of the text. */
    @Test
    void manyOpenersSharingOneCloserAreReadQuickly() {
        final String text = "[{ALLOW edit alice ".repeat( 40_000 ) + "\nx}]";
        final Reading reading = assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> AccessRules.read( text ) );
        assertInstanceOf( Locked.class, reading );
    }

    @Test
    void aTextWithNoDirectiveIsNotParsed() {
        final long before = AccessRules.PARSES.get();
        assertEquals( new Rules( List.of(), List.of() ),
                AccessRules.read( "---\ntitle: x\n---\nPlain [{TableOfContents}] text with `code`, $x$ and <b>html</b>.\n" ) );
        assertEquals( new Rules( List.of(), List.of() ), AccessRules.read( "" ) );
        assertEquals( new Rules( List.of(), List.of() ), AccessRules.read( null ) );
        assertEquals( before, AccessRules.PARSES.get(), "no {allow / {deny in any case: no parse" );

        AccessRules.read( "Some [{ aLLoW view x}] text" );
        assertEquals( before + 1, AccessRules.PARSES.get(), "a directive in any case, after any whitespace, is parsed" );
    }

    @Test
    void aReasonNamesTheLineAndTheText() {
        final Locked locked = assertInstanceOf( Locked.class,
                AccessRules.read( "[{ALLOW view All}]\nline two\n[{DENY edit bob}]\n\nBody.\n" ) );
        assertEquals( List.of( new Reason( Shape.DENY, 3, null, "[{DENY edit bob}]" ) ), locked.reasons() );
        assertEquals( List.of( Shape.CANONICAL, Shape.DENY ), locked.spans().stream().map( Span::shape ).toList(),
                "a locked reading still lists every span, canonical ones included" );
    }

    /** The editor shows the body alone, so a reason's line counts from the first line after the closing {@code ---}. */
    @Test
    void aReasonInTheBodyNamesItsBodyLineNotItsLineInTheWholeText() {
        final Locked locked = assertInstanceOf( Locked.class,
                AccessRules.read( "---\ntitle: T\ntype: article\n---\nIntro.\n\n[{DENY edit bob}]\n" ) );
        assertEquals( List.of( new Reason( Shape.DENY, 3, null, "[{DENY edit bob}]" ) ), locked.reasons() );
    }

    @Test
    void aBodyLineCountsTheSameWithCrlfLineEnds() {
        final Locked locked = assertInstanceOf( Locked.class,
                AccessRules.read( "---\r\ntitle: T\r\n---\r\n[{DENY edit bob}]\r\n" ) );
        assertEquals( List.of( new Reason( Shape.DENY, 1, null, "[{DENY edit bob}]" ) ), locked.reasons() );
    }

    /** The editor shows the frontmatter as fields, so a reason there names its field, not a line. */
    @Test
    void aReasonInTheFrontmatterNamesItsField() {
        final Locked scalar = assertInstanceOf( Locked.class,
                AccessRules.read( "---\ntitle: T\nsummary: '[{ALLOW view Admin}]'\n---\nBody.\n" ) );
        assertEquals( List.of( new Reason( Shape.IN_FRONTMATTER, null, "summary", "[{ALLOW view Admin}]" ) ), scalar.reasons() );

        final Locked listItem = assertInstanceOf( Locked.class,
                AccessRules.read( "---\ntitle: T\ntags:\n  - a\n  - '[{ALLOW view Admin}]'\nstatus: x\n---\nBody.\n" ) );
        assertEquals( "tags", listItem.reasons().get( 0 ).field() );

        final Locked folded = assertInstanceOf( Locked.class,
                AccessRules.read( "---\n\"summary\": >\n  Some words\n  [{ALLOW view Admin}]\n---\nBody [{DENY edit bob}].\n" ) );
        assertEquals( List.of( new Reason( Shape.IN_FRONTMATTER, null, "summary", "[{ALLOW view Admin}]" ),
                new Reason( Shape.DENY, 1, null, "[{DENY edit bob}]" ) ), folded.reasons() );
    }

    @Test
    void severalCanonicalRulesAreReadInOrder() {
        final Rules rules = assertInstanceOf( Rules.class,
                AccessRules.read( "Intro [{ALLOW view All}] and [{ALLOW edit Admin,alice}]() end.\n\n`[{DENY view x}]`\n" ) );
        assertEquals( List.of( "ALLOW view All", "ALLOW edit Admin,alice" ), rules.ruleLines() );
        assertEquals( 2, rules.spans().size() );
    }

    @Test
    void aRuleTheTextScanReadsInsideRenderCodeIsDisputed() {
        // Inside an HTML block the vanilla parse sees no fence, the render path sees an HTML block: never literal.
        final Reading reading = AccessRules.read( "<details>\n```\n[{ALLOW edit bob}]\n```\n</details>\n" );
        assertInstanceOf( Locked.class, reading );
    }

    @Test
    void anOverlongSpanIsCutShortInItsReason() {
        final String principals = "someone@example.test,\n ".repeat( 50 );
        final Locked locked = assertInstanceOf( Locked.class, AccessRules.read( "[{DENY edit " + principals + "x}]\n" ) );
        assertEquals( AccessRules.MAX_REASON_TEXT + 1, locked.reasons().get( 0 ).text().length() );
    }

    @ParameterizedTest
    @EnumSource( Shape.class )
    void theUnambiguousShapesAndEscapedTextAreUndisputed( final Shape shape ) {
        final Set< Shape > undisputed = Set.of( Shape.CANONICAL, Shape.WRAPPED, Shape.CASE_VARIANT, Shape.LEADING_SPACE, Shape.ESCAPED );
        assertEquals( !undisputed.contains( shape ), AccessRules.disputed( shape ) );
        assertEquals( undisputed.contains( shape ) && shape != Shape.ESCAPED, AccessRules.isRule( shape ) );
    }
}
