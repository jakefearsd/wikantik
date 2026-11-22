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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.Actor;
import com.wikantik.work.ReportableProject;
import com.wikantik.work.ReportedComment;
import com.wikantik.work.ReportedEvent;
import com.wikantik.work.ReportedItem;
import com.wikantik.work.ReportedItemView;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intake JSON is an explicit allow-list. These tests pin the exact key sets, recursively, of a view whose every string
 * carries a marker, and fail when a record gains a component that the JSON does not name (so a field cannot reach a
 * reporter by being added to a record: someone must edit {@code WorkIntakeJson} and this test).
 */
class WorkIntakeJsonTest {

    private static final Instant T0 = Instant.parse( "2026-11-01T10:00:00Z" );

    private static final Set< String > VIEW_KEYS = Set.of( "key", "projectKey", "projectName", "level", "type", "status",
        "title", "body", "bodyHtml", "reporter", "reporterName", "createdAt", "updatedAt", "comments", "events", "archived" );
    private static final Set< String > COMMENT_KEYS = Set.of( "id", "author", "authorName", "authorKind", "body", "bodyHtml",
        "mentions", "createdAt", "editedAt", "removedAt", "mine" );
    private static final Set< String > EVENT_KEYS = Set.of( "id", "type", "at", "actor", "actorName", "actorKind",
        "fromValue", "toValue" );

    private static ReportedItemView marked() {
        return new ReportedItemView( "M_key", "M_projectKey", "M_projectName", WorkLevel.TASK, WorkType.CHORE, WorkStatus.REVIEW,
            "M_title", "M_body", "M_bodyHtml", "M_reporter", T0, T0.plusSeconds( 1 ),
            List.of( new ReportedComment( 77, "M_author", Actor.Kind.AGENT, "M_cbody", "M_cbodyHtml",
                List.of( "M_mention" ), T0, T0.plusSeconds( 2 ), T0.plusSeconds( 3 ) ) ),
            List.of( new ReportedEvent( 88, "M_type", T0, "M_actor", Actor.Kind.SYSTEM, "M_from", "M_to" ) ), true );
    }

    @Test
    void theKeysAreExactlyTheAllowedSetAtEveryDepth() {
        final JsonObject o = WorkIntakeJson.view( marked(), "M_viewer" );
        assertEquals( VIEW_KEYS, o.keySet() );
        assertEquals( COMMENT_KEYS, o.getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject().keySet() );
        assertEquals( EVENT_KEYS, o.getAsJsonArray( "events" ).get( 0 ).getAsJsonObject().keySet() );
        assertEquals( VIEW_KEYS, allKeysAtDepthZeroOnly( o ), "no nested object beyond comments and events" );
    }

    /** The union of keys of objects reachable other than through comments and events must add nothing. */
    private static Set< String > allKeysAtDepthZeroOnly( final JsonObject o ) {
        final Set< String > keys = new TreeSet<>( o.keySet() );
        for ( final var e : o.entrySet() ) {
            if ( e.getValue().isJsonObject() ) {
                keys.addAll( e.getValue().getAsJsonObject().keySet() );
            }
        }
        return keys;
    }

    @Test
    void everyMarkerIsWrittenAndNothingBesides() {
        final String text = WorkIntakeJson.view( marked(), "M_viewer" ).toString();
        for ( final String marker : List.of( "M_key", "M_projectKey", "M_projectName", "M_title", "M_body", "M_bodyHtml",
            "M_reporter", "M_author", "M_cbody", "M_cbodyHtml", "M_mention", "M_type", "M_actor", "M_from", "M_to" ) ) {
            assertTrue( text.contains( marker ), marker + " is an allowed field" );
        }
        assertFalse( text.contains( "M_viewer" ), "the viewer's name only decides mine" );
        for ( final String key : List.of( "login", "agentId", "itemId", "projectId", "note", "authorAgentId", "assignee",
            "queue", "parent", "labels", "links", "priority", "sprint", "estimate", "version", "viewerRole" ) ) {
            assertFalse( text.contains( "\"" + key + "\"" ), "no " + key + " key" );
        }
    }

