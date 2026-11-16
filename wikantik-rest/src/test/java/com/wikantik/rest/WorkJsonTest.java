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
package com.wikantik.rest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.Actor;
import com.wikantik.work.CommentView;
import com.wikantik.work.Item;
import com.wikantik.work.Member;
import com.wikantik.work.WorkEvent;
import com.wikantik.work.WorkRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkJsonTest {

    private static final Instant AT = Instant.parse( "2026-11-15T10:00:00Z" );
    private static final WorkViewer MEMBER = new WorkViewer( "dana", false );
    private static final WorkViewer ADMIN = new WorkViewer( "root", true );
    private static final Function< String, String > PEOPLE = login -> "Name of " + login;
    private static final WorkLookup LOOKUP = new WorkLookup(
        id -> Map.of( 5L, "builder", 6L, "reviewer" ).get( id ),
        key -> Map.of( "triage", "Triage queue" ).get( key ) );

    private static JsonObject enriched( final Object value, final WorkViewer viewer ) {
        return WorkJson.enrich( WorkJson.GSON.toJsonTree( value ), PEOPLE, viewer, LOOKUP ).getAsJsonObject();
    }

    private static WorkEvent event( final String actor, final Actor.Kind kind, final Long agentId, final String type,
                                    final String from, final String to, final String note ) {
        return new WorkEvent( 1, 1, 7L, "PLAT-1", AT, actor, kind, agentId, type, from, to, note );
    }

    private static String text( final JsonObject o, final String key ) {
        return o.get( key ).getAsString();
    }

    @Test
    void agentEventShowsAgentName() {
        // the event stored the name at write time ("builder-old"); the agent has since been renamed to "builder"
        final JsonObject o = enriched( event( "builder-old", Actor.Kind.AGENT, 5L, "claimed", "ready", "in_progress",
            "2026-11-15T10:30:00Z" ), MEMBER );
        assertEquals( "builder", text( o, "actorName" ), "current name, resolved by id" );
        assertEquals( "agent", text( o, "actorKind" ) );
        assertEquals( "builder", text( o, "actor" ), "a member sees no stored text either" );
    }

    @Test
    void agentEventFallsBackToStoredNameWhenTheAgentCannotBeResolved() {
        final JsonObject o = enriched( event( "ghost", Actor.Kind.AGENT, 99L, "claimed", "ready", "in_progress", null ),
            MEMBER );
        assertEquals( "ghost", text( o, "actorName" ) );
    }

    @Test
    void humanEventStillShowsDisplayNameAndKind() {
        final JsonObject o = enriched( event( "carol", Actor.Kind.HUMAN, null, "status", "ready", "review", null ),
            MEMBER );
        assertEquals( "Name of carol", text( o, "actorName" ) );
        assertEquals( "human", text( o, "actorKind" ) );
        assertEquals( "Name of carol", text( o, "actor" ) );
        assertEquals( "carol", text( enriched( event( "carol", Actor.Kind.HUMAN, null, "status", "a", "b", null ), ADMIN ),
            "actor" ) );
    }

    @Test
    void anAgentNameIsNeverLookedUpAsAPerson() {
        final Function< String, String > forbidden = login -> {
            throw new AssertionError( "an agent name must not go through the people directory: " + login );
        };
        WorkJson.enrich( WorkJson.GSON.toJsonTree( event( "builder", Actor.Kind.AGENT, 5L, "claimed", "ready", "x", null ) ),
            forbidden, MEMBER, LOOKUP );
    }

    @Test
    void queueValueShowsQueueName() {
        final JsonObject assigned = enriched( event( "carol", Actor.Kind.HUMAN, null, "assigned", "erin", "queue:triage",
            null ), MEMBER );
        assertEquals( "Triage queue", text( assigned, "toValueName" ) );
        assertEquals( "queue:triage", text( assigned, "toValue" ), "a queue is not confidential, the key stays" );
        assertEquals( "Name of erin", text( assigned, "fromValueName" ) );
        assertEquals( "Name of erin", text( assigned, "fromValue" ) );
        final JsonObject handoff = enriched( event( "carol", Actor.Kind.HUMAN, null, "handoff", "ready|erin",
            "review|queue:triage", "over to you" ), MEMBER );
        assertEquals( "Triage queue", text( handoff, "toValueName" ) );
        assertEquals( "review|queue:triage", text( handoff, "toValue" ) );
        assertEquals( "ready|Name of erin", text( handoff, "fromValue" ) );
    }

    @Test
    void anUnknownQueueFallsBackToItsKey() {
        final JsonObject o = enriched( event( "carol", Actor.Kind.HUMAN, null, "assigned", null, "queue:gone", null ),
            MEMBER );
        assertEquals( "gone", text( o, "toValueName" ) );
    }

    @Test
    void releasedEventNamesTheHolder() {
        final JsonObject o = enriched( event( "carol", Actor.Kind.HUMAN, null, "released", "agent:5", "ready", "moved" ),
            MEMBER );
        assertEquals( "builder", text( o, "fromValueName" ) );
        assertEquals( "agent:5", text( o, "fromValue" ) );
        assertFalse( o.has( "toValueName" ), "the resulting status is not a name" );
    }

    @Test
    void itemCarriesClaimQueueAndReporterNames() {
        final Item base = WorkHttp.item( "PLAT-1", "erin", 3 );
        final Item item = new Item( base.id(), base.projectId(), base.key(), base.level(), null, null, base.kind(),
            base.title(), base.body(), base.status(), 3, 1.0, null, null, "reviewer", null, null, null, 3, AT, AT, null,
            "triage", 5L, AT, AT.plusSeconds( 1800 ), 6L );
        final JsonObject o = enriched( item, MEMBER );
        assertEquals( "builder", text( o, "claimedByName" ) );
        assertEquals( "Triage queue", text( o, "queueName" ) );
        assertEquals( "reviewer", text( o, "reporterName" ) );
        assertEquals( "agent", text( o, "reporterKind" ) );
        assertEquals( "reviewer", text( o, "reporter" ) );
    }

    @Test
    void anUnclaimedItemHasNoClaimName() {
        final JsonObject o = enriched( WorkHttp.item( "PLAT-1", "erin", 3 ), MEMBER );
        assertFalse( o.has( "claimedByName" ) );
        assertFalse( o.has( "queueName" ) );
        assertEquals( "human", text( o, "reporterKind" ) );
    }

    @Test
    void agentMemberShowsAgentName() {
        final JsonObject o = enriched( new Member( 1, "5", "agent", WorkRole.MEMBER ), MEMBER );
        assertEquals( "builder", text( o, "displayName" ) );
        assertEquals( "builder", text( o, "principal" ) );
        assertFalse( o.get( "self" ).getAsBoolean() );
        final JsonObject asAgent = enriched( new Member( 1, "5", "agent", WorkRole.MEMBER ),
            new WorkViewer( "builder", false, 5L ) );
        assertTrue( asAgent.get( "self" ).getAsBoolean() );
        assertEquals( "builder", text( enriched( new Member( 1, "5", "agent", WorkRole.MEMBER ), ADMIN ), "principal" ),
            "even an admin sees the name, not the id" );
    }

    @Test
    void aPersonNamedLikeAnAgentIsNotTheAgent() {
        // an agent viewer called "alice" must not see a person called alice as itself
        final JsonObject o = enriched( new Member( 1, "alice", "user", WorkRole.MEMBER ),
            new WorkViewer( "alice", false, 5L ) );
        assertFalse( o.get( "self" ).getAsBoolean() );
    }

    @Test
    void agentMemberEventShowsAgentName() {
        final JsonObject o = enriched( event( "root", Actor.Kind.HUMAN, null, "member", null, "agent:5:member", null ),
            MEMBER );
        assertEquals( "builder", text( o, "toValueName" ) );
    }

    @Test
    void commentByAgentIsMineOnlyForThatAgent() {
        final CommentView c = new CommentView( 1, 7, "builder", "text", AT, null, null, "<p>text</p>", List.of(), 5L );
        assertFalse( enriched( c, MEMBER ).get( "mine" ).getAsBoolean() );
        assertFalse( enriched( c, new WorkViewer( "builder", false, 6L ) ).get( "mine" ).getAsBoolean(),
            "same name, different agent id" );
        assertFalse( enriched( c, new WorkViewer( "builder", false ) ).get( "mine" ).getAsBoolean(),
            "a person whose login equals the stored name" );
        assertTrue( enriched( c, new WorkViewer( "builder", false, 5L ) ).get( "mine" ).getAsBoolean() );
        assertEquals( "agent", text( enriched( c, MEMBER ), "authorKind" ) );
        assertEquals( "builder", text( enriched( c, MEMBER ), "authorName" ) );
    }

    @Test
    void noLoginForNonAdminInAnyAgentField() {
        final Item base = WorkHttp.item( "PLAT-1", "bob.login", 3 );
        final Item item = new Item( base.id(), base.projectId(), base.key(), base.level(), null, null, base.kind(),
            base.title(), base.body(), base.status(), 3, 1.0, null, "bob.login", "carol.login", null, null, null, 3, AT, AT,
            null, "triage", 5L, AT, AT.plusSeconds( 60 ), null );
        final List< Object > everything = List.of( item,
            event( "carol.login", Actor.Kind.HUMAN, null, "released", "agent:5", "ready", "reassigned" ),
            event( "bob.login", Actor.Kind.HUMAN, null, "handoff", "ready|carol.login", "review|bob.login", "n" ),
            event( "builder", Actor.Kind.AGENT, 5L, "claim_expired", "in_progress", "ready", null ),
            new CommentView( 2, 7, "carol.login", "t", AT, null, null, "<p>t</p>", List.of(), null ),
            new Member( 1, "bob.login", "user", WorkRole.MEMBER ),
            new Member( 1, "5", "agent", WorkRole.MEMBER ) );
        final Function< String, String > shownAs = login -> "P-" + login.substring( 0, login.indexOf( '.' ) );
        final JsonElement member = WorkJson.enrich( WorkJson.GSON.toJsonTree( everything ), shownAs, MEMBER, LOOKUP );
        final String shown = WorkJson.GSON.toJson( member );
        assertFalse( shown.contains( "bob.login" ), shown );
        assertFalse( shown.contains( "carol.login" ), shown );
        final String admin = WorkJson.GSON.toJson(
            WorkJson.enrich( WorkJson.GSON.toJsonTree( everything ), shownAs, ADMIN, LOOKUP ) );
        assertTrue( admin.contains( "bob.login" ) );
    }

    /** The events a UI prints a sentence from, one per claim-related cause. */
    private static List< WorkEvent > claimEvents() {
        final String expires = "2026-11-15T10:30:00Z";
        return List.of(
            event( "builder", Actor.Kind.AGENT, 5L, "claimed", "ready", "in_progress", expires ),
            event( "builder", Actor.Kind.AGENT, 5L, "claim_renewed", null, expires, null ),
            event( "builder", Actor.Kind.AGENT, 5L, "released", "agent:5", "ready", "released" ),
            event( "builder", Actor.Kind.AGENT, 5L, "released", "agent:5", "ready", "blocked on infra" ),
            event( "carol", Actor.Kind.HUMAN, null, "released", "agent:5", "review", "moved" ),
            event( "carol", Actor.Kind.HUMAN, null, "released", "agent:5", "ready", "reassigned" ),
            event( "root", Actor.Kind.HUMAN, null, "released", "agent:5", "ready", "access removed" ),
            event( "root", Actor.Kind.HUMAN, null, "released", "agent:5", "ready", "agent retired" ),
            event( "builder", Actor.Kind.AGENT, 5L, "claim_expired", "in_progress", "ready", null ),
            event( "builder", Actor.Kind.AGENT, 5L, "handoff", "in_progress|", "review|queue:triage", "ready for a second look" ),
            event( "builder", Actor.Kind.AGENT, 5L, "handoff", "in_progress|queue:triage", "review|erin", "over to you, Erin" ),
            event( "carol", Actor.Kind.HUMAN, null, "assigned", "queue:triage", "erin", null ),
            event( "carol", Actor.Kind.HUMAN, null, "assigned", "erin", "queue:triage", "back to the pool" ) );
    }

    @Test
    void everyClaimEventHasWhoWhichClaimFromToQueueOrPersonNoteTimeAndItem() {
        for ( final WorkEvent e : claimEvents() ) {
            final JsonObject o = enriched( e, MEMBER );
            final String label = e.type() + " " + e.fromValue() + " -> " + e.toValue();
            assertTrue( o.has( "actorName" ) && !o.get( "actorName" ).isJsonNull(), label );
            assertTrue( List.of( "human", "agent" ).contains( text( o, "actorKind" ) ), label );
            assertEquals( "PLAT-1", text( o, "itemKey" ), label );
            assertEquals( "2026-11-15T10:00:00Z", text( o, "at" ), label );
            assertTrue( o.has( "note" ) && o.has( "fromValue" ) && o.has( "toValue" ), label );
            if ( "released".equals( e.type() ) ) {
                assertEquals( "builder", text( o, "fromValueName" ), "which agent's claim: " + label );
            }
            if ( e.toValue() != null && e.toValue().contains( "queue:" ) ) {
                assertEquals( "Triage queue", text( o, "toValueName" ), label );
            }
            if ( e.fromValue() != null && e.fromValue().contains( "queue:" ) ) {
                assertEquals( "Triage queue", text( o, "fromValueName" ), label );
            }
            if ( "handoff".equals( e.type() ) && e.toValue().endsWith( "|erin" ) ) {
                assertEquals( "Name of erin", text( o, "toValueName" ), label );
            }
        }
    }
}
