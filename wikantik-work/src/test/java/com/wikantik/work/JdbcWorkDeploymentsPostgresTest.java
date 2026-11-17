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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deployment store against real PostgreSQL: requests, moves, history, and what "shipped" means. */
@RequiresPostgres
class JdbcWorkDeploymentsPostgresTest {

    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private DataSource ds;
    private JdbcWorkStore store;
    private WorkDeploymentStore deployments;
    private Product product;
    private Item one;
    private Item two;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        deployments = store.deployments();
        product = store.createProduct( new Product( 0, "wikantik", "Wikantik", null, "main", null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        one = store.createItem( store.createProject( "PLAT", "Platform", "" ), JdbcWorkStoreItemsPostgresTest.draft( "one" ), alice );
        two = store.createItem( store.createProject( "OPS", "Operations", "" ), JdbcWorkStoreItemsPostgresTest.draft( "two" ), alice );
        // the items target the product the deployments are of: only then does a deployment ship them
        try ( java.sql.Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement() ) {
            st.executeUpdate( "UPDATE work_item SET product_id = " + product.id() );
        }
    }

    private long count( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private Deployment requested() throws SQLException {
        return deployments.request( product, "production", "2.5.26", List.of( one, two ), alice );
    }

    private Deployment verified( final Deployment d ) throws SQLException {
        deployments.move( d.id(), DeploymentState.APPROVED, null, null, lena );
        deployments.move( d.id(), DeploymentState.RUNNING, null, null, alice );
        return deployments.move( d.id(), DeploymentState.VERIFIED, "health 200; smoke ok", null, alice );
    }

    @Test
    void requestWritesTheDeploymentItsItemsOneHistoryRowAndAnEventPerItem() throws SQLException {
        final Deployment d = requested();
        assertEquals( DeploymentState.REQUESTED, d.state() );
        assertEquals( "2.5.26", d.version() );
        assertEquals( "alice", d.requestedBy() );
        assertEquals( Set.of( one.id(), two.id() ), Set.copyOf( d.itemIds() ) );
        final List< DeploymentEvent > history = deployments.history( d.id() );
        assertEquals( 1, history.size() );
        assertNull( history.get( 0 ).from() );
        assertEquals( DeploymentState.REQUESTED, history.get( 0 ).to() );
        assertEquals( 2, count( "SELECT count(*) FROM work_event WHERE type = 'deployment_requested' AND from_value = "
            + "'deployment:" + d.id() + "' AND to_value = '2.5.26'" ) );
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'deployment_requested' AND item_id = "
            + two.id() + " AND project_id = " + two.projectId() ), "each event sits in its own item's project" );
        assertEquals( d, deployments.deployment( d.id() ).orElseThrow() );
        assertEquals( List.of( d.id() ), deployments.awaitingApproval().stream().map( Deployment::id ).toList() );
    }

    @Test
    void moveFollowsTheTransitionTable() throws SQLException {
        final Deployment d = requested();
        final WorkConflictException early = assertThrows( WorkConflictException.class,
            () -> deployments.move( d.id(), DeploymentState.RUNNING, null, null, alice ) );
        assertEquals( WorkConflictException.BAD_TRANSITION, early.reason() );
        assertEquals( "A deployment that is requested cannot become running.", early.getMessage() );
        final Deployment approved = deployments.move( d.id(), DeploymentState.APPROVED, null, "go", lena );
        assertEquals( "lena", approved.approvedBy() );
        assertNotNull( approved.approvedAt() );
        assertTrue( deployments.awaitingApproval().isEmpty() );
        assertThrows( WorkConflictException.class,
            () -> deployments.move( d.id(), DeploymentState.APPROVED, null, null, lena ), "approved twice" );
        assertNotNull( deployments.move( d.id(), DeploymentState.RUNNING, null, null, alice ).startedAt() );
        final Deployment failed = deployments.move( d.id(), DeploymentState.FAILED, null, "smoke test red", alice );
        assertNotNull( failed.closedAt() );
        assertEquals( List.of( "requested", "approved", "running", "failed" ),
            deployments.history( d.id() ).stream().map( e -> e.to().wire() ).toList() );
        assertEquals( "smoke test red", deployments.history( d.id() ).get( 3 ).note() );
        assertEquals( DeploymentState.RUNNING, deployments.history( d.id() ).get( 3 ).from() );
    }

