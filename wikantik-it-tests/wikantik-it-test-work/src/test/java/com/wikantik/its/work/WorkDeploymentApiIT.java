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
package com.wikantik.its.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.its.work.WorkIt.Agent;
import com.wikantik.its.work.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.wikantik.its.work.WorkIt.json;
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
        final JsonObject made = itemOf( json( r ).getAsJsonObject() );
        // a deployment carries work that is ready or later, never a draft: a person's task or story starts ready
        assertEquals( "epic".equals( level ) ? "draft" : "ready", made.get( "status" ).getAsString() );
        return made.get( "key" ).getAsString();
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
    void aRequestCanBeWithdrawnAndIsThenNeverCleared() throws Exception {
        final String a = newItem( "DPA", "Requested by mistake", null, "task" );
        final long id = request( asAgent( "POST", "/api/work/deployments", Map.of( "product", "depprod", "version",
            "3.4.0", "items", List.of( a ) ) ) );
        final JsonObject withdrawn = ok( asAgent( "POST", "/api/work/deployments/" + id + "/reject",
            Map.of( "note", "wrong version" ) ) );
        assertEquals( "rejected", withdrawn.get( "state" ).getAsString() );
        assertEquals( 1, gateScript( id, "3.4.0" ) );
        final HttpResponse< String > late = admin.send( "POST", "/api/work/deployments/" + id + "/approve", null );
        assertEquals( 409, late.statusCode(), late.body() );
        assertFalse( ok( admin.get( "/api/work/me" ) ).getAsJsonArray( "deploymentsAwaiting" ).toString()
            .contains( "\"id\":" + id + "," ), "it no longer waits for anyone" );

        final String draft = itemOf( json( admin.send( "POST", "/api/work/items", Map.of( "project", "DPA", "level",
            "task", "title", "Still a draft", "productKey", "depprod" ) ) ).getAsJsonObject() ).get( "key" ).getAsString();
        // a person's new task starts ready; move it back to draft
        ok( admin.send( "PATCH", "/api/work/items/" + draft, Map.of( "expectedVersion", version( draft ), "changes",
            Map.of( "status", "draft" ) ) ) );
        final HttpResponse< String > unready = admin.send( "POST", "/api/work/deployments", Map.of( "product",
            "depprod", "version", "3.4.1", "items", List.of( draft ) ) );
        assertEquals( 400, unready.statusCode(), unready.body() );
        assertEquals( "items", json( unready ).getAsJsonObject().get( "field" ).getAsString() );
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

    // ---- fix-forward (Part 6): the "remediates" field

    /** A ready task of {@code project} that targets {@code product}; returns its key. */
    private static String itemFor( final String project, final String product, final String title ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/items", Map.of( "project", project, "level", "task",
            "title", title, "productKey", product ) );
        assertEquals( 201, r.statusCode(), r.body() );
        return itemOf( json( r ).getAsJsonObject() ).get( "key" ).getAsString();
    }

    /** Requests (as the admin), approves and starts a deployment of one new item; returns its id, running. */
    private static long running( final String project, final String product, final String environment, final String version )
            throws Exception {
        final Map< String, Object > body = new java.util.HashMap<>( Map.of( "product", product, "version", version,
            "items", List.of( itemFor( project, product, "Fix-forward " + version ) ) ) );
        if ( environment != null ) {
            body.put( "environment", environment );
        }
        final long id = request( admin.send( "POST", "/api/work/deployments", body ) );
        ok( admin.send( "POST", "/api/work/deployments/" + id + "/approve", Map.of( "note", "go" ) ) );
        ok( admin.send( "POST", "/api/work/deployments/" + id + "/start", null ) );
        return id;
    }

    /** A deployment that ran and failed; returns its id. */
    private static long failed( final String project, final String product, final String environment, final String version )
            throws Exception {
        final long id = running( project, product, environment, version );
        assertEquals( "failed", ok( admin.send( "POST", "/api/work/deployments/" + id + "/fail", Map.of( "note", "boom" ) ) )
            .get( "state" ).getAsString() );
        return id;
    }

    private static HttpResponse< String > requestFix( final Caller who, final String product, final String version,
                                                      final String itemKey, final Object remediates ) throws Exception {
        final Map< String, Object > body = new java.util.HashMap<>( Map.of( "product", product, "version", version,
            "items", List.of( itemKey ) ) );
        body.put( "remediates", remediates );
        return who.send( "POST", "/api/work/deployments", body );
    }

    private static boolean remediatesNothing( final JsonObject deployment ) {
        return !deployment.has( "remediatesId" ) || deployment.get( "remediatesId" ).isJsonNull();
    }

    private static String fieldOf( final HttpResponse< String > r ) {
        return json( r ).getAsJsonObject().get( "field" ).getAsString();
    }

    private static String messageOf( final HttpResponse< String > r ) {
        return json( r ).getAsJsonObject().get( "message" ).getAsString();
    }

    @Test
    void aFailedOrVerifiedDeploymentCanBeRemediatedAndTheFixNamesIt() throws Exception {
        final String tag = Long.toString( System.nanoTime(), 36 );
        final long broken = failed( "DPA", "depprod", null, "6.0." + tag );
        final HttpResponse< String > fixed = requestFix( admin, "depprod", "6.0." + tag + "-fix",
            itemFor( "DPA", "depprod", "Fix " + tag ), broken );
        final long fixId = request( fixed );
        assertEquals( broken, json( fixed ).getAsJsonObject().get( "remediatesId" ).getAsLong() );
        assertEquals( broken, ok( admin.get( "/api/work/deployments/" + fixId ) ).get( "remediatesId" ).getAsLong(),
            "a read of the fix names it too" );
        assertTrue( remediatesNothing( ok( admin.get( "/api/work/deployments/" + broken ) ) ),
            "the failed deployment itself remediates nothing" );

        // the message says verified deployments can be remediated as well (a verified one that is then rolled back)
        ok( admin.send( "POST", "/api/work/deployments/" + fixId + "/approve", Map.of( "note", "go" ) ) );
        ok( admin.send( "POST", "/api/work/deployments/" + fixId + "/start", null ) );
        ok( admin.send( "POST", "/api/work/deployments/" + fixId + "/verify", Map.of( "evidence", "health 200" ) ) );
        final HttpResponse< String > again = requestFix( admin, "depprod", "6.0." + tag + "-fix2",
            itemFor( "DPA", "depprod", "Fix again " + tag ), fixId );
        assertEquals( fixId, json( again ).getAsJsonObject().get( "remediatesId" ).getAsLong() );
        assertEquals( 201, again.statusCode(), again.body() );
    }

    @Test
    void remediatesRefusalsNameTheFieldAndAHiddenTargetIsOneAnswerWithAnUnknownOne() throws Exception {
        final String tag = Long.toString( System.nanoTime(), 36 );
        final String other = "depother" + tag;
        assertEquals( 201, admin.send( "POST", "/api/work/products", Map.of( "key", other, "name", "Other " + tag,
            "defaultBranch", "main" ) ).statusCode() );
        final long broken = failed( "DPA", "depprod", null, "6.1." + tag );
        final String fixItem = itemFor( "DPA", "depprod", "Refused fix " + tag );

        // not a deployment number
        for ( final Object bad : new Object[] { "abc", 0, -3, 1.5 } ) {
            final HttpResponse< String > r = requestFix( admin, "depprod", "6.1." + tag + "-a", fixItem, bad );
            assertEquals( 400, r.statusCode(), bad + ": " + r.body() );
            assertEquals( "remediates", fieldOf( r ), bad + ": " + r.body() );
        }

        // a deployment that has not ended cannot be remediated
        final long waiting = request( admin.send( "POST", "/api/work/deployments", Map.of( "product", "depprod",
            "version", "6.1." + tag + "-w", "items", List.of( itemFor( "DPA", "depprod", "Still waiting " + tag ) ) ) ) );
        final HttpResponse< String > notEnded = requestFix( admin, "depprod", "6.1." + tag + "-b", fixItem, waiting );
        assertEquals( 400, notEnded.statusCode(), notEnded.body() );
        assertEquals( "remediates", fieldOf( notEnded ) );
        assertEquals( "Only a deployment that was verified, failed or rolled back can be remediated.", messageOf( notEnded ) );

        // another product's failure
        final long otherFailed = failed( "DPA", other, null, "6.1." + tag + "-o" );
        final HttpResponse< String > wrongProduct = requestFix( admin, "depprod", "6.1." + tag + "-c", fixItem, otherFailed );
        assertEquals( 400, wrongProduct.statusCode(), wrongProduct.body() );
        assertEquals( "remediates", fieldOf( wrongProduct ) );
        assertEquals( "A remediating deployment fixes an earlier deployment of the same product and environment.",
            messageOf( wrongProduct ) );

        // the same product's failure in another environment (the new request is production)
        final long staging = failed( "DPA", "depprod", "staging", "6.1." + tag + "-s" );
        final HttpResponse< String > wrongEnvironment = requestFix( admin, "depprod", "6.1." + tag + "-d", fixItem, staging );
        assertEquals( 400, wrongEnvironment.statusCode(), wrongEnvironment.body() );
        assertEquals( "remediates", fieldOf( wrongEnvironment ) );
        assertEquals( messageOf( wrongProduct ), messageOf( wrongEnvironment ) );

        // a failure carrying only DPB items, asked about by a member of DPA only, is the answer for an unknown id
        final long hidden = failed( "DPB", "depprod", null, "6.1." + tag + "-h" );
        final String visibleItem = itemFor( "DPA", "depprod", "Visible to A " + tag );
        final HttpResponse< String > seenAsHidden = requestFix( onlyA, "depprod", "6.1." + tag + "-e", visibleItem, hidden );
        final HttpResponse< String > seenAsGhost = requestFix( onlyA, "depprod", "6.1." + tag + "-e", visibleItem, 987654321L );
        assertEquals( 404, seenAsGhost.statusCode(), seenAsGhost.body() );
        assertEquals( seenAsGhost.statusCode(), seenAsHidden.statusCode(), "same status: " + seenAsHidden.body() );
        assertEquals( seenAsGhost.body(), seenAsHidden.body(), "same message: a hidden deployment is not confirmed" );
        // and a visible failure is accepted for the same caller, so the refusal above was about the target
        assertEquals( 201, requestFix( onlyA, "depprod", "6.1." + tag + "-f", visibleItem, broken ).statusCode() );
        // ASSUMED: a request cannot name itself (it has no id yet); the table CHECK is covered by the Java schema test
    }
}
