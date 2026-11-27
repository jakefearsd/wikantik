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
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intake end to end against the real container: a <em>reporter</em> (a person whose only standing in a project is the
 * {@code reporter} role) files an item and follows only the items they filed, and reaches nothing else.
 *
 * <p><b>How the leak checks work.</b> The work JSON serialises nulls explicitly, so a field that is absent for a
 * reporter arrives as {@code null} and a test for a missing key proves nothing. Every "must not leak" check here
 * searches the response <em>text</em> for the forbidden value (a marker string planted in the member-only data, a
 * login, a display name). The sweep compares each answer a reporter gets with what a stranger (a person with no row
 * anywhere) gets for the same request: status and body must be identical.
 *
 * <p><b>Fixtures.</b> Every method builds its own project, accounts and items (per-run letters in every key and
 * login, as {@link WorkFindIT} does), takes every key and id from the server and relies on no other method's side
 * effects, so the methods may run in any order. Only the admin account, one queue and one agent are shared, and no
 * method changes them.
 *
 * <p>Routes and JSON shapes are those of {@code WorkIntakeResource} and {@code WorkIntakeJson} (task 8.3a report).
 */
public class WorkIntakeIT {

    /** Per-run letters, so a rerun on the same server finds no project, login or product of the last. */
    private static final String RUN = new java.util.Random().ints( 6, 'a', 'z' + 1 )
        .collect( StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append ).toString();
    private static final String QUEUE = "intkq" + RUN;
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String UNKNOWN_KEY = "ZZZZZ-99999";
    private static final String OUTSIDE = "reportedFromOutside";
    /** What the form refuses by name, each with a value of a plausible type. */
    private static final Map< String, Object > MEMBER_FIELDS = memberFields();

