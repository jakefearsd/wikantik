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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A token resolves to an agent, and the agent to an actor only while it is active and has a living sponsor. */
@RequiresPostgres
class WorkAgentTokenIdentityPostgresTest {

    private JdbcWorkStore store;
    private WorkService service;
    private long agent;
    private String token;
    private final Set< String > gone = new HashSet<>();
    private boolean outage;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of( "Crew" ); }
            @Override public boolean exists( final String login ) { return !gone.contains( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< Person > person( final String login ) {
                if ( outage ) {
                    throw new WorkUnavailableException( "directory down" );
                }
                return gone.contains( login ) ? Optional.empty()
                    : Optional.of( new Person( login, groupsOf( login ), false ) );
            }
        }, Clock.systemUTC() );
        agent = store.registerAgent( "codex", "", "", List.of(), "ana" ).id();
        token = store.credentials().issue( agent, "ana" ).token();
    }

    private Actor actor() throws SQLException {
        final WorkRegistryService.AgentLogin login = service.registry().agentForToken( token ).orElseThrow();
        assertEquals( agent, login.agentId() );
        return service.registry().actorForAgent( login.agentId() );
    }

    @Test
    void aLiveTokenResolvesToItsAgent() throws SQLException {
        final Actor a = actor();
        assertEquals( Actor.agent( agent, "codex", Actor.human( "ana", Set.of( "Crew" ), false ) ), a );
        assertEquals( 1, WorkAgentFixtures.scalar( PostgresTestDb.createDataSource(),
            "SELECT count(*) FROM work_agent_credential WHERE last_used_at IS NOT NULL" ), "the use was recorded" );
    }

    @Test
    void anUnknownOrRevokedTokenIsNobody() throws SQLException {
        assertTrue( service.registry().agentForToken( "wka_" + "x".repeat( 43 ) ).isEmpty() );
        store.credentials().revokeAll( agent );
        assertTrue( service.registry().agentForToken( token ).isEmpty() );
    }

    @Test
    void aRetiredAgentsTokenIsNoActor() throws SQLException {
        store.claims().retireAgent( agent, java.time.Instant.now(), Actor.human( "root", Set.of(), true ) );
        assertNull( actor() );
    }

    @Test
    void aSponsorlessAgentsTokenIsNoActor() throws SQLException {
        store.setSponsor( agent, null, "ana" );
        assertNull( actor() );
        gone.add( "bo" );
        store.setSponsor( agent, "bo", "root" );
        assertNull( actor(), "a sponsor whose account is gone is no sponsor" );
        gone.clear();
        assertNotNull( actor() );
    }

    @Test
    void aDirectoryOutageIsUnavailableNotNoActor() {
        outage = true;
        assertThrows( WorkUnavailableException.class, this::actor );
    }

    @Test
    void aPersonalKeyStringNeverReachesTheCredentialTable() throws SQLException {
        final WorkStore spy = org.mockito.Mockito.spy( store );
        final WorkService svc = new WorkService( spy, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC() );
        assertTrue( svc.registry().agentForToken( "wkk_" + "x".repeat( 43 ) ).isEmpty() );
        assertTrue( svc.registry().agentForToken( null ).isEmpty() );
        org.mockito.Mockito.verify( spy, org.mockito.Mockito.never() ).credentials();
    }
}
