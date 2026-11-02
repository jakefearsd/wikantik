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

import com.wikantik.api.exceptions.AccessRuleShapeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save-time half of #104: a save may not add an access rule the reader cannot read (it would lock the page), but a
 * disputed span the stored page already holds may stay, so an administrator can still save other fixes to a locked page.
 */
class AccessRuleShapeCheckTest {

    private static final String PAGE = "ShapeCheckPage";

    private final Map< String, String > stored = new HashMap<>();
    private final AccessRuleShapeCheck check = new AccessRuleShapeCheck( stored::get );

    private void save( final String text ) throws AccessRuleShapeException {
        check.check( PAGE, text );
    }

    @Test
    void addingADenyRuleIsRefusedWithItsShapeLineAndTheCanonicalForm() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "First line.\n\n[{DENY view Bob}]\n\nMore.\n" ) );
        assertEquals( 1, e.violations().size() );
        final AccessRuleShapeException.Violation v = e.violations().get( 0 );
        assertEquals( AccessRuleShapeException.CODE, v.code() );
        assertEquals( "ACCESS_RULE_SHAPE", v.code() );
        assertEquals( "DENY", v.shape() );
        assertEquals( 3, v.line() );
        assertNull( v.field() );
        assertEquals( "[{DENY view Bob}]", v.text() );
        assertTrue( v.message().contains( "line 3" ), v.message() );
        assertTrue( v.message().contains( "DENY" ), v.message() );
        assertTrue( v.message().contains( "write `[{ALLOW …}]`; to show the syntax, put it in `code`" ), v.message() );
        assertTrue( e.getMessage().contains( v.message() ), e.getMessage() );
    }

    /** A megabyte of openers is about 150,000 violations: the refusal names the first ones and counts the rest. */
    @Test
    void aRefusalOfAMegabyteOfOpenersIsBounded() {
        final String text = "[{deny ".repeat( 150_000 ) + "}]";
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class, () -> save( text ) );
        assertEquals( AccessRuleShapeException.MAX_VIOLATIONS, e.violations().size() );
        assertTrue( e.total() > 100_000, "total " + e.total() );
        assertTrue( e.getMessage().length() < 64 * 1024, "message of " + e.getMessage().length() + " chars" );
        assertTrue( e.getMessage().contains( ( e.total() - AccessRuleShapeException.MAX_VIOLATIONS ) + " more" ), e.getMessage().substring( e.getMessage().length() - 200 ) );
    }

    /** The editor shows the body without its frontmatter, so the line is counted from the body's first line. */
    @Test
    void theLineIsTheBodyLineTheEditorShows() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "---\ncanonical_id: 01ABC\ntitle: T\ntype: article\n---\nIntro.\n\n[{DENY view Bob}]\n" ) );
        final AccessRuleShapeException.Violation v = e.violations().get( 0 );
        assertEquals( 3, v.line() );
        assertTrue( v.message().contains( "line 3" ), v.message() );
    }

    /** Every shape the reader disputes is refused when new; the decision is the reader's, not a list kept here. */
    @ParameterizedTest( name = "{0}" )
    @MethodSource( "shapes" )
    void aNewSpanIsRefusedExactlyWhenTheReaderLocksThePage( final AclDirectiveFixtures.Shaped row ) {
        if ( AccessRules.read( row.text() ) instanceof AccessRules.Locked ) {
            final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class, () -> save( row.text() ) );
            assertEquals( row.shape().name(), e.violations().get( 0 ).shape() );
        } else {
            assertDoesNotThrow( () -> save( row.text() ) );
        }
    }

    /** §5.3: the shapes whose intent is clear are rules (or, escaped, text), so a new one saves. */
    @ParameterizedTest( name = "{0}" )
    @ValueSource( strings = {
            "[{ALLOW edit Admin,\n bob}]\n\nBody.\n", "[{allow VIEW Bob}]\n\nBody.\n", "[{ ALLOW view Bob}]\n\nBody.\n",
            "Write \\[{ALLOW view Bob}] to restrict.\n" } )
    void anUnambiguousRuleShapeOrAnEscapedRuleSaves( final String text ) {
        assertDoesNotThrow( () -> save( text ) );
    }

    /** A save of many overlapping disputed candidates is refused without costing the square of the text. */
    @Test
    void manyOpenersSharingOneCloserAreRefusedQuickly() {
        final String text = "[{DENY edit alice ".repeat( 40_000 ) + "\nx}]";
        assertTimeoutPreemptively( Duration.ofSeconds( 10 ),
                () -> assertThrows( AccessRuleShapeException.class, () -> save( text ) ) );
    }

    /**
     * Overlapping disputed spans (each opener to the one shared closer) may not cost a copy of the text each: that is the
     * square of the text in memory. Lower-case openers are spans of their own, unlike the upper-case ones above.
     */
    @ParameterizedTest( name = "{0}" )
    @ValueSource( strings = { "[{deny edit alice ", "[{allow edit alice " } )
    void manyLowerCaseOpenersSharingOneCloserAreRefusedQuickly( final String opener ) {
        final String text = opener.repeat( 40_000 ) + "\nx}]";
        AccessRules.read( "[{allow view warm}] x" );
        assertTimeoutPreemptively( Duration.ofSeconds( 10 ),
                () -> assertThrows( AccessRuleShapeException.class, () -> save( text ) ) );
    }

    /** A page whose overlapping disputed spans are all already stored may still be saved with other changes. */
    @Test
    void overlappingDisputedSpansAlreadyStoredMayStay() {
        final String spans = "[{deny edit a [{deny edit b}] and [{deny view c}]";
        stored.put( PAGE, "Old intro.\n\n" + spans + "\n" );
        assertDoesNotThrow( () -> save( "New intro, longer.\n\n" + spans + "\n\nMore.\n" ) );
        assertThrows( AccessRuleShapeException.class,
                () -> save( "New intro.\n\n[{deny edit a [{deny edit B}] and [{deny view c}]\n" ) );
    }

    @Test
    void aLowerCaseDenyIsRefused() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "[{allow view a}] [{deny view Bob}]\n" ) );
        assertEquals( List.of( "DENY" ), e.violations().stream().map( AccessRuleShapeException.Violation::shape ).toList() );
    }

    /** A line break that splits a name is refused, and the refusal says how to write the rule instead. */
    @Test
    void aWrappedRuleWhoseLineBreakSplitsANameIsRefusedWithTheFix() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "[{ALLOW edit Jane\n Bob}]\n\nBody.\n" ) );
        final AccessRuleShapeException.Violation v = e.violations().get( 0 );
        assertEquals( "WRAPPED_NAME", v.shape() );
        assertTrue( v.message().contains( "one name or two" ), v.message() );
        assertTrue( v.message().contains( "comma" ), v.message() );
    }

    /** I4: "commenting out" a rule does not disable it, so the save says so and how to keep it as a note. */
    @Test
    void aRuleInsideACommentIsRefusedWithGuidance() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "Intro.\n\n%% later: [{ALLOW view All}] %%\n" ) );
        final AccessRuleShapeException.Violation v = e.violations().get( 0 );
        assertEquals( "IN_COMMENT", v.shape() );
        assertTrue( v.message().contains( "a rule inside a %% comment is still read as a rule; delete it, or put it in `code` "
                + "to keep it as a note" ), v.message() );
    }

    /** Every shape that locks a page has its own explanation, and no shape that does not lock keeps one. */
    @Test
    void everyDisputedShapeAndOnlyThoseHaveAReason() {
        for ( final AccessRules.Shape shape : AccessRules.Shape.values() ) {
            assertEquals( AccessRules.disputed( shape ), AccessRuleShapeCheck.WHY.containsKey( shape ), shape.name() );
        }
    }

    @Test
    void anUnchangedDisputedSpanMayStayWhileTheRestOfThePageIsEdited() {
        stored.put( PAGE, "Intro.\n\n[{DENY view Bob}]\n\nOld body.\n" );
        assertDoesNotThrow( () -> save( "Intro, reworded.\n\n[{DENY view Bob}]\n\nNew body, longer.\n\nAnd more.\n" ) );
    }

    @Test
    void aStoredSpanWithCrlfLineEndsMatchesTheSameSpanSentWithLf() {
        stored.put( PAGE, "Intro.\r\n\r\n[{DENY view Admin,\r\n bob}]\r\n\r\nBody.\r\n" );
        assertDoesNotThrow( () -> save( "Intro, edited.\n\n[{DENY view Admin,\n bob}]\n\nBody.\n" ) );
    }

    @Test
    void aSecondCopyOfAStoredDisputedSpanIsNew() {
        stored.put( PAGE, "[{DENY view Bob}]\n\nBody.\n" );
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "[{DENY view Bob}]\n\nBody.\n\n[{DENY view Bob}]\n" ) );
        assertEquals( 1, e.violations().size() );
        assertEquals( 5, e.violations().get( 0 ).line() );
    }

    @Test
    void aDifferentDisputedSpanBesideAStoredOneIsRefusedAloneNotTheStoredOne() {
        stored.put( PAGE, "[{DENY view Bob}]\n\nBody.\n" );
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "[{DENY view Bob}]\n\nBody.\n\n[{ALLOWED edit Bob}]\n" ) );
        assertEquals( List.of( "ALLOW_PREFIXED_WORD" ), e.violations().stream().map( AccessRuleShapeException.Violation::shape ).toList() );
    }

    @Test
    void removingTheDisputedSpanIsAllowed() {
        stored.put( PAGE, "[{DENY view Bob}]\n\nBody.\n" );
        assertDoesNotThrow( () -> save( "[{ALLOW view Bob}]\n\nBody.\n" ) );
    }

    /** The editor shows the frontmatter as fields: a rule there is reported by its field, not by a line. */
    @Test
    void aRuleInTheFrontmatterIsRefusedWithItsField() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "---\ntitle: T\nsummary: [{ALLOW view Admin}]\n---\nBody.\n" ) );
        final AccessRuleShapeException.Violation v = e.violations().get( 0 );
        assertEquals( "IN_FRONTMATTER", v.shape() );
        assertEquals( "summary", v.field() );
        assertNull( v.line() );
        assertTrue( v.message().contains( "frontmatter field `summary`" ), v.message() );
        assertFalse( v.message().contains( "line" ), v.message() );
    }

    @Test
    void textWithoutAccessRulesAndCanonicalRulesPassUntouched() {
        assertDoesNotThrow( () -> save( "Just prose.\n" ) );
        assertDoesNotThrow( () -> save( "[{ALLOW edit Bob}]\n\nBody.\n" ) );
        assertDoesNotThrow( () -> save( "Docs: write `[{DENY view Bob}]`.\n" ) );
    }

    @Test
    void nullOrEmptyContentPasses() {
        assertDoesNotThrow( () -> save( null ) );
        assertDoesNotThrow( () -> save( "" ) );
    }

    @Test
    void aStoredTextThatCannotBeReadCountsAsHoldingNothing() {
        final AccessRuleShapeCheck failing = new AccessRuleShapeCheck( name -> {
            throw new IllegalStateException( "provider down" );
        } );
        assertThrows( AccessRuleShapeException.class, () -> failing.check( PAGE, "[{DENY view Bob}]\n\nBody.\n" ) );
    }

    @Test
    void aSaveWithNoPageNameStillChecksTheText() {
        assertThrows( AccessRuleShapeException.class, () -> check.check( null, "[{DENY view Bob}]\n\nBody.\n" ) );
    }

    @Test
    void aWholeTextTheRenderParseCannotReadIsRefusedWhenNew() {
        final String unreadable = "[{ALLOW view Admin}]\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n";
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class, () -> save( unreadable ) );
        assertTrue( e.violations().stream().allMatch( v -> v.shape().equals( "UNREADABLE" ) ), e.getMessage() );
    }

    @Test
    void aStoredUnreadableTextMayBeSavedAgainWithOtherEdits() {
        stored.put( PAGE, "[{ALLOW view Admin}]\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n" );
        assertDoesNotThrow( () -> save( "[{ALLOW view Admin}]\n\nSecret text, edited.\n\n[^1]: [[^1]\\[{y}]\n" ) );
    }

    /**
     * A text the render parse cannot read, with no rule span of its own, is refused as a whole. What it is keyed on must
     * not move with the text (the reader's reason quotes offsets), or an administrator could save no other edit to it.
     */
    @Test
    void aStoredWholeTextUnreadableWithNoSpanMayBeSavedAgainWithEditsThatMoveIt() {
        final String before = "{ALLOW view Admin}\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n";
        final AccessRules.Locked locked = assertInstanceOf( AccessRules.Locked.class, AccessRules.read( before ) );
        assertTrue( locked.spans().stream().noneMatch( s -> AccessRules.disputed( s.shape() ) ),
                "precondition: no disputed span of its own, so the whole text is refused: " + locked );
        stored.put( PAGE, before );

        assertDoesNotThrow( () -> save( "{ALLOW view Admin}\n\nSecret text, edited and longer.\n\n[^1]: [[^1]\\[{y}]\n" ) );
    }

    @Test
    void aWholeTextRefusalSaysWhyOnce() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "{ALLOW view Admin}\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n" ) );
        final String message = e.violations().get( 0 ).message();
        final String why = "cannot be read the way the renderer reads it";
        assertEquals( message.indexOf( why ), message.lastIndexOf( why ), message );
        assertTrue( message.contains( "UNREADABLE" ), message );
    }

    static List< AclDirectiveFixtures.Shaped > shapes() {
        return AclDirectiveFixtures.SHAPES;
    }
}
