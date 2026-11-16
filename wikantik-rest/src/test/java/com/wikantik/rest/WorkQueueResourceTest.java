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
import com.wikantik.work.Queue;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkRegistryService;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class WorkQueueResourceTest {

    private static final Actor ADMIN = Actor.human( "root", Set.of(), true );
    private static final Queue TRIAGE = new Queue( "triage", "Triage queue", "Incoming work", null );

    private WorkService svc;
    private WorkRegistryService registry;
    private WorkQueueResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        registry = Mockito.mock( WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        servlet = WorkHttp.wire( new WorkQueueResource(), svc, ADMIN );
    }

    private WorkHttp.Result send( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/queues", path, Map.of(), body );
    }

    @Test
    void listsQueues() throws Exception {
        Mockito.when( registry.listQueues( ADMIN ) ).thenReturn( List.of( TRIAGE ) );
        final WorkHttp.Result r = send( "GET", null, null );
        assertEquals( 200, r.status() );
        final JsonObject q = r.json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "triage", q.get( "key" ).getAsString() );
        assertEquals( "Triage queue", q.get( "name" ).getAsString() );
    }

    @Test
    void createsAQueueWith201() throws Exception {
        Mockito.when( registry.createQueue( ADMIN, "triage", "Triage queue", "Incoming work" ) ).thenReturn( TRIAGE );
        final WorkHttp.Result r = send( "POST", null, "{\"key\":\"triage\",\"name\":\"Triage queue\",\"description\":\"Incoming work\"}" );
        assertEquals( 201, r.status() );
        assertEquals( "triage", r.json().getAsJsonObject().get( "key" ).getAsString() );
    }

    @Test
    void createNeedsAKey() throws Exception {
        assertEquals( 400, send( "POST", null, "{\"name\":\"x\"}" ).status() );
        Mockito.verifyNoInteractions( registry );
    }

    @Test
    void aDuplicateKeyIs409Exists() throws Exception {
        Mockito.when( registry.createQueue( any(), anyString(), anyString(), any() ) )
            .thenThrow( new WorkConflictException( "Queue triage already exists.", WorkConflictException.EXISTS ) );
        final WorkHttp.Result r = send( "POST", null, "{\"key\":\"triage\",\"name\":\"T\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "exists", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void patchesAQueueAndReturnsIt() throws Exception {
        Mockito.when( registry.listQueues( ADMIN ) ).thenReturn( List.of( new Queue( "triage", "Renamed", "d", null ) ) );
        final WorkHttp.Result r = send( "PATCH", "/triage", "{\"name\":\"Renamed\",\"description\":\"d\"}" );
        assertEquals( 200, r.status() );
        Mockito.verify( registry ).updateQueue( ADMIN, "triage", "Renamed", "d" );
        assertEquals( "Renamed", r.json().getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void retiresAQueueWith204() throws Exception {
        final WorkHttp.Result r = send( "POST", "/triage/retire", null );
        assertEquals( 204, r.status() );
        Mockito.verify( registry ).retireQueue( ADMIN, "triage" );
    }

    @Test
    void nonAdminCreateIs403() throws Exception {
        final Actor member = Actor.human( "dana", Set.of(), false );
        servlet = WorkHttp.wire( new WorkQueueResource(), svc, member );
        Mockito.when( registry.createQueue( any(), anyString(), anyString(), any() ) )
            .thenThrow( new WorkDeniedException( "Only a wiki admin may do that." ) );
        final WorkHttp.Result r = send( "POST", null, "{\"key\":\"triage\",\"name\":\"T\"}" );
        assertEquals( 403, r.status() );
    }

    @Test
    void nonMemberListIsEmpty() throws Exception {
        final Actor stranger = Actor.human( "zed", Set.of(), false );
        servlet = WorkHttp.wire( new WorkQueueResource(), svc, stranger );
        Mockito.when( registry.listQueues( stranger ) ).thenReturn( List.of() );
        final WorkHttp.Result r = send( "GET", null, null );
        assertEquals( 200, r.status() );
        assertEquals( "[]", r.body() );
    }

    @Test
    void anAdminGets404ForAnUnknownQueueOnEveryWrite() throws Exception {
        Mockito.doThrow( new WorkNotFoundException( "No such queue: nope" ) ).when( registry )
            .updateQueue( any(), anyString(), any(), any() );
        Mockito.doThrow( new WorkNotFoundException( "No such queue: nope" ) ).when( registry ).retireQueue( any(), anyString() );
        for ( final String[] call : new String[][] { { "PATCH", "/nope", "{\"name\":\"x\"}" }, { "POST", "/nope/retire", null } } ) {
            final WorkHttp.Result r = send( call[ 0 ], call[ 1 ], call[ 2 ] );
            assertEquals( 404, r.status(), call[ 1 ] );
            assertFalse( r.body().contains( "nope" ), r.body() );
        }
    }

    @Test
    void aNonAdminSeesTheSame403ForAKnownAndAnUnknownQueue() throws Exception {
        final Actor member = Actor.human( "dana", Set.of(), false );
        servlet = WorkHttp.wire( new WorkQueueResource(), svc, member );
        // like the real service: authorise first, look up second
        Mockito.doThrow( new WorkDeniedException( "Only a wiki admin may do that." ) ).when( registry )
            .updateQueue( any(), anyString(), any(), any() );
        Mockito.doThrow( new WorkDeniedException( "Only a wiki admin may do that." ) ).when( registry ).retireQueue( any(), anyString() );
        final WorkHttp.Result known = send( "PATCH", "/triage", "{\"name\":\"x\"}" );
        final WorkHttp.Result unknown = send( "PATCH", "/no-such-queue", "{\"name\":\"x\"}" );
        assertEquals( 403, known.status() );
        assertEquals( known.status(), unknown.status() );
        assertEquals( known.body(), unknown.body() );
        assertEquals( send( "POST", "/triage/retire", null ).body(), send( "POST", "/no-such-queue/retire", null ).body() );
    }

    @Test
    void validationIs400WithTheField() throws Exception {
        Mockito.when( registry.createQueue( any(), anyString(), anyString(), any() ) )
            .thenThrow( new WorkValidationException( "key", "A queue key is lower-case letters, digits and dashes." ) );
        final WorkHttp.Result r = send( "POST", null, "{\"key\":\"Bad Key\",\"name\":\"x\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "key", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void disabledFeatureIs404AndAnonymousIs401() throws Exception {
        Mockito.doReturn( null ).when( servlet ).actor( any() );
        assertEquals( 401, send( "GET", null, null ).status() );
        Mockito.doReturn( null ).when( servlet ).workService();
        assertEquals( 404, send( "GET", null, null ).status() );
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals( 404, send( "GET", "/triage/nonsense", null ).status() );
        assertEquals( 404, send( "DELETE", "/triage", null ).status() );
    }
}
