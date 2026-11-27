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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flow and DORA figures (Part 6) over the REST API, end to end: {@code GET /api/work/projects/{key}/flow},
 * {@code /dora} and {@code GET /api/work/products/{key}/dora}. Checked against the JSON shapes in the Java lane
 * report, not against any frontend.
 *
 * <p>Every method builds its own projects, product, accounts and items (a per-method random tag in each key and
 * login), takes every key and id from the server's answers, and computes every figure it asserts from rows it made
 * itself, in a project and product no other method uses. No method depends on another.
 *
 * <p>An IT cannot set the server's clock, so what the history makes with "now" times is asserted only as counts,
 * ratios, booleans, the presence and unit of fields and bounds ("at least the pause the test slept, at most the
 * test's own elapsed time plus slack"). Exact values are asserted only where the inputs carry their own times: the
 * code-host deliveries (fixed instants on 2026-01-10, a window around them) and the first-commit times given when a
 * change is opened. The default window is the last 90 days ending today in the server's zone; a test that runs
 * across local midnight would still be inside it.
 *
 * <p>Anything not derivable from the Java lane report is marked {@code // ASSUMED:}.
 */
public class WorkFlowApiIT {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient PLAIN = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).build();
    private static final String REPO = "acme/flow";
    /** A window around the fixed code-host instants (2026-01-10 10:xxZ), wide enough for any server zone. */
    private static final String JAN = "from=2026-01-09&to=2026-01-11";
    private static final long DAY = 86_400L;

    private static Caller admin;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
    }

    // ---------------------------------------------------------------- fixtures and small helpers

    private static String tag() {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < 6; i++ ) {
            sb.append( ( char ) ( 'a' + RANDOM.nextInt( 26 ) ) );
        }
        return sb.toString();
    }

    private static String word( final String tag ) {
        return Character.toUpperCase( tag.charAt( 0 ) ) + tag.substring( 1 );
    }

    private static String project( final String prefix, final String tag ) throws Exception {
        final String key = prefix + tag.toUpperCase( Locale.ROOT );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", key, "name", "Flow " + key ) ).statusCode() );
        return key;
    }

    private static String product( final String tag ) throws Exception {
        final String key = "flowprod" + tag;
        assertEquals( 201, admin.send( "POST", "/api/work/products", Map.of( "key", key, "name", "Flow product " + tag,
            "defaultBranch", "main" ) ).statusCode() );
        return key;
    }

    private static void member( final String project, final String login, final String kind, final String role ) throws Exception {
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + project + "/members", Map.of( "principal", login,
            "principalKind", kind, "role", role ) ).statusCode() );
    }

    private static JsonObject ok( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), WorkIt.status( r ) );
        return json( r ).getAsJsonObject();
    }

    private static JsonObject itemOf( final JsonObject view ) {
        return view.has( "item" ) ? view.getAsJsonObject( "item" ) : view;
    }

    /** A task of {@code project}, targeting {@code product} when given; a person's task starts ready. Returns its key. */
    private static String item( final String project, final String product, final String title, final List< String > labels )
            throws Exception {
        final Map< String, Object > draft = new HashMap<>( Map.of( "project", project, "level", "task", "title", title ) );
        if ( product != null ) {
            draft.put( "productKey", product );
        }
        if ( labels != null ) {
            draft.put( "labels", labels );
        }
        final HttpResponse< String > r = admin.send( "POST", "/api/work/items", draft );
        assertEquals( 201, r.statusCode(), r.body() );
        return itemOf( json( r ).getAsJsonObject() ).get( "key" ).getAsString();
    }

    private static int version( final String key ) throws Exception {
        return itemOf( ok( admin.get( "/api/work/items/" + key ) ) ).get( "version" ).getAsInt();
    }

    private static void patch( final String key, final Map< String, Object > changes ) throws Exception {
        final HttpResponse< String > r = admin.send( "PATCH", "/api/work/items/" + key,
            Map.of( "expectedVersion", version( key ), "changes", changes ) );
        assertEquals( 200, r.statusCode(), key + " " + changes + ": " + r.body() );
    }

    private static void move( final String key, final String status ) throws Exception {
        patch( key, Map.of( "status", status ) );
    }

    private static void pause() throws Exception {
        Thread.sleep( 1_100 );   // whole seconds are the unit: a figure that must be positive needs a full second
    }

    /** Seconds the test has been running, plus room for the server's own slowness. */
    private static long slack( final long startedAtMillis ) {
        return ( System.currentTimeMillis() - startedAtMillis ) / 1000 + 120;
    }

    // ---- deployments through the real API (the admin is a person, so every gate lets them)

    private static long requestDeployment( final String product, final String environment, final String version,
                                           final Long remediates, final String... itemKeys ) throws Exception {
        final Map< String, Object > body = new HashMap<>( Map.of( "product", product, "version", version,
            "items", List.of( itemKeys ) ) );
        if ( environment != null ) {
            body.put( "environment", environment );
        }
        if ( remediates != null ) {
            body.put( "remediates", remediates );
        }
        final HttpResponse< String > r = admin.send( "POST", "/api/work/deployments", body );
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().get( "id" ).getAsLong();
    }

    private static void step( final long id, final String action, final Object body ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/deployments/" + id + "/" + action, body );
        assertEquals( 200, r.statusCode(), action + " " + id + ": " + r.body() );
    }

    /** Requested, approved, running. */
    private static long running( final String product, final String environment, final String version, final Long remediates,
                                 final String... itemKeys ) throws Exception {
        final long id = requestDeployment( product, environment, version, remediates, itemKeys );
        step( id, "approve", Map.of( "note", "go" ) );
        step( id, "start", null );
        return id;
    }

    private static long verified( final String product, final String environment, final String version, final Long remediates,
                                  final String... itemKeys ) throws Exception {
        final long id = running( product, environment, version, remediates, itemKeys );
        step( id, "verify", Map.of( "evidence", "health 200 " + version ) );
        return id;
    }

    private static long failed( final String product, final String version, final String... itemKeys ) throws Exception {
        final long id = running( product, null, version, null, itemKeys );
        step( id, "fail", Map.of( "note", "boom" ) );
        return id;
    }

    // ---- reading the figures

    private static JsonObject flow( final Caller who, final String project, final String query ) throws Exception {
        return ok( who.get( "/api/work/projects/" + project + "/flow" + ( query == null ? "" : "?" + query ) ) );
    }

    private static JsonObject dora( final Caller who, final String project, final String query ) throws Exception {
        return ok( who.get( "/api/work/projects/" + project + "/dora" + ( query == null ? "" : "?" + query ) ) );
    }

    private static JsonObject productDora( final String product, final String query ) throws Exception {
        return ok( admin.get( "/api/work/products/" + product + "/dora" + ( query == null ? "" : "?" + query ) ) );
    }

    private static int sum( final JsonArray rows, final String field ) {
        int total = 0;
        for ( final JsonElement e : rows ) {
            total += e.getAsJsonObject().get( field ).getAsInt();
        }
        return total;
    }

    private static double sumPoints( final JsonArray rows ) {
        double total = 0;
        for ( final JsonElement e : rows ) {
            total += e.getAsJsonObject().get( "points" ).getAsDouble();
        }
        return total;
    }

    private static void assertNoNumberTrouble( final String body ) {
        assertFalse( body.contains( "NaN" ) || body.contains( "Infinity" ), "no NaN or Infinity anywhere: " + body );
    }

    private static void assertNoDurations( final JsonObject d, final String what ) {
        assertEquals( 0, d.get( "n" ).getAsInt(), what );
        assertTrue( d.get( "p50Seconds" ).isJsonNull(), what + " p50 is null" );
        assertTrue( d.get( "p85Seconds" ).isJsonNull(), what + " p85 is null" );
    }

    private static void assertRate( final JsonObject dora, final String name, final int numerator, final int denominator ) {
        final JsonObject r = dora.getAsJsonObject( name );
        assertEquals( numerator, r.get( "numerator" ).getAsInt(), name + " numerator: " + r );
        assertEquals( denominator, r.get( "denominator" ).getAsInt(), name + " denominator: " + r );
    }

    // ================================================================ empty window

    @Test
    void anEmptyWindowHasTheDocumentedShapeAndNoNaN() throws Exception {
        final String tag = tag();
        final String project = project( "FE", tag );
        final String product = product( tag );

        final HttpResponse< String > rawFlow = admin.get( "/api/work/projects/" + project + "/flow?" + JAN );
        final JsonObject f = ok( rawFlow );
        assertNoNumberTrouble( rawFlow.body() );
        assertEquals( "2026-01-09", f.get( "from" ).getAsString() );
        assertEquals( "2026-01-11", f.get( "to" ).getAsString() );
        assertFalse( f.get( "zone" ).getAsString().isBlank(), "the zone the weeks are cut in is named" );
        final JsonArray weeks = f.getAsJsonArray( "throughput" );
        assertEquals( 1, weeks.size(), "Fri 9 to Sun 11 January is one Monday-based week: " + weeks );
        assertEquals( "2026-01-05", weeks.get( 0 ).getAsJsonObject().get( "weekStart" ).getAsString() );
        assertEquals( 0, weeks.get( 0 ).getAsJsonObject().get( "items" ).getAsInt() );
        assertEquals( 0, weeks.get( 0 ).getAsJsonObject().get( "points" ).getAsDouble() );
        assertNoDurations( f.getAsJsonObject( "cycleTime" ), "cycle time" );
        assertNoDurations( f.getAsJsonObject( "leadTime" ), "lead time" );
        final JsonArray wip = f.getAsJsonArray( "wip" );
        assertEquals( 2, wip.size() );
        assertEquals( "in_progress", wip.get( 0 ).getAsJsonObject().get( "status" ).getAsString() );
        assertEquals( "review", wip.get( 1 ).getAsJsonObject().get( "status" ).getAsString() );
        for ( final JsonElement w : wip ) {
            assertEquals( 0, w.getAsJsonObject().get( "count" ).getAsInt() );
            assertEquals( 0, w.getAsJsonObject().getAsJsonArray( "items" ).size() );
        }

        for ( final HttpResponse< String > r : List.of( admin.get( "/api/work/projects/" + project + "/dora?" + JAN ),
                admin.get( "/api/work/products/" + product + "/dora?" + JAN ) ) ) {
            final JsonObject d = ok( r );
            assertNoNumberTrouble( r.body() );
            final JsonArray freq = d.getAsJsonArray( "deploymentFrequency" );
            assertEquals( 1, freq.size() );
            assertEquals( "2026-01-05", freq.get( 0 ).getAsJsonObject().get( "weekStart" ).getAsString() );
            assertEquals( 0, freq.get( 0 ).getAsJsonObject().get( "deployments" ).getAsInt() );
            assertNoDurations( d.getAsJsonObject( "leadTimeForChanges" ), "lead time for changes" );
            assertEquals( 0, d.get( "changesWithoutCommitTime" ).getAsInt() );
            assertEquals( 0, d.get( "changesWithClampedCommitTime" ).getAsInt() );
            assertEquals( 30, d.get( "maxCommitLeadDays" ).getAsInt() );
            assertRate( d, "changeFailureRate", 0, 0 );
            assertNoDurations( d.getAsJsonObject( "recoveryTime" ), "recovery time" );
            assertEquals( 0, d.get( "unrecovered" ).getAsInt() );
            assertRate( d, "reworkRate", 0, 0 );
            final JsonObject merges = d.getAsJsonObject( "merges" );
            for ( final String k : List.of( "reviewed", "unreviewed", "direct", "flagged", "flaggedUnacknowledged" ) ) {
                assertEquals( 0, merges.get( k ).getAsInt(), k );
            }
        }
        assertTrue( dora( admin, project, JAN ).get( "product" ).isJsonNull(), "a project report with no product filter names none" );
        assertEquals( product, productDora( product, JAN ).get( "product" ).getAsString() );

        // no parameters at all: the last 90 days, every week present and empty
        final HttpResponse< String > rawDefault = admin.get( "/api/work/projects/" + project + "/dora" );
        final JsonObject defaults = ok( rawDefault );
        assertNoNumberTrouble( rawDefault.body() );
        final int rows = defaults.getAsJsonArray( "deploymentFrequency" ).size();
        assertTrue( rows >= 13 && rows <= 14, "90 days touch 13 or 14 Mondays: " + rows );
        assertEquals( 0, sum( defaults.getAsJsonArray( "deploymentFrequency" ), "deployments" ) );
    }

    // ================================================================ flow

    @Test
    void flowFiguresFollowTheItemsThatWereMoved() throws Exception {
        final long started = System.currentTimeMillis();
        final String tag = tag();
        final String project = project( "FF", tag );
        final String label = "flw" + tag;

        // e1 and e2: in progress, then done (a pause between, so the cycle time is positive); e1 is labelled
        final String e1 = item( project, null, "Flow one", List.of( label ) );
        final String e2 = item( project, null, "Flow two", null );
        // e3 stays in progress; e4 goes straight to done (never in progress); e5 is closed and reopened
        final String e3 = item( project, null, "Flow three", null );
        final String e4 = item( project, null, "Flow four", null );
        final String e5 = item( project, null, "Flow five", null );
        patch( e1, Map.of( "estimate", 3 ) );
        patch( e2, Map.of( "estimate", 1.5 ) );
        move( e1, "in_progress" );
        move( e2, "in_progress" );
        move( e3, "in_progress" );
        pause();
        move( e1, "done" );
        move( e2, "done" );
        move( e4, "done" );
        move( e5, "done" );
        move( e5, "ready" ); // ASSUMED: a person may reopen a done task (status moves are not restricted for tasks)

        final long bound = slack( started );
        final JsonObject f = flow( admin, project, null );
        assertEquals( 3, sum( f.getAsJsonArray( "throughput" ), "items" ), "e1, e2 and e4 are closed; e5 was reopened: " + f );
        assertEquals( 4.5, sumPoints( f.getAsJsonArray( "throughput" ) ), 0.0001, "3 + 1.5 + a missing estimate counted as 0" );
        final JsonObject cycle = f.getAsJsonObject( "cycleTime" );
        assertEquals( 2, cycle.get( "n" ).getAsInt(), "e4 was never in progress, e3 is not closed: " + cycle );
        assertTrue( cycle.get( "p50Seconds" ).getAsLong() >= 1 && cycle.get( "p50Seconds" ).getAsLong() <= bound, cycle.toString() );
        assertTrue( cycle.get( "p85Seconds" ).getAsLong() >= cycle.get( "p50Seconds" ).getAsLong() );
        final JsonObject lead = f.getAsJsonObject( "leadTime" );
        assertEquals( 3, lead.get( "n" ).getAsInt(), "e1, e2, e4: " + lead );
        assertTrue( lead.get( "p50Seconds" ).getAsLong() >= 0 && lead.get( "p50Seconds" ).getAsLong() <= bound, lead.toString() );
        assertTrue( lead.get( "p85Seconds" ).getAsLong() >= lead.get( "p50Seconds" ).getAsLong() );

        final JsonArray wip = f.getAsJsonArray( "wip" );
        final JsonObject inProgress = wip.get( 0 ).getAsJsonObject();
        assertEquals( "in_progress", inProgress.get( "status" ).getAsString() );
        assertEquals( 1, inProgress.get( "count" ).getAsInt(), "only e3 is still in progress: " + wip );
        final JsonObject aged = inProgress.getAsJsonArray( "items" ).get( 0 ).getAsJsonObject();
        assertEquals( e3, aged.get( "key" ).getAsString() );
        assertEquals( "Flow three", aged.get( "title" ).getAsString() );
        assertTrue( aged.get( "ageSeconds" ).getAsLong() >= 1 && aged.get( "ageSeconds" ).getAsLong() <= bound );
        assertEquals( 0, wip.get( 1 ).getAsJsonObject().get( "count" ).getAsInt(), "nothing is in review" );

        // the label filter is the items' current value: only e1 carries it
        final JsonObject labelled = flow( admin, project, "label=" + label );
        assertEquals( 1, sum( labelled.getAsJsonArray( "throughput" ), "items" ) );
        assertEquals( 3.0, sumPoints( labelled.getAsJsonArray( "throughput" ) ), 0.0001 );
        assertEquals( 1, labelled.getAsJsonObject( "cycleTime" ).get( "n" ).getAsInt() );
        assertEquals( 0, labelled.getAsJsonArray( "wip" ).get( 0 ).getAsJsonObject().get( "count" ).getAsInt() );

        // an unknown assignee is an empty report, not an error
        final JsonObject nobody = flow( admin, project, "assignee=Nobody%20Atall%20" + word( tag ) );
        assertEquals( 0, sum( nobody.getAsJsonObject().getAsJsonArray( "throughput" ), "items" ) );
        assertNoDurations( nobody.getAsJsonObject( "cycleTime" ), "cycle time of nobody" );

        // bad filters name their field; an unknown sprint is a 404
        final HttpResponse< String > badType = admin.get( "/api/work/projects/" + project + "/flow?type=nonsense" );
        assertEquals( 400, badType.statusCode(), badType.body() );
        assertEquals( "type", json( badType ).getAsJsonObject().get( "field" ).getAsString() );
        final HttpResponse< String > badSprint = admin.get( "/api/work/projects/" + project + "/flow?sprint=abc" );
        assertEquals( 400, badSprint.statusCode(), badSprint.body() );
        assertEquals( "sprint", json( badSprint ).getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( 404, admin.get( "/api/work/projects/" + project + "/flow?sprint=987654321" ).statusCode() );
    }

    // ================================================================ DORA: counted history

    @Test
    void doraCountsADeploymentThatWasRolledBackAFailureAndItsRemediation() throws Exception {
        final long started = System.currentTimeMillis();
        final String tag = tag();
        final String project = project( "FD", tag );
        final String product = product( tag );
        final String[] items = new String[ 6 ];
        for ( int i = 0; i < items.length; i++ ) {
            items[ i ] = item( project, product, "Dora item " + i, null );
        }

        // D1 verified; D2 verified then rolled back (Q11: still a verified deployment, and a failure); D3 failed
        verified( product, null, "1.0." + tag, null, items[ 0 ] );
        final long d2 = running( product, null, "1.1." + tag, null, items[ 1 ] );
        step( d2, "verify", Map.of( "evidence", "health 200" ) );
        pause();
        step( d2, "roll-back", Map.of( "note", "bad" ) );
        final long d3 = failed( product, "1.2." + tag, items[ 2 ] );
        // a staging deployment counts nowhere
        verified( product, "staging", "1.3." + tag, null, items[ 3 ] );

        JsonObject d = dora( admin, project, null );
        assertEquals( 2, sum( d.getAsJsonArray( "deploymentFrequency" ), "deployments" ),
            "D1 and D2 were verified in production (D2 was rolled back after; D3 never verified; staging never counts): " + d );
        assertRate( d, "changeFailureRate", 2, 3 ); // D1, D2, D3 finished; D2 and D3 failed
        assertRate( d, "reworkRate", 0, 3 );
        assertEquals( 1, d.getAsJsonObject( "recoveryTime" ).get( "n" ).getAsInt(), "D2's rollback ended its failure: " + d );
        assertEquals( 1, d.get( "unrecovered" ).getAsInt(), "D3 has no end yet: " + d );
        final long recovery = d.getAsJsonObject( "recoveryTime" ).get( "p50Seconds" ).getAsLong();
        assertTrue( recovery >= 1 && recovery <= slack( started ), "from live to rollback, at least the pause: " + recovery );

        // D4 verifies and names D3: D3's recovery is reported, nothing is unrecovered, and D4 is rework
        verified( product, null, "1.4." + tag, d3, items[ 4 ] );
        d = dora( admin, project, null );
        assertEquals( 3, sum( d.getAsJsonArray( "deploymentFrequency" ), "deployments" ), "D1, D2 and D4: " + d );
        assertRate( d, "changeFailureRate", 2, 4 );
        assertRate( d, "reworkRate", 1, 4 );
        assertEquals( 2, d.getAsJsonObject( "recoveryTime" ).get( "n" ).getAsInt(), d.toString() );
        assertEquals( 0, d.get( "unrecovered" ).getAsInt(), "a remediated failure is no longer unrecovered: " + d );
        assertTrue( d.getAsJsonObject( "recoveryTime" ).get( "p85Seconds" ).getAsLong()
            >= d.getAsJsonObject( "recoveryTime" ).get( "p50Seconds" ).getAsLong() );
        assertTrue( d.getAsJsonObject( "recoveryTime" ).get( "p85Seconds" ).getAsLong() <= slack( started ) );
        assertNoNumberTrouble( d.toString() );

        // the product's view over the same single project and the project narrowed to the product agree
        assertEquals( d.getAsJsonArray( "deploymentFrequency" ).toString(),
            productDora( product, null ).getAsJsonArray( "deploymentFrequency" ).toString() );
        assertRate( dora( admin, project, "product=" + product ), "changeFailureRate", 2, 4 );
        assertEquals( product, dora( admin, project, "product=" + product ).get( "product" ).getAsString() );
        assertEquals( 404, admin.get( "/api/work/projects/" + project + "/dora?product=nosuch" + tag ).statusCode() );
    }

    // ================================================================ DORA: Q10, a fix filed under another project

    @Test
    void aFixForwardFiledUnderAnotherProjectEndsTheClockAndRevealsNothingOfThatProject() throws Exception {
        final String tag = tag();
        final String projectA = project( "FA", tag );
        final String projectB = project( "FB", tag );
        final String product = product( tag );
        final String loginA = "fwlonea" + tag;
        final String loginB = "fwloneb" + tag;
        final Caller memberA = WorkIt.account( admin, loginA, "Flow A " + word( tag ) );
        final Caller memberB = WorkIt.account( admin, loginB, "Flow B " + word( tag ) );
        member( projectA, loginA, "user", "member" );
        member( projectB, loginB, "user", "member" );
        final String itemA = item( projectA, product, "Broken in A " + tag, null );
        final String itemB = item( projectB, product, "Fixed in B " + tag, null );
        final String versionA = "2.0." + tag;
        final String versionB = "2.1." + tag + "-bfix";

        final long broken = failed( product, versionA, itemA );
        JsonObject a = dora( memberA, projectA, null );
        assertEquals( 0, a.getAsJsonObject( "recoveryTime" ).get( "n" ).getAsInt(), "nothing has fixed it yet: " + a );
        assertEquals( 1, a.get( "unrecovered" ).getAsInt() );

        verified( product, null, versionB, broken, itemB );   // the fix carries only B's item

        a = dora( memberA, projectA, null );
        assertEquals( 1, a.getAsJsonObject( "recoveryTime" ).get( "n" ).getAsInt(), "B's fix ended A's failure: " + a );
        assertEquals( 0, a.get( "unrecovered" ).getAsInt() );
        assertEquals( 0, sum( a.getAsJsonArray( "deploymentFrequency" ), "deployments" ), "the fix does not carry A's items" );
        assertRate( a, "changeFailureRate", 1, 1 );
        assertRate( a, "reworkRate", 0, 1 );
        for ( final String secret : List.of( projectB, itemB, versionB, "Fixed in B", "Flow " + projectB ) ) {
            assertFalse( a.toString().contains( secret ), "A's member learns nothing of B: " + secret + " in " + a );
        }
        assertEquals( 404, memberA.get( "/api/work/projects/" + projectB + "/dora" ).statusCode() );

        // B sees its own deployment and nothing of A's failure
        final JsonObject b = dora( memberB, projectB, null );
        assertEquals( 1, sum( b.getAsJsonArray( "deploymentFrequency" ), "deployments" ) );
        assertRate( b, "changeFailureRate", 0, 1 );
        assertRate( b, "reworkRate", 1, 1 );
        assertEquals( 0, b.getAsJsonObject( "recoveryTime" ).get( "n" ).getAsInt() );
        for ( final String secret : List.of( projectA, itemA, versionA, "Broken in A", "Flow " + projectA ) ) {
            assertFalse( b.toString().contains( secret ), "B's member learns nothing of A: " + secret + " in " + b );
        }

        // the whole product, for an admin
        final JsonObject all = productDora( product, null );
        assertEquals( 1, sum( all.getAsJsonArray( "deploymentFrequency" ), "deployments" ) );
        assertRate( all, "changeFailureRate", 1, 2 );
        assertRate( all, "reworkRate", 1, 2 );
        assertEquals( 1, all.getAsJsonObject( "recoveryTime" ).get( "n" ).getAsInt() );
        assertEquals( 0, all.get( "unrecovered" ).getAsInt() );
    }

    // ================================================================ DORA: lead time from given first-commit times

    @Test
    void leadTimeStartsAtTheGivenFirstCommitAndIsCappedThirtyDaysBeforeOpening() throws Exception {
        final long started = System.currentTimeMillis();
        final String tag = tag();
        final String project = project( "FL", tag );
        final String product = product( tag );
        final String withCommit = item( project, product, "Five days", null );
        final String rebased = item( project, product, "Forty-five days", null );
        final String bare = item( project, product, "No commit time", null );
        final Instant now = Instant.now().truncatedTo( ChronoUnit.SECONDS );
        mergeAChange( withCommit, now.minus( 5, ChronoUnit.DAYS ).toString() );
        mergeAChange( rebased, now.minus( 45, ChronoUnit.DAYS ).toString() );
        mergeAChange( bare, null );
        verified( product, null, "3.0." + tag, null, withCommit, rebased, bare );

        final long bound = slack( started );
        final JsonObject d = dora( admin, project, null );
        final JsonObject lead = d.getAsJsonObject( "leadTimeForChanges" );
        assertEquals( 3, lead.get( "n" ).getAsInt(), d.toString() );
        assertEquals( 1, d.get( "changesWithoutCommitTime" ).getAsInt() );
        assertEquals( 1, d.get( "changesWithClampedCommitTime" ).getAsInt(), "only the 45-day commit is moved by the cap" );
        assertEquals( 30, d.get( "maxCommitLeadDays" ).getAsInt() );
        // sorted: bare (seconds), five days, thirty days (the 45-day commit capped); nearest rank: p50 is the 2nd, p85 the 3rd
        final long p50 = lead.get( "p50Seconds" ).getAsLong();
        final long p85 = lead.get( "p85Seconds" ).getAsLong();
        assertTrue( p50 >= 5 * DAY && p50 <= 5 * DAY + bound, "p50 is the five-day change: " + p50 );
        assertTrue( p85 >= 30 * DAY && p85 <= 30 * DAY + bound, "p85 is the capped change, not 45 days: " + p85 );
        assertEquals( 1, sum( d.getAsJsonArray( "deploymentFrequency" ), "deployments" ) );
    }

    /** Opens a change on the item (with a first-commit time when given) and merges it, as a person. */
    private static void mergeAChange( final String itemKey, final String firstCommitAt ) throws Exception {
        final Map< String, Object > body = new HashMap<>( Map.of( "branch", "work/" + itemKey + "-x", "headSha", "abc1234" ) );
        if ( firstCommitAt != null ) {
            body.put( "firstCommitAt", firstCommitAt );
        }
        final HttpResponse< String > opened = admin.send( "POST", "/api/work/items/" + itemKey + "/changes", body );
        assertEquals( 201, opened.statusCode(), opened.body() );
        final long id = json( opened ).getAsJsonObject().get( "id" ).getAsLong();
        final HttpResponse< String > merged = admin.send( "POST", "/api/work/changes/" + id + "/merge",
            Map.of( "mergedSha", "abc1234def" ) );
        assertEquals( 200, merged.statusCode(), merged.body() );
    }

    // ================================================================ DORA: merge mix from signed code-host deliveries

    @Test
    void theMergeMixCountsReviewedUnreviewedAndDirectMergesAndNeverASupersededRow() throws Exception {
        final String tag = tag();
        final String project = project( "FM", tag );
        final String product = product( tag );
        final String secret = enableHook( product );
        final String reviewer = "rev" + tag;
        final String user = "gh" + tag;

        // i1: opened, approved on its head, merged. reviewed
        final String i1 = item( project, product, "Reviewed one", null );
        final Pr p1 = pr( i1, user );
        deliver( product, secret, "pull_request", opened( p1, 1 ) );
        deliver( product, secret, "pull_request_review", approved( p1, reviewer, 2 ) );
        deliver( product, secret, "pull_request", merged( p1, reviewer, sha(), 3 ) );

        // i2: opened and merged with no approval. unreviewed, flagged
        final String i2 = item( project, product, "Unreviewed", null );
        final Pr p2 = pr( i2, user );
        deliver( product, secret, "pull_request", opened( p2, 1 ) );
        deliver( product, secret, "pull_request", merged( p2, user, sha(), 3 ) );

        // i3: a direct push to the default branch. direct, flagged (no direct commits allowed by default)
        final String i3 = item( project, product, "Direct", null );
        deliver( product, secret, "push", push( "main", sha(), sha(), t( 2 ),
            commit( sha(), i3 + " hotfix", t( 2 ), "A", "a@elsewhere.example", user, true ) ) );

        // i4: the merge commit reaches main (recorded as a direct row) before the pull request's merge replaces it.
        // The replaced direct row is superseded and counts nowhere: one reviewed merge, not a reviewed and a direct one
        final String i4 = item( project, product, "Merge push first", null );
        final Pr p4 = pr( i4, user );
        final String mergeSha = sha();
        // the push comes BEFORE the pull request is known: once the pull request is open, a push naming its number is a
        // duplicate of the merge to come and records no direct row, so nothing would be superseded
        deliver( product, secret, "push", push( "main", sha(), mergeSha, t( 3 ),
            commit( mergeSha, "Merge pull request #" + p4.number() + " from o/" + p4.branch(), t( 3 ), "M", null, reviewer, true ) ) );
        deliver( product, secret, "pull_request", opened( p4, 1 ) );
        deliver( product, secret, "pull_request_review", approved( p4, reviewer, 2 ) );
        deliver( product, secret, "pull_request", merged( p4, reviewer, mergeSha, 3 ) );
        final JsonArray i4Changes = ok( admin.get( "/api/work/items/" + i4 + "/changes" ) ).getAsJsonArray( "changes" );
        int superseded = 0;
        for ( final JsonElement c : i4Changes ) {
            superseded += c.getAsJsonObject().has( "supersededBy" ) && !c.getAsJsonObject().get( "supersededBy" ).isJsonNull() ? 1 : 0;
        }
        assertEquals( 1, superseded, "the direct row the merge replaced exists and is marked superseded: " + i4Changes );

        for ( final JsonObject d : List.of( dora( admin, project, JAN ), productDora( product, JAN ) ) ) {
            final JsonObject m = d.getAsJsonObject( "merges" );
            assertEquals( 2, m.get( "reviewed" ).getAsInt(), "i1 and i4: " + m );
            assertEquals( 1, m.get( "unreviewed" ).getAsInt(), "i2: " + m );
            assertEquals( 1, m.get( "direct" ).getAsInt(), "i3 only: i4's replaced row counts nowhere: " + m );
            assertEquals( 2, m.get( "flagged" ).getAsInt(), "i2 and i3: " + m );
            assertEquals( 2, m.get( "flaggedUnacknowledged" ).getAsInt(), m.toString() );
            assertNoDurations( d.getAsJsonObject( "leadTimeForChanges" ), "no deployment shipped any of them" );
        }

        // acknowledging i2's flag lowers only the unacknowledged count; the flagged count is of the window
        final long ackId = ok( admin.get( "/api/work/items/" + i2 + "/changes" ) ).getAsJsonArray( "changes" )
            .get( 0 ).getAsJsonObject().get( "id" ).getAsLong();
        assertEquals( 200, admin.send( "POST", "/api/work/changes/" + ackId + "/acknowledge-flag", null ).statusCode() );
        final JsonObject after = dora( admin, project, JAN ).getAsJsonObject( "merges" );
        assertEquals( 2, after.get( "flagged" ).getAsInt() );
        assertEquals( 1, after.get( "flaggedUnacknowledged" ).getAsInt() );

        // the same merges are outside any window that does not hold 10 January
        final JsonObject elsewhere = dora( admin, project, "from=2026-03-01&to=2026-03-31" ).getAsJsonObject( "merges" );
        assertEquals( 0, elsewhere.get( "reviewed" ).getAsInt() + elsewhere.get( "unreviewed" ).getAsInt()
            + elsewhere.get( "direct" ).getAsInt() );
    }

    // ================================================================ window validation

    @Test
    void aBadWindowIs400NamingTheFieldOnEveryRoute() throws Exception {
        final String tag = tag();
        final String project = project( "FV", tag );
        final String product = product( tag );
        final List< String > routes = List.of( "/api/work/projects/" + project + "/flow", "/api/work/projects/" + project + "/dora",
            "/api/work/products/" + product + "/dora" );
        for ( final String route : routes ) {
            assertBad( route, "from=2026-02-01&to=2026-01-01", "from", "The start date is after the end date." );
            assertBad( route, "from=2024-01-01&to=2026-01-01", "to", "A report covers at most 366 days." );
            assertBad( route, "from=2025-01-01&to=2026-01-02", "to", "A report covers at most 366 days." ); // 367 days inclusive
            assertBad( route, "from=banana", "from", "Use a date like 2026-11-21." );
            assertBad( route, "to=2026-13-45", "to", "Use a date like 2026-11-21." );
            assertBad( route, "from=99999-01-01&to=2026-01-01", "from", null );
            assertBad( route, "from=1969-12-31&to=2026-01-01", "from", "The year must be between 1970 and 9999." );
            assertBad( route, "from=2026-01-01&to=10000-01-01", "to", null );
            final HttpResponse< String > limit = admin.get( route + "?from=2025-01-01&to=2026-01-01" );
            assertEquals( 200, limit.statusCode(), route + " 366 days inclusive is the limit and allowed: " + limit.body() );
        }
    }

    private static void assertBad( final String route, final String query, final String field, final String message )
            throws Exception {
        final HttpResponse< String > r = admin.get( route + "?" + query );
        assertEquals( 400, r.statusCode(), route + "?" + query + ": " + r.body() );
        final JsonObject body = json( r ).getAsJsonObject();
        assertEquals( field, body.get( "field" ).getAsString(), route + "?" + query + ": " + r.body() );
        if ( message != null ) {
            assertEquals( message, body.get( "message" ).getAsString(), route + "?" + query );
        }
    }

    // ================================================================ visibility

    @Test
    void aMemberOfAnotherProjectAndAStrangerLearnNothingAndTheProductIsForAdmins() throws Exception {
        final String tag = tag();
        final String project = project( "FP", tag );
        final String elsewhere = project( "FQ", tag );
        final String product = product( tag );
        final String ghost = "FZ" + tag.toUpperCase( Locale.ROOT );
        final String otherLogin = "fwother" + tag;
        final String strangerLogin = "fwstrg" + tag;
        final Caller other = WorkIt.account( admin, otherLogin, "Flow Other " + word( tag ) );
        final Caller stranger = WorkIt.account( admin, strangerLogin, "Flow Stranger " + word( tag ) );
        member( elsewhere, otherLogin, "user", "member" );
        final String carried = item( project, product, "Carried " + tag, null );
        verified( product, null, "4.0." + tag, null, carried );
        assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", "fwq" + tag, "name", "Flow queue " + tag ) ).statusCode() );
        final Agent insider = WorkIt.registerAgent( admin, "fwin" + tag + "@it", "fwq" + tag );
        final Agent outsider = WorkIt.registerAgent( admin, "fwout" + tag + "@it", "fwq" + tag );
        member( project, insider.name(), "agent", "member" );

        final List< String > suffixes = List.of( "flow", "dora", "dora?product=" + product, "flow?" + JAN, "dora?" + JAN );
        // positive control: the admin reads every one
        for ( final String s : suffixes ) {
            assertEquals( 200, admin.get( "/api/work/projects/" + project + "/" + s ).statusCode(), s );
        }
        for ( final Caller c : new Caller[] { other, stranger } ) {
            for ( final String s : suffixes ) {
                final HttpResponse< String > real = c.get( "/api/work/projects/" + project + "/" + s );
                final HttpResponse< String > none = c.get( "/api/work/projects/" + ghost + "/" + s );
                assertEquals( 404, real.statusCode(), s + ": " + real.body() );
                assertEquals( none.statusCode(), real.statusCode(), s );
                assertEquals( none.body(), real.body(), s + ": a project one cannot see and one that does not exist are one answer" );
            }
            final HttpResponse< String > prod = c.get( "/api/work/products/" + product + "/dora" );
            assertEquals( 403, prod.statusCode(), "the product view is for wiki admins: " + prod.body() );
            final HttpResponse< String > noProd = c.get( "/api/work/products/nosuch" + tag + "/dora" );
            // ASSUMED: the admin check comes before the lookup, so a non-admin gets 403 for an unknown product too; only
            // "not 200" is asserted, because the product key is not confidential (deviation 2 of the Java lane report)
            assertTrue( noProd.statusCode() == 403 || noProd.statusCode() == 404, "unknown product: " + noProd.statusCode() );
        }

        // an agent that is a member reads its project; one that is not learns nothing; neither reads a product
        assertEquals( 200, WorkIt.bearer( insider.token(), "GET", "/api/work/projects/" + project + "/flow", null ).statusCode() );
        assertEquals( 200, WorkIt.bearer( insider.token(), "GET", "/api/work/projects/" + project + "/dora", null ).statusCode() );
        final HttpResponse< String > outReal = WorkIt.bearer( outsider.token(), "GET", "/api/work/projects/" + project + "/dora", null );
        final HttpResponse< String > outNone = WorkIt.bearer( outsider.token(), "GET", "/api/work/projects/" + ghost + "/dora", null );
        assertEquals( 404, outReal.statusCode(), outReal.body() );
        assertEquals( outNone.body(), outReal.body() );
        assertEquals( 403, WorkIt.bearer( insider.token(), "GET", "/api/work/products/" + product + "/dora", null ).statusCode(),
            "an agent never reads the product view" );

        // a work API key acts as its owner
        // ASSUMED: any account may mint a work-scope key through /api/self/apikeys (WorkKeySessionIT does so as the admin)
        final String strangerKey = mint( stranger );
        assertEquals( 404, WorkIt.bearer( strangerKey, "GET", "/api/work/projects/" + project + "/flow", null ).statusCode(),
            "a stranger's key is a stranger" );
        // a work key acts as a person with no admin rights, even the admin's own (WorkKeySessionIT). The admin created
        // this project and so led it (WIKIENG-3); out of it, the key is a stranger here and is refused the product view
        assertEquals( 204, admin.send( "DELETE", "/api/work/projects/" + project + "/members",
            Map.of( "principal", "janne", "principalKind", "user" ) ).statusCode() );
        final String adminKey = mint( admin );
        assertEquals( 404, WorkIt.bearer( adminKey, "GET", "/api/work/projects/" + project + "/dora", null ).statusCode(),
            "the admin's key is not the admin's session" );
        assertEquals( 403, WorkIt.bearer( adminKey, "GET", "/api/work/products/" + product + "/dora", null ).statusCode() );

        // an admin on an unknown product: 404
        final HttpResponse< String > unknown = admin.get( "/api/work/products/nosuch" + tag + "/dora" );
        assertEquals( 404, unknown.statusCode(), unknown.body() );
        // the REST layer answers every not-found with the one uniform body, never the service's message
        assertEquals( "Not found.", json( unknown ).getAsJsonObject().get( "message" ).getAsString() );
    }

    private static String mint( final Caller who ) throws Exception {
        final HttpResponse< String > r = who.send( "POST", "/api/self/apikeys", Map.of( "label", "flow-" + tag(), "scope", "work" ) );
        assertEquals( 201, r.statusCode(), "mint a work key: " + r.statusCode() );
        return json( r ).getAsJsonObject().get( "token" ).getAsString();
    }

    // ================================================================ signed code-host deliveries (as WorkCodeHostIT does)

    private static String enableHook( final String product ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/products/" + product + "/hook", null );
        assertEquals( 200, r.statusCode(), "enable hook: " + r.statusCode() );
        return json( r ).getAsJsonObject().get( "secret" ).getAsString();
    }

    private static String hmac( final String secret, final byte[] body ) throws Exception {
        final Mac mac = Mac.getInstance( "HmacSHA256" );
        mac.init( new SecretKeySpec( secret.getBytes( StandardCharsets.UTF_8 ), "HmacSHA256" ) );
        return HexFormat.of().formatHex( mac.doFinal( body ) );
    }

    /** One signed GitHub delivery with no cookie and no session; retries only a 429; the answer must be 204. */
    private static void deliver( final String product, final String secret, final String event, final String json ) throws Exception {
        final byte[] body = json.getBytes( StandardCharsets.UTF_8 );
        for ( int attempt = 0; ; attempt++ ) {
            final HttpResponse< String > r = PLAIN.send( HttpRequest.newBuilder()
                .uri( URI.create( WorkIt.baseUrl() + "/api/work/hooks/" + product + "/github" ) )
                .header( "Content-Type", "application/json" ).header( "X-GitHub-Event", event )
                .header( "X-GitHub-Delivery", UUID.randomUUID().toString() )
                .header( "X-Hub-Signature-256", "sha256=" + hmac( secret, body ) )
                .POST( HttpRequest.BodyPublishers.ofByteArray( body ) ).build(), HttpResponse.BodyHandlers.ofString() );
            if ( r.statusCode() == 429 && attempt < 6 ) {
                Thread.sleep( 1_200 );
                continue;
            }
            assertEquals( 204, r.statusCode(), event + " delivery accepted: " + r.statusCode() + " " + r.body() );
            return;
        }
    }

    private static JsonObject obj( final Object... kv ) {
        final JsonObject o = new JsonObject();
        for ( int i = 0; i < kv.length; i += 2 ) {
            final String k = ( String ) kv[ i ];
            final Object v = kv[ i + 1 ];
            if ( v == null ) {
                continue;
            }
            switch ( v ) {
                case JsonElement j -> o.add( k, j );
                case Boolean b -> o.addProperty( k, b );
                case Number n -> o.addProperty( k, n );
                default -> o.addProperty( k, v.toString() );
            }
        }
        return o;
    }

    /** Minute {@code m} of a fixed past hour (the deliveries' own clock). */
    private static String t( final int m ) {
        return String.format( "2026-01-10T10:%02d:00Z", m );
    }

    private static String sha() {
        final byte[] b = new byte[ 20 ];
        RANDOM.nextBytes( b );
        return HexFormat.of().formatHex( b );
    }

    private static JsonObject commit( final String sha, final String message, final String at, final String name,
                                      final String email, final String username, final boolean distinct ) {
        return obj( "id", sha, "message", message, "timestamp", at, "distinct", distinct,
            "author", obj( "name", name, "email", email, "username", username ) );
    }

    private static String push( final String branch, final String before, final String after, final String at,
                                final JsonObject... commits ) {
        final JsonArray list = new JsonArray();
        for ( final JsonObject c : commits ) {
            list.add( c );
        }
        return obj( "ref", "refs/heads/" + branch, "before", before, "after", after, "commits", list,
            "repository", obj( "full_name", REPO, "default_branch", "main", "pushed_at", Instant.parse( at ).getEpochSecond() ),
            "head_commit", obj( "timestamp", at ) ).toString();
    }

    private record Pr( int number, String branch, String head, String title, String user ) {
    }

    private static Pr pr( final String itemKey, final String user ) {
        return new Pr( 100 + RANDOM.nextInt( 800_000 ), itemKey + "-fix", sha(), itemKey + " fix", user );
    }

    private static JsonObject prBody( final Pr p, final String at, final Object... extra ) {
        final JsonObject pr = obj( "number", p.number, "title", p.title, "html_url", "https://github.com/" + REPO + "/pull/" + p.number,
            "draft", false, "user", obj( "login", p.user ), "head", obj( "ref", p.branch, "sha", p.head ),
            "base", obj( "ref", "main" ), "created_at", t( 1 ), "updated_at", at );
        for ( int i = 0; i < extra.length; i += 2 ) {
            final Object v = extra[ i + 1 ];
            switch ( v ) {
                case JsonElement j -> pr.add( ( String ) extra[ i ], j );
                case Boolean b -> pr.addProperty( ( String ) extra[ i ], b );
                default -> pr.addProperty( ( String ) extra[ i ], v.toString() );
            }
        }
        return pr;
    }

    private static String prEvent( final String action, final JsonObject pr ) {
        return obj( "action", action, "number", pr.get( "number" ), "pull_request", pr,
            "repository", obj( "full_name", REPO, "default_branch", "main" ) ).toString();
    }

    private static String opened( final Pr p, final int minute ) {
        return prEvent( "opened", prBody( p, t( minute ), "created_at", t( minute ) ) );
    }

    private static String merged( final Pr p, final String mergedBy, final String mergeSha, final int minute ) {
        return prEvent( "closed", prBody( p, t( minute ), "merged", true, "merged_at", t( minute ), "closed_at", t( minute ),
            "merged_by", obj( "login", mergedBy ), "merge_commit_sha", mergeSha ) );
    }

    private static String approved( final Pr p, final String reviewer, final int minute ) {
        return obj( "action", "submitted", "review", obj( "state", "approved", "commit_id", p.head, "submitted_at", t( minute ),
            "user", obj( "login", reviewer ) ), "pull_request", prBody( p, t( minute ) ),
            "repository", obj( "full_name", REPO, "default_branch", "main" ) ).toString();
    }
}
