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
import com.wikantik.work.FindFilter;
import com.wikantik.work.FindParams;
import com.wikantik.work.SavedFilter;
import com.wikantik.work.SavedFilterDraft;
import com.wikantik.work.SavedFilterView;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkFindService;
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
import java.util.TreeMap;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class WorkFilterResourceTest {

    private WorkService svc;
    private WorkFindService find;
    private WorkFilterResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        find = Mockito.mock( WorkFindService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.find() ).thenReturn( find );
        servlet = WorkHttp.wire( new WorkFilterResource(), svc, ALICE );
    }

    private static SavedFilter filter( final long id, final String owner, final String project,
                                       final Map< String, String > params ) {
        return new SavedFilter( id, owner, "Mine", project == null ? null : 5L, project, project != null,
            new TreeMap<>( params ), Instant.parse( "2026-11-20T10:00:00Z" ) );
    }

    private static SavedFilterView view( final SavedFilter f ) {
        return new SavedFilterView( f, "alice".equals( f.owner() ), "alice".equals( f.owner() ) );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/filters", path, Map.of(), body );
    }

    private static String field( final WorkHttp.Result r ) {
        return r.json().getAsJsonObject().get( "field" ).getAsString();
    }

    @Test
    void listsFiltersWithDisplayNamesAndNoLogins() throws Exception {
        Mockito.when( find.saved( ALICE ) ).thenReturn( List.of( view( filter( 3, "alice", null,
            Map.of( "assignee", "alice", "reporter", "me", "status", "ready" ) ) ) ) );
        final WorkHttp.Result r = call( "GET", null, null );
        assertEquals( 200, r.status() );
        final JsonObject v = r.json().getAsJsonObject().getAsJsonArray( "filters" ).get( 0 ).getAsJsonObject();
        assertEquals( 3, v.get( "id" ).getAsLong() );
        assertEquals( "Name of alice", v.get( "ownerName" ).getAsString() );
        assertEquals( "Name of alice", v.getAsJsonObject( "filter" ).get( "assignee" ).getAsString() );
        assertEquals( "me", v.getAsJsonObject( "filter" ).get( "reporter" ).getAsString() );
        assertEquals( "ready", v.getAsJsonObject( "filter" ).get( "status" ).getAsString() );
        assertTrue( v.get( "project" ).isJsonNull() );
        assertTrue( v.get( "mine" ).getAsBoolean() );
        assertEquals( "2026-11-20T10:00:00Z", v.get( "createdAt" ).getAsString() );
        assertFalse( v.has( "owner" ) );
        assertFalse( r.body().replace( "Name of alice", "" ).contains( "alice" ), r.body() );
    }

    @Test
    void aNonAdminViewerGetsDisplayNamesThatResolveForThem() throws Exception {
        Mockito.when( find.saved( ALICE ) ).thenReturn( List.of( view( filter( 3, "carol", "PLAT",
            Map.of( "assignee", "carol", "reporter", "dave", "projects", "PLAT" ) ) ) ) );
        final JsonObject v = call( "GET", null, null ).json().getAsJsonObject().getAsJsonArray( "filters" ).get( 0 )
            .getAsJsonObject();
        final Map< String, String > shown = new TreeMap<>();
        v.getAsJsonObject( "filter" ).entrySet().forEach( e -> shown.put( e.getKey(), e.getValue().getAsString() ) );
        // what the service's person lookup does for a non-admin: a display name resolves, a login does not
        final Map< String, String > loginsByName = Map.of( "Name of carol", "carol", "Name of dave", "dave" );
        final FindFilter parsed = FindParams.parse( shown );
        assertEquals( "carol", loginsByName.get( parsed.assignee() ), "the mapped assignee resolves" );
        assertEquals( "dave", loginsByName.get( parsed.reporter() ), "the mapped reporter resolves" );
        assertEquals( java.util.Set.of( "PLAT" ), parsed.projectKeys() );
        assertEquals( null, loginsByName.get( "carol" ), "a raw login would resolve to nobody" );
        assertFalse( v.toString().contains( "\"carol\"" ) || v.toString().contains( "\"dave\"" ), v.toString() );
    }

    @Test
    void aSharedFilterOfAnotherPersonShowsTheirNameAndTheProjectKey() throws Exception {
        Mockito.when( find.saved( ALICE ) ).thenReturn( List.of( new SavedFilterView(
            filter( 4, "carol", "PLAT", Map.of( "projects", "PLAT" ) ), false, false ) ) );
        final JsonObject v = call( "GET", null, null ).json().getAsJsonObject().getAsJsonArray( "filters" ).get( 0 )
            .getAsJsonObject();
        assertEquals( "PLAT", v.get( "project" ).getAsString() );
        assertTrue( v.get( "shared" ).getAsBoolean() );
        assertFalse( v.get( "mine" ).getAsBoolean() );
        assertFalse( v.get( "mayDelete" ).getAsBoolean() );
        assertEquals( "Name of carol", v.get( "ownerName" ).getAsString() );
    }

    @Test
    void saveAnswers201AndPassesTheDraft() throws Exception {
        Mockito.when( find.save( eq( ALICE ), any() ) ).thenReturn( view( filter( 9, "alice", "PLAT",
            Map.of( "projects", "PLAT", "status", "ready" ) ) ) );
        final WorkHttp.Result r = call( "POST", null,
            "{\"name\":\"Ready\",\"project\":\"PLAT\",\"shared\":true,\"filter\":{\"status\":\"ready\",\"assignee\":\"Smith, Jo\"}}" );
        assertEquals( 201, r.status() );
        assertEquals( 9, r.json().getAsJsonObject().get( "id" ).getAsLong() );
        final ArgumentCaptor< SavedFilterDraft > d = ArgumentCaptor.forClass( SavedFilterDraft.class );
        Mockito.verify( find ).save( eq( ALICE ), d.capture() );
        assertEquals( "Ready", d.getValue().name() );
        assertEquals( "PLAT", d.getValue().projectKey() );
        assertTrue( d.getValue().shared() );
        assertEquals( Map.of( "status", "ready", "assignee", "Smith, Jo" ), d.getValue().filter() );
    }

    @Test
    void saveDefaultsToPrivateWithNoProject() throws Exception {
        Mockito.when( find.save( eq( ALICE ), any() ) ).thenReturn( view( filter( 9, "alice", null, Map.of() ) ) );
        assertEquals( 201, call( "POST", null, "{\"name\":\"x\"}" ).status() );
        final ArgumentCaptor< SavedFilterDraft > d = ArgumentCaptor.forClass( SavedFilterDraft.class );
        Mockito.verify( find ).save( eq( ALICE ), d.capture() );
        assertFalse( d.getValue().shared() );
        assertEquals( null, d.getValue().projectKey() );
        assertEquals( Map.of(), d.getValue().filter() );
    }

    @Test
    void saveRefusesAnUnknownFieldByName() throws Exception {
        final WorkHttp.Result r = call( "POST", null, "{\"name\":\"x\",\"owner\":\"bob\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "owner", field( r ) );
        Mockito.verify( find, Mockito.never() ).save( any(), any() );
    }

    @Test
    void filterValuesMustBeText() throws Exception {
        for ( final String filter : List.of( "{\"priority\":1}", "{\"watching\":true}", "{\"status\":[\"ready\"]}",
            "{\"status\":null}", "\"x\"", "[]" ) ) {
            final WorkHttp.Result r = call( "POST", null, "{\"name\":\"x\",\"filter\":" + filter + "}" );
            assertEquals( 400, r.status(), filter );
            assertEquals( "filter", field( r ), filter );
        }
    }

    @Test
    void sharedMustBeABoolean() throws Exception {
        final WorkHttp.Result r = call( "POST", null, "{\"name\":\"x\",\"shared\":\"yes\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "shared", field( r ) );
    }

    @Test
    void aValidationErrorFromTheServiceIsA400OnItsField() throws Exception {
        Mockito.when( find.save( any(), any() ) ).thenThrow( new WorkValidationException( "assignee", "No one has that name." ) );
        final WorkHttp.Result r = call( "POST", null, "{\"name\":\"x\",\"filter\":{\"assignee\":\"Nobody\"}}" );
        assertEquals( 400, r.status() );
        assertEquals( "assignee", field( r ) );
        assertFalse( r.body().contains( "Nobody" ) );
    }

    @Test
    void renameTakesAName() throws Exception {
        Mockito.when( find.rename( ALICE, 3L, "New" ) ).thenReturn( view( filter( 3, "alice", null, Map.of() ) ) );
        final WorkHttp.Result r = call( "PATCH", "/3", "{\"name\":\"New\"}" );
        assertEquals( 200, r.status() );
        assertEquals( 3, r.json().getAsJsonObject().get( "id" ).getAsLong() );
        Mockito.verify( find ).rename( ALICE, 3L, "New" );
        assertEquals( 400, call( "PATCH", "/3", "{\"name\":\"New\",\"shared\":true}" ).status() );
    }

    @Test
    void deleteIs204() throws Exception {
        final WorkHttp.Result r = call( "DELETE", "/3", null );
        assertEquals( 204, r.status() );
        Mockito.verify( find ).delete( ALICE, 3L );
    }

    @Test
    void anUnknownIdIs404() throws Exception {
        Mockito.doThrow( new WorkNotFoundException( "Not found." ) ).when( find ).delete( any(), anyLong() );
        Mockito.when( find.rename( any(), anyLong(), any() ) ).thenThrow( new WorkNotFoundException( "Not found." ) );
        final WorkHttp.Result del = call( "DELETE", "/404", null );
        final WorkHttp.Result ren = call( "PATCH", "/404", "{\"name\":\"x\"}" );
        assertEquals( 404, del.status() );
        assertEquals( 404, ren.status() );
        assertEquals( del.body(), ren.body() );
        final WorkHttp.Result garbage = call( "DELETE", "/abc", null );
        assertEquals( 404, garbage.status() );
        assertEquals( del.body(), garbage.body() );
    }

    @Test
    void aStrangerGetsTheBodyOfAnUnknownIdOnEveryRoute() throws Exception {
        final WorkNotFoundException hidden = new WorkNotFoundException( "Not found." );
        Mockito.doThrow( hidden ).when( find ).delete( any(), anyLong() );
        Mockito.when( find.rename( any(), anyLong(), any() ) ).thenThrow( hidden );
        Mockito.when( find.save( any(), any() ) ).thenThrow( hidden );
        final String unknown = call( "DELETE", "/1", null ).body();
        assertEquals( unknown, call( "PATCH", "/1", "{\"name\":\"x\"}" ).body() );
        final WorkHttp.Result save = call( "POST", null, "{\"name\":\"x\",\"project\":\"SECRET\",\"shared\":true}" );
        assertEquals( 404, save.status() );
        assertEquals( unknown, save.body() );
        assertFalse( save.body().contains( "SECRET" ) );
    }

    @Test
    void aDenialIs403() throws Exception {
        Mockito.doThrow( new WorkDeniedException( "Only a lead may do that." ) ).when( find ).delete( any(), anyLong() );
        assertEquals( 403, call( "DELETE", "/3", null ).status() );
    }

    @Test
    void aDuplicateIs409WithReasonExists() throws Exception {
        Mockito.when( find.save( any(), any() ) ).thenThrow( new WorkConflictException(
            "You already have a filter with that name.", WorkConflictException.EXISTS ) );
        final WorkHttp.Result r = call( "POST", null, "{\"name\":\"x\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "exists", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void disabledFeatureIs404() throws Exception {
        Mockito.doReturn( null ).when( servlet ).workService();
        assertEquals( 404, call( "GET", null, null ).status() );
    }

    @Test
    void anonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, call( "GET", null, null ).status() );
    }

    @Test
    void anEmptyListIsAnEmptyArray() throws Exception {
        Mockito.when( find.saved( ALICE ) ).thenReturn( List.of() );
        final JsonArray a = call( "GET", null, null ).json().getAsJsonObject().getAsJsonArray( "filters" );
        assertEquals( 0, a.size() );
    }
}
