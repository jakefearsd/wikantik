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
    private static final class FakeKeys implements AgentKeys {
        final List< String > minted = new ArrayList<>();
        final List< String > reminted = new ArrayList<>();
        final List< String > revoked = new ArrayList<>();
        final List< String > creators = new ArrayList<>();
        int next = 100;
        RuntimeException revokeFails;

        @Override
        public Minted mint( final String ownerLogin, final String label, final String createdBy ) {
            minted.add( ownerLogin + "|" + label );
            creators.add( createdBy );
            return new Minted( next++, "token-" + ( next - 1 ) );
        }

        @Override
        public Minted remint( final int oldKeyId, final String label, final String createdBy ) {
            reminted.add( oldKeyId + "|" + label );
            creators.add( createdBy );
            return new Minted( next++, "token-" + ( next - 1 ) );
        }

        @Override
        public void revoke( final int keyId, final String revokedBy ) {
            revoked.add( keyId + "|" + revokedBy );
            if ( revokeFails != null ) {
                throw revokeFails;
            }
        }
    }

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private WorkStore store;
    private WorkClaimStore claims;
    private FakeKeys keys;
    private WorkService service;
    /** What reached the admin audit trail: {@code actor|action|type|target|detail}. */
    private final List< String > audited = new ArrayList<>();

    private static Agent agent( final long id, final String name, final Integer key, final Instant retired ) {
        return new Agent( id, name, "", "", key, List.of( "implementer" ), NOW, retired );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        claims = mock( WorkClaimStore.class );
        when( store.claims() ).thenReturn( claims );
        keys = new FakeKeys();
        when( store.queue( "implementer" ) ).thenReturn( Optional.of( new Queue( "implementer", "I", "", null ) ) );
        when( store.queue( "old" ) ).thenReturn( Optional.of( new Queue( "old", "O", "", NOW ) ) );
        when( store.queue( "nope" ) ).thenReturn( Optional.empty() );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return "lena".equals( login )
                || "admin".equals( login ); }
            @Override public String displayName( final String login ) { return login; }
        }, keys, Clock.fixed( NOW, ZoneOffset.UTC ), WorkSettings.DEFAULTS, null,
            ( actor, action, type, target, detail ) -> audited.add( actor + "|" + action + "|" + type + "|" + target
                + "|" + detail ) );
    }

    private AgentDraft draft( final String name, final String owner, final String... queues ) {
        return new AgentDraft( name, "openai", "codex", List.of( queues ), owner );
    }

    @Test
    void nonAdminCannotManageQueuesOrAgents() {
        final AgentDraft d = draft( "codex@docker2", "lena", "implementer" );
        assertThrows( WorkDeniedException.class, () -> service.registry().createQueue( mallory, "ops", "Ops", "" ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().updateQueue( mallory, "ops", "Ops", "" ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().retireQueue( mallory, "ops" ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().registerAgent( mallory, d ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().updateAgent( mallory, 1, d ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().rotateAgentKey( mallory, 1, null ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().retireAgent( mallory, 1 ) );
    }

    @Test
    void nonAdminGetsDenialNotNotFoundForUnknownAgent() throws SQLException {
        when( store.agent( 999 ) ).thenReturn( Optional.empty() );
        when( store.queue( "ghost" ) ).thenReturn( Optional.empty() );
        assertThrows( WorkDeniedException.class, () -> service.registry().updateAgent( mallory, 999,
            draft( "x", "lena" ) ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().rotateAgentKey( mallory, 999, null ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().retireAgent( mallory, 999 ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().retireQueue( mallory, "ghost" ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().updateQueue( mallory, "ghost", "G", "" ) );
    }

    @Test
    void adminGetsNotFoundForUnknownAgentAndQueue() throws SQLException {
        when( store.agent( 999 ) ).thenReturn( Optional.empty() );
        assertThrows( WorkNotFoundException.class, () -> service.registry().updateAgent( admin, 999, draft( "x", "lena" ) ) );
        assertThrows( WorkNotFoundException.class, () -> service.registry().rotateAgentKey( admin, 999, null ) );
        assertThrows( WorkNotFoundException.class, () -> service.registry().retireAgent( admin, 999 ) );
        assertThrows( WorkNotFoundException.class, () -> service.registry().retireQueue( admin, "nope" ) );
        assertThrows( WorkNotFoundException.class, () -> service.registry().updateQueue( admin, "nope", "N", "" ) );
        verify( store, never() ).updateAgent( org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
            anyString(), anyList() );
        verify( store, never() ).rebindAgentKey( org.mockito.ArgumentMatchers.anyLong(), anyInt() );
        assertTrue( keys.minted.isEmpty() );
    }

    @Test
    void agentActorCannotManage() {
        final Actor bot = Actor.agent( 5, "bot" );
        final Actor flagged = new Actor( "bot", Set.of(), true, Actor.Kind.AGENT, 5L );
        for ( final Actor a : List.of( bot, flagged ) ) {
            assertThrows( WorkDeniedException.class, () -> service.registry().createQueue( a, "ops", "Ops", "" ) );
            assertThrows( WorkDeniedException.class, () -> service.registry().registerAgent( a, draft( "x", "lena" ) ) );
            assertThrows( WorkDeniedException.class, () -> service.registry().retireAgent( a, 1 ) );
        }
    }

    @Test
    void registerMintsThenStores() throws SQLException {
        when( store.registerAgent( eq( "codex@docker2" ), eq( "openai" ), eq( "codex" ), eq( 100 ),
            eq( List.of( "implementer" ) ) ) ).thenReturn( agent( 7, "codex@docker2", 100, null ) );
        final WorkService.AgentWithToken r = service.registry().registerAgent( admin,
            draft( "Codex@Docker2", "lena", "implementer" ) );
        assertEquals( List.of( "lena|work-agent:codex@docker2" ), keys.minted );
        assertEquals( "token-100", r.token() );
        assertEquals( 7, r.agent().id() );
        assertTrue( keys.revoked.isEmpty() );
        assertFalse( r.toString().contains( "token-100" ), "the token must not reach a log line via toString" );
    }

    @Test
    void registerRevokesKeyWhenStoreFails() throws SQLException {
        final SQLException boom = new SQLException( "boom", "XX000" );
        when( store.registerAgent( anyString(), anyString(), anyString(), anyInt(), anyList() ) ).thenThrow( boom );
        final SQLException thrown = assertThrows( SQLException.class,
            () -> service.registry().registerAgent( admin, draft( "codex@docker2", "lena", "implementer" ) ) );
        assertSame( boom, thrown );
        assertEquals( List.of( "100|admin" ), keys.revoked );
    }

    @Test
    void registerRevokesKeyAndReportsConflictOnUniqueViolation() throws SQLException {
        when( store.registerAgent( anyString(), anyString(), anyString(), anyInt(), anyList() ) )
            .thenThrow( new SQLException( "dup", "23505" ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> service.registry().registerAgent( admin, draft( "codex", "lena", "implementer" ) ) );
        assertEquals( WorkConflictException.EXISTS, e.reason() );
        assertEquals( List.of( "100|admin" ), keys.revoked );
    }

    @Test
    void registerRejectsDuplicateNameBeforeMinting() throws SQLException {
        when( store.agentByName( "codex" ) ).thenReturn( Optional.of( agent( 1, "codex", 3, null ) ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> service.registry().registerAgent( admin, draft( "CODEX", "lena" ) ) );
        assertEquals( WorkConflictException.EXISTS, e.reason() );
        assertTrue( keys.minted.isEmpty() );
    }

    @Test
    void registerValidatesBeforeMinting() {
        assertThrows( WorkValidationException.class, () -> service.registry().registerAgent( admin, draft( "bad name!", "lena" ) ) );
        assertThrows( WorkValidationException.class, () -> service.registry().registerAgent( admin, draft( null, "lena" ) ) );
        assertTrue( keys.minted.isEmpty() );
    }

    @Test
    void retiredQueueRefusedForAgent() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> service.registry().registerAgent( admin, draft( "codex", "lena", "old" ) ) );
        assertEquals( "queues", e.field() );
        assertThrows( WorkValidationException.class,
            () -> service.registry().registerAgent( admin, draft( "codex", "lena", "nope" ) ) );
        assertTrue( keys.minted.isEmpty() );
    }

    @Test
    void unknownOwnerRejected() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> service.registry().registerAgent( admin, draft( "codex", "nobody", "implementer" ) ) );
        assertEquals( "owner", e.field() );
        assertTrue( keys.minted.isEmpty() );
    }

    @Test
    void ownerDefaultsToTheCaller() throws SQLException {
        when( store.registerAgent( anyString(), anyString(), anyString(), anyInt(), anyList() ) )
            .thenReturn( agent( 7, "codex", 100, null ) );
        service.registry().registerAgent( admin, draft( "codex", null, "implementer" ) );
        assertEquals( List.of( "admin|work-agent:codex" ), keys.minted );
    }

    @Test
    void updateAgentRefusesRenameOntoAnotherAgent() throws SQLException {
        when( store.agent( 1 ) ).thenReturn( Optional.of( agent( 1, "codex", 3, null ) ) );
        when( store.agentByName( "other" ) ).thenReturn( Optional.of( agent( 2, "other", 4, null ) ) );
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> service.registry().updateAgent( admin, 1, draft( "Other", "ignored" ) ) ).reason() );
        verify( store, never() ).updateAgent( org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
            anyString(), anyList() );
    }

    @Test
    void updateAgentTurnsUniqueViolationIntoConflict() throws SQLException {
        when( store.agent( 1 ) ).thenReturn( Optional.of( agent( 1, "codex", 3, null ) ) );
        doThrow( new SQLException( "dup", "23505" ) ).when( store ).updateAgent( eq( 1L ), anyString(), anyString(),
            anyString(), anyList() );
        assertThrows( WorkConflictException.class, () -> service.registry().updateAgent( admin, 1, draft( "fresh", null ) ) );
    }

    @Test
    void updateAgentStoresNormalisedName() throws SQLException {
        when( store.agent( 1 ) ).thenReturn( Optional.of( agent( 1, "codex", 3, null ) ) );
        service.registry().updateAgent( admin, 1, draft( " Codex ", "ignored", "implementer" ) );
        verify( store ).updateAgent( 1L, "codex", "openai", "codex", List.of( "implementer" ) );
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
    void rotateRevokesOldAfterRebind() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        final WorkService.AgentWithToken r = service.registry().rotateAgentKey( admin, 7, null );
        assertEquals( 100, r.agent().apiKeyId() );
        assertEquals( "token-100", r.token() );
        assertEquals( List.of( "55|admin" ), keys.revoked );
        inOrder( store ).verify( store ).rebindAgentKey( 7L, 100 );
    }

    @Test
    void rotateKeepsTheOriginalOwner() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        service.registry().rotateAgentKey( admin, 7, null );
        assertEquals( List.of( "55|work-agent:codex" ), keys.reminted, "the replacement is minted from the old key" );
        assertTrue( keys.minted.isEmpty(), "rotation never mints a key owned by the rotating admin" );
    }

    @Test
    void rotateOfAnAgentWithNoKeyMintsForTheAdmin() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", null, null ) ) );
        service.registry().rotateAgentKey( admin, 7, null );
        assertEquals( List.of( "admin|work-agent:codex" ), keys.minted );
        assertTrue( keys.reminted.isEmpty() );
        assertTrue( keys.revoked.isEmpty() );
    }

    @Test
    void rotateWhenTheOldKeyCannotBeRemintedChangesNothing() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        final AgentKeys failing = new AgentKeys() {
            @Override public Minted mint( final String o, final String l, final String by ) {
                throw new AssertionError( "must not mint" );
            }
            @Override public Minted remint( final int id, final String l, final String by ) {
                throw new WorkConflictException( "The agent's key no longer exists." );
            }
            @Override public void revoke( final int id, final String by ) {
                throw new AssertionError( "must not revoke" );
            }
        };
        final WorkService svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, failing, Clock.fixed( NOW, ZoneOffset.UTC ), WorkSettings.DEFAULTS, null );
        assertThrows( WorkConflictException.class, () -> svc.registry().rotateAgentKey( admin, 7, null ) );
        verify( store, never() ).rebindAgentKey( org.mockito.ArgumentMatchers.anyLong(), anyInt() );
    }

    @Test
    void rotateRevokesNewWhenRebindFails() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        final SQLException boom = new SQLException( "boom" );
        doThrow( boom ).when( store ).rebindAgentKey( 7L, 100 );
        assertSame( boom, assertThrows( SQLException.class, () -> service.registry().rotateAgentKey( admin, 7, null ) ) );
        assertEquals( List.of( "100|admin" ), keys.revoked, "only the new key is revoked; the old stays bound" );
    }

    @Test
    void rotateOfRetiredAgentIsNotFound() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, NOW ) ) );
        assertThrows( WorkNotFoundException.class, () -> service.registry().rotateAgentKey( admin, 7, null ) );
        assertTrue( keys.minted.isEmpty() );
    }

    @Test
    void retireRevokesKey() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        service.registry().retireAgent( admin, 7 );
        verify( claims ).retireAgent( 7L, NOW, admin );
        assertEquals( List.of( "55|admin" ), keys.revoked );
    }

    @Test
    void retireSurvivesARevokeFailure() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        keys.revokeFails = new IllegalStateException( "key service down" );
        service.registry().retireAgent( admin, 7 );
        verify( claims ).retireAgent( 7L, NOW, admin );
    }

    @Test
    void retireOfRetiredAgentRetriesTheRevokeAndReleasesNothingNew() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, NOW ) ) );
        service.registry().retireAgent( admin, 7 );
        verify( claims, never() ).retireAgent( org.mockito.ArgumentMatchers.anyLong(), any(), any() );
        assertEquals( List.of( "55|admin" ), keys.revoked );
    }

    @Test
    void aKeyInTheHistoryIsNoActor() throws SQLException {
        when( store.agentByApiKey( 58 ) ).thenReturn( Optional.empty() );
        when( store.wasEverAgentKey( 58 ) ).thenReturn( true );
        assertNull( service.registry().actorForApiKey( 58, "lena" ) );
    }

    @Test
    void nonAdminSeesAgentsWithoutKeyIds() throws SQLException {
        when( store.agents() ).thenReturn( List.of( agent( 7, "codex", 55, null ) ) );
        when( store.visibleProjects( mallory ) ).thenReturn( List.of() );
        assertTrue( service.registry().listAgents( mallory ).isEmpty() );
        assertTrue( service.registry().listQueues( mallory ).isEmpty() );
        final Actor lena = Actor.human( "lena", Set.of(), false );
        when( store.visibleProjects( lena ) ).thenReturn( List.of( new Project( 1, "PLAT", "P", "", Autonomy.DELEGATED, NOW, null ) ) );
        final List< Agent > seen = service.registry().listAgents( lena );
        assertEquals( 1, seen.size() );
        assertNull( seen.get( 0 ).apiKeyId() );
        assertEquals( "codex", seen.get( 0 ).name() );
        assertEquals( 55, service.registry().listAgents( admin ).get( 0 ).apiKeyId() );
    }

    @Test
    void actorForApiKeyDerivesKindFromTheBinding() throws SQLException {
        when( store.agentByApiKey( 55 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        when( store.agentByApiKey( 56 ) ).thenReturn( Optional.of( agent( 8, "gone", 56, NOW ) ) );
        when( store.agentByApiKey( 57 ) ).thenReturn( Optional.empty() );
        assertEquals( Actor.agent( 7, "codex" ), service.registry().actorForApiKey( 55, "lena" ) );
        assertNull( service.registry().actorForApiKey( 56, "lena" ) );
        assertEquals( Actor.human( "lena", Set.of(), false ), service.registry().actorForApiKey( 57, "lena" ) );
    }

    @Test
    void agentMembersResolveByNameAndRefuseLead() throws SQLException {
        final Project plat = new Project( 1, "PLAT", "P", "", Autonomy.DELEGATED, NOW, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.agentByName( "codex" ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        when( store.agentByName( "gone" ) ).thenReturn( Optional.of( agent( 8, "gone", 56, NOW ) ) );
        service.putMember( admin, "PLAT", "Codex", "agent", WorkRole.MEMBER );
        verify( store ).putMember( 1L, "7", "agent", WorkRole.MEMBER, admin );
        assertEquals( "principal", assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "gone", "agent", WorkRole.MEMBER ) ).field() );
        assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "ghost", "agent", WorkRole.MEMBER ) );
        assertEquals( "role", assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "codex", "agent", WorkRole.LEAD ) ).field() );
        service.removeMember( admin, "PLAT", "gone", "agent" );
        verify( store ).removeMember( 1L, "8", "agent", admin );
    }

    @Test
    void agentNameForKeyNamesTheAgentAKeyIsOrWasBoundTo() throws SQLException {
        when( store.agentEverBoundTo( 55 ) ).thenReturn( Optional.of( agent( 7, "codex", 56, null ) ) );
        when( store.agentEverBoundTo( 57 ) ).thenReturn( Optional.empty() );
        assertEquals( Optional.of( "codex" ), service.registry().agentNameForKey( 55 ) );
        assertTrue( service.registry().isAgentKey( 55 ) );
        assertEquals( Optional.empty(), service.registry().agentNameForKey( 57 ) );
        assertFalse( service.registry().isAgentKey( 57 ) );
    }

    // ---- P28: accountability

    @Test
    void keysRecordTheActingAdminAsCreatorWhileTheOwnerStaysTheOwner() throws SQLException {
        when( store.agentByName( "codex" ) ).thenReturn( Optional.empty() );
        when( store.registerAgent( eq( "codex" ), anyString(), anyString(), anyInt(), anyList() ) )
            .thenReturn( agent( 7, "codex", 100, null ) );
        service.registry().registerAgent( admin, draft( "codex", "lena", "implementer" ) );
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 100, null ) ) );
        service.registry().rotateAgentKey( admin, 7, null );
        assertEquals( List.of( "lena|work-agent:codex" ), keys.minted, "the owner is the principal" );
        assertEquals( List.of( "100|work-agent:codex" ), keys.reminted );
        assertEquals( List.of( "admin", "admin" ), keys.creators, "the acting admin is the creator of both keys" );
    }

    @Test
    void everyRegistryChangeReachesTheAdminAuditTrailWithoutAKeyOrToken() throws SQLException {
        when( store.queue( "ops" ) ).thenReturn( Optional.empty(), Optional.of( new Queue( "ops", "Ops", "", null ) ) );
        when( store.createQueue( "ops", "Ops", "" ) ).thenReturn( new Queue( "ops", "Ops", "", null ) );
        when( store.agentByName( "codex" ) ).thenReturn( Optional.empty() );
        when( store.registerAgent( eq( "codex" ), anyString(), anyString(), anyInt(), anyList() ) )
            .thenReturn( agent( 7, "codex", 100, null ) );
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 100, null ) ) );
        when( store.agentByName( "codex2" ) ).thenReturn( Optional.empty() );
        final WorkRegistryService r = service.registry();
        r.createQueue( admin, "ops", "Ops", "" );
        r.updateQueue( admin, "ops", "Operations", "" );
        r.retireQueue( admin, "ops" );
        final String token = r.registerAgent( admin, draft( "codex", "lena", "implementer" ) ).token();
        r.updateAgent( admin, 7, draft( "codex2", null, "implementer" ) );
        final String rotated = r.rotateAgentKey( admin, 7, null ).token();
        r.retireAgent( admin, 7 );
        assertEquals( List.of(
            "admin|work.queue.create|work_queue|ops|null",
            "admin|work.queue.update|work_queue|ops|null",
            "admin|work.queue.retire|work_queue|ops|null",
            "admin|work.agent.register|work_agent|codex|null",
            "admin|work.agent.update|work_agent|codex2|renamed from codex",
            "admin|work.agent.rotate_key|work_agent|codex|null",
            "admin|work.agent.retire|work_agent|codex|null" ), audited );
        for ( final String entry : audited ) {
            assertFalse( entry.contains( token ) || entry.contains( rotated ) || entry.contains( "100" )
                || entry.contains( "101" ), "no token or key id: " + entry );
        }
    }

    @Test
    void aFailingAuditNeverUndoesTheChange() throws SQLException {
        final WorkService svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, keys, Clock.fixed( NOW, ZoneOffset.UTC ), WorkSettings.DEFAULTS, null,
            ( actor, action, type, target, detail ) -> { throw new IllegalStateException( "audit down" ); } );
        when( store.queue( "ops" ) ).thenReturn( Optional.of( new Queue( "ops", "Ops", "", null ) ) );
        svc.registry().retireQueue( admin, "ops" );
        verify( store ).retireQueue( "ops", NOW );
    }

    // ---- M5: rotation may name a new owner

    @Test
    void rotationWithANewOwnerMintsUnderThatOwner() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        final WorkService.AgentWithToken r = service.registry().rotateAgentKey( admin, 7, "lena" );
        assertEquals( List.of( "lena|work-agent:codex" ), keys.minted );
        assertTrue( keys.reminted.isEmpty(), "a named owner is minted for, not re-minted from the old key" );
        assertEquals( List.of( "55|admin" ), keys.revoked, "the old key is revoked" );
        verify( store ).rebindAgentKey( 7, r.agent().apiKeyId() );
        assertEquals( "admin|work.agent.rotate_key|work_agent|codex|new owner lena", audited.get( 0 ) );
    }

    @Test
    void rotationToAnUnknownOwnerChangesNothing() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( 7, "codex", 55, null ) ) );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> service.registry().rotateAgentKey( admin, 7, "ghost" ) );
        assertEquals( "owner", e.field() );
        assertTrue( keys.minted.isEmpty() && keys.reminted.isEmpty() && keys.revoked.isEmpty() );
        verify( store, never() ).rebindAgentKey( org.mockito.ArgumentMatchers.anyLong(), anyInt() );
    }
}
