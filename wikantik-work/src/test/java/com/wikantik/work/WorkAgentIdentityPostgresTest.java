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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identity from an API key and agent membership, against a real database: an agent sees only what it is admitted to. */
@RequiresPostgres
class WorkAgentIdentityPostgresTest {

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkService service;
    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        final AgentKeys keys = new AgentKeys() {
            @Override
            public Minted mint( final String ownerLogin, final String label ) {
                try {
                    return new Minted( ( int ) WorkAgentFixtures.apiKey( ds, "work" ), "tok" );
                } catch ( final SQLException e ) {
                    throw new IllegalStateException( e );
                }
            }

            @Override
            public void revoke( final int keyId, final String revokedBy ) {
                // the key row stays; retirement is what makes it no actor
            }
        };
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, keys, Clock.systemUTC(), WorkSettings.DEFAULTS, null );
        service.createProject( admin, "PLAT", "Platform", "" );
        service.createProject( admin, "OPS", "Operations", "" );
        service.putMember( admin, "PLAT", "lena", "user", WorkRole.LEAD );
        service.putMember( admin, "OPS", "lena", "user", WorkRole.LEAD );
        service.createQueue( admin, "implementer", "Implementer", "" );
        service.createItem( lena, draft( "PLAT" ) );
        service.createItem( lena, draft( "OPS" ) );
    }

    private static ItemDraft draft( final String project ) {
        return new ItemDraft( project, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", null, null, null,
            null );
    }

    private WorkService.AgentWithToken register( final String name ) throws SQLException {
        return service.registerAgent( admin, new AgentDraft( name, "", "", List.of( "implementer" ), "lena" ) );
    }

    private Actor actorFor( final WorkService.AgentWithToken r ) throws SQLException {
        return service.actorForApiKey( r.agent().apiKeyId(), "lena" );
    }

    @Test
    void boundKeyIsTheAgent() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex@docker2" );
        final Actor a = actorFor( r );
        assertEquals( Actor.Kind.AGENT, a.kind() );
        assertEquals( r.agent().id(), a.agentId() );
        assertEquals( "codex@docker2", a.login() );
        assertEquals( false, a.admin() );
    }

    @Test
    void retiredAgentKeyIsNoActor() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        service.retireAgent( admin, r.agent().id() );
        assertNull( actorFor( r ), "a retired agent's key is no actor, and never falls back to its owner" );
    }

    @Test
    void unboundKeyIsTheOwnerNeverAdmin() throws SQLException {
        final int unbound = ( int ) WorkAgentFixtures.apiKey( ds, "work" );
        final Actor owner = service.actorForApiKey( unbound, "admin" );
        assertEquals( Actor.Kind.HUMAN, owner.kind() );
        assertEquals( "admin", owner.login() );
        assertEquals( false, owner.admin(), "a work key never carries admin rights, even for an admin owner" );
        assertTrue( service.listProjects( owner ).isEmpty() );
    }

    @Test
    void agentOwnedByLeadSeesOnlyAdmittedProjects() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        service.putMember( lena, "PLAT", "codex", "agent", WorkRole.MEMBER );
        final Actor agent = actorFor( r );
        assertEquals( List.of( "PLAT" ), service.listProjects( agent ).stream().map( Project::key ).toList() );
        assertEquals( "PLAT-1", service.item( agent, "PLAT-1" ).item().key() );
        final WorkNotFoundException hidden = assertThrows( WorkNotFoundException.class,
            () -> service.item( agent, "OPS-1" ) );
        final WorkNotFoundException unknown = assertThrows( WorkNotFoundException.class,
            () -> service.item( agent, "NOPE-1" ) );
        assertEquals( unknown.getMessage(), hidden.getMessage() );
    }

    @Test
    void agentNamedLikeALoginGetsNoneOfThePersonsItems() throws SQLException {
        service.putMember( admin, "PLAT", "jake", "user", WorkRole.MEMBER );
        final Item item = service.createItem( lena, draft( "PLAT" ) ).item();
        service.updateItem( admin, item.key(), new ItemPatch( item.version(),
            java.util.Map.of( "assignee", "jake" ), null ) );
        assertEquals( 1, service.waitingOn( Actor.human( "jake", Set.of(), false ) ).size() );
        final WorkService.AgentWithToken r = register( "jake" );
        final Actor agent = actorFor( r );
        assertEquals( "jake", agent.login() );
        assertTrue( service.listProjects( agent ).isEmpty() );
        assertTrue( service.waitingOn( agent ).isEmpty() );
    }

    @Test
    void agentMemberAsLeadRejected() throws SQLException {
        register( "codex" );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "codex", "agent", WorkRole.LEAD ) );
        assertEquals( "role", e.field() );
    }

    @Test
    void agentMemberByNameStoresId() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        service.putMember( admin, "PLAT", "codex", "agent", WorkRole.MEMBER );
        final Member m = service.members( admin, "PLAT" ).stream().filter( x -> "agent".equals( x.principalKind() ) )
            .findFirst().orElseThrow();
        assertEquals( String.valueOf( r.agent().id() ), m.principal() );
        assertEquals( 1L, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event WHERE type = 'member' "
            + "AND to_value LIKE 'agent:" + r.agent().id() + ":%'" ) );
        assertEquals( "principal", assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "PLAT", "ghost", "agent", WorkRole.MEMBER ) ).field() );
        service.removeMember( admin, "PLAT", "codex", "agent" );
        assertTrue( service.members( admin, "PLAT" ).stream().noneMatch( x -> "agent".equals( x.principalKind() ) ) );
    }

    @Test
    void retiredAgentCannotBeAdmittedButItsMembershipCanBeRemoved() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        service.putMember( admin, "PLAT", "codex", "agent", WorkRole.MEMBER );
        service.retireAgent( admin, r.agent().id() );
        assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "OPS", "codex", "agent", WorkRole.MEMBER ) );
        service.removeMember( admin, "PLAT", "codex", "agent" );
    }
}
