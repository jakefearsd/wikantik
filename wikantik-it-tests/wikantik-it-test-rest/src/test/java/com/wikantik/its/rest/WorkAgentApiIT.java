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
import com.wikantik.its.rest.WorkIt.Agent;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agents over the REST API with bearer keys, end to end: the four hand-off directions and the log they leave, the
 * rule that an agent sees only the projects it was admitted to (even when its key's owner is an admin), the loss of
 * access (rotated key, retired agent, removed or demoted membership), the scope of a key, and a non-admin member's
 * view (no logins, no key ids). Project {@code AGT} is the working project, {@code HID} the one the agents are not in.
 */
public class WorkAgentApiIT {

    private static final String CODEX = "codex@it";
    private static final String CLAUDE = "claude@it";
    private static final String A_LOGIN = "wagpersona";
    private static final String B_LOGIN = "wagpersonb";
    private static final String A_NAME = "Wag Person A";
    private static final String B_NAME = "Wag Person B";

    private static Caller admin;
    private static Caller personA;
    private static Caller personB;
    private static Agent codex;
    private static Agent claude;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        personA = WorkIt.account( admin, A_LOGIN, A_NAME );
        personB = WorkIt.account( admin, B_LOGIN, B_NAME );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "AGT", "name", "Agents" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "HID", "name", "Hidden" ) ).statusCode() );
        for ( final String q : List.of( "implementer", "reviewer" ) ) {
            assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", q, "name", "Queue " + q ) ).statusCode() );
        }
        codex = WorkIt.registerAgent( admin, CODEX, "implementer" );
        claude = WorkIt.registerAgent( admin, CLAUDE, "reviewer" );
        admit( "AGT", CODEX, "member" );
        admit( "AGT", CLAUDE, "member" );
        for ( final String login : List.of( A_LOGIN, B_LOGIN ) ) {
            assertEquals( 204, admin.send( "PUT", "/api/work/projects/AGT/members", Map.of( "principal", login,
                "principalKind", "user", "role", "member" ) ).statusCode() );
        }
    }

    // ---- helpers

    private static void admit( final String project, final String agentName, final String role ) throws Exception {
        final HttpResponse< String > r = admin.send( "PUT", "/api/work/projects/" + project + "/members",
            Map.of( "principal", agentName, "principalKind", "agent", "role", role ) );
        assertEquals( 204, r.statusCode(), "admit " + agentName + ": " + r.body() );
    }

    private static JsonObject ok( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), WorkIt.status( r ) );
        return json( r ).getAsJsonObject();
    }

    private static JsonObject itemOf( final JsonObject view ) {
        return view.has( "item" ) ? view.getAsJsonObject( "item" ) : view;
    }

    private static String keyOf( final JsonObject view ) {
        return itemOf( view ).get( "key" ).getAsString();
    }

    private static int versionOf( final JsonObject view ) {
        return itemOf( view ).get( "version" ).getAsInt();
    }

    /** A draft epic made by the admin; ready in the queue when {@code queue} is given. */
    private static JsonObject newItem( final String project, final String title, final String queue ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/items", Map.of( "project", project,
            "level", "task", "title", title ) );
        assertEquals( 201, r.statusCode(), r.body() );
        JsonObject view = json( r ).getAsJsonObject();
        if ( queue != null ) {
            view = ok( admin.send( "POST", "/api/work/items/" + keyOf( view ) + "/handoff", Map.of(
                "expectedVersion", versionOf( view ), "status", "ready", "queue", queue, "note", "to the queue" ) ) );
        }
        return view;
    }

    private static HttpResponse< String > asAgent( final Agent a, final String method, final String path, final Object body )
            throws Exception {
        return WorkIt.bearer( a.token(), method, path, body == null ? null : WorkIt.GSON.toJson( body ) );
    }

    private static JsonObject claim( final Agent a, final String itemKey ) throws Exception {
        return ok( asAgent( a, "POST", "/api/work/items/" + itemKey + "/claim", null ) );
    }

    private static JsonArray events( final Caller who, final String itemKey ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/items/" + itemKey + "/events" );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonArray();
    }

    private static JsonObject itemNow( final String itemKey ) throws Exception {
        return itemOf( ok( admin.get( "/api/work/items/" + itemKey ) ) );
    }

    private static List< String > types( final JsonArray events ) {
        final List< String > out = new ArrayList<>();
        for ( final JsonElement e : events ) {
            out.add( e.getAsJsonObject().get( "type" ).getAsString() );
        }
        return out;
    }

    private static JsonObject eventOfType( final JsonArray events, final String type, final int nth ) {
        int seen = 0;
        for ( final JsonElement e : events ) {
            if ( type.equals( e.getAsJsonObject().get( "type" ).getAsString() ) && seen++ == nth ) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError( "no " + type + " #" + nth + " in " + events );
    }

    private static void assertActor( final JsonObject event, final String kind, final String name ) {
        assertEquals( kind, event.get( "actorKind" ).getAsString(), event.toString() );
        assertEquals( name, event.get( "actorName" ).getAsString(), event.toString() );
    }

    private static String columnOf( final String project, final String itemKey ) throws Exception {
        final JsonArray board = json( admin.get( "/api/work/projects/" + project + "/board" ) ).getAsJsonArray();
        for ( final JsonElement c : board ) {
            for ( final JsonElement i : c.getAsJsonObject().getAsJsonArray( "items" ) ) {
                if ( itemKey.equals( i.getAsJsonObject().get( "key" ).getAsString() ) ) {
                    return c.getAsJsonObject().get( "status" ).getAsString();
                }
            }
        }
        return null;
    }

    private static JsonObject waitingFor( final Caller who, final String itemKey ) throws Exception {
        for ( final JsonElement e : ok( who.get( "/api/work/me" ) ).getAsJsonArray( "waitingOn" ) ) {
            if ( itemKey.equals( e.getAsJsonObject().get( "key" ).getAsString() ) ) {
                return e.getAsJsonObject().getAsJsonObject( "waiting" );
            }
        }
        throw new AssertionError( itemKey + " is not waiting on the caller" );
    }

    // ---- (C) the four hand-off directions and the log they leave

    @Test
    void fourHandoffDirectionsOverRest() throws Exception {
        // person -> agent: A creates a draft and hands it to the implementer queue
        final HttpResponse< String > created = personA.send( "POST", "/api/work/items", Map.of( "project", "AGT",
            "level", "task", "title", "Four directions" ) );
        assertEquals( 201, created.statusCode(), created.body() );
        final JsonObject draft = json( created ).getAsJsonObject();
        final String key = keyOf( draft );
        final JsonObject queued = ok( personA.send( "POST", "/api/work/items/" + key + "/handoff", Map.of(
            "expectedVersion", versionOf( draft ), "status", "ready", "queue", "implementer", "note", "please build" ) ) );
        assertEquals( "ready", itemOf( queued ).get( "status" ).getAsString() );
        assertEquals( "ready", columnOf( "AGT", key ) );
        // positive control for the filter below: the item is claimable by codex (queue implementer) and not by claude
        assertTrue( asAgent( codex, "GET", "/api/work/claimable?project=AGT", null ).body().contains( key ) );
        assertFalse( asAgent( claude, "GET", "/api/work/claimable?project=AGT", null ).body().contains( key ) );

        final JsonObject claimed = claim( codex, key );
        assertFalse( claimed.get( "renewed" ).getAsBoolean() );
        assertEquals( "in_progress", itemOf( claimed ).get( "status" ).getAsString() );
        assertEquals( CODEX, itemOf( claimed ).get( "claimedByName" ).getAsString() );
        assertEquals( "in_progress", columnOf( "AGT", key ) );

        // agent -> agent: codex hands to the reviewer queue; claude takes it
        final JsonObject toReview = ok( asAgent( codex, "POST", "/api/work/items/" + key + "/handoff", Map.of(
            "expectedVersion", versionOf( claimed ), "status", "review", "queue", "reviewer", "note", "built, please review" ) ) );
        assertTrue( itemOf( toReview ).get( "claimedByAgent" ).isJsonNull(), "the claim ended with the hand-off" );
        assertEquals( "review", columnOf( "AGT", key ) );
        final JsonObject taken = claim( claude, key );
        assertEquals( CLAUDE, itemOf( taken ).get( "claimedByName" ).getAsString() );
        assertEquals( "review", itemOf( taken ).get( "status" ).getAsString(), "a review item is claimed where it is" );

        // agent -> person: claude hands to B
        ok( asAgent( claude, "POST", "/api/work/items/" + key + "/handoff", Map.of(
            "expectedVersion", versionOf( taken ), "status", "in_progress", "assignee", B_NAME, "note", "needs a human" ) ) );
        final JsonObject waiting = waitingFor( personB, key );
        assertEquals( CLAUDE, waiting.get( "byName" ).getAsString() );
        assertEquals( "agent", waiting.get( "byKind" ).getAsString() );
        assertEquals( "assigned", waiting.get( "reason" ).getAsString() );
        assertEquals( "needs a human", waiting.get( "note" ).getAsString() );

        // person -> person: B hands to A
        final JsonObject bView = itemOf( ok( personB.get( "/api/work/items/" + key ) ) );
        ok( personB.send( "POST", "/api/work/items/" + key + "/handoff", Map.of( "expectedVersion",
            bView.get( "version" ).getAsInt(), "status", "review", "assignee", A_NAME, "note", "over to you" ) ) );
        final JsonObject waitingA = waitingFor( personA, key );
        assertEquals( B_NAME, waitingA.get( "byName" ).getAsString() );
        assertEquals( "human", waitingA.get( "byKind" ).getAsString() );

        // the complete ordered log, on the item and in the project activity
        final JsonArray log = events( admin, key );
        final List< String > sequence = types( log );
        sequence.remove( "created" );
        assertEquals( List.of( "handoff", "claimed", "handoff", "released", "claimed", "handoff", "released", "handoff" ),
            sequence, log.toString() );
        final List< JsonObject > flow = new ArrayList<>();
        for ( final JsonElement e : log ) {
            if ( !"created".equals( e.getAsJsonObject().get( "type" ).getAsString() ) ) {
                flow.add( e.getAsJsonObject() );
            }
        }
        assertActor( flow.get( 0 ), "human", A_NAME );
        assertEquals( "ready|queue:implementer", flow.get( 0 ).get( "toValue" ).getAsString() );
        assertEquals( "please build", flow.get( 0 ).get( "note" ).getAsString() );
        assertActor( flow.get( 1 ), "agent", CODEX );
        assertEquals( "in_progress", flow.get( 1 ).get( "toValue" ).getAsString() );
        assertActor( flow.get( 2 ), "agent", CODEX );
        assertEquals( "review|queue:reviewer", flow.get( 2 ).get( "toValue" ).getAsString() );
        assertActor( flow.get( 3 ), "agent", CODEX );
        assertEquals( "agent:" + codex.id(), flow.get( 3 ).get( "fromValue" ).getAsString() );
        assertEquals( CODEX, flow.get( 3 ).get( "fromValueName" ).getAsString() );
        assertEquals( "review", flow.get( 3 ).get( "toValue" ).getAsString() );
        assertEquals( "reassigned", flow.get( 3 ).get( "note" ).getAsString() );
        assertActor( flow.get( 4 ), "agent", CLAUDE );
        assertActor( flow.get( 5 ), "agent", CLAUDE );
        assertEquals( "needs a human", flow.get( 5 ).get( "note" ).getAsString() );
        assertActor( flow.get( 6 ), "agent", CLAUDE );
        assertEquals( "agent:" + claude.id(), flow.get( 6 ).get( "fromValue" ).getAsString() );
        assertEquals( "reassigned", flow.get( 6 ).get( "note" ).getAsString() );
        assertActor( flow.get( 7 ), "human", B_NAME );
        assertEquals( "over to you", flow.get( 7 ).get( "note" ).getAsString() );

        final JsonArray activity = json( personA.get( "/api/work/activity?project=AGT&item=" + key + "&limit=200" ) ).getAsJsonArray();
        final List< String > fromActivity = types( activity );
        fromActivity.remove( "created" );
        final List< String > reversed = new ArrayList<>( fromActivity );
        java.util.Collections.reverse( reversed );
        assertTrue( sequence.equals( fromActivity ) || sequence.equals( reversed ),
            "the project activity shows the same log: " + activity );
        for ( final String name : List.of( CODEX, CLAUDE, A_NAME, B_NAME ) ) {
            assertTrue( activity.toString().contains( name ), "activity names " + name );
        }
        assertEquals( 8, flow.size() );
    }

    // ---- (B4) visibility, REST

    private record Pair( String real, String ghost ) {
    }

    @Test
    void agentSeesOnlyAdmittedProjects() throws Exception {
        final String hiddenKey = keyOf( newItem( "HID", "Hidden item", null ) );
        final String openKey = keyOf( newItem( "AGT", "Open item", "implementer" ) );
        final String ghostKey = "GHOST-" + hiddenKey.substring( hiddenKey.lastIndexOf( '-' ) + 1 );

        final List< Pair > pairs = List.of(
            new Pair( "/api/work/projects/HID", "/api/work/projects/GHOST" ),
            new Pair( "/api/work/projects/HID/board", "/api/work/projects/GHOST/board" ),
            new Pair( "/api/work/projects/HID/members", "/api/work/projects/GHOST/members" ),
            new Pair( "/api/work/items?project=HID", "/api/work/items?project=GHOST" ),
            new Pair( "/api/work/items/" + hiddenKey, "/api/work/items/" + ghostKey ),
            new Pair( "/api/work/items/" + hiddenKey + "/events", "/api/work/items/" + ghostKey + "/events" ),
            new Pair( "/api/work/items/" + hiddenKey + "/revisions", "/api/work/items/" + ghostKey + "/revisions" ),
            new Pair( "/api/work/activity?project=HID", "/api/work/activity?project=GHOST" ),
            new Pair( "/api/work/claimable?project=HID", "/api/work/claimable?project=GHOST" ) );
        for ( final Pair p : pairs ) {
            // positive control: the admin session reads the real path
            assertEquals( 200, admin.get( p.real() ).statusCode(), "the real path is readable: " + p.real() );
            for ( final Agent a : List.of( codex, claude ) ) {
                final HttpResponse< String > real = asAgent( a, "GET", p.real(), null );
                final HttpResponse< String > ghost = asAgent( a, "GET", p.ghost(), null );
                assertTrue( real.statusCode() == 404 || real.statusCode() == 200 && real.body().equals( "[]" ),
                    p.real() + " for " + a.name() + ": " + real.statusCode() + " " + real.body() );
                assertEquals( ghost.statusCode(), real.statusCode(), p.real() );
                assertEquals( ghost.body(), real.body(), "byte-identical to the nonexistent project: " + p.real() );
            }
        }
        // writes on a hidden item read like writes on a nonexistent one
        for ( final String action : List.of( "claim", "release" ) ) {
            final HttpResponse< String > real = asAgent( codex, "POST", "/api/work/items/" + hiddenKey + "/" + action, null );
            final HttpResponse< String > ghost = asAgent( codex, "POST", "/api/work/items/" + ghostKey + "/" + action, null );
            assertEquals( 404, real.statusCode() );
            assertEquals( ghost.body(), real.body() );
        }
        // the project list shows the admitted project and omits the other, for a key whose owner sees both
        final String list = asAgent( codex, "GET", "/api/work/projects", null ).body();
        assertTrue( list.contains( "\"AGT\"" ), list );
        assertFalse( list.contains( "\"HID\"" ), list );
        assertTrue( admin.get( "/api/work/projects" ).body().contains( "\"HID\"" ), "the owner's own session sees it" );
        // and admitted reads work for the agent
        assertEquals( 200, asAgent( codex, "GET", "/api/work/items/" + openKey, null ).statusCode() );
    }

    // ---- (B5) losing access

    @Test
    void rotatedKeyStopsWorkingImmediately() throws Exception {
        final Agent rot = WorkIt.registerAgent( admin, "rot@it", "implementer" );
        admit( "AGT", rot.name(), "member" );
        assertEquals( 200, asAgent( rot, "GET", "/api/work/me", null ).statusCode(), "the key works before rotation" );
        final HttpResponse< String > rotated = admin.send( "POST", "/api/work/agents/" + rot.id() + "/rotate-key", null );
        assertEquals( 200, rotated.statusCode() );
        assertEquals( "no-store", rotated.headers().firstValue( "Cache-Control" ).orElse( "" ) );
        final String fresh = json( rotated ).getAsJsonObject().get( "token" ).getAsString();
        assertNotEquals( rot.token(), fresh );
        final HttpResponse< String > old = asAgent( rot, "GET", "/api/work/me", null );
        assertEquals( 401, old.statusCode() );
        assertEquals( "{\"error\":\"Invalid API key\"}", old.body() );
        assertEquals( 200, WorkIt.bearer( fresh, "GET", "/api/work/me", null ).statusCode(), "the new key works" );
    }

    @Test
    void retiredAgentKeyIsRefusedAndItsItemReturns() throws Exception {
        final Agent ret = WorkIt.registerAgent( admin, "ret@it", "implementer" );
        admit( "AGT", ret.name(), "member" );
        final String key = keyOf( newItem( "AGT", "Held by a retiree", "implementer" ) );
        claim( ret, key );
        assertEquals( "in_progress", itemNow( key ).get( "status" ).getAsString() );
        assertEquals( 204, admin.send( "POST", "/api/work/agents/" + ret.id() + "/retire", null ).statusCode() );
        final HttpResponse< String > refused = asAgent( ret, "GET", "/api/work/me", null );
        assertEquals( 401, refused.statusCode() );
        assertEquals( "{\"error\":\"Invalid API key\"}", refused.body() );
        final JsonObject item = itemNow( key );
        assertEquals( "ready", item.get( "status" ).getAsString() );
        assertEquals( "implementer", item.get( "queue" ).getAsString() );
        assertTrue( item.get( "claimedByAgent" ).isJsonNull(), item.toString() );
        final JsonObject released = eventOfType( events( admin, key ), "released", 0 );
        assertEquals( "agent retired", released.get( "note" ).getAsString() );
        assertEquals( "agent:" + ret.id(), released.get( "fromValue" ).getAsString() );
        // positive control: another agent can claim it again
        assertEquals( 200, asAgent( codex, "POST", "/api/work/items/" + key + "/claim", null ).statusCode() );
    }

    @Test
    void removedAgentLosesTheProjectAndItsClaim() throws Exception {
        final Agent gone = WorkIt.registerAgent( admin, "gone@it", "implementer" );
        admit( "AGT", gone.name(), "member" );
        final String key = keyOf( newItem( "AGT", "Held by one removed", "implementer" ) );
        claim( gone, key );
        assertEquals( 200, asAgent( gone, "GET", "/api/work/projects/AGT", null ).statusCode(), "control: member sees AGT" );
        assertEquals( 204, admin.send( "DELETE", "/api/work/projects/AGT/members", Map.of( "principal", gone.name(),
            "principalKind", "agent" ) ).statusCode() );
        assertEquals( 404, asAgent( gone, "GET", "/api/work/projects/AGT", null ).statusCode() );
        assertEquals( 404, asAgent( gone, "GET", "/api/work/items/" + key, null ).statusCode() );
        final JsonObject item = itemNow( key );
        assertEquals( "ready", item.get( "status" ).getAsString() );
        assertTrue( item.get( "claimedByAgent" ).isJsonNull(), item.toString() );
        assertEquals( "access removed", eventOfType( events( admin, key ), "released", 0 ).get( "note" ).getAsString() );
    }

    @Test
    void demotedAgentCanReadButNotClaim() throws Exception {
        final Agent dem = WorkIt.registerAgent( admin, "dem@it", "implementer" );
        admit( "AGT", dem.name(), "member" );
        final String held = keyOf( newItem( "AGT", "Held by one demoted", "implementer" ) );
        final String other = keyOf( newItem( "AGT", "Offered to one demoted", "implementer" ) );
        claim( dem, held );
        admit( "AGT", dem.name(), "viewer" );
        assertEquals( 200, asAgent( dem, "GET", "/api/work/items/" + held, null ).statusCode(), "a viewer reads" );
        final HttpResponse< String > refused = asAgent( dem, "POST", "/api/work/items/" + other + "/claim", null );
        assertEquals( 403, refused.statusCode(), refused.body() );
        assertTrue( itemNow( other ).get( "claimedByAgent" ).isJsonNull() );
        final JsonObject item = itemNow( held );
        assertEquals( "ready", item.get( "status" ).getAsString() );
        assertTrue( item.get( "claimedByAgent" ).isJsonNull(), item.toString() );
        assertEquals( "access removed", eventOfType( events( admin, held ), "released", 0 ).get( "note" ).getAsString() );
        // control: a member agent claims the same item
        assertEquals( 200, asAgent( codex, "POST", "/api/work/items/" + other + "/claim", null ).statusCode() );
    }

    // ---- scope, stale, sweep

    @Test
    void mcpReadKeyIs403OnWorkApi() throws Exception {
        final HttpResponse< String > minted = admin.send( "POST", "/api/self/apikeys", Map.of( "label", "wag-read", "scope", "mcp_read" ) );
        assertEquals( 201, minted.statusCode() );
        final String readToken = json( minted ).getAsJsonObject().get( "token" ).getAsString();
        final HttpResponse< String > refused = WorkIt.bearer( readToken, "GET", "/api/work/me", null );
        assertEquals( 403, refused.statusCode() );
        assertEquals( "{\"error\":\"Key not authorized for work\"}", refused.body() );
        assertEquals( 200, asAgent( codex, "GET", "/api/work/me", null ).statusCode(), "control: a work key is accepted" );
        assertEquals( 401, WorkIt.bearer( "wkk_not-a-key", "GET", "/api/work/me", null ).statusCode() );
    }

    @Test
    void staleHandoffIs409() throws Exception {
        final JsonObject item = newItem( "AGT", "Stale hand-off", "implementer" );
        final int current = versionOf( item );
        final HttpResponse< String > stale = admin.send( "POST", "/api/work/items/" + keyOf( item ) + "/handoff", Map.of(
            "expectedVersion", current - 1, "status", "review", "queue", "reviewer", "note", "late" ) );
        assertEquals( 409, stale.statusCode(), stale.body() );
        final JsonObject body = json( stale ).getAsJsonObject();
        assertEquals( "stale", body.get( "reason" ).getAsString() );
        assertTrue( body.has( "current" ), body.toString() );
        // control: the same hand-off at the right version goes through
        assertEquals( 200, admin.send( "POST", "/api/work/items/" + keyOf( item ) + "/handoff", Map.of(
            "expectedVersion", current, "status", "review", "queue", "reviewer", "note", "on time" ) ).statusCode() );
    }

    @Test
    void nobodyCanTriggerTheSweepFromOutside() throws Exception {
        final String key = keyOf( newItem( "AGT", "Sweep probe", "implementer" ) );
        // a route that does exist answers for the same caller (control), the guessed sweep routes do not
        assertEquals( 200, asAgent( codex, "GET", "/api/work/claimable", null ).statusCode() );
        for ( final String path : List.of( "/api/work/claims/sweep", "/api/work/claims/expire", "/api/work/sweep",
                "/api/work/items/" + key + "/sweep" ) ) {
            assertEquals( 404, asAgent( codex, "POST", path, null ).statusCode(), "agent POST " + path );
            assertEquals( 404, admin.send( "POST", path, null ).statusCode(), "admin POST " + path );
        }
    }

    // ---- (B8) no logins or key ids to a non-admin member

    @Test
    void nonAdminSeesNoLoginsAndNoKeyIds() throws Exception {
        final Agent holder = WorkIt.registerAgent( admin, "leak@it", "implementer" );
        admit( "AGT", holder.name(), "member" );
        final String key = keyOf( newItem( "AGT", "Leak probe", "implementer" ) );
        claim( holder, key );
        assertEquals( 201, asAgent( holder, "POST", "/api/work/items/" + key + "/comments", Map.of( "body", "agent says hi" ) ).statusCode() );
        // a login-bearing event: the admin (login janne) assigns it to person A, who then hands it to B
        final JsonObject now = ok( admin.get( "/api/work/items/" + key ) );
        ok( admin.send( "POST", "/api/work/items/" + key + "/handoff", Map.of( "expectedVersion", versionOf( now ),
            "status", "ready", "assignee", A_NAME, "note", "assigned by admin" ) ) );
        final JsonObject mine = ok( personA.get( "/api/work/items/" + key ) );
        ok( personA.send( "POST", "/api/work/items/" + key + "/handoff", Map.of( "expectedVersion", versionOf( mine ),
            "status", "ready", "assignee", B_NAME, "note", "over" ) ) );
        final String keyId = admin.get( "/api/work/agents" ).body();
        assertTrue( keyId.contains( "apiKeyId" ), "positive control: an admin sees key ids" );

        final List< String > paths = List.of( "/api/work/queues", "/api/work/agents", "/api/work/claimable?project=AGT",
            "/api/work/items/" + key, "/api/work/items/" + key + "/events", "/api/work/activity?project=AGT&limit=500",
            "/api/work/me", "/api/work/projects/AGT/members", "/api/work/projects/AGT/board" );
        for ( final String path : paths ) {
            final HttpResponse< String > r = personB.get( path );
            assertEquals( 200, r.statusCode(), path + ": " + r.body() );
            for ( final String secret : List.of( A_LOGIN, B_LOGIN, "janne", "apiKeyId" ) ) {
                assertFalse( r.body().contains( secret ), path + " shows " + secret + ": " + r.body() );
            }
        }
        // positive control: the admin's view of the same events and members does show logins
        final String adminSees = admin.get( "/api/work/activity?project=AGT&limit=500" ).body()
            + admin.get( "/api/work/projects/AGT/members" ).body();
        assertTrue( adminSees.contains( A_LOGIN ) || adminSees.contains( "janne" ), "admin sees logins: " + adminSees );
        assertTrue( personB.get( "/api/work/activity?project=AGT&limit=500" ).body().contains( A_NAME ),
            "the display name is shown instead" );
    }
}
