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
import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Agent;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deployments over the REST API, end to end: a deployment goes from requested to verified; an agent requests and
 * a person approves; a member of one carried project sees only that project's items; the deploy script's check
 * says no before approval and yes after; and an item under an "in production" epic closes only once a
 * deployment that carried it is verified. Projects DPA and DPB; product depprod (a person approves).
 */
public class WorkDeploymentApiIT {

    private static Caller admin;
    private static Caller onlyA;
    private static Agent agent;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        onlyA = WorkIt.account( admin, "wdeponlya", "Wdep Only A" );
        for ( final String key : List.of( "DPA", "DPB" ) ) {
            assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", key, "name", "Deploy " + key ) ).statusCode() );
        }
        assertEquals( 201, admin.send( "POST", "/api/work/products", Map.of( "key", "depprod", "name", "Deploy product",
            "defaultBranch", "main" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", "dep-impl", "name", "Deploy queue" ) ).statusCode() );
        agent = WorkIt.registerAgent( admin, "depagent@it", "dep-impl" );
        for ( final String key : List.of( "DPA", "DPB" ) ) {
            assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + key + "/members", Map.of( "principal",
                "depagent@it", "principalKind", "agent", "role", "member" ) ).statusCode() );
        }
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/DPA/members", Map.of( "principal", "wdeponlya",
            "principalKind", "user", "role", "member" ) ).statusCode() );
    }

    private static JsonObject ok( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), WorkIt.status( r ) );
        return json( r ).getAsJsonObject();
    }

    private static HttpResponse< String > asAgent( final String method, final String path, final Object body )
            throws Exception {
        return WorkIt.bearer( agent.token(), method, path, body == null ? null : WorkIt.GSON.toJson( body ) );
    }

    private static JsonObject itemOf( final JsonObject view ) {
        return view.has( "item" ) ? view.getAsJsonObject( "item" ) : view;
    }

    private static String newItem( final String project, final String title, final String parentKey, final String level )
            throws Exception {
        final Map< String, Object > draft = new java.util.HashMap<>( Map.of( "project", project, "level", level,
            "title", title ) );
        if ( !"epic".equals( level ) ) {
            draft.put( "productKey", "depprod" );
        }
        if ( parentKey != null ) {
            draft.put( "parentKey", parentKey );
        }
        final HttpResponse< String > r = admin.send( "POST", "/api/work/items", draft );
        assertEquals( 201, r.statusCode(), r.body() );
        return itemOf( json( r ).getAsJsonObject() ).get( "key" ).getAsString();
    }

    private static int version( final String key ) throws Exception {
        return itemOf( ok( admin.get( "/api/work/items/" + key ) ) ).get( "version" ).getAsInt();
    }

    private static HttpResponse< String > close( final String key ) throws Exception {
        return admin.send( "PATCH", "/api/work/items/" + key, Map.of( "expectedVersion", version( key ),
            "changes", Map.of( "status", "done" ) ) );
    }

    private static long request( final HttpResponse< String > r ) {
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().get( "id" ).getAsLong();
    }

    /** Runs bin/work-deploy-gate.sh against this server with the agent's token; returns its exit code. */
    private static int gateScript( final long deploymentId, final String version ) throws Exception {
        Path root = Path.of( "" ).toAbsolutePath();
        while ( root != null && !Files.exists( root.resolve( "bin/work-deploy-gate.sh" ) ) ) {
            root = root.getParent();
        }
        final ProcessBuilder pb = new ProcessBuilder( root.resolve( "bin/work-deploy-gate.sh" ).toString(),
            String.valueOf( deploymentId ), version );
        pb.environment().put( "WORK_API_URL", WorkIt.baseUrl() );
        pb.environment().put( "WORK_API_TOKEN", agent.token() );
        pb.redirectErrorStream( true );
        final Process p = pb.start();
        final String out = new String( p.getInputStream().readAllBytes(), StandardCharsets.UTF_8 );
        assertTrue( p.waitFor( 60, TimeUnit.SECONDS ), "the gate script finished" );
        assertFalse( out.contains( agent.token() ), "the script never prints the token" );
        return p.exitValue();
    }

    @Test
    void anAgentRequestsAPersonApprovesAndTheScriptFollows() throws Exception {
        final String a = newItem( "DPA", "Ship A", null, "task" );
        final long id = request( asAgent( "POST", "/api/work/deployments", Map.of( "product", "depprod", "version",
            "3.1.0", "items", List.of( a ) ) ) );
        assertEquals( 1, gateScript( id, "3.1.0" ), "not approved yet" );

        final HttpResponse< String > byAgent = asAgent( "POST", "/api/work/deployments/" + id + "/approve", null );
        assertEquals( 403, byAgent.statusCode(), byAgent.body() );
        assertEquals( "A person approves deployments of this product.",
            json( byAgent ).getAsJsonObject().get( "message" ).getAsString() );
        final JsonArray awaiting = ok( admin.get( "/api/work/me" ) ).getAsJsonArray( "deploymentsAwaiting" );
        assertTrue( awaiting.toString().contains( "\"id\":" + id ), "it waits for the admin: " + awaiting );

        ok( admin.send( "POST", "/api/work/deployments/" + id + "/approve", Map.of( "note", "go" ) ) );
        assertEquals( 0, gateScript( id, "3.1.0" ), "approved" );
        assertEquals( 1, gateScript( id, "3.1.1" ), "another version is not what was approved" );
        assertEquals( 2, gateScript( 987654321L, "3.1.0" ), "an unknown deployment cannot be asked about" );

        ok( asAgent( "POST", "/api/work/deployments/" + id + "/start", null ) );
        final HttpResponse< String > bare = asAgent( "POST", "/api/work/deployments/" + id + "/verify", Map.of() );
        assertEquals( 400, bare.statusCode(), bare.body() );
        assertEquals( "evidence", json( bare ).getAsJsonObject().get( "field" ).getAsString() );
        final JsonObject verified = ok( asAgent( "POST", "/api/work/deployments/" + id + "/verify",
            Map.of( "evidence", "GET /api/health 200\nSMOKE OK" ) ) );
        assertEquals( "verified", verified.get( "state" ).getAsString() );
        assertEquals( "GET /api/health 200\nSMOKE OK", verified.get( "evidence" ).getAsString() );
        assertEquals( 1, gateScript( id, "3.1.0" ), "a finished deployment is not cleared to run again" );

        final JsonArray history = ok( admin.get( "/api/work/deployments/" + id + "/history" ) ).getAsJsonArray( "history" );
        assertEquals( 4, history.size() );
        assertEquals( "depagent@it", history.get( 0 ).getAsJsonObject().get( "actorName" ).getAsString() );
        final JsonArray shipped = ok( admin.get( "/api/work/items/" + a + "/shipped-in" ) ).getAsJsonArray( "shippedIn" );
        assertEquals( "3.1.0", shipped.get( 0 ).getAsJsonObject().get( "version" ).getAsString() );
    }

    @Test
    void aMemberOfOneProjectSeesOnlyItsItems() throws Exception {
        final String a = newItem( "DPA", "Visible to A", null, "task" );
        final String b = newItem( "DPB", "Hidden from A", null, "task" );
        final long id = request( admin.send( "POST", "/api/work/deployments", Map.of( "product", "depprod", "version",
            "3.2.0", "items", List.of( a, b ) ) ) );
        final HttpResponse< String > seen = onlyA.get( "/api/work/deployments/" + id );
        assertEquals( 200, seen.statusCode(), seen.body() );
        final JsonArray items = json( seen ).getAsJsonObject().getAsJsonArray( "items" );
        assertEquals( 1, items.size() );
        assertEquals( a, items.get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertFalse( seen.body().contains( "DPB" ), "nothing of the other project: " + seen.body() );
        assertFalse( seen.body().contains( "Hidden from A" ), seen.body() );
        assertFalse( json( seen ).getAsJsonObject().get( "mayRun" ).getAsBoolean() );
        assertEquals( 403, onlyA.send( "POST", "/api/work/deployments/" + id + "/start", null ).statusCode() );

        final long hidden = request( admin.send( "POST", "/api/work/deployments", Map.of( "product", "depprod",
            "version", "3.2.1", "items", List.of( b ) ) ) );
        final HttpResponse< String > real = onlyA.get( "/api/work/deployments/" + hidden );
        final HttpResponse< String > none = onlyA.get( "/api/work/deployments/987654321" );
        assertEquals( 404, real.statusCode() );
        assertEquals( none.body(), real.body(), "a hidden deployment and one that does not exist are one answer" );
        final JsonArray listed = ok( onlyA.get( "/api/work/deployments?product=depprod" ) ).getAsJsonArray( "deployments" );
        assertFalse( listed.toString().contains( "\"id\":" + hidden + "," ), "the hidden one is not listed" );
    }

    @Test
    void anInProductionItemClosesOnlyAfterVerification() throws Exception {
        final String epic = newItem( "DPA", "Production incidents", null, "epic" );
        ok( admin.send( "PATCH", "/api/work/items/" + epic, Map.of( "expectedVersion", version( epic ),
            "changes", Map.of( "doneMeans", "in_production" ) ) ) );
        final String story = newItem( "DPA", "Indexer stalled", epic, "story" );
        final HttpResponse< String > early = close( story );
        assertEquals( 409, early.statusCode(), early.body() );
        assertEquals( "awaiting_deployment", json( early ).getAsJsonObject().get( "reason" ).getAsString() );

        final long id = request( admin.send( "POST", "/api/work/deployments", Map.of( "product", "depprod", "version",
            "3.3.0", "items", List.of( story ) ) ) );
        ok( admin.send( "POST", "/api/work/deployments/" + id + "/approve", null ) );
        ok( admin.send( "POST", "/api/work/deployments/" + id + "/start", null ) );
        assertEquals( 409, close( story ).statusCode(), "running is not verified" );
        ok( admin.send( "POST", "/api/work/deployments/" + id + "/verify", Map.of( "evidence", "health 200" ) ) );
        assertEquals( "done", itemOf( ok( close( story ) ) ).get( "status" ).getAsString() );

        final HttpResponse< String > back = admin.send( "POST", "/api/work/deployments/" + id + "/approve", null );
        assertEquals( 409, back.statusCode(), back.body() );
        assertEquals( "bad_transition", json( back ).getAsJsonObject().get( "reason" ).getAsString() );
    }
}
