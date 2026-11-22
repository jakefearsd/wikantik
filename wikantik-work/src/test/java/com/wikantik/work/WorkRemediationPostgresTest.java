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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A deployment may name the earlier deployment it remediates (fix-forward): what is accepted, what is refused. */
@RequiresPostgres
class WorkRemediationPostgresTest {

    private static final String SAME = "A remediating deployment fixes an earlier deployment of the same product and environment.";
    private static final String STATE = "Only a deployment that was verified, failed or rolled back can be remediated.";

    private GateFixture f;
    private WorkDeploymentsService deployments;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        deployments = f.svc.deployments();
        item = f.status( f.lena, f.task(), "ready" );
    }

    private long request( final String env, final String version, final Long remediates ) throws SQLException {
        return deployments.request( f.mia, new DeploymentDraft( "wikantik", env, version, List.of( item.key() ),
            remediates ) ).deployment().id();
    }

    private long running( final String env, final String version ) throws SQLException {
        final long id = request( env, version, null );
        deployments.approve( f.lena, id, null );
        deployments.start( f.mia, id );
        return id;
    }

    private long verified() throws SQLException {
        final long id = running( null, "1.0.0" );
        deployments.verify( f.mia, id, "health 200; smoke ok" );
        return id;
    }

    private WorkValidationException invalid( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkValidationException.class, call );
    }

    @Test
    void aVerifiedDeploymentCanBeRemediated() throws SQLException {
        final long bad = verified();
        final long id = request( null, "1.0.1", bad );
        assertEquals( bad, deployments.deployment( f.mia, id ).deployment().remediatesId() );
        assertNull( deployments.deployment( f.mia, bad ).deployment().remediatesId() );
    }

    @Test
    void aFailedAndARolledBackOneCanBeRemediated() throws SQLException {
        final long failed = running( null, "1.0.0" );
        deployments.fail( f.mia, failed, "smoke red" );
        assertEquals( failed, deployments.deployment( f.mia, request( null, "1.0.1", failed ) ).deployment().remediatesId() );
        final long rolled = running( null, "1.0.2" );
        deployments.verify( f.mia, rolled, "health 200; smoke ok" );
        deployments.rollBack( f.mia, rolled, "regression" );
        assertEquals( rolled, deployments.deployment( f.mia, request( null, "1.0.3", rolled ) ).deployment().remediatesId() );
    }

    @Test
    void aRequestedApprovedOrRunningOneCannot() throws SQLException {
        final long requested = request( null, "1.0.0", null );
        WorkValidationException e = invalid( () -> request( null, "1.0.1", requested ) );
        assertEquals( "remediates", e.field() );
        assertEquals( STATE, e.getMessage() );
        deployments.approve( f.lena, requested, null );
        assertEquals( STATE, invalid( () -> request( null, "1.0.1", requested ) ).getMessage() );
        deployments.start( f.mia, requested );
        e = invalid( () -> request( null, "1.0.1", requested ) );
        assertEquals( "remediates", e.field() );
        assertEquals( STATE, e.getMessage() );
        final long rejected = request( null, "1.0.2", null );
        deployments.reject( f.mia, rejected, "wrong build" );
        assertEquals( STATE, invalid( () -> request( null, "1.0.3", rejected ) ).getMessage() );
    }

    @Test
    void anotherProductOrEnvironmentIsRefused() throws SQLException {
        final long bad = verified();
        final WorkValidationException staging = invalid( () -> request( "staging", "1.0.1", bad ) );
        assertEquals( "remediates", staging.field() );
        assertEquals( SAME, staging.getMessage() );
        f.svc.createProduct( f.admin, new Product( 0, "jakemon", "Jakemon", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        final Item other = f.status( f.lena, f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.TASK, null,
            "jakemon", WorkKind.SOFTWARE, "Another product", "", null, null, null, "implementer", null, null ) ).item(),
            "ready" );
        final WorkValidationException product = invalid( () -> deployments.request( f.mia,
            new DeploymentDraft( "jakemon", null, "1.0.1", List.of( other.key() ), bad ) ) );
        assertEquals( "remediates", product.field() );
        assertEquals( SAME, product.getMessage() );
    }

    @Test
    void aBlankEnvironmentMeansProduction() throws SQLException {
        final long bad = verified();
        assertEquals( bad, deployments.deployment( f.mia, request( "", "1.0.1", bad ) ).deployment().remediatesId() );
        assertEquals( bad, deployments.deployment( f.mia, request( "production", "1.0.2", bad ) ).deployment().remediatesId() );
    }

    @Test
    void anUnknownOrUnseenTargetIsNotFound() throws SQLException {
        final String unknown = assertThrows( WorkNotFoundException.class, () -> request( null, "1.0.1", 999_999L ) )
            .getMessage();
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        f.svc.putMember( f.admin, "OPS", "olga", "user", WorkRole.LEAD );
        final Actor olga = Actor.human( "olga", Set.of(), false );
        final Item ops = f.status( olga, f.svc.createItem( olga, new ItemDraft( "OPS", WorkLevel.TASK, null, "wikantik",
            WorkKind.SOFTWARE, "An ops item", "", null, null, null, null, null, null ) ).item(), "ready" );
        final long id = deployments.request( olga, new DeploymentDraft( "wikantik", null, "9.9.9",
            List.of( ops.key() ) ) ).deployment().id();
        deployments.approve( f.admin, id, null );
        deployments.start( olga, id );
        deployments.verify( olga, id, "health 200; smoke ok" );
        final String unseen = assertThrows( WorkNotFoundException.class, () -> request( null, "1.0.2", id ) ).getMessage();
        assertEquals( unknown, unseen, "unknown and unseen are the same answer" );
    }

    @Test
    void twoDeploymentsMayRemediateOne() throws SQLException {
        final long bad = verified();
        final long one = request( null, "1.0.1", bad );
        final long two = request( null, "1.0.2", bad );
        assertEquals( bad, deployments.deployment( f.mia, one ).deployment().remediatesId() );
        assertEquals( bad, deployments.deployment( f.mia, two ).deployment().remediatesId() );
    }
}
