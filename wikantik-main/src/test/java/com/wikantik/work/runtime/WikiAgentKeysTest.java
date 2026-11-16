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
package com.wikantik.work.runtime;

import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.AgentKeys;
import com.wikantik.work.WorkConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WikiAgentKeys} against the real {@code api_keys} table. */
@RequiresPostgres
class WikiAgentKeysTest {

    private ApiKeyService service;
    private WikiAgentKeys keys;
    private String owner;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService( PostgresTestDb.createDataSource() );
        keys = new WikiAgentKeys( service );
        owner = "owner-" + UUID.randomUUID();
    }

    @Test
    void mintsWorkScopedKeyForOwner() {
        final AgentKeys.Minted m = keys.mint( owner, "work-agent:codex", "janne" );
        final ApiKeyService.Record r = service.findById( m.keyId() ).orElseThrow();
        assertEquals( owner, r.principalLogin() );
        assertEquals( ApiKeyService.Scope.WORK, r.scope(), "a minted agent key is work-scoped and nothing broader" );
        assertEquals( "work-agent:codex", r.label() );
        assertTrue( service.verify( m.token() ).isPresent(), "the token verifies" );
    }

    @Test
    void remintKeepsOwnerAndScope() {
        // The old key is deliberately broader than work: the replacement must still be work-only.
        final ApiKeyService.Generated old = service.generate( owner, "old", ApiKeyService.Scope.ALL, "someone" );
        final AgentKeys.Minted m = keys.remint( old.record().id(), "work-agent:codex", "janne" );
        assertNotEquals( old.record().id(), m.keyId() );
        final ApiKeyService.Record r = service.findById( m.keyId() ).orElseThrow();
        assertEquals( owner, r.principalLogin(), "owner is the old key's owner" );
        assertEquals( ApiKeyService.Scope.WORK, r.scope(), "scope is work, never copied from the old key" );
        assertEquals( "work-agent:codex", r.label() );
        assertTrue( service.verify( m.token() ).isPresent() );
    }

    @Test
    void remintOfUnknownKeyMintsNothing() {
        final int before = service.list().size();
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> keys.remint( Integer.MAX_VALUE, "work-agent:ghost", "janne" ) );
        assertFalse( e.getMessage().isBlank() );
        assertEquals( before, service.list().size(), "no key was minted" );
    }

    @Test
    void revokeDelegates() {
        final AgentKeys.Minted m = keys.mint( owner, "x", "janne" );
        keys.revoke( m.keyId(), "admin" );
        assertFalse( service.findById( m.keyId() ).orElseThrow().isActive() );
        assertTrue( service.verify( m.token() ).isEmpty() );
    }

    @Test
    void theActingAdminIsTheCreatorAndTheOwnerThePrincipal() {
        final AgentKeys.Minted m = keys.mint( owner, "work-agent:codex", "janne" );
        final ApiKeyService.Record r = service.findById( m.keyId() ).orElseThrow();
        assertEquals( owner, r.principalLogin() );
        assertEquals( "janne", r.createdBy() );
        final AgentKeys.Minted again = keys.remint( m.keyId(), "work-agent:codex", "root" );
        final ApiKeyService.Record r2 = service.findById( again.keyId() ).orElseThrow();
        assertEquals( owner, r2.principalLogin() );
        assertEquals( "root", r2.createdBy() );
    }

    @Test
    void anAgentKeyRevokedOutOfBandIsRestoredByARemint() {
        // The recovery path: the old key was revoked (owner deleted, or by hand); re-minting from it still works.
        final AgentKeys.Minted m = keys.mint( owner, "work-agent:codex", "janne" );
        service.revokeAllForPrincipal( owner );
        assertTrue( service.verify( m.token() ).isEmpty() );
        final AgentKeys.Minted restored = keys.remint( m.keyId(), "work-agent:codex", "janne" );
        assertTrue( service.verify( restored.token() ).isPresent(), "the re-minted key works" );
        assertEquals( owner, service.findById( restored.keyId() ).orElseThrow().principalLogin() );
    }
}
