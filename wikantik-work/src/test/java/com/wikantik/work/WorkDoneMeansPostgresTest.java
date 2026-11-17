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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An epic whose done-means is "in production": its stories and tasks close only once a verified deployment
 * carried them. The rule holds for people too; it is about the work, not about who asks.
 */
@RequiresPostgres
class WorkDoneMeansPostgresTest {

    private GateFixture f;
    private WorkDeploymentsService deployments;
    private Item epic;
    private Item story;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        deployments = f.svc.deployments();
        epic = f.draft( WorkLevel.EPIC, null );
        f.set( f.lena, epic, "doneMeans", "in_production" );
        story = f.draft( WorkLevel.STORY, epic.key() );
        f.status( f.lena, story, "ready" );
    }

    private String held( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkConflictException.class, call ).reason();
    }

    private long deploymentOf( final String version, final Item... items ) throws SQLException {
        return deployments.request( f.mia, new DeploymentDraft( "wikantik", null, version,
            List.of( items ).stream().map( Item::key ).toList() ) ).deployment().id();
    }

    private long running( final String version, final Item... items ) throws SQLException {
        final long id = deploymentOf( version, items );
        deployments.approve( f.lena, id, null );
        deployments.start( f.mia, id );
        return id;
    }

    private long verified( final String version, final Item... items ) throws SQLException {
        final long id = running( version, items );
        deployments.verify( f.mia, id, "health 200; smoke ok" );
        return id;
    }

    @Test
    void anItemUnderAnInProductionEpicCannotCloseAtMerged() throws SQLException {
        assertEquals( "awaiting_deployment", held( () -> f.status( f.mia, story, "done" ) ), "a person is held too" );
        assertEquals( "awaiting_deployment", held( () -> f.handoff( f.mia, story, WorkStatus.DONE, "lena", null ) ) );
        f.svc.claims().claim( f.author, story.key() );
        assertEquals( "awaiting_deployment", held( () -> f.status( f.author, story, "done" ) ) );
        assertEquals( "This closes when a deployment that carries it is verified.",
            assertThrows( WorkConflictException.class, () -> f.status( f.lena, story, "done" ) ).getMessage() );
        assertEquals( WorkStatus.REVIEW, f.status( f.mia, story, "review" ).status(), "every other move is open" );
        assertEquals( WorkStatus.CANCELLED, f.status( f.mia, story, "cancelled" ).status(), "and it may be cancelled" );
    }

    @Test
    void itClosesOnceACarryingDeploymentIsVerified() throws SQLException {
        final long id = running( "2.5.26", story );
        assertEquals( "awaiting_deployment", held( () -> f.status( f.mia, story, "done" ) ), "running is not verified" );
        deployments.verify( f.mia, id, "health 200; smoke ok" );
        assertEquals( WorkStatus.DONE, f.status( f.mia, story, "done" ).status() );
    }

    @Test
    void aFailedOrRolledBackDeploymentDoesNotCountButOneVerifiedIsEnough() throws SQLException {
        deployments.fail( f.mia, running( "2.5.26", story ), "smoke test red" );
        assertEquals( "awaiting_deployment", held( () -> f.status( f.mia, story, "done" ) ) );
        final long good = verified( "2.5.27", story );
        deployments.rollBack( f.mia, good, "regression" );
        assertEquals( "awaiting_deployment", held( () -> f.status( f.mia, story, "done" ) ), "rolled back again" );
        verified( "2.5.28", story );
        assertEquals( WorkStatus.DONE, f.status( f.mia, story, "done" ).status() );
    }

    @Test
    void anItemUnderAMergedEpicOrNoEpicClosesAsBefore() throws SQLException {
        final Item other = f.draft( WorkLevel.EPIC, null );
        final Item plain = f.draft( WorkLevel.STORY, other.key() );
        assertEquals( WorkStatus.DONE, f.status( f.mia, plain, "done" ).status() );
        assertEquals( WorkStatus.DONE, f.status( f.mia, f.task(), "done" ).status() );
    }

    @Test
    void changingTheEpicBackToMergedLetsItClose() throws SQLException {
        assertEquals( "awaiting_deployment", held( () -> f.status( f.mia, story, "done" ) ) );
        f.set( f.lena, epic, "doneMeans", "merged" );
        assertEquals( WorkStatus.READY, f.fresh( story ).status(), "nothing closes by itself" );
        assertEquals( WorkStatus.DONE, f.status( f.mia, story, "done" ).status() );
    }

    @Test
    void theEpicItselfIsNotHeld() throws SQLException {
        assertEquals( WorkStatus.DONE, f.status( f.lena, epic, "done" ).status() );
    }

    @Test
    void shippedInListsVerifiedDeploymentsOnlyAndSurvivesAReopen() throws SQLException {
        deploymentOf( "2.5.25", story );
        deployments.fail( f.mia, running( "2.5.26", story ), "red" );
        final long first = verified( "2.5.27", story );
        final long second = verified( "2.5.28", story );
        assertEquals( List.of( second, first ), deployments.shippedIn( f.mia, story.key() ).stream()
            .map( v -> v.deployment().id() ).toList(), "newest first" );
        f.status( f.mia, story, "done" );
        f.status( f.mia, story, "review" );
        assertEquals( 2, deployments.shippedIn( f.mia, story.key() ).size() );
        assertTrue( deployments.shippedIn( f.mia, f.task().key() ).isEmpty() );
        assertThrows( WorkNotFoundException.class,
            () -> deployments.shippedIn( Actor.human( "sam", Set.of(), false ), story.key() ) );
        assertEquals( 2, f.count( "SELECT count(*) FROM work_event WHERE type = 'shipped' AND item_id = " + story.id() ) );
    }
}
