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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Claim, renewal, release, expiry and the claim lists of {@link WorkClaimStore}, against real PostgreSQL. */
@RequiresPostgres
class JdbcWorkClaimsPostgresTest {

    private static final Duration HOUR = Duration.ofHours( 1 );

    private WorkClaimFixtures fx;
    private JdbcWorkStore store;
    private WorkClaimStore claims;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        store = fx.store;
        claims = store.claims();
    }

    @Test
    void claimTakesReadyItem() throws SQLException {
        final Item item = fx.ready( "x" );
        final Instant before = Instant.now();
        final Item got = claims.claim( item, fx.actorA, HOUR ).orElseThrow();
        assertEquals( WorkStatus.IN_PROGRESS, got.status() );
        assertEquals( fx.a.id(), got.claimedByAgent() );
        assertEquals( item.version() + 1, got.version() );
        assertTrue( got.claimedAt() != null && !got.claimedAt().isBefore( before.minusSeconds( 5 ) ) );
        assertTrue( Duration.between( before.plus( HOUR ), got.claimExpiresAt() ).abs().getSeconds() < 30 );
        assertEquals( got, fx.reload( item ) );
        final List< WorkEvent > ev = fx.events( item, WorkEvent.CLAIMED );
        assertEquals( 1, ev.size() );
        assertEquals( fx.a.id(), ev.get( 0 ).agentId() );
        assertEquals( Actor.Kind.AGENT, ev.get( 0 ).actorKind() );
        assertEquals( "agent-a", ev.get( 0 ).actor() );
        assertEquals( "ready", ev.get( 0 ).fromValue() );
        assertEquals( "in_progress", ev.get( 0 ).toValue() );
        assertEquals( got.claimExpiresAt().toString(), ev.get( 0 ).note() );
    }

    @Test
    void claimTakesReviewItemWithoutMoving() throws SQLException {
        final Item item = fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" );
        final Item got = claims.claim( item, fx.actorA, HOUR ).orElseThrow();
        assertEquals( WorkStatus.REVIEW, got.status() );
        assertEquals( item.version(), got.version() );
        assertEquals( fx.a.id(), got.claimedByAgent() );
        final WorkEvent e = fx.events( item, WorkEvent.CLAIMED ).get( 0 );
        assertEquals( "review", e.fromValue() );
        assertEquals( "review", e.toValue() );
    }

    @Test
    void holderClaimRenews() throws SQLException {
        final Item first = claims.claim( fx.ready( "x" ), fx.actorA, Duration.ofMinutes( 10 ) ).orElseThrow();
        final Item again = claims.claim( first, fx.actorA, HOUR ).orElseThrow();
        assertEquals( first.claimedAt(), again.claimedAt() );
        assertTrue( again.claimExpiresAt().isAfter( first.claimExpiresAt() ) );
        assertEquals( first.version(), again.version() );
        assertEquals( WorkStatus.IN_PROGRESS, again.status() );
        assertEquals( 1, fx.events( first, WorkEvent.CLAIMED ).size() );
        final List< WorkEvent > renewed = fx.events( first, WorkEvent.CLAIM_RENEWED );
        assertEquals( 1, renewed.size() );
        assertEquals( again.claimExpiresAt().toString(), renewed.get( 0 ).toValue() );
        assertNull( renewed.get( 0 ).fromValue() );
        assertEquals( fx.a.id(), renewed.get( 0 ).agentId() );
    }

    @Test
    void nonAgentActorCannotClaim() throws SQLException {
        final Item item = fx.ready( "x" );
        assertThrows( IllegalArgumentException.class, () -> claims.claim( item, WorkClaimFixtures.LENA, HOUR ) );
        assertThrows( IllegalArgumentException.class, () -> claims.claim( item,
            new Actor( "ghost", Set.of(), false, Actor.Kind.AGENT, null, null ), HOUR ) );
    }

    @Test
    void notClaimable() throws SQLException {
        final Item blocker = fx.create( fx.plat, "blocker", WorkStatus.IN_PROGRESS, 3, null, null, null );
        final Item blocked = fx.ready( "blocked" );
        store.addLink( blocked, "blocked_by", blocker.key().toLowerCase(), WorkClaimFixtures.LENA );
        final Item heldByB = claims.claim( fx.ready( "held" ), fx.actorB, HOUR ).orElseThrow();
        final java.util.Map< String, Item > cases = new java.util.LinkedHashMap<>();
        cases.put( "draft", fx.create( fx.plat, "d", WorkStatus.DRAFT, 3, null, null, "implementer" ) );
        cases.put( "in progress, unheld", fx.create( fx.plat, "p", WorkStatus.IN_PROGRESS, 3, null, null,
            "implementer" ) );
        cases.put( "done", fx.create( fx.plat, "n", WorkStatus.DONE, 3, null, null, "implementer" ) );
        cases.put( "held by B", heldByB );
        cases.put( "queue not served", fx.create( fx.plat, "q", WorkStatus.READY, 3, null, null, "reviewer" ) );
        cases.put( "assigned to a person", fx.create( fx.plat, "u", WorkStatus.READY, 3, null, "bob", null ) );
        cases.put( "no assignee", fx.create( fx.plat, "z", WorkStatus.READY, 3, null, null, null ) );
        cases.put( "blocked by an open item", blocked );
        for ( final var c : cases.entrySet() ) {
            final Item before = fx.reload( c.getValue() );
            assertTrue( claims.claim( c.getValue(), fx.actorA, HOUR ).isEmpty(), c.getKey() );
            assertEquals( before, fx.reload( c.getValue() ), c.getKey() );
            assertTrue( fx.events( before, WorkEvent.CLAIMED ).stream().noneMatch( e -> e.agentId() == fx.a.id() ),
                c.getKey() );
        }
        // Once the blocker is done the same item is claimable.
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET status = 'done' WHERE id = " + blocker.id() );
        assertTrue( claims.claim( blocked, fx.actorA, HOUR ).isPresent() );
    }

    @Test
    void blockerInAnotherProjectDoesNotBlock() throws SQLException {
        final Project other = store.createProject( "OTH", "Other", "" );
        final Item foreign = fx.create( other, "foreign", WorkStatus.IN_PROGRESS, 3, null, null, null );
        final Item item = fx.ready( "x" );
        store.addLink( item, "blocked_by", foreign.key(), WorkClaimFixtures.LENA );
        assertTrue( claims.claim( item, fx.actorA, HOUR ).isPresent() );
        assertEquals( store.isBlocked( item.id() ), false );
    }

    @Test
    void releaseReturnsToQueue() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final Item got = claims.release( held, fx.actorA, "done for now" ).orElseThrow();
        assertEquals( WorkStatus.READY, got.status() );
        assertNull( got.claimedByAgent() );
        assertNull( got.claimedAt() );
        assertNull( got.claimExpiresAt() );
        assertEquals( "implementer", got.queue() );
        assertEquals( held.version() + 1, got.version() );
        final List< WorkEvent > ev = fx.events( held, WorkEvent.RELEASED );
        assertEquals( 1, ev.size() );
        assertEquals( "agent-a", ev.get( 0 ).actor() );
        assertEquals( fx.a.id(), ev.get( 0 ).agentId() );
        assertEquals( "agent:" + fx.a.id(), ev.get( 0 ).fromValue() );
        assertEquals( "ready", ev.get( 0 ).toValue() );
        assertEquals( "done for now", ev.get( 0 ).note() );
    }

    @Test
    void personReleasesAnAgentsClaimAndBlankNoteDefaults() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        claims.release( held, WorkClaimFixtures.LENA, "  " ).orElseThrow();
        final WorkEvent e = fx.events( held, WorkEvent.RELEASED ).get( 0 );
        assertEquals( "lena", e.actor() );
        assertEquals( Actor.Kind.HUMAN, e.actorKind() );
        assertNull( e.agentId() );
        assertEquals( "agent:" + fx.a.id(), e.fromValue() );
        assertEquals( "released", e.note() );
    }

    @Test
    void releaseOfReviewClaimKeepsReview() throws SQLException {
        final Item held = claims.claim( fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        final Item got = claims.release( held, fx.actorA, null ).orElseThrow();
        assertEquals( WorkStatus.REVIEW, got.status() );
        assertEquals( held.version(), got.version() );
        assertNull( got.claimedByAgent() );
        assertEquals( "review", fx.events( held, WorkEvent.RELEASED ).get( 0 ).toValue() );
    }

    @Test
    void releaseWithGoneOrOthersClaimIsEmptyAndWritesNothing() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        claims.release( held, fx.actorA, "n" ).orElseThrow();
        final int events = store.itemEvents( held.id() ).size();
        assertTrue( claims.release( held, fx.actorA, "again" ).isEmpty() );
        assertTrue( claims.release( fx.reload( held ), fx.actorA, "unheld" ).isEmpty() );
        // B takes it; A's stale copy still names A, which no longer holds it.
        claims.claim( fx.reload( held ), fx.actorB, HOUR ).orElseThrow();
        final int afterB = store.itemEvents( held.id() ).size();
        assertTrue( claims.release( held, fx.actorA, "stale" ).isEmpty() );
        assertEquals( fx.b.id(), fx.reload( held ).claimedByAgent() );
        assertEquals( afterB, store.itemEvents( held.id() ).size() );
        assertEquals( events + 1, afterB );
    }

    @Test
    void sweepExpiresOnlyPastClaims() throws SQLException {
        final Item lapsed = claims.claim( fx.ready( "lapsed" ), fx.actorA, HOUR ).orElseThrow();
        final Item live = claims.claim( fx.ready( "live" ), fx.actorB, HOUR ).orElseThrow();
        fx.expireNow( lapsed );
        final List< Item > swept = claims.sweepExpiredClaims( 10 );
        assertEquals( 1, swept.size() );
        assertEquals( lapsed.id(), swept.get( 0 ).id() );
        final Item after = fx.reload( lapsed );
        assertEquals( WorkStatus.READY, after.status() );
        assertNull( after.claimedByAgent() );
        assertEquals( lapsed.version() + 1, after.version() );
        final List< WorkEvent > ev = fx.events( lapsed, WorkEvent.CLAIM_EXPIRED );
        assertEquals( 1, ev.size() );
        assertEquals( "agent-a", ev.get( 0 ).actor() );
        assertEquals( Actor.Kind.AGENT, ev.get( 0 ).actorKind() );
        assertEquals( fx.a.id(), ev.get( 0 ).agentId() );
        assertEquals( "in_progress", ev.get( 0 ).fromValue() );
        assertEquals( "ready", ev.get( 0 ).toValue() );
        assertEquals( live, fx.reload( live ) );
        assertTrue( fx.events( live, WorkEvent.CLAIM_EXPIRED ).isEmpty() );
        assertTrue( claims.sweepExpiredClaims( 10 ).isEmpty() );
    }

    @Test
    void sweepHandlesAtMostMaxRows() throws SQLException {
        for ( int i = 0; i < 5; i++ ) {
            fx.expireNow( claims.claim( fx.ready( "t" + i ), fx.actorA, HOUR ).orElseThrow() );
        }
        assertEquals( 2, claims.sweepExpiredClaims( 2 ).size() );
        assertEquals( 3, claims.sweepExpiredClaims( 100 ).size() );
        assertTrue( claims.sweepExpiredClaims( 0 ).isEmpty() );
    }

    @Test
    void sweepOfReviewClaimKeepsReview() throws SQLException {
        final Item held = claims.claim( fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        fx.expireNow( held );
        claims.sweepExpiredClaims( 5 );
        final Item after = fx.reload( held );
        assertEquals( WorkStatus.REVIEW, after.status() );
        assertEquals( held.version(), after.version() );
        assertEquals( "review", fx.events( held, WorkEvent.CLAIM_EXPIRED ).get( 0 ).toValue() );
    }

    @Test
    void sweepThenClaimGivesExpiredThenClaimed() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        fx.expireNow( held );
        claims.sweepExpiredClaims( 5 );
        final Item again = claims.claim( fx.reload( held ), fx.actorA, HOUR ).orElseThrow();
        assertNotEquals( held.claimedAt(), again.claimedAt() );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.CLAIM_EXPIRED, WorkEvent.CLAIMED ),
            store.itemEvents( held.id() ).stream().map( WorkEvent::type )
                .filter( t -> t.startsWith( "claim" ) ).toList() );
    }

    @Test
    void expiryLeavesOtherColumnsAlone() throws SQLException {
        final Item held = claims.claim( fx.ready( "keep me" ), fx.actorA, HOUR ).orElseThrow();
        store.addLink( held, "url", "https://example.com", WorkClaimFixtures.LENA );
        store.addComment( held, WorkClaimFixtures.LENA, "a comment", List.of() );
        final Item before = fx.reload( held );
        final List< Link > links = store.links( held.id() );
        final List< Comment > comments = store.comments( held.id() );
        fx.expireNow( held );
        claims.sweepExpiredClaims( 5 );
        final Item after = fx.reload( held );
        assertEquals( new Item( before.id(), before.projectId(), before.key(), before.level(), before.parentId(),
            before.productId(), before.kind(), before.title(), before.body(), WorkStatus.READY, before.priority(),
            before.rank(), before.dueOn(), before.assignee(), before.reporter(), before.autonomy(),
            before.doneMeans(), before.allowDirect(), before.version() + 1, before.createdAt(), after.updatedAt(),
            before.closedAt(), before.queue(), null, null, null, before.reporterAgentId(), null, null ), after );
        assertEquals( links, store.links( held.id() ) );
        assertEquals( comments, store.comments( held.id() ) );
    }

    @Test
    void claimableListsOnlyServedQueuesInGivenProjects() throws SQLException {
        final Project other = store.createProject( "OTH", "Other", "" );
        final Item mine = fx.ready( "mine" );
        final Item review = fx.create( fx.plat, "rev", WorkStatus.REVIEW, 3, null, null, "implementer" );
        fx.create( fx.plat, "notmine", WorkStatus.READY, 3, null, null, "reviewer" );
        fx.create( fx.plat, "person", WorkStatus.READY, 3, null, "bob", null );
        fx.create( fx.plat, "draft", WorkStatus.DRAFT, 3, null, null, "implementer" );
        final Item elsewhere = fx.create( other, "other", WorkStatus.READY, 3, null, null, "implementer" );
        claims.claim( fx.ready( "taken" ), fx.actorB, HOUR ).orElseThrow();
        assertEquals( Set.of( mine.id(), review.id() ), ids( claims.claimable( fx.a.id(), Set.of( fx.plat.id() ),
            null, 50 ) ) );
        assertEquals( Set.of( elsewhere.id() ), ids( claims.claimable( fx.a.id(), Set.of( other.id() ), null, 50 ) ) );
        assertEquals( 3, claims.claimable( fx.a.id(), Set.of( fx.plat.id(), other.id() ), null, 50 ).size() );
        // Any queue: the reviewer item shows when no agent is named, and a queue filter narrows.
        assertEquals( 3, claims.claimable( null, Set.of( fx.plat.id() ), null, 50 ).size() );
        assertEquals( 1, claims.claimable( null, Set.of( fx.plat.id() ), "reviewer", 50 ).size() );
        assertEquals( 0, claims.claimable( fx.a.id(), Set.of( fx.plat.id() ), "reviewer", 50 ).size() );
        assertTrue( claims.claimable( fx.a.id(), Set.of(), null, 50 ).isEmpty() );
        assertEquals( 1, claims.claimable( fx.a.id(), Set.of( fx.plat.id() ), null, 1 ).size() );
    }

    private static Set< Long > ids( final List< Item > items ) {
        return items.stream().map( Item::id ).collect( java.util.stream.Collectors.toSet() );
    }

    @Test
    void claimableOrdersByPriorityThenDueThenId() throws SQLException {
        final Item low = fx.create( fx.plat, "low", WorkStatus.READY, 4, LocalDate.of( 2026, 1, 1 ), null,
            "implementer" );
        final Item lateDue = fx.create( fx.plat, "late", WorkStatus.READY, 1, LocalDate.of( 2026, 3, 1 ), null,
            "implementer" );
        final Item noDue = fx.create( fx.plat, "nodue", WorkStatus.READY, 1, null, null, "implementer" );
        final Item earlyDue = fx.create( fx.plat, "early", WorkStatus.READY, 1, LocalDate.of( 2026, 2, 1 ), null,
            "implementer" );
        final Item noDue2 = fx.create( fx.plat, "nodue2", WorkStatus.READY, 1, null, null, "implementer" );
        assertEquals( List.of( earlyDue.id(), lateDue.id(), noDue.id(), noDue2.id(), low.id() ),
            claims.claimable( fx.a.id(), Set.of( fx.plat.id() ), null, 50 ).stream().map( Item::id ).toList() );
    }

    @Test
    void claimableExcludesBlocked() throws SQLException {
        final Item blocker = fx.create( fx.plat, "blocker", WorkStatus.REFINING, 3, null, null, null );
        final Item blocked = fx.ready( "blocked" );
        final Item free = fx.ready( "free" );
        store.addLink( blocked, "blocked_by", blocker.key(), WorkClaimFixtures.LENA );
        assertEquals( List.of( free.id() ), claims.claimable( fx.a.id(), Set.of( fx.plat.id() ), null, 50 )
            .stream().map( Item::id ).toList() );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET status = 'cancelled' WHERE id = " + blocker.id() );
        assertEquals( 2, claims.claimable( fx.a.id(), Set.of( fx.plat.id() ), null, 50 ).size() );
    }

    @Test
    void claimsHeldByAndActiveCount() throws SQLException {
        final Project other = store.createProject( "OTH", "Other", "" );
        store.putMember( other.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        final Item one = claims.claim( fx.ready( "one" ), fx.actorA, HOUR ).orElseThrow();
        final Item two = claims.claim( fx.create( other, "two", WorkStatus.READY, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        claims.claim( fx.ready( "three" ), fx.actorB, HOUR ).orElseThrow();
        assertEquals( 3, claims.activeClaimCount() );
        assertEquals( Set.of( one.id() ), ids( claims.claimsHeldBy( fx.a.id(), Set.of( fx.plat.id() ) ) ) );
        assertEquals( Set.of( one.id(), two.id() ),
            ids( claims.claimsHeldBy( fx.a.id(), Set.of( fx.plat.id(), other.id() ) ) ) );
        assertTrue( claims.claimsHeldBy( fx.a.id(), Set.of() ).isEmpty() );
    }

    @Test
    void removingAgentMemberReleasesItsClaimsInThatProjectOnly() throws SQLException {
        final Project other = store.createProject( "OTH", "Other", "" );
        store.putMember( other.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        final Item here = claims.claim( fx.ready( "here" ), fx.actorA, HOUR ).orElseThrow();
        final Item there = claims.claim( fx.create( other, "there", WorkStatus.READY, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        final Item bs = claims.claim( fx.ready( "b's" ), fx.actorB, HOUR ).orElseThrow();
        store.removeMember( fx.plat.id(), String.valueOf( fx.a.id() ), "agent", WorkClaimFixtures.LENA );
        assertNull( fx.reload( here ).claimedByAgent() );
        assertEquals( WorkStatus.READY, fx.reload( here ).status() );
        assertEquals( fx.a.id(), fx.reload( there ).claimedByAgent() );
        assertEquals( fx.b.id(), fx.reload( bs ).claimedByAgent() );
        final WorkEvent e = fx.events( here, WorkEvent.RELEASED ).get( 0 );
        assertEquals( "lena", e.actor() );
        assertEquals( "agent:" + fx.a.id(), e.fromValue() );
        assertEquals( "ready", e.toValue() );
        assertEquals( "access removed", e.note() );
    }

    @Test
    void demotingAgentToViewerReleasesItsClaims() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        store.putMember( fx.plat.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.VIEWER, WorkClaimFixtures.LENA );
        assertNull( fx.reload( held ).claimedByAgent() );
        assertEquals( "access removed", fx.events( held, WorkEvent.RELEASED ).get( 0 ).note() );
    }

    @Test
    void aMemberChangeThatKeepsAccessKeepsTheClaim() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        store.putMember( fx.plat.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        // A person losing access is not an agent losing access.
        store.putMember( fx.plat.id(), "bob", "user", WorkRole.VIEWER, WorkClaimFixtures.LENA );
        store.removeMember( fx.plat.id(), "bob", "user", WorkClaimFixtures.LENA );
        assertEquals( fx.a.id(), fx.reload( held ).claimedByAgent() );
        assertTrue( fx.events( held, WorkEvent.RELEASED ).isEmpty() );
    }

    @Test
    void retireAgentReleasesAllClaims() throws SQLException {
        final Project other = store.createProject( "OTH", "Other", "" );
        store.putMember( other.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        final Item one = claims.claim( fx.ready( "one" ), fx.actorA, HOUR ).orElseThrow();
        final Item two = claims.claim( fx.create( other, "two", WorkStatus.READY, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        final Item review = claims.claim( fx.create( fx.plat, "r", WorkStatus.REVIEW, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        final Item bs = claims.claim( fx.ready( "b's" ), fx.actorB, HOUR ).orElseThrow();
        final Instant at = Instant.parse( "2026-11-16T10:00:00Z" );
        claims.retireAgent( fx.a.id(), at, WorkClaimFixtures.LENA );
        assertEquals( at, store.agent( fx.a.id() ).orElseThrow().retiredAt() );
        for ( final Item i : List.of( one, two, review ) ) {
            assertNull( fx.reload( i ).claimedByAgent() );
            final WorkEvent e = fx.events( i, WorkEvent.RELEASED ).get( 0 );
            assertEquals( "agent retired", e.note() );
            assertEquals( "lena", e.actor() );
            assertEquals( "agent:" + fx.a.id(), e.fromValue() );
        }
        assertEquals( WorkStatus.READY, fx.reload( one ).status() );
        assertEquals( WorkStatus.REVIEW, fx.reload( review ).status() );
        assertEquals( fx.b.id(), fx.reload( bs ).claimedByAgent() );
        // Retiring again is harmless.
        claims.retireAgent( fx.a.id(), Instant.now(), WorkClaimFixtures.LENA );
        assertEquals( at, store.agent( fx.a.id() ).orElseThrow().retiredAt() );
    }

    @Test
    void retireRequiresAnAttributedActor() throws SQLException {
        final Actor ghost = new Actor( "ghost", Set.of(), false, Actor.Kind.AGENT, null, null );
        assertThrows( IllegalArgumentException.class, () -> claims.retireAgent( fx.a.id(), Instant.now(), ghost ) );
        assertThrows( IllegalArgumentException.class, () -> claims.release( fx.ready( "x" ), ghost, "n" ) );
        assertTrue( store.agent( fx.a.id() ).orElseThrow().active() );
    }

    /** Moves the claimed item to a status by a person, which ends the claim. */
    private void moveByPerson( final Item held, final WorkStatus status, final String queue ) throws SQLException {
        final Item cur = fx.reload( held );
        store.updateItem( cur, new Item( cur.id(), cur.projectId(), cur.key(), cur.level(), null, null, cur.kind(),
            cur.title(), cur.body(), status, cur.priority(), cur.rank(), null, null, cur.reporter(), null, null, null,
            cur.version(), null, null, null, queue, cur.claimedByAgent(), cur.claimedAt(), cur.claimExpiresAt(),
            null, null, null ), WorkClaimFixtures.LENA, null );
    }

    @Test
    void everyReleasedEventHasTheSameShape() throws SQLException {
        final List< Item > items = new ArrayList<>();
        final List< Long > holders = new ArrayList<>();
        final Item a1 = claims.claim( fx.ready( "move" ), fx.actorA, HOUR ).orElseThrow();
        moveByPerson( a1, WorkStatus.READY, "implementer" );
        items.add( a1 );
        holders.add( fx.a.id() );
        final Item a2 = claims.claim( fx.ready( "reassign" ), fx.actorA, HOUR ).orElseThrow();
        moveByPerson( a2, WorkStatus.READY, "reviewer" );
        items.add( a2 );
        holders.add( fx.a.id() );
        final Item a3 = claims.claim( fx.ready( "release by person" ), fx.actorA, HOUR ).orElseThrow();
        claims.release( a3, WorkClaimFixtures.LENA, "n" ).orElseThrow();
        items.add( a3 );
        holders.add( fx.a.id() );
        final Item a4 = claims.claim( fx.ready( "release by holder" ), fx.actorA, HOUR ).orElseThrow();
        claims.release( a4, fx.actorA, null ).orElseThrow();
        items.add( a4 );
        holders.add( fx.a.id() );
        final Item r = claims.claim( fx.create( fx.plat, "review", WorkStatus.REVIEW, 3, null, null, "implementer" ),
            fx.actorB, HOUR ).orElseThrow();
        claims.release( r, fx.actorB, "n" ).orElseThrow();
        items.add( r );
        holders.add( fx.b.id() );
        final Agent c = fx.agent( "agent-c", List.of( "implementer" ) );
        final Agent d = fx.agent( "agent-d", List.of( "implementer" ) );
        final Agent e = fx.agent( "agent-e", List.of( "implementer" ) );
        for ( final Agent x : List.of( c, d, e ) ) {
            store.putMember( fx.plat.id(), String.valueOf( x.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        }
        final Item removed = claims.claim( fx.ready( "removed" ), Actor.agent( c.id(), c.name() ), HOUR ).orElseThrow();
        store.removeMember( fx.plat.id(), String.valueOf( c.id() ), "agent", WorkClaimFixtures.LENA );
        items.add( removed );
        holders.add( c.id() );
        final Item demoted = claims.claim( fx.ready( "demoted" ), Actor.agent( d.id(), d.name() ), HOUR ).orElseThrow();
        store.putMember( fx.plat.id(), String.valueOf( d.id() ), "agent", WorkRole.VIEWER, WorkClaimFixtures.LENA );
        items.add( demoted );
        holders.add( d.id() );
        final Item retired = claims.claim( fx.ready( "retired" ), Actor.agent( e.id(), e.name() ), HOUR ).orElseThrow();
        claims.retireAgent( e.id(), Instant.now(), WorkClaimFixtures.LENA );
        items.add( retired );
        holders.add( e.id() );
        final Project arch = store.createProject( "ARCH", "Archived", "" );
        store.putMember( arch.id(), String.valueOf( fx.b.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        final Item archived = claims.claim( fx.create( arch, "archived", WorkStatus.READY, 3, null, null,
            "implementer" ), fx.actorB, HOUR ).orElseThrow();
        assertEquals( 1, store.archiveProject( arch.id(), Instant.now(), WorkClaimFixtures.LENA ) );
        items.add( archived );
        holders.add( fx.b.id() );
        assertEquals( 9, items.size() );
        for ( int i = 0; i < items.size(); i++ ) {
            final List< WorkEvent > ev = fx.events( items.get( i ), WorkEvent.RELEASED );
            assertEquals( 1, ev.size(), "released events for item " + i );
            assertEquals( "agent:" + holders.get( i ), ev.get( 0 ).fromValue(), "from " + i );
            assertEquals( fx.reload( items.get( i ) ).status().wire(), ev.get( 0 ).toValue(), "to " + i );
            assertTrue( ev.get( 0 ).note() != null && !ev.get( 0 ).note().isBlank(), "note " + i );
        }
    }

    @Test
    void archivingAProjectReleasesEveryClaimInItAndNoOther() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        store.putMember( ops.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        final Item one = claims.claim( fx.ready( "one" ), fx.actorA, HOUR ).orElseThrow();
        final Item two = claims.claim( fx.create( fx.plat, "two", WorkStatus.REVIEW, 3, null, null, "implementer" ),
            fx.actorB, HOUR ).orElseThrow();
        final Item other = claims.claim( fx.create( ops, "other", WorkStatus.READY, 3, null, null, "implementer" ),
            fx.actorA, HOUR ).orElseThrow();
        final Actor root = Actor.human( "root", Set.of(), true );

        assertEquals( 2, store.archiveProject( fx.plat.id(), Instant.now(), root ) );

        final Item oneNow = fx.reload( one );
        assertNull( oneNow.claimedByAgent() );
        assertEquals( WorkStatus.READY, oneNow.status() );
        assertEquals( one.version() + 1, oneNow.version() );
        assertEquals( WorkStatus.REVIEW, fx.reload( two ).status() );
        assertNull( fx.reload( two ).claimedByAgent() );
        for ( final Item i : List.of( one, two ) ) {
            final List< WorkEvent > ev = fx.events( i, WorkEvent.RELEASED );
            assertEquals( 1, ev.size() );
            assertEquals( "agent:" + ( i == one ? fx.a.id() : fx.b.id() ), ev.get( 0 ).fromValue() );
            assertEquals( fx.reload( i ).status().wire(), ev.get( 0 ).toValue() );
            assertEquals( "project archived", ev.get( 0 ).note() );
            assertEquals( "root", ev.get( 0 ).actor() );
            assertEquals( Actor.Kind.HUMAN, ev.get( 0 ).actorKind() );
        }
        assertEquals( fx.a.id(), fx.reload( other ).claimedByAgent(), "a claim in another project is untouched" );
        assertTrue( fx.events( other, WorkEvent.RELEASED ).isEmpty() );
        assertEquals( 0, store.archiveProject( fx.plat.id(), Instant.now(), root ), "archiving twice releases none" );
    }
}
