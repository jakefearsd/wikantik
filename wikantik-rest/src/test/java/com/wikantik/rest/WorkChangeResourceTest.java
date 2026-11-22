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

import com.google.gson.JsonObject;
import com.wikantik.work.Ack;
import com.wikantik.work.Actor;
import com.wikantik.work.Autonomy;
import com.wikantik.work.Change;
import com.wikantik.work.ChangeDraft;
import com.wikantik.work.ChangeState;
import com.wikantik.work.DoneMeans;
import com.wikantik.work.EffectivePolicy;
import com.wikantik.work.ItemPolicyView;
import com.wikantik.work.MergeKind;
import com.wikantik.work.WorkChangesService;
import com.wikantik.work.WorkChangesService.ChangeCounts;
import com.wikantik.work.WorkChangesService.ChangeView;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** The change, policy and acknowledge routes: what they pass to the service and how its answers go on the wire. */
class WorkChangeResourceTest {

    private static final Instant T0 = Instant.parse( "2026-11-17T10:00:00Z" );

    private WorkService svc;
    private WorkChangesService changes;
    private WorkChangeResource changeServlet;
    private WorkItemResource itemServlet;
    private WorkProjectResource projectServlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        changes = Mockito.mock( WorkChangesService.class );
        Mockito.when( svc.changes() ).thenReturn( changes );
        // the registry is empty: an agent falls back to the name stored on the row
        Mockito.when( svc.registry() ).thenReturn( Mockito.mock( com.wikantik.work.WorkRegistryService.class ) );
        changeServlet = WorkHttp.wire( new WorkChangeResource(), svc, ALICE );
        itemServlet = WorkHttp.wire( new WorkItemResource(), svc, ALICE );
        projectServlet = WorkHttp.wire( new WorkProjectResource(), svc, ALICE );
    }

    /** A change agent 7 opened, bob approved at its head and bob merged. */
    private static Change merged( final long id ) {
        return new Change( id, 3, 9, "work/PLAT-1-x", "main", "aaaaaaa1", "https://example.test/pr/1",
            ChangeState.MERGED, "aaaaaaa1", MergeKind.REVIEWED, "codex", 7L, "bob", null, "aaaaaaa1", T0, "bob", null,
            T0, T0, null, null, "reported", null, null, null, null, null, null, null, null );
    }

    private static Change open( final long id ) {
        return new Change( id, 3, 9, "work/PLAT-1-x", "main", "aaaaaaa1", null, ChangeState.OPEN, null, null,
            "alice", null, null, null, null, null, null, null, T0, null, null, null, "reported", null, null, null, null, null, null, null, null );
    }

    private void viewIs( final Change c ) throws Exception {
        Mockito.when( changes.view( ALICE, c.id() ) ).thenReturn( new ChangeView( c, "PLAT-1", "wikantik" ) );
    }

    private WorkHttp.Result change( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( changeServlet, method, "/api/work/changes", path, Map.of(), body );
    }

    private WorkHttp.Result item( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( itemServlet, method, "/api/work/items", path, Map.of(), body );
    }

    // ---- item-level routes

    @Test
    void listsAnItemsChangesWithNamesAndKeys() throws Exception {
        Mockito.when( changes.views( ALICE, "PLAT-1" ) )
            .thenReturn( List.of( new ChangeView( merged( 5 ), "PLAT-1", "wikantik" ) ) );
        final WorkHttp.Result r = item( "GET", "/PLAT-1/changes", null );
        assertEquals( 200, r.status() );
        final JsonObject c = r.json().getAsJsonObject().getAsJsonArray( "changes" ).get( 0 ).getAsJsonObject();
        assertEquals( 5, c.get( "id" ).getAsLong() );
        assertEquals( "PLAT-1", c.get( "itemKey" ).getAsString() );
        assertEquals( "wikantik", c.get( "productKey" ).getAsString() );
        assertEquals( "merged", c.get( "state" ).getAsString() );
        assertEquals( "reviewed", c.get( "mergeKind" ).getAsString() );
        assertTrue( c.get( "approvedForHead" ).getAsBoolean() );
        assertEquals( "agent", c.get( "openedByKind" ).getAsString() );
        assertEquals( "codex", c.get( "openedByName" ).getAsString(), "an agent is shown by its name" );
        assertEquals( "Name of bob", c.get( "reviewedByName" ).getAsString() );
        assertEquals( "Name of bob", c.get( "mergedBy" ).getAsString(), "a non-admin sees no login" );
        assertFalse( r.body().contains( "\"bob\"" ), "no login anywhere in the body: " + r.body() );
    }

    @Test
    void anAdminSeesTheLoginBesideTheName() throws Exception {
        final Actor root = Actor.human( "root", Set.of(), true );
        final WorkItemResource asAdmin = WorkHttp.wire( new WorkItemResource(), svc, root );
        Mockito.when( changes.views( root, "PLAT-1" ) )
            .thenReturn( List.of( new ChangeView( merged( 5 ), "PLAT-1", "wikantik" ) ) );
        final JsonObject c = WorkHttp.call( asAdmin, "GET", "/api/work/items", "/PLAT-1/changes", Map.of(), null )
            .json().getAsJsonObject().getAsJsonArray( "changes" ).get( 0 ).getAsJsonObject();
        assertEquals( "bob", c.get( "mergedBy" ).getAsString() );
        assertEquals( "Name of bob", c.get( "mergedByName" ).getAsString() );
    }

    @Test
    void opensAChangeWith201() throws Exception {
        Mockito.when( changes.open( eq( ALICE ), eq( "PLAT-1" ), any() ) ).thenReturn( open( 6 ) );
        viewIs( open( 6 ) );
        final WorkHttp.Result r = item( "POST", "/PLAT-1/changes",
            "{\"branch\":\"work/PLAT-1-x\",\"baseBranch\":\"main\",\"headSha\":\"aaaaaaa1\",\"reviewUrl\":\"https://e.test/1\"}" );
        assertEquals( 201, r.status() );
        assertEquals( "open", r.json().getAsJsonObject().get( "state" ).getAsString() );
        final ArgumentCaptor< ChangeDraft > d = ArgumentCaptor.forClass( ChangeDraft.class );
        Mockito.verify( changes ).open( eq( ALICE ), eq( "PLAT-1" ), d.capture() );
        assertEquals( new ChangeDraft( "work/PLAT-1-x", "main", "aaaaaaa1", "https://e.test/1", null ), d.getValue() );
    }

    @Test
    void firstCommitAtRoundTripsOnOpenAndPatch() throws Exception {
        Mockito.when( changes.open( eq( ALICE ), eq( "PLAT-1" ), any() ) ).thenReturn( open( 6 ) );
        viewIs( open( 6 ) );
        assertEquals( 201, item( "POST", "/PLAT-1/changes",
            "{\"branch\":\"work/PLAT-1-x\",\"firstCommitAt\":\"2026-11-17T09:00:00Z\"}" ).status() );
        final ArgumentCaptor< ChangeDraft > d = ArgumentCaptor.forClass( ChangeDraft.class );
        Mockito.verify( changes ).open( eq( ALICE ), eq( "PLAT-1" ), d.capture() );
        assertEquals( "2026-11-17T09:00:00Z", d.getValue().firstCommitAt() );
        assertEquals( 200, change( "PATCH", "/6", "{\"firstCommitAt\":\"2026-11-17T08:00:00Z\"}" ).status() );
        Mockito.verify( changes ).update( ALICE, 6L, null, null, "2026-11-17T08:00:00Z" );
    }

    @Test
    void anUnknownFieldWhenOpeningIs400() throws Exception {
        final WorkHttp.Result r = item( "POST", "/PLAT-1/changes", "{\"branch\":\"b\",\"state\":\"merged\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "state", r.json().getAsJsonObject().get( "field" ).getAsString() );
        Mockito.verifyNoInteractions( changes );
    }

    @Test
    void saysWhatGovernsAnItem() throws Exception {
        Mockito.when( svc.policyOf( ALICE, "PLAT-1" ) ).thenReturn( new ItemPolicyView(
            new EffectivePolicy( Autonomy.DELEGATED, true, DoneMeans.IN_PRODUCTION ), Autonomy.AUTONOMOUS,
            new Ack( true, "bob", T0 ) ) );
        final JsonObject o = item( "GET", "/PLAT-1/policy", null ).json().getAsJsonObject();
        final JsonObject effective = o.getAsJsonObject( "effective" );
        assertEquals( "delegated", effective.get( "autonomy" ).getAsString() );
        assertEquals( "autonomous", effective.get( "uncappedAutonomy" ).getAsString(), "before the product's ceiling" );
        assertTrue( effective.get( "allowDirect" ).getAsBoolean() );
        assertEquals( "in_production", effective.get( "doneMeans" ).getAsString() );
        assertTrue( o.get( "ackRequired" ).getAsBoolean() );
        assertFalse( o.get( "ackWaiting" ).getAsBoolean() );
        assertEquals( "Name of bob", o.get( "acknowledgedBy" ).getAsString() );
        assertEquals( T0.toString(), o.get( "acknowledgedAt" ).getAsString() );
    }

    @Test
    void acknowledgesAnItem() throws Exception {
        Mockito.when( svc.acknowledge( ALICE, "PLAT-1" ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 3 ) );
        final WorkHttp.Result r = item( "POST", "/PLAT-1/acknowledge", null );
        assertEquals( 200, r.status() );
        assertEquals( "PLAT-1", r.json().getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString() );
    }

    @Test
    void anAgentCannotAcknowledgeIs403() throws Exception {
        Mockito.when( svc.acknowledge( ALICE, "PLAT-1" ) )
            .thenThrow( new WorkDeniedException( "A person acknowledges work closed without one." ) );
        final WorkHttp.Result r = item( "POST", "/PLAT-1/acknowledge", null );
        assertEquals( 403, r.status() );
        assertEquals( "A person acknowledges work closed without one.",
            r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void countsLiveChangesByItem() throws Exception {
        Mockito.when( changes.counts( ALICE, "PLAT" ) ).thenReturn( Map.of( "PLAT-1", new ChangeCounts( 2, 1 ) ) );
        final JsonObject o = WorkHttp.call( projectServlet, "GET", "/api/work/projects", "/PLAT/change-counts",
            Map.of(), null ).json().getAsJsonObject().getAsJsonObject( "PLAT-1" );
        assertEquals( 2, o.get( "openChanges" ).getAsInt() );
        assertEquals( 1, o.get( "inReview" ).getAsInt() );
    }

    // ---- change-level routes

    @Test
    void updatesAChange() throws Exception {
        viewIs( open( 6 ) );
        assertEquals( 200, change( "PATCH", "/6", "{\"headSha\":\"bbbbbbb2\"}" ).status() );
        Mockito.verify( changes ).update( ALICE, 6L, "bbbbbbb2", null, null );
    }

    @Test
    void asksForAReview() throws Exception {
        viewIs( open( 6 ) );
        assertEquals( 200, change( "POST", "/6/request-review", null ).status() );
        Mockito.verify( changes ).requestReview( ALICE, 6L );
    }

    @Test
    void recordsAReview() throws Exception {
        viewIs( open( 6 ) );
        assertEquals( 200, change( "POST", "/6/review", "{\"approved\":false,\"note\":\"needs a test\"}" ).status() );
        Mockito.verify( changes ).review( ALICE, 6L, false, "needs a test" );
    }

    @Test
    void aReviewMustSayApprovedOrNot() throws Exception {
        final WorkHttp.Result r = change( "POST", "/6/review", "{\"note\":\"x\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "approved", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( 400, change( "POST", "/6/review", "{\"approved\":\"yes\"}" ).status() );
    }

    @Test
    void mergesAChange() throws Exception {
        viewIs( merged( 6 ) );
        final WorkHttp.Result r = change( "POST", "/6/merge", "{\"mergedSha\":\"aaaaaaa1\"}" );
        assertEquals( 200, r.status() );
        assertEquals( "merged", r.json().getAsJsonObject().get( "state" ).getAsString() );
        Mockito.verify( changes ).merge( ALICE, 6L, "aaaaaaa1" );
    }

    @Test
    void abandonsAChangeWithOrWithoutABody() throws Exception {
        viewIs( open( 6 ) );
        assertEquals( 200, change( "POST", "/6/abandon", "{\"note\":\"wrong approach\"}" ).status() );
        Mockito.verify( changes ).abandon( ALICE, 6L, "wrong approach" );
        assertEquals( 200, change( "POST", "/6/abandon", null ).status() );
        Mockito.verify( changes ).abandon( ALICE, 6L, null );
    }

    @Test
    void acknowledgesAFlaggedMerge() throws Exception {
        viewIs( merged( 6 ) );
        final WorkHttp.Result r = change( "POST", "/6/acknowledge-flag", null );
        assertEquals( 200, r.status() );
        assertEquals( "merged", r.json().getAsJsonObject().get( "state" ).getAsString() );
        Mockito.verify( changes ).acknowledgeFlag( ALICE, 6L );
    }

    @Test
    void aFlagAcknowledgementConflictCarriesItsReason() throws Exception {
        for ( final String reason : List.of( WorkConflictException.ALREADY_ACKNOWLEDGED, WorkConflictException.NOT_FLAGGED,
            WorkConflictException.ARCHIVED ) ) {
            Mockito.when( changes.acknowledgeFlag( ALICE, 6L ) ).thenThrow( new WorkConflictException( "no", reason ) );
            final WorkHttp.Result r = change( "POST", "/6/acknowledge-flag", null );
            assertEquals( 409, r.status() );
            assertEquals( reason, r.json().getAsJsonObject().get( "reason" ).getAsString() );
            Mockito.reset( changes );
        }
    }

    @Test
    void aFlagAcknowledgementIsDeniedTo403AndHiddenFromAStranger() throws Exception {
        Mockito.when( changes.acknowledgeFlag( ALICE, 6L ) )
            .thenThrow( new WorkDeniedException( "You do not have a high enough role in this project." ) );
        assertEquals( 403, change( "POST", "/6/acknowledge-flag", null ).status() );
        Mockito.reset( changes );
        Mockito.when( changes.acknowledgeFlag( ALICE, 6L ) ).thenThrow( new WorkNotFoundException( "Not found." ) );
        assertEquals( 404, change( "POST", "/6/acknowledge-flag", null ).status() );
    }

    @Test
    void conflictsCarryTheirReason() throws Exception {
        for ( final String reason : List.of( WorkConflictException.NOT_APPROVED, WorkConflictException.CHANGE_CLOSED,
            WorkConflictException.OPEN_CHANGES, WorkConflictException.NOT_IN_REVIEW ) ) {
            Mockito.when( changes.merge( ALICE, 6L, "aaaaaaa1" ) ).thenThrow( new WorkConflictException( "no", reason ) );
            final WorkHttp.Result r = change( "POST", "/6/merge", "{\"mergedSha\":\"aaaaaaa1\"}" );
            assertEquals( 409, r.status() );
            assertEquals( reason, r.json().getAsJsonObject().get( "reason" ).getAsString() );
            assertFalse( r.json().getAsJsonObject().has( "current" ), "a change conflict has no item to merge against" );
            Mockito.reset( changes );
        }
    }

    @Test
    void refusalsAre403AndValidationIs400() throws Exception {
        Mockito.when( changes.merge( ALICE, 6L, "aaaaaaa1" ) )
            .thenThrow( new WorkDeniedException( "An agent may not merge its own change." ) );
        final WorkHttp.Result denied = change( "POST", "/6/merge", "{\"mergedSha\":\"aaaaaaa1\"}" );
        assertEquals( 403, denied.status() );
        assertEquals( "An agent may not merge its own change.",
            denied.json().getAsJsonObject().get( "message" ).getAsString() );
        Mockito.when( changes.merge( ALICE, 6L, "zz" ) )
            .thenThrow( new WorkValidationException( "mergedSha", "A commit id is 7 to 64 hexadecimal characters." ) );
        final WorkHttp.Result bad = change( "POST", "/6/merge", "{\"mergedSha\":\"zz\"}" );
        assertEquals( 400, bad.status() );
        assertEquals( "mergedSha", bad.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void anUnseenOrMalformedChangeIsNotFound() throws Exception {
        Mockito.when( changes.requestReview( ALICE, 6L ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final WorkHttp.Result hidden = change( "POST", "/6/request-review", null );
        final WorkHttp.Result malformed = change( "POST", "/six/request-review", null );
        final WorkHttp.Result noRoute = change( "POST", "/6/explode", null );
        assertEquals( 404, hidden.status() );
        assertEquals( 404, malformed.status() );
        assertEquals( 404, noRoute.status() );
        assertEquals( hidden.body(), malformed.body(), "unseen and unknown are one answer" );
    }
}
