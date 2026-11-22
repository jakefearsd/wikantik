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
import com.wikantik.work.WebhookCreated;
import com.wikantik.work.WebhookDraft;
import com.wikantik.work.WebhookPatch;
import com.wikantik.work.WebhookView;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import com.wikantik.work.WorkWebhooksService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

/** The webhook routes, reached through {@link WorkProjectResource}. */
class WorkWebhookRoutesTest {

    private static final String SECRET = "s3cr3t-signing-value-0123456789";

    private WorkWebhooksService webhooks;
    private WorkProjectResource servlet;

    @BeforeEach
    void setUp() {
        final WorkService svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        webhooks = Mockito.mock( WorkWebhooksService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.webhooks() ).thenReturn( webhooks );
        servlet = WorkHttp.wire( new WorkProjectResource(), svc, ALICE );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/projects", path, Map.of(), body );
    }

    private static WebhookView view( final long id ) {
        return new WebhookView( id, "generic", "https://hooks.example.com", List.of( "status" ), "Lena Lead",
            Instant.parse( "2026-11-21T08:00:00Z" ), null, null, null, null, false );
    }

    @Test
    void listReturnsViewsWithoutUrlOrSecret() throws Exception {
        Mockito.when( webhooks.list( ALICE, "PLAT" ) ).thenReturn( List.of( view( 3 ) ) );
        final WorkHttp.Result r = call( "GET", "/PLAT/webhooks", null );
        assertEquals( 200, r.status() );
        final JsonObject w = r.json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( 3, w.get( "id" ).getAsInt() );
        assertEquals( "https://hooks.example.com", w.get( "target" ).getAsString() );
        assertEquals( "Lena Lead", w.get( "ownerName" ).getAsString() );
        assertFalse( w.has( "url" ) );
        assertFalse( w.has( "secret" ) );
        assertFalse( r.body().contains( SECRET ) );
    }

