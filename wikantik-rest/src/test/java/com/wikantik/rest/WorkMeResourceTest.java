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
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class WorkMeResourceTest {

    private WorkService svc;
    private WorkMeResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        Mockito.when( svc.deployments() ).thenReturn( Mockito.mock( com.wikantik.work.WorkDeploymentsService.class ) );
        servlet = WorkHttp.wire( new WorkMeResource(), svc, ALICE );
    }

    private WorkHttp.Result get() throws Exception {
        return WorkHttp.call( servlet, "GET", "/api/work/me", null, Map.of(), null );
    }

    @Test
    void returnsWaitingOnItemsAndProjectsWithRole() throws Exception {
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( new com.wikantik.work.WaitingItem( WorkHttp.item( "PLAT-1", "alice", 1 ), "assigned", null ) ) );
        Mockito.when( svc.listProjectsWithRoles( ALICE ) ).thenReturn( List.of(
            new WorkService.ProjectRole( WorkHttp.project( "PLAT" ), WorkRole.LEAD ) ) );
        final JsonObject o = get().json().getAsJsonObject();
        assertEquals( "PLAT-1", o.getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( "lead", o.getAsJsonArray( "projects" ).get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
    }

    @Test
    void responseShowsDisplayNameNotLogin() throws Exception {
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( new com.wikantik.work.WaitingItem( WorkHttp.item( "PLAT-1", "alice", 1 ), "assigned", null ) ) );
        final JsonObject item = get().json().getAsJsonObject().getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject();
        assertEquals( "Name of alice", item.get( "assigneeName" ).getAsString() );
        assertEquals( "Name of alice", item.get( "assignee" ).getAsString(), "not even your own login" );
    }

    private static com.wikantik.work.WorkEvent handoff( final String actor, final com.wikantik.work.Actor.Kind kind,
                                                        final Long agentId ) {
        return new com.wikantik.work.WorkEvent( 9, 1, 7L, "PLAT-1", java.time.Instant.parse( "2026-11-15T09:00:00Z" ), actor,
            kind, agentId, "handoff", "in_progress|", "review|alice", "please review the migration" );
    }

    @Test
    void waitingOnCarriesHandoffByNameAndNote() throws Exception {
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( new com.wikantik.work.WaitingItem(
            WorkHttp.item( "PLAT-1", "alice", 1 ), "assigned", handoff( "carol", com.wikantik.work.Actor.Kind.HUMAN, null ) ) ) );
        final JsonObject w = get().json().getAsJsonObject().getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject()
            .getAsJsonObject( "waiting" );
        assertEquals( "assigned", w.get( "reason" ).getAsString() );
        assertEquals( "Name of carol", w.get( "byName" ).getAsString() );
        assertEquals( "human", w.get( "byKind" ).getAsString() );
        assertEquals( "please review the migration", w.get( "note" ).getAsString() );
        assertEquals( "2026-11-15T09:00:00Z", w.get( "at" ).getAsString() );
        assertEquals( false, w.has( "by" ), "a login is for admins only" );
    }

    @Test
    void anAdminAlsoGetsTheLoginInBy() throws Exception {
        final com.wikantik.work.Actor admin = com.wikantik.work.Actor.human( "root", java.util.Set.of(), true );
        servlet = WorkHttp.wire( new WorkMeResource(), svc, admin );
        Mockito.when( svc.waitingOn( admin ) ).thenReturn( List.of( new com.wikantik.work.WaitingItem(
            WorkHttp.item( "PLAT-1", "root", 1 ), "assigned", handoff( "carol", com.wikantik.work.Actor.Kind.HUMAN, null ) ) ) );
        Mockito.when( svc.listProjectsWithRoles( admin ) ).thenReturn( List.of() );
        final JsonObject w = get().json().getAsJsonObject().getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject()
            .getAsJsonObject( "waiting" );
        assertEquals( "carol", w.get( "by" ).getAsString() );
        assertEquals( "Name of carol", w.get( "byName" ).getAsString() );
    }

    @Test
    void anAgentHandoffIsByTheAgentsCurrentName() throws Exception {
        final com.wikantik.work.WorkRegistryService registry = Mockito.mock( com.wikantik.work.WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        Mockito.when( registry.listAgents( ALICE ) ).thenReturn( List.of( new com.wikantik.work.Agent( 5, "builder", "", "", "alice",
            List.of(), java.time.Instant.EPOCH, null ) ) );
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( new com.wikantik.work.WaitingItem(
            WorkHttp.item( "PLAT-1", "alice", 1 ), "assigned", handoff( "builder-old", com.wikantik.work.Actor.Kind.AGENT, 5L ) ) ) );
        final JsonObject w = get().json().getAsJsonObject().getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject()
            .getAsJsonObject( "waiting" );
        assertEquals( "builder", w.get( "byName" ).getAsString() );
        assertEquals( "agent", w.get( "byKind" ).getAsString() );
        assertEquals( false, w.has( "by" ) );
    }

    @Test
    void aMentionHasNoHandoffBehindIt() throws Exception {
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( new com.wikantik.work.WaitingItem(
            WorkHttp.item( "PLAT-1", "bob", 1 ), "mentioned", null ) ) );
        final JsonObject w = get().json().getAsJsonObject().getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject()
            .getAsJsonObject( "waiting" );
        assertEquals( "mentioned", w.get( "reason" ).getAsString() );
        assertEquals( true, w.get( "byName" ).isJsonNull() );
        assertEquals( true, w.get( "note" ).isJsonNull() );
    }

    @Test
    void disabledFeatureIs404() throws Exception {
        Mockito.doReturn( null ).when( servlet ).workService();
        assertEquals( 404, get().status() );
    }

    @Test
    void anonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, get().status() );
    }

    @Test
    void unexpectedFailureIs500() throws Exception {
        Mockito.when( svc.waitingOn( any() ) ).thenThrow( new java.sql.SQLException( "boom" ) );
        final WorkHttp.Result r = get();
        assertEquals( 500, r.status() );
        assertEquals( false, r.body().contains( "boom" ) );
    }

    @Test
    void anArchivedProjectSaysSo() throws Exception {
        Mockito.when( svc.waitingOn( any() ) ).thenReturn( List.of() );
        final com.wikantik.work.Project p = WorkHttp.project( "OLD" );
        final com.wikantik.work.Project archived = new com.wikantik.work.Project( p.id(), p.key(), p.name(),
            p.description(), p.defaultAutonomy(), p.createdAt(), java.time.Instant.parse( "2026-11-10T00:00:00Z" ) );
        Mockito.when( svc.listProjectsWithRoles( ALICE ) ).thenReturn( List.of(
            new WorkService.ProjectRole( archived, WorkRole.MEMBER ),
            new WorkService.ProjectRole( WorkHttp.project( "NEW" ), WorkRole.MEMBER ) ) );
        final var projects = get().json().getAsJsonObject().getAsJsonArray( "projects" );
        assertEquals( true, projects.get( 0 ).getAsJsonObject().get( "archived" ).getAsBoolean() );
        assertEquals( false, projects.get( 1 ).getAsJsonObject().get( "archived" ).getAsBoolean() );
    }
}
