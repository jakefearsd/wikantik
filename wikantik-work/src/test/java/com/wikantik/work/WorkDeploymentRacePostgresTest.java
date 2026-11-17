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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A deploy script asking while the deployment moves, and two approvals at once. */
@RequiresPostgres
class WorkDeploymentRacePostgresTest {

    private final ExecutorService pool = Executors.newFixedThreadPool( 2 );
    private GateFixture f;
    private long id;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        id = f.svc.deployments().request( f.mia, new DeploymentDraft( "wikantik", null, "2.5.26",
            List.of( f.task().key() ) ) ).deployment().id();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void theScriptSeesOnlyWhatIsCommitted() throws Exception {
        f.svc.deployments().approve( f.lena, id, null );
        f.svc.deployments().start( f.mia, id );
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Deployment > failing = pool.submit( () -> new JdbcWorkStore( gate ).deployments()
            .move( id, DeploymentState.FAILED, null, "smoke test red", f.mia ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        assertTrue( f.svc.deployments().cleared( f.mia, id, "2.5.26" ), "the failure is not committed yet" );
        gate.open();
        failing.get( 30, TimeUnit.SECONDS );
        assertFalse( f.svc.deployments().cleared( f.mia, id, "2.5.26" ), "a failed deployment is never cleared" );
    }

    @Test
    void anApprovalNotYetCommittedClearsNothing() throws Exception {
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Deployment > approving = pool.submit( () -> new JdbcWorkStore( gate ).deployments()
            .move( id, DeploymentState.APPROVED, null, null, f.lena ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        assertFalse( f.svc.deployments().cleared( f.mia, id, "2.5.26" ) );
        gate.open();
        approving.get( 30, TimeUnit.SECONDS );
        assertTrue( f.svc.deployments().cleared( f.mia, id, "2.5.26" ) );
    }

    @Test
    void twoApprovalsWriteOneHistoryRow() throws Exception {
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Deployment > first = pool.submit( () -> new JdbcWorkStore( gate ).deployments()
            .move( id, DeploymentState.APPROVED, null, null, f.lena ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< WorkDeploymentsService.DeploymentView > second =
            pool.submit( () -> f.svc.deployments().approve( f.admin, id, null ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        assertEquals( "lena", first.get( 30, TimeUnit.SECONDS ).approvedBy() );
        final ExecutionException e = assertThrows( ExecutionException.class, () -> second.get( 30, TimeUnit.SECONDS ) );
        assertEquals( WorkConflictException.BAD_TRANSITION,
            assertInstanceOf( WorkConflictException.class, e.getCause() ).reason() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_deployment_event WHERE to_state = 'approved'" ) );
    }
}