    @Test
    void addReturnsTheSecretOnce201() throws Exception {
        Mockito.when( webhooks.add( eq( ALICE ), eq( "PLAT" ), any() ) ).thenReturn( new WebhookCreated( view( 4 ), SECRET ) );
        final WorkHttp.Result r = call( "POST", "/PLAT/webhooks",
            "{\"url\":\"https://hooks.example.com/x\",\"format\":\"generic\",\"eventTypes\":[\"status\"]}" );
        assertEquals( 201, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( SECRET, o.get( "secret" ).getAsString() );
        assertEquals( 4, o.getAsJsonObject( "webhook" ).get( "id" ).getAsInt() );
        final ArgumentCaptor< WebhookDraft > d = ArgumentCaptor.forClass( WebhookDraft.class );
        Mockito.verify( webhooks ).add( eq( ALICE ), eq( "PLAT" ), d.capture() );
        assertEquals( new WebhookDraft( "https://hooks.example.com/x", "generic", List.of( "status" ) ), d.getValue() );
    }

    @Test
    void aSlackWebhookHasANullSecret() throws Exception {
        Mockito.when( webhooks.add( any(), any(), any() ) ).thenReturn( new WebhookCreated( view( 4 ), null ) );
        final JsonObject o = call( "POST", "/PLAT/webhooks", "{\"url\":\"https://hooks.slack.com/a\",\"format\":\"slack\"}" )
            .json().getAsJsonObject();
        assertTrue( o.get( "secret" ).isJsonNull() );
    }

    @Test
    void patchPassesOnlyWhatWasSent() throws Exception {
        Mockito.when( webhooks.update( eq( ALICE ), eq( "PLAT" ), eq( 3L ), any() ) ).thenReturn( view( 3 ) );
        final WorkHttp.Result r = call( "PATCH", "/PLAT/webhooks/3", "{\"enabled\":false}" );
        assertEquals( 200, r.status() );
        assertFalse( r.body().contains( SECRET ) );
        final ArgumentCaptor< WebhookPatch > p = ArgumentCaptor.forClass( WebhookPatch.class );
        Mockito.verify( webhooks ).update( eq( ALICE ), eq( "PLAT" ), eq( 3L ), p.capture() );
        assertEquals( new WebhookPatch( null, null, false ), p.getValue() );
    }

    @Test
    void aNonBooleanEnabledIs400NamingIt() throws Exception {
        final WorkHttp.Result r = call( "PATCH", "/PLAT/webhooks/3", "{\"enabled\":\"yes\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "enabled", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void anUnknownFieldIs400NamingIt() throws Exception {
        assertEquals( "secret", call( "PATCH", "/PLAT/webhooks/3", "{\"secret\":\"mine\"}" )
            .json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "owner", call( "POST", "/PLAT/webhooks", "{\"url\":\"https://a.example.com\",\"owner\":\"bob\"}" )
            .json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void deleteIs204() throws Exception {
        assertEquals( 204, call( "DELETE", "/PLAT/webhooks/3", null ).status() );
        Mockito.verify( webhooks ).remove( ALICE, "PLAT", 3L );
    }

    @Test
    void aStrangerGets404OnAllFour() throws Exception {
        final WorkNotFoundException gone = new WorkNotFoundException( "Not found." );
        Mockito.when( webhooks.list( any(), any() ) ).thenThrow( gone );
        Mockito.when( webhooks.add( any(), any(), any() ) ).thenThrow( gone );
        Mockito.when( webhooks.update( any(), any(), anyLong(), any() ) ).thenThrow( gone );
        Mockito.doThrow( gone ).when( webhooks ).remove( any(), any(), anyLong() );
        final String[][] routes = { { "GET", "/HIDDEN/webhooks", null },
            { "POST", "/HIDDEN/webhooks", "{\"url\":\"https://a.example.com\"}" },
            { "PATCH", "/HIDDEN/webhooks/3", "{\"enabled\":true}" }, { "DELETE", "/HIDDEN/webhooks/3", null } };
        for ( final String[] route : routes ) {
            final WorkHttp.Result r = call( route[ 0 ], route[ 1 ], route[ 2 ] );
            assertEquals( 404, r.status(), route[ 0 ] + route[ 1 ] );
            assertEquals( "Not found.", r.json().getAsJsonObject().get( "message" ).getAsString() );
        }
        final WorkHttp.Result unknownProject = call( "GET", "/NOPE/webhooks", null );
        assertEquals( 404, unknownProject.status() );
    }

    @Test
    void aMemberGets403OnAdd() throws Exception {
        Mockito.when( webhooks.add( any(), any(), any() ) ).thenThrow( new WorkDeniedException( "Only a lead can do this." ) );
        assertEquals( 403, call( "POST", "/PLAT/webhooks", "{\"url\":\"https://a.example.com\"}" ).status() );
    }

    @Test
    void aPrivateUrlIs400NamingUrlWithoutEchoingTheAddress() throws Exception {
        Mockito.when( webhooks.add( any(), any(), any() ) )
            .thenThrow( new WorkValidationException( "url", "That address is not allowed." ) );
        final WorkHttp.Result r = call( "POST", "/PLAT/webhooks", "{\"url\":\"https://10.0.0.5/hook\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "url", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertFalse( r.body().contains( "10.0.0.5" ) );
    }

    @Test
    void aBadFormatOrEventTypeIs400NamingIt() throws Exception {
        Mockito.when( webhooks.add( any(), any(), any() ) )
            .thenThrow( new WorkValidationException( "eventTypes", "x is not an event a webhook can send." ) );
        assertEquals( "eventTypes", call( "POST", "/PLAT/webhooks", "{\"url\":\"https://a.example.com\",\"eventTypes\":[\"x\"]}" )
            .json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( "eventTypes", call( "POST", "/PLAT/webhooks", "{\"url\":\"https://a.example.com\",\"eventTypes\":\"x\"}" )
            .json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void webhooksOffIs409() throws Exception {
        Mockito.when( webhooks.add( any(), any(), any() ) ).thenThrow(
            new WorkConflictException( "Webhooks are turned off on this wiki.", WorkConflictException.WEBHOOKS_OFF ) );
        final WorkHttp.Result r = call( "POST", "/PLAT/webhooks", "{\"url\":\"https://a.example.com\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "webhooks_off", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void archivedIs409() throws Exception {
        Mockito.doThrow( new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED ) )
            .when( webhooks ).remove( any(), any(), anyLong() );
        assertEquals( "archived", call( "DELETE", "/PLAT/webhooks/3", null ).json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void aNonNumericIdIs404() throws Exception {
        assertEquals( 404, call( "PATCH", "/PLAT/webhooks/abc", "{\"enabled\":true}" ).status() );
        assertEquals( 404, call( "DELETE", "/PLAT/webhooks/abc", null ).status() );
        Mockito.verifyNoInteractions( webhooks );
    }
}