    @Test
    void verifiedStoresEvidenceAndShipsEveryItem() throws SQLException {
        final Deployment d = verified( requested() );
        assertEquals( "health 200; smoke ok", d.evidence() );
        assertNotNull( d.verifiedAt() );
        assertEquals( 2, count( "SELECT count(*) FROM work_event WHERE type = 'shipped' AND to_value = '2.5.26'" ) );
        assertTrue( deployments.shippedVerified( one.id() ) );
        assertTrue( deployments.shippedVerified( two.id() ) );
    }

    @Test
    void rolledBackWritesAnEventPerItemAndNoLongerCounts() throws SQLException {
        final Deployment d = verified( requested() );
        deployments.move( d.id(), DeploymentState.ROLLED_BACK, null, "regression", lena );
        assertEquals( 2, count( "SELECT count(*) FROM work_event WHERE type = 'rolled_back'" ) );
        assertFalse( deployments.shippedVerified( one.id() ) );
    }

    @Test
    void shippedVerifiedIgnoresFailedRequestedAndOtherItems() throws SQLException {
        assertFalse( deployments.shippedVerified( one.id() ) );
        final Deployment failing = deployments.request( product, "production", "2.5.26", List.of( one ), alice );
        deployments.move( failing.id(), DeploymentState.APPROVED, null, null, lena );
        deployments.move( failing.id(), DeploymentState.RUNNING, null, null, alice );
        deployments.move( failing.id(), DeploymentState.FAILED, null, null, alice );
        assertFalse( deployments.shippedVerified( one.id() ) );
        verified( deployments.request( product, "production", "2.5.27", List.of( one ), alice ) );
        assertTrue( deployments.shippedVerified( one.id() ), "one verified among several is enough" );
        assertFalse( deployments.shippedVerified( two.id() ) );
        assertEquals( List.of( "2.5.27", "2.5.26" ),
            deployments.carrying( one.id() ).stream().map( Deployment::version ).toList() );
        assertEquals( 2, deployments.forProduct( product.id(), 10, 0 ).size() );
        assertEquals( 1, deployments.forProduct( product.id(), 1, 0 ).size() );
        assertEquals( 1, deployments.forProduct( product.id(), 10, 1 ).size() );
    }

    @Test
    void anAgentsMoveRecordsItsId() throws SQLException {
        final long agentId = WorkAgentFixtures.agent( ds, "deployer" );
        final Actor agent = Actor.agent( agentId, "deployer" );
        final Deployment d = deployments.request( product, "staging", "2.5.26", List.of( one ), agent );
        assertEquals( agentId, d.requestedByAgentId() );
        assertEquals( "staging", d.environment() );
        assertEquals( 1, count( "SELECT count(*) FROM work_deployment_event WHERE actor_kind = 'agent' AND agent_id = "
            + agentId ) );
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'deployment_requested' AND agent_id = "
            + agentId ) );
        assertEquals( agentId, deployments.move( d.id(), DeploymentState.APPROVED, null, null, agent ).approvedByAgentId() );
    }

    @Test
    void anUnknownDeploymentIsEmpty() throws SQLException {
        assertTrue( deployments.deployment( 999_999 ).isEmpty() );
        assertTrue( deployments.history( 999_999 ).isEmpty() );
    }

    // ---- all or nothing

    private WorkDeploymentStore failingOn( final int statement ) {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( statement, new RuntimeException( "boom" ) );
        return new JdbcWorkStore( faulty ).deployments();
    }

    @Test
    void aFailedRequestLeavesNothing() throws SQLException {
        // statements: 1 insert the deployment, 2 its items, 3 the history row, 4 and 5 an event per item
        assertThrows( RuntimeException.class,
            () -> failingOn( 5 ).request( product, "production", "2.5.26", List.of( one, two ), alice ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_deployment" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_deployment_item" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_deployment_event" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'deployment_requested'" ) );
    }

    @Test
    void aFailedVerifyLeavesTheDeploymentRunning() throws SQLException {
        final Deployment d = requested();
        deployments.move( d.id(), DeploymentState.APPROVED, null, null, lena );
        deployments.move( d.id(), DeploymentState.RUNNING, null, null, alice );
        // statements: 1 lock the row, 2 update it, 3 the history row, 4 and 5 a shipped event per item
        assertThrows( RuntimeException.class,
            () -> failingOn( 5 ).move( d.id(), DeploymentState.VERIFIED, "health 200", null, alice ) );
        assertEquals( DeploymentState.RUNNING, deployments.deployment( d.id() ).orElseThrow().state() );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'shipped'" ) );
        assertEquals( 3, deployments.history( d.id() ).size() );
    }
}
