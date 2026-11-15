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
package com.wikantik.its.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /api/work/*} surface end to end: a project lifecycle for a member, and the visibility rule that a
 * caller who is not a member of a project can neither see it nor tell it from one that does not exist.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class WorkApiIT {

    private static Caller admin;
    private static Caller member;
    private static Caller outsider;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        member = WorkIt.account( admin, "wapimember", "Wapi Member Name" );
        outsider = WorkIt.account( admin, "wapioutsider", "Wapi Outsider Name" );
        created( admin.send( "POST", "/api/work/projects", Map.of( "key", "PLAT", "name", "Platform", "description", "d" ) ) );
        created( admin.send( "POST", "/api/work/products", Map.of( "key", "wikantik", "name", "Wikantik" ) ) );
        noContent( admin.send( "PUT", "/api/work/projects/PLAT/members", Map.of( "principal", "wapimember",
            "principalKind", "user", "role", "member" ) ) );
    }

    private static JsonObject created( final HttpResponse< String > r ) {
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject();
    }

    private static JsonObject ok( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject();
    }

    private static void noContent( final HttpResponse< String > r ) {
        assertEquals( 204, r.statusCode(), r.body() );
    }

    private static String key( final JsonObject itemView ) {
        return itemView.getAsJsonObject( "item" ).get( "key" ).getAsString();
    }

    private static int version( final JsonObject itemView ) {
        return itemView.getAsJsonObject( "item" ).get( "version" ).getAsInt();
    }

    @Test
    @Order( 1 )
    void memberRunsAnItemThroughItsLife() throws Exception {
        final JsonObject epic = created( member.send( "POST", "/api/work/items", Map.of( "project", "PLAT",
            "level", "epic", "title", "Epic one" ) ) );
        final JsonObject story = created( member.send( "POST", "/api/work/items", Map.of( "project", "PLAT",
            "level", "story", "title", "Story one", "parentKey", key( epic ) ) ) );
        final JsonObject task = created( member.send( "POST", "/api/work/items", Map.of( "project", "PLAT",
            "level", "task", "title", "Task one", "parentKey", key( story ), "productKey", "wikantik",
            "assignee", "wapimember" ) ) );
        assertEquals( "PLAT", task.get( "projectKey" ).getAsString() );
        assertEquals( key( story ), task.get( "parentKey" ).getAsString() );
        assertEquals( "wikantik", task.get( "productKey" ).getAsString() );
        assertEquals( "Wapi Member Name", task.getAsJsonObject( "item" ).get( "assigneeName" ).getAsString() );
        assertEquals( "wapimember", task.getAsJsonObject( "item" ).get( "assignee" ).getAsString() );

        // the task moves through every status
        int v = version( task );
        final List< String > path = List.of( "refining", "ready", "in_progress", "review", "done", "cancelled" );
        for ( final String status : path ) {
            final JsonObject moved = ok( member.send( "PATCH", "/api/work/items/" + key( task ), Map.of(
                "expectedVersion", v, "changes", Map.of( "status", status ) ) ) );
            assertEquals( status, moved.getAsJsonObject( "item" ).get( "status" ).getAsString() );
            assertEquals( v + 1, version( moved ) );
            v = version( moved );
        }

        // the story is edited twice
        int sv = version( story );
        for ( final String title : List.of( "Story two", "Story three" ) ) {
            final JsonObject edited = ok( member.send( "PATCH", "/api/work/items/" + key( story ), Map.of(
                "expectedVersion", sv, "changes", Map.of( "title", title, "body", "Body of " + title ) ) ) );
            sv = version( edited );
        }
        final JsonArray revisions = json( member.get( "/api/work/items/" + key( story ) + "/revisions" ) ).getAsJsonArray();
        assertEquals( 3, revisions.size(), "creation plus two edits: " + revisions );
        assertEquals( "Story one", revisions.get( 0 ).getAsJsonObject().get( "title" ).getAsString() );
        assertEquals( "Story three", revisions.get( 2 ).getAsJsonObject().get( "title" ).getAsString() );
        assertEquals( "Wapi Member Name", revisions.get( 2 ).getAsJsonObject().get( "editedByName" ).getAsString() );

        // a comment and a page link
        final JsonObject comment = created( member.send( "POST", "/api/work/items/" + key( story ) + "/comments",
            Map.of( "body", "Looks good" ) ) );
        assertEquals( "Wapi Member Name", comment.get( "authorName" ).getAsString() );
        final JsonObject link = created( member.send( "POST", "/api/work/items/" + key( story ) + "/links",
            Map.of( "kind", "page", "target", "Main" ) ) );
        assertEquals( "page", link.get( "kind" ).getAsString() );
        final JsonObject fetched = ok( member.get( "/api/work/items/" + key( story ) ) );
        assertEquals( 1, fetched.getAsJsonArray( "comments" ).size() );
        assertEquals( 1, fetched.getAsJsonArray( "links" ).size() );

        // events of the task, in order: created, then one status event per move
        final JsonArray events = json( member.get( "/api/work/items/" + key( task ) + "/events" ) ).getAsJsonArray();
        final List< String > types = new ArrayList<>();
        final List< String > statuses = new ArrayList<>();
        for ( final JsonElement e : events ) {
            types.add( e.getAsJsonObject().get( "type" ).getAsString() );
            if ( "status".equals( e.getAsJsonObject().get( "type" ).getAsString() ) ) {
                statuses.add( e.getAsJsonObject().get( "toValue" ).getAsString() );
            }
        }
        assertEquals( "created", types.get( 0 ), types.toString() );
        assertEquals( path, statuses );
        assertEquals( "Wapi Member Name", events.get( 0 ).getAsJsonObject().get( "actorName" ).getAsString() );

        // the board has one column per status, with the cancelled task in its column
        final JsonArray board = json( member.get( "/api/work/projects/PLAT/board" ) ).getAsJsonArray();
        assertEquals( 7, board.size() );
        for ( final JsonElement c : board ) {
            final JsonObject col = c.getAsJsonObject();
            if ( "cancelled".equals( col.get( "status" ).getAsString() ) ) {
                assertEquals( 1, col.get( "total" ).getAsInt() );
                assertEquals( key( task ), col.getAsJsonArray( "items" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
            }
        }
        // the project activity shows the same story
        final JsonArray activity = json( member.get( "/api/work/activity?project=PLAT&limit=500" ) ).getAsJsonArray();
        assertTrue( activity.size() >= 10, "activity: " + activity );
        // me: the cancelled task is no longer waiting on anyone; the project is listed with the member's role
        final JsonObject me = ok( member.get( "/api/work/me" ) );
        assertEquals( "member", me.getAsJsonArray( "projects" ).get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
        // items list filters
        final JsonArray tasks = json( member.get( "/api/work/items?project=PLAT&level=task" ) ).getAsJsonArray();
        assertEquals( 1, tasks.size() );
        final JsonArray members = json( member.get( "/api/work/projects/PLAT/members" ) ).getAsJsonArray();
        assertEquals( "Wapi Member Name", members.get( 0 ).getAsJsonObject().get( "displayName" ).getAsString() );
    }

    @Test
    @Order( 2 )
    void staleVersionIs409() throws Exception {
        final JsonObject item = created( member.send( "POST", "/api/work/items", Map.of( "project", "PLAT",
            "level", "story", "title", "Racy" ) ) );
        final int v = version( item );
        ok( member.send( "PATCH", "/api/work/items/" + key( item ), Map.of( "expectedVersion", v,
            "changes", Map.of( "title", "Racy two" ) ) ) );
        final HttpResponse< String > stale = member.send( "PATCH", "/api/work/items/" + key( item ), Map.of(
            "expectedVersion", v, "changes", Map.of( "title", "Racy three" ) ) );
        assertEquals( 409, stale.statusCode(), stale.body() );
        final JsonObject current = json( stale ).getAsJsonObject().getAsJsonObject( "current" );
        assertEquals( v + 1, current.getAsJsonObject( "item" ).get( "version" ).getAsInt() );
        assertEquals( "Racy two", current.getAsJsonObject( "item" ).get( "title" ).getAsString() );
    }

    /** One request of the sweep: the real, populated target and the same request against one that does not exist. */
    private record Probe( String method, String real, String ghost, Object body, Object ghostBody, int expected ) {
        Probe( final String method, final String real, final String ghost, final Object body, final int expected ) {
            this( method, real, ghost, body, body, expected );
        }
    }

    @Test
    @Order( 3 )
    void nonMemberGets404Everywhere() throws Exception {
        // the real numeric ids of a comment and a link in PLAT, found as the member
        final JsonObject story = ok( member.get( "/api/work/items/PLAT-2" ) );
        final long commentId = story.getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject().get( "id" ).getAsLong();
        final long linkId = story.getAsJsonArray( "links" ).get( 0 ).getAsJsonObject().get( "id" ).getAsLong();
        final long ghostId = 987654321L;
        final Map< String, Object > member1 = Map.of( "principal", "wapimember", "principalKind", "user", "role", "member" );
        final Map< String, Object > member2 = Map.of( "principal", "wapimember", "principalKind", "user" );
        final Map< String, Object > patchBody = Map.of( "expectedVersion", 1, "changes", Map.of( "title", "x" ) );
        final Map< String, Object > newItem = Map.of( "level", "epic", "title", "x" );

        // every route of the endpoint table, every method; project/product creation and the product writes are
        // admin-only by design, so for a non-member they are 403 whether or not the target exists
        final List< Probe > probes = List.of(
            new Probe( "POST", "/api/work/projects", "/api/work/projects", Map.of( "key", "PLAT", "name", "x" ), 403 ),
            new Probe( "GET", "/api/work/projects/PLAT", "/api/work/projects/GHOST", null, 404 ),
            new Probe( "PATCH", "/api/work/projects/PLAT", "/api/work/projects/GHOST", Map.of( "name", "x" ), 404 ),
            new Probe( "POST", "/api/work/projects/PLAT/archive", "/api/work/projects/GHOST/archive", null, 404 ),
            new Probe( "GET", "/api/work/projects/PLAT/members", "/api/work/projects/GHOST/members", null, 404 ),
            new Probe( "PUT", "/api/work/projects/PLAT/members", "/api/work/projects/GHOST/members", member1, 404 ),
            new Probe( "DELETE", "/api/work/projects/PLAT/members", "/api/work/projects/GHOST/members", member2, 404 ),
            new Probe( "GET", "/api/work/projects/PLAT/board", "/api/work/projects/GHOST/board", null, 404 ),
            new Probe( "POST", "/api/work/products", "/api/work/products", Map.of( "key", "wikantik", "name", "x" ), 403 ),
            new Probe( "PATCH", "/api/work/products/wikantik", "/api/work/products/ghostprod", Map.of( "name", "x" ), 404 ),
            new Probe( "POST", "/api/work/products/wikantik/archive", "/api/work/products/ghostprod/archive", null, 403 ),
            new Probe( "GET", "/api/work/items?project=PLAT", "/api/work/items?project=GHOST", null, 404 ),
            new Probe( "POST", "/api/work/items", "/api/work/items", withProject( newItem, "PLAT" ),
                withProject( newItem, "GHOST" ), 404 ),
            new Probe( "GET", "/api/work/items/PLAT-1", "/api/work/items/GHOST-1", null, 404 ),
            new Probe( "PATCH", "/api/work/items/PLAT-1", "/api/work/items/GHOST-1", patchBody, 404 ),
            new Probe( "GET", "/api/work/items/PLAT-1/revisions", "/api/work/items/GHOST-1/revisions", null, 404 ),
            new Probe( "GET", "/api/work/items/PLAT-1/events", "/api/work/items/GHOST-1/events", null, 404 ),
            new Probe( "POST", "/api/work/items/PLAT-1/comments", "/api/work/items/GHOST-1/comments", Map.of( "body", "x" ), 404 ),
            new Probe( "PATCH", "/api/work/comments/" + commentId, "/api/work/comments/" + ghostId, Map.of( "body", "x" ), 404 ),
            new Probe( "DELETE", "/api/work/comments/" + commentId, "/api/work/comments/" + ghostId, null, 404 ),
            new Probe( "POST", "/api/work/items/PLAT-1/links", "/api/work/items/GHOST-1/links",
                Map.of( "kind", "page", "target", "Main" ), 404 ),
            new Probe( "DELETE", "/api/work/links/" + linkId, "/api/work/links/" + ghostId, null, 404 ),
            new Probe( "GET", "/api/work/activity?project=PLAT", "/api/work/activity?project=GHOST", null, 404 ) );

        for ( final Probe p : probes ) {
            final HttpResponse< String > real = outsider.send( p.method, p.real, p.body );
            final HttpResponse< String > ghost = outsider.send( p.method, p.ghost, p.ghostBody );
            final String label = p.method + " " + p.real;
            assertEquals( p.expected, real.statusCode(), label + ": " + real.body() );
            assertEquals( ghost.statusCode(), real.statusCode(), label + " status differs from the ghost target" );
            assertEquals( ghost.body(), real.body(), label + " body differs from the ghost target" );
            assertFalse( real.body().contains( "PLAT" ) || real.body().contains( "wikantik" ), label + " echoes a key: " + real.body() );
            if ( "GET".equals( p.method ) ) {
                // the "real" target really is there: the member reads it
                assertEquals( 200, member.get( p.real ).statusCode(), label + " (as the member): the real target must exist" );
            }
        }
        // the lists carry no target; an outsider's are empty
        assertEquals( "[]", WorkIt.GSON.toJson( json( outsider.get( "/api/work/projects" ) ) ) );
        assertEquals( "[]", WorkIt.GSON.toJson( json( outsider.get( "/api/work/products" ) ) ) );
        final JsonObject me = ok( outsider.get( "/api/work/me" ) );
        assertEquals( 0, me.getAsJsonArray( "waitingOn" ).size() );
        assertEquals( 0, me.getAsJsonArray( "projects" ).size() );
        // and an anonymous caller is only asked to sign in
        final Caller anonymous = new Caller();
        assertEquals( 401, anonymous.get( "/api/work/projects" ).statusCode() );
        assertEquals( 401, anonymous.get( "/api/work/items/PLAT-1" ).statusCode() );
        // nothing the sweep did changed the project
        assertEquals( "Platform", ok( member.get( "/api/work/projects/PLAT" ) ).get( "name" ).getAsString() );
        assertEquals( 1, ok( member.get( "/api/work/items/PLAT-2" ) ).getAsJsonArray( "comments" ).size() );
    }

    private static Map< String, Object > withProject( final Map< String, Object > fields, final String project ) {
        final Map< String, Object > m = new java.util.LinkedHashMap<>( fields );
        m.put( "project", project );
        return m;
    }

    @Test
    @Order( 4 )
    void removedMemberLosesAccessOnNextRequest() throws Exception {
        final Caller carol = WorkIt.account( admin, "wapicarol", "Wapi Carol Name" );
        created( admin.send( "POST", "/api/work/projects", Map.of( "key", "GONE", "name", "Gone soon" ) ) );
        noContent( admin.send( "PUT", "/api/work/projects/GONE/members", Map.of( "principal", "wapicarol",
            "principalKind", "user", "role", "member" ) ) );
        final JsonObject item = created( carol.send( "POST", "/api/work/items", Map.of( "project", "GONE",
            "level", "epic", "title", "Mine" ) ) );
        assertEquals( 200, carol.get( "/api/work/items/" + key( item ) ).statusCode() );

        noContent( admin.send( "DELETE", "/api/work/projects/GONE/members", Map.of( "principal", "wapicarol",
            "principalKind", "user" ) ) );

        assertEquals( 404, carol.get( "/api/work/items/" + key( item ) ).statusCode() );
        assertEquals( 404, carol.get( "/api/work/projects/GONE" ).statusCode() );
        assertEquals( "[]", WorkIt.GSON.toJson( json( carol.get( "/api/work/projects" ) ) ) );
    }

    @Test
    @Order( 5 )
    void aBlockerInAProjectTheCallerCannotSeeLooksLikeAMissingOne() throws Exception {
        created( admin.send( "POST", "/api/work/projects", Map.of( "key", "OTHR", "name", "Other" ) ) );
        final JsonObject hiddenItem = created( admin.send( "POST", "/api/work/items", Map.of( "project", "OTHR",
            "level", "epic", "title", "Hidden" ) ) );
        final JsonObject mine = created( member.send( "POST", "/api/work/items", Map.of( "project", "PLAT",
            "level", "epic", "title", "Wants a blocker" ) ) );

        final HttpResponse< String > foreign = member.send( "POST", "/api/work/items/" + key( mine ) + "/links",
            Map.of( "kind", "blocked_by", "target", key( hiddenItem ) ) );
        final HttpResponse< String > missing = member.send( "POST", "/api/work/items/" + key( mine ) + "/links",
            Map.of( "kind", "blocked_by", "target", "PLAT-99999" ) );
        assertEquals( 400, foreign.statusCode(), foreign.body() );
        assertEquals( 400, missing.statusCode(), missing.body() );
        // same body but for whatever the caller typed: nothing about the other project shows through
        assertEquals( missing.body(), foreign.body() );
        assertFalse( foreign.body().contains( "OTHR" ), foreign.body() );
        assertEquals( "target", json( foreign ).getAsJsonObject().get( "field" ).getAsString() );

        final JsonObject after = ok( member.get( "/api/work/items/" + key( mine ) ) );
        assertFalse( after.get( "blocked" ).getAsBoolean() );
        assertEquals( 0, after.getAsJsonArray( "links" ).size() );
    }
}
