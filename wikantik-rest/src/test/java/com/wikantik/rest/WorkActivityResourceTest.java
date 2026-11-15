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
        Mockito.when( svc.activity( eq( ALICE ), eq( "PLAT" ), any(), any(), anyInt() ) ).thenReturn( List.of( event() ) );
        final JsonObject e = get( Map.of( "project", "PLAT", "before", "2026-11-02T00:00:00Z" ) ).json()
            .getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "Name of alice", e.get( "actorName" ).getAsString() );
        assertEquals( "alice", e.get( "actor" ).getAsString() );
        assertEquals( "2026-11-01T10:00:00Z", e.get( "at" ).getAsString() );
        Mockito.verify( svc ).activity( ALICE, "PLAT", Instant.parse( "2026-11-02T00:00:00Z" ), null, 0 );
    }

    @Test
    void beforeIdIsPassedWithBefore() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), anyInt() ) ).thenReturn( List.of() );
        get( Map.of( "project", "PLAT", "before", "2026-11-02T00:00:00Z", "beforeId", "42" ) );
        Mockito.verify( svc ).activity( ALICE, "PLAT", Instant.parse( "2026-11-02T00:00:00Z" ), 42L, 0 );
    }

    @Test
    void anUnparseableBeforeIdIs400OnBeforeId() throws Exception {
        final WorkHttp.Result r = get( Map.of( "project", "PLAT", "before", "2026-11-02T00:00:00Z", "beforeId", "x1" ) );
        assertEquals( 400, r.status() );
        assertEquals( "beforeId", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void limitIsClampedTo200() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), anyInt() ) ).thenReturn( List.of() );
        get( Map.of( "project", "PLAT", "limit", "100000" ) );
        final ArgumentCaptor< Integer > limit = ArgumentCaptor.forClass( Integer.class );
        Mockito.verify( svc ).activity( any(), anyString(), any(), any(), limit.capture() );
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
        Mockito.when( svc.activity( any(), anyString(), any(), any(), anyInt() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final WorkHttp.Result r = get( Map.of( "project", "HIDDEN" ) );
        assertEquals( 404, r.status() );
        assertFalse( r.body().contains( "HIDDEN" ), r.body() );
    }

    @Test
    void deniedMapsTo403WithReason() throws Exception {
        Mockito.when( svc.activity( any(), anyString(), any(), any(), anyInt() ) ).thenThrow( new WorkDeniedException( "nope" ) );
        final WorkHttp.Result r = get( Map.of( "project", "PLAT" ) );
        assertEquals( 403, r.status() );
        assertEquals( "nope", r.json().getAsJsonObject().get( "message" ).getAsString() );
    }
}
