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
        servlet = WorkHttp.wire( new WorkMeResource(), svc, ALICE );
    }

    private WorkHttp.Result get() throws Exception {
        return WorkHttp.call( servlet, "GET", "/api/work/me", null, Map.of(), null );
    }

    @Test
    void returnsWaitingOnItemsAndProjectsWithRole() throws Exception {
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( WorkHttp.item( "PLAT-1", "alice", 1 ) ) );
        Mockito.when( svc.listProjects( ALICE ) ).thenReturn( List.of( WorkHttp.project( "PLAT" ) ) );
        Mockito.when( svc.roleIn( ALICE, "PLAT" ) ).thenReturn( WorkRole.LEAD );
        final JsonObject o = get().json().getAsJsonObject();
        assertEquals( "PLAT-1", o.getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( "lead", o.getAsJsonArray( "projects" ).get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
    }

    @Test
    void responseShowsDisplayNameNotLogin() throws Exception {
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of( WorkHttp.item( "PLAT-1", "alice", 1 ) ) );
        final JsonObject item = get().json().getAsJsonObject().getAsJsonArray( "waitingOn" ).get( 0 ).getAsJsonObject();
        assertEquals( "Name of alice", item.get( "assigneeName" ).getAsString() );
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
    void aProjectThatVanishesMidListIsLeftOut() throws Exception {
        Mockito.when( svc.waitingOn( any() ) ).thenReturn( List.of() );
        Mockito.when( svc.listProjects( ALICE ) ).thenReturn( List.of( WorkHttp.project( "GONE" ) ) );
        Mockito.when( svc.roleIn( any(), anyString() ) ).thenThrow( new com.wikantik.work.WorkNotFoundException( "x" ) );
        assertEquals( 0, get().json().getAsJsonObject().getAsJsonArray( "projects" ).size() );
    }
}
