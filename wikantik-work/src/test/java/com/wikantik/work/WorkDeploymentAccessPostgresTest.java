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

import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deployments through the service, over real PostgreSQL and real standing. A deployment carries items of two
 * projects: PLAT (lead lena, member mia) and OPS (lead olga, member mia). Lena knows nothing of OPS.
 */
@RequiresPostgres
class WorkDeploymentAccessPostgresTest {

    private static final String PERSON = "A person approves deployments of this product.";
    private static final String LEAD =
        "Approving this deployment takes a lead of every project it carries, or a wiki administrator.";

    private final Actor olga = Actor.human( "olga", Set.of(), false );
    private final Actor sam = Actor.human( "sam", Set.of(), false );
    private GateFixture f;
    private WorkDeploymentsService deployments;
    private Item plat;
    private Item ops;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        deployments = f.svc.deployments();
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        f.svc.putMember( f.admin, "OPS", "olga", "user", WorkRole.LEAD );
        f.svc.putMember( f.admin, "OPS", "mia", "user", WorkRole.MEMBER );
        f.svc.putMember( f.admin, "OPS", "author", "agent", WorkRole.MEMBER );
        plat = f.status( f.lena, f.task(), "ready" );
        ops = f.status( olga, f.svc.createItem( olga, new ItemDraft( "OPS", WorkLevel.TASK, null, "wikantik",
            WorkKind.SOFTWARE, "An ops item", "", null, null, null, null ) ).item(), "ready" );
    }

    private DeploymentView requestedBy( final Actor who ) throws SQLException {
        return deployments.request( who, new DeploymentDraft( "wikantik", null, "2.5.26",
            List.of( plat.key(), ops.key() ) ) );
    }

    private void gate( final DeployGate gate ) throws SQLException {
        final Product p = f.svc.listProducts( f.admin ).get( 0 );
        f.svc.updateProduct( f.admin, new Product( p.id(), p.key(), p.name(), p.repoUrl(), p.defaultBranch(),
            p.wikiCluster(), p.autonomyCeiling(), gate, p.archivedAt() ) );
    }

    private String denied( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkDeniedException.class, call ).reason();
    }

    private WorkValidationException invalid( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkValidationException.class, call );
    }

    // ---- who sees what

    @Test
    void aRequestCarriesItsItemsAndStartsRequestedInProduction() throws SQLException {
        final DeploymentView v = requestedBy( f.mia );
        assertEquals( DeploymentState.REQUESTED, v.deployment().state() );
        assertEquals( "production", v.deployment().environment() );
        assertEquals( "wikantik", v.productKey() );
        assertEquals( List.of( plat.key(), ops.key() ), v.visibleItems().stream().map( Item::key ).toList() );
        assertFalse( v.mayApprove(), "a member requests; a lead of every project approves" );
        assertTrue( v.mayRun() );
    }

    @Test
    void aMemberOfOneCarriedProjectSeesOnlyItsItems() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        final DeploymentView v = deployments.deployment( f.lena, id );
        assertEquals( List.of( plat.key() ), v.visibleItems().stream().map( Item::key ).toList() );
        assertEquals( List.of( plat.id() ), v.deployment().itemIds(), "nor the ids of the rest, nor how many" );
        assertFalse( v.toString().contains( "OPS" ), "no field reveals the other project: " + v );
        assertFalse( v.mayApprove() );
        assertFalse( v.mayRun() );
        assertEquals( 1, deployments.history( f.lena, id ).size() );
        assertEquals( List.of( id ), deployments.forProduct( f.lena, "wikantik", 10 ).stream()
            .map( d -> d.deployment().id() ).toList() );
    }

    @Test
    void aNonMemberGetsNotFound() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        final Agent orphanRow = f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "orphan", "", "", List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "orphan", "agent", WorkRole.MEMBER );
        final Actor underAStranger = Actor.agent( orphanRow.id(), "orphan", sam );
        for ( final Actor who : List.of( sam, underAStranger ) ) {
            for ( final long which : new long[] { id, 999_999L } ) {
                assertThrows( WorkNotFoundException.class, () -> deployments.deployment( who, which ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.history( who, which ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.approve( who, which, null ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.start( who, which ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.verify( who, which, "ok" ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.fail( who, which, null ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.rollBack( who, which, null ) );
                assertThrows( WorkNotFoundException.class, () -> deployments.cleared( who, which, "2.5.26" ) );
            }
            assertThrows( WorkNotFoundException.class, () -> deployments.forProduct( who, "wikantik", 10 ) );
            assertTrue( deployments.awaitingMyApproval( who ).isEmpty() );
        }
    }

    // ---- requesting

    @Test
    void aRequestNeedsMembershipOfEveryCarriedProject() throws SQLException {
        assertThrows( WorkNotFoundException.class, () -> requestedBy( f.lena ), "lena cannot see the OPS item" );
        f.svc.putMember( f.admin, "OPS", "lena", "user", WorkRole.VIEWER );
        assertThrows( WorkDeniedException.class, () -> requestedBy( f.lena ), "a viewer of OPS may not carry its item" );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_deployment" ) );
    }

    @Test
    void whatADeploymentCarriesIsChecked() throws SQLException {
        f.svc.createProduct( f.admin, new Product( 0, "jakemon", "Jakemon", null, "main", null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        final WorkValidationException wrong = invalid( () -> deployments.request( f.mia,
            new DeploymentDraft( "jakemon", null, "1.0.8", List.of( plat.key() ) ) ) );
        assertEquals( "items", wrong.field() );
        assertEquals( plat.key() + " does not target this product.", wrong.getMessage() );
        final WorkValidationException empty = invalid( () -> deployments.request( f.mia,
            new DeploymentDraft( "wikantik", null, "2.5.26", List.of() ) ) );
        assertEquals( "A deployment carries at least one item.", empty.getMessage() );
        assertEquals( "version", invalid( () -> deployments.request( f.mia,
            new DeploymentDraft( "wikantik", null, "2.5 26", List.of( plat.key() ) ) ) ).field() );
        assertEquals( "environment", invalid( () -> deployments.request( f.mia,
            new DeploymentDraft( "wikantik", "Prod East", "2.5.26", List.of( plat.key() ) ) ) ).field() );
        assertThrows( WorkNotFoundException.class, () -> deployments.request( f.mia,
            new DeploymentDraft( "nope", null, "2.5.26", List.of( plat.key() ) ) ) );
        final DeploymentView twice = deployments.request( f.mia,
            new DeploymentDraft( "wikantik", "staging", "2.5.26", List.of( plat.key(), plat.key() ) ) );
        assertEquals( 1, twice.visibleItems().size(), "a key named twice is carried once" );
    }

    @Test
    void onlyWorkThatCanShipIsCarried() throws SQLException {
        final Item draft = f.task();
        final Item refining = f.status( f.lena, f.task(), "refining" );
        final Item cancelled = f.status( f.lena, f.task(), "cancelled" );
        final Item epic = f.draft( WorkLevel.EPIC, null );
        for ( final Item bad : List.of( draft, refining, cancelled ) ) {
            final WorkValidationException e = invalid( () -> deployments.request( f.mia,
                new DeploymentDraft( "wikantik", null, "2.5.26", List.of( bad.key() ) ) ) );
            assertEquals( "items", e.field() );
            assertEquals( bad.key() + " is " + bad.status().wire() + "; a deployment carries work that is ready or later.",
                e.getMessage() );
        }
        assertEquals( "items", invalid( () -> deployments.request( f.mia,
            new DeploymentDraft( "wikantik", null, "2.5.26", List.of( epic.key() ) ) ) ).field(), "an epic ships nothing itself" );
        final Item done = f.status( f.lena, f.task(), "done" );
        assertEquals( 1, deployments.request( f.mia, new DeploymentDraft( "wikantik", null, "2.5.26",
            List.of( done.key() ) ) ).visibleItems().size(), "finished work ships" );
    }

    // ---- withdrawing and rejecting

    @Test
    void theRequesterWithdrawsAndAnApproverRejectsAndNeitherIsEverCleared() throws SQLException {
        final long mine = requestedBy( f.mia ).deployment().id();
        assertEquals( "Rejecting this deployment takes whoever requested it, or someone who may approve it.",
            denied( () -> deployments.reject( f.lena, mine, "not yours" ) ), "a lead of one project only" );
        final DeploymentView withdrawn = deployments.reject( f.mia, mine, "wrong version" );
        assertEquals( DeploymentState.REJECTED, withdrawn.deployment().state() );
        assertFalse( deployments.cleared( f.mia, mine, "2.5.26" ) );
        assertTrue( deployments.awaitingMyApproval( f.admin ).isEmpty() );
        assertEquals( "bad_transition", assertThrows( WorkConflictException.class,
            () -> deployments.approve( f.admin, mine, null ) ).reason(), "a rejected deployment stays rejected" );
        assertEquals( "wrong version", deployments.history( f.mia, mine ).get( 1 ).note() );

        final long theirs = requestedBy( f.author ).deployment().id();
        assertEquals( DeploymentState.REJECTED, deployments.reject( f.admin, theirs, "not this week" ).deployment().state() );

        final long approved = requestedBy( f.mia ).deployment().id();
        deployments.approve( f.admin, approved, null );
        assertTrue( deployments.cleared( f.mia, approved, "2.5.26" ) );
        deployments.reject( f.admin, approved, "superseded" );
        assertFalse( deployments.cleared( f.mia, approved, "2.5.26" ), "an approval that will not be used is taken back" );
        deployments.approve( f.admin, requestedBy( f.mia ).deployment().id(), null );
    }

    @Test
    void aRunningDeploymentCannotBeRejected() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        deployments.approve( f.admin, id, null );
        deployments.start( f.mia, id );
        assertEquals( "bad_transition", assertThrows( WorkConflictException.class,
            () -> deployments.reject( f.admin, id, "too late" ) ).reason() );
    }

    // ---- the gate

    @Test
    void anAgentCannotApproveUnderAHumanGateEvenInAnAutonomousEpic() throws SQLException {
        final Item epic = f.draft( WorkLevel.EPIC, null );
        f.level( epic, Autonomy.AUTONOMOUS );
        final Item story = f.status( f.lena, f.draft( WorkLevel.STORY, epic.key() ), "ready" );
        final long id = deployments.request( f.author,
            new DeploymentDraft( "wikantik", null, "2.5.26", List.of( story.key() ) ) ).deployment().id();
        assertEquals( PERSON, denied( () -> deployments.approve( f.author, id, null ) ) );
        assertEquals( DeploymentState.REQUESTED, deployments.deployment( f.lena, id ).deployment().state() );
    }

    @Test
    void approvalTakesALeadOfEveryProjectOrAnAdmin() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        assertEquals( LEAD, denied( () -> deployments.approve( f.mia, id, null ) ) );
        assertEquals( LEAD, denied( () -> deployments.approve( f.lena, id, null ) ), "a lead of PLAT only" );
        assertEquals( LEAD, denied( () -> deployments.approve( olga, id, null ) ), "a lead of OPS only" );
        f.svc.putMember( f.admin, "OPS", "lena", "user", WorkRole.LEAD );
        final DeploymentView approved = deployments.approve( f.lena, id, "go" );
        assertEquals( DeploymentState.APPROVED, approved.deployment().state() );
        assertEquals( "lena", approved.deployment().approvedBy() );
        final long second = requestedBy( f.mia ).deployment().id();
        assertEquals( DeploymentState.APPROVED, deployments.approve( f.admin, second, null ).deployment().state() );
    }

    @Test
    void underAnAgentGateTheRequestingAgentApproves() throws SQLException {
        gate( DeployGate.AGENT );
        final long id = requestedBy( f.author ).deployment().id();
        assertEquals( LEAD, denied( () -> deployments.approve( f.reviewer, id, null ) ),
            "an agent that is not a member of every carried project" );
        assertEquals( f.author.agentId(),
            deployments.approve( f.author, id, null ).deployment().approvedByAgentId() );
    }

    @Test
    void awaitingMyApprovalListsWhatTheCallerMayApprove() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        assertEquals( List.of( id ), deployments.awaitingMyApproval( f.admin ).stream()
            .map( d -> d.deployment().id() ).toList() );
        assertTrue( deployments.awaitingMyApproval( f.mia ).isEmpty() );
        assertTrue( deployments.awaitingMyApproval( f.lena ).isEmpty() );
        deployments.approve( f.admin, id, null );
        assertTrue( deployments.awaitingMyApproval( f.admin ).isEmpty() );
    }

    // ---- running it

    @Test
    void runningTakesMembershipOfEveryCarriedProjectAndEvidenceToVerify() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        deployments.approve( f.admin, id, null );
        assertEquals( "Running this deployment takes membership of every project it carries.",
            denied( () -> deployments.start( f.lena, id ) ) );
        assertEquals( DeploymentState.RUNNING, deployments.start( f.author, id ).deployment().state() );
        final WorkValidationException none = invalid( () -> deployments.verify( f.author, id, "  " ) );
        assertEquals( "evidence", none.field() );
        assertEquals( "Attach the health and smoke-test output before marking this verified.", none.getMessage() );
        assertEquals( "evidence", invalid( () -> deployments.verify( f.author, id, "x".repeat( 20_001 ) ) ).field() );
        final DeploymentView verified = deployments.verify( f.author, id, "health 200\nsmoke ok" );
        assertEquals( "health 200\nsmoke ok", verified.deployment().evidence() );
        assertEquals( "bad_transition", assertThrows( WorkConflictException.class,
            () -> deployments.fail( f.author, id, "too late" ) ).reason() );
        assertEquals( DeploymentState.ROLLED_BACK, deployments.rollBack( f.mia, id, "regression" ).deployment().state() );
        assertEquals( List.of( "requested", "approved", "running", "verified", "rolled_back" ),
            deployments.history( f.mia, id ).stream().map( e -> e.to().wire() ).toList() );
    }

    @Test
    void clearedIsTrueOnlyForApprovedOrRunningAndTheSameVersion() throws SQLException {
        final long id = requestedBy( f.mia ).deployment().id();
        assertFalse( deployments.cleared( f.mia, id, "2.5.26" ), "requested" );
        deployments.approve( f.admin, id, null );
        assertTrue( deployments.cleared( f.mia, id, "2.5.26" ) );
        assertTrue( deployments.cleared( f.lena, id, "2.5.26" ), "anyone who can see it may ask" );
        assertFalse( deployments.cleared( f.mia, id, "2.5.27" ), "another version" );
        deployments.start( f.mia, id );
        assertTrue( deployments.cleared( f.mia, id, "2.5.26" ), "running" );
        deployments.fail( f.mia, id, "red" );
        assertFalse( deployments.cleared( f.mia, id, "2.5.26" ), "failed" );
    }
}
