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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.work.Actor;
import com.wikantik.work.IntakeDraft;
import com.wikantik.work.ReportableProject;
import com.wikantik.work.ReportedComment;
import com.wikantik.work.ReportedEvent;
import com.wikantik.work.ReportedItem;
import com.wikantik.work.ReportedItemView;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkIntakeService;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkType;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class WorkIntakeResourceTest {

    private static final Actor RITA = Actor.human( "rita", Set.of(), false );
    private static final Actor AGENT = Actor.agent( 7, "codex@docker2" );
    private static final Instant T0 = Instant.parse( "2026-11-01T10:00:00Z" );
    private static final String NOT_FOUND_BODY = "{\"error\":true,\"status\":404,\"message\":\"Not found.\"}";

    private WorkService svc;
    private WorkIntakeService intake;
    private WorkIntakeResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        intake = Mockito.mock( WorkIntakeService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.intake() ).thenReturn( intake );
        servlet = WorkHttp.wire( new WorkIntakeResource(), svc, RITA );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/intake", path, Map.of(), body );
    }

    static ReportedItemView view() {
        return new ReportedItemView( "PLAT-4", "PLAT", "Platform", WorkLevel.STORY, WorkType.BUG, WorkStatus.DRAFT,
            "Broken", "It breaks", "<p>It breaks</p>", "Person RITA", T0, T0.plusSeconds( 60 ),
            List.of( new ReportedComment( 9, "Person RITA", Actor.Kind.HUMAN, "more", "<p>more</p>", List.of(), T0, null, null ),
                new ReportedComment( 10, "codex@docker2", Actor.Kind.AGENT, "looking", "<p>looking</p>", List.of( "Lena L" ), T0, null, null ) ),
            List.of( new ReportedEvent( 1, "created", T0, "Person RITA", Actor.Kind.HUMAN, null, null ),
                new ReportedEvent( 2, "status", T0, "Lena L", Actor.Kind.HUMAN, "draft", "ready" ) ), false );
    }

    @Test
    void submitReturns201WithTheReportedView() throws Exception {
        Mockito.when( intake.submit( eq( RITA ), any() ) ).thenReturn( view() );
        final WorkHttp.Result r = call( "POST", "/items",
            "{\"projectKey\":\"PLAT\",\"level\":\"task\",\"type\":\"bug\",\"title\":\"Broken\",\"body\":\"It breaks\"}" );
        assertEquals( 201, r.status() );
        assertEquals( "PLAT-4", r.json().getAsJsonObject().get( "key" ).getAsString() );
        final ArgumentCaptor< IntakeDraft > d = ArgumentCaptor.forClass( IntakeDraft.class );
        Mockito.verify( intake ).submit( eq( RITA ), d.capture() );
        assertEquals( new IntakeDraft( "PLAT", WorkLevel.TASK, WorkType.BUG, "Broken", "It breaks" ), d.getValue() );
    }

    @Test
    void theProjectMayBeNamedProjectAndLevelAndTypeAreOptional() throws Exception {
        Mockito.when( intake.submit( eq( RITA ), any() ) ).thenReturn( view() );
        assertEquals( 201, call( "POST", "/items", "{\"project\":\"PLAT\",\"title\":\"T\"}" ).status() );
        final ArgumentCaptor< IntakeDraft > d = ArgumentCaptor.forClass( IntakeDraft.class );
        Mockito.verify( intake ).submit( eq( RITA ), d.capture() );
        assertEquals( new IntakeDraft( "PLAT", null, null, "T", null ), d.getValue() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "assignee", "priority", "parentKey", "productKey", "labels", "sprint", "estimate", "queue",
        "dueOn", "kind", "status" } )
    void aMembersFieldIsRefusedByName( final String field ) throws Exception {
        final WorkHttp.Result r = call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":\"T\",\"" + field + "\":\"x\"}" );
        assertEquals( 400, r.status() );
        assertEquals( field, r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "A project member sets this after triage.", r.json().getAsJsonObject().get( "message" ).getAsString() );
        Mockito.verifyNoInteractions( intake );
    }

    @Test
    void anUnknownFieldIsRefused() throws Exception {
        final WorkHttp.Result r = call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":\"T\",\"mood\":\"x\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "mood", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "Unknown field: mood", r.json().getAsJsonObject().get( "message" ).getAsString() );
        Mockito.verifyNoInteractions( intake );
    }

    @Test
    void aWrongTypedFieldIsA400OnThatField() throws Exception {
        final WorkHttp.Result r = call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":[\"a\"]}" );
        assertEquals( 400, r.status() );
        assertEquals( "title", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( 400, call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":\"T\",\"level\":\"giant\"}" ).status() );
    }

    @Test
    void anOverlongBodyIsA400NamingBodyAndAnHugeRequestIsA413() throws Exception {
        Mockito.when( intake.submit( any(), any() ) ).thenThrow(
            new WorkValidationException( "body", "The text may be at most 20000 characters." ) );
        final WorkHttp.Result r = call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":\"T\",\"body\":\"x\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "body", r.json().getAsJsonObject().get( "field" ).getAsString() );
        final String huge = "{\"projectKey\":\"PLAT\",\"title\":\"T\",\"body\":\"" + "x".repeat( 300_000 ) + "\"}";
        assertEquals( 413, call( "POST", "/items", huge ).status() );
    }

    private WorkIntakeResource asAgent() {
        return WorkHttp.wire( new WorkIntakeResource(), svc, AGENT );
    }

    /** Refuses ONLY the agent actor: it fails if the route stops passing the caller through. */
    private void refuseOnlyAgents( final String text ) throws Exception {
        Mockito.when( intake.submit( Mockito.argThat( a -> a != null && a.kind() == Actor.Kind.AGENT ), any() ) )
            .thenThrow( new WorkDeniedException( text ) );
        Mockito.when( intake.item( Mockito.argThat( a -> a != null && a.kind() == Actor.Kind.AGENT ), anyString() ) )
            .thenThrow( new WorkNotFoundException( "an agent has no reporter view" ) );
        Mockito.when( intake.submit( eq( RITA ), any() ) ).thenReturn( view() );
        Mockito.when( intake.item( eq( RITA ), anyString() ) ).thenReturn( view() );
    }

    @Test
    void anAgentFilingIs403WithThePolicyText() throws Exception {
        final String text = "A person files intake; an agent creates items in the projects it belongs to.";
        refuseOnlyAgents( text );
        final WorkHttp.Result r = WorkHttp.call( asAgent(), "POST", "/api/work/intake", "/items", Map.of(),
            "{\"projectKey\":\"PLAT\",\"title\":\"T\"}" );
        assertEquals( 403, r.status() );
        assertEquals( text, r.json().getAsJsonObject().get( "message" ).getAsString() );
        assertEquals( 201, call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":\"T\"}" ).status(),
            "a person is not refused by the same stub" );
    }

    @Test
    void anArchivedProjectIs409Archived() throws Exception {
        Mockito.when( intake.submit( any(), any() ) ).thenThrow(
            new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED ) );
        final WorkHttp.Result r = call( "POST", "/items", "{\"projectKey\":\"PLAT\",\"title\":\"T\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "archived", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void anUnknownProjectIsTheSame404() throws Exception {
        Mockito.when( intake.submit( any(), any() ) ).thenThrow( new WorkNotFoundException( "no project NOPE" ) );
        final WorkHttp.Result r = call( "POST", "/items", "{\"projectKey\":\"NOPE\",\"title\":\"T\"}" );
        assertEquals( 404, r.status() );
        assertEquals( com.google.gson.JsonParser.parseString( NOT_FOUND_BODY ), r.json() );
    }

    @Test
    void theReportedViewHasExactlyTheseKeys() throws Exception {
        Mockito.when( intake.item( RITA, "PLAT-4" ) ).thenReturn( view() );
        final JsonObject o = call( "GET", "/items/PLAT-4", null ).json().getAsJsonObject();
        assertEquals( Set.of( "key", "projectKey", "projectName", "level", "type", "status", "title", "body", "bodyHtml",
            "reporter", "reporterName", "createdAt", "updatedAt", "comments", "events", "archived" ), o.keySet() );
        for ( final String absent : List.of( "assignee", "parentKey", "links", "productKey", "queue", "claimedByAgent",
            "priority", "labels", "sprint", "estimate", "viewerRole", "id", "projectId", "reportedFromOutside" ) ) {
            assertFalse( o.has( absent ), absent );
        }
        assertEquals( "story", o.get( "level" ).getAsString() );
        assertEquals( "bug", o.get( "type" ).getAsString() );
        assertEquals( "draft", o.get( "status" ).getAsString() );
        assertEquals( "2026-11-01T10:00:00Z", o.get( "createdAt" ).getAsString() );
        final JsonObject agentComment = o.getAsJsonArray( "comments" ).get( 1 ).getAsJsonObject();
        assertEquals( "codex@docker2", agentComment.get( "authorName" ).getAsString() );
        assertEquals( "agent", agentComment.get( "authorKind" ).getAsString() );
        assertEquals( false, agentComment.get( "mine" ).getAsBoolean() );
    }

    @Test
    void theReporterIsShownByDisplayName() throws Exception {
        Mockito.when( intake.item( RITA, "PLAT-4" ) ).thenReturn( view() );
        final JsonObject o = call( "GET", "/items/PLAT-4", null ).json().getAsJsonObject();
        assertEquals( "Person RITA", o.get( "reporter" ).getAsString() );
        assertEquals( "Person RITA", o.get( "reporterName" ).getAsString() );
        assertFalse( o.toString().contains( "\"rita\"" ), "the service's names are not mapped a second time" );
        assertFalse( o.toString().contains( "Name of" ), "no login-to-name mapping ran over the intake JSON" );
    }

    @Test
    void mineIsTheViewersOwnCommentByDisplayName() throws Exception {
        // the test servlet names a login "Name of <login>"; the service's view names rita "Person RITA"
        servlet = WorkHttp.wire( new WorkIntakeResource(), svc, RITA );
        Mockito.doReturn( "Person RITA" ).when( servlet ).displayName( "rita" );
        Mockito.when( intake.item( RITA, "PLAT-4" ) ).thenReturn( view() );
        final JsonArray comments = call( "GET", "/items/PLAT-4", null ).json().getAsJsonObject().getAsJsonArray( "comments" );
        assertEquals( true, comments.get( 0 ).getAsJsonObject().get( "mine" ).getAsBoolean() );
        assertEquals( false, comments.get( 1 ).getAsJsonObject().get( "mine" ).getAsBoolean() );
    }

    @Test
    void reportableCarriesKeyNameAndRoleOnly() throws Exception {
        Mockito.when( intake.reportableProjects( RITA ) ).thenReturn( List.of(
            new ReportableProject( "PLAT", "Platform", WorkRole.REPORTER ) ) );
        final JsonObject p = call( "GET", "/projects", null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( Set.of( "key", "name", "role" ), p.keySet() );
        assertEquals( "reporter", p.get( "role" ).getAsString() );
        assertFalse( p.has( "description" ) );
        assertFalse( p.has( "defaultAutonomy" ) );
    }

    @Test
    void listsWhatTheCallerFiled() throws Exception {
        Mockito.when( intake.reported( RITA ) ).thenReturn( List.of( new ReportedItem( "PLAT-4", "PLAT", "Platform",
            WorkLevel.TASK, WorkType.FEATURE, WorkStatus.READY, "Broken", T0, T0, 3 ) ) );
        final JsonObject i = call( "GET", "/items", null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( Set.of( "key", "projectKey", "projectName", "level", "type", "status", "title", "createdAt",
            "updatedAt", "commentCount" ), i.keySet() );
        assertEquals( 3, i.get( "commentCount" ).getAsInt() );
        assertEquals( "ready", i.get( "status" ).getAsString() );
    }

    @Test
    void anEmptyListIsAnEmptyArray() throws Exception {
        Mockito.when( intake.reported( RITA ) ).thenReturn( List.of() );
        assertEquals( "[]", call( "GET", "/items", null ).body() );
    }

    @Test
    void notFoundIs404ForAnItemAndForAComment() throws Exception {
        Mockito.when( intake.item( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "not rita's PLAT-1" ) );
        Mockito.when( intake.comment( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "not rita's PLAT-1" ) );
        final WorkHttp.Result item = call( "GET", "/items/PLAT-1", null );
        final WorkHttp.Result comment = call( "POST", "/items/PLAT-1/comments", "{\"body\":\"hi\"}" );
        assertEquals( 404, item.status() );
        assertEquals( 404, comment.status() );
        assertEquals( item.body(), comment.body(), "one body" );
        assertFalse( item.body().contains( "PLAT-1" ), "the key is not echoed" );
        assertFalse( item.body().contains( "rita" ) );
    }

    @Test
    void aMalformedKeyIsTheSame404AsAnUnknownOne() throws Exception {
        Mockito.when( intake.item( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "no such item" ) );
        assertEquals( call( "GET", "/items/PLAT-1", null ).body(), call( "GET", "/items/not%20a%20key;--", null ).body() );
        assertEquals( 404, call( "GET", "/items/PLAT-1/extra/path", null ).status() );
    }

    @Test
    void aCommentReturns201WithTheCommentOnly() throws Exception {
        Mockito.when( intake.comment( RITA, "PLAT-4", "more" ) ).thenReturn(
            new ReportedComment( 11, "Person RITA", Actor.Kind.HUMAN, "more", "<p>more</p>", List.of(), T0, null, null ) );
        final WorkHttp.Result r = call( "POST", "/items/PLAT-4/comments", "{\"body\":\"more\"}" );
        assertEquals( 201, r.status() );
        final JsonObject c = r.json().getAsJsonObject();
        assertEquals( Set.of( "id", "author", "authorName", "authorKind", "body", "bodyHtml", "mentions", "createdAt",
            "editedAt", "removedAt", "mine" ), c.keySet() );
        assertEquals( 11, c.get( "id" ).getAsInt() );
    }

    @Test
    void aCommentBodyOverTheCapIs400OnBodyAndUnknownFieldsAreRefused() throws Exception {
        Mockito.when( intake.comment( any(), anyString(), any() ) ).thenThrow(
            new WorkValidationException( "body", "The text may be at most 20000 characters." ) );
        final WorkHttp.Result r = call( "POST", "/items/PLAT-4/comments", "{\"body\":\"x\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "body", r.json().getAsJsonObject().get( "field" ).getAsString() );
        final WorkHttp.Result extra = call( "POST", "/items/PLAT-4/comments", "{\"body\":\"x\",\"mentions\":[\"a\"]}" );
        assertEquals( 400, extra.status() );
        assertEquals( "mentions", extra.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void anAgentOnTheIntakeItemRouteIsRefusedAsTheServiceRefuses() throws Exception {
        refuseOnlyAgents( "unused" );
        assertEquals( 404, WorkHttp.call( asAgent(), "GET", "/api/work/intake", "/items/PLAT-4", Map.of(), null ).status() );
        assertEquals( 200, call( "GET", "/items/PLAT-4", null ).status(), "a person is not refused by the same stub" );
    }

    @Test
    void anonymousIs401AndADisabledFeatureIs404() throws Exception {
        final WorkIntakeResource anon = WorkHttp.wire( new WorkIntakeResource(), svc, null );
        assertEquals( 401, WorkHttp.call( anon, "GET", "/api/work/intake", "/projects", Map.of(), null ).status() );
        final WorkIntakeResource off = WorkHttp.wire( new WorkIntakeResource(), null, RITA );
        assertEquals( 404, WorkHttp.call( off, "GET", "/api/work/intake", "/projects", Map.of(), null ).status() );
    }

    @Test
    void anUnexpectedFailureIs500WithNoDetail() throws Exception {
        Mockito.when( intake.reported( any() ) ).thenThrow( new IllegalStateException( "secret detail" ) );
        final WorkHttp.Result r = call( "GET", "/items", null );
        assertEquals( 500, r.status() );
        assertFalse( r.body().contains( "secret detail" ) );
        assertTrue( r.body().contains( "Internal error." ) );
    }
}
