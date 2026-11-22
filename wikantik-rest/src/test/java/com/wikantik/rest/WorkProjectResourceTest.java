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
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Member;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class WorkProjectResourceTest {

    private WorkService svc;
    private WorkProjectResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        svc = Mockito.mock( WorkService.class );
        servlet = WorkHttp.wire( new WorkProjectResource(), svc, ALICE );
        Mockito.when( svc.roleIn( any(), anyString() ) ).thenReturn( WorkRole.MEMBER );
        Mockito.when( svc.project( any(), anyString() ) ).thenAnswer( inv -> WorkHttp.project( inv.getArgument( 1 ) ) );
    }

    private WorkHttp.Result call( final String method, final String path, final Map< String, String > q,
                                  final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/projects", path, q, body );
    }

    @Test
    void listsVisibleProjectsWithRole() throws Exception {
        Mockito.when( svc.listProjectsWithRoles( ALICE ) ).thenReturn( List.of(
            new WorkService.ProjectRole( WorkHttp.project( "PLAT" ), WorkRole.MEMBER ) ) );
        final JsonObject p = call( "GET", null, Map.of(), null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "PLAT", p.get( "key" ).getAsString() );
        assertEquals( "member", p.get( "role" ).getAsString() );
        assertEquals( "supervised", p.get( "defaultAutonomy" ).getAsString() );
    }

    @Test
    void createsAProjectWith201() throws Exception {
        Mockito.when( svc.createProject( ALICE, "PLAT", "Platform", "d" ) ).thenReturn( WorkHttp.project( "PLAT" ) );
        final WorkHttp.Result r = call( "POST", null, Map.of(), "{\"key\":\"PLAT\",\"name\":\"Platform\",\"description\":\"d\"}" );
        assertEquals( 201, r.status() );
        assertEquals( "PLAT", r.json().getAsJsonObject().get( "key" ).getAsString() );
    }

    @Test
    void getsAndPatchesAProject() throws Exception {
        assertEquals( "PLAT", call( "GET", "/PLAT", Map.of(), null ).json().getAsJsonObject().get( "key" ).getAsString() );
        final WorkHttp.Result r = call( "PATCH", "/PLAT", Map.of(), "{\"name\":\"New\",\"defaultAutonomy\":\"delegated\"}" );
        assertEquals( 200, r.status() );
        Mockito.verify( svc ).updateProject( ALICE, "PLAT", "New", "desc", com.wikantik.work.Autonomy.DELEGATED );
    }

    @Test
    void archivesAProjectWith204() throws Exception {
        assertEquals( 204, call( "POST", "/PLAT/archive", Map.of(), null ).status() );
        Mockito.verify( svc ).archiveProject( ALICE, "PLAT" );
    }

    @Test
    void listsMembersWithDisplayNames() throws Exception {
        Mockito.when( svc.members( ALICE, "PLAT" ) ).thenReturn( List.of(
            new Member( 1, "bob", "user", WorkRole.LEAD ), new Member( 1, "Editors", "group", WorkRole.MEMBER ) ) );
        final var arr = call( "GET", "/PLAT/members", Map.of(), null ).json().getAsJsonArray();
        assertEquals( "Name of bob", arr.get( 0 ).getAsJsonObject().get( "displayName" ).getAsString() );
        assertEquals( "Name of bob", arr.get( 0 ).getAsJsonObject().get( "principal" ).getAsString(),
            "a member is shown no login, not even as the principal" );
        assertEquals( "lead", arr.get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
        assertEquals( "Editors", arr.get( 1 ).getAsJsonObject().get( "displayName" ).getAsString() );
        assertEquals( "Editors", arr.get( 1 ).getAsJsonObject().get( "principal" ).getAsString() );
        assertFalse( arr.get( 0 ).getAsJsonObject().get( "self" ).getAsBoolean() );
        assertEquals( "id-" + "bob".hashCode(), arr.get( 0 ).getAsJsonObject().get( "mentionId" ).getAsString() );
        assertFalse( arr.get( 1 ).getAsJsonObject().has( "mentionId" ), "a group cannot be mentioned" );
    }

    @Test
    void theCallersOwnRowIsMarkedSelf() throws Exception {
        Mockito.when( svc.members( ALICE, "PLAT" ) ).thenReturn( List.of( new Member( 1, "alice", "user", WorkRole.LEAD ),
            new Member( 1, "alice", "group", WorkRole.VIEWER ) ) );
        final var arr = call( "GET", "/PLAT/members", Map.of(), null ).json().getAsJsonArray();
        assertTrue( arr.get( 0 ).getAsJsonObject().get( "self" ).getAsBoolean() );
        assertFalse( arr.get( 1 ).getAsJsonObject().get( "self" ).getAsBoolean(), "a group is never you" );
    }

    @Test
    void anAdminSeesMemberLogins() throws Exception {
        servlet = WorkHttp.wire( new WorkProjectResource(), svc, Actor.human( "root", java.util.Set.of(), true ) );
        Mockito.when( svc.members( any(), anyString() ) ).thenReturn( List.of( new Member( 1, "bob", "user",
            WorkRole.LEAD ) ) );
        final JsonObject m = call( "GET", "/PLAT/members", Map.of(), null ).json().getAsJsonArray().get( 0 )
            .getAsJsonObject();
        assertEquals( "bob", m.get( "principal" ).getAsString() );
        assertEquals( "Name of bob", m.get( "displayName" ).getAsString() );
    }

    @Test
    void anArchivedRefusalIs409ReasonArchived() throws Exception {
        Mockito.doThrow( new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED ) )
            .when( svc ).putMember( any(), anyString(), anyString(), anyString(), any() );
        final WorkHttp.Result r = call( "PUT", "/PLAT/members", Map.of(),
            "{\"principal\":\"Name of bob\",\"principalKind\":\"user\",\"role\":\"member\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "archived", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void putsAndDeletesMembers() throws Exception {
        assertEquals( 204, call( "PUT", "/PLAT/members", Map.of(),
            "{\"principal\":\"bob\",\"principalKind\":\"user\",\"role\":\"member\"}" ).status() );
        Mockito.verify( svc ).putMember( ALICE, "PLAT", "bob", "user", WorkRole.MEMBER );
        assertEquals( 204, call( "DELETE", "/PLAT/members", Map.of(), "{\"principal\":\"bob\",\"principalKind\":\"user\"}" ).status() );
        Mockito.verify( svc ).removeMember( ALICE, "PLAT", "bob", "user" );
        assertEquals( 204, call( "DELETE", "/PLAT/members", Map.of( "principal", "carol", "principalKind", "user" ), null ).status() );
        Mockito.verify( svc ).removeMember( ALICE, "PLAT", "carol", "user" );
    }

    @Test
    void anAgentMemberIsShownByNameWithNoIdAndNoMentionId() throws Exception {
        final com.wikantik.work.WorkRegistryService registry = Mockito.mock( com.wikantik.work.WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        Mockito.when( registry.listAgents( ALICE ) ).thenReturn( List.of( new com.wikantik.work.Agent( 5, "builder", "", "", "alice",
            List.of(), java.time.Instant.EPOCH, null ) ) );
        Mockito.when( svc.members( ALICE, "PLAT" ) ).thenReturn( List.of( new Member( 1, "5", "agent", WorkRole.MEMBER ) ) );
        final JsonObject m = call( "GET", "/PLAT/members", Map.of(), null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "builder", m.get( "displayName" ).getAsString() );
        assertEquals( "builder", m.get( "principal" ).getAsString() );
        assertFalse( m.get( "self" ).getAsBoolean() );
        assertFalse( m.has( "mentionId" ), "an agent cannot be @-mentioned" );
    }

    @Test
    void anAgentMemberRowSaysWhetherItHasAnyEffectAndWhyNot() throws Exception {
        final com.wikantik.work.WorkRegistryService registry = Mockito.mock( com.wikantik.work.WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        Mockito.when( svc.members( ALICE, "PLAT" ) ).thenReturn( List.of( new Member( 1, "5", "agent", WorkRole.MEMBER ),
            new Member( 1, "6", "agent", WorkRole.MEMBER ), new Member( 1, "7", "agent", WorkRole.MEMBER ),
            new Member( 1, "bob", "user", WorkRole.LEAD ) ) );
        Mockito.when( registry.agentStandings( ALICE, "PLAT" ) ).thenReturn( Map.of(
            5L, com.wikantik.work.WorkRegistryService.MemberStanding.EFFECTIVE,
            6L, com.wikantik.work.WorkRegistryService.MemberStanding.SPONSOR_NOT_MEMBER ) );
        final var rows = call( "GET", "/PLAT/members", Map.of(), null ).json().getAsJsonArray();
        assertTrue( rows.get( 0 ).getAsJsonObject().get( "effective" ).getAsBoolean() );
        assertFalse( rows.get( 0 ).getAsJsonObject().has( "reason" ) );
        assertFalse( rows.get( 1 ).getAsJsonObject().get( "effective" ).getAsBoolean() );
        assertEquals( "sponsor_not_member", rows.get( 1 ).getAsJsonObject().get( "reason" ).getAsString() );
        assertFalse( rows.get( 2 ).getAsJsonObject().has( "effective" ), "unknown standing is left unsaid" );
        assertFalse( rows.get( 3 ).getAsJsonObject().has( "effective" ), "only agent rows carry it" );
    }

    @Test
    void aFailedStandingLookupStillListsTheMembers() throws Exception {
        final com.wikantik.work.WorkRegistryService registry = Mockito.mock( com.wikantik.work.WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        Mockito.when( svc.members( ALICE, "PLAT" ) ).thenReturn( List.of( new Member( 1, "5", "agent", WorkRole.MEMBER ) ) );
        Mockito.when( registry.agentStandings( ALICE, "PLAT" ) ).thenThrow( new java.sql.SQLException( "down" ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/members", Map.of(), null );
        assertEquals( 200, r.status() );
        assertFalse( r.json().getAsJsonArray().get( 0 ).getAsJsonObject().has( "effective" ) );
    }

    @Test
    void putsAndDeletesAnAgentMemberByName() throws Exception {
        assertEquals( 204, call( "PUT", "/PLAT/members", Map.of(),
            "{\"principal\":\"builder\",\"principalKind\":\"agent\",\"role\":\"member\"}" ).status() );
        Mockito.verify( svc ).putMember( ALICE, "PLAT", "builder", "agent", WorkRole.MEMBER );
        assertEquals( 204, call( "DELETE", "/PLAT/members", Map.of(), "{\"principal\":\"builder\",\"principalKind\":\"agent\"}" ).status() );
        Mockito.verify( svc ).removeMember( ALICE, "PLAT", "builder", "agent" );
    }

    @Test
    void anAgentMemberRefusalIsA400OnThePrincipal() throws Exception {
        Mockito.doThrow( new com.wikantik.work.WorkValidationException( "principal", "No such agent." ) ).when( svc )
            .putMember( any(), anyString(), anyString(), anyString(), any() );
        final WorkHttp.Result r = call( "PUT", "/PLAT/members", Map.of(),
            "{\"principal\":\"ghost\",\"principalKind\":\"agent\",\"role\":\"lead\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "principal", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void theBoardPassesTheQueueFilter() throws Exception {
        Mockito.when( svc.boardSummaries( any(), anyString(), any() ) ).thenReturn( List.of() );
        call( "GET", "/PLAT/board", Map.of( "queue", "triage" ), null );
        final org.mockito.ArgumentCaptor< com.wikantik.work.ItemFilter > f = org.mockito.ArgumentCaptor.forClass( com.wikantik.work.ItemFilter.class );
        Mockito.verify( svc ).boardSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( "triage", f.getValue().queue() );
    }

    @Test
    void boardFilterCarriesTypeAndLabels() throws Exception {
        Mockito.when( svc.boardSummaries( any(), anyString(), any() ) ).thenReturn( List.of() );
        WorkHttp.call( servlet, "GET", "/api/work/projects", "/PLAT/board", Map.of( "type", "bug" ), null,
            req -> Mockito.doReturn( new String[] { "ui", "Api,billing" } ).when( req ).getParameterValues( "label" ) );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).boardSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( com.wikantik.work.WorkType.BUG, f.getValue().type() );
        assertEquals( java.util.Set.of( "api", "billing", "ui" ), f.getValue().labels() );
    }

    @Test
    void labelsRouteListsCounts() throws Exception {
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.labelsOf( ALICE, "PLAT" ) ).thenReturn( List.of(
            new com.wikantik.work.LabelCount( "ui", 3 ), new com.wikantik.work.LabelCount( "api", 1 ) ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/labels", Map.of(), null );
        assertEquals( 200, r.status() );
        final JsonObject first = r.json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "ui", first.get( "name" ).getAsString() );
        assertEquals( 3, first.get( "items" ).getAsInt() );
    }

    @Test
    void labelsRouteForAStrangerIs404() throws Exception {
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.labelsOf( any(), anyString() ) )
            .thenThrow( new com.wikantik.work.WorkNotFoundException( "No such project." ) );
        assertEquals( 404, call( "GET", "/NOPE/labels", Map.of(), null ).status() );
    }

    @Test
    void anUnknownRoleIs400() throws Exception {
        final WorkHttp.Result r = call( "PUT", "/PLAT/members", Map.of(),
            "{\"principal\":\"bob\",\"principalKind\":\"user\",\"role\":\"boss\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "role", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void boardPassesFiltersAndIgnoresStatus() throws Exception {
        Mockito.when( svc.boardSummaries( any(), anyString(), any() ) ).thenReturn( List.of(
            new WorkService.SummaryColumn( WorkStatus.DRAFT, 1, List.of(
                WorkHttp.summary( WorkHttp.item( "PLAT-1", "carol", 1 ), 2, false, null, null ) ) ) ) );
        final JsonObject col = call( "GET", "/PLAT/board", Map.of( "assignee", "carol", "status", "done", "limit", "9999" ), null )
            .json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "draft", col.get( "status" ).getAsString() );
        final JsonObject card = col.getAsJsonArray( "items" ).get( 0 ).getAsJsonObject();
        assertEquals( "Name of carol", card.get( "assigneeName" ).getAsString() );
        assertEquals( 2, card.get( "commentCount" ).getAsInt() );
        assertEquals( false, card.get( "blocked" ).getAsBoolean() );
        assertEquals( true, card.get( "productKey" ).isJsonNull() );
        assertEquals( true, card.get( "parentKey" ).isJsonNull() );
        assertEquals( 1, col.get( "total" ).getAsInt() );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).boardSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( "carol", f.getValue().assignee() );
        assertEquals( null, f.getValue().statuses() );
    }

    @Test
    void disabledFeatureIs404() throws Exception {
        Mockito.doReturn( null ).when( servlet ).workService();
        assertEquals( 404, call( "GET", null, Map.of(), null ).status() );
    }

    @Test
    void anonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, call( "GET", null, Map.of(), null ).status() );
    }

    @Test
    void notFoundMapsTo404WithoutEchoingTheKey() throws Exception {
        Mockito.when( svc.project( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "no SECRET" ) );
        final WorkHttp.Result r = call( "GET", "/SECRET", Map.of(), null );
        assertEquals( 404, r.status() );
        assertFalse( r.body().contains( "SECRET" ), r.body() );
    }

    @Test
    void deniedMapsTo403WithReason() throws Exception {
        Mockito.doThrow( new WorkDeniedException( "Only leads manage members." ) ).when( svc )
            .putMember( any(), anyString(), any(), any(), any() );
        final WorkHttp.Result r = call( "PUT", "/PLAT/members", Map.of(), "{\"principal\":\"b\",\"principalKind\":\"user\",\"role\":\"member\"}" );
        assertEquals( 403, r.status() );
        assertEquals( "Only leads manage members.", r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void conflictMapsTo409() throws Exception {
        Mockito.doThrow( new WorkConflictException( "Project PLAT is archived." ) ).when( svc ).archiveProject( any(), anyString() );
        assertEquals( 409, call( "POST", "/PLAT/archive", Map.of(), null ).status() );
    }

    @Test
    void validationMapsTo400() throws Exception {
        Mockito.when( svc.createProject( any(), any(), any(), any() ) )
            .thenThrow( new WorkValidationException( "key", "A project key is 2 to 10 capital letters." ) );
        final JsonObject o = call( "POST", null, Map.of(), "{\"key\":\"x\",\"name\":\"n\"}" ).json().getAsJsonObject();
        assertEquals( "key", o.get( "field" ).getAsString() );
    }

    @Test
    void responseShowsDisplayNameNotLogin() throws Exception {
        Mockito.when( svc.members( any(), anyString() ) ).thenReturn( List.of( new Member( 1, "bob", "user", WorkRole.MEMBER ) ) );
        final String body = call( "GET", "/PLAT/members", Map.of(), null ).body();
        assertEquals( true, body.contains( "Name of bob" ) );
    }

    private com.wikantik.work.WorkFlowService flowService() {
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        final com.wikantik.work.WorkFlowService flow = Mockito.mock( com.wikantik.work.WorkFlowService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.flow() ).thenReturn( flow );
        return flow;
    }

    private static com.wikantik.work.FlowReport emptyFlow() {
        return new com.wikantik.work.FlowReport( java.time.LocalDate.parse( "2026-11-02" ),
            java.time.LocalDate.parse( "2026-11-15" ), "UTC", List.of(), com.wikantik.work.Durations.NONE,
            com.wikantik.work.Durations.NONE, List.of() );
    }

    private static com.wikantik.work.DoraReport emptyDora( final String product ) {
        return new com.wikantik.work.DoraReport( java.time.LocalDate.parse( "2026-11-02" ),
            java.time.LocalDate.parse( "2026-11-15" ), "UTC", product, List.of(), com.wikantik.work.Durations.NONE, 0, 0, 0,
            new com.wikantik.work.DoraReport.Rate( 0, 0 ), com.wikantik.work.Durations.NONE, 0,
            new com.wikantik.work.DoraReport.Rate( 0, 0 ), new com.wikantik.work.DoraReport.MergeMix( 0, 0, 0, 0, 0 ) );
    }

    @Test
    void flowPassesEveryFilter() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.project( any(), anyString(), any() ) ).thenReturn( emptyFlow() );
        final WorkHttp.Result r = call( "GET", "/PLAT/flow", Map.of( "from", "2026-11-02", "to", "2026-11-15",
            "sprint", "7", "label", "backend", "type", "bug", "assignee", "Mia Jones" ), null );
        assertEquals( 200, r.status() );
        final ArgumentCaptor< com.wikantik.work.FlowQuery > q = ArgumentCaptor.forClass( com.wikantik.work.FlowQuery.class );
        Mockito.verify( flow ).project( eq( ALICE ), eq( "PLAT" ), q.capture() );
        assertEquals( new com.wikantik.work.FlowQuery( java.time.LocalDate.parse( "2026-11-02" ),
            java.time.LocalDate.parse( "2026-11-15" ), 7L, "backend", com.wikantik.work.WorkType.BUG, "Mia Jones" ),
            q.getValue() );
        assertEquals( "UTC", r.json().getAsJsonObject().get( "zone" ).getAsString() );
    }

    @Test
    void flowWithNoParametersAsksForAllDefaults() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.project( any(), anyString(), any() ) ).thenReturn( emptyFlow() );
        assertEquals( 200, call( "GET", "/PLAT/flow", Map.of(), null ).status() );
        final ArgumentCaptor< com.wikantik.work.FlowQuery > q = ArgumentCaptor.forClass( com.wikantik.work.FlowQuery.class );
        Mockito.verify( flow ).project( eq( ALICE ), eq( "PLAT" ), q.capture() );
        assertEquals( new com.wikantik.work.FlowQuery( null, null, null, null, null, null ), q.getValue() );
    }

    @Test
    void aBadDateIs400NamingIt() throws Exception {
        flowService();
        for ( final String path : List.of( "/PLAT/flow", "/PLAT/dora" ) ) {
            final WorkHttp.Result from = call( "GET", path, Map.of( "from", "11/02/2026" ), null );
            assertEquals( 400, from.status() );
            assertEquals( "from", from.json().getAsJsonObject().get( "field" ).getAsString() );
            assertEquals( "Use a date like 2026-11-21.", from.json().getAsJsonObject().get( "message" ).getAsString() );
            final WorkHttp.Result to = call( "GET", path, Map.of( "to", "10000-01-01" ), null );
            assertEquals( 400, to.status() );
            assertEquals( "to", to.json().getAsJsonObject().get( "field" ).getAsString() );
        }
    }

    @Test
    void aWindowTheServiceRefusesIs400NamingTheField() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.project( any(), anyString(), any() ) ).thenThrow(
            new WorkValidationException( "to", "A report covers at most 366 days." ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/flow", Map.of( "from", "2020-01-01", "to", "2026-01-01" ), null );
        assertEquals( 400, r.status() );
        assertEquals( "to", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void aBadSprintIs400() throws Exception {
        flowService();
        final WorkHttp.Result r = call( "GET", "/PLAT/flow", Map.of( "sprint", "next" ), null );
        assertEquals( 400, r.status() );
        assertEquals( "sprint", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "A sprint is named by its number.", r.json().getAsJsonObject().get( "message" ).getAsString() );
        final WorkHttp.Result type = call( "GET", "/PLAT/flow", Map.of( "type", "nonsense" ), null );
        assertEquals( 400, type.status() );
        assertEquals( "type", type.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void flowForAStrangerIs404() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.project( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "No such project." ) );
        assertEquals( 404, call( "GET", "/PLAT/flow", Map.of(), null ).status() );
        Mockito.when( flow.dora( any(), anyString(), any() ) ).thenThrow( new WorkNotFoundException( "No such project." ) );
        assertEquals( 404, call( "GET", "/PLAT/dora", Map.of(), null ).status() );
    }

    @Test
    void doraPassesTheProduct() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.dora( any(), anyString(), any() ) ).thenReturn( emptyDora( "wikantik" ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/dora", Map.of( "from", "2026-11-02", "to", "2026-11-15",
            "product", "wikantik" ), null );
        assertEquals( 200, r.status() );
        final ArgumentCaptor< com.wikantik.work.DoraQuery > q = ArgumentCaptor.forClass( com.wikantik.work.DoraQuery.class );
        Mockito.verify( flow ).dora( eq( ALICE ), eq( "PLAT" ), q.capture() );
        assertEquals( new com.wikantik.work.DoraQuery( java.time.LocalDate.parse( "2026-11-02" ),
            java.time.LocalDate.parse( "2026-11-15" ), "wikantik" ), q.getValue() );
        assertEquals( "wikantik", r.json().getAsJsonObject().get( "product" ).getAsString() );
    }

    @Test
    void anEmptyDoraWindowSerialisesAsZerosAndNulls() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.dora( any(), anyString(), any() ) ).thenReturn( emptyDora( null ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/dora", Map.of(), null );
        assertFalse( r.body().contains( "NaN" ) || r.body().contains( "Infinity" ), r.body() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( 0, o.getAsJsonObject( "changeFailureRate" ).get( "denominator" ).getAsInt() );
        assertTrue( o.getAsJsonObject( "recoveryTime" ).get( "p50Seconds" ).isJsonNull() );
        assertTrue( o.get( "product" ).isJsonNull() );
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals( 404, call( "GET", "/PLAT/nothing", Map.of(), null ).status() );
        assertEquals( 404, call( "PUT", "/PLAT", Map.of(), "{}" ).status() );
    }

    private com.wikantik.work.WorkSprintsService sprints() {
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        final com.wikantik.work.WorkSprintsService sprints = Mockito.mock( com.wikantik.work.WorkSprintsService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.sprints() ).thenReturn( sprints );
        return sprints;
    }

    @Test
    void listsTheProjectsSprints() throws Exception {
        final com.wikantik.work.WorkSprintsService sprints = sprints();
        Mockito.when( sprints.list( ALICE, "PLAT" ) ).thenReturn( List.of( WorkSprintFixtures.planned( 9 ) ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/sprints", Map.of(), null );
        assertEquals( 200, r.status() );
        assertEquals( 9, r.json().getAsJsonArray().get( 0 ).getAsJsonObject().get( "id" ).getAsLong() );
    }

    @Test
    void createIs201AndRefusesAnUnknownField() throws Exception {
        final com.wikantik.work.WorkSprintsService sprints = sprints();
        Mockito.when( sprints.create( eq( ALICE ), eq( "PLAT" ), any() ) ).thenReturn( WorkSprintFixtures.planned( 9 ) );
        final WorkHttp.Result r = call( "POST", "/PLAT/sprints", Map.of(),
            "{\"name\":\"Sprint 1\",\"goal\":\"g\",\"startsOn\":\"2026-11-23\",\"endsOn\":\"2026-12-04\"}" );
        assertEquals( 201, r.status() );
        final ArgumentCaptor< com.wikantik.work.SprintDraft > d = ArgumentCaptor.forClass( com.wikantik.work.SprintDraft.class );
        Mockito.verify( sprints ).create( eq( ALICE ), eq( "PLAT" ), d.capture() );
        assertEquals( new com.wikantik.work.SprintDraft( "Sprint 1", "g", java.time.LocalDate.parse( "2026-11-23" ),
            java.time.LocalDate.parse( "2026-12-04" ) ), d.getValue() );
        final WorkHttp.Result bad = call( "POST", "/PLAT/sprints", Map.of(), "{\"name\":\"x\",\"state\":\"active\"}" );
        assertEquals( 400, bad.status() );
        assertEquals( "state", bad.json().getAsJsonObject().get( "field" ).getAsString() );
        final WorkHttp.Result date = call( "POST", "/PLAT/sprints", Map.of(), "{\"name\":\"x\",\"startsOn\":\"soon\"}" );
        assertEquals( "startsOn", date.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void velocityRoute() throws Exception {
        final com.wikantik.work.WorkSprintsService sprints = sprints();
        Mockito.when( sprints.velocity( ALICE, "PLAT" ) ).thenReturn( List.of( new com.wikantik.work.Velocity( 9, "Sprint 1",
            java.time.LocalDate.parse( "2026-12-04" ), 5, new java.math.BigDecimal( "10.0" ), 4, new java.math.BigDecimal( "8.5" ) ) ) );
        final JsonObject v = call( "GET", "/PLAT/velocity", Map.of(), null ).json().getAsJsonObject()
            .getAsJsonArray( "sprints" ).get( 0 ).getAsJsonObject();
        assertEquals( 9, v.get( "id" ).getAsLong() );
        assertEquals( "Sprint 1", v.get( "name" ).getAsString() );
        assertEquals( "2026-12-04", v.get( "endsOn" ).getAsString() );
        assertEquals( 5, v.get( "committedItems" ).getAsInt() );
        assertEquals( 10.0, v.get( "committedPoints" ).getAsDouble() );
        assertEquals( 4, v.get( "completedItems" ).getAsInt() );
        assertEquals( 8.5, v.get( "completedPoints" ).getAsDouble() );
    }

    @Test
    void boardFilterCarriesTheSprint() throws Exception {
        Mockito.when( svc.boardSummaries( any(), anyString(), any() ) ).thenReturn( List.of() );
        assertEquals( 200, call( "GET", "/PLAT/board", Map.of( "sprint", "active" ), null ).status() );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).boardSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( com.wikantik.work.SprintRef.parse( "active" ), f.getValue().sprint() );
        final WorkHttp.Result bad = call( "GET", "/PLAT/board", Map.of( "sprint", "x" ), null );
        assertEquals( 400, bad.status() );
        assertEquals( "sprint", bad.json().getAsJsonObject().get( "field" ).getAsString() );
    }
}