    private static Caller admin;
    private static WorkIt.Agent agent;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", QUEUE, "name", "Intake queue " + RUN ) )
            .statusCode() );
        agent = WorkIt.registerAgent( admin, "intkbot" + RUN + "@it", QUEUE );
    }

    private static Map< String, Object > memberFields() {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "assignee", "Somebody" );
        m.put( "priority", 1 );
        m.put( "parentKey", "ABC-1" );
        m.put( "productKey", "someproduct" );
        m.put( "labels", List.of( "x" ) );
        m.put( "sprint", 1 );
        m.put( "estimate", 3 );
        m.put( "queue", "someq" );
        m.put( "dueOn", "2030-01-01" );
        m.put( "kind", "k" );
        m.put( "status", "ready" );
        return m;
    }

    // ================================================================ fixture

    /** One project with a lead, member, viewer, two reporters and a stranger; M1's data is filled in by {@link #loadM1}. */
    private static final class Fx {
        String tag;
        String tagUpper;
        String project;
        String projectName;
        String description;
        String leadLogin;
        String memberLogin;
        String viewerLogin;
        String rLogin;
        String r2Login;
        String sLogin;
        String leadName;
        String memberName;
        String viewerName;
        String rName;
        String r2Name;
        String sName;
        Caller lead;
        Caller member;
        Caller viewer;
        Caller r;
        Caller r2;
        Caller s;
        // M1: the item that carries everything a reporter must never see
        String m1;
        String epic;
        String linkTarget;
        String product;
        String deploymentVersion;
        long commentId;
        long attachmentId;
        long linkId;
        long sprintId;
        long changeId;
        long deploymentId;
        long filterId;
        final List< String > markers = new ArrayList<>();

        List< String > logins() {
            return List.of( leadLogin, memberLogin, viewerLogin, rLogin, r2Login, sLogin );
        }

        /** Everything that must never reach a reporter's answers about M1 or the project. */
        List< String > forbiddenEverywhere() {
            final List< String > out = new ArrayList<>( markers );
            out.addAll( List.of( leadName, memberName, viewerName, projectName, description ) );
            out.addAll( logins() );
            out.add( "\"" + m1 + "\"" );
            return out;
        }
    }

    private static Fx base() throws Exception {
        final int n = SEQ.getAndIncrement();
        final Fx fx = new Fx();
        fx.tag = RUN + ( char ) ( 'a' + n );
        fx.tagUpper = fx.tag.toUpperCase( java.util.Locale.ROOT );
        final String word = Character.toUpperCase( fx.tag.charAt( 0 ) ) + fx.tag.substring( 1 );
        fx.project = "INTK" + RUN.substring( 0, 4 ).toUpperCase( java.util.Locale.ROOT ) + n;
        fx.projectName = "Intk Project " + word;
        fx.description = "DESCMARK" + fx.tagUpper;
        fx.leadLogin = "intklead" + fx.tag;
        fx.memberLogin = "intkmem" + fx.tag;
        fx.viewerLogin = "intkview" + fx.tag;
        fx.rLogin = "intkrptr" + fx.tag;
        fx.r2Login = "intkrptrb" + fx.tag;
        fx.sLogin = "intkstr" + fx.tag;
        fx.leadName = "Intk Lead " + word;
        fx.memberName = "Intk Member " + word;
        fx.viewerName = "Intk Viewer " + word;
        fx.rName = "Intk Reporter " + word;
        fx.r2Name = "Intk Reporterb " + word;
        fx.sName = "Intk Stranger " + word;
        fx.lead = WorkIt.account( admin, fx.leadLogin, fx.leadName );
        fx.member = WorkIt.account( admin, fx.memberLogin, fx.memberName );
        fx.viewer = WorkIt.account( admin, fx.viewerLogin, fx.viewerName );
        fx.r = WorkIt.account( admin, fx.rLogin, fx.rName );
        fx.r2 = WorkIt.account( admin, fx.r2Login, fx.r2Name );
        fx.s = WorkIt.account( admin, fx.sLogin, fx.sName );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", fx.project, "name", fx.projectName,
            "description", fx.description ) ).statusCode() );
        role( fx.project, fx.leadLogin, "lead" );
        role( fx.project, fx.memberLogin, "member" );
        role( fx.project, fx.viewerLogin, "viewer" );
        role( fx.project, fx.rLogin, "reporter" );
        role( fx.project, fx.r2Login, "reporter" );
        return fx;
    }

    private static void role( final String project, final String login, final String role ) throws Exception {
        final HttpResponse< String > r = admin.send( "PUT", "/api/work/projects/" + project + "/members",
            Map.of( "principal", login, "principalKind", "user", "role", role ) );
        assertEquals( 204, r.statusCode(), "grant " + role + " to " + login + ": " + r.body() );
    }

    /** The member's story M1 and everything around it that a reporter must never see, each with its own marker. */
    private static void loadM1( final Fx fx ) throws Exception {
        final String label = "mklabel" + fx.tag;
        final String epicTitle = "MKEPIC" + fx.tagUpper;
        final String linkTitle = "MKLINK" + fx.tagUpper;
        final String title = "MKTITLE" + fx.tagUpper;
        final String body = "MKBODY" + fx.tagUpper;
        final String comment = "MKCOMMENT" + fx.tagUpper;
        final String fileName = "mkfile" + fx.tag + ".txt";
        final String fileBytes = "MKBYTES" + fx.tagUpper;
        final String sprintName = "MKSPRINT" + fx.tagUpper;
        final String branch = "work/mkbranch" + fx.tag;
        final String filterName = "MKFILTER" + fx.tagUpper;
        fx.product = "intkprod" + fx.tag;
        fx.deploymentVersion = "9." + ( Math.abs( RUN.hashCode() ) % 100000 ) + "." + fx.tag;
        fx.markers.addAll( List.of( label, epicTitle, linkTitle, title, body, comment, fileName, fileBytes, sprintName,
            branch, filterName, fx.product, fx.deploymentVersion ) );

        assertEquals( 201, admin.send( "POST", "/api/work/products", Map.of( "key", fx.product,
            "name", "Intk product " + fx.tag, "defaultBranch", "main" ) ).statusCode() );
        fx.epic = created( fx.member, Map.of( "project", fx.project, "level", "epic", "title", epicTitle ) );
        fx.linkTarget = created( fx.member, Map.of( "project", fx.project, "level", "task", "title", linkTitle ) );
        fx.m1 = created( fx.member, Map.of( "project", fx.project, "level", "story", "title", title, "body", body,
            "labels", List.of( label ), "parentKey", fx.epic, "productKey", fx.product, "assignee", fx.leadName ) );

        final HttpResponse< String > sprint = fx.lead.send( "POST", "/api/work/projects/" + fx.project + "/sprints", Map.of(
            "name", sprintName, "startsOn", LocalDate.now().toString(), "endsOn", LocalDate.now().plusDays( 13 ).toString() ) );
        assertEquals( 201, sprint.statusCode(), sprint.body() );
        fx.sprintId = json( sprint ).getAsJsonObject().get( "id" ).getAsLong();
        patch( fx.member, fx.m1, Map.of( "sprint", fx.sprintId, "estimate", 8 ), null );

        final HttpResponse< String > c = fx.member.send( "POST", "/api/work/items/" + fx.m1 + "/comments",
            Map.of( "body", comment ) );
        assertEquals( 201, c.statusCode(), c.body() );
        fx.commentId = json( c ).getAsJsonObject().get( "id" ).getAsLong();

        final HttpResponse< String > up = fx.member.multipart( "/api/work/items/" + fx.m1 + "/attachments", fileName,
            fileBytes.getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( 201, up.statusCode(), up.body() );
        fx.attachmentId = json( up ).getAsJsonObject().get( "id" ).getAsLong();

        final HttpResponse< String > link = fx.member.send( "POST", "/api/work/items/" + fx.m1 + "/links",
            Map.of( "kind", "blocked_by", "target", fx.linkTarget ) );
        assertEquals( 201, link.statusCode(), link.body() );
        fx.linkId = json( link ).getAsJsonObject().get( "id" ).getAsLong();

        assertEquals( 200, fx.member.send( "PUT", "/api/work/watch/" + fx.m1, Map.of( "state", "watching" ) ).statusCode() );

        final HttpResponse< String > change = admin.send( "POST", "/api/work/items/" + fx.m1 + "/changes",
            Map.of( "branch", branch, "headSha", "abc1234" ) );
        assertEquals( 201, change.statusCode(), change.body() );
        fx.changeId = json( change ).getAsJsonObject().get( "id" ).getAsLong();

        // The deployment must really exist (a person's story starts ready, which is what a deployment carries), so the
        // sweep drives the real deployment routes and not an id that does not exist.
        final HttpResponse< String > dep = admin.send( "POST", "/api/work/deployments", Map.of( "product", fx.product,
            "version", fx.deploymentVersion, "items", List.of( fx.m1 ) ) );
        assertEquals( 201, dep.statusCode(), "request a deployment of M1: " + dep.body() );
        fx.deploymentId = json( dep ).getAsJsonObject().get( "id" ).getAsLong();

        final HttpResponse< String > filter = fx.member.send( "POST", "/api/work/filters", Map.of( "name", filterName,
            "project", fx.project, "shared", true, "filter", Map.of( "label", label ) ) );
        assertEquals( 201, filter.statusCode(), filter.body() );
        fx.filterId = json( filter ).getAsJsonObject().get( "id" ).getAsLong();
    }

    // ================================================================ helpers

    /** Creates an item through the ordinary route and returns the key the server gave it. */
    private static String created( final Caller who, final Map< String, Object > draft ) throws Exception {
        final HttpResponse< String > r = who.send( "POST", "/api/work/items", draft );
        assertEquals( 201, r.statusCode(), r.body() );
        return itemOf( json( r ).getAsJsonObject() ).get( "key" ).getAsString();
    }

    private static JsonObject itemOf( final JsonObject view ) {
        return view.has( "item" ) && view.get( "item" ).isJsonObject() ? view.getAsJsonObject( "item" ) : view;
    }

    private static JsonObject viewOf( final Caller who, final String key ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/items/" + key );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject();
    }

    private static int version( final Caller who, final String key ) throws Exception {
        return itemOf( viewOf( who, key ) ).get( "version" ).getAsInt();
    }

    private static HttpResponse< String > patchRaw( final Caller who, final String key, final Map< String, Object > changes,
                                                     final String note ) throws Exception {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "expectedVersion", version( who, key ) );
        body.put( "changes", changes );
        if ( note != null ) {
            body.put( "note", note );
        }
        return who.send( "PATCH", "/api/work/items/" + key, body );
    }

    private static void patch( final Caller who, final String key, final Map< String, Object > changes, final String note )
            throws Exception {
        final HttpResponse< String > r = patchRaw( who, key, changes, note );
        assertEquals( 200, r.statusCode(), "patch " + key + " " + changes.keySet() + ": " + r.body() );
    }

    /** A reporter files through the intake route and gets the 201 view. */
    private static JsonObject file( final Caller who, final String project, final String title, final String body )
            throws Exception {
        final Map< String, Object > draft = new LinkedHashMap<>();
        draft.put( "projectKey", project );
        draft.put( "level", "task" );
        draft.put( "type", "bug" );
        draft.put( "title", title );
        draft.put( "body", body );
        final HttpResponse< String > r = who.send( "POST", "/api/work/intake/items", draft );
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject();
    }

    private static JsonObject intakeItem( final Caller who, final String key ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/intake/items/" + key );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject();
    }

    private static HttpResponse< String > comment( final Caller who, final String key, final String text ) throws Exception {
        return who.send( "POST", "/api/work/intake/items/" + key + "/comments", Map.of( "body", text ) );
    }

    /** The status and the first 200 characters of the body: a 20,000-character echo would bury the failure. */
    private static String brief( final HttpResponse< String > r ) {
        return r.statusCode() + " " + r.body().substring( 0, Math.min( 200, r.body().length() ) );
    }

    private static void assertNone( final String text, final Collection< String > forbidden, final String where ) {
        for ( final String f : forbidden ) {
            assertFalse( text.contains( f ), where + " contains the forbidden value " + f );
        }
    }

    /** The keys a reporter's own view must never have, as quoted JSON keys. */
    private static final List< String > MEMBER_ONLY_KEYS = List.of( "\"assignee\"", "\"assigneeName\"", "\"labels\"",
        "\"sprint\"", "\"sprintId\"", "\"estimate\"", "\"parentKey\"", "\"links\"", "\"productKey\"", "\"queue\"",
        "\"priority\"", "\"viewerRole\"", "\"claimedByAgent\"", "\"authorAgentId\"", "\"agentId\"" );

    /** A not-found, exactly as the server says it for a key that does not exist. */
    private static void assertSameNotFound( final HttpResponse< String > got, final HttpResponse< String > unknown,
                                            final String what ) {
        assertEquals( 404, got.statusCode(), what + ": " + got.body() );
        assertEquals( unknown.statusCode(), got.statusCode(), what );
        assertEquals( unknown.body(), got.body(), what + ": the same body as an unknown key" );
    }

    private static List< String > noticeTypes( final String inboxBody ) {
        final List< String > out = new ArrayList<>();
        for ( final JsonElement n : json2( inboxBody ).getAsJsonArray( "notices" ) ) {
            out.add( n.getAsJsonObject().getAsJsonObject( "event" ).get( "type" ).getAsString() );
        }
        return out;
    }

    private static JsonObject json2( final String body ) {
        return com.google.gson.JsonParser.parseString( body ).getAsJsonObject();
    }

    private static int unread( final Caller who ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/inbox/unread" );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().get( "unread" ).getAsInt();
    }

    private static String inbox( final Caller who ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/inbox?limit=100" );
        assertEquals( 200, r.statusCode(), r.body() );
        return r.body();
    }

    /** Polls the inbox until it holds at least {@code n} notices (the dispatcher runs every 2 s in this wiki). */
    private static String awaitNotices( final Caller who, final int n ) throws Exception {
        return WorkIt.await( "the inbox with " + n + " notices", who, "/api/work/inbox?limit=100",
            b -> noticeTypes( b ).size() >= n );
    }

    private static Set< String > cardKeys( final JsonArray board ) {
        final Set< String > keys = new HashSet<>();
        for ( final JsonElement col : board ) {
            for ( final JsonElement card : col.getAsJsonObject().getAsJsonArray( "items" ) ) {
                keys.add( card.getAsJsonObject().get( "key" ).getAsString() );
            }
        }
        return keys;
    }

    private static JsonObject cardOf( final JsonArray board, final String key ) {
        for ( final JsonElement col : board ) {
            for ( final JsonElement card : col.getAsJsonObject().getAsJsonArray( "items" ) ) {
                if ( key.equals( card.getAsJsonObject().get( "key" ).getAsString() ) ) {
                    return card.getAsJsonObject();
                }
            }
        }
        return null;
    }

    private static boolean isTrue( final JsonObject o, final String field ) {
        return o.has( field ) && o.get( field ).isJsonPrimitive() && o.get( field ).getAsBoolean();
    }

    /** What a member can see of an item that a reporter must not be able to change, as a few comparable facts. */
    private static String snapshot( final Fx fx, final String key ) throws Exception {
        final JsonObject view = viewOf( fx.member, key );
        final JsonObject item = itemOf( view );
        final HttpResponse< String > members = fx.lead.get( "/api/work/projects/" + fx.project + "/members" );
        assertEquals( 200, members.statusCode(), members.body() );
        return item.get( "version" ) + "|" + item.get( "status" ) + "|" + item.get( "title" ) + "|"
            + view.getAsJsonArray( "comments" ).size() + "|" + view.getAsJsonArray( "links" ).size() + "|"
            + item.get( "labels" ) + "|" + item.get( "assignee" ) + "|" + json( members ).getAsJsonArray().size();
    }

    // ================================================================ filing

    @Test
    void aReporterFilesADraftThatMembersSeeAsFromOutside() throws Exception {
        final Fx fx = base();

        final HttpResponse< String > projects = fx.r.get( "/api/work/intake/projects" );
        assertEquals( 200, projects.statusCode(), projects.body() );
        final JsonArray listed = json( projects ).getAsJsonArray();
        assertEquals( 1, listed.size(), projects.body() );
        final JsonObject entry = listed.get( 0 ).getAsJsonObject();
        assertEquals( fx.project, entry.get( "key" ).getAsString() );
        assertEquals( fx.projectName, entry.get( "name" ).getAsString() );
        assertEquals( "reporter", entry.get( "role" ).getAsString() );
        assertEquals( Set.of( "key", "name", "role" ), entry.keySet(), "key, name and role only" );
        assertFalse( projects.body().contains( fx.description ), "no project description: " + projects.body() );

        final HttpResponse< String > filed = fx.r.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project,
            "level", "task", "type", "bug", "title", "Printer on fire " + fx.tag, "body", "It is smoking " + fx.tag ) );
        assertEquals( 201, filed.statusCode(), filed.body() );
        final JsonObject view = json( filed ).getAsJsonObject();
        final String key = view.get( "key" ).getAsString();
        assertTrue( key.startsWith( fx.project + "-" ), "the key comes from the project's own counter: " + key );
        assertEquals( "draft", view.get( "status" ).getAsString() );
        assertEquals( "bug", view.get( "type" ).getAsString() );
        assertEquals( fx.rName, view.get( "reporter" ).getAsString(), "the reporter is shown by display name" );
        assertNone( filed.body(), fx.logins(), "the filing response" );
        assertNone( filed.body(), MEMBER_ONLY_KEYS, "the filing response" );

        // R's later reads never carry a login of anyone, and never a member-only field
        for ( final String path : List.of( "/api/work/intake/items/" + key, "/api/work/intake/items",
            "/api/work/intake/projects" ) ) {
            final HttpResponse< String > r = fx.r.get( path );
            assertEquals( 200, r.statusCode(), path );
            assertNone( r.body(), fx.logins(), path );
            assertNone( r.body(), MEMBER_ONLY_KEYS, path );
        }
        final JsonArray mine = json( fx.r.get( "/api/work/intake/items" ) ).getAsJsonArray();
        assertEquals( List.of( key ), keysOf( mine ), "R lists exactly what R filed" );

        // a member sees it on the board, marked as filed from outside, under R's display name and never R's login
        final JsonArray board = json( fx.member.get( "/api/work/projects/" + fx.project + "/board" ) ).getAsJsonArray();
        final JsonObject card = cardOf( board, key );
        assertNotNull( card, "the draft is on the board" );
        assertTrue( isTrue( card, OUTSIDE ), "the card says it was filed from outside: " + card );
        final HttpResponse< String > asMember = fx.member.get( "/api/work/items/" + key );
        assertEquals( 200, asMember.statusCode() );
        assertTrue( asMember.body().contains( fx.rName ), "a member sees the reporter's display name" );
        assertFalse( asMember.body().contains( fx.rLogin ), "and not their login: " + asMember.body() );
        assertTrue( isTrue( json( asMember ).getAsJsonObject(), OUTSIDE ), asMember.body() );

        // Q14: a member and a viewer may file through the same route; theirs is a draft too and is not "from outside"
        final JsonObject byMember = file( fx.member, fx.project, "Member's request " + fx.tag, "m" );
        assertEquals( "draft", byMember.get( "status" ).getAsString() );
        final JsonObject byViewer = file( fx.viewer, fx.project, "Viewer's request " + fx.tag, "v" );
        assertEquals( "draft", byViewer.get( "status" ).getAsString() );
        final JsonArray board2 = json( fx.member.get( "/api/work/projects/" + fx.project + "/board" ) ).getAsJsonArray();
        assertFalse( isTrue( cardOf( board2, byMember.get( "key" ).getAsString() ), OUTSIDE ), "a member's own filing" );
        assertFalse( isTrue( cardOf( board2, byViewer.get( "key" ).getAsString() ), OUTSIDE ), "a viewer's filing" );
        assertTrue( isTrue( cardOf( board2, key ), OUTSIDE ), "R's is still marked" );
        // and R's list does not grow with other people's filings
        assertEquals( List.of( key ), keysOf( json( fx.r.get( "/api/work/intake/items" ) ).getAsJsonArray() ) );

        // an epic is a member's level
        final HttpResponse< String > epic = fx.r.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project,
            "level", "epic", "title", "Too big", "body", "x" ) );
        assertEquals( 400, epic.statusCode(), epic.body() );
        assertEquals( "level", json( epic ).getAsJsonObject().get( "field" ).getAsString() );
    }

    private static List< String > keysOf( final JsonArray rows ) {
        final List< String > out = new ArrayList<>();
        for ( final JsonElement e : rows ) {
            out.add( e.getAsJsonObject().get( "key" ).getAsString() );
        }
        return out;
    }

    @Test
    void aReporterIsRefusedByNameForMemberFieldsAndForOversizeText() throws Exception {
        final Fx fx = base();

        for ( final Map.Entry< String, Object > field : MEMBER_FIELDS.entrySet() ) {
            final Map< String, Object > draft = new LinkedHashMap<>();
            draft.put( "projectKey", fx.project );
            draft.put( "level", "task" );
            draft.put( "title", "Sneaky " + field.getKey() );
            draft.put( "body", "b" );
            draft.put( field.getKey(), field.getValue() );
            final HttpResponse< String > r = fx.r.send( "POST", "/api/work/intake/items", draft );
            assertEquals( 400, r.statusCode(), field.getKey() + ": " + r.body() );
            final JsonObject err = json( r ).getAsJsonObject();
            assertEquals( field.getKey(), err.get( "field" ).getAsString(), "the 400 names the field: " + r.body() );
            assertEquals( "A project member sets this after triage.", err.get( "message" ).getAsString() );
        }
        final HttpResponse< String > unknown = fx.r.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project,
            "level", "task", "title", "t", "bogus", "x" ) );
        assertEquals( 400, unknown.statusCode(), unknown.body() );
        assertEquals( "bogus", json( unknown ).getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "Unknown field: bogus", json( unknown ).getAsJsonObject().get( "message" ).getAsString() );
        assertEquals( List.of(), keysOf( json( fx.r.get( "/api/work/intake/items" ) ).getAsJsonArray() ),
            "every refusal above stored nothing" );

        // size caps: 20,000 characters are accepted, one more is refused naming the field
        final String atCap = "x".repeat( 20_000 );
        final HttpResponse< String > tooBig = fx.r.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project,
            "level", "task", "title", "Too much", "body", atCap + "y" ) );
        assertEquals( 400, tooBig.statusCode(), brief( tooBig ) );
        assertEquals( "body", json( tooBig ).getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( List.of(), keysOf( json( fx.r.get( "/api/work/intake/items" ) ).getAsJsonArray() ), "nothing stored" );
        final JsonObject exactly = file( fx.r, fx.project, "Exactly at the cap", atCap );
        final String key = exactly.get( "key" ).getAsString();

        final HttpResponse< String > bigComment = comment( fx.r, key, atCap + "y" );
        assertEquals( 400, bigComment.statusCode(), brief( bigComment ) );
        assertEquals( "body", json( bigComment ).getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( 201, comment( fx.r, key, atCap ).statusCode(), "a comment of exactly 20,000 is accepted" );
        assertEquals( 1, intakeItem( fx.r, key ).getAsJsonArray( "comments" ).size(), "only the accepted comment is stored" );
        final HttpResponse< String > extra = fx.r.send( "POST", "/api/work/intake/items/" + key + "/comments",
            Map.of( "body", "hi", "assignee", "Somebody" ) );
        assertEquals( 400, extra.statusCode(), extra.body() );
        assertEquals( "assignee", json( extra ).getAsJsonObject().get( "field" ).getAsString() );

        // a person with no row cannot file at all, and it looks like a project that does not exist
        final HttpResponse< String > stranger = fx.s.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project,
            "level", "task", "title", "Let me in", "body", "x" ) );
        final HttpResponse< String > ghost = fx.s.send( "POST", "/api/work/intake/items", Map.of( "projectKey", "ZZZ" + fx.tagUpper.substring( 0, 5 ),
            "level", "task", "title", "Let me in", "body", "x" ) );
        assertSameNotFound( stranger, ghost, "a stranger filing" );
    }

    // ================================================================ a reporter's own item

    @Test
    void aReporterFollowsTheirItemReadsTheWholeDiscussionAndChangesNothing() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String key = file( fx.r, fx.project, "My broken thing " + fx.tag, "Please look " + fx.tag )
            .get( "key" ).getAsString();
        final JsonObject r2Item = file( fx.r2, fx.project, "R2's broken thing " + fx.tag, "r2" );
        final String key2 = r2Item.get( "key" ).getAsString();

        // the reduced view: none of M1's markers or anyone's login, and none of the member-only fields
        final HttpResponse< String > got = fx.r.get( "/api/work/intake/items/" + key );
        assertEquals( 200, got.statusCode(), got.body() );
        assertNone( got.body(), fx.forbiddenEverywhere().stream().filter( v -> !v.equals( fx.projectName )
            && !v.equals( fx.description ) ).toList(), "the reporter's own item" );
        assertNone( got.body(), MEMBER_ONLY_KEYS, "the reporter's own item" );
        final JsonObject view = json( got ).getAsJsonObject();
        assertEquals( fx.projectName, view.get( "projectName" ).getAsString(), "the project's name is what the form already showed" );
        for ( final JsonElement e : view.getAsJsonArray( "events" ) ) {
            assertTrue( Set.of( "created", "status", "commented", "attached" ).contains(
                e.getAsJsonObject().get( "type" ).getAsString() ), "only the four event types: " + e );
        }

        // R comments; the member and the lead reply; R reads the whole discussion by display names only
        final HttpResponse< String > mine = comment( fx.r, key, "Here is more detail " + fx.tag );
        assertEquals( 201, mine.statusCode(), mine.body() );
        assertTrue( json( mine ).getAsJsonObject().get( "mine" ).getAsBoolean() );
        assertEquals( fx.rName, json( mine ).getAsJsonObject().get( "authorName" ).getAsString() );
        assertEquals( 201, fx.member.send( "POST", "/api/work/items/" + key + "/comments",
            Map.of( "body", "Thanks, looking now " + fx.tag ) ).statusCode() );
        assertEquals( 201, fx.lead.send( "POST", "/api/work/items/" + key + "/comments",
            Map.of( "body", "Triaged " + fx.tag ) ).statusCode() );
        final HttpResponse< String > after = fx.r.get( "/api/work/intake/items/" + key );
        assertEquals( 200, after.statusCode() );
        final JsonArray discussion = json( after ).getAsJsonObject().getAsJsonArray( "comments" );
        final List< String > authors = new ArrayList<>();
        for ( final JsonElement c : discussion ) {
            authors.add( c.getAsJsonObject().get( "authorName" ).getAsString() );
        }
        assertEquals( List.of( fx.rName, fx.memberName, fx.leadName ), authors, "the whole discussion, by display name" );
        assertNone( after.body(), fx.logins(), "the discussion" );
        assertNone( after.body(), fx.markers, "the discussion" );

        // nothing R can try through any route changes anything: each attempt is refused, then a member re-reads
        final String before = snapshot( fx, key );
        final int v = version( fx.member, key );
        final long memberComment = commentIdOf( fx.member, key, "Thanks, looking now " + fx.tag );
        final long ownComment = commentIdOf( fx.member, key, "Here is more detail " + fx.tag );
        final List< HttpResponse< String > > attempts = List.of(
            fx.r.send( "PATCH", "/api/work/items/" + key, Map.of( "expectedVersion", v, "changes", Map.of( "status", "done" ) ) ),
            fx.r.send( "PATCH", "/api/work/items/" + key, Map.of( "expectedVersion", v, "changes", Map.of( "assignee", fx.rName ) ) ),
            fx.r.send( "PATCH", "/api/work/items/" + key, Map.of( "expectedVersion", v, "changes", Map.of( "labels", List.of( "mine" ) ) ) ),
            fx.r.send( "POST", "/api/work/items/" + key + "/links", Map.of( "kind", "blocked_by", "target", fx.m1 ) ),
            fx.r.send( "POST", "/api/work/items/" + key + "/links", Map.of( "kind", "page", "target", "Main" ) ),
            fx.r.send( "PATCH", "/api/work/comments/" + memberComment, Map.of( "body", "edited by R" ) ),
            fx.r.send( "DELETE", "/api/work/comments/" + memberComment, null ),
            fx.r.send( "DELETE", "/api/work/comments/" + ownComment, null ),
            fx.r.send( "POST", "/api/work/items/" + key + "/comments", Map.of( "body", "through the member route" ) ) );
        final HttpResponse< String > unknown = fx.s.send( "PATCH", "/api/work/items/" + UNKNOWN_KEY,
            Map.of( "expectedVersion", 1, "changes", Map.of( "status", "done" ) ) );
        for ( int i = 0; i < attempts.size(); i++ ) {
            assertEquals( 404, attempts.get( i ).statusCode(), "attempt " + i + ": " + attempts.get( i ).body() );
            assertEquals( unknown.body(), attempts.get( i ).body(), "attempt " + i + " answers like an unknown key" );
        }
        assertEquals( before, snapshot( fx, key ), "nothing R tried changed the item, its comments, links or the members" );
        assertEquals( "draft", itemOf( viewOf( fx.member, key ) ).get( "status" ).getAsString() );

        // R cannot read M1 or R2's item through the intake route: the same not-found as a key that does not exist
        final HttpResponse< String > ghost = fx.r.get( "/api/work/intake/items/" + UNKNOWN_KEY );
        assertSameNotFound( ghost, ghost, "an unknown key" );
        assertSameNotFound( fx.r.get( "/api/work/intake/items/" + fx.m1 ), ghost, "M1 through the intake route" );
        assertSameNotFound( fx.r.get( "/api/work/intake/items/" + key2 ), ghost, "another reporter's item" );
        assertSameNotFound( fx.r.get( "/api/work/intake/items/" + fx.project ), ghost, "a project key" );
        assertSameNotFound( comment( fx.r, fx.m1, "hello" ), comment( fx.r, UNKNOWN_KEY, "hello" ), "commenting on M1" );
        assertSameNotFound( comment( fx.r, key2, "hello" ), comment( fx.r, UNKNOWN_KEY, "hello" ), "commenting on R2's item" );
        // and R2 cannot read R's
        assertSameNotFound( fx.r2.get( "/api/work/intake/items/" + key ), ghost, "R's item as R2" );
        assertSameNotFound( comment( fx.r2, key, "hello" ), comment( fx.r2, UNKNOWN_KEY, "hello" ), "R2 commenting on R's item" );
        assertEquals( List.of( key2 ), keysOf( json( fx.r2.get( "/api/work/intake/items" ) ).getAsJsonArray() ) );
        assertEquals( 0, intakeItem( fx.r2, key2 ).getAsJsonArray( "comments" ).size(), "R2's own item has no comments" );
        assertEquals( 1, intakeItem( fx.r, key ).getAsJsonArray( "comments" ).asList().stream().filter(
            c -> c.getAsJsonObject().get( "authorName" ).getAsString().equals( fx.rName ) ).count(),
            "nothing R tried on R2's or M1's item reached them" );
        assertEquals( 1, viewOf( fx.member, fx.m1 ).getAsJsonArray( "comments" ).size(), "M1 got no comment" );
    }

    private static long commentIdOf( final Caller who, final String key, final String text ) throws Exception {
        for ( final JsonElement c : viewOf( who, key ).getAsJsonArray( "comments" ) ) {
            if ( c.getAsJsonObject().get( "body" ).getAsString().equals( text ) ) {
                return c.getAsJsonObject().get( "id" ).getAsLong();
            }
        }
        throw new AssertionError( "no comment with that text on " + key );
    }

    // ================================================================ the leak sweep

    private record Req( String method, String path, Object body ) {
        static Req get( final String path ) {
            return new Req( "GET", path, null );
        }
    }

    private static String enc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 );
    }

    /** Every route family of the work API, aimed at project P, item M1 and the real ids around it. */
    private static List< Req > sweep( final Fx fx ) {
        final String p = "/api/work/projects/" + fx.project;
        final String m1 = "/api/work/items/" + fx.m1;
        final String prod = "/api/work/products/" + fx.product;
        final long sid = fx.sprintId;
        final List< Req > out = new ArrayList<>();
        // projects: view, members, board, labels, sprints, velocity, flow, dora, change counts, webhooks
        out.addAll( List.of( Req.get( "/api/work/projects" ), Req.get( p ), Req.get( p + "/members" ), Req.get( p + "/board" ),
            Req.get( p + "/board?label=mklabel" + fx.tag ), Req.get( p + "/labels" ), Req.get( p + "/sprints" ),
            Req.get( p + "/velocity" ), Req.get( p + "/flow" ), Req.get( p + "/dora" ), Req.get( p + "/change-counts" ),
            Req.get( p + "/webhooks" ) ) );
        // list and find, with filters and a text search for M1's own words
        out.addAll( List.of( Req.get( "/api/work/items?project=" + fx.project ),
            Req.get( "/api/work/items?project=" + fx.project + "&label=mklabel" + fx.tag ),
            Req.get( "/api/work/items?project=" + fx.project + "&assignee=" + enc( fx.leadName ) ),
            Req.get( "/api/work/items?project=" + fx.project + "&sprint=" + sid ),
            Req.get( "/api/work/items" ), Req.get( "/api/work/items?projects=" + fx.project ),
            Req.get( "/api/work/items?label=mklabel" + fx.tag ), Req.get( "/api/work/items?assignee=" + enc( fx.leadName ) ),
            Req.get( "/api/work/items?sprint=" + sid ), Req.get( "/api/work/items?reporter=me" ),
            Req.get( "/api/work/items?watching=true" ),
            Req.get( "/api/work/items?q=MKTITLE" + fx.tagUpper ), Req.get( "/api/work/items?q=MKBODY" + fx.tagUpper ),
            Req.get( "/api/work/items?projects=" + fx.project + "&q=MKCOMMENT" + fx.tagUpper ),
            Req.get( "/api/work/claimable?project=" + fx.project ) ) );
        // M1 itself (its comments, links and the rest come inside the view) and every route under it
        out.addAll( List.of( Req.get( m1 ), Req.get( m1 + "/events" ), Req.get( m1 + "/revisions" ),
            Req.get( m1 + "/changes" ), Req.get( m1 + "/policy" ), Req.get( m1 + "/shipped-in" ), Req.get( m1 + "/points" ),
            Req.get( m1 + "/attachments" ), Req.get( m1 + "/attachments/" + fx.attachmentId ),
            Req.get( "/api/work/watch/" + fx.m1 ) ) );
        // sprints, flow of the product, deployments, changes
        out.addAll( List.of( Req.get( "/api/work/sprints/" + sid ), Req.get( "/api/work/sprints/" + sid + "/report" ),
            Req.get( "/api/work/deployments" ), Req.get( "/api/work/deployments?product=" + fx.product ),
            Req.get( "/api/work/deployments/" + fx.deploymentId ), Req.get( "/api/work/deployments/" + fx.deploymentId + "/history" ),
            Req.get( "/api/work/deployments/" + fx.deploymentId + "/gate" ), Req.get( "/api/work/products" ),
            Req.get( prod + "/history" ), Req.get( prod + "/dora" ) ) );
        // saved filters, the project's activity feed, the assignment log, agents and queues
        out.addAll( List.of( Req.get( "/api/work/filters" ), Req.get( "/api/work/activity?project=" + fx.project ),
            Req.get( "/api/work/activity?project=" + fx.project + "&item=" + fx.m1 ),
            Req.get( "/api/work/activity?project=" + fx.project + "&limit=500" ),
            Req.get( "/api/work/log?project=" + fx.project ), Req.get( "/api/work/log" ), Req.get( "/api/work/agents" ),
            Req.get( "/api/work/queues" ) ) );
        // writes: every one is refused the way it is for a stranger
        out.addAll( List.of(
            new Req( "POST", "/api/work/items", Map.of( "project", fx.project, "level", "task", "title", "R makes an item" ) ),
            new Req( "PATCH", m1, Map.of( "expectedVersion", 1, "changes", Map.of( "title", "R renames M1" ) ) ),
            new Req( "POST", m1 + "/comments", Map.of( "body", "R comments on M1" ) ),
            new Req( "PATCH", "/api/work/comments/" + fx.commentId, Map.of( "body", "R edits" ) ),
            new Req( "DELETE", "/api/work/comments/" + fx.commentId, null ),
            new Req( "POST", m1 + "/links", Map.of( "kind", "page", "target", "Main" ) ),
            new Req( "DELETE", "/api/work/links/" + fx.linkId, null ),
            new Req( "POST", m1 + "/changes", Map.of( "branch", "work/r-branch", "headSha", "abc1234" ) ),
            new Req( "PATCH", "/api/work/changes/" + fx.changeId, Map.of( "headSha", "abc1234" ) ),
            new Req( "POST", "/api/work/changes/" + fx.changeId + "/request-review", null ),
            new Req( "POST", "/api/work/changes/" + fx.changeId + "/review", Map.of( "verdict", "approve" ) ),
            new Req( "POST", "/api/work/changes/" + fx.changeId + "/merge", Map.of( "mergedSha", "abc1234def" ) ),
            new Req( "POST", "/api/work/changes/" + fx.changeId + "/abandon", null ),
            new Req( "POST", "/api/work/changes/" + fx.changeId + "/acknowledge-flag", null ),
            new Req( "POST", m1 + "/claim", null ), new Req( "POST", m1 + "/release", null ),
            new Req( "POST", m1 + "/handoff", Map.of( "expectedVersion", 1, "status", "ready", "queue", QUEUE ) ),
            new Req( "POST", m1 + "/acknowledge", null ),
            new Req( "PUT", "/api/work/watch/" + fx.m1, Map.of( "state", "watching" ) ),
            new Req( "POST", "/api/work/inbox/read/" + fx.m1, null ),
            new Req( "PATCH", p, Map.of( "name", "R renames the project" ) ),
            new Req( "POST", p + "/archive", null ),
            new Req( "PUT", p + "/members", Map.of( "principal", fx.rLogin, "principalKind", "user", "role", "lead" ) ),
            new Req( "DELETE", p + "/members", Map.of( "principal", fx.memberLogin, "principalKind", "user" ) ),
            new Req( "POST", p + "/sprints", Map.of( "name", "R's sprint", "startsOn", "2030-01-01", "endsOn", "2030-01-14" ) ),
            new Req( "PATCH", "/api/work/sprints/" + sid, Map.of( "name", "renamed" ) ),
            new Req( "POST", "/api/work/sprints/" + sid + "/start", null ),
            new Req( "POST", "/api/work/sprints/" + sid + "/close", Map.of() ),
            new Req( "POST", p + "/webhooks", Map.of( "url", "https://example.com/hook", "format", "generic" ) ),
            new Req( "PATCH", p + "/webhooks/1", Map.of( "enabled", false ) ),
            new Req( "DELETE", p + "/webhooks/1", null ),
            new Req( "POST", "/api/work/deployments", Map.of( "product", fx.product, "version", "1.0.0", "items", List.of( fx.m1 ) ) ),
            new Req( "POST", "/api/work/deployments/" + fx.deploymentId + "/approve", null ),
            new Req( "POST", "/api/work/deployments/" + fx.deploymentId + "/reject", Map.of( "note", "no" ) ),
            new Req( "POST", "/api/work/deployments/" + fx.deploymentId + "/start", null ),
            new Req( "POST", "/api/work/filters", Map.of( "name", "R pinned", "project", fx.project, "shared", true,
                "filter", Map.of( "label", "x" ) ) ),
            new Req( "PATCH", "/api/work/filters/" + fx.filterId, Map.of( "name", "renamed" ) ),
            new Req( "DELETE", "/api/work/filters/" + fx.filterId, null ),
            new Req( "POST", prod + "/hook", Map.of() ), new Req( "DELETE", prod + "/hook", null ),
            // the code-host hook routes are signature-authenticated: no caller and no signature reaches the same 404
            new Req( "POST", "/api/work/hooks/" + fx.product + "/generic", Map.of() ),
            new Req( "POST", "/api/work/hooks/" + fx.product + "/github", Map.of() ) ) );
        return out;
    }

    @Test
    void aReporterReachesNothingElseInTheProjectExactlyAsAStrangerDoes() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String key = file( fx.r, fx.project, "Mine alone " + fx.tag, "mine" ).get( "key" ).getAsString();
        final String before = snapshot( fx, fx.m1 );
        final JsonArray boardBefore = json( fx.member.get( "/api/work/projects/" + fx.project + "/board" ) ).getAsJsonArray();

        final List< String > forbidden = fx.forbiddenEverywhere();
        final List< Req > requests = sweep( fx );
        int driven = 0;
        for ( final Req q : requests ) {
            final String label = q.method() + " " + q.path();
            final HttpResponse< String > asR = "GET".equals( q.method() ) ? fx.r.get( q.path() )
                : fx.r.send( q.method(), q.path(), q.body() );
            final HttpResponse< String > asS = "GET".equals( q.method() ) ? fx.s.get( q.path() )
                : fx.s.send( q.method(), q.path(), q.body() );
            assertEquals( asS.statusCode(), asR.statusCode(), label + ": the reporter's status differs from a stranger's" );
            assertEquals( asS.body(), asR.body(), label + ": the reporter's body differs from a stranger's" );
            assertNone( asR.body(), forbidden, label );
            assertFalse( asR.statusCode() >= 500, label + ": " + asR.statusCode() );
            driven++;
        }
        // the list holds 97 requests today (read and write families) and must not shrink; every id in it was asserted real at setup
        assertEquals( requests.size(), driven, "the sweep drove every request of its list" );
        assertTrue( requests.size() >= 97, "the sweep holds every route family: " + requests.size() );

        // an upload to M1 is refused like any other write
        final HttpResponse< String > upR = fx.r.multipart( "/api/work/items/" + fx.m1 + "/attachments", "r.txt",
            "r".getBytes( StandardCharsets.UTF_8 ) );
        final HttpResponse< String > upS = fx.s.multipart( "/api/work/items/" + fx.m1 + "/attachments", "r.txt",
            "r".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( 404, upR.statusCode(), upR.body() );
        assertEquals( upS.body(), upR.body() );

        // the own item is not readable through the ordinary item route either (the intake route is the one door)
        final HttpResponse< String > ghost = fx.s.get( "/api/work/items/" + UNKNOWN_KEY );
        assertSameNotFound( fx.r.get( "/api/work/items/" + key ), ghost, "their own item through the member route" );
        assertSameNotFound( fx.r.get( "/api/work/items/" + key + "/events" ), fx.s.get( "/api/work/items/" + UNKNOWN_KEY + "/events" ),
            "their own item's events through the member route" );
        assertEquals( 200, fx.r.get( "/api/work/intake/items/" + key ).statusCode(), "control: the intake route answers" );
        final HttpResponse< String > ghostIntake = fx.r.get( "/api/work/intake/items/" + UNKNOWN_KEY );
        assertSameNotFound( fx.r.get( "/api/work/intake/items/" + fx.m1 ), ghostIntake, "M1 through the intake route" );

        // nothing the sweep did changed anything a member can see
        assertEquals( before, snapshot( fx, fx.m1 ), "M1, its comments, links and the members are unchanged" );
        assertEquals( cardKeys( boardBefore ), cardKeys( json( fx.member.get( "/api/work/projects/" + fx.project
            + "/board" ) ).getAsJsonArray() ), "the board holds the same cards" );
        assertEquals( 200, fx.member.get( "/api/work/items/" + fx.m1 + "/attachments/" + fx.attachmentId ).statusCode(),
            "M1's attachment is still there" );
    }

    // ================================================================ notifications

    @Test
    void aReporterHearsOnlyStatusChangesAndCommentsOnTheirItemWithNothingInternal() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String noteA = "INTERNALNOTEA" + fx.tagUpper;
        final String noteB = "INTERNALNOTEB" + fx.tagUpper;
        final String key = file( fx.r, fx.project, "Please notify me " + fx.tag, "body" ).get( "key" ).getAsString();
        // the filing makes R a watcher of their own item once the dispatcher has seen it
        WorkIt.await( "R's watch state", fx.r, "/api/work/watch/" + key, b -> b.contains( "watching" ) );
        assertEquals( 0, noticeTypes( inbox( fx.r ) ).size(), "their own filing is not news to them" );
        assertEquals( 0, unread( fx.r ) );

        // 1: a member moves it on
        patch( fx.member, key, Map.of( "status", "refining" ), null );
        awaitNotices( fx.r, 1 );
        // 2: a member moves it again, assigns it to the lead, and gives an internal note
        patch( fx.member, key, Map.of( "status", "in_progress", "assignee", fx.leadName ), noteA );
        final String two = awaitNotices( fx.r, 2 );
        assertNone( two, List.of( noteA, fx.leadName, fx.leadLogin, fx.memberLogin, QUEUE ), "the inbox after a status move" );
        assertEquals( List.of( "status", "status" ), noticeTypes( two ) );
        for ( final JsonElement n : json2( two ).getAsJsonArray( "notices" ) ) {
            final JsonObject ev = n.getAsJsonObject().getAsJsonObject( "event" );
            assertTrue( ev.get( "note" ).isJsonNull(), "no note in a reporter's notice: " + ev );
            assertTrue( ev.get( "agentId" ) == null || ev.get( "agentId" ).isJsonNull(), "no agent id: " + ev );
            assertTrue( Set.of( "refining", "in_progress" ).contains( ev.get( "toValue" ).getAsString() ), ev.toString() );
        }

        // 3 (controller decision 16b): a hand-off that moves the status is the same safe line
        final HttpResponse< String > handoff = fx.lead.send( "POST", "/api/work/items/" + key + "/handoff", Map.of(
            "expectedVersion", version( fx.lead, key ), "status", "review", "queue", QUEUE, "note", noteB ) );
        assertEquals( 200, handoff.statusCode(), handoff.body() );
        final String three = awaitNotices( fx.r, 3 );
        assertEquals( List.of( "status", "status", "status" ), noticeTypes( three ),
            "a hand-off reaches R as a status change, never as a hand-off" );
        assertNone( three, List.of( noteB, noteA, QUEUE, "handoff" ), "the inbox after a hand-off" );

        // 4: a comment reaches them as a comment
        assertEquals( 201, fx.member.send( "POST", "/api/work/items/" + key + "/comments",
            Map.of( "body", "Question for you " + fx.tag ) ).statusCode() );
        assertEquals( 4, noticeTypes( awaitNotices( fx.r, 4 ) ).size() );

        // the silent changes: label, sprint, estimate, assignee, a file, and a link; and M1's own activity
        patch( fx.member, key, Map.of( "labels", List.of( "mklabel" + fx.tag ), "sprint", fx.sprintId, "estimate", 5 ), null );
        patch( fx.member, key, Map.of( "assignee", fx.memberName ), null );
        assertEquals( 201, fx.member.multipart( "/api/work/items/" + key + "/attachments", "silent" + fx.tag + ".txt",
            "s".getBytes( StandardCharsets.UTF_8 ) ).statusCode() );
        assertEquals( 201, fx.member.send( "POST", "/api/work/items/" + key + "/links",
            Map.of( "kind", "blocked_by", "target", fx.m1 ) ).statusCode() );
        assertEquals( 201, fx.lead.send( "POST", "/api/work/items/" + fx.m1 + "/comments",
            Map.of( "body", "Activity on M1 " + fx.tag ) ).statusCode() );
        patch( fx.lead, fx.m1, Map.of( "status", "in_progress" ), "M1 note " + fx.tag );
        // the control: a status move after all of them. Events are handled in order, so once its notice shows, every
        // earlier event has been handled and the count below is exact, not a guess after a wait.
        patch( fx.member, key, Map.of( "status", "ready" ), null );
        final String five = awaitNotices( fx.r, 5 );
        final List< String > types = noticeTypes( five );
        assertEquals( 5, types.size(), "no notice for the label, sprint, estimate, assignee, file, link or M1: " + types );
        assertEquals( 4, types.stream().filter( "status"::equals ).count(), types.toString() );
        assertEquals( 1, types.stream().filter( "commented"::equals ).count(), types.toString() );
        assertNone( five, fx.markers, "the inbox" );
        assertNone( five, List.of( "\"" + fx.m1 + "\"", "silent" + fx.tag, noteA, noteB, QUEUE ), "the inbox" );
        assertNone( five, fx.logins(), "the inbox" );
        assertEquals( 5, unread( fx.r ) );

        // reading: one item's notices, then mark all
        assertSameNotFound( fx.r.send( "POST", "/api/work/inbox/read/" + fx.m1, null ),
            fx.r.send( "POST", "/api/work/inbox/read/" + UNKNOWN_KEY, null ), "marking M1 read" );
        final HttpResponse< String > read = fx.r.send( "POST", "/api/work/inbox/read/" + key, null );
        assertTrue( read.statusCode() == 200 || read.statusCode() == 204, read.body() );
        assertEquals( 0, unread( fx.r ), "reading their item clears it" );
        patch( fx.member, key, Map.of( "status", "in_progress" ), null );
        awaitNotices( fx.r, 6 );
        assertEquals( 1, unread( fx.r ) );
        final HttpResponse< String > all = fx.r.send( "POST", "/api/work/inbox/read-all", null );
        assertTrue( all.statusCode() == 200 || all.statusCode() == 204, all.body() );
        assertEquals( 0, unread( fx.r ), "mark all read clears the count" );
        assertEquals( 0, json2( inbox( fx.r ) ).get( "unread" ).getAsInt() );

        // /api/work/me: nothing of P beyond its key, name and R's role; no projects, no waiting lane
        final HttpResponse< String > me = fx.r.get( "/api/work/me" );
        assertEquals( 200, me.statusCode(), me.body() );
        final JsonObject meJson = json( me ).getAsJsonObject();
        assertEquals( 0, meJson.getAsJsonArray( "projects" ).size() );
        assertEquals( 0, meJson.getAsJsonArray( "waitingOn" ).size() );
        final JsonArray reportable = meJson.getAsJsonArray( "reportable" );
        assertEquals( 1, reportable.size(), me.body() );
        assertEquals( Set.of( "key", "name", "role" ), reportable.get( 0 ).getAsJsonObject().keySet() );
        assertEquals( fx.project, reportable.get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertNone( me.body(), List.of( fx.description, fx.memberName, fx.leadName, fx.m1 ), "/api/work/me" );

        // a stranger hears nothing of this project, and M1's activity reached nobody outside it
        assertEquals( 0, noticeTypes( inbox( fx.s ) ).size() );
        assertEquals( 0, unread( fx.s ) );
        // R2, a reporter of the same project with no item, hears nothing either
        assertEquals( 0, noticeTypes( inbox( fx.r2 ) ).size() );
    }

    // ================================================================ attachments

    @Test
    void aReporterAttachesToTheirOwnItemAndReachesNoOtherFile() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String key = file( fx.r, fx.project, "With a file " + fx.tag, "body" ).get( "key" ).getAsString();
        final String ownBase = "/api/work/items/" + key + "/attachments";
        final byte[] ownBytes = ( "R's own bytes " + fx.tag ).getBytes( StandardCharsets.UTF_8 );

        // upload and download their own file; the listing says they may attach and names people by display name
        final HttpResponse< String > up = fx.r.multipart( ownBase, "r-" + fx.tag + ".txt", ownBytes );
        assertEquals( 201, up.statusCode(), up.body() );
        final JsonObject uploaded = json( up ).getAsJsonObject();
        final long ownId = uploaded.get( "id" ).getAsLong();
        assertNone( up.body(), fx.logins(), "the upload response" );
        final HttpResponse< byte[] > down = fx.r.bytes( ownBase + "/" + ownId );
        assertEquals( 200, down.statusCode() );
        assertTrue( Arrays.equals( ownBytes, down.body() ), "the same bytes come back" );
        final HttpResponse< String > list = fx.r.get( ownBase );
        assertEquals( 200, list.statusCode(), list.body() );
        assertTrue( json( list ).getAsJsonObject().get( "mayAttach" ).getAsBoolean(), "a reporter may attach to their own item" );
        assertEquals( 1, json( list ).getAsJsonObject().getAsJsonArray( "attachments" ).size() );
        assertNone( list.body(), fx.logins(), "the file list" );

        // M1's file is out of reach through their own item's URL, through M1's, and for the other reporter
        final HttpResponse< String > unknownFile = fx.s.get( ownBase + "/987654321" );
        final HttpResponse< String > viaOwn = fx.r.get( ownBase + "/" + fx.attachmentId );
        assertSameNotFound( viaOwn, unknownFile, "M1's attachment id through R's own item" );
        final String m1Base = "/api/work/items/" + fx.m1 + "/attachments";
        assertEquals( 404, fx.r.get( m1Base + "/" + fx.attachmentId ).statusCode() );
        assertEquals( fx.s.get( m1Base + "/" + fx.attachmentId ).body(), fx.r.get( m1Base + "/" + fx.attachmentId ).body() );
        assertEquals( fx.s.get( m1Base ).body(), fx.r.get( m1Base ).body(), "M1's file list is a stranger's answer" );
        assertEquals( 404, fx.r.bytes( m1Base + "/" + fx.attachmentId ).statusCode() );
        assertEquals( 404, fx.r2.bytes( ownBase + "/" + ownId ).statusCode(), "another reporter cannot fetch R's file" );
        assertEquals( 404, fx.r2.get( ownBase ).statusCode() );
        assertEquals( 404, fx.s.bytes( ownBase + "/" + ownId ).statusCode() );

        // a file a member puts on a comment in R's item is visible to R; once that comment is removed it is gone for all
        final HttpResponse< String > c = fx.member.send( "POST", "/api/work/items/" + key + "/comments",
            Map.of( "body", "Here is the log" ) );
        assertEquals( 201, c.statusCode(), c.body() );
        final long commentId = json( c ).getAsJsonObject().get( "id" ).getAsLong();
        final String logName = "log-" + fx.tag + ".txt";
        final HttpResponse< String > memberUp = fx.member.multipart( ownBase, logName, "text/plain",
            "member log".getBytes( StandardCharsets.UTF_8 ), Map.of( "comment", String.valueOf( commentId ) ) );
        assertEquals( 201, memberUp.statusCode(), memberUp.body() );
        final long memberFile = json( memberUp ).getAsJsonObject().get( "id" ).getAsLong();
        final HttpResponse< String > listed = fx.r.get( ownBase );
        assertTrue( listed.body().contains( logName ), "R sees the file on the member's comment: " + listed.body() );
        assertEquals( 200, fx.r.bytes( ownBase + "/" + memberFile ).statusCode() );
        assertNone( listed.body(), fx.logins(), "the file list with a member's file" );

        // R may remove their own file, never the member's
        final HttpResponse< String > foreign = fx.r.send( "DELETE", ownBase + "/" + memberFile, null );
        assertEquals( 403, foreign.statusCode(), foreign.body() );
        assertEquals( 200, fx.r.bytes( ownBase + "/" + memberFile ).statusCode(), "the member's file is still there" );
        final HttpResponse< String > removed = fx.r.send( "DELETE", ownBase + "/" + ownId, null );
        assertEquals( 204, removed.statusCode(), removed.body() );
        assertEquals( 404, fx.r.bytes( ownBase + "/" + ownId ).statusCode(), "their removed file is gone" );

        // removing the comment removes its file from the list and the content, for the reporter and the member alike
        final HttpResponse< String > gone = fx.member.send( "DELETE", "/api/work/comments/" + commentId, null );
        assertTrue( gone.statusCode() == 200 || gone.statusCode() == 204, gone.body() );
        assertFalse( fx.r.get( ownBase ).body().contains( logName ), "the removed comment's file is not listed" );
        assertEquals( 404, fx.r.bytes( ownBase + "/" + memberFile ).statusCode() );
        assertEquals( 404, fx.member.bytes( ownBase + "/" + memberFile ).statusCode() );
        assertFalse( json( fx.r.get( "/api/work/intake/items/" + key ) ).toString().contains( logName ),
            "the file's name is not in R's timeline once the comment is removed" );
        assertEquals( 0, json( fx.r.get( ownBase ) ).getAsJsonObject().getAsJsonArray( "attachments" ).size() );
    }

    // ================================================================ standing changes

    @Test
    void removingAReportersRowClosesTheirItemAndMakingThemAMemberOpensTheMemberView() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String key = file( fx.r, fx.project, "Soon to be orphaned " + fx.tag, "body" ).get( "key" ).getAsString();
        WorkIt.await( "R's watch state", fx.r, "/api/work/watch/" + key, b -> b.contains( "watching" ) );
        patch( fx.member, key, Map.of( "status", "refining" ), null );
        awaitNotices( fx.r, 1 );
        assertEquals( 1, unread( fx.r ) );

        // removing the row: the same answers as for a stranger, and an empty inbox with nothing unread
        assertEquals( 204, admin.send( "DELETE", "/api/work/projects/" + fx.project + "/members",
            Map.of( "principal", fx.rLogin, "principalKind", "user" ) ).statusCode() );
        final HttpResponse< String > ghost = fx.s.get( "/api/work/intake/items/" + key );
        assertSameNotFound( fx.r.get( "/api/work/intake/items/" + key ), ghost, "their item after the row is removed" );
        assertSameNotFound( comment( fx.r, key, "still here?" ), comment( fx.s, key, "still here?" ), "commenting after removal" );
        assertEquals( "[]", fx.r.get( "/api/work/intake/items" ).body().replace( " ", "" ) );
        assertEquals( "[]", fx.r.get( "/api/work/intake/projects" ).body().replace( " ", "" ) );
        assertEquals( 0, json( fx.r.get( "/api/work/me" ) ).getAsJsonObject().getAsJsonArray( "reportable" ).size() );
        assertEquals( 0, noticeTypes( inbox( fx.r ) ).size(), "the inbox no longer shows the item" );
        assertEquals( 0, unread( fx.r ) );
        assertEquals( 404, fx.r.get( "/api/work/watch/" + key ).statusCode() );
        assertSameNotFound( fx.r.send( "POST", "/api/work/projects/" + fx.project + "/sprints",
            Map.of( "name", "x", "startsOn", "2030-01-01", "endsOn", "2030-01-14" ) ),
            fx.s.send( "POST", "/api/work/projects/" + fx.project + "/sprints",
                Map.of( "name", "x", "startsOn", "2030-01-01", "endsOn", "2030-01-14" ) ), "sprints after removal" );
        // the item itself is untouched: a member still sees it
        assertEquals( 200, fx.member.get( "/api/work/items/" + key ).statusCode() );
        // and later events on it reach nobody outside: the lead watches the item, so the lead's notice for a new status
        // move is the proof the dispatcher has handled it, and only then is the removed reporter's inbox read
        assertEquals( 200, fx.lead.send( "PUT", "/api/work/watch/" + key, Map.of( "state", "watching" ) ).statusCode() );
        patch( fx.member, key, Map.of( "status", "ready" ), null );
        patch( fx.member, fx.m1, Map.of( "status", "in_progress" ), null );
        WorkIt.await( "the lead's notice of the move", fx.lead, "/api/work/inbox?limit=100",
            b -> b.contains( "\"" + key + "\"" ) );
        assertEquals( 0, noticeTypes( inbox( fx.r ) ).size(), "a removed reporter hears nothing new" );
        assertEquals( 0, unread( fx.r ) );

        // making them a member: the normal member view through the normal routes
        role( fx.project, fx.rLogin, "member" );
        final HttpResponse< String > asMember = fx.r.get( "/api/work/items/" + key );
        assertEquals( 200, asMember.statusCode(), asMember.body() );
        assertTrue( itemOf( json( asMember ).getAsJsonObject() ).has( "version" ), "the full view carries the version" );
        assertEquals( 200, fx.r.get( "/api/work/projects/" + fx.project + "/board" ).statusCode() );
        assertEquals( 200, fx.r.get( "/api/work/items/" + fx.m1 ).statusCode(), "a member sees M1" );
        final JsonArray reportable = json( fx.r.get( "/api/work/intake/projects" ) ).getAsJsonArray();
        assertEquals( "member", reportable.get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
        assertEquals( 1, json( fx.r.get( "/api/work/me" ) ).getAsJsonObject().getAsJsonArray( "projects" ).size() );
    }

    // ================================================================ credentials

    private static String mintWorkKey( final Caller owner, final String label ) throws Exception {
        final HttpResponse< String > r = owner.send( "POST", "/api/self/apikeys", Map.of( "label", label, "scope", "work" ) );
        assertEquals( 201, r.statusCode(), "mint a work key" );
        return json( r ).getAsJsonObject().get( "token" ).getAsString();
    }

    private static HttpResponse< String > bearer( final String token, final String method, final String path,
                                                  final Object body ) throws Exception {
        return WorkIt.bearer( token, method, path, body == null ? null : WorkIt.GSON.toJson( body ) );
    }

    /** An agent is not a person: an intake list is the service's empty answer, exactly {@code 200 []}. */
    private static void assertAgentGetsNothing( final HttpResponse< String > r, final String what ) {
        assertEquals( 200, r.statusCode(), what + ": " + r.body() );
        assertEquals( "[]", r.body().replace( " ", "" ), what );
    }

    /**
     * An agent with no standing of its own in the project is exactly a stranger on every intake route: the same exact
     * not-found as an unknown key for reading R's item and for commenting on it, and for filing the one a stranger
     * gets; its lists are empty.
     */
    private static void assertAgentIsAStranger( final Fx fx, final String token, final String key, final String who )
            throws Exception {
        final HttpResponse< String > ghost = bearer( token, "GET", "/api/work/intake/items/" + UNKNOWN_KEY, null );
        assertSameNotFound( ghost, ghost, who + " reads an unknown key" );
        assertSameNotFound( bearer( token, "GET", "/api/work/intake/items/" + key, null ), ghost, who + " reads R's item" );
        assertSameNotFound( bearer( token, "GET", "/api/work/intake/items/" + key, null ),
            fx.s.get( "/api/work/intake/items/" + key ), who + " reads R's item exactly as a stranger does" );
        assertSameNotFound( bearer( token, "POST", "/api/work/intake/items/" + key + "/comments", Map.of( "body", "agent says hi" ) ),
            bearer( token, "POST", "/api/work/intake/items/" + UNKNOWN_KEY + "/comments", Map.of( "body", "agent says hi" ) ),
            who + " comments on R's item" );
        final Map< String, Object > draft = Map.of( "projectKey", fx.project, "level", "task", "title", "Agent files", "body", "x" );
        assertSameNotFound( bearer( token, "POST", "/api/work/intake/items", draft ), fx.s.send( "POST", "/api/work/intake/items",
            draft ), who + " files into the project exactly as a stranger does" );
        assertEquals( "[]", bearer( token, "GET", "/api/work/intake/items", null ).body().replace( " ", "" ), who + ": no list" );
        assertEquals( "[]", bearer( token, "GET", "/api/work/intake/projects", null ).body().replace( " ", "" ), who + ": no projects" );
        assertEquals( 404, bearer( token, "GET", "/api/work/items/" + key, null ).statusCode(),
            who + " reads R's item through the ordinary route as a stranger does" );
    }

    @Test
    void anAgentTokenIsRefusedOnEveryIntakeRouteAndAReportersKeyReachesWhatTheirSessionReaches() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String key = file( fx.r, fx.project, "Credentials " + fx.tag, "body" ).get( "key" ).getAsString();

        // (i) an agent that is a MEMBER of the project (its own row; its sponsor is the admin): the same standing as any
        // member, so it reads the reduced intake view of R's item, which is a strict subset of what the ordinary item
        // route already gives it. Filing is the one thing the contract refuses an agent outright (403).
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + fx.project + "/members", Map.of( "principal", agent.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        assertEquals( 200, bearer( agent.token(), "GET", "/api/work/me", null ).statusCode(), "control: the token reaches work" );
        assertAgentGetsNothing( bearer( agent.token(), "GET", "/api/work/intake/projects", null ), "intake projects" );
        assertAgentGetsNothing( bearer( agent.token(), "GET", "/api/work/intake/items", null ), "intake list" );
        assertEquals( 200, bearer( agent.token(), "GET", "/api/work/items/" + key, null ).statusCode(),
            "control: a member agent reads R's item through the ordinary route, so the intake view adds nothing" );
        final HttpResponse< String > memberAgentItem = bearer( agent.token(), "GET", "/api/work/intake/items/" + key, null );
        assertEquals( 200, memberAgentItem.statusCode(), "(i) a member agent reads the intake view: " + memberAgentItem.body() );
        assertNone( memberAgentItem.body(), MEMBER_ONLY_KEYS, "(i) the member agent's intake view" );
        assertNone( memberAgentItem.body(), fx.markers, "(i) the member agent's intake view carries no M1 data" );
        final HttpResponse< String > agentFile = bearer( agent.token(), "POST", "/api/work/intake/items", Map.of(
            "projectKey", fx.project, "level", "task", "title", "Agent files", "body", "x" ) );
        assertEquals( 403, agentFile.statusCode(), "(i) a member agent files nothing through intake: " + agentFile.body() );

        // (ii) an agent with no row in the project at all: a stranger on every intake route
        final WorkIt.Agent outsider = WorkIt.registerAgent( admin, "intkout" + fx.tag + "@it" );
        assertAgentIsAStranger( fx, outsider.token(), key, "(ii) an agent with no row in the project" );

        // (iii) an agent with its own member row whose sponsor holds ONLY the reporter role: the sponsor ceiling leaves it
        // no standing, and an agent is never a reporter, so it is a stranger too
        final String sponsorLogin = "intkspon" + fx.tag;
        final Caller sponsor = WorkIt.account( admin, sponsorLogin, "Intk Sponsor " + fx.tag );
        role( fx.project, sponsorLogin, "lead" );
        final HttpResponse< String > registered = sponsor.send( "POST", "/api/work/agents", Map.of( "name",
            "intkspn" + fx.tag + "@it", "vendor", "it", "runtime", "it", "queues", List.of() ) );
        assertEquals( 201, registered.statusCode(), "a lead registers an agent: " + WorkIt.status( registered ) );
        final String sponsoredName = "intkspn" + fx.tag + "@it";
        final String sponsoredToken = json( registered ).getAsJsonObject().get( "token" ).getAsString();
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + fx.project + "/members", Map.of( "principal", sponsoredName,
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        role( fx.project, sponsorLogin, "reporter" );
        assertAgentIsAStranger( fx, sponsoredToken, key, "(iii) an agent whose sponsor is only a reporter" );

        // (iv) an agent named like the reporter: the registry refuses the name while the person exists, so the agent
        // cannot be built; the service-level case (a row, a reporter sponsor, the reporter's name) is pinned in the
        // wikantik-work tests (anAgentNamedLikeTheReporterCannotSeeTheirItem)
        final HttpResponse< String > named = admin.send( "POST", "/api/work/agents", Map.of( "name", fx.rLogin,
            "vendor", "it", "runtime", "it", "queues", List.of() ) );
        assertEquals( 400, named.statusCode(), "(iv) an agent cannot be named like the reporter: " + named.body() );
        assertEquals( "name", json( named ).getAsJsonObject().get( "field" ).getAsString(), named.body() );

        final String after = fx.r.get( "/api/work/intake/items/" + key ).body();
        assertFalse( after.contains( agent.name() ), "no agent left anything on R's item" );
        assertFalse( after.contains( outsider.name() ), "no agent left anything on R's item" );
        assertFalse( after.contains( sponsoredName ), "no agent left anything on R's item" );
        assertEquals( 0, json2( after ).getAsJsonArray( "comments" ).size() );
        assertEquals( List.of( key ), keysOf( json( fx.r.get( "/api/work/intake/items" ) ).getAsJsonArray() ) );

        // R's own work key: what the session reaches, no more
        final String workKey = mintWorkKey( fx.r, "intk-" + fx.tag );
        final HttpResponse< String > viaSession = fx.r.get( "/api/work/intake/items/" + key );
        final HttpResponse< String > viaKey = bearer( workKey, "GET", "/api/work/intake/items/" + key, null );
        assertEquals( 200, viaKey.statusCode(), viaKey.body() );
        assertEquals( viaSession.body(), viaKey.body(), "the key reads exactly what the session reads" );
        assertEquals( fx.r.get( "/api/work/intake/projects" ).body(), bearer( workKey, "GET", "/api/work/intake/projects", null ).body() );
        final HttpResponse< String > ghost = fx.s.get( "/api/work/items/" + UNKNOWN_KEY );
        assertSameNotFound( bearer( workKey, "GET", "/api/work/items/" + fx.m1, null ), ghost, "M1 with the reporter's key" );
        assertSameNotFound( bearer( workKey, "GET", "/api/work/items/" + key, null ), ghost, "their item through the member route" );
        assertSameNotFound( bearer( workKey, "GET", "/api/work/projects/" + fx.project + "/board", null ),
            fx.s.get( "/api/work/projects/ZZZZZZ/board" ), "the board with the key" );
        assertEquals( fx.s.get( "/api/work/items?projects=" + fx.project ).body(),
            bearer( workKey, "GET", "/api/work/items?projects=" + fx.project, null ).body(), "find with the key is a stranger's" );
        final HttpResponse< String > viaKeyFile = bearer( workKey, "POST", "/api/work/intake/items", Map.of(
            "projectKey", fx.project, "level", "task", "title", "Filed with a key " + fx.tag, "body", "x" ) );
        assertEquals( 201, viaKeyFile.statusCode(), viaKeyFile.body() );
        assertEquals( 201, bearer( workKey, "POST", "/api/work/intake/items/" + key + "/comments", Map.of( "body", "by key" ) )
            .statusCode() );
        assertEquals( 2, keysOf( json( fx.r.get( "/api/work/intake/items" ) ).getAsJsonArray() ).size(),
            "the key's filing is R's own" );

        // member management refuses the reporter role for an agent, and the agent keeps the role it had
        final HttpResponse< String > refused = admin.send( "PUT", "/api/work/projects/" + fx.project + "/members", Map.of(
            "principal", agent.name(), "principalKind", "agent", "role", "reporter" ) );
        assertEquals( 400, refused.statusCode(), refused.body() );
        assertEquals( "role", json( refused ).getAsJsonObject().get( "field" ).getAsString(), refused.body() );
        boolean stillMember = false;
        for ( final JsonElement m : json( fx.lead.get( "/api/work/projects/" + fx.project + "/members" ) ).getAsJsonArray() ) {
            if ( "agent".equals( m.getAsJsonObject().get( "principalKind" ).getAsString() ) ) {
                stillMember = "member".equals( m.getAsJsonObject().get( "role" ).getAsString() );
            }
        }
        assertTrue( stillMember, "the agent's row was not changed by the refused request" );
    }

    // ================================================================ a stranger on the intake routes

    @Test
    void aStrangerGetsNotFoundOnEveryIntakeRoute() throws Exception {
        final Fx fx = base();
        loadM1( fx );
        final String key = file( fx.r, fx.project, "Stranger probe " + fx.tag, "body" ).get( "key" ).getAsString();

        // lists are empty, not an error
        assertEquals( "[]", fx.s.get( "/api/work/intake/projects" ).body().replace( " ", "" ) );
        assertEquals( "[]", fx.s.get( "/api/work/intake/items" ).body().replace( " ", "" ) );
        final HttpResponse< String > me = fx.s.get( "/api/work/me" );
        assertEquals( 0, json( me ).getAsJsonObject().getAsJsonArray( "reportable" ).size() );

        // every keyed route answers a real item, M1, a project key and a key that never existed identically
        final HttpResponse< String > ghost = fx.s.get( "/api/work/intake/items/" + UNKNOWN_KEY );
        assertEquals( 404, ghost.statusCode(), ghost.body() );
        for ( final String k : List.of( key, fx.m1, fx.project, fx.epic, "not-a-key", "" + fx.sprintId ) ) {
            assertSameNotFound( fx.s.get( "/api/work/intake/items/" + k ), ghost, "GET intake item " + k );
            assertSameNotFound( comment( fx.s, k, "hello" ), comment( fx.s, UNKNOWN_KEY, "hello" ), "comment on " + k );
        }
        assertSameNotFound( fx.s.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project, "level", "task",
            "title", "t", "body", "b" ) ), fx.s.send( "POST", "/api/work/intake/items", Map.of( "projectKey", "ZZZ" + fx.tagUpper.substring( 0, 5 ),
            "level", "task", "title", "t", "body", "b" ) ), "filing into P" );
        assertNone( fx.s.get( "/api/work/intake/items/" + key ).body(), fx.forbiddenEverywhere(), "the stranger's answers" );

        // an anonymous caller is only asked to sign in
        final Caller anonymous = new Caller();
        assertEquals( 401, anonymous.get( "/api/work/intake/projects" ).statusCode() );
        assertEquals( 401, anonymous.get( "/api/work/intake/items/" + key ).statusCode() );
        final int anonymousPost = anonymous.send( "POST", "/api/work/intake/items", Map.of( "projectKey", fx.project ) ).statusCode();
        assertTrue( anonymousPost == 401 || anonymousPost == 403, "an anonymous filing is refused: " + anonymousPost );

        // the viewer and the member are read by the same rules: both may file, neither reads a reporter's intake item
        // as a reporter would, and both see R's item on the normal routes
        assertEquals( 200, fx.viewer.get( "/api/work/items/" + key ).statusCode() );
        assertEquals( 200, fx.member.get( "/api/work/items/" + key ).statusCode() );
    }
}
