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
package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The registry at the service: authorisation first, validation, key lifecycle ordering, identity. */
class WorkRegistryServiceTest {

    private static final Instant NOW = Instant.parse( "2026-11-16T12:00:00Z" );

    /** Records what the service asked of the key service. */

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private WorkStore store;
    private WorkClaimStore claims;
    private WorkService service;
    /** What reached the admin audit trail: {@code actor|action|type|target|detail}. */
    private final List< String > audited = new ArrayList<>();

    private static Agent agent( final long id, final String name, final Integer key, final Instant retired ) {
        return new Agent( id, name, "", "", "lena", List.of( "implementer" ), NOW, retired );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        claims = mock( WorkClaimStore.class );
        when( store.claims() ).thenReturn( claims );
        when( store.queue( "implementer" ) ).thenReturn( Optional.of( new Queue( "implementer", "I", "", null ) ) );
        when( store.queue( "old" ) ).thenReturn( Optional.of( new Queue( "old", "O", "", NOW ) ) );
        when( store.queue( "nope" ) ).thenReturn( Optional.empty() );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return "lena".equals( login )
                || "admin".equals( login ); }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.fixed( NOW, ZoneOffset.UTC ), WorkSettings.DEFAULTS, null,
            ( actor, action, type, target, detail ) -> audited.add( actor + "|" + action + "|" + type + "|" + target
                + "|" + detail ) );
    }

    private AgentDraft draft( final String name, final String owner, final String... queues ) {
        return new AgentDraft( name, "openai", "codex", List.of( queues ) );
    }
















    @Test
    void createQueueConflictsOnExistingKeyAndOnUniqueViolation() throws SQLException {
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> service.registry().createQueue( admin, "implementer", "Again", "" ) ).reason() );
        when( store.createQueue( "fresh", "Fresh", "" ) ).thenThrow( new SQLException( "dup", "23505" ) );
        assertThrows( WorkConflictException.class, () -> service.registry().createQueue( admin, "fresh", "Fresh", null ) );
        assertThrows( WorkValidationException.class, () -> service.registry().createQueue( admin, "Bad Key", "X", "" ) );
    }

    @Test
    void queueWritesGoThrough() throws SQLException {
        service.registry().updateQueue( admin, "implementer", " Impl ", null );
        verify( store ).updateQueue( "implementer", "Impl", "" );
        service.registry().retireQueue( admin, "implementer" );
        verify( store ).retireQueue( "implementer", NOW );
    }













    @Test
    void agentMembersResolveByNameAndRefuseLead() throws SQLException {
        final Project plat = new Project( 1, "PLAT", "P", "", Autonomy.DELEGATED, NOW, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.agentByName( "codex" ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        when( store.agentByName( "gone" ) ).thenReturn( Optional.of( agent( 8, "gone", 56, NOW ) ) );
        service.putMember( admin, "PLAT", "Codex", "agent", WorkRole.MEMBER );
        verify( store ).putMember( 1L, "7", "agent", WorkRole.MEMBER, admin, java.util.List.of() );
        assertEquals( "principal", assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "gone", "agent", WorkRole.MEMBER ) ).field() );
        assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "ghost", "agent", WorkRole.MEMBER ) );
        assertEquals( "role", assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "codex", "agent", WorkRole.LEAD ) ).field() );
        service.removeMember( admin, "PLAT", "gone", "agent" );
        verify( store ).removeMember( 1L, "8", "agent", admin, java.util.List.of() );
    }


    // ---- P28: accountability



    @Test
    void aFailingAuditNeverUndoesTheChange() throws SQLException {
        final WorkService svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.fixed( NOW, ZoneOffset.UTC ), WorkSettings.DEFAULTS, null,
            ( actor, action, type, target, detail ) -> { throw new IllegalStateException( "audit down" ); } );
        when( store.queue( "ops" ) ).thenReturn( Optional.of( new Queue( "ops", "Ops", "", null ) ) );
        svc.registry().retireQueue( admin, "ops" );
        verify( store ).retireQueue( "ops", NOW );
    }

    // ---- M5: rotation may name a new owner



}
