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

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projects, membership, visibility and products of {@link JdbcWorkStore}, against real PostgreSQL. */
@RequiresPostgres
class JdbcWorkStoreProjectsPostgresTest {

    private JdbcWorkStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
    }

    private static Actor human( final String login, final String... groups ) {
        return Actor.human( login, Set.of( groups ), false );
    }

    @Test
    void nonMemberSeesNoProjects() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        assertTrue( store.visibleProjects( human( "alice" ) ).isEmpty() );
        assertEquals( Optional.empty(), store.roleFor( p.id(), human( "alice" ) ) );
    }

    @Test
    void userRowGrantsRole() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "alice", "user", WorkRole.MEMBER, "admin", Actor.Kind.HUMAN );
        assertEquals( Optional.of( WorkRole.MEMBER ), store.roleFor( p.id(), human( "alice" ) ) );
        assertEquals( List.of( p.id() ), store.visibleProjects( human( "alice" ) ).stream().map( Project::id ).toList() );
        assertTrue( store.visibleProjects( human( "bob" ) ).isEmpty() );
    }

    @Test
    void groupRowGrantsRole() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "editors", "group", WorkRole.VIEWER, "admin", Actor.Kind.HUMAN );
        assertEquals( Optional.of( WorkRole.VIEWER ), store.roleFor( p.id(), human( "bob", "editors" ) ) );
        assertEquals( 1, store.visibleProjects( human( "bob", "editors" ) ).size() );
        assertEquals( Optional.empty(), store.roleFor( p.id(), human( "bob", "others" ) ) );
    }

    @Test
    void agentRowMatchesAgentIdAsText() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "42", "agent", WorkRole.MEMBER, "admin", Actor.Kind.HUMAN );
        final Actor agent = new Actor( "bot", Set.of(), false, Actor.Kind.AGENT, 42L );
        assertEquals( Optional.of( WorkRole.MEMBER ), store.roleFor( p.id(), agent ) );
        assertEquals( 1, store.visibleProjects( agent ).size() );
        final Actor other = new Actor( "bot", Set.of(), false, Actor.Kind.AGENT, 43L );
        assertEquals( Optional.empty(), store.roleFor( p.id(), other ) );
        // a human whose login happens to be "42" must not match the agent row
        assertEquals( Optional.empty(), store.roleFor( p.id(), human( "42" ) ) );
    }

    @Test
    void agentNeverMatchesUserRowOfSameLogin() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "alice", "user", WorkRole.LEAD, "admin", Actor.Kind.HUMAN );
        final Actor agent = new Actor( "alice", Set.of(), false, Actor.Kind.AGENT, 7L );
        assertEquals( Optional.empty(), store.roleFor( p.id(), agent ) );
        assertTrue( store.visibleProjects( agent ).isEmpty() );
    }

    @Test
    void agentNeverInheritsGroupRows() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "editors", "group", WorkRole.LEAD, "admin", Actor.Kind.HUMAN );
        final Actor agent = new Actor( "bot", Set.of( "editors" ), false, Actor.Kind.AGENT, 7L );
        assertEquals( Optional.empty(), store.roleFor( p.id(), agent ) );
        assertTrue( store.visibleProjects( agent ).isEmpty() );
    }

    @Test
    void highestRoleWins() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "alice", "user", WorkRole.VIEWER, "admin", Actor.Kind.HUMAN );
        store.putMember( p.id(), "editors", "group", WorkRole.LEAD, "admin", Actor.Kind.HUMAN );
        assertEquals( Optional.of( WorkRole.LEAD ), store.roleFor( p.id(), human( "alice", "editors" ) ) );
    }

    @Test
    void putMemberUpdatesExistingRole() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "alice", "user", WorkRole.VIEWER, "admin", Actor.Kind.HUMAN );
        store.putMember( p.id(), "alice", "user", WorkRole.LEAD, "admin", Actor.Kind.HUMAN );
        assertEquals( List.of( new Member( p.id(), "alice", "user", WorkRole.LEAD ) ), store.members( p.id() ) );
    }

    @Test
    void adminSeesAllWithoutRows() throws SQLException {
        store.createProject( "WIKI", "Wiki", "" );
        store.createProject( "OPS", "Ops", "" );
        final Actor admin = Actor.human( "root", Set.of(), true );
        assertEquals( 2, store.visibleProjects( admin ).size() );
        assertEquals( Optional.empty(), store.roleFor( store.project( "WIKI" ).orElseThrow().id(), admin ) );
    }

    @Test
    void removingMemberRemovesVisibility() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "alice", "user", WorkRole.MEMBER, "admin", Actor.Kind.HUMAN );
        store.removeMember( p.id(), "alice", "user", "admin", Actor.Kind.HUMAN );
        assertTrue( store.visibleProjects( human( "alice" ) ).isEmpty() );
        assertTrue( store.members( p.id() ).isEmpty() );
    }

    @Test
    void duplicateProjectKeyRejected() throws SQLException {
        store.createProject( "WIKI", "Wiki", "" );
        final SQLException e = assertThrows( SQLException.class, () -> store.createProject( "WIKI", "Again", "" ) );
        assertEquals( "23505", e.getSQLState() );
    }

    @Test
    void lowercaseProjectKeyRejected() {
        final SQLException e = assertThrows( SQLException.class, () -> store.createProject( "wiki", "Wiki", "" ) );
        assertEquals( "23514", e.getSQLState() );
    }

    @Test
    void archivedProjectStillVisibleToMembers() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( p.id(), "alice", "user", WorkRole.MEMBER, "admin", Actor.Kind.HUMAN );
        final Instant at = Instant.parse( "2026-11-15T10:00:00Z" );
        store.archiveProject( p.id(), at, "admin", Actor.Kind.HUMAN );
        final List< Project > visible = store.visibleProjects( human( "alice" ) );
        assertEquals( 1, visible.size() );
        assertEquals( at, visible.get( 0 ).archivedAt() );
    }

    @Test
    void projectRoundTripAndUpdate() throws SQLException {
        final Project p = store.createProject( "WIKI", "Wiki", "desc" );
        assertTrue( p.id() > 0 );
        assertEquals( "Wiki", p.name() );
        assertEquals( "desc", p.description() );
        assertEquals( Autonomy.DELEGATED, p.defaultAutonomy() );
        assertNotNull( p.createdAt() );
        assertNull( p.archivedAt() );
        store.updateProject( p.id(), "Wiki 2", "d2", Autonomy.SUPERVISED, "admin", Actor.Kind.HUMAN );
        final Project u = store.project( "WIKI" ).orElseThrow();
        assertEquals( "Wiki 2", u.name() );
        assertEquals( "d2", u.description() );
        assertEquals( Autonomy.SUPERVISED, u.defaultAutonomy() );
        assertEquals( Optional.empty(), store.project( "NOPE" ) );
        assertEquals( 1, store.projects().size() );
    }

    @Test
    void productRoundTrip() throws SQLException {
        final Product draft = new Product( 0, "wiki", "Wikantik", "https://example.com/r.git", "main", "wikantik",
            Autonomy.SUPERVISED, DeployGate.AGENT, null );
        final Product made = store.createProduct( draft );
        assertTrue( made.id() > 0 );
        assertEquals( made, store.product( "wiki" ).orElseThrow() );
        assertEquals( DeployGate.AGENT, made.deployGate() );

        final Product changed = new Product( made.id(), "wiki", "Wikantik 2", null, null, null,
            Autonomy.AUTONOMOUS, DeployGate.HUMAN, null );
        store.updateProduct( changed );
        assertEquals( changed, store.product( "wiki" ).orElseThrow() );

        final Instant at = Instant.parse( "2026-11-15T10:00:00Z" );
        store.archiveProduct( made.id(), at );
        assertEquals( at, store.product( "wiki" ).orElseThrow().archivedAt() );
        assertEquals( 1, store.products().size() );
        assertEquals( Optional.empty(), store.product( "none" ) );
    }
}
