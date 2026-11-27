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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a find request becomes a spec for one caller, or no spec at all when it can match nothing. */
class FindSpecsTest {

    private static final Instant NOW = Instant.parse( "2026-11-15T12:00:00Z" );
    private final WorkPeople people = new WorkPeople( WorkPeopleTest.DIRECTORY );
    private final Actor member = Actor.human( "u-c01d", Set.of(), false );
    private final Actor admin = Actor.human( "root", Set.of(), true );
    private final Actor agent = Actor.agent( 9, "bot", member );
    private final List< Project > visible = List.of(
        new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null, null ),
        new Project( 2, "OPS", "Ops", "", Autonomy.SUPERVISED, NOW, null, null ) );
    private final List< Product > products = List.of(
        new Product( 7, "web", "Web", null, null, null, Autonomy.DELEGATED, DeployGate.HUMAN, null ) );

    private static FindFilter filter( final String assignee, final String queue, final String reporter,
                                      final String product, final boolean watching, final String text ) {
        return new FindFilter( Set.of(), null, null, null, null, null, assignee, queue, reporter, product, null,
            watching, text, null, null, FindFilter.Sort.UPDATED, 0, null );
    }

    private java.util.Optional< FindSpec > resolve( final Actor who, final FindFilter f ) {
        return FindSpecs.resolve( who, f, visible, products, people );
    }

    @Test
    void anOpenFilterCoversEveryVisibleProjectWithNoPersonOrProductRestriction() {
        final FindSpec s = resolve( member, filter( null, null, null, null, false, null ) ).orElseThrow();
        assertEquals( Set.of( 1L, 2L ), s.projectIds() );
        assertNull( s.assigneeLogin() );
        assertNull( s.assigneeAgentId() );
        assertNull( s.reporterLogin() );
        assertNull( s.productId() );
        assertNull( s.watcherLogin() );
    }

    @Test
    void blankPeopleQueuesAndTextMeanNoRestriction() {
        final FindSpec s = resolve( member, filter( "  ", " ", "", "  ", false, "   " ) ).orElseThrow();
        assertNull( s.assigneeLogin() );
        assertNull( s.reporterLogin() );
        assertNull( s.queue() );
        assertNull( s.text() );
        assertNull( s.productId() );
        final FindSpec named = resolve( member, filter( null, "implementer", null, null, false, "needle" ) ).orElseThrow();
        assertEquals( "implementer", named.queue() );
        assertEquals( "needle", named.text() );
    }

    @Test
    void meIsTheCallerByLoginForAPersonAndByIdForAnAgent() {
        final FindSpec person = resolve( member, filter( "me", null, "me", null, false, null ) ).orElseThrow();
        assertEquals( "u-c01d", person.assigneeLogin() );
        assertNull( person.assigneeAgentId() );
        assertEquals( "u-c01d", person.reporterLogin() );
        final FindSpec bot = resolve( agent, filter( "me", null, "me", null, false, null ) ).orElseThrow();
        assertNull( bot.assigneeLogin() );
        assertEquals( 9L, bot.assigneeAgentId() );
        assertEquals( 9L, bot.reporterAgentId() );
    }

    @Test
    void aNamedPersonIsFoundByDisplayNameAndAnUnknownOneMatchesNothing() {
        assertEquals( "u-7f3a", resolve( member, filter( "Ana Lima", null, null, null, false, null ) ).orElseThrow().assigneeLogin() );
        assertTrue( resolve( member, filter( "Nobody", null, null, null, false, null ) ).isEmpty() );
        assertTrue( resolve( member, filter( null, null, "Nobody", null, false, null ) ).isEmpty() );
        assertTrue( resolve( member, filter( "u-7f3a", null, null, null, false, null ) ).isEmpty(), "a member cannot name a login" );
        assertEquals( "u-7f3a", resolve( admin, filter( "u-7f3a", null, null, null, false, null ) ).orElseThrow().assigneeLogin() );
    }

    @Test
    void aProductIsFoundByItsKeyAndAnUnknownKeyMatchesNothing() {
        assertEquals( 7L, resolve( member, filter( null, null, null, "web", false, null ) ).orElseThrow().productId() );
        assertEquals( 7L, resolve( member, filter( null, null, null, " web ", false, null ) ).orElseThrow().productId() );
        assertTrue( resolve( member, filter( null, null, null, "mobile", false, null ) ).isEmpty() );
        assertTrue( resolve( member, filter( null, null, null, "WEB", false, null ) ).isEmpty(), "keys are case-sensitive" );
    }

    @Test
    void watchingNeedsAPersonAndAnAgentMatchesNothing() {
        assertEquals( "u-c01d", resolve( member, filter( null, null, null, null, true, null ) ).orElseThrow().watcherLogin() );
        assertTrue( resolve( agent, filter( null, null, null, null, true, null ) ).isEmpty() );
    }

    @Test
    void aProjectKeyNamesAVisibleProjectInAnyCaseAndAnotherKeyMatchesNothing() {
        final FindFilter ops = new FindFilter( Set.of( " ops " ), null, null, null, null, null, null, null, null, null,
            null, false, null, null, null, FindFilter.Sort.UPDATED, 0, null );
        assertEquals( Set.of( 2L ), resolve( member, ops ).orElseThrow().projectIds() );
        final FindFilter hidden = new FindFilter( Set.of( "SECRET" ), null, null, null, null, null, null, null, null, null,
            null, false, null, null, null, FindFilter.Sort.UPDATED, 0, null );
        assertTrue( resolve( member, hidden ).isEmpty() );
        assertTrue( FindSpecs.resolve( member, filter( null, null, null, null, false, null ), List.of(), products, people ).isEmpty() );
    }
}
