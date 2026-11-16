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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The sponsor ceiling: an agent's role is the lower of its own and its sponsor's; it sees only what both can. */
@RequiresPostgres
class WorkStandingPostgresTest {

    private static final Actor ROOT = Actor.human( "root", Set.of(), true );

    private JdbcWorkStore store;
    private WorkStanding standing;
    private long p1;
    private long p2;
    private long p3;
    private long agentId;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        standing = new WorkStanding( store );
        p1 = store.createProject( "PA", "A", "" ).id();
        p2 = store.createProject( "PB", "B", "" ).id();
        p3 = store.createProject( "PC", "C", "" ).id();
        agentId = store.registerAgent( "codex", "", "", List.of(), "ana" ).id();
    }

    private void member( final long project, final String principal, final String kind, final WorkRole role )
            throws SQLException {
        store.putMember( project, principal, kind, role, ROOT );
    }

    private void agentIs( final long project, final WorkRole role ) throws SQLException {
        member( project, String.valueOf( agentId ), "agent", role );
    }

    private Actor agent( final Actor sponsor ) {
        return Actor.agent( agentId, "codex", sponsor );
    }

    private static Actor ana( final String... groups ) {
        return Actor.human( "ana", Set.of( groups ), false );
    }

    private Optional< WorkRole > role( final Actor actor ) throws SQLException {
        return standing.roleIn( p1, actor );
    }

    @Test
    void anAgentKeepsItsRoleWhileItsSponsorIsAtLeastAMember() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        member( p1, "ana", "user", WorkRole.LEAD );
        assertEquals( Optional.of( WorkRole.MEMBER ), role( agent( ana() ) ) );
        member( p1, "ana", "user", WorkRole.MEMBER );
        assertEquals( Optional.of( WorkRole.MEMBER ), role( agent( ana() ) ) );
    }

    @Test
    void aViewerSponsorMakesTheAgentAViewer() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        member( p1, "ana", "user", WorkRole.VIEWER );
        assertEquals( Optional.of( WorkRole.VIEWER ), role( agent( ana() ) ) );
    }

    @Test
    void aSponsorWhoIsNotAMemberLeavesTheAgentWithNoRole() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        assertEquals( Optional.empty(), role( agent( ana() ) ) );
    }

    @Test
    void anAdminSponsorNeedsNoMembership() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        assertEquals( Optional.of( WorkRole.MEMBER ), role( agent( Actor.human( "root", Set.of(), true ) ) ) );
    }

    @Test
    void theAgentsOwnRoleStillCapsIt() throws SQLException {
        agentIs( p1, WorkRole.VIEWER );
        member( p1, "ana", "user", WorkRole.LEAD );
        assertEquals( Optional.of( WorkRole.VIEWER ), role( agent( ana() ) ) );
    }

    @Test
    void anAgentWithNoMemberRowHasNoRoleWhateverItsSponsorIs() throws SQLException {
        member( p1, "ana", "user", WorkRole.LEAD );
        assertEquals( Optional.empty(), role( agent( ana() ) ), "the sponsor is a ceiling, never a source" );
        assertEquals( Optional.empty(), role( agent( Actor.human( "root", Set.of(), true ) ) ) );
    }

    @Test
    void aSponsorStandingOnlyOnAGroupCountsUntilTheGroupRowGoes() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        member( p1, "Crew", "group", WorkRole.MEMBER );
        assertEquals( Optional.of( WorkRole.MEMBER ), role( agent( ana( "Crew" ) ) ) );
        assertEquals( Optional.empty(), role( agent( ana() ) ), "the person left the group" );
        store.removeMember( p1, "Crew", "group", ROOT );
        assertEquals( Optional.empty(), role( agent( ana( "Crew" ) ) ), "the group lost its row" );
    }

    @Test
    void anAgentWithNoSponsorHasNoRole() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        assertEquals( Optional.empty(), role( Actor.agent( agentId, "codex" ) ) );
    }

    @Test
    void aPersonsRoleIsTheirOwn() throws SQLException {
        member( p1, "ana", "user", WorkRole.LEAD );
        assertEquals( Optional.of( WorkRole.LEAD ), role( ana() ) );
        assertEquals( Optional.empty(), standing.roleIn( p2, ana() ) );
    }

    @Test
    void visibleProjectsIsTheIntersection() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        agentIs( p2, WorkRole.MEMBER );
        member( p2, "ana", "user", WorkRole.VIEWER );
        member( p3, "ana", "user", WorkRole.LEAD );
        assertEquals( List.of( "PB" ), keys( standing.visibleProjects( agent( ana() ) ) ) );
        assertEquals( List.of( "PA", "PB" ), keys( standing.visibleProjects( agent( Actor.human( "root", Set.of(), true ) ) ) ),
            "an admin sponsor limits nothing, and adds nothing" );
        assertEquals( List.of(), keys( standing.visibleProjects( Actor.agent( agentId, "codex" ) ) ) );
        assertEquals( List.of( "PB", "PC" ), keys( standing.visibleProjects( ana() ) ) );
    }

    private static List< String > keys( final List< Project > projects ) {
        return projects.stream().map( Project::key ).sorted().toList();
    }
}
