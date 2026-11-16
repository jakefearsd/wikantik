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
import com.wikantik.work.Agent;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkRegistryService;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class WorkAgentResourceTest {

    private static final Actor ADMIN = Actor.human( "root", Set.of(), true );
    private static final Actor MEMBER = Actor.human( "dana", Set.of(), false );
    private static final Instant T = Instant.parse( "2026-11-15T10:00:00Z" );
    private static final Agent BUILDER = new Agent( 5, "builder", "acme", "claude-code", 42, List.of( "triage" ), T, null );
    private static final String TOKEN = "wkk_secret-token-value";

    private WorkService svc;
    private WorkRegistryService registry;
    private WorkAgentResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        registry = Mockito.mock( WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, ADMIN );
    }

    private WorkHttp.Result send( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/agents", path, Map.of(), body );
    }

    @Test
    void registerReturnsTokenOnceWithNoStore() throws Exception {
        Mockito.when( registry.registerAgent( eq( ADMIN ), any() ) ).thenReturn( new WorkService.AgentWithToken( BUILDER, TOKEN ) );
        final WorkHttp.Result r = send( "POST", null,
            "{\"name\":\"builder\",\"vendor\":\"acme\",\"runtime\":\"claude-code\",\"queues\":[\"triage\"],\"owner\":\"Dana D\"}" );
        assertEquals( 201, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( TOKEN, o.get( "token" ).getAsString() );
        assertEquals( "builder", o.getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertEquals( 42, o.getAsJsonObject( "agent" ).get( "apiKeyId" ).getAsInt(), "the registering admin sees the key id" );
        assertEquals( "no-store", r.headers().get( "Cache-Control" ) );
        final ArgumentCaptor< AgentDraft > d = ArgumentCaptor.forClass( AgentDraft.class );
        Mockito.verify( registry ).registerAgent( eq( ADMIN ), d.capture() );
        assertEquals( new AgentDraft( "builder", "acme", "claude-code", List.of( "triage" ), "Dana D" ), d.getValue() );
    }

    @Test
    void aFailedRegistrationShowsNoTokenAndIsNotMarkedNoStoreAsASecret() throws Exception {
        Mockito.when( registry.registerAgent( any(), any() ) )
            .thenThrow( new WorkConflictException( "Agent builder already exists.", WorkConflictException.EXISTS ) );
        final WorkHttp.Result r = send( "POST", null, "{\"name\":\"builder\"}" );
        assertEquals( 409, r.status() );
        assertFalse( r.body().contains( "token" ) );
    }

    @Test
    void queuesMustBeAnArrayOfText() throws Exception {
        assertEquals( 400, send( "POST", null, "{\"name\":\"b\",\"queues\":\"triage\"}" ).status() );
        assertEquals( 400, send( "POST", null, "{\"name\":\"b\",\"queues\":[1]}" ).status() );
        assertEquals( 400, send( "POST", null, "{\"name\":\"b\",\"queues\":[{}]}" ).status() );
        Mockito.verifyNoInteractions( registry );
    }

    @Test
    void listHidesKeyIdFromNonAdmin() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        Mockito.when( registry.listAgents( MEMBER ) ).thenReturn( List.of(
            new Agent( 5, "builder", "acme", "claude-code", null, List.of( "triage" ), T, null ) ) );
        final JsonObject a = send( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertFalse( a.has( "apiKeyId" ), "not even as null" );
        assertEquals( "builder", a.get( "name" ).getAsString() );
    }

    @Test
    void evenIfTheServiceLeakedAKeyIdANonAdminStillDoesNotGetIt() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        Mockito.when( registry.listAgents( MEMBER ) ).thenReturn( List.of( BUILDER ) );
        assertFalse( send( "GET", null, null ).body().contains( "apiKeyId" ) );
    }

    @Test
    void anAdminListSeesKeyIds() throws Exception {
        Mockito.when( registry.listAgents( ADMIN ) ).thenReturn( List.of( BUILDER ) );
        assertEquals( 42, send( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject().get( "apiKeyId" ).getAsInt() );
    }

    @Test
    void nonMemberListIsEmpty() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        Mockito.when( registry.listAgents( MEMBER ) ).thenReturn( List.of() );
        assertEquals( "[]", send( "GET", null, null ).body() );
    }

    @Test
    void nonAdminRegisterIs403() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        Mockito.when( registry.registerAgent( any(), any() ) ).thenThrow( new WorkDeniedException( "Only a wiki admin may do that." ) );
        final WorkHttp.Result r = send( "POST", null, "{\"name\":\"builder\"}" );
        assertEquals( 403, r.status() );
        assertFalse( r.body().contains( "token" ) );
    }

    @Test
    void rotateReturnsNewToken() throws Exception {
        final Agent rebound = new Agent( 5, "builder", "acme", "claude-code", 43, List.of( "triage" ), T, null );
        Mockito.when( registry.rotateAgentKey( ADMIN, 5, null ) ).thenReturn( new WorkService.AgentWithToken( rebound, "wkk_new" ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", null );
        assertEquals( 200, r.status() );
        assertEquals( "wkk_new", r.json().getAsJsonObject().get( "token" ).getAsString() );
        assertEquals( 43, r.json().getAsJsonObject().getAsJsonObject( "agent" ).get( "apiKeyId" ).getAsInt() );
        assertEquals( "no-store", r.headers().get( "Cache-Control" ) );
    }

    @Test
    void rotateMayNameANewOwner() throws Exception {
        final Agent rebound = new Agent( 5, "builder", "acme", "claude-code", 44, List.of( "triage" ), T, null );
        Mockito.when( registry.rotateAgentKey( ADMIN, 5, "Lena Lead" ) )
            .thenReturn( new WorkService.AgentWithToken( rebound, "wkk_newer" ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", "{\"owner\":\"Lena Lead\"}" );
        assertEquals( 200, r.status() );
        assertEquals( "wkk_newer", r.json().getAsJsonObject().get( "token" ).getAsString() );
    }

    @Test
    void patchUpdatesAndReturnsTheAgent() throws Exception {
        Mockito.when( registry.listAgents( ADMIN ) ).thenReturn( List.of( BUILDER ) );
        final WorkHttp.Result r = send( "PATCH", "/5", "{\"name\":\"builder\",\"vendor\":\"acme\",\"runtime\":\"x\",\"queues\":[\"triage\"]}" );
        assertEquals( 200, r.status() );
        Mockito.verify( registry ).updateAgent( ADMIN, 5, new AgentDraft( "builder", "acme", "x", List.of( "triage" ), null ) );
        assertEquals( "builder", r.json().getAsJsonObject().get( "name" ).getAsString() );
        assertFalse( r.body().contains( "token" ) );
    }

    @Test
    void retireIs204() throws Exception {
        assertEquals( 204, send( "POST", "/5/retire", null ).status() );
        Mockito.verify( registry ).retireAgent( ADMIN, 5 );
    }

    @Test
    void unknownAgentIs404() throws Exception {
        Mockito.doThrow( new WorkNotFoundException( "No such agent: 99" ) ).when( registry ).retireAgent( any(), anyLong() );
        Mockito.doThrow( new WorkNotFoundException( "No such agent: 99" ) ).when( registry ).updateAgent( any(), anyLong(), any() );
        Mockito.when( registry.rotateAgentKey( any(), anyLong(), any() ) ).thenThrow( new WorkNotFoundException( "No such agent: 99" ) );
        for ( final String[] call : new String[][] { { "POST", "/99/retire", null }, { "PATCH", "/99", "{\"name\":\"x\"}" },
            { "POST", "/99/rotate-key", null }, { "POST", "/abc/retire", null } } ) {
            final WorkHttp.Result r = send( call[ 0 ], call[ 1 ], call[ 2 ] );
            assertEquals( 404, r.status(), call[ 1 ] );
            assertFalse( r.body().contains( "99" ), r.body() );
            assertFalse( r.body().contains( "token" ) );
        }
    }

    @Test
    void aNonAdminGets403WhetherOrNotTheAgentExistsAndWhateverTheIdLooksLike() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        // like the real service: authorise before looking anything up
        Mockito.doThrow( new WorkDeniedException( "Only a wiki admin may do that." ) ).when( registry ).retireAgent( any(), anyLong() );
        Mockito.doThrow( new WorkDeniedException( "Only a wiki admin may do that." ) ).when( registry ).updateAgent( any(), anyLong(), any() );
        Mockito.when( registry.rotateAgentKey( any(), anyLong(), any() ) ).thenThrow( new WorkDeniedException( "Only a wiki admin may do that." ) );
        for ( final String[] call : new String[][] { { "POST", "%s/retire", null }, { "PATCH", "%s", "{\"name\":\"x\"}" },
            { "POST", "%s/rotate-key", null } } ) {
            final WorkHttp.Result known = send( call[ 0 ], call[ 1 ].formatted( "/5" ), call[ 2 ] );
            final WorkHttp.Result unknown = send( call[ 0 ], call[ 1 ].formatted( "/99999" ), call[ 2 ] );
            final WorkHttp.Result nonsense = send( call[ 0 ], call[ 1 ].formatted( "/abc" ), call[ 2 ] );
            assertEquals( 403, known.status(), call[ 1 ] );
            assertEquals( known.body(), unknown.body(), call[ 1 ] );
            assertEquals( known.body(), nonsense.body(), call[ 1 ] );
        }
    }

    @Test
    void theTokenIsNotInAnyLogLine() throws Exception {
        final org.apache.logging.log4j.core.LoggerContext ctx = ( org.apache.logging.log4j.core.LoggerContext )
            org.apache.logging.log4j.LogManager.getContext( false );
        final java.util.List< String > lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        final org.apache.logging.log4j.core.appender.AbstractAppender sink =
            new org.apache.logging.log4j.core.appender.AbstractAppender( "tokenSink", null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY ) {
                @Override
                public void append( final org.apache.logging.log4j.core.LogEvent event ) {
                    lines.add( event.getMessage().getFormattedMessage() );
                }
            };
        sink.start();
        ctx.getConfiguration().getRootLogger().addAppender( sink, org.apache.logging.log4j.Level.ALL, null );
        ctx.updateLoggers();
        try {
            Mockito.when( registry.registerAgent( eq( ADMIN ), any() ) ).thenReturn( new WorkService.AgentWithToken( BUILDER, TOKEN ) );
            send( "POST", null, "{\"name\":\"builder\"}" );
        } finally {
            ctx.getConfiguration().getRootLogger().removeAppender( "tokenSink" );
            ctx.updateLoggers();
            sink.stop();
        }
        assertTrue( lines.stream().noneMatch( l -> l.contains( TOKEN ) ), lines.toString() );
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
        assertEquals( 404, send( "GET", "/5/nonsense", null ).status() );
        assertEquals( 404, send( "DELETE", "/5", null ).status() );
    }

    @Test
    void aNonNumericAgentIdIsLoggedWithoutTheCallersText() throws Exception {
        final List< String > warned = new java.util.concurrent.CopyOnWriteArrayList<>();
        final String logger = WorkAgentResource.class.getName();
        final var core = ( org.apache.logging.log4j.core.Logger ) org.apache.logging.log4j.LogManager.getLogger( logger );
        final var capture = new org.apache.logging.log4j.core.appender.AbstractAppender( "agent-id-capture", null, null,
            true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY ) {
            @Override
            public void append( final org.apache.logging.log4j.core.LogEvent e ) {
                warned.add( e.getMessage().getFormattedMessage() );
            }
        };
        capture.start();
        final org.apache.logging.log4j.Level before = core.getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel( logger, org.apache.logging.log4j.Level.ALL );
        core.addAppender( capture );
        Mockito.when( registry.rotateAgentKey( any(), anyLong(), any() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        try {
            send( "POST", "/evil%0Aforged-line/rotate-key", null );
        } finally {
            core.removeAppender( capture );
            org.apache.logging.log4j.core.config.Configurator.setLevel( logger, before );
            capture.stop();
        }
        assertTrue( warned.stream().anyMatch( m -> m.contains( "not a number" ) ), warned.toString() );
        assertTrue( warned.stream().noneMatch( m -> m.contains( "evil" ) || m.contains( "forged" ) ), warned.toString() );
    }

    @Test
    void aPatchPassesOnlyTheFieldsItNames() throws Exception {
        Mockito.when( registry.listAgents( ADMIN ) ).thenReturn( List.of( BUILDER ) );
        final WorkHttp.Result r = send( "PATCH", "/5", "{\"runtime\":\"codex-2\"}" );
        assertEquals( 200, r.status() );
        Mockito.verify( registry ).updateAgent( ADMIN, 5, new AgentDraft( null, null, "codex-2", null, null ) );
    }

    @Test
    void aRotationWhoseOwnerIsGoneIsA400OnOwner() throws Exception {
        final String message = "This agent's key owner no longer exists. Name a new owner.";
        Mockito.when( registry.rotateAgentKey( ADMIN, 5, null ) )
            .thenThrow( new com.wikantik.work.WorkValidationException( "owner", message ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", null );
        assertEquals( 400, r.status() );
        assertEquals( "owner", r.json().getAsJsonObject().get( "field" ).getAsString() );
        assertEquals( message, r.json().getAsJsonObject().get( "message" ).getAsString() );
    }

    @Test
    void aRotatePostWithABlankBodyIsAPlainRotation() throws Exception {
        // A client that sends no JSON but a non-zero or unknown length (chunked) still means "rotate, same owner".
        final Agent rebound = new Agent( 5, "builder", "acme", "claude-code", 45, List.of( "triage" ), T, null );
        Mockito.when( registry.rotateAgentKey( ADMIN, 5, null ) )
            .thenReturn( new WorkService.AgentWithToken( rebound, "wkk_blank" ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", "  \n" );
        assertEquals( 200, r.status(), r.body() );
        assertEquals( "wkk_blank", r.json().getAsJsonObject().get( "token" ).getAsString() );
    }
}
