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
    private final java.util.Set< Integer > failingRevokes = new java.util.HashSet<>();
    private final List< Integer > revokeAttempts = new java.util.ArrayList<>();
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
            public Minted remint( final int oldKeyId, final String label ) {
                return mint( null, label );
            }

            @Override
            public void revoke( final int keyId, final String revokedBy ) {
                revokeAttempts.add( keyId );
                if ( failingRevokes.contains( keyId ) ) {
                    throw new IllegalStateException( "key service down" );
                }
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
        service.registry().createQueue( admin, "implementer", "Implementer", "" );
        service.createItem( lena, draft( "PLAT" ) );
        service.createItem( lena, draft( "OPS" ) );
    }

    private static ItemDraft draft( final String project ) {
        return new ItemDraft( project, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", null, null, null,
            null );
    }

    private WorkService.AgentWithToken register( final String name ) throws SQLException {
        return service.registry().registerAgent( admin, new AgentDraft( name, "", "", List.of( "implementer" ), "lena" ) );
    }

    private Actor actorFor( final WorkService.AgentWithToken r ) throws SQLException {
        return service.registry().actorForApiKey( r.agent().apiKeyId(), "lena" );
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
        service.registry().retireAgent( admin, r.agent().id() );
        assertNull( actorFor( r ), "a retired agent's key is no actor, and never falls back to its owner" );
    }

    @Test
    void unboundKeyIsTheOwnerNeverAdmin() throws SQLException {
        final int unbound = ( int ) WorkAgentFixtures.apiKey( ds, "work" );
        final Actor owner = service.registry().actorForApiKey( unbound, "admin" );
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
    void admittedAgentNamedLikeAMemberGetsNoneOfTheirWork() throws SQLException {
        service.putMember( admin, "PLAT", "jake", "user", WorkRole.MEMBER );
        final Item item = service.createItem( lena, draft( "PLAT" ) ).item();
        service.updateItem( admin, item.key(), new ItemPatch( item.version(),
            java.util.Map.of( "assignee", "jake" ), null ) );
        final Item mentioned = service.createItem( lena, draft( "PLAT" ) ).item();
        service.addComment( admin, mentioned.key(), "over to @jake please" );
        final Actor person = Actor.human( "jake", Set.of(), false );
        assertEquals( 2, service.waitingOn( person ).size(), "the person has an assignment and a mention" );
        final Actor agent = actorFor( register( "jake" ) );
        service.putMember( admin, "PLAT", "jake", "agent", WorkRole.MEMBER );
        assertEquals( List.of( "PLAT" ), service.listProjects( agent ).stream().map( Project::key ).toList() );
        assertTrue( service.waitingOn( agent ).isEmpty(), "an agent never receives a person's lane by name" );
        assertTrue( service.claims().myClaims( agent ).isEmpty() );
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
        service.registry().retireAgent( admin, r.agent().id() );
        assertThrows( WorkValidationException.class,
            () -> service.putMember( admin, "OPS", "codex", "agent", WorkRole.MEMBER ) );
        service.removeMember( admin, "PLAT", "codex", "agent" );
    }

    @Test
    void rotatedOldKeyIsNoActorEvenIfRevokeFailed() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        final int oldKey = r.agent().apiKeyId();
        failingRevokes.add( oldKey );
        final WorkService.AgentWithToken rotated = service.registry().rotateAgentKey( admin, r.agent().id() );
        assertNull( service.registry().actorForApiKey( oldKey, "lena" ),
            "the old token is still live but must not become its owner" );
        final Actor now = service.registry().actorForApiKey( rotated.agent().apiKeyId(), "lena" );
        assertEquals( r.agent().id(), now.agentId() );
    }

    @Test
    void rotatedTwiceBothOldKeysAreNoActor() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        final int first = r.agent().apiKeyId();
        final int second = service.registry().rotateAgentKey( admin, r.agent().id() ).agent().apiKeyId();
        final int third = service.registry().rotateAgentKey( admin, r.agent().id() ).agent().apiKeyId();
        assertNull( service.registry().actorForApiKey( first, "lena" ) );
        assertNull( service.registry().actorForApiKey( second, "lena" ) );
        assertEquals( Actor.Kind.AGENT, service.registry().actorForApiKey( third, "lena" ).kind() );
    }

    @Test
    void repeatRetireRetriesTheRevoke() throws SQLException {
        final WorkService.AgentWithToken r = register( "codex" );
        final int key = r.agent().apiKeyId();
        failingRevokes.add( key );
        service.registry().retireAgent( admin, r.agent().id() );
        assertEquals( List.of( key ), revokeAttempts );
        failingRevokes.clear();
        service.registry().retireAgent( admin, r.agent().id() );
        assertEquals( List.of( key, key ), revokeAttempts, "retiring again completes a retirement whose revoke failed" );
        assertNull( service.registry().actorForApiKey( key, "lena" ) );
    }

    @Test
    void unboundNeverBoundKeyStillActsAsOwner() throws SQLException {
        final Actor owner = service.registry().actorForApiKey( ( int ) WorkAgentFixtures.apiKey( ds, "work" ), "lena" );
        assertEquals( Actor.Kind.HUMAN, owner.kind() );
        assertEquals( "lena", owner.login() );
    }
}
