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
import com.wikantik.work.CommentView;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.ItemPatch;
import com.wikantik.work.ItemRevision;
import com.wikantik.work.Link;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkEvent;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkRegistryService;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class WorkItemResourceTest {

    private WorkService svc;
    private WorkClaimsService claims;
    private WorkItemResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        claims = Mockito.mock( WorkClaimsService.class );
        Mockito.when( svc.claims() ).thenReturn( claims );
        servlet = WorkHttp.wire( new WorkItemResource(), svc, ALICE );
    }

    private WorkHttp.Result get( final String path, final Map< String, String > q ) throws Exception {
        return WorkHttp.call( servlet, "GET", "/api/work/items", path, q, null );
    }

    private WorkHttp.Result send( final String method, final String servletPath, final String path, final String body )
            throws Exception {
        return WorkHttp.call( servlet, method, servletPath, path, Map.of(), body );
    }

    // ---- one test per row

    @Test
    void listsItemsOfAProject() throws Exception {
        Mockito.when( svc.itemSummaries( eq( ALICE ), eq( "PLAT" ), any() ) ).thenReturn( List.of(
            WorkHttp.summary( WorkHttp.item( "PLAT-1", null, 1 ), 4, true, "web", "PLAT-0" ) ) );
        final WorkHttp.Result r = get( null, Map.of( "project", "PLAT", "status", "draft,ready", "level", "task",
            "assignee", "alice", "q", "x", "dueBefore", "2026-12-31" ) );
        assertEquals( 200, r.status() );
        final JsonObject first = r.json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "PLAT-1", first.get( "key" ).getAsString() );
        assertEquals( 4, first.get( "commentCount" ).getAsInt() );
        assertEquals( true, first.get( "blocked" ).getAsBoolean() );
        assertEquals( "web", first.get( "productKey" ).getAsString() );
        assertEquals( "PLAT-0", first.get( "parentKey" ).getAsString() );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).itemSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( 2, f.getValue().statuses().size() );
        assertEquals( "alice", f.getValue().assignee() );
        assertEquals( "2026-12-31", f.getValue().dueBefore().toString() );
    }

    @Test
    void projectIsRequiredForTheList() throws Exception {
        final WorkHttp.Result r = get( null, Map.of() );
        assertEquals( 400, r.status() );
        assertEquals( "project", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void badFilterValuesAre400() throws Exception {
        assertEquals( 400, get( null, Map.of( "project", "PLAT", "status", "bogus" ) ).status() );
        assertEquals( 400, get( null, Map.of( "project", "PLAT", "level", "bogus" ) ).status() );
        assertEquals( 400, get( null, Map.of( "project", "PLAT", "dueBefore", "tomorrow" ) ).status() );
    }

    @Test
    void createsAnItemWith201() throws Exception {
        Mockito.when( svc.createItem( eq( ALICE ), any() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", null,
            "{\"project\":\"PLAT\",\"level\":\"task\",\"title\":\"T\",\"priority\":2,\"dueOn\":\"2026-12-01\"}" );
        assertEquals( 201, r.status() );
        assertEquals( "PLAT-1", r.json().getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString() );
        final ArgumentCaptor< com.wikantik.work.ItemDraft > d = ArgumentCaptor.forClass( com.wikantik.work.ItemDraft.class );
        Mockito.verify( svc ).createItem( eq( ALICE ), d.capture() );
        assertEquals( "PLAT", d.getValue().projectKey() );
        assertEquals( 2, d.getValue().priority() );
        assertEquals( "2026-12-01", d.getValue().dueOn().toString() );
    }

    @Test
    void unknownFieldInACreateBodyIs400() throws Exception {
        final WorkHttp.Result r = send( "POST", "/api/work/items", null, "{\"project\":\"PLAT\",\"tittle\":\"T\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "tittle", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void getsOneItem() throws Exception {
        Mockito.when( svc.item( ALICE, "PLAT-1" ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        final JsonObject o = get( "/PLAT-1", Map.of() ).json().getAsJsonObject();
        assertEquals( "PLAT", o.get( "projectKey" ).getAsString() );
        assertEquals( "member", o.get( "viewerRole" ).getAsString() );
        assertEquals( "draft", o.getAsJsonObject( "item" ).get( "status" ).getAsString() );
        assertEquals( "task", o.getAsJsonObject( "item" ).get( "level" ).getAsString() );
        assertEquals( "2026-11-01T10:00:00Z", o.getAsJsonObject( "item" ).get( "createdAt" ).getAsString() );
    }

    @Test
    void patchesAnItemPassingPlainValues() throws Exception {
        Mockito.when( svc.updateItem( eq( ALICE ), eq( "PLAT-1" ), any() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 2 ) );
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1",
            "{\"expectedVersion\":1,\"changes\":{\"status\":\"ready\",\"priority\":2,\"assignee\":null,"
                + "\"allowDirect\":true},\"note\":\"go\"}" );
        assertEquals( 200, r.status() );
        final ArgumentCaptor< ItemPatch > p = ArgumentCaptor.forClass( ItemPatch.class );
        Mockito.verify( svc ).updateItem( eq( ALICE ), eq( "PLAT-1" ), p.capture() );
        assertEquals( 1, p.getValue().expectedVersion() );
        assertEquals( "go", p.getValue().note() );
        assertEquals( "ready", p.getValue().changes().get( "status" ) );
        assertEquals( 2, ( ( Number ) p.getValue().changes().get( "priority" ) ).intValue() );
        assertTrue( p.getValue().changes().containsKey( "assignee" ) );
        assertNull( p.getValue().changes().get( "assignee" ) );
        assertEquals( Boolean.TRUE, p.getValue().changes().get( "allowDirect" ) );
    }

    @Test
    void patchNeedsAnExpectedVersion() throws Exception {
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1", "{\"changes\":{\"title\":\"x\"}}" );
        assertEquals( 400, r.status() );
        assertEquals( "expectedVersion", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void unknownJsonFieldInChangesIs400() throws Exception {
        Mockito.when( svc.updateItem( any(), anyString(), any() ) )
            .thenThrow( new WorkValidationException( "bogus", "Unknown field: bogus" ) );
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1",
            "{\"expectedVersion\":1,\"changes\":{\"bogus\":1}}" );
        assertEquals( 400, r.status() );
        assertEquals( "bogus", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void movingAQueuedCardToInProgressIs400WithTheRule() throws Exception {
        // The board's "move to In progress" on a queued card is a PATCH of status; the rule's message reaches it.
        final String rule = "Work in a queue is ready or in review until an agent claims it.";
        Mockito.when( svc.updateItem( any(), anyString(), any() ) )
            .thenThrow( new WorkValidationException( "status", rule ) );
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1",
            "{\"expectedVersion\":1,\"changes\":{\"status\":\"in_progress\"}}" );
        assertEquals( 400, r.status() );
        assertEquals( "status", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( rule, r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void aNestedValueInChangesIs400() throws Exception {
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1",
            "{\"expectedVersion\":1,\"changes\":{\"title\":{\"a\":1}}}" );
        assertEquals( 400, r.status() );
        assertEquals( "title", r.json().getAsJsonObject().get( "field" ).getAsString() );
        Mockito.verify( svc, Mockito.never() ).updateItem( any(), anyString(), any() );
    }

    @Test
    void listsRevisionsAndEvents() throws Exception {
        Mockito.when( svc.revisions( ALICE, "PLAT-1" ) ).thenReturn( List.of(
            new ItemRevision( 7, 1, "T", "B", "alice", Instant.parse( "2026-11-01T10:00:00Z" ), null ) ) );
        Mockito.when( svc.events( ALICE, "PLAT-1" ) ).thenReturn( List.of( new WorkEvent( 1, 1L, 7L, "PLAT-1",
            Instant.parse( "2026-11-01T10:00:00Z" ), "alice", com.wikantik.work.Actor.Kind.HUMAN, null, "assigned",
            "alice", "bob", null ) ) );
        final JsonObject rev = get( "/PLAT-1/revisions", Map.of() ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "Name of alice", rev.get( "editedByName" ).getAsString() );
        final JsonObject ev = get( "/PLAT-1/events", Map.of() ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "human", ev.get( "actorKind" ).getAsString() );
        assertEquals( "Name of alice", ev.get( "actorName" ).getAsString() );
        assertEquals( "Name of bob", ev.get( "toValueName" ).getAsString() );
    }

    @Test
    void addsEditsAndRemovesComments() throws Exception {
        final CommentView c = new CommentView( 5, 7, "alice", "hi", Instant.parse( "2026-11-01T10:00:00Z" ), null, null, "<p>hi</p>", List.of(), null );
        Mockito.when( svc.addComment( ALICE, "PLAT-1", "hi" ) ).thenReturn( c );
        Mockito.when( svc.editComment( ALICE, 5, "yo" ) ).thenReturn( c );
        final WorkHttp.Result added = send( "POST", "/api/work/items", "/PLAT-1/comments", "{\"body\":\"hi\"}" );
        assertEquals( 201, added.status() );
        assertEquals( "Name of alice", added.json().getAsJsonObject().get( "authorName" ).getAsString() );
        assertEquals( 200, send( "PATCH", "/api/work/comments", "/5", "{\"body\":\"yo\"}" ).status() );
        assertEquals( 204, send( "DELETE", "/api/work/comments", "/5", null ).status() );
        Mockito.verify( svc ).removeComment( ALICE, 5 );
    }

    @Test
    void addsAndRemovesLinks() throws Exception {
        Mockito.when( svc.addLink( ALICE, "PLAT-1", "page", "SomePage" ) ).thenReturn( new Link( 9, 7, "page", "SomePage" ) );
        final WorkHttp.Result added = send( "POST", "/api/work/items", "/PLAT-1/links",
            "{\"kind\":\"page\",\"target\":\"SomePage\"}" );
        assertEquals( 201, added.status() );
        assertEquals( "SomePage", added.json().getAsJsonObject().get( "target" ).getAsString() );
        assertEquals( 204, send( "DELETE", "/api/work/links", "/9", null ).status() );
        Mockito.verify( svc ).removeLink( ALICE, 9 );
    }

    // ---- cross-cutting behaviour

    @Test
    void disabledFeatureIs404() throws Exception {
        Mockito.doReturn( null ).when( servlet ).workService();
        final WorkHttp.Result r = get( "/PLAT-1", Map.of() );
        assertEquals( 404, r.status() );
        Mockito.verifyNoInteractions( svc );
    }

    @Test
    void anonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, get( "/PLAT-1", Map.of() ).status() );
        Mockito.verifyNoInteractions( svc );
    }

    @Test
    void notFoundMapsTo404WithoutEchoingTheKey() throws Exception {
        Mockito.when( svc.item( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "No such PLAT-99" ) );
        final WorkHttp.Result r = get( "/PLAT-99", Map.of() );
        assertEquals( 404, r.status() );
        assertFalse( r.body().contains( "PLAT-99" ), r.body() );
    }

    @Test
    void unknownPathAndUnparseableIdAre404() throws Exception {
        assertEquals( 404, get( "/PLAT-1/nonsense", Map.of() ).status() );
        assertEquals( 404, send( "DELETE", "/api/work/comments", "/abc", null ).status() );
        assertEquals( 404, send( "DELETE", "/api/work/links", "/9x", null ).status() );
        Mockito.verifyNoInteractions( svc );
    }

    @Test
    void deniedMapsTo403WithReason() throws Exception {
        Mockito.when( svc.updateItem( any(), anyString(), any() ) ).thenThrow( new WorkDeniedException( "Viewers cannot edit." ) );
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1", "{\"expectedVersion\":1,\"changes\":{\"title\":\"x\"}}" );
        assertEquals( 403, r.status() );
        assertEquals( "Viewers cannot edit.", r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void conflictMapsTo409WithCurrentItem() throws Exception {
        Mockito.when( svc.updateItem( any(), anyString(), any() ) ).thenThrow( new WorkConflictException( "changed" ) );
        Mockito.when( svc.item( ALICE, "PLAT-1" ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 5 ) );
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1", "{\"expectedVersion\":1,\"changes\":{\"title\":\"x\"}}" );
        assertEquals( 409, r.status() );
        assertEquals( 5, r.json().getAsJsonObject().getAsJsonObject( "current" ).getAsJsonObject( "item" )
            .get( "version" ).getAsInt() );
    }

    @Test
    void validationMapsTo400WithFieldAndMessage() throws Exception {
        Mockito.when( svc.addComment( any(), anyString(), any() ) ).thenThrow( new WorkValidationException( "body", "A comment cannot be empty." ) );
        final JsonObject o = send( "POST", "/api/work/items", "/PLAT-1/comments", "{\"body\":\"\"}" ).json().getAsJsonObject();
        assertEquals( "body", o.get( "field" ).getAsString() );
        assertEquals( "A comment cannot be empty.", o.get( "message" ).getAsString() );
    }

    @Test
    void unexpectedFailureIs500WithoutDetail() throws Exception {
        Mockito.when( svc.item( any(), anyString() ) ).thenThrow( new java.sql.SQLException( "connection to 10.0.0.5 refused" ) );
        final WorkHttp.Result r = get( "/PLAT-1", Map.of() );
        assertEquals( 500, r.status() );
        assertFalse( r.body().contains( "10.0.0.5" ), r.body() );
    }

    @Test
    void responseShowsDisplayNameNotLogin() throws Exception {
        Mockito.when( svc.item( ALICE, "PLAT-1" ) ).thenReturn( WorkHttp.view( "PLAT-1", "carol", 1 ) );
        final JsonObject item = get( "/PLAT-1", Map.of() ).json().getAsJsonObject().getAsJsonObject( "item" );
        assertEquals( "Name of carol", item.get( "assigneeName" ).getAsString() );
        assertEquals( "Name of bob", item.get( "reporterName" ).getAsString() );
        assertEquals( "Name of carol", item.get( "assignee" ).getAsString(), "a member is shown no login" );
        assertEquals( "Name of bob", item.get( "reporter" ).getAsString() );
    }

    @Test
    void anAdminSeesLoginsBesideNames() throws Exception {
        servlet = WorkHttp.wire( new WorkItemResource(), svc, Actor.human( "root", java.util.Set.of(), true ) );
        Mockito.when( svc.item( any(), anyString() ) ).thenReturn( WorkHttp.view( "PLAT-1", "carol", 1 ) );
        final JsonObject item = get( "/PLAT-1", Map.of() ).json().getAsJsonObject().getAsJsonObject( "item" );
        assertEquals( "carol", item.get( "assignee" ).getAsString() );
        assertEquals( "Name of carol", item.get( "assigneeName" ).getAsString() );
    }

    @Test
    void aCommentSaysWhetherItIsMineAndShowsNoLogin() throws Exception {
        final CommentView mine = new CommentView( 5, 7, "alice", "hi", Instant.parse( "2026-11-01T10:00:00Z" ), null,
            null, "<p>hi</p>", List.of(), null );
        final CommentView theirs = new CommentView( 6, 7, "carol", "yo", Instant.parse( "2026-11-01T10:00:00Z" ), null,
            null, "<p>yo</p>", List.of( "Name of alice" ), null );
        final WorkService.ItemView v = WorkHttp.view( "PLAT-1", null, 1 );
        Mockito.when( svc.item( ALICE, "PLAT-1" ) ).thenReturn( new WorkService.ItemView( v.item(), v.bodyHtml(),
            v.projectKey(), null, null, List.of(), List.of( mine, theirs ), List.of(), false, WorkRole.MEMBER, false, null ) );
        final var comments = get( "/PLAT-1", Map.of() ).json().getAsJsonObject().getAsJsonArray( "comments" );
        assertTrue( comments.get( 0 ).getAsJsonObject().get( "mine" ).getAsBoolean() );
        assertFalse( comments.get( 1 ).getAsJsonObject().get( "mine" ).getAsBoolean() );
        assertEquals( "Name of carol", comments.get( 1 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( "<p>yo</p>", comments.get( 1 ).getAsJsonObject().get( "bodyHtml" ).getAsString() );
        assertFalse( comments.toString().contains( "\"carol\"" ), comments.toString() );
    }

    @Test
    void aStaleConflictCarriesTheCurrentItemAndSaysStale() throws Exception {
        Mockito.when( svc.updateItem( any(), anyString(), any() ) ).thenThrow( new WorkConflictException( "changed" ) );
        Mockito.when( svc.item( any(), anyString() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 5 ) );
        final JsonObject b = send( "PATCH", "/api/work/items", "/PLAT-1",
            "{\"expectedVersion\":1,\"changes\":{\"title\":\"x\"}}" ).json().getAsJsonObject();
        assertEquals( "stale", b.get( "reason" ).getAsString() );
        assertTrue( b.has( "current" ) );
    }

    @Test
    void anArchivedConflictSaysArchivedAndCarriesNoCurrentItem() throws Exception {
        Mockito.when( svc.updateItem( any(), anyString(), any() ) ).thenThrow(
            new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED ) );
        final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1",
            "{\"expectedVersion\":1,\"changes\":{\"title\":\"x\"}}" );
        assertEquals( 409, r.status() );
        assertEquals( "archived", r.json().getAsJsonObject().get( "reason" ).getAsString() );
        assertFalse( r.json().getAsJsonObject().has( "current" ) );
        Mockito.verify( svc, Mockito.never() ).item( any(), anyString() );
    }

    @Test
    void theDueOrderIsPassedThroughAndAnUnknownOrderIs400() throws Exception {
        Mockito.when( svc.itemSummaries( any(), anyString(), any() ) ).thenReturn( List.of() );
        get( "", Map.of( "project", "PLAT", "order", "due" ) );
        Mockito.verify( svc ).itemSummaries( any(), eq( "PLAT" ),
            Mockito.argThat( f -> f.order() == com.wikantik.work.ItemFilter.Order.DUE ) );
        final WorkHttp.Result bad = get( "", Map.of( "project", "PLAT", "order", "random" ) );
        assertEquals( 400, bad.status() );
        assertEquals( "order", bad.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void limitIsClampedTo200() throws Exception {
        Mockito.when( svc.itemSummaries( any(), anyString(), any() ) ).thenReturn( List.of() );
        get( null, Map.of( "project", "PLAT", "limit", "5000" ) );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).itemSummaries( any(), anyString(), f.capture() );
        assertEquals( 200, f.getValue().limit() );
    }

    @Test
    void aNonIntegerExpectedVersionIs400() throws Exception {
        for ( final String bad : List.of( "1e99", "1.5", "99999999999", "\"1\"", "null" ) ) {
            final WorkHttp.Result r = send( "PATCH", "/api/work/items", "/PLAT-1",
                "{\"expectedVersion\":" + bad + ",\"changes\":{\"title\":\"x\"}}" );
            assertEquals( 400, r.status(), bad + ": " + r.body() );
            assertEquals( "expectedVersion", r.json().getAsJsonObject().get( "field" ).getAsString(), bad );
        }
        Mockito.verify( svc, Mockito.never() ).updateItem( any(), anyString(), any() );
    }

    @Test
    void anOversizedBodyIs413() throws Exception {
        final String big = "{\"body\":\"" + "x".repeat( 262_200 ) + "\"}";
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/comments", big );
        assertEquals( 413, r.status(), r.body() );
        Mockito.verifyNoInteractions( svc );
    }

    @Test
    void aBodyJustUnderTheLimitIsAccepted() throws Exception {
        Mockito.when( svc.addComment( any(), anyString(), any() ) ).thenReturn(
            new CommentView( 5, 7, "alice", "hi", Instant.parse( "2026-11-01T10:00:00Z" ), null, null, "<p>hi</p>", List.of(), null ) );
        final String big = "{\"body\":\"" + "x".repeat( 262_000 ) + "\"}";
        assertEquals( 201, send( "POST", "/api/work/items", "/PLAT-1/comments", big ).status() );
    }

    @Test
    void disabledFeatureIs404EvenForAnAnonymousCaller() throws Exception {
        Mockito.doReturn( null ).when( servlet ).workService();
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 404, get( "/PLAT-1", Map.of() ).status() );
        Mockito.verify( servlet, Mockito.never() ).actor( any() );
    }

    // ---- phase 2: claims, hand-off, queues, bearer keys

    private static final Actor BUILDER = Actor.agent( 5, "builder" );

    @Test
    void claimReturnsItemAndRenewedFlag() throws Exception {
        Mockito.when( claims.claim( ALICE, "PLAT-1" ) ).thenReturn(
            new WorkClaimsService.ClaimResult( WorkHttp.view( "PLAT-1", null, 2 ), true ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/claim", null );
        assertEquals( 200, r.status() );
        assertEquals( true, r.json().getAsJsonObject().get( "renewed" ).getAsBoolean() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( "PLAT-1", o.getAsJsonObject( "item" ).get( "key" ).getAsString(), "an ItemView at the top level" );
        assertEquals( "PLAT", o.get( "projectKey" ).getAsString() );
        assertFalse( o.getAsJsonObject( "item" ).has( "item" ) );
    }

    @Test
    void notClaimableIs409WithReason() throws Exception {
        Mockito.when( claims.claim( any(), anyString() ) ).thenThrow( new WorkConflictException(
            "This item is claimed by reviewer until 2026-11-15T10:30:00Z.", WorkConflictException.NOT_CLAIMABLE ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/claim", null );
        assertEquals( 409, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( "not_claimable", o.get( "reason" ).getAsString() );
        assertTrue( o.get( "message" ).getAsString().contains( "reviewer" ) );
        assertFalse( o.has( "current" ) );
    }

    @Test
    void releaseWithoutClaimIs409NotClaimed() throws Exception {
        Mockito.when( claims.release( any(), anyString(), any() ) ).thenThrow( new WorkConflictException(
            "This item is not claimed.", WorkConflictException.NOT_CLAIMED ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/release", null );
        assertEquals( 409, r.status() );
        assertEquals( "not_claimed", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void releaseWithAReservedNoteIs400() throws Exception {
        Mockito.when( claims.release( any(), anyString(), any() ) )
            .thenThrow( new com.wikantik.work.WorkValidationException( "note", "Choose a different note." ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/release", "{\"note\":\"moved\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "note", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "Choose a different note.", r.json().getAsJsonObject().get( "message" ).getAsString() );
        Mockito.verify( claims ).release( ALICE, "PLAT-1", "moved" );
    }

    @Test
    void releaseTakesAnOptionalNote() throws Exception {
        Mockito.when( claims.release( any(), anyString(), any() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 3 ) );
        assertEquals( 200, send( "POST", "/api/work/items", "/PLAT-1/release", "{\"note\":\"blocked on infra\"}" ).status() );
        Mockito.verify( claims ).release( ALICE, "PLAT-1", "blocked on infra" );
        assertEquals( 200, send( "POST", "/api/work/items", "/PLAT-1/release", null ).status() );
        Mockito.verify( claims ).release( ALICE, "PLAT-1", null );
    }

    @Test
    void handoffPassesStatusTargetNoteAndVersion() throws Exception {
        Mockito.when( claims.handoff( any(), anyString(), any() ) ).thenReturn( WorkHttp.view( "PLAT-1", "erin", 4 ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/handoff",
            "{\"expectedVersion\":3,\"status\":\"review\",\"assignee\":\"Erin E\",\"note\":\"please review\"}" );
        assertEquals( 200, r.status() );
        final ArgumentCaptor< WorkClaimsService.Handoff > h = ArgumentCaptor.forClass( WorkClaimsService.Handoff.class );
        Mockito.verify( claims ).handoff( eq( ALICE ), eq( "PLAT-1" ), h.capture() );
        assertEquals( new WorkClaimsService.Handoff( 3, WorkStatus.REVIEW, "Erin E", null, "please review" ), h.getValue() );
        send( "POST", "/api/work/items", "/PLAT-1/handoff", "{\"expectedVersion\":3,\"queue\":\"triage\",\"note\":\"n\"}" );
        Mockito.verify( claims ).handoff( ALICE, "PLAT-1", new WorkClaimsService.Handoff( 3, null, null, "triage", "n" ) );
    }

    @Test
    void handoffNeedsNote400() throws Exception {
        Mockito.when( claims.handoff( any(), anyString(), any() ) ).thenThrow(
            new WorkValidationException( "note", "A hand-off needs a note saying what the next one should do." ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/handoff",
            "{\"expectedVersion\":3,\"assignee\":\"Erin E\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "note", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void handoffBodyShapeIsCheckedBeforeTheServiceIsAsked() throws Exception {
        for ( final String body : new String[] { "{}", "{\"expectedVersion\":\"x\"}", "{\"expectedVersion\":1.5}",
            "{\"expectedVersion\":1,\"status\":\"bogus\"}", "{\"expectedVersion\":1,\"queue\":{}}", "[]", "not json" } ) {
            assertEquals( 400, send( "POST", "/api/work/items", "/PLAT-1/handoff", body ).status(), body );
        }
        Mockito.verify( claims, Mockito.never() ).handoff( any(), anyString(), any() );
    }

    @Test
    void aStaleHandoffCarriesTheCurrentItem() throws Exception {
        Mockito.when( claims.handoff( any(), anyString(), any() ) ).thenThrow( new WorkConflictException( "changed" ) );
        Mockito.when( svc.item( ALICE, "PLAT-1" ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 9 ) );
        final WorkHttp.Result r = send( "POST", "/api/work/items", "/PLAT-1/handoff",
            "{\"expectedVersion\":3,\"assignee\":\"x\",\"note\":\"n\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "stale", r.json().getAsJsonObject().get( "reason" ).getAsString() );
        assertEquals( 9, r.json().getAsJsonObject().getAsJsonObject( "current" ).getAsJsonObject( "item" )
            .get( "version" ).getAsInt() );
    }

    @Test
    void claimableLimitClampedTo50() throws Exception {
        Mockito.when( claims.claimable( any(), any(), any(), Mockito.anyInt() ) ).thenReturn( List.of(
            WorkHttp.summary( WorkHttp.item( "PLAT-1", null, 1 ), 0, false, null, null ) ) );
        final WorkHttp.Result r = WorkHttp.call( servlet, "GET", "/api/work/claimable", null,
            Map.of( "project", "PLAT", "queue", "triage", "limit", "999" ), null );
        assertEquals( 200, r.status() );
        assertEquals( "PLAT-1", r.json().getAsJsonArray().get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        Mockito.verify( claims ).claimable( ALICE, "PLAT", "triage", 50 );
        WorkHttp.call( servlet, "GET", "/api/work/claimable", null, Map.of(), null );
        Mockito.verify( claims ).claimable( ALICE, null, null, 20 );
    }

    @Test
    void claimableForAHiddenProjectIsTheSame404AsAMissingOne() throws Exception {
        Mockito.when( claims.claimable( any(), eq( "HIDDEN" ), any(), Mockito.anyInt() ) )
            .thenThrow( new WorkNotFoundException( "No such project HIDDEN" ) );
        Mockito.when( claims.claimable( any(), eq( "MISSING" ), any(), Mockito.anyInt() ) )
            .thenThrow( new WorkNotFoundException( "Not found." ) );
        final WorkHttp.Result hidden = WorkHttp.call( servlet, "GET", "/api/work/claimable", null, Map.of( "project", "HIDDEN" ), null );
        final WorkHttp.Result missing = WorkHttp.call( servlet, "GET", "/api/work/claimable", null, Map.of( "project", "MISSING" ), null );
        assertEquals( 404, hidden.status() );
        assertEquals( missing.status(), hidden.status() );
        assertEquals( missing.body(), hidden.body() );
    }

    @Test
    void queueParameterFiltersTheList() throws Exception {
        Mockito.when( svc.itemSummaries( any(), anyString(), any() ) ).thenReturn( List.of() );
        get( null, Map.of( "project", "PLAT", "queue", "triage" ) );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).itemSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( "triage", f.getValue().queue() );
    }

    @Test
    void createPassesTheQueue() throws Exception {
        Mockito.when( svc.createItem( eq( ALICE ), any() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        send( "POST", "/api/work/items", null, "{\"project\":\"PLAT\",\"level\":\"task\",\"title\":\"T\",\"queue\":\"triage\"}" );
        final ArgumentCaptor< com.wikantik.work.ItemDraft > d = ArgumentCaptor.forClass( com.wikantik.work.ItemDraft.class );
        Mockito.verify( svc ).createItem( eq( ALICE ), d.capture() );
        assertEquals( "triage", d.getValue().queue() );
    }

    @Test
    void anItemShowsWhoHoldsItAndWhichQueueItIsIn() throws Exception {
        final WorkRegistryService registry = Mockito.mock( WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        Mockito.when( registry.listAgents( ALICE ) ).thenReturn( List.of( new com.wikantik.work.Agent( 5, "builder", "", "",
            null, List.of( "triage" ), Instant.parse( "2026-11-01T10:00:00Z" ), null ) ) );
        Mockito.when( registry.listQueues( ALICE ) ).thenReturn( List.of( new com.wikantik.work.Queue( "triage", "Triage queue", "", null ) ) );
        final com.wikantik.work.Item base = WorkHttp.item( "PLAT-1", null, 2 );
        final com.wikantik.work.Item claimed = new com.wikantik.work.Item( base.id(), base.projectId(), base.key(), base.level(),
            null, null, base.kind(), base.title(), base.body(), WorkStatus.IN_PROGRESS, 3, 1.0, null, null, "bob", null, null, null,
            2, base.createdAt(), base.updatedAt(), null, "triage", 5L, Instant.parse( "2026-11-15T10:00:00Z" ),
            Instant.parse( "2026-11-15T10:30:00Z" ), null );
        Mockito.when( svc.item( ALICE, "PLAT-1" ) ).thenReturn( new WorkService.ItemView( claimed, "<p>b</p>", "PLAT", null, null,
            List.of(), List.of(), List.of(), false, WorkRole.MEMBER, false, null ) );
        final JsonObject item = get( "/PLAT-1", Map.of() ).json().getAsJsonObject().getAsJsonObject( "item" );
        assertEquals( "builder", item.get( "claimedByName" ).getAsString() );
        assertEquals( "Triage queue", item.get( "queueName" ).getAsString() );
        assertEquals( "triage", item.get( "queue" ).getAsString() );
    }

    private WorkItemResource keyed( final Actor session ) {
        final WorkRegistryService registry = Mockito.mock( WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        return WorkHttp.wireKeyed( new WorkItemResource(), svc, session );
    }

    private static com.wikantik.auth.apikeys.ApiKeyService.Record key( final com.wikantik.auth.apikeys.ApiKeyService.Scope scope ) {
        return new com.wikantik.auth.apikeys.ApiKeyService.Record( 42, "h", "owner", "k", scope, Instant.EPOCH, "admin", null,
            null, null, false );
    }

    private WorkHttp.Result getWithKey( final WorkItemResource s, final com.wikantik.auth.apikeys.ApiKeyService.Record record,
                                        final String authorization ) throws Exception {
        return WorkHttp.call( s, "GET", "/api/work/items", "/PLAT-1", Map.of(), null, req -> {
            Mockito.doReturn( authorization ).when( req ).getHeader( "Authorization" );
            Mockito.doReturn( record ).when( req ).getAttribute( com.wikantik.auth.apikeys.ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD );
        } );
    }

    @Test
    void bearerAgentActorUsed() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        Mockito.when( svc.registry().actorForApiKey( 42, "owner" ) ).thenReturn( BUILDER );
        Mockito.when( svc.item( any(), anyString() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        final WorkHttp.Result r = getWithKey( s, key( com.wikantik.auth.apikeys.ApiKeyService.Scope.WORK ), "Bearer wkk_x" );
        assertEquals( 200, r.status() );
        final ArgumentCaptor< Actor > seen = ArgumentCaptor.forClass( Actor.class );
        Mockito.verify( svc ).item( seen.capture(), eq( "PLAT-1" ) );
        assertEquals( Actor.Kind.AGENT, seen.getValue().kind() );
        assertEquals( 5L, seen.getValue().agentId() );
    }

    @Test
    void bearerKeyWinsOverALoggedInSession() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        Mockito.when( svc.registry().actorForApiKey( 42, "owner" ) ).thenReturn( BUILDER );
        Mockito.when( svc.item( any(), anyString() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        getWithKey( s, key( com.wikantik.auth.apikeys.ApiKeyService.Scope.WORK ), "Bearer wkk_x" );
        Mockito.verify( s, Mockito.never() ).sessionActor( any() );
        Mockito.verify( svc, Mockito.never() ).item( eq( ALICE ), anyString() );
    }

    @Test
    void aBearerHeaderWithNoVerifiedKeyIsNeverASessionRequest() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        final WorkHttp.Result r = getWithKey( s, null, "Bearer wkk_whatever" );
        assertEquals( 401, r.status() );
        assertEquals( WorkApiKeyFilter.INVALID_KEY_BODY, r.body() );
        Mockito.verify( s, Mockito.never() ).sessionActor( any() );
        Mockito.verify( svc, Mockito.never() ).item( any(), anyString() );
    }

    @Test
    void aKeyBoundToNoActorIs401WithTheSameBodyAsAnUnknownKey() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        Mockito.when( svc.registry().actorForApiKey( 42, "owner" ) ).thenReturn( null );   // retired or rotated away
        final WorkHttp.Result retired = getWithKey( s, key( com.wikantik.auth.apikeys.ApiKeyService.Scope.WORK ), "Bearer wkk_x" );
        final WorkHttp.Result unknown = getWithKey( s, null, "Bearer wkk_y" );
        assertEquals( 401, retired.status() );
        assertEquals( unknown.body(), retired.body() );
        Mockito.verify( s, Mockito.never() ).sessionActor( any() );
    }

    @Test
    void aKeyWhoseScopeIsNotWorkIsNoActorEvenIfItReachedTheResource() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        final WorkHttp.Result r = getWithKey( s, key( com.wikantik.auth.apikeys.ApiKeyService.Scope.MCP_ADMIN ), "Bearer wkk_x" );
        assertEquals( 401, r.status() );
        Mockito.verify( svc.registry(), Mockito.never() ).actorForApiKey( Mockito.anyInt(), anyString() );
    }

    @Test
    void anOrdinaryKeyActsAsItsOwnerAPerson() throws Exception {
        final WorkItemResource s = keyed( null );
        Mockito.when( svc.registry().actorForApiKey( 42, "owner" ) ).thenReturn( Actor.human( "owner", java.util.Set.of(), false ) );
        Mockito.when( svc.item( any(), anyString() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        assertEquals( 200, getWithKey( s, key( com.wikantik.auth.apikeys.ApiKeyService.Scope.ALL ), "Bearer wkk_x" ).status() );
        final ArgumentCaptor< Actor > seen = ArgumentCaptor.forClass( Actor.class );
        Mockito.verify( svc ).item( seen.capture(), eq( "PLAT-1" ) );
        assertEquals( Actor.Kind.HUMAN, seen.getValue().kind() );
        assertFalse( seen.getValue().admin() );
    }

    @Test
    void noBearerHeaderIsASessionRequestAsBefore() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        Mockito.when( svc.item( any(), anyString() ) ).thenReturn( WorkHttp.view( "PLAT-1", null, 1 ) );
        assertEquals( 200, getWithKey( s, null, null ).status() );
        Mockito.verify( svc ).item( eq( ALICE ), eq( "PLAT-1" ) );
    }

    @Test
    void aFailedKeyLookupIs500NotA401() throws Exception {
        final WorkItemResource s = keyed( ALICE );
        Mockito.when( svc.registry().actorForApiKey( 42, "owner" ) ).thenThrow( new java.sql.SQLException( "db down 10.0.0.9" ) );
        final WorkHttp.Result r = getWithKey( s, key( com.wikantik.auth.apikeys.ApiKeyService.Scope.WORK ), "Bearer wkk_x" );
        assertEquals( 500, r.status() );
        assertFalse( r.body().contains( "10.0.0.9" ) );
    }

    // ---- not-found identity: hidden = missing, byte for byte

    @Test
    void everyNewItemRouteAnswersAHiddenItemExactlyLikeAMissingOne() throws Exception {
        final String[][] routes = {
            { "POST", "/PLAT-1/claim", null },
            { "POST", "/PLAT-1/release", "{\"note\":\"x\"}" },
            { "POST", "/PLAT-1/handoff", "{\"expectedVersion\":1,\"assignee\":\"a\",\"note\":\"n\"}" } };
        for ( final String[] route : routes ) {
            Mockito.reset( claims );
            Mockito.when( claims.claim( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "hidden PLAT-1 of project PLAT" ) );
            Mockito.when( claims.release( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "hidden PLAT-1 of project PLAT" ) );
            Mockito.when( claims.handoff( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "hidden PLAT-1 of project PLAT" ) );
            final WorkHttp.Result hidden = send( route[ 0 ], "/api/work/items", route[ 1 ], route[ 2 ] );
            Mockito.reset( claims );
            Mockito.when( claims.claim( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "Not found." ) );
            Mockito.when( claims.release( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "Not found." ) );
            Mockito.when( claims.handoff( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "Not found." ) );
            final WorkHttp.Result missing = send( route[ 0 ], "/api/work/items", route[ 1 ].replace( "PLAT-1", "NOPE-9" ), route[ 2 ] );
            assertEquals( 404, hidden.status(), route[ 1 ] );
            assertEquals( missing.status(), hidden.status(), route[ 1 ] );
            assertEquals( missing.body(), hidden.body(), route[ 1 ] );
            assertFalse( hidden.body().contains( "PLAT" ), hidden.body() );
        }
    }
}
