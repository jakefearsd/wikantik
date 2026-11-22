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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @org.junit.jupiter.api.Test
    void aSystemActorHasNoStanding() throws SQLException {
        member( p1, "codehost:github", "user", WorkRole.LEAD );
        final Actor system = Actor.system( "github" );
        assertEquals( Optional.empty(), standing.roleIn( p1, system ) );
        assertEquals( List.of(), standing.visibleProjects( system ) );
        assertEquals( false, standing.seesProject( p1, system ) );
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

    @Test
    void seesProjectIsTrueForAViewer() throws SQLException {
        member( p1, "ana", "user", WorkRole.VIEWER );
        assertEquals( true, standing.seesProject( p1, ana() ) );
    }

    @Test
    void seesProjectIsFalseForAStranger() throws SQLException {
        assertEquals( false, standing.seesProject( p1, ana() ) );
    }

    @Test
    void seesProjectIsTrueForAWikiAdminWithNoRow() throws SQLException {
        assertEquals( true, standing.seesProject( p1, Actor.human( "root", Set.of(), true ) ) );
    }

    @Test
    void seesProjectIsFalseForAMemberAgentUnderAStrangerSponsor() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        assertEquals( false, standing.seesProject( p1, agent( ana() ) ) );
        member( p1, "ana", "user", WorkRole.VIEWER );
        assertEquals( true, standing.seesProject( p1, agent( ana() ) ) );
    }

    private static List< String > keys( final List< Project > projects ) {
        return projects.stream().map( Project::key ).sorted().toList();
    }

    // ---- reporters: no standing, three narrow questions

    private Item reportedBy( final long project, final Actor by ) throws SQLException {
        return store.createItem( store.projects().stream().filter( p -> p.id() == project ).findFirst().orElseThrow(),
            JdbcWorkStoreItemsPostgresTest.draft( "filed by " + by.login() ), by );
    }

    private static List< Long > ids( final List< Project > projects ) {
        return projects.stream().map( Project::id ).toList();
    }

    @Test
    void aReporterHasNoRoleAndSeesNoProject() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        assertEquals( Optional.empty(), role( ana() ) );
        assertFalse( standing.seesProject( p1, ana() ) );
        assertEquals( List.of(), standing.visibleProjects( ana() ) );
        assertEquals( List.of( p1 ), ids( standing.reportableProjects( ana() ) ) );
        assertTrue( standing.reportsOnly( p1, ana() ) );
    }

    @Test
    void aReporterWhoIsAlsoAMemberElsewhereSeesOnlyThatElsewhere() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        member( p2, "ana", "user", WorkRole.VIEWER );
        assertEquals( List.of( p2 ), ids( standing.visibleProjects( ana() ) ) );
        assertEquals( List.of( p1, p2 ), ids( standing.reportableProjects( ana() ) ) );
    }

    @Test
    void aReporterCanSeeOnlyTheirOwnItem() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        member( p1, "bob", "user", WorkRole.REPORTER );
        final Item mine = reportedBy( p1, ana() );
        final Item bobs = reportedBy( p1, Actor.human( "bob", Set.of(), false ) );
        assertTrue( standing.canSee( ana(), mine ) );
        assertFalse( standing.canSee( ana(), bobs ) );
        final Item byAgent = store.createItem( store.projects().get( 0 ), JdbcWorkStoreItemsPostgresTest.draft( "agent" ),
            Actor.agent( agentId, "ana" ) );
        assertEquals( "ana", byAgent.reporter() );
        assertFalse( standing.canSee( ana(), byAgent ), "an agent named ana reported it, not the person" );
    }

    @Test
    void aRemovedReporterSeesNothing() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        final Item mine = reportedBy( p1, ana() );
        assertTrue( standing.mayFile( p1, ana() ) );
        store.removeMember( p1, "ana", "user", ROOT );
        assertFalse( standing.canSee( ana(), mine ) );
        assertFalse( standing.mayFile( p1, ana() ) );
        assertEquals( List.of(), standing.reportableProjects( ana() ) );
    }

    @Test
    void aGroupMayHoldTheReporterRole() throws SQLException {
        member( p1, "Customers", "group", WorkRole.REPORTER );
        final Actor ana = ana( "Customers" );
        final Item mine = reportedBy( p1, ana );
        assertTrue( standing.mayFile( p1, ana ) );
        assertTrue( standing.canSee( ana, mine ) );
        assertEquals( Optional.empty(), role( ana ) );
        assertTrue( standing.reportsOnly( p1, ana ) );
        assertFalse( standing.mayFile( p1, ana() ), "a person outside the group may not file" );
    }

    @Test
    void aViewerRowBeatsAReporterRow() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        member( p1, "Customers", "group", WorkRole.VIEWER );
        final Actor ana = ana( "Customers" );
        assertEquals( Optional.of( WorkRole.VIEWER ), role( ana ) );
        assertEquals( List.of( p1 ), ids( standing.visibleProjects( ana ) ) );
        assertFalse( standing.reportsOnly( p1, ana ) );
    }

    /** M3: the other direction: a member by her own row and a reporter through a group is a member everywhere. */
    @Test
    void aDirectMemberRowBeatsAGroupReporterRow() throws SQLException {
        member( p1, "ana", "user", WorkRole.MEMBER );
        member( p1, "Customers", "group", WorkRole.REPORTER );
        final Actor ana = ana( "Customers" );
        assertEquals( Optional.of( WorkRole.MEMBER ), role( ana ) );
        assertTrue( standing.seesProject( p1, ana ) );
        assertEquals( List.of( p1 ), ids( standing.visibleProjects( ana ) ) );
        assertFalse( standing.reportsOnly( p1, ana ) );
        assertFalse( standing.followsAsReporter( ana, reportedBy( p1, ana ) ) );
        assertEquals( Set.of(), standing.reportOnlyProjects( ana, Set.of( p1 ) ) );
        assertEquals( Set.of(), standing.outsideReporters( p1, List.of( "ana" ),
            login -> Optional.of( new WorkDirectory.Person( "ana", Set.of( "Customers" ), false ) ) ) );
    }

    /** The per-item reporter question: only her own report, only while the reporter row is all she holds. */
    @Test
    void followsAsReporterIsPerItemAndPersonOnly() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        final Item hers = reportedBy( p1, ana() );
        final Item other = reportedBy( p1, Actor.human( "bob", Set.of(), false ) );
        assertTrue( standing.followsAsReporter( ana(), hers ) );
        assertFalse( standing.followsAsReporter( ana(), other ) );
        assertEquals( Set.of( p1 ), standing.reportOnlyProjects( ana(), Set.of() ) );
        assertFalse( standing.followsAsReporter( agent( ana() ), hers ), "an agent is never a reporter" );
        member( p1, "ana", "user", WorkRole.VIEWER );
        assertFalse( standing.followsAsReporter( ana(), hers ), "a viewer follows it as a member does" );
    }

    @Test
    void anAdminIsNeverReporterOnly() throws SQLException {
        member( p1, "root", "user", WorkRole.REPORTER );
        assertFalse( standing.reportsOnly( p1, ROOT ) );
        assertTrue( standing.mayFile( p1, ROOT ) );
        assertEquals( 3, standing.reportableProjects( ROOT ).size() );
    }

    @Test
    void anAgentSponsoredByAReporterHasNothing() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        member( p1, "ana", "user", WorkRole.REPORTER );
        final Item byAna = reportedBy( p1, ana() );
        final Actor agent = agent( ana() );
        assertEquals( Optional.empty(), role( agent ) );
        assertEquals( List.of(), standing.visibleProjects( agent ) );
        assertFalse( standing.canSee( agent, byAna ) );
        assertFalse( standing.mayFile( p1, agent ) );
        assertEquals( List.of(), standing.reportableProjects( agent ) );
    }

    @Test
    void aReporterSponsorDoesNotWidenAnAgentsCeilingEither() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        member( p1, "ana", "user", WorkRole.REPORTER );
        member( p1, "Crew", "group", WorkRole.VIEWER );
        assertEquals( Optional.of( WorkRole.VIEWER ), role( agent( ana( "Crew" ) ) ), "the sponsor's viewer row is the ceiling" );
    }

    @Test
    void anAgentNamedLikeTheReporterCannotSeeTheirItem() throws SQLException {
        member( p1, "ana", "user", WorkRole.REPORTER );
        final Item byAna = reportedBy( p1, ana() );
        final long rita = store.registerAgent( "ana", "", "", List.of(), "ana" ).id();
        member( p1, String.valueOf( rita ), "agent", WorkRole.MEMBER );
        final Actor agent = Actor.agent( rita, "ana", ana() );
        assertFalse( standing.canSee( agent, byAna ) );
        assertFalse( standing.mayFile( p1, agent ) );
        assertFalse( standing.reportsOnly( p1, agent ) );
    }

    @Test
    void anAgentNeverFiles() throws SQLException {
        agentIs( p1, WorkRole.MEMBER );
        assertFalse( standing.mayFile( p1, agent( ROOT ) ) );
        assertFalse( standing.mayFile( p1, Actor.system( "github" ) ) );
        assertEquals( List.of(), standing.reportableProjects( agent( ROOT ) ) );
    }

    @Test
    void outsideReportersNamesOnlyThoseHoldingOnlyTheReporterRole() throws SQLException {
        final java.util.function.Function< String, Optional< WorkDirectory.Person > > people =
            login -> "ghost".equals( login ) ? Optional.empty() : Optional.of( new WorkDirectory.Person( login, Set.of(), false ) );
        assertEquals( Set.of(), standing.outsideReporters( p1, List.of( "rita" ), people ), "no reporter rows at all" );
        member( p1, "rita", "user", WorkRole.REPORTER );
        member( p1, "lena", "user", WorkRole.LEAD );
        assertEquals( Set.of( "rita" ), standing.outsideReporters( p1, List.of( "rita", "lena", "ghost", "rita" ), people ) );
        member( p1, "rita", "user", WorkRole.VIEWER );
        assertEquals( Set.of(), standing.outsideReporters( p1, List.of( "rita" ), people ) );
        store.removeMember( p1, "rita", "user", ROOT );
        assertEquals( Set.of(), standing.outsideReporters( p1, List.of( "rita" ), people ) );
    }

    @Test
    void outsideReportersOnlyResolvesTheGivenLogins() throws SQLException {
        member( p1, "rita", "user", WorkRole.REPORTER );
        member( p1, "gus", "user", WorkRole.REPORTER );
        final java.util.List< String > asked = new java.util.ArrayList<>();
        standing.outsideReporters( p1, List.of( "rita" ), login -> {
            asked.add( login );
            return Optional.of( new WorkDirectory.Person( login, Set.of(), false ) );
        } );
        assertEquals( List.of( "rita" ), asked );
        asked.clear();
        standing.outsideReporters( p2, List.of( "rita" ), login -> {
            asked.add( login );
            return Optional.empty();
        } );
        assertEquals( List.of(), asked, "a project with no reporter row resolves nobody" );
    }
}
