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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store enforces "no claim beyond the sponsor's standing", not only the service: a claim locks the agent row
 * (with the sponsor it was resolved under), the agent's member row, the sponsor's member rows, the project and then
 * the item. A change to the sponsor's standing releases the claims of the agents they sponsor in its own
 * transaction. Agent A is sponsored by ana (not an admin).
 */
@RequiresPostgres
class JdbcWorkClaimsSponsorPostgresTest {

    private static final Duration HOUR = Duration.ofHours( 1 );
    private static final Actor ROOT = Actor.human( "root", Set.of(), true );

    private WorkClaimFixtures fx;
    private WorkClaimStore claims;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        claims = fx.store.claims();
        fx.store.setSponsor( fx.a.id(), "ana", "ana" );
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static Actor ana( final String... groups ) {
        return Actor.human( "ana", Set.of( groups ), false );
    }

    private Actor agentA( final Actor sponsor ) {
        return Actor.agent( fx.a.id(), fx.a.name(), sponsor );
    }

    private void sponsorIs( final WorkRole role ) throws SQLException {
        fx.store.putMember( fx.plat.id(), "ana", "user", role, ROOT );
    }

    private List< WorkEvent > released( final Item item ) throws SQLException {
        return fx.events( item, WorkEvent.RELEASED );
    }

    // ---- the claim

