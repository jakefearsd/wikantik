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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkRegistryService;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiWorkActorsTest {

    private TestEngine engine;
    private GroupManager groups;
    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        groups = engine.getManager( GroupManager.class );
        admin = engine.adminSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            groups.removeGroup( "WorkCrew" );
        } catch ( final com.wikantik.auth.NoSuchPrincipalException e ) {
            // the group was never created by this test
        }
        engine.stop();
    }

    private void setMembers( final String members ) throws Exception {
        final Group group = groups.parseGroup( "WorkCrew", members, true );
        groups.setGroup( admin, group );
    }

    @Test
    void anonymousIsNull() {
        assertNull( WikiWorkActors.actorFor( engine, com.wikantik.api.spi.Wiki.session().guest( engine ) ) );
        assertNull( WikiWorkActors.actorFor( engine, null ) );
    }

    @Test
    void loginAndKindAreResolved() throws Exception {
        final Actor actor = WikiWorkActors.actorFor( engine, engine.janneSession() );
        assertEquals( "janne", actor.login() );
        assertEquals( Actor.Kind.HUMAN, actor.kind() );
    }

    @Test
    void groupsReflectMembershipChangeImmediately() throws Exception {
        final Session janne = engine.janneSession();
        assertFalse( WikiWorkActors.actorFor( engine, janne ).groups().contains( "WorkCrew" ) );

        setMembers( "janne" );
        assertTrue( WikiWorkActors.actorFor( engine, janne ).groups().contains( "WorkCrew" ),
            "added to the group: the next call sees it" );

        setMembers( "someoneElse" );
        assertFalse( WikiWorkActors.actorFor( engine, janne ).groups().contains( "WorkCrew" ),
            "removed from the group: the next call sees it" );
    }

    @Test
    void adminFlagFollowsAllPermission() throws Exception {
        assertTrue( WikiWorkActors.actorFor( engine, admin ).admin() );
        assertFalse( WikiWorkActors.actorFor( engine, engine.janneSession() ).admin() );
    }

    // ---- actorForKey: the registry is a mock here; the real-store semantics (retired agent, rotated-away key)
    // are pinned in wikantik-work's WorkRegistryServiceTest / WorkAgentIdentityPostgresTest.

    private final WorkService service = mock( WorkService.class );
    private final WorkRegistryService registry = mock( WorkRegistryService.class );

    private static ApiKeyService.Record key( final int id, final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record( id, "hash", "owner", "label", scope, java.time.Instant.now(), "admin",
            null, null, null, false );
    }

    @Test
    void mcpReadKeyIsNoWorkActor() throws Exception {
        when( service.registry() ).thenReturn( registry );
        for ( final ApiKeyService.Scope scope : ApiKeyService.Scope.values() ) {
            if ( scope.matches( ApiKeyService.Scope.WORK ) ) {
                continue;
            }
            assertNull( WikiWorkActors.actorForKey( service, key( 5, scope ) ), scope + " is no work key" );
        }
        verify( registry, never() ).actorForApiKey( anyInt(), anyString() );
    }

    @Test
    void workKeyDelegatesToService() throws Exception {
        when( service.registry() ).thenReturn( registry );
        final Actor bot = Actor.agent( 3, "codex" );
        when( registry.actorForApiKey( 5, "owner" ) ).thenReturn( bot );
        assertEquals( bot, WikiWorkActors.actorForKey( service, key( 5, ApiKeyService.Scope.WORK ) ) );
    }

    @Test
    void allScopeKeyAlsoReachesWork() throws Exception {
        when( service.registry() ).thenReturn( registry );
        final Actor owner = Actor.human( "owner", java.util.Set.of(), false );
        when( registry.actorForApiKey( 6, "owner" ) ).thenReturn( owner );
        assertEquals( owner, WikiWorkActors.actorForKey( service, key( 6, ApiKeyService.Scope.ALL ) ) );
    }

    @Test
    void aRetiredOrRotatedAwayKeyIsNullWithNoFallbackToItsOwner() throws Exception {
        when( service.registry() ).thenReturn( registry );
        when( registry.actorForApiKey( 7, "owner" ) ).thenReturn( null );
        assertNull( WikiWorkActors.actorForKey( service, key( 7, ApiKeyService.Scope.WORK ) ),
            "null passes through; the key's owner is never substituted" );
        verify( registry ).actorForApiKey( 7, "owner" );
    }
}