    @Test
    void everyRecordComponentIsNamedOnTheWire() {
        assertCovered( ReportedItemView.class, VIEW_KEYS );
        assertCovered( ReportedComment.class, COMMENT_KEYS );
        assertCovered( ReportedEvent.class, EVENT_KEYS );
        assertCovered( ReportedItem.class, Set.of( "key", "projectKey", "projectName", "level", "type", "status", "title",
            "createdAt", "updatedAt", "commentCount" ) );
        assertCovered( ReportableProject.class, Set.of( "key", "name", "role" ) );
    }

    private static void assertCovered( final Class< ? > record, final Set< String > wire ) {
        final Set< String > components = new HashSet<>();
        for ( final RecordComponent c : record.getRecordComponents() ) {
            components.add( c.getName() );
        }
        assertTrue( wire.containsAll( components ), record.getSimpleName() + " has a component the JSON does not name: "
            + components.stream().filter( c -> !wire.contains( c ) ).toList() );
    }

    @Test
    void theListRowsAndProjectsCarryTheirOwnKeysOnly() {
        final JsonObject row = WorkIntakeJson.listed( new ReportedItem( "M_key", "M_pk", "M_pn", WorkLevel.STORY,
            WorkType.BUG, WorkStatus.DONE, "M_title", T0, T0, 2 ) );
        assertEquals( Set.of( "key", "projectKey", "projectName", "level", "type", "status", "title", "createdAt",
            "updatedAt", "commentCount" ), row.keySet() );
        final JsonArray projects = WorkIntakeJson.reportable( List.of( new ReportableProject( "K", "N", WorkRole.VIEWER ) ) );
        assertEquals( Set.of( "key", "name", "role" ), projects.get( 0 ).getAsJsonObject().keySet() );
    }

    /** A lapsed claim reaches a reporter as a status event with no actor: written as explicit nulls, never thrown on. */
    @Test
    void aStatusEventWithNoActorSerialisesWithTheActorFieldsAsExplicitNull() {
        final ReportedItemView v = new ReportedItemView( "K-1", "K", "Kay", WorkLevel.STORY, WorkType.FEATURE,
            WorkStatus.READY, "T", null, null, "R", T0, T0, List.of(),
            List.of( new ReportedEvent( 5, "status", T0, null, null, "in_progress", "ready" ) ), false );
        final JsonObject e = WorkIntakeJson.view( v, "R" ).getAsJsonArray( "events" ).get( 0 ).getAsJsonObject();
        assertEquals( EVENT_KEYS, e.keySet() );
        for ( final String field : List.of( "actor", "actorName", "actorKind" ) ) {
            assertTrue( e.has( field ) && e.get( field ).isJsonNull(), field + " is present as null: " + e );
        }
        assertEquals( "ready", e.get( "toValue" ).getAsString() );
    }

    @Test
    void nullsAreWrittenExplicitlyOnTheFieldsThatCanBeNull() {
        final ReportedItemView v = new ReportedItemView( "K-1", "K", "Kay", WorkLevel.STORY, WorkType.FEATURE,
            WorkStatus.DRAFT, "T", null, null, "R", T0, T0, List.of(), List.of(), false );
        final JsonObject o = WorkIntakeJson.view( v, null );
        for ( final String field : List.of( "body", "bodyHtml" ) ) {
            final JsonElement e = o.get( field );
            assertTrue( e != null && e.isJsonNull(), field + " is a JSON null, not missing" );
        }
        final JsonObject c = WorkIntakeJson.comment( new ReportedComment( 1, "A", Actor.Kind.HUMAN, "b", "<p>b</p>", List.of(),
            T0, null, null ), null );
        assertTrue( c.get( "editedAt" ).isJsonNull() );
        assertTrue( c.get( "removedAt" ).isJsonNull() );
        assertEquals( false, c.get( "mine" ).getAsBoolean() );
        assertEquals( 0, c.getAsJsonArray( "mentions" ).size() );
    }
}
