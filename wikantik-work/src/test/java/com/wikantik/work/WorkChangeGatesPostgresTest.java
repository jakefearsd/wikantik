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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Change records through the service, over real PostgreSQL: the life of a change and every gate on it. */
@RequiresPostgres
class WorkChangeGatesPostgresTest {

    private static final String A = "aaaaaaa1";
    private static final String B = "bbbbbbb2";

    private GateFixture f;
    private WorkChangesService changes;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        changes = f.svc.changes();
        item = f.task();
        f.status( f.lena, item, "ready" );
    }

    private static ChangeDraft branch( final String name, final String head ) {
        return new ChangeDraft( name, null, head, null, null );
    }

    private static ChangeDraft direct( final String sha ) {
        return new ChangeDraft( "main", null, null, null, sha );
    }

    /** A change the author agent opened at {@link #A} and asked a review of. */
    private Change inReview() throws SQLException {
        final Change c = changes.open( f.author, item.key(), branch( "work/PLAT-1-x", A ) );
        return changes.requestReview( f.author, c.id() );
    }

    private String denied( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkDeniedException.class, call ).reason();
    }

    private String conflict( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkConflictException.class, call ).reason();
    }

    private String invalid( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkValidationException.class, call ).field();
    }

    private List< String > events() throws SQLException {
        return f.svc.events( f.lena, item.key() ).stream()
            .sorted( java.util.Comparator.comparingLong( WorkEvent::id ) ).map( WorkEvent::type )
            .filter( t -> t.startsWith( "change" ) || t.startsWith( "review" ) ).toList();
    }

    // ---- the life of a change

    @Test
    void openWritesAChangeAndOneEvent() throws SQLException {
        final Change c = changes.open( f.author, item.key(), branch( "work/PLAT-1-x", A ) );
        assertEquals( ChangeState.OPEN, c.state() );
        assertEquals( "main", c.baseBranch(), "the product's default branch" );
        assertEquals( A, c.headSha() );
        assertEquals( f.author.agentId(), c.openedByAgentId() );
        assertEquals( "author", c.openedBy() );
        assertEquals( List.of( "change_opened" ), events() );
        assertEquals( List.of( c ), changes.list( f.mia, item.key() ) );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_opened' AND agent_id = "
            + f.author.agentId() + " AND from_value = 'change:" + c.id() + "'" ) );
    }

    @Test
    void aSecondLiveChangeOnTheSameBranchIsAValidationError() throws SQLException {
        changes.open( f.author, item.key(), branch( "work/PLAT-1-x", A ) );
        assertEquals( "branch", invalid( () -> changes.open( f.mia, item.key(), branch( "work/PLAT-1-x", B ) ) ) );
        assertNotNull( changes.open( f.mia, item.key(), branch( "work/PLAT-1-y", B ) ) );
    }

    @Test
    void badInputIsRefusedByField() {
        assertEquals( "branch", invalid( () -> changes.open( f.mia, item.key(), branch( "has space", A ) ) ) );
        assertEquals( "branch", invalid( () -> changes.open( f.mia, item.key(), branch( "", A ) ) ) );
        assertEquals( "headSha", invalid( () -> changes.open( f.mia, item.key(), branch( "b", "not-a-sha" ) ) ) );
        assertEquals( "headSha", invalid( () -> changes.open( f.mia, item.key(), branch( "b", "abc" ) ) ) );
        assertEquals( "reviewUrl", invalid( () -> changes.open( f.mia, item.key(),
            new ChangeDraft( "b", null, A, "javascript:alert(1)", null ) ) ) );
        assertEquals( "mergedSha", invalid( () -> changes.open( f.mia, item.key(), direct( "zzz" ) ) ) );
    }

    @Test
    void updateWithANewHeadClearsTheApprovalAndTheSameHeadKeepsIt() throws SQLException {
        final Change c = inReview();
        changes.review( f.reviewer, c.id(), true, "looks right" );
        final Change same = changes.update( f.author, c.id(), A, "https://example.test/pr/1" );
        assertTrue( same.approvedForHead() );
        assertEquals( "https://example.test/pr/1", same.reviewUrl() );
        final Change moved = changes.update( f.author, c.id(), B, null );
        assertFalse( moved.approvedForHead() );
        assertNull( moved.reviewedByAgentId() );
        assertEquals( "https://example.test/pr/1", moved.reviewUrl(), "an omitted field is left alone" );
        assertEquals( ChangeState.IN_REVIEW, moved.state() );
    }

    @Test
    void reviewRecordsTheReviewerAndHeadAndARejectionReturnsToOpen() throws SQLException {
        final Change c = inReview();
        final Change rejected = changes.review( f.reviewer, c.id(), false, "needs a test" );
        assertEquals( ChangeState.OPEN, rejected.state() );
        assertNull( rejected.reviewedHeadSha() );
        assertEquals( "not_in_review", conflict( () -> changes.review( f.reviewer, c.id(), true, null ) ) );
        changes.requestReview( f.author, c.id() );
        final Change approved = changes.review( f.reviewer, c.id(), true, null );
        assertEquals( A, approved.reviewedHeadSha() );
        assertEquals( f.reviewer.agentId(), approved.reviewedByAgentId() );
        assertEquals( ChangeState.IN_REVIEW, approved.state() );
        assertEquals( List.of( "change_opened", "review_requested", "change_reviewed", "review_requested",
            "change_reviewed" ), events() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_reviewed' "
            + "AND to_value = 'changes_requested' AND note = 'needs a test'" ) );
    }

    @Test
    void aReviewNeedsACommitToApprove() throws SQLException {
        final Change c = changes.open( f.author, item.key(), branch( "b", null ) );
        changes.requestReview( f.author, c.id() );
        assertEquals( "headSha", invalid( () -> changes.review( f.reviewer, c.id(), true, null ) ) );
    }

    @Test
    void abandonedAndMergedChangesRefuseEveryWrite() throws SQLException {
        final Change gone = changes.abandon( f.author, inReview().id(), "wrong approach" );
        assertEquals( ChangeState.ABANDONED, gone.state() );
        assertNotNull( gone.closedAt() );
        assertEquals( "change_closed", conflict( () -> changes.update( f.author, gone.id(), B, null ) ) );
        assertEquals( "change_closed", conflict( () -> changes.requestReview( f.author, gone.id() ) ) );
        assertEquals( "change_closed", conflict( () -> changes.review( f.reviewer, gone.id(), true, null ) ) );
        assertEquals( "change_closed", conflict( () -> changes.merge( f.mia, gone.id(), B ) ) );
        assertEquals( "change_closed", conflict( () -> changes.abandon( f.author, gone.id(), null ) ) );
        final Change merged = changes.open( f.mia, item.key(), direct( B ) );
        assertEquals( "change_closed", conflict( () -> changes.merge( f.mia, merged.id(), B ) ) );
    }

    // ---- the gates

    @Test
    void theOpenerAgentCannotReviewOrMergeItsOwnChange() throws SQLException {
        final Change c = inReview();
        assertEquals( "An agent may not review its own change.",
            denied( () -> changes.review( f.author, c.id(), true, null ) ) );
        changes.review( f.reviewer, c.id(), true, null );
        assertEquals( "An agent may not merge its own change.", denied( () -> changes.merge( f.author, c.id(), A ) ) );
        assertEquals( ChangeState.IN_REVIEW, changes.list( f.lena, item.key() ).get( 0 ).state() );
    }

    @Test
    void theApprovingAgentMergesAtDelegatedAndAThirdAgentCannot() throws SQLException {
        final Change c = inReview();
        assertEquals( "The agent that approved this change merges it.",
            denied( () -> changes.merge( f.reviewer, c.id(), A ) ), "no approval yet" );
        changes.review( f.reviewer, c.id(), true, null );
        final Agent third = f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "third", "", "", List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "third", "agent", WorkRole.MEMBER );
        assertEquals( "The agent that approved this change merges it.",
            denied( () -> changes.merge( Actor.agent( third.id(), "third", f.admin ), c.id(), A ) ) );
        final Change merged = changes.merge( f.reviewer, c.id(), A );
        assertEquals( ChangeState.MERGED, merged.state() );
        assertEquals( MergeKind.REVIEWED, merged.mergeKind() );
        assertEquals( A, merged.mergedSha() );
        assertEquals( f.reviewer.agentId(), merged.mergedByAgentId() );
        assertNotNull( merged.closedAt() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_merged' "
            + "AND to_value = 'reviewed' AND note = '" + A + "'" ) );
    }

    @Test
    void noAgentMergesAtSupervised() throws SQLException {
        f.level( item, Autonomy.SUPERVISED );
        final Change c = inReview();
        changes.review( f.reviewer, c.id(), true, null );
        assertEquals( "A person merges at the supervised level.",
            denied( () -> changes.merge( f.reviewer, c.id(), A ) ) );
        assertEquals( MergeKind.REVIEWED, changes.merge( f.mia, c.id(), A ).mergeKind() );
    }

    @Test
    void aNewCommitAfterApprovalBlocksTheMerge() throws SQLException {
        final Change c = inReview();
        changes.review( f.reviewer, c.id(), true, null );
        changes.update( f.author, c.id(), B, null );
        assertEquals( "The agent that approved this change merges it.",
            denied( () -> changes.merge( f.reviewer, c.id(), B ) ) );
        changes.review( f.reviewer, c.id(), true, null );
        assertEquals( "not_approved", conflict( () -> changes.merge( f.reviewer, c.id(), A ) ),
            "the merged commit must be the approved one" );
        assertEquals( B, changes.merge( f.reviewer, c.id(), B ).mergedSha() );
    }

    @Test
    void loweringTheLevelAfterApprovalRefusesTheMerge() throws SQLException {
        final Change c = inReview();
        changes.review( f.reviewer, c.id(), true, null );
        f.ceiling( Autonomy.SUPERVISED );
        assertEquals( "A person merges at the supervised level.",
            denied( () -> changes.merge( f.reviewer, c.id(), A ) ) );
    }

    @Test
    void aPersonMergesTheirOwnChangeUnreviewed() throws SQLException {
        final Change c = changes.open( f.mia, item.key(), branch( "work/PLAT-1-mine", A ) );
        final Change merged = changes.merge( f.mia, c.id(), A );
        assertEquals( MergeKind.UNREVIEWED, merged.mergeKind() );
        assertEquals( "mia", merged.mergedBy() );
        assertNull( merged.mergedByAgentId() );
    }

    @Test
    void aPersonOpensAndMergesInOneCall() throws SQLException {
        final Change c = changes.open( f.mia, item.key(), direct( A ) );
        assertEquals( ChangeState.MERGED, c.state() );
        assertEquals( MergeKind.DIRECT, c.mergeKind() );
        assertEquals( A, c.mergedSha() );
        assertEquals( List.of( "change_merged" ), events() );
    }

    @Test
    void anAgentsDirectCommitNeedsAllowDirect() throws SQLException {
        final String refusal = "Direct commits by an agent need a lead to allow them on this item or its epic.";
        assertEquals( refusal, denied( () -> changes.open( f.author, item.key(), direct( A ) ) ) );
        f.set( f.lena, item, "allowDirect", true );
        assertEquals( MergeKind.DIRECT, changes.open( f.author, item.key(), direct( A ) ).mergeKind() );
        f.level( item, Autonomy.SUPERVISED );
        assertEquals( refusal, denied( () -> changes.open( f.author, item.key(), direct( B ) ) ),
            "the flag gives an agent nothing at supervised" );
    }

    @Test
    void allowDirectOnTheEpicCoversItsStories() throws SQLException {
        final Item epic = f.draft( WorkLevel.EPIC, null );
        f.set( f.lena, epic, "allowDirect", true );
        final Item story = f.draft( WorkLevel.STORY, epic.key() );
        f.status( f.lena, story, "ready" );
        assertEquals( MergeKind.DIRECT, changes.open( f.author, story.key(), direct( A ) ).mergeKind() );
    }

    @Test
    void anAgentIsFencedByAnotherAgentsClaim() throws SQLException {
        f.svc.claims().claim( f.reviewer, item.key() );
        assertEquals( "This item is claimed by another agent.",
            denied( () -> changes.open( f.author, item.key(), branch( "b", A ) ) ) );
        assertNotNull( changes.open( f.reviewer, item.key(), branch( "b", A ) ) );
        assertNotNull( changes.open( f.mia, item.key(), branch( "c", A ) ), "a person is never fenced" );
    }

    @Test
    void aViewerCannotOpenAChange() throws SQLException {
        f.svc.putMember( f.admin, "PLAT", "vera", "user", WorkRole.VIEWER );
        final Actor vera = Actor.human( "vera", Set.of(), false );
        assertThrows( WorkDeniedException.class, () -> changes.open( vera, item.key(), branch( "b", A ) ) );
        assertTrue( changes.list( vera, item.key() ).isEmpty() );
    }

    @Test
    void anItemWithNoProductHasNoChanges() throws SQLException {
        final Item bare = f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, null,
            WorkKind.ADMIN, "Admin work", "", null, null, null, null ) ).item();
        assertEquals( "productKey", invalid( () -> changes.open( f.mia, bare.key(), branch( "b", A ) ) ) );
    }

    // ---- what a live change holds back

    @Test
    void anItemCannotBeDoneWithALiveChange() throws SQLException {
        final Change c = changes.open( f.mia, item.key(), branch( "b", A ) );
        assertEquals( "open_changes", conflict( () -> f.status( f.mia, item, "done" ) ) );
        assertEquals( "open_changes", conflict( () -> f.handoff( f.mia, item, WorkStatus.DONE, "lena", null ) ) );
        assertEquals( WorkStatus.REVIEW, f.status( f.mia, item, "review" ).status(), "other moves are open" );
        changes.merge( f.mia, c.id(), A );
        assertEquals( WorkStatus.DONE, f.status( f.mia, item, "done" ).status() );
        assertEquals( "status", invalid( () -> changes.open( f.mia, item.key(), branch( "late", A ) ) ),
            "a closed item takes no new change" );
    }

    @Test
    void theProductCannotChangeUnderALiveChange() throws SQLException {
        f.svc.createProduct( f.admin, new Product( 0, "jakemon", "Jakemon", null, "main", null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        final Change c = changes.open( f.mia, item.key(), branch( "b", A ) );
        assertEquals( "open_changes", conflict( () -> f.set( f.mia, item, "productKey", "jakemon" ) ) );
        changes.abandon( f.mia, c.id(), null );
        assertNotNull( f.set( f.mia, item, "productKey", "jakemon" ).productId() );
        final long madeAgainst = changes.list( f.mia, item.key() ).get( 0 ).productId();
        assertEquals( "wikantik", f.svc.listProducts( f.admin ).stream().filter( p -> p.id() == madeAgainst )
            .findFirst().orElseThrow().key(),
            "a closed change keeps the product it was made against" );
    }

    @Test
    void anExpiredClaimLeavesTheChangeAttached() throws SQLException {
        f.svc.claims().claim( f.author, item.key() );
        final Change c = changes.open( f.author, item.key(), branch( "work/PLAT-1-x", A ) );
        WorkAgentFixtures.exec( f.ds, "UPDATE work_item SET claim_expires_at = NOW() - INTERVAL '1 minute'" );
        assertEquals( 1, f.svc.claims().sweepExpiredClaims() );
        f.svc.claims().claim( f.reviewer, item.key() );
        assertEquals( List.of( c.id() ), changes.list( f.reviewer, item.key() ).stream().map( Change::id ).toList() );
        assertNotNull( changes.update( f.reviewer, c.id(), B, null ), "the next agent resumes the branch" );
    }

    // ---- hidden is not found

    @Test
    void aChangeOnAnItemTheCallerCannotSeeIsNotFound() throws SQLException {
        final Change c = inReview();
        final Actor stranger = Actor.human( "sam", Set.of(), false );
        final Agent orphanRow = f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "orphan", "", "", List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "orphan", "agent", WorkRole.MEMBER );
        final Actor underStranger = Actor.agent( orphanRow.id(), "orphan", stranger );
        for ( final Actor who : List.of( stranger, underStranger ) ) {
            assertThrows( WorkNotFoundException.class, () -> changes.list( who, item.key() ) );
            assertThrows( WorkNotFoundException.class, () -> changes.open( who, item.key(), branch( "z", A ) ) );
            for ( final long id : new long[] { c.id(), 999_999L } ) {
                assertThrows( WorkNotFoundException.class, () -> changes.update( who, id, B, null ) );
                assertThrows( WorkNotFoundException.class, () -> changes.requestReview( who, id ) );
                assertThrows( WorkNotFoundException.class, () -> changes.review( who, id, true, null ) );
                assertThrows( WorkNotFoundException.class, () -> changes.merge( who, id, A ) );
                assertThrows( WorkNotFoundException.class, () -> changes.abandon( who, id, null ) );
            }
            assertThrows( WorkNotFoundException.class, () -> changes.counts( who, "PLAT" ) );
        }
        assertThrows( WorkNotFoundException.class, () -> changes.merge( f.mia, 999_999L, A ) );
    }

    @Test
    void countsSayWhatIsLivePerItem() throws SQLException {
        final Change one = changes.open( f.mia, item.key(), branch( "one", A ) );
        changes.open( f.mia, item.key(), branch( "two", A ) );
        changes.requestReview( f.mia, one.id() );
        changes.open( f.mia, item.key(), direct( B ) );
        final WorkChangesService.ChangeCounts counts = changes.counts( f.mia, "PLAT" ).get( item.key() );
        assertEquals( 2, counts.live() );
        assertEquals( 1, counts.inReview() );
        assertEquals( 1, changes.counts( f.mia, "PLAT" ).size() );
    }
}
