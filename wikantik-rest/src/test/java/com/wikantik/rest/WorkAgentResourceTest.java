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
    private static final Agent BUILDER = new Agent( 5, "builder", "acme", "claude-code", "root", List.of( "triage" ), T, null );
    private static final String TOKEN = "wka_secret-token-value";

    private WorkService svc;
    private WorkRegistryService registry;
    private WorkAgentResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        registry = Mockito.mock( WorkRegistryService.class );
        Mockito.when( svc.registry() ).thenReturn( registry );
        try {
            Mockito.when( registry.agentViews( any() ) ).thenReturn( List.of( view( BUILDER ) ) );
            Mockito.when( registry.describe( any() ) ).thenAnswer( inv -> view( inv.getArgument( 0 ) ) );
        } catch ( final java.sql.SQLException e ) {
            throw new IllegalStateException( e );
        }
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, ADMIN );
    }

    private static WorkRegistryService.AgentView view( final Agent a ) {
        return new WorkRegistryService.AgentView( a, a.sponsor() == null ? null : "Root Admin",
            a.active() ? WorkRegistryService.AgentStatus.ACTIVE : WorkRegistryService.AgentStatus.RETIRED );
    }

    private WorkHttp.Result send( final String method, final String path, final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/agents", path, Map.of(), body );
    }

    @Test
    void registerReturnsTokenOnceWithNoStore() throws Exception {
        Mockito.when( registry.registerAgent( eq( ADMIN ), any() ) ).thenReturn( new WorkService.AgentWithToken( BUILDER, TOKEN ) );
        final WorkHttp.Result r = send( "POST", null,
            "{\"name\":\"builder\",\"vendor\":\"acme\",\"runtime\":\"claude-code\",\"queues\":[\"triage\"]}" );
        assertEquals( 201, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( TOKEN, o.get( "token" ).getAsString() );
        assertEquals( "builder", o.getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertFalse( o.getAsJsonObject( "agent" ).has( "apiKeyId" ), "an agent has no API key" );
        assertEquals( "no-store", r.headers().get( "Cache-Control" ) );
        final ArgumentCaptor< AgentDraft > d = ArgumentCaptor.forClass( AgentDraft.class );
        Mockito.verify( registry ).registerAgent( eq( ADMIN ), d.capture() );
        assertEquals( new AgentDraft( "builder", "acme", "claude-code", List.of( "triage" ) ), d.getValue() );
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
    void nonMemberListIsEmpty() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        Mockito.when( registry.agentViews( MEMBER ) ).thenReturn( List.of() );
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
        Mockito.when( registry.rotateCredential( ADMIN, 5 ) ).thenReturn( new WorkService.AgentWithToken( BUILDER, "wka_new" ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", null );
        assertEquals( 200, r.status() );
        assertEquals( "wka_new", r.json().getAsJsonObject().get( "token" ).getAsString() );
        assertEquals( "builder", r.json().getAsJsonObject().getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertEquals( "no-store", r.headers().get( "Cache-Control" ) );
    }


    @Test
    void patchUpdatesAndReturnsTheAgent() throws Exception {
        Mockito.when( registry.listAgents( ADMIN ) ).thenReturn( List.of( BUILDER ) );
        final WorkHttp.Result r = send( "PATCH", "/5", "{\"name\":\"builder\",\"vendor\":\"acme\",\"runtime\":\"x\",\"queues\":[\"triage\"]}" );
        assertEquals( 200, r.status() );
        Mockito.verify( registry ).updateAgent( ADMIN, 5, new AgentDraft( "builder", "acme", "x", List.of( "triage" ) ) );
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
        Mockito.when( registry.rotateCredential( any(), anyLong() ) ).thenThrow( new WorkNotFoundException( "No such agent: 99" ) );
        for ( final String[] call : new String[][] { { "POST", "/99/retire", null }, { "PATCH", "/99", "{\"name\":\"x\"}" },
            { "POST", "/99/rotate-key", null }, { "POST", "/abc/retire", null } } ) {
            final WorkHttp.Result r = send( call[ 0 ], call[ 1 ], call[ 2 ] );
            assertEquals( 404, r.status(), call[ 1 ] );
            assertFalse( r.body().contains( "99" ), r.body() );
            assertFalse( r.body().contains( "token" ) );
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
        Mockito.when( registry.rotateCredential( any(), anyLong() ) ).thenThrow( new WorkNotFoundException( "x" ) );
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
        Mockito.verify( registry ).updateAgent( ADMIN, 5, new AgentDraft( null, null, "codex-2", null ) );
    }


    @Test
    void aRotatePostWithABlankBodyIsAPlainRotation() throws Exception {
        // A client that sends no JSON but a non-zero or unknown length (chunked) still means "rotate, same owner".
        final Agent rebound = new Agent( 5, "builder", "acme", "claude-code", null, List.of( "triage" ), T, null );
        Mockito.when( registry.rotateCredential( ADMIN, 5 ) )
            .thenReturn( new WorkService.AgentWithToken( rebound, "wkk_blank" ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", "  \n" );
        assertEquals( 200, r.status(), r.body() );
        assertEquals( "wkk_blank", r.json().getAsJsonObject().get( "token" ).getAsString() );
    }

    @Test
    void aRotationDuringAUserDatabaseOutageIs503NotAnOwnerRefusal() throws Exception {
        Mockito.when( registry.rotateCredential( ADMIN, 5 ) )
            .thenThrow( new com.wikantik.work.WorkUnavailableException( "test: user database unreachable" ) );
        final WorkHttp.Result r = send( "POST", "/5/rotate-key", null );
        assertEquals( 503, r.status(), r.body() );
        assertTrue( r.body().contains( "Service temporarily unavailable" ), r.body() );
        assertFalse( r.body().contains( "unreachable" ), "the cause stays in the log: " + r.body() );
    }

    // ---- sponsors (phase 3a)

    @Test
    void anAgentIsShownWithItsSponsorsNameStatusAndWhetherItIsTheCallers() throws Exception {
        final JsonObject a = send( "GET", null, null ).json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "Root Admin", a.get( "sponsorName" ).getAsString() );
        assertEquals( "active", a.get( "status" ).getAsString() );
        assertTrue( a.get( "sponsoredByMe" ).getAsBoolean() );
        assertEquals( "root", a.get( "sponsor" ).getAsString(), "a wiki admin also sees the login" );
        assertFalse( a.has( "apiKeyId" ) );
    }

    @Test
    void sponsorLoginIsShownOnlyToAnAdmin() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        final WorkHttp.Result r = send( "GET", null, null );
        final JsonObject a = r.json().getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "Root Admin", a.get( "sponsorName" ).getAsString() );
        assertFalse( a.has( "sponsor" ), "a login never reaches a non-admin: " + r.body() );
        assertFalse( a.get( "sponsoredByMe" ).getAsBoolean() );
    }

    @Test
    void aSponsorlessOrRetiredAgentSaysSoPlainly() throws Exception {
        final Agent loose = new Agent( 6, "loose", "", "", null, List.of(), T, null );
        Mockito.when( registry.agentViews( ADMIN ) ).thenReturn( List.of(
            new WorkRegistryService.AgentView( loose, null, WorkRegistryService.AgentStatus.NO_SPONSOR ),
            new WorkRegistryService.AgentView( BUILDER, "Root Admin", WorkRegistryService.AgentStatus.SPONSOR_GONE ) ) );
        final var list = send( "GET", null, null ).json().getAsJsonArray();
        assertEquals( "no_sponsor", list.get( 0 ).getAsJsonObject().get( "status" ).getAsString() );
        assertFalse( list.get( 0 ).getAsJsonObject().has( "sponsorName" ) );
        assertEquals( "sponsor_gone", list.get( 1 ).getAsJsonObject().get( "status" ).getAsString() );
    }

    @Test
    void puttingTheSponsorTakesTheAgentOverAndReturnsIt() throws Exception {
        Mockito.when( registry.takeOver( ADMIN, 5 ) ).thenReturn( BUILDER );
        final WorkHttp.Result r = send( "PUT", "/5/sponsor", null );
        assertEquals( 200, r.status() );
        assertEquals( "builder", r.json().getAsJsonObject().get( "name" ).getAsString() );
        Mockito.verify( registry ).takeOver( ADMIN, 5 );
    }

    @Test
    void aRefusedTakeoverIsA403WithTheReason() throws Exception {
        Mockito.when( registry.takeOver( any(), anyLong() ) ).thenThrow( new WorkDeniedException(
            "This agent has a sponsor. Ask them to release it, or ask a wiki administrator." ) );
        final WorkHttp.Result r = send( "PUT", "/5/sponsor", null );
        assertEquals( 403, r.status() );
        assertTrue( r.body().contains( "This agent has a sponsor. Ask them to release it, or ask a wiki administrator." ) );
    }

    @Test
    void deletingTheSponsorReleasesIt() throws Exception {
        assertEquals( 204, send( "DELETE", "/5/sponsor", null ).status() );
        Mockito.verify( registry ).releaseSponsorship( ADMIN, 5 );
    }

    @Test
    void anAgentTheCallerCannotSeeIs404OnEveryRoute() throws Exception {
        servlet = WorkHttp.wire( new WorkAgentResource(), svc, MEMBER );
        final WorkNotFoundException hidden = new WorkNotFoundException( "No such agent: 5" );
        Mockito.when( registry.rotateCredential( any(), anyLong() ) ).thenThrow( hidden );
        Mockito.when( registry.takeOver( any(), anyLong() ) ).thenThrow( hidden );
        Mockito.doThrow( hidden ).when( registry ).releaseSponsorship( any(), anyLong() );
        Mockito.doThrow( hidden ).when( registry ).retireAgent( any(), anyLong() );
        Mockito.doThrow( hidden ).when( registry ).updateAgent( any(), anyLong(), any() );
        final String missing = send( "POST", "/999/rotate-key", null ).body();
        for ( final WorkHttp.Result r : List.of( send( "POST", "/5/rotate-key", null ), send( "PUT", "/5/sponsor", null ),
                send( "DELETE", "/5/sponsor", null ), send( "POST", "/5/retire", null ),
                send( "PATCH", "/5", "{\"vendor\":\"x\"}" ) ) ) {
            assertEquals( 404, r.status() );
            assertEquals( missing, r.body(), "hidden and missing are one answer" );
        }
    }

    /** The one-time token must reach the caller even if the agent list cannot be read just after the commit. */
    @Test
    void aCommittedRotationOrRegistrationReturnsItsTokenEvenWhenTheListCannotBeRead() throws Exception {
        Mockito.when( registry.agentViews( any() ) ).thenThrow(
            new com.wikantik.work.WorkUnavailableException( "The user directory could not be read." ) );
        Mockito.when( registry.rotateCredential( ADMIN, 5 ) ).thenReturn( new WorkService.AgentWithToken( BUILDER, "wka_new" ) );
        Mockito.when( registry.registerAgent( eq( ADMIN ), any() ) ).thenReturn( new WorkService.AgentWithToken( BUILDER, TOKEN ) );
        Mockito.when( registry.takeOver( ADMIN, 5 ) ).thenReturn( BUILDER );
        Mockito.when( registry.listAgents( ADMIN ) ).thenReturn( List.of( BUILDER ) );
        final WorkHttp.Result rotated = send( "POST", "/5/rotate-key", null );
        assertEquals( 200, rotated.status(), rotated.body() );
        assertEquals( "wka_new", rotated.json().getAsJsonObject().get( "token" ).getAsString() );
        final WorkHttp.Result made = send( "POST", null, "{\"name\":\"builder\"}" );
        assertEquals( 201, made.status(), made.body() );
        assertEquals( TOKEN, made.json().getAsJsonObject().get( "token" ).getAsString() );
        assertEquals( 200, send( "PUT", "/5/sponsor", null ).status() );
        assertEquals( 200, send( "PATCH", "/5", "{\"vendor\":\"x\"}" ).status() );
    }
}
