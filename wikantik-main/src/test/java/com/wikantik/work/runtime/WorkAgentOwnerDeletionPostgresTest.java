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
import com.wikantik.work.Actor;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.JdbcWorkStore;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner of an agent's key is deleted (every key they own is revoked, as {@code UserDeletionService} does): the
 * agent's key then verifies as nothing (REST 401, MCP "needs a work API key"), and rotation, with a new owner or from
 * the revoked key, restores it. Real {@code api_keys} and {@code work_*} rows, the real key port.
 */
@RequiresPostgres
class WorkAgentOwnerDeletionPostgresTest {

    private final Actor admin = Actor.human( "janne", Set.of(), true );
    private ApiKeyService apiKeys;
    private WorkService svc;
    private String owner;
    private String successor;
    /** Who the user database has; a test removes the owner to model its deletion. */
    private Set< String > people;
    /** Unique per test: the work tables are shared and never truncated here. */
    private String name;

    @BeforeEach
    void setUp() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        apiKeys = new ApiKeyService( ds );
        owner = "owner-" + UUID.randomUUID();
        successor = "successor-" + UUID.randomUUID();
        name = "codex-" + UUID.randomUUID().toString().substring( 0, 8 ) + "@it";
        people = new java.util.HashSet<>( Set.of( owner, successor, "janne" ) );
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return people.contains( login ); }
            @Override public String displayName( final String login ) { return login; }
        }, new WikiAgentKeys( apiKeys, login -> people.contains( login ) ), Clock.systemUTC(), WorkSettings.DEFAULTS,
            null );
    }

    /** What the REST filter and the MCP endpoint resolve a bearer token to: null is a 401 or a refusal. */
    private Actor actorFor( final String token ) throws Exception {
        final ApiKeyService.Record rec = apiKeys.verify( token ).orElse( null );
        return rec == null ? null : WikiWorkActors.actorForKey( svc, rec );
    }

    @Test
    void anAgentWhoseOwnerWasDeletedActsNoMoreUntilRotationRestoresIt() throws Exception {
        final WorkService.AgentWithToken made = svc.registry().registerAgent( admin, new AgentDraft( name, "",
            "", List.of(), owner ) );
        assertEquals( Actor.agent( made.agent().id(), name ), actorFor( made.token() ) );

        apiKeys.revokeAllForPrincipalOrThrow( owner );

        assertEquals( null, actorFor( made.token() ), "the agent's key resolves to no actor" );
        assertTrue( svc.registry().listAgents( admin ).stream().filter( a -> a.id() == made.agent().id() ).findFirst()
            .orElseThrow().active(), "the agent is not retired" );

        final WorkService.AgentWithToken rotated = svc.registry().rotateAgentKey( admin, made.agent().id(), successor );
        assertEquals( Actor.agent( made.agent().id(), name ), actorFor( rotated.token() ), "restored" );
        final ApiKeyService.Record key = apiKeys.findById( rotated.agent().apiKeyId() ).orElseThrow();
        assertEquals( successor, key.principalLogin() );
        assertEquals( "janne", key.createdBy() );
        assertEquals( null, actorFor( made.token() ), "the old key stays dead" );
    }

    @Test
    void rotationWithoutAnOwnerRemintsFromTheRevokedKey() throws Exception {
        final WorkService.AgentWithToken made = svc.registry().registerAgent( admin, new AgentDraft( name, "",
            "", List.of(), owner ) );
        apiKeys.revoke( made.agent().apiKeyId(), "someone" );
        final WorkService.AgentWithToken rotated = svc.registry().rotateAgentKey( admin, made.agent().id(), null );
        assertEquals( Actor.agent( made.agent().id(), name ), actorFor( rotated.token() ) );
        assertEquals( owner, apiKeys.findById( rotated.agent().apiKeyId() ).orElseThrow().principalLogin() );
    }

    @Test
    void rotationWithoutANewOwnerIsRefusedOnceTheOwnerIsDeleted() throws Exception {
        final WorkService.AgentWithToken made = svc.registry().registerAgent( admin, new AgentDraft( name, "",
            "", List.of(), owner ) );
        apiKeys.revokeAllForPrincipalOrThrow( owner );
        people.remove( owner );

        final com.wikantik.work.WorkValidationException e = assertThrows( com.wikantik.work.WorkValidationException.class,
            () -> svc.registry().rotateAgentKey( admin, made.agent().id(), null ) );
        assertEquals( "owner", e.field() );
        assertEquals( "This agent's key owner no longer exists. Name a new owner.", e.getMessage() );
        assertEquals( null, actorFor( made.token() ) );

        final WorkService.AgentWithToken rotated = svc.registry().rotateAgentKey( admin, made.agent().id(), successor );
        assertEquals( Actor.agent( made.agent().id(), name ), actorFor( rotated.token() ), "a named owner restores it" );
    }
}
