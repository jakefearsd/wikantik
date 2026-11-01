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

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The save-time half of #104: a save may not add an access rule the reader cannot read (it would lock the page), but a
 * disputed span the stored page already holds may stay, so an administrator can still save other fixes to a locked page.
 */
class AccessRuleShapeFilterTest {

    private static final String PAGE = "ShapeFilterPage";

    private final Map< String, String > stored = new HashMap<>();
    private final AccessRuleShapeFilter filter = new AccessRuleShapeFilter( stored::get );

    private static Context contextFor( final String name ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        return context;
    }

    private String save( final String text ) throws Exception {
        return filter.preSave( contextFor( PAGE ), text );
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
        assertEquals( "[{DENY view Bob}]", v.text() );
        assertTrue( v.message().contains( "line 3" ), v.message() );
        assertTrue( v.message().contains( "DENY" ), v.message() );
        assertTrue( v.message().contains( "write `[{ALLOW …}]`; to show the syntax, put it in `code`" ), v.message() );
        assertTrue( e.getMessage().contains( v.message() ), e.getMessage() );
    }

    /** Every shape the reader disputes is refused when new; the decision is the reader's, not a list kept here. */
    @ParameterizedTest( name = "{0}" )
    @MethodSource( "shapes" )
    void aNewSpanIsRefusedExactlyWhenTheReaderLocksThePage( final AclDirectiveFixtures.Shaped row ) throws Exception {
        if ( AccessRules.read( row.text() ) instanceof AccessRules.Locked ) {
            final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class, () -> save( row.text() ) );
            assertEquals( row.shape().name(), e.violations().get( 0 ).shape() );
        } else {
            assertSame( row.text(), save( row.text() ) );
        }
    }

    @Test
    void anUnchangedDisputedSpanMayStayWhileTheRestOfThePageIsEdited() throws Exception {
        stored.put( PAGE, "Intro.\n\n[{DENY view Bob}]\n\nOld body.\n" );
        final String edited = "Intro, reworded.\n\n[{DENY view Bob}]\n\nNew body, longer.\n\nAnd more.\n";
        assertSame( edited, save( edited ) );
    }

    @Test
    void aStoredSpanWithCrlfLineEndsMatchesTheSameSpanSentWithLf() throws Exception {
        stored.put( PAGE, "Intro.\r\n\r\n[{DENY view Admin,\r\n bob}]\r\n\r\nBody.\r\n" );
        final String edited = "Intro, edited.\n\n[{DENY view Admin,\n bob}]\n\nBody.\n";
        assertSame( edited, save( edited ) );
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
    void removingTheDisputedSpanIsAllowed() throws Exception {
        stored.put( PAGE, "[{DENY view Bob}]\n\nBody.\n" );
        final String fixed = "[{ALLOW view Bob}]\n\nBody.\n";
        assertSame( fixed, save( fixed ) );
    }

    @Test
    void aRuleInTheFrontmatterIsRefusedWithItsLine() {
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> save( "---\ntitle: T\nsummary: [{ALLOW view Admin}]\n---\nBody.\n" ) );
        assertEquals( "IN_FRONTMATTER", e.violations().get( 0 ).shape() );
        assertEquals( 3, e.violations().get( 0 ).line() );
    }

    @Test
    void textWithoutAccessRulesAndCanonicalRulesPassUntouched() throws Exception {
        assertSame( "Just prose.\n", save( "Just prose.\n" ) );
        assertSame( "[{ALLOW edit Bob}]\n\nBody.\n", save( "[{ALLOW edit Bob}]\n\nBody.\n" ) );
        assertSame( "Docs: write `[{DENY view Bob}]`.\n", save( "Docs: write `[{DENY view Bob}]`.\n" ) );
    }

    @Test
    void nullOrEmptyContentPassesThrough() throws Exception {
        assertEquals( null, save( null ) );
        assertSame( "", save( "" ) );
    }

    @Test
    void aStoredTextThatCannotBeReadCountsAsHoldingNothing() {
        final AccessRuleShapeFilter failing = new AccessRuleShapeFilter( name -> {
            throw new IllegalStateException( "provider down" );
        } );
        assertThrows( AccessRuleShapeException.class,
                () -> failing.preSave( contextFor( PAGE ), "[{DENY view Bob}]\n\nBody.\n" ) );
    }

    @Test
    void aSaveWithNoPageInTheContextStillChecksTheText() {
        assertThrows( AccessRuleShapeException.class, () -> filter.preSave( null, "[{DENY view Bob}]\n\nBody.\n" ) );
    }

    @Test
    void aWholeTextTheRenderParseCannotReadIsRefusedWhenNew() {
        final String unreadable = "[{ALLOW view Admin}]\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n";
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class, () -> save( unreadable ) );
        assertTrue( e.violations().stream().allMatch( v -> v.shape().equals( "UNREADABLE" ) ), e.getMessage() );
    }

    @Test
    void aStoredUnreadableTextMayBeSavedAgainWithOtherEdits() throws Exception {
        stored.put( PAGE, "[{ALLOW view Admin}]\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n" );
        final String edited = "[{ALLOW view Admin}]\n\nSecret text, edited.\n\n[^1]: [[^1]\\[{y}]\n";
        assertSame( edited, save( edited ) );
    }

    static List< AclDirectiveFixtures.Shaped > shapes() {
        return AclDirectiveFixtures.SHAPES;
    }
}
