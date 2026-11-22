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

import com.wikantik.work.Day;
import com.wikantik.work.SprintDraft;
import com.wikantik.work.SprintReport;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkSprintsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

/** The sprint routes: what they pass to the service and how its refusals go on the wire. */
class WorkSprintResourceTest {

    private WorkSprintsService sprints;
    private WorkSprintResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        final WorkService svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        sprints = Mockito.mock( WorkSprintsService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.sprints() ).thenReturn( sprints );
        servlet = WorkHttp.wire( new WorkSprintResource(), svc, ALICE );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/sprints", path, Map.of(), body );
    }

    @Test
    void getReturnsTheSprint() throws Exception {
        Mockito.when( sprints.get( ALICE, 9L ) ).thenReturn( WorkSprintFixtures.planned( 9 ) );
        final WorkHttp.Result r = call( "GET", "/9", null );
        assertEquals( 200, r.status() );
        assertEquals( "Sprint 1", r.json().getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void patchKeepsAbsentFields() throws Exception {
        Mockito.when( sprints.get( ALICE, 9L ) ).thenReturn( WorkSprintFixtures.planned( 9 ) );
        Mockito.when( sprints.update( eq( ALICE ), eq( 9L ), eq( 3 ), any() ) ).thenReturn( WorkSprintFixtures.planned( 9 ) );
        assertEquals( 200, call( "PATCH", "/9", "{\"version\":3,\"name\":\"Renamed\"}" ).status() );
        final ArgumentCaptor< SprintDraft > d = ArgumentCaptor.forClass( SprintDraft.class );
        Mockito.verify( sprints ).update( eq( ALICE ), eq( 9L ), eq( 3 ), d.capture() );
        assertEquals( new SprintDraft( "Renamed", "Ship it", LocalDate.parse( "2026-11-23" ),
            LocalDate.parse( "2026-12-04" ) ), d.getValue() );
    }

    @Test
    void startPassesTheVersion() throws Exception {
        Mockito.when( sprints.start( ALICE, 9L, 3 ) ).thenReturn( WorkSprintFixtures.planned( 9 ) );
        assertEquals( 200, call( "POST", "/9/start", "{\"version\":3}" ).status() );
        Mockito.verify( sprints ).start( ALICE, 9L, 3 );
    }

    @Test
    void closePassesTheTargetOrNull() throws Exception {
        Mockito.when( sprints.close( any(), anyLong(), anyInt(), any() ) ).thenReturn( WorkSprintFixtures.closed( 9 ) );
        assertEquals( 200, call( "POST", "/9/close", "{\"version\":3,\"moveOpenTo\":12}" ).status() );
        Mockito.verify( sprints ).close( ALICE, 9L, 3, 12L );
        assertEquals( 200, call( "POST", "/9/close", "{\"version\":4,\"moveOpenTo\":null}" ).status() );
        Mockito.verify( sprints ).close( ALICE, 9L, 4, null );
        assertEquals( 200, call( "POST", "/9/close", "{\"version\":5}" ).status() );
        Mockito.verify( sprints ).close( ALICE, 9L, 5, null );
        assertEquals( "moveOpenTo", call( "POST", "/9/close", "{\"version\":5,\"moveOpenTo\":\"x\"}" )
            .json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void aStrangerGets404OnEverySprintRoute() throws Exception {
        final WorkNotFoundException gone = new WorkNotFoundException( "Not found." );
        Mockito.when( sprints.get( any(), anyLong() ) ).thenThrow( gone );
        Mockito.when( sprints.update( any(), anyLong(), anyInt(), any() ) ).thenThrow( gone );
        Mockito.when( sprints.start( any(), anyLong(), anyInt() ) ).thenThrow( gone );
        Mockito.when( sprints.close( any(), anyLong(), anyInt(), any() ) ).thenThrow( gone );
        final String[][] routes = { { "GET", "/9", null }, { "PATCH", "/9", "{\"version\":1,\"name\":\"x\"}" },
            { "POST", "/9/start", "{\"version\":1}" }, { "POST", "/9/close", "{\"version\":1}" } };
        for ( final String[] route : routes ) {
            final WorkHttp.Result r = call( route[ 0 ], route[ 1 ], route[ 2 ] );
            assertEquals( 404, r.status(), route[ 0 ] + route[ 1 ] );
            assertEquals( "Not found.", r.json().getAsJsonObject().get( "message" ).getAsString() );
        }
    }

    @Test
    void reportRoute() throws Exception {
        final WorkService.ItemSummary added = WorkHttp.summary( WorkHttp.item( "PLAT-1", "alice", 1 ), 0, false, null, null );
        final WorkService.ItemSummary removed = WorkHttp.summary( WorkHttp.item( "PLAT-2", null, 1 ), 0, false, null, null );
        final List< Day > days = List.of(
            new Day( LocalDate.parse( "2026-11-23" ), new BigDecimal( "5.0" ), 2, new BigDecimal( "5.0" ), 2 ),
            new Day( LocalDate.parse( "2026-11-24" ), new BigDecimal( "2.5" ), 1, new BigDecimal( "7.5" ), 3 ) );
        Mockito.when( sprints.report( ALICE, 9L ) ).thenReturn(
            new SprintReport( WorkSprintFixtures.planned( 9 ), days, List.of( added ), List.of( removed ) ) );
        final WorkHttp.Result r = call( "GET", "/9/report", null );
        assertEquals( 200, r.status() );
        final com.google.gson.JsonObject o = r.json().getAsJsonObject();
        assertEquals( "Sprint 1", o.getAsJsonObject( "sprint" ).get( "name" ).getAsString() );
        assertEquals( 2, o.getAsJsonArray( "days" ).size() );
        final com.google.gson.JsonObject d2 = o.getAsJsonArray( "days" ).get( 1 ).getAsJsonObject();
        assertEquals( "2026-11-24", d2.get( "date" ).getAsString() );
        assertEquals( 2.5, d2.get( "remainingPoints" ).getAsDouble() );
        assertEquals( 1, d2.get( "remainingItems" ).getAsInt() );
        assertEquals( 7.5, d2.get( "scopePoints" ).getAsDouble() );
        assertEquals( 3, d2.get( "scopeItems" ).getAsInt() );
        assertEquals( "PLAT-1", o.getAsJsonArray( "added" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( "PLAT-2", o.getAsJsonArray( "removed" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
    }

    @Test
    void aStrangerGets404OnTheReport() throws Exception {
        Mockito.when( sprints.report( any(), anyLong() ) ).thenThrow( new WorkNotFoundException( "Not found." ) );
        assertEquals( 404, call( "GET", "/9/report", null ).status() );
        assertEquals( 404, call( "GET", "/abc/report", null ).status() );
    }

    @Test
    void aNonNumericIdIs404() throws Exception {
        assertEquals( 404, call( "GET", "/abc", null ).status() );
        assertEquals( 404, call( "POST", "/abc/start", "{\"version\":1}" ).status() );
        Mockito.verifyNoInteractions( sprints );
    }

    @Test
    void eachConflictReasonIs409WithItsReason() throws Exception {
        for ( final String reason : new String[] { "stale", "sprint_active", "sprint_closed", "bad_transition",
            "bad_target", "archived" } ) {
            Mockito.doThrow( new WorkConflictException( "no", reason ) ).when( sprints ).start( any(), anyLong(), anyInt() );
            final WorkHttp.Result r = call( "POST", "/9/start", "{\"version\":1}" );
            assertEquals( 409, r.status(), reason );
            assertEquals( reason, r.json().getAsJsonObject().get( "reason" ).getAsString() );
        }
    }

    @Test
    void aMissingVersionIs400NamingVersion() throws Exception {
        for ( final String[] route : new String[][] { { "PATCH", "/9" }, { "POST", "/9/start" }, { "POST", "/9/close" } } ) {
            final WorkHttp.Result r = call( route[ 0 ], route[ 1 ], "{}" );
            assertEquals( 400, r.status(), route[ 1 ] );
            assertEquals( "version", r.json().getAsJsonObject().get( "field" ).getAsString() );
        }
    }

    @Test
    void anAgentTokenIsRefusedWith403() throws Exception {
        Mockito.when( sprints.start( any(), anyLong(), anyInt() ) )
            .thenThrow( new WorkDeniedException( "A person plans a sprint." ) );
        final WorkHttp.Result r = call( "POST", "/9/start", "{\"version\":1}" );
        assertEquals( 403, r.status() );
        assertEquals( "A person plans a sprint.", r.json().getAsJsonObject().get( "message" ).getAsString() );
        assertNull( r.json().getAsJsonObject().get( "field" ) );
    }
}
