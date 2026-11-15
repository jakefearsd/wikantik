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
import com.wikantik.work.BoardColumn;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Member;
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
        Mockito.when( svc.listProjects( ALICE ) ).thenReturn( List.of( WorkHttp.project( "PLAT" ) ) );
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
        assertEquals( "bob", arr.get( 0 ).getAsJsonObject().get( "principal" ).getAsString() );
        assertEquals( "lead", arr.get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
        assertEquals( "Editors", arr.get( 1 ).getAsJsonObject().get( "displayName" ).getAsString() );
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
    void anUnknownRoleIs400() throws Exception {
        final WorkHttp.Result r = call( "PUT", "/PLAT/members", Map.of(),
            "{\"principal\":\"bob\",\"principalKind\":\"user\",\"role\":\"boss\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "role", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void boardPassesFiltersAndIgnoresStatus() throws Exception {
        Mockito.when( svc.board( any(), anyString(), any() ) ).thenReturn( List.of(
            new BoardColumn( WorkStatus.DRAFT, 1, List.of( WorkHttp.item( "PLAT-1", "carol", 1 ) ) ) ) );
        final JsonObject col = call( "GET", "/PLAT/board", Map.of( "assignee", "carol", "status", "done", "limit", "9999" ), null )
            .json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "draft", col.get( "status" ).getAsString() );
        assertEquals( "Name of carol", col.getAsJsonArray( "items" ).get( 0 ).getAsJsonObject().get( "assigneeName" ).getAsString() );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc ).board( eq( ALICE ), eq( "PLAT" ), f.capture() );
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

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals( 404, call( "GET", "/PLAT/nothing", Map.of(), null ).status() );
        assertEquals( 404, call( "PUT", "/PLAT", Map.of(), "{}" ).status() );
    }
}
