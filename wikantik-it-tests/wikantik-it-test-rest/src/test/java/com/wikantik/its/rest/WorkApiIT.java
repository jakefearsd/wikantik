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
            "assignee", "Wapi Member Name" ) ) );
        assertEquals( "PLAT", task.get( "projectKey" ).getAsString() );
        assertEquals( key( story ), task.get( "parentKey" ).getAsString() );
        assertEquals( "wikantik", task.get( "productKey" ).getAsString() );
        assertEquals( "Wapi Member Name", task.getAsJsonObject( "item" ).get( "assigneeName" ).getAsString() );
        assertEquals( "Wapi Member Name", task.getAsJsonObject( "item" ).get( "assignee" ).getAsString(),
            "a member names and is shown people by display name" );

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
        assertEquals( "stale", json( stale ).getAsJsonObject().get( "reason" ).getAsString() );
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

    /** Logins that look nothing like the display names, so any occurrence in a response is a leak. */
    private static final String LEAD_LOGIN = "wlkxlead";
    private static final String MEM_LOGIN = "wlkxmem";
    private static final String OTHER_LOGIN = "wlkxother";
    private static final String LEAD_NAME = "Lena Leadwell Name";
    private static final String MEM_NAME = "Milo Memberly Name";
    private static final String OTHER_NAME = "Olga Otherly Name";

    @Test
    @Order( 6 )
    void aNonAdminMemberIsShownNoLoginAnywhere() throws Exception {
        final Caller lead = WorkIt.account( admin, LEAD_LOGIN, LEAD_NAME );
        final Caller mem = WorkIt.account( admin, MEM_LOGIN, MEM_NAME );
        WorkIt.account( admin, OTHER_LOGIN, OTHER_NAME );
        created( admin.send( "POST", "/api/work/projects", Map.of( "key", "WLK", "name", "Login leak" ) ) );
        // the admin may name people by login; the lead (not an admin) by display name only
        noContent( admin.send( "PUT", "/api/work/projects/WLK/members", Map.of( "principal", LEAD_LOGIN,
            "principalKind", "user", "role", "lead" ) ) );
        noContent( lead.send( "PUT", "/api/work/projects/WLK/members", Map.of( "principal", MEM_NAME,
            "principalKind", "user", "role", "viewer" ) ) );
        noContent( lead.send( "PUT", "/api/work/projects/WLK/members", Map.of( "principal", MEM_NAME,
            "principalKind", "user", "role", "member" ) ) );
        noContent( lead.send( "PUT", "/api/work/projects/WLK/members", Map.of( "principal", OTHER_NAME,
            "principalKind", "user", "role", "viewer" ) ) );
        noContent( lead.send( "DELETE", "/api/work/projects/WLK/members", Map.of( "principal", OTHER_NAME,
            "principalKind", "user" ) ) );

        // a non-admin cannot confirm a login: a real one and a made-up one get the same answer
        final HttpResponse< String > realLogin = lead.send( "PUT", "/api/work/projects/WLK/members", Map.of(
            "principal", OTHER_LOGIN, "principalKind", "user", "role", "viewer" ) );
        final HttpResponse< String > fakeLogin = lead.send( "PUT", "/api/work/projects/WLK/members", Map.of(
            "principal", "wlkxnobody", "principalKind", "user", "role", "viewer" ) );
        assertEquals( 400, realLogin.statusCode(), realLogin.body() );
        assertEquals( fakeLogin.statusCode(), realLogin.statusCode() );
        assertEquals( fakeLogin.body().replace( "wlkxnobody", "X" ), realLogin.body().replace( OTHER_LOGIN, "X" ) );

        // an item assigned, handed off, commented with mentions (the lead's token, the admin's bare login)
        final JsonObject item = created( lead.send( "POST", "/api/work/items", Map.of( "project", "WLK",
            "level", "story", "title", "Leak check", "assignee", MEM_NAME ) ) );
        final String k = key( item );
        ok( lead.send( "PATCH", "/api/work/items/" + k, Map.of( "expectedVersion", version( item ),
            "changes", Map.of( "status", "ready", "assignee", LEAD_NAME ), "note", "over to me" ) ) );
        final JsonArray members = json( lead.get( "/api/work/projects/WLK/members" ) ).getAsJsonArray();
        String memMention = null;
        for ( final JsonElement m : members ) {
            if ( MEM_NAME.equals( m.getAsJsonObject().get( "displayName" ).getAsString() )
                    && m.getAsJsonObject().has( "mentionId" ) ) {
                memMention = m.getAsJsonObject().get( "mentionId" ).getAsString();
            }
        }
        final String leadComment = memMention == null ? "ping " + MEM_NAME
            : "ping @[" + MEM_NAME + "](user:" + memMention + ") **please**";
        created( lead.send( "POST", "/api/work/items/" + k + "/comments", Map.of( "body", leadComment ) ) );
        final JsonObject adminComment = created( admin.send( "POST", "/api/work/items/" + k + "/comments",
            Map.of( "body", "cc @" + MEM_LOGIN + " and @" + LEAD_LOGIN ) ) );
        assertFalse( adminComment.get( "body" ).getAsString().contains( MEM_LOGIN ),
            "a mention the admin typed as a login is stored as a token: " + adminComment );

        // everything the member reads
        final List< String > paths = List.of( "/api/work/items/" + k, "/api/work/items/" + k + "/events",
            "/api/work/items/" + k + "/revisions", "/api/work/projects/WLK/board", "/api/work/items?project=WLK",
            "/api/work/projects/WLK/members", "/api/work/activity?project=WLK&limit=200", "/api/work/me",
            "/api/work/projects", "/api/work/projects/WLK" );
        for ( final String path : paths ) {
            final HttpResponse< String > r = mem.get( path );
            assertEquals( 200, r.statusCode(), path + ": " + r.body() );
            for ( final String login : List.of( LEAD_LOGIN, MEM_LOGIN, OTHER_LOGIN ) ) {
                assertFalse( r.body().contains( login ), path + " shows the login " + login + ": " + r.body() );
            }
        }
        final JsonObject seen = ok( mem.get( "/api/work/items/" + k ) );
        assertEquals( LEAD_NAME, seen.getAsJsonObject( "item" ).get( "assignee" ).getAsString() );
        final JsonArray comments = seen.getAsJsonArray( "comments" );
        assertTrue( comments.get( 0 ).getAsJsonObject().get( "bodyHtml" ).getAsString().contains( "<strong>please</strong>" )
            || memMention == null, "comments are rendered Markdown: " + comments );
        // the page pipeline writes the @ as &#64;; the name is the display name, and the comment lists it to mark
        assertTrue( comments.get( 1 ).getAsJsonObject().get( "bodyHtml" ).getAsString().contains( MEM_NAME ),
            "a mention shows the display name: " + comments );
        assertTrue( comments.get( 1 ).getAsJsonObject().get( "mentions" ).toString().contains( MEM_NAME ),
            comments.toString() );
        assertFalse( comments.get( 0 ).getAsJsonObject().get( "mine" ).getAsBoolean() );

        // positive control: the admin is shown the logins
        final String asAdmin = admin.get( "/api/work/items/" + k ).body();
        assertTrue( asAdmin.contains( LEAD_LOGIN ), asAdmin );
        assertTrue( admin.get( "/api/work/projects/WLK/members" ).body().contains( MEM_LOGIN ) );

        // the membership changes are in the project's activity, by display name, with no item
        final JsonArray activity = json( mem.get( "/api/work/activity?project=WLK&limit=200" ) ).getAsJsonArray();
        int memberEvents = 0;
        for ( final JsonElement e : activity ) {
            final JsonObject ev = e.getAsJsonObject();
            if ( "member".equals( ev.get( "type" ).getAsString() ) ) {
                memberEvents++;
                assertTrue( ev.get( "itemKey" ).isJsonNull(), ev.toString() );
            }
        }
        assertEquals( 5, memberEvents, "lead added; member added, promoted; other added, removed: " + activity );
        assertTrue( activity.toString().contains( "\"toValueName\":\"" + OTHER_NAME + "\"" ), activity.toString() );

        // the item filter narrows the feed to that item
        final JsonArray onlyItem = json( mem.get( "/api/work/activity?project=WLK&item=" + k ) ).getAsJsonArray();
        assertFalse( onlyItem.isEmpty() );
        for ( final JsonElement e : onlyItem ) {
            assertEquals( k, e.getAsJsonObject().get( "itemKey" ).getAsString() );
        }
    }

    @Test
    @Order( 7 )
    void anArchivedProjectIsReadableButRefusesWritesAsArchived() throws Exception {
        created( admin.send( "POST", "/api/work/projects", Map.of( "key", "ARCH", "name", "To be archived" ) ) );
        noContent( admin.send( "PUT", "/api/work/projects/ARCH/members", Map.of( "principal", "wapimember",
            "principalKind", "user", "role", "member" ) ) );
        final JsonObject item = created( member.send( "POST", "/api/work/items", Map.of( "project", "ARCH",
            "level", "epic", "title", "Before the archive" ) ) );
        noContent( admin.send( "POST", "/api/work/projects/ARCH/archive", null ) );

        final JsonObject read = ok( member.get( "/api/work/items/" + key( item ) ) );
        assertTrue( read.get( "archived" ).getAsBoolean() );
        assertTrue( ok( member.get( "/api/work/projects/ARCH" ) ).get( "archived" ).getAsBoolean() );

        final HttpResponse< String > patch = member.send( "PATCH", "/api/work/items/" + key( item ), Map.of(
            "expectedVersion", version( item ), "changes", Map.of( "title", "After" ) ) );
        assertEquals( 409, patch.statusCode(), patch.body() );
        assertEquals( "archived", json( patch ).getAsJsonObject().get( "reason" ).getAsString() );
        assertFalse( json( patch ).getAsJsonObject().has( "current" ), "nothing to merge against: " + patch.body() );
        final HttpResponse< String > comment = member.send( "POST", "/api/work/items/" + key( item ) + "/comments",
            Map.of( "body", "late" ) );
        assertEquals( 409, comment.statusCode(), comment.body() );
        assertEquals( "archived", json( comment ).getAsJsonObject().get( "reason" ).getAsString() );

        final JsonArray activity = json( member.get( "/api/work/activity?project=ARCH" ) ).getAsJsonArray();
        assertTrue( activity.toString().contains( "\"note\":\"archived\"" ), "the archive is in the activity: " + activity );
        assertEquals( "Before the archive", ok( member.get( "/api/work/items/" + key( item ) ) )
            .getAsJsonObject( "item" ).get( "title" ).getAsString() );
    }
}