    @Test
    void noClaimWhenTheSponsorIsNotAMember() throws SQLException {
        assertTrue( claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).isEmpty() );
    }

    @Test
    void noClaimWhenTheSponsorIsAViewer() throws SQLException {
        sponsorIs( WorkRole.VIEWER );
        assertTrue( claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).isEmpty() );
    }

    @Test
    void claimWhenTheSponsorIsAMemberOrLead() throws SQLException {
        sponsorIs( WorkRole.MEMBER );
        assertTrue( claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).isPresent() );
        sponsorIs( WorkRole.LEAD );
        assertTrue( claims.claim( fx.ready( "y" ), agentA( ana() ), HOUR ).isPresent() );
    }

    @Test
    void claimWhenTheSponsorIsAMemberThroughAGroup() throws SQLException {
        fx.store.putMember( fx.plat.id(), "Crew", "group", WorkRole.MEMBER, ROOT );
        assertTrue( claims.claim( fx.ready( "x" ), agentA( ana( "Crew" ) ), HOUR ).isPresent() );
        assertTrue( claims.claim( fx.ready( "y" ), agentA( ana( "Other" ) ), HOUR ).isEmpty() );
    }

    @Test
    void claimWhenTheSponsorIsAnAdminAndNotAMember() throws SQLException {
        fx.store.setSponsor( fx.a.id(), "root", "root" );
        assertTrue( claims.claim( fx.ready( "x" ), agentA( ROOT ), HOUR ).isPresent() );
    }

    @Test
    void noClaimWhenTheSponsorChangedSinceTheActorWasResolved() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Actor resolvedUnderAna = agentA( ana() );
        fx.store.setSponsor( fx.a.id(), "bo", "bo" );
        assertTrue( claims.claim( fx.ready( "x" ), resolvedUnderAna, HOUR ).isEmpty(),
            "the actor was resolved under a sponsor the agent no longer has" );
        fx.store.setSponsor( fx.a.id(), null, "bo" );
        assertTrue( claims.claim( fx.ready( "y" ), resolvedUnderAna, HOUR ).isEmpty() );
    }

    // ---- a member change releases the claims of the agents the person sponsors

    @Test
    void removingTheSponsorsRowReleasesTheirAgentsClaims() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).orElseThrow();
        final Item others = claims.claim( fx.ready( "y" ), fx.actorB, HOUR ).orElseThrow();
        final int ended = fx.store.removeMember( fx.plat.id(), "ana", "user", WorkClaimFixtures.LENA, List.of( ana() ) );
        assertEquals( 1, ended );
        final Item after = fx.reload( held );
        assertNull( after.claimedByAgent() );
        assertEquals( WorkStatus.READY, after.status() );
        final WorkEvent e = released( held ).get( 0 );
        assertEquals( "sponsor access removed", e.note() );
        assertEquals( "agent:" + fx.a.id(), e.fromValue() );
        assertEquals( "ready", e.toValue() );
        assertEquals( "lena", e.actor(), "the person who changed the membership" );
        assertNotNull( fx.reload( others ).claimedByAgent(), "another sponsor's agent keeps its claim" );
    }

    @Test
    void demotingTheSponsorToViewerReleasesAndToMemberDoesNot() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).orElseThrow();
        assertEquals( 0, fx.store.putMember( fx.plat.id(), "ana", "user", WorkRole.MEMBER, WorkClaimFixtures.LENA,
            List.of( ana() ) ) );
        assertNotNull( fx.reload( held ).claimedByAgent() );
        assertEquals( 1, fx.store.putMember( fx.plat.id(), "ana", "user", WorkRole.VIEWER, WorkClaimFixtures.LENA,
            List.of( ana() ) ) );
        assertNull( fx.reload( held ).claimedByAgent() );
    }

    @Test
    void removingAGroupRowReleasesClaimsOfSponsorsWhoStoodOnlyOnThatGroup() throws SQLException {
        fx.store.putMember( fx.plat.id(), "Crew", "group", WorkRole.MEMBER, ROOT );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ana( "Crew" ) ), HOUR ).orElseThrow();
        assertEquals( 1, fx.store.removeMember( fx.plat.id(), "Crew", "group", WorkClaimFixtures.LENA,
            List.of( ana( "Crew" ) ) ) );
        assertNull( fx.reload( held ).claimedByAgent() );
        assertEquals( "sponsor access removed", released( held ).get( 0 ).note() );
    }

    @Test
    void aSponsorWithAnotherRouteToMemberKeepsTheClaims() throws SQLException {
        sponsorIs( WorkRole.MEMBER );
        fx.store.putMember( fx.plat.id(), "Crew", "group", WorkRole.MEMBER, ROOT );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ana( "Crew" ) ), HOUR ).orElseThrow();
        assertEquals( 0, fx.store.removeMember( fx.plat.id(), "ana", "user", WorkClaimFixtures.LENA,
            List.of( ana( "Crew" ) ) ) );
        assertNotNull( fx.reload( held ).claimedByAgent() );
    }

    @Test
    void anAdminSponsorIsNeverRechecked() throws SQLException {
        fx.store.setSponsor( fx.a.id(), "root", "root" );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ROOT ), HOUR ).orElseThrow();
        fx.store.putMember( fx.plat.id(), "root", "user", WorkRole.MEMBER, ROOT );
        assertEquals( 0, fx.store.removeMember( fx.plat.id(), "root", "user", WorkClaimFixtures.LENA, List.of( ROOT ) ) );
        assertNotNull( fx.reload( held ).claimedByAgent() );
    }

    // ---- a change of sponsor

    @Test
    void aNewSponsorOutOfReachReleasesAndOneInReachKeeps() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).orElseThrow();
        fx.store.putMember( fx.plat.id(), "pat", "user", WorkRole.LEAD, ROOT );
        final Actor pat = Actor.human( "pat", Set.of(), false );
        assertEquals( 0, claims.setSponsorAndRelease( fx.a.id(), "ana", pat, "pat", pat ) );
        assertEquals( "pat", fx.store.agent( fx.a.id() ).orElseThrow().sponsor() );
        assertNotNull( fx.reload( held ).claimedByAgent(), "the new sponsor is a lead here" );
        final Actor bo = Actor.human( "bo", Set.of(), false );
        assertEquals( 1, claims.setSponsorAndRelease( fx.a.id(), "pat", bo, "root", ROOT ) );
        assertNull( fx.reload( held ).claimedByAgent() );
        final WorkEvent e = released( held ).get( 0 );
        assertEquals( "sponsor access removed", e.note() );
        assertEquals( "root", e.actor() );
        assertEquals( List.of( "bo", "pat", "ana", "sponsor-root" ), fx.store.sponsorHistory( fx.a.id() ).stream()
            .map( WorkRegistryStore.SponsorChange::sponsor ).toList(), "each change is in the history, newest first" );
    }

    @Test
    void theSweepEndsAClaimOnlyUnderTheSponsorItJudged() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Item held = claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).orElseThrow();
        // the sweep read the agent under ana and found her without standing; pat took it over before the release
        fx.store.putMember( fx.plat.id(), "pat", "user", WorkRole.LEAD, ROOT );
        final Actor pat = Actor.human( "pat", Set.of(), false );
        claims.setSponsorAndRelease( fx.a.id(), "ana", pat, "pat", pat );
        final Actor self = Actor.agent( fx.a.id(), fx.a.name() );
        assertEquals( 0, claims.releaseForStanding( fx.plat.id(), fx.a.id(), "ana", self, "sponsor access removed" ) );
        assertNotNull( fx.reload( held ).claimedByAgent(), "pat stands behind it now: the claim is his agent's" );
        assertEquals( 1, claims.releaseForStanding( fx.plat.id(), fx.a.id(), "pat", self, "sponsor access removed" ) );
        assertNull( fx.reload( held ).claimedByAgent() );
    }

    @Test
    void givingUpSponsorshipReleasesEveryClaim() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Item one = claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).orElseThrow();
        final Item two = claims.claim( fx.ready( "y" ), agentA( ana() ), HOUR ).orElseThrow();
        assertEquals( 2, claims.setSponsorAndRelease( fx.a.id(), "ana", null, "ana", ana() ) );
        assertNull( fx.store.agent( fx.a.id() ).orElseThrow().sponsor() );
        assertNull( fx.reload( one ).claimedByAgent() );
        assertNull( fx.reload( two ).claimedByAgent() );
    }

    // ---- races: the sponsor's member row is in the lock order

    @Test
    void aClaimWaitingOnTheSponsorsRemovalComesBackEmpty() throws Exception {
        sponsorIs( WorkRole.LEAD );
        final Item item = fx.ready( "x" );
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< ? > removal = pool.submit( () -> {
            new JdbcWorkStore( gate ).removeMember( fx.plat.id(), "ana", "user", WorkClaimFixtures.LENA,
                List.of( ana() ) );
            return null;
        } );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Optional< Item > > waiting = pool.submit( () -> claims.claim( item, agentA( ana() ), HOUR ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        removal.get( 30, TimeUnit.SECONDS );
        assertTrue( waiting.get( 30, TimeUnit.SECONDS ).isEmpty(), "the removal landed first: nothing to stand on" );
        assertNull( fx.reload( item ).claimedByAgent() );
    }

    @Test
    void aSponsorsRemovalWaitingOnAClaimReleasesIt() throws Exception {
        sponsorIs( WorkRole.LEAD );
        final Item item = fx.ready( "x" );
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< Optional< Item > > claim = pool.submit(
            () -> new JdbcWorkStore( gate ).claims().claim( item, agentA( ana() ), HOUR ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Integer > removal = pool.submit( () -> fx.store.removeMember( fx.plat.id(), "ana", "user",
            WorkClaimFixtures.LENA, List.of( ana() ) ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        assertTrue( claim.get( 30, TimeUnit.SECONDS ).isPresent(), "the claim landed first" );
        assertEquals( 1, removal.get( 30, TimeUnit.SECONDS ), "and the removal, having waited, released it" );
        assertNull( fx.reload( item ).claimedByAgent() );
        assertEquals( List.of( "sponsor access removed" ), released( item ).stream().map( WorkEvent::note ).toList() );
    }

    @Test
    void aClaimWaitingOnAChangeOfSponsorComesBackEmpty() throws Exception {
        sponsorIs( WorkRole.LEAD );
        final Item item = fx.ready( "x" );
        final CommitGate gate = new CommitGate( fx.ds );
        final Actor bo = Actor.human( "bo", Set.of(), false );
        final Future< Integer > takeover = pool.submit(
            () -> new JdbcWorkStore( gate ).claims().setSponsorAndRelease( fx.a.id(), "ana", bo, "root", ROOT ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Optional< Item > > waiting = pool.submit( () -> claims.claim( item, agentA( ana() ), HOUR ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        takeover.get( 30, TimeUnit.SECONDS );
        assertTrue( waiting.get( 30, TimeUnit.SECONDS ).isEmpty(), "never a claim under the old sponsor" );
        assertNull( fx.reload( item ).claimedByAgent() );
    }

    // ---- what the sweep needs

    @Test
    void claimHoldersNameEachAgentProjectPairOnceWithItsSponsor() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR );
        claims.claim( fx.ready( "y" ), agentA( ana() ), HOUR );
        claims.claim( fx.ready( "z" ), fx.actorB, HOUR );
        final List< WorkClaimStore.Holder > all = claims.claimHolders( null );
        assertEquals( 2, all.size() );
        assertTrue( all.contains( new WorkClaimStore.Holder( fx.a.id(), fx.a.name(), "ana", fx.plat.id() ) ) );
        assertTrue( all.contains( new WorkClaimStore.Holder( fx.b.id(), fx.b.name(), "sponsor-root", fx.plat.id() ) ) );
        assertEquals( all, claims.claimHolders( fx.plat.id() ) );
    }

    @Test
    void releaseForStandingEndsOnlyTheNamedAgentsClaimsInThatProject() throws SQLException {
        sponsorIs( WorkRole.LEAD );
        final Item mine = claims.claim( fx.ready( "x" ), agentA( ana() ), HOUR ).orElseThrow();
        final Item theirs = claims.claim( fx.ready( "y" ), fx.actorB, HOUR ).orElseThrow();
        final Actor self = Actor.agent( fx.a.id(), fx.a.name() );
        assertEquals( 1, claims.releaseForStanding( fx.plat.id(), List.of( fx.a.id() ), self, "sponsor access removed" ) );
        assertNull( fx.reload( mine ).claimedByAgent() );
        assertNotNull( fx.reload( theirs ).claimedByAgent() );
        final WorkEvent e = released( mine ).get( 0 );
        assertEquals( Actor.Kind.AGENT, e.actorKind(), "a sweep's release is attributed to the agent, as expiry is" );
        assertEquals( fx.a.id(), e.agentId() );
    }
}
