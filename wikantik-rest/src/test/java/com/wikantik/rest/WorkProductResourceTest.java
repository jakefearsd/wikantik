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
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeployGate;
import com.wikantik.work.Product;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class WorkProductResourceTest {

    private static final Product WIKI = new Product( 3, "wikantik", "Wikantik", "https://git/x", "main", "Dev",
        Autonomy.DELEGATED, DeployGate.HUMAN, null );

    private WorkService svc;
    private WorkProductResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        svc = Mockito.mock( WorkService.class );
        servlet = WorkHttp.wire( new WorkProductResource(), svc, ALICE );
        Mockito.when( svc.listProducts( ALICE ) ).thenReturn( List.of( WIKI ) );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/products", path, Map.of(), body );
    }

    @Test
    void aMemberSeesKeyNameAndArchivedOnly() throws Exception {
        final JsonObject p = call( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "wikantik", p.get( "key" ).getAsString() );
        assertEquals( java.util.Set.of( "key", "name", "archived" ), p.keySet() );
        assertFalse( p.get( "archived" ).getAsBoolean() );
    }

    @Test
    void anAdminSeesTheWholeProduct() throws Exception {
        final com.wikantik.work.Actor root = com.wikantik.work.Actor.human( "root", java.util.Set.of(), true );
        servlet = WorkHttp.wire( new WorkProductResource(), svc, root );
        Mockito.when( svc.listProducts( root ) ).thenReturn( List.of( WIKI ) );
        final JsonObject p = call( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "delegated", p.get( "autonomyCeiling" ).getAsString() );
        assertEquals( "human", p.get( "deployGate" ).getAsString() );
        assertFalse( p.get( "archived" ).getAsBoolean() );
    }

    @Test
    void createsAProductWith201() throws Exception {
        final WorkHttp.Result r = call( "POST", null, "{\"key\":\"wikantik\",\"name\":\"Wikantik\",\"deployGate\":\"agent\"}" );
        assertEquals( 201, r.status() );
        final ArgumentCaptor< Product > p = ArgumentCaptor.forClass( Product.class );
        Mockito.verify( svc ).createProduct( any(), p.capture() );
        assertEquals( DeployGate.AGENT, p.getValue().deployGate() );
        assertNull( p.getValue().autonomyCeiling() );
    }

    @Test
    void patchKeepsFieldsTheBodyOmits() throws Exception {
        assertEquals( 200, call( "PATCH", "/wikantik", "{\"name\":\"Renamed\"}" ).status() );
        final ArgumentCaptor< Product > p = ArgumentCaptor.forClass( Product.class );
        Mockito.verify( svc ).updateProduct( any(), p.capture() );
        assertEquals( "Renamed", p.getValue().name() );
        assertEquals( "main", p.getValue().defaultBranch() );
        assertEquals( 3, p.getValue().id() );
        assertEquals( Autonomy.DELEGATED, p.getValue().autonomyCeiling() );
    }

    @Test
    void patchOfAnUnknownProductIs404() throws Exception {
        assertEquals( 404, call( "PATCH", "/ghost", "{\"name\":\"x\"}" ).status() );
        Mockito.verify( svc, Mockito.never() ).updateProduct( any(), any() );
    }

    @Test
    void archivesAProductWith204() throws Exception {
        assertEquals( 204, call( "POST", "/wikantik/archive", null ).status() );
        Mockito.verify( svc ).archiveProduct( ALICE, "wikantik" );
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
    void notFoundMapsTo404() throws Exception {
        Mockito.doThrow( new WorkNotFoundException( "No such product: ghost" ) ).when( svc ).archiveProduct( any(), anyString() );
        final WorkHttp.Result r = call( "POST", "/ghost/archive", null );
        assertEquals( 404, r.status() );
        assertFalse( r.body().contains( "ghost" ), r.body() );
    }

    @Test
    void deniedMapsTo403WithReason() throws Exception {
        Mockito.when( svc.createProduct( any(), any() ) ).thenThrow( new WorkDeniedException( "Only wiki admins." ) );
        final WorkHttp.Result r = call( "POST", null, "{\"key\":\"abc\",\"name\":\"n\"}" );
        assertEquals( 403, r.status() );
        assertEquals( "Only wiki admins.", r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void conflictMapsTo409() throws Exception {
        Mockito.when( svc.createProduct( any(), any() ) ).thenThrow( new WorkConflictException( "exists" ) );
        assertEquals( 409, call( "POST", null, "{\"key\":\"wikantik\",\"name\":\"n\"}" ).status() );
    }

    @Test
    void validationMapsTo400() throws Exception {
        Mockito.when( svc.createProduct( any(), any() ) ).thenThrow( new WorkValidationException( "key", "bad key" ) );
        assertEquals( 400, call( "POST", null, "{\"key\":\"X\",\"name\":\"n\"}" ).status() );
        assertEquals( 400, call( "POST", null, "{\"key\":\"abc\",\"autonomyCeiling\":\"huge\"}" ).status() );
    }

    @Test
    void historyListsTheProductsEventsNewestFirst() throws Exception {
        final java.time.Instant t = java.time.Instant.parse( "2026-11-20T10:00:00Z" );
        Mockito.when( svc.productHistory( ALICE, "wikantik" ) ).thenReturn( List.of(
            new com.wikantik.work.ProductEvent( 2, t, "alice", com.wikantik.work.Actor.Kind.HUMAN, null,
                "deploy_gate", "human", "agent" ),
            new com.wikantik.work.ProductEvent( 1, t.minusSeconds( 60 ), "alice", com.wikantik.work.Actor.Kind.HUMAN, null,
                "name", "Old", "Wikantik" ) ) );
        final com.google.gson.JsonArray events = call( "GET", "/wikantik/history", null ).json().getAsJsonObject()
            .getAsJsonArray( "events" );
        assertEquals( 2, events.size() );
        final JsonObject first = events.get( 0 ).getAsJsonObject();
        assertEquals( "deploy_gate", first.get( "field" ).getAsString() );
        assertEquals( "human", first.get( "from" ).getAsString() );
        assertEquals( "agent", first.get( "to" ).getAsString() );
        assertEquals( "2026-11-20T10:00:00Z", first.get( "at" ).getAsString() );
        assertEquals( "Name of alice", first.get( "actorName" ).getAsString() );
        assertEquals( "Name of alice", first.get( "actor" ).getAsString(), "a non-admin caller sees no login" );
        assertEquals( "name", events.get( 1 ).getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void historyOfAnUnknownProductIs404() throws Exception {
        Mockito.when( svc.productHistory( any(), anyString() ) ).thenThrow( new WorkNotFoundException( "No such product: ghost" ) );
        assertEquals( 404, call( "GET", "/ghost/history", null ).status() );
    }

    @Test
    void historyForANonAdminIsRefused() throws Exception {
        Mockito.when( svc.productHistory( any(), anyString() ) ).thenThrow( new WorkDeniedException( "Only wiki admins." ) );
        Mockito.doThrow( new WorkDeniedException( "Only wiki admins." ) ).when( svc ).updateProduct( any(), any() );
        final int patch = call( "PATCH", "/wikantik", "{\"name\":\"x\"}" ).status();
        assertEquals( 403, patch );
        assertEquals( patch, call( "GET", "/wikantik/history", null ).status() );
    }
}
