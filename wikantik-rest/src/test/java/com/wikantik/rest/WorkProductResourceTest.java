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
import static org.mockito.ArgumentMatchers.eq;

class WorkProductResourceTest {

    private static final Product WIKI = new Product( 3, "wikantik", "Wikantik", "https://git/x", "main", "Dev",
        Autonomy.DELEGATED, DeployGate.HUMAN, null );

    private WorkService svc;
    private WorkProductResource servlet;
    private com.wikantik.work.WorkCodeHostService codeHost;

    @BeforeEach
    void setUp() throws Exception {
        svc = Mockito.mock( WorkService.class );
        servlet = WorkHttp.wire( new WorkProductResource(), svc, ALICE );
        Mockito.when( svc.listProducts( ALICE ) ).thenReturn( List.of( WIKI ) );
        codeHost = Mockito.mock( com.wikantik.work.WorkCodeHostService.class );
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.codeHost() ).thenReturn( codeHost );
        Mockito.when( codeHost.hooks( any() ) ).thenReturn( Map.of() );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/products", path, Map.of(), body );
    }

    private com.wikantik.work.WorkFlowService flowService() {
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        final com.wikantik.work.WorkFlowService flow = Mockito.mock( com.wikantik.work.WorkFlowService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.flow() ).thenReturn( flow );
        return flow;
    }

    private static com.wikantik.work.DoraReport emptyDora() {
        return new com.wikantik.work.DoraReport( java.time.LocalDate.parse( "2026-11-02" ),
            java.time.LocalDate.parse( "2026-11-15" ), "UTC", "wikantik", List.of(), com.wikantik.work.Durations.NONE, 0, 0, 0,
            new com.wikantik.work.DoraReport.Rate( 0, 0 ), com.wikantik.work.Durations.NONE, 0,
            new com.wikantik.work.DoraReport.Rate( 0, 0 ), new com.wikantik.work.DoraReport.MergeMix( 0, 0, 0, 0, 0 ) );
    }

    @Test
    void productDoraForANonAdminIs403() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.doraForProduct( any(), anyString(), any(), any() ) ).thenThrow(
            new WorkDeniedException( "Only a wiki administrator may read a product's figures." ) );
        assertEquals( 403, call( "GET", "/wikantik/dora", null ).status() );
    }

    @Test
    void productDoraForAnAdminIs200() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.doraForProduct( any(), anyString(), any(), any() ) ).thenReturn( emptyDora() );
        final WorkHttp.Result r = WorkHttp.call( servlet, "GET", "/api/work/products", "/wikantik/dora",
            Map.of( "from", "2026-11-02", "to", "2026-11-15" ), null );
        assertEquals( 200, r.status() );
        assertEquals( "wikantik", r.json().getAsJsonObject().get( "product" ).getAsString() );
        Mockito.verify( flow ).doraForProduct( ALICE, "wikantik", java.time.LocalDate.parse( "2026-11-02" ),
            java.time.LocalDate.parse( "2026-11-15" ) );
    }

    @Test
    void productDoraForAnUnknownProductIs404AndABadDateIs400() throws Exception {
        final com.wikantik.work.WorkFlowService flow = flowService();
        Mockito.when( flow.doraForProduct( any(), anyString(), any(), any() ) ).thenThrow(
            new WorkNotFoundException( "No such product: nope" ) );
        assertEquals( 404, call( "GET", "/nope/dora", null ).status() );
        final WorkHttp.Result bad = WorkHttp.call( servlet, "GET", "/api/work/products", "/wikantik/dora",
            Map.of( "from", "soon" ), null );
        assertEquals( 400, bad.status() );
        assertEquals( "from", bad.json().getAsJsonObject().get( "field" ).getAsString() );
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

    private static final com.wikantik.work.Actor ROOT = com.wikantik.work.Actor.human( "root", java.util.Set.of(), true );
    private static final String SECRET = "ab12".repeat( 16 );

    private void asRoot() throws Exception {
        servlet = WorkHttp.wire( new WorkProductResource(), svc, ROOT );
        Mockito.when( svc.listProducts( ROOT ) ).thenReturn( List.of( WIKI ) );
    }

    private void issued() throws Exception {
        final java.time.Instant on = java.time.Instant.parse( "2026-11-20T10:00:00Z" );
        Mockito.when( codeHost.enableHook( ROOT, "wikantik" ) ).thenReturn( new com.wikantik.work.HookCreated(
            new com.wikantik.work.HookStatus( "wikantik", true, on, null ), SECRET ) );
        Mockito.when( codeHost.hooks( ROOT ) ).thenReturn( Map.of( "wikantik",
            new com.wikantik.work.HookStatus( "wikantik", true, on, on.plusSeconds( 60 ) ) ) );
    }

    @Test
    void turningTheHookOnReturnsTheSecretAndTheUrlsOnce() throws Exception {
        asRoot();
        issued();
        final WorkHttp.Result r = call( "POST", "/wikantik/hook", null );
        assertEquals( 200, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( SECRET, o.get( "secret" ).getAsString() );
        assertEquals( "/api/work/hooks/wikantik/github", o.get( "githubUrl" ).getAsString() );
        assertEquals( "/api/work/hooks/wikantik/generic", o.get( "genericUrl" ).getAsString() );
        assertEquals( java.util.Set.of( "secret", "githubUrl", "genericUrl" ), o.keySet() );
        assertEquals( "no-store", r.headers().get( "Cache-Control" ), "the response carrying the secret is never cached" );
        assertFalse( o.get( "githubUrl" ).getAsString().contains( SECRET ) );
    }

    @Test
    void theSecretIsInNoOtherResponse() throws Exception {
        asRoot();
        issued();
        Mockito.when( svc.productHistory( ROOT, "wikantik" ) ).thenReturn( List.of( new com.wikantik.work.ProductEvent( 1,
            java.time.Instant.parse( "2026-11-20T10:00:00Z" ), "root", com.wikantik.work.Actor.Kind.HUMAN, null, "hook",
            null, "enabled" ) ) );
        call( "POST", "/wikantik/hook", null );
        final WorkHttp.Result list = call( "GET", null, null );
        final WorkHttp.Result history = call( "GET", "/wikantik/history", null );
        assertEquals( 200, list.status() );
        assertEquals( 200, history.status() );
        assertFalse( list.body().contains( SECRET ), list.body() );
        assertFalse( history.body().contains( SECRET ), history.body() );
    }

    @Test
    void anAdminsProductRowsCarryTheHookState() throws Exception {
        asRoot();
        issued();
        final JsonObject hook = call( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject()
            .getAsJsonObject( "hook" );
        assertEquals( java.util.Set.of( "enabled", "enabledAt", "lastReceivedAt" ), hook.keySet() );
        assertEquals( true, hook.get( "enabled" ).getAsBoolean() );
        assertEquals( "2026-11-20T10:00:00Z", hook.get( "enabledAt" ).getAsString() );
        assertEquals( "2026-11-20T10:01:00Z", hook.get( "lastReceivedAt" ).getAsString() );
    }

    @Test
    void aProductWithNoHookIsOffWithNullTimes() throws Exception {
        asRoot();
        final JsonObject hook = call( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject()
            .getAsJsonObject( "hook" );
        assertEquals( false, hook.get( "enabled" ).getAsBoolean() );
        assertEquals( true, hook.get( "enabledAt" ).isJsonNull() );
        assertEquals( true, hook.get( "lastReceivedAt" ).isJsonNull() );
    }

    @Test
    void aMemberSeesNoHookAndTheServiceIsNotAskedForOne() throws Exception {
        final JsonObject p = call( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertFalse( p.has( "hook" ) );
        Mockito.verify( codeHost, Mockito.never() ).hooks( any() );
    }

    @Test
    void turningTheHookOffIs204() throws Exception {
        asRoot();
        assertEquals( 204, call( "DELETE", "/wikantik/hook", null ).status() );
        Mockito.verify( codeHost ).disableHook( ROOT, "wikantik" );
    }

    @Test
    void theHookRoutesMapRefusalsLikeProductEdits() throws Exception {
        Mockito.when( codeHost.enableHook( any(), anyString() ) ).thenThrow( new WorkDeniedException( "Only wiki admins." ) );
        assertEquals( 403, call( "POST", "/wikantik/hook", null ).status() );
        Mockito.doThrow( new WorkDeniedException( "Only wiki admins." ) ).when( codeHost ).disableHook( any(), anyString() );
        assertEquals( 403, call( "DELETE", "/wikantik/hook", null ).status() );
        Mockito.when( codeHost.enableHook( any(), eq( "ghost" ) ) ).thenThrow( new WorkNotFoundException( "No such product: ghost" ) );
        final WorkHttp.Result missing = call( "POST", "/ghost/hook", null );
        assertEquals( 404, missing.status() );
        assertFalse( missing.body().contains( "ghost" ), missing.body() );
        Mockito.when( codeHost.enableHook( any(), eq( "old" ) ) ).thenThrow(
            new WorkConflictException( "The product is archived.", WorkConflictException.ARCHIVED ) );
        assertEquals( 409, call( "POST", "/old/hook", null ).status() );
    }
}
