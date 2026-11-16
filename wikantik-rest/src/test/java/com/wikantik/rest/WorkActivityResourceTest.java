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
import com.wikantik.work.Actor;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkEvent;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class WorkActivityResourceTest {

    private WorkService svc;
    private WorkActivityResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        Mockito.when( svc.registry() ).thenReturn( Mockito.mock( com.wikantik.work.WorkRegistryService.class ) );
        servlet = WorkHttp.wire( new WorkActivityResource(), svc, ALICE );
    }

    private WorkHttp.Result get( final Map< String, String > q ) throws Exception {
        return WorkHttp.call( servlet, "GET", "/api/work/activity", null, q, null );
    }

    private static WorkEvent event() {
        return new WorkEvent( 1, 1L, 7L, "PLAT-1", Instant.parse( "2026-11-01T10:00:00Z" ), "alice", Actor.Kind.HUMAN,
            null, "status", "draft", "ready", null );
    }

    @Test
    void listsActivityNewestFirstWithDisplayNames() throws Exception {
        Mockito.when( svc.activity( eq( ALICE ), eq( "PLAT" ), any(), any(), any(), any(), anyInt() ) ).thenReturn( List.of( event() ) );
        final JsonObject e = get( Map.of( "project", "PLAT", "before", "2026-11-02T00:00:00Z" ) ).json()
            .getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "Name of alice", e.get( "actorName" ).getAsString() );
        assertEquals( "Name of alice", e.get( "actor" ).getAsString(), "a member is never shown a login" );
        assertEquals( "2026-11-01T10:00:00Z", e.get( "at" ).getAsString() );
        Mockito.verify( svc ).activity( ALICE, "PLAT", Instant.parse( "2026-11-02T00:00:00Z" ), null, null, null, 0 );
    }

    @Test
    void beforeIdIsPassedWithBefore() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenReturn( List.of() );
        get( Map.of( "project", "PLAT", "before", "2026-11-02T00:00:00Z", "beforeId", "42" ) );
        Mockito.verify( svc ).activity( ALICE, "PLAT", Instant.parse( "2026-11-02T00:00:00Z" ), 42L, null, null, 0 );
    }

    @Test
    void itemNarrowsTheFeed() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenReturn( List.of() );
        get( Map.of( "project", "PLAT", "item", "PLAT-3" ) );
        Mockito.verify( svc ).activity( ALICE, "PLAT", null, null, "PLAT-3", null, 0 );
    }

    @Test
    void aMemberEventNamesPeopleByDisplayNameOnly() throws Exception {
        final WorkEvent member = new WorkEvent( 2, 1L, null, null, Instant.parse( "2026-11-01T10:00:00Z" ), "lena",
            Actor.Kind.HUMAN, null, "member", "user:bob:member", "user:bob:lead", null );
        final WorkEvent group = new WorkEvent( 3, 1L, null, null, Instant.parse( "2026-11-01T10:00:00Z" ), "lena",
            Actor.Kind.HUMAN, null, "member", null, "group:editors:viewer", null );
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) )
            .thenReturn( List.of( member, group ) );
        final com.google.gson.JsonArray events = get( Map.of( "project", "PLAT" ) ).json().getAsJsonArray();
        final JsonObject m = events.get( 0 ).getAsJsonObject();
        assertEquals( "user:Name of bob:member", m.get( "fromValue" ).getAsString() );
        assertEquals( "user:Name of bob:lead", m.get( "toValue" ).getAsString() );
        assertEquals( "Name of bob", m.get( "toValueName" ).getAsString() );
        assertTrue( m.get( "itemKey" ).isJsonNull() );
        final JsonObject g = events.get( 1 ).getAsJsonObject();
        assertEquals( "group:editors:viewer", g.get( "toValue" ).getAsString(), "a group name is not a login" );
        assertEquals( "editors", g.get( "toValueName" ).getAsString() );
        assertFalse( events.toString().contains( "\"bob\"" ) || events.toString().contains( ":bob:" ), events.toString() );
    }

    @Test
    void anAdminSeesTheLoginsInEvents() throws Exception {
        servlet = WorkHttp.wire( new WorkActivityResource(), svc, Actor.human( "root", java.util.Set.of(), true ) );
        final WorkEvent member = new WorkEvent( 2, 1L, null, null, Instant.parse( "2026-11-01T10:00:00Z" ), "lena",
            Actor.Kind.HUMAN, null, "member", null, "user:bob:lead", null );
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenReturn( List.of( member ) );
        final JsonObject m = get( Map.of( "project", "PLAT" ) ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "user:bob:lead", m.get( "toValue" ).getAsString() );
        assertEquals( "lena", m.get( "actor" ).getAsString() );
        assertEquals( "Name of lena", m.get( "actorName" ).getAsString() );
    }

    @Test
    void anUnparseableBeforeIdIs400OnBeforeId() throws Exception {
        final WorkHttp.Result r = get( Map.of( "project", "PLAT", "before", "2026-11-02T00:00:00Z", "beforeId", "x1" ) );
        assertEquals( 400, r.status() );
        assertEquals( "beforeId", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void limitIsClampedTo200() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenReturn( List.of() );
        get( Map.of( "project", "PLAT", "limit", "100000" ) );
        final ArgumentCaptor< Integer > limit = ArgumentCaptor.forClass( Integer.class );
        Mockito.verify( svc ).activity( any(), anyString(), any(), any(), any(), any(), limit.capture() );
        assertEquals( 200, limit.getValue() );
    }

    @Test
    void projectIsRequired() throws Exception {
        final WorkHttp.Result r = get( Map.of() );
        assertEquals( 400, r.status() );
        assertEquals( "project", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void aBadBeforeIs400() throws Exception {
        assertEquals( 400, get( Map.of( "project", "PLAT", "before", "yesterday" ) ).status() );
    }

    @Test
    void disabledFeatureIs404() throws Exception {
        Mockito.doReturn( null ).when( servlet ).workService();
        assertEquals( 404, get( Map.of( "project", "PLAT" ) ).status() );
    }

    @Test
    void anonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, get( Map.of( "project", "PLAT" ) ).status() );
    }

    @Test
    void notFoundMapsTo404WithoutEchoingTheKey() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final WorkHttp.Result r = get( Map.of( "project", "HIDDEN" ) );
        assertEquals( 404, r.status() );
        assertFalse( r.body().contains( "HIDDEN" ), r.body() );
    }

    @Test
    void deniedMapsTo403WithReason() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenThrow( new WorkDeniedException( "nope" ) );
        final WorkHttp.Result r = get( Map.of( "project", "PLAT" ) );
        assertEquals( 403, r.status() );
        assertEquals( "nope", r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    // ---- types on the project feed

    @Test
    void typesParamNarrows() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) ).thenReturn( List.of() );
        get( Map.of( "project", "PLAT", "types", "claimed, released,,claim_expired" ) );
        Mockito.verify( svc ).activity( ALICE, "PLAT", null, null, null,
            java.util.Set.of( "claimed", "released", "claim_expired" ), 0 );
    }

    @Test
    void unknownTypeIs400WhateverTheProject() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), any(), any(), anyInt() ) )
            .thenThrow( new com.wikantik.work.WorkValidationException( "types", "types must be among [x]." ) );
        final WorkHttp.Result real = get( Map.of( "project", "PLAT", "types", "nonsense" ) );
        final WorkHttp.Result ghost = get( Map.of( "project", "GHOST", "types", "nonsense" ) );
        assertEquals( 400, real.status() );
        assertEquals( real.body(), ghost.body() );
        assertEquals( "types", real.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    // ---- the assignment log

    private WorkHttp.Result log( final Map< String, String > q ) throws Exception {
        return WorkHttp.call( servlet, "GET", "/api/work/log", null, q, null );
    }

    private static com.wikantik.work.AssignmentLogEvent logged( final String actor, final Actor.Kind kind, final Long agent,
                                                                final String type, final String from, final String to ) {
        return new com.wikantik.work.AssignmentLogEvent( 5, 1L, "PLAT", "Platform", false, 7L, "PLAT-1", "Fix the thing",
            Instant.parse( "2026-11-01T10:00:00Z" ), actor, kind, agent, type, from, to, null );
    }

    @Test
    void logReturnsProjectKeyAndNames() throws Exception {
        Mockito.when( svc.assignmentLog( any(), any() ) ).thenReturn( List.of(
            logged( "codex@docker2", Actor.Kind.AGENT, 9L, "claimed", "ready", "in_progress" ) ) );
        final WorkHttp.Result res = log( Map.of() );
        assertEquals( 200, res.status(), res.body() );
        final JsonObject e = res.json().getAsJsonObject().get( "events" ).getAsJsonArray().get( 0 )
            .getAsJsonObject();
        assertEquals( "PLAT", e.get( "projectKey" ).getAsString() );
        assertEquals( "Platform", e.get( "projectName" ).getAsString() );
        assertEquals( "PLAT-1", e.get( "itemKey" ).getAsString() );
        assertEquals( "Fix the thing", e.get( "itemTitle" ).getAsString() );
        assertEquals( "agent", e.get( "actorKind" ).getAsString() );
        assertEquals( "claimed", e.get( "type" ).getAsString() );
        assertEquals( "5", e.get( "id" ).getAsString() );
        assertTrue( e.has( "actorName" ), e.toString() );
    }

    @Test
    void nonAdminSeesNoLoginInLog() throws Exception {
        Mockito.when( svc.assignmentLog( any(), any() ) ).thenReturn( List.of(
            logged( "lena", Actor.Kind.HUMAN, null, "assigned", null, "bob" ) ) );
        final String body = log( Map.of() ).body();
        assertFalse( body.contains( "\"lena\"" ) || body.contains( "\"bob\"" ), body );
        assertTrue( body.contains( "Name of lena" ) && body.contains( "Name of bob" ), body );
    }

    @Test
    void logPassesFiltersAndCursorThrough() throws Exception {
        Mockito.when( svc.assignmentLog( any(), any() ) ).thenReturn( List.of() );
        assertEquals( 200, log( Map.of( "project", "PLAT", "types", "claimed", "actorKind", "agent", "agent", "9",
            "before", "2026-11-02T00:00:00Z", "beforeId", "42", "limit", "5" ) ).status() );
        Mockito.verify( svc ).assignmentLog( ALICE, new com.wikantik.work.AssignmentLogQuery( "PLAT",
            Instant.parse( "2026-11-02T00:00:00Z" ), 42L, java.util.Set.of( "claimed" ), Actor.Kind.AGENT, 9L, 5 ) );
        assertEquals( 200, log( Map.of( "actorKind", "human" ) ).status() );
    }

    @Test
    void logRejectsBadParametersBeforeAnyLookup() throws Exception {
        for ( final Map< String, String > bad : List.of( Map.of( "actorKind", "robot" ), Map.of( "agent", "x" ),
                Map.of( "beforeId", "3" ), Map.of( "before", "yesterday" ), Map.of( "beforeId", "z", "before",
                    "2026-11-02T00:00:00Z" ) ) ) {
            assertEquals( 400, log( bad ).status(), bad.toString() );
        }
        Mockito.verify( svc, Mockito.never() ).assignmentLog( any(), any() );
    }

    @Test
    void logUnknownTypeIs400AndHiddenProjectIsTheSame404AsUnknown() throws Exception {
        Mockito.when( svc.assignmentLog( any(), any() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final WorkHttp.Result hidden = log( Map.of( "project", "HIDDEN" ) );
        final WorkHttp.Result ghost = log( Map.of( "project", "GHOST" ) );
        assertEquals( 404, hidden.status() );
        assertEquals( hidden.body().replace( "HIDDEN", "" ), ghost.body().replace( "GHOST", "" ) );
        assertFalse( hidden.body().contains( "HIDDEN" ) );
    }

    @Test
    void logDisabledIs404AndAnonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, log( Map.of() ).status() );
        Mockito.doReturn( null ).when( servlet ).workService();
        assertEquals( 404, log( Map.of() ).status() );
    }
}
