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
import java.util.HashMap;
import java.util.Map;

import static com.wikantik.its.work.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change records and the autonomy gates over the REST API, end to end: two agents with their own tokens take an
 * item from an open change to merged and done; the rules no level relaxes (an agent never merges its own change,
 * no item closes over a live change); a non-member learns nothing about a change; and an item an agent closes at
 * the autonomous level waits for a person.
 */
public class WorkChangeApiIT {

    private static final String SHA = "aaaaaaa1";
    private static final String OUTSIDER = "wchgoutsider";

    private static Caller admin;
    private static Caller outsider;
    private static Agent author;
    private static Agent reviewer;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        outsider = WorkIt.account( admin, OUTSIDER, "Wchg Outsider" );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "CHG", "name", "Changes" ) ).statusCode() );
        for ( final String q : new String[] { "chg-impl", "chg-review" } ) {
            assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", q, "name", "Queue " + q ) ).statusCode() );
        }
        assertEquals( 201, admin.send( "POST", "/api/work/products", Map.of( "key", "chgprod", "name", "Change product",
            "defaultBranch", "main", "autonomyCeiling", "autonomous" ) ).statusCode() );
        author = WorkIt.registerAgent( admin, "chgauthor@it", "chg-impl" );
        reviewer = WorkIt.registerAgent( admin, "chgreviewer@it", "chg-review" );
        for ( final String name : new String[] { "chgauthor@it", "chgreviewer@it" } ) {
            assertEquals( 204, admin.send( "PUT", "/api/work/projects/CHG/members", Map.of( "principal", name,
                "principalKind", "agent", "role", "member" ) ).statusCode() );
        }
    }

    // ---- helpers

    private static JsonObject ok( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), WorkIt.status( r ) );
        return json( r ).getAsJsonObject();
    }

    private static HttpResponse< String > as( final Agent a, final String method, final String path, final Object body )
            throws Exception {
        return WorkIt.bearer( a.token(), method, path, body == null ? null : WorkIt.GSON.toJson( body ) );
    }

    private static JsonObject itemOf( final JsonObject view ) {
        return view.has( "item" ) ? view.getAsJsonObject( "item" ) : view;
    }

    private static int version( final String key ) throws Exception {
        return itemOf( ok( admin.get( "/api/work/items/" + key ) ) ).get( "version" ).getAsInt();
    }

    /** A task targeting the product, ready in the implementer queue; returns its key. */
    private static String readyItem( final String title, final String autonomy ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/items", Map.of( "project", "CHG",
            "level", "task", "title", title, "productKey", "chgprod" ) );
        assertEquals( 201, r.statusCode(), r.body() );
        final String key = itemOf( json( r ).getAsJsonObject() ).get( "key" ).getAsString();
        if ( autonomy != null ) {
            ok( admin.send( "PATCH", "/api/work/items/" + key, Map.of( "expectedVersion", version( key ),
                "changes", Map.of( "autonomy", autonomy ) ) ) );
        }
        ok( admin.send( "POST", "/api/work/items/" + key + "/handoff", Map.of( "expectedVersion", version( key ),
            "status", "ready", "queue", "chg-impl", "note", "to the queue" ) ) );
        return key;
    }

    private static HttpResponse< String > move( final Agent a, final String key, final String status ) throws Exception {
        return as( a, "PATCH", "/api/work/items/" + key, Map.of( "expectedVersion", version( key ),
            "changes", Map.of( "status", status ) ) );
    }

    /** The author claims the item, opens a change at {@link #SHA} and asks for a review; returns the change id. */
    private static long changeInReview( final String key ) throws Exception {
        ok( as( author, "POST", "/api/work/items/" + key + "/claim", null ) );
        final HttpResponse< String > opened = as( author, "POST", "/api/work/items/" + key + "/changes",
            Map.of( "branch", "work/" + key + "-thing", "headSha", SHA ) );
        assertEquals( 201, opened.statusCode(), opened.body() );
        final long id = json( opened ).getAsJsonObject().get( "id" ).getAsLong();
        ok( as( author, "POST", "/api/work/changes/" + id + "/request-review", null ) );
        return id;
    }

    private static void handToReviewer( final String key ) throws Exception {
        ok( as( author, "POST", "/api/work/items/" + key + "/handoff", Map.of( "expectedVersion", version( key ),
            "status", "review", "queue", "chg-review", "note", "ready for review" ) ) );
        ok( as( reviewer, "POST", "/api/work/items/" + key + "/claim", null ) );
    }

    // ---- tests

    @Test
    void twoAgentsDeliverAnItemAtDelegated() throws Exception {
        final String key = readyItem( "Deliver me", null );
        final long id = changeInReview( key );
        handToReviewer( key );

        final JsonObject listed = ok( as( reviewer, "GET", "/api/work/items/" + key + "/changes", null ) )
            .getAsJsonArray( "changes" ).get( 0 ).getAsJsonObject();
        assertEquals( "in_review", listed.get( "state" ).getAsString() );
        assertEquals( "chgauthor@it", listed.get( "openedByName" ).getAsString() );
        assertEquals( "agent", listed.get( "openedByKind" ).getAsString() );
        assertEquals( "chgprod", listed.get( "productKey" ).getAsString() );

        final JsonObject approved = ok( as( reviewer, "POST", "/api/work/changes/" + id + "/review",
            Map.of( "approved", true, "note", "looks right" ) ) );
        assertTrue( approved.get( "approvedForHead" ).getAsBoolean() );
        final JsonObject merged = ok( as( reviewer, "POST", "/api/work/changes/" + id + "/merge", Map.of( "mergedSha", SHA ) ) );
        assertEquals( "merged", merged.get( "state" ).getAsString() );
        assertEquals( "reviewed", merged.get( "mergeKind" ).getAsString() );
        assertEquals( "done", itemOf( ok( move( reviewer, key, "done" ) ) ).get( "status" ).getAsString() );

        final JsonObject policy = ok( admin.get( "/api/work/items/" + key + "/policy" ) );
        assertEquals( "delegated", policy.getAsJsonObject( "effective" ).get( "autonomy" ).getAsString() );
        assertFalse( policy.get( "ackRequired" ).getAsBoolean(), "a delegated close needs no acknowledgement" );
        final JsonObject counts = ok( admin.get( "/api/work/projects/CHG/change-counts" ) );
        assertFalse( counts.has( key ), "nothing live is left on it" );
    }

    @Test
    void anAgentCannotReviewOrMergeItsOwnChange() throws Exception {
        final String key = readyItem( "Mine alone", null );
        final long id = changeInReview( key );
        final HttpResponse< String > review = as( author, "POST", "/api/work/changes/" + id + "/review", Map.of( "approved", true ) );
        assertEquals( 403, review.statusCode(), review.body() );
        assertEquals( "An agent may not review its own change.", json( review ).getAsJsonObject().get( "message" ).getAsString() );
        final HttpResponse< String > merge = as( author, "POST", "/api/work/changes/" + id + "/merge", Map.of( "mergedSha", SHA ) );
        assertEquals( 403, merge.statusCode(), merge.body() );
        assertEquals( "An agent may not merge its own change.", json( merge ).getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void anItemCannotCloseWithALiveChange() throws Exception {
        final String key = readyItem( "Still open", null );
        final long id = changeInReview( key );
        final HttpResponse< String > close = move( author, key, "done" );
        assertEquals( 409, close.statusCode(), close.body() );
        assertEquals( "open_changes", json( close ).getAsJsonObject().get( "reason" ).getAsString() );
        final JsonObject counts = ok( admin.get( "/api/work/projects/CHG/change-counts" ) );
        assertEquals( 1, counts.getAsJsonObject( key ).get( "inReview" ).getAsInt() );
        ok( as( author, "POST", "/api/work/changes/" + id + "/abandon", Map.of( "note", "not needed" ) ) );
        assertEquals( "done", itemOf( ok( move( author, key, "done" ) ) ).get( "status" ).getAsString() );
    }

    @Test
    void aNonMemberLearnsNothingAboutAChange() throws Exception {
        final String key = readyItem( "Hidden work", null );
        final long id = changeInReview( key );
        final HttpResponse< String > list = outsider.get( "/api/work/items/" + key + "/changes" );
        final HttpResponse< String > policy = outsider.get( "/api/work/items/" + key + "/policy" );
        final HttpResponse< String > counts = outsider.get( "/api/work/projects/CHG/change-counts" );
        final HttpResponse< String > real = outsider.send( "POST", "/api/work/changes/" + id + "/merge", Map.of( "mergedSha", SHA ) );
        final HttpResponse< String > none = outsider.send( "POST", "/api/work/changes/987654321/merge", Map.of( "mergedSha", SHA ) );
        for ( final HttpResponse< String > r : new HttpResponse[] { list, policy, counts, real, none } ) {
            assertEquals( 404, r.statusCode(), r.body() );
        }
        assertEquals( none.body(), real.body(), "a hidden change and one that does not exist are one answer" );
        final JsonArray still = ok( admin.get( "/api/work/items/" + key + "/changes" ) ).getAsJsonArray( "changes" );
        assertEquals( "in_review", still.get( 0 ).getAsJsonObject().get( "state" ).getAsString() );
    }

    @Test
    void anAutonomousCloseWaitsForAPerson() throws Exception {
        final String key = readyItem( "Fix it unattended", "autonomous" );
        ok( as( author, "POST", "/api/work/items/" + key + "/claim", null ) );
        assertEquals( "done", itemOf( ok( move( author, key, "done" ) ) ).get( "status" ).getAsString() );

        final JsonObject policy = ok( admin.get( "/api/work/items/" + key + "/policy" ) );
        assertEquals( "autonomous", policy.getAsJsonObject( "effective" ).get( "autonomy" ).getAsString() );
        assertTrue( policy.get( "ackWaiting" ).getAsBoolean() );
        final Map< String, String > reasons = new HashMap<>();
        for ( final var w : ok( admin.get( "/api/work/me" ) ).getAsJsonArray( "waitingOn" ) ) {
            reasons.put( w.getAsJsonObject().get( "key" ).getAsString(),
                w.getAsJsonObject().getAsJsonObject( "waiting" ).get( "reason" ).getAsString() );
        }
        assertEquals( "acknowledge", reasons.get( key ), "it waits in the done-without-you lane: " + reasons );

        final HttpResponse< String > byAgent = as( reviewer, "POST", "/api/work/items/" + key + "/acknowledge", null );
        assertEquals( 403, byAgent.statusCode(), byAgent.body() );
        ok( admin.send( "POST", "/api/work/items/" + key + "/acknowledge", null ) );
        final JsonObject after = ok( admin.get( "/api/work/items/" + key + "/policy" ) );
        assertFalse( after.get( "ackWaiting" ).getAsBoolean() );
        assertTrue( after.get( "ackRequired" ).getAsBoolean(), "the record of how it was closed stays" );
    }
}
