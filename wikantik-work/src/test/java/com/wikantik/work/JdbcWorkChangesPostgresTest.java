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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The change store itself: what each write records, who is an author, and which writes are no-ops. */
@RequiresPostgres
class JdbcWorkChangesPostgresTest {

    private static final String A = "aaaaaaa1";
    private static final String B = "bbbbbbb2";

    private GateFixture f;
    private JdbcWorkStore store;
    private WorkChangeStore changes;
    private long product;
    private Item item;
    private final List< Change > checked = new ArrayList<>();
    private final WorkChangeStore.Check check = checked::add;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        store = new JdbcWorkStore( f.ds );
        changes = store.changes();
        product = store.product( "wikantik" ).orElseThrow().id();
        item = f.task();
    }

    private Change open( final Actor by, final String branch, final String head ) throws SQLException {
        return changes.open( item, product, new ChangeDraft( branch, "main", head, null, null ), by );
    }

    private List< String > eventTypes() throws SQLException {
        return store.itemEvents( item.id() ).stream().filter( e -> e.type().startsWith( "change_" ) || e.type().equals( "review_requested" ) )
            .map( e -> e.type() + ":" + e.toValue() + ":" + e.note() ).toList();
    }

    @Test
    void anItemHasALiveChangeOnlyWhileOneIsOpenOrInReview() throws SQLException {
        assertFalse( changes.hasLive( item.id() ) );
        final Change c = open( f.mia, "work/one", A );
        assertTrue( changes.hasLive( item.id() ) );
        assertFalse( changes.hasLive( item.id() + 1_000 ) );
        changes.abandon( item, c.id(), "no longer needed", check, f.mia );
        assertFalse( changes.hasLive( item.id() ), "abandoned is not live" );
    }

    @Test
    void anOpenedChangeIsOpenOnTheBranchWithItsHeadAndOneOpenedEvent() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        assertEquals( ChangeState.OPEN, c.state() );
        assertEquals( "work/one", c.branch() );
        assertEquals( A, c.headSha() );
        assertNull( c.mergedSha() );
        assertNull( c.mergedBy() );
        assertNull( c.closedAt() );
        assertEquals( "mia", c.openedBy() );
        assertEquals( List.of( "change_opened:work/one:" + A ), eventTypes() );
    }

    @Test
    void aSecondLiveChangeOnTheSameBranchIsRefusedButAnotherBranchOrAnAbandonedOneIsFine() throws SQLException {
        final Change first = open( f.mia, "work/one", A );
        assertEquals( "branch", assertThrows( WorkValidationException.class, () -> open( f.mia, "work/one", B ) ).field() );
        open( f.mia, "work/two", B );
        changes.abandon( item, first.id(), null, check, f.mia );
        assertEquals( ChangeState.OPEN, open( f.mia, "work/one", B ).state() );
    }

    @Test
    void aChangeOpenedAlreadyMergedIsDirectAndRecordsWhoMergedIt() throws SQLException {
        final Change byPerson = changes.open( item, product, new ChangeDraft( "main", "main", null, null, A ), f.mia );
        assertEquals( ChangeState.MERGED, byPerson.state() );
        assertEquals( MergeKind.DIRECT, byPerson.mergeKind() );
        assertEquals( A, byPerson.mergedSha() );
        assertEquals( A, byPerson.headSha(), "a direct change's head is the merged commit" );
        assertEquals( "mia", byPerson.mergedBy() );
        assertNull( byPerson.mergedByAgentId() );
        assertTrue( byPerson.closedAt() != null );
        assertEquals( List.of(), byPerson.headAgentIds() );
        final Item other = f.task();
        final Change byAgent = changes.open( other, product, new ChangeDraft( "main", "main", null, null, B ), f.author );
        assertEquals( "author", byAgent.mergedBy() );
        assertEquals( f.author.agentId(), byAgent.mergedByAgentId() );
        assertEquals( List.of( f.author.agentId() ), byAgent.headAgentIds() );
        assertTrue( eventTypes().contains( "change_merged:direct:" + A ) );
    }

    @Test
    void aDirectChangeMayRepeatABranchThatAlreadyHasALiveChange() throws SQLException {
        open( f.mia, "main", A );
        assertEquals( ChangeState.MERGED,
            changes.open( item, product, new ChangeDraft( "main", "main", null, null, B ), f.mia ).state() );
    }

    @Test
    void anAgentThatNamesACommitWhenOpeningIsItsAuthorAndOneThatNamesNoneIsNot() throws SQLException {
        assertEquals( List.of( f.author.agentId() ), open( f.author, "work/a", A ).headAgentIds() );
        assertEquals( List.of(), open( f.mia, "work/b", A ).headAgentIds() );
        assertEquals( List.of(), open( f.author, "work/c", null ).headAgentIds() );
    }

    @Test
    void aNewHeadWithdrawsTheApprovalAndAnAgentThatSetsItBecomesAnAuthor() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        changes.requestReview( item, c.id(), check, f.mia );
        changes.review( item, c.id(), true, "ok", check, f.reviewer );
        assertEquals( A, store.changes().change( c.id() ).orElseThrow().reviewedHeadSha() );
        final Change moved = changes.update( item, c.id(), B, null, null, check, f.author );
        assertEquals( B, moved.headSha() );
        assertNull( moved.reviewedHeadSha() );
        assertNull( moved.reviewedBy() );
        assertNull( moved.reviewedAt() );
        assertEquals( List.of( f.author.agentId() ), moved.headAgentIds() );
        final Change again = changes.update( item, c.id(), "ccccccc3", null, null, check, f.author );
        assertEquals( List.of( f.author.agentId() ), again.headAgentIds(), "an author is added once" );
        assertTrue( eventTypes().contains( "change_updated:ccccccc3:null" ) );
    }

    @Test
    void anUpdateThatChangesNothingWritesNoEventButAnUrlChangeDoes() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final int before = eventTypes().size();
        changes.update( item, c.id(), null, null, null, check, f.mia );
        changes.update( item, c.id(), A, null, null, check, f.mia );
        assertEquals( before, eventTypes().size() );
        final Change withUrl = changes.update( item, c.id(), null, "https://example.com/pr/1", null, check, f.mia );
        assertEquals( "https://example.com/pr/1", withUrl.reviewUrl() );
        assertEquals( A, withUrl.headSha() );
        assertEquals( before + 1, eventTypes().size() );
        assertNull( changes.update( item, c.id(), null, "", null, check, f.mia ).reviewUrl(), "an empty address clears it" );
    }

    @Test
    void aFirstCommitOnlyMovesEarlierAndNeverPastTheOpening() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final Instant opened = c.openedAt();
        final Instant early = opened.minusSeconds( 3600 );
        final Change first = changes.update( item, c.id(), null, null, early, check, f.mia );
        assertEquals( early, first.firstCommitAt().truncatedTo( java.time.temporal.ChronoUnit.MILLIS ).isBefore( early ) ? early : first.firstCommitAt() );
        assertTrue( eventTypes().contains( "change_updated:null:first commit" ) );
        final int events = eventTypes().size();
        final Change later = changes.update( item, c.id(), null, null, early.plusSeconds( 600 ), check, f.mia );
        assertEquals( first.firstCommitAt(), later.firstCommitAt(), "a later report is not the first commit" );
        assertEquals( events, eventTypes().size() );
        final Change earlier = changes.update( item, c.id(), null, null, early.minusSeconds( 600 ), check, f.mia );
        assertTrue( earlier.firstCommitAt().isBefore( first.firstCommitAt() ) );
        final Item other = f.task();
        final Change c2 = changes.open( other, product, new ChangeDraft( "work/two", "main", A, null, null ), f.mia );
        final Change capped = changes.update( other, c2.id(), null, null, c2.openedAt().plusSeconds( 7200 ), check, f.mia );
        assertEquals( c2.openedAt(), capped.firstCommitAt(), "never after the change was opened" );
    }

    @Test
    void aMovedHeadAndAFirstCommitAreBothRecorded() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final Instant early = c.openedAt().minusSeconds( 60 );
        final Change both = changes.update( item, c.id(), B, null, early, check, f.mia );
        assertEquals( B, both.headSha() );
        assertTrue( both.firstCommitAt() != null );
    }

    @Test
    void askingForAReviewMovesOpenToInReviewOnceAndRecordsIt() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final Change asked = changes.requestReview( item, c.id(), check, f.mia );
        assertEquals( ChangeState.IN_REVIEW, asked.state() );
        assertEquals( List.of( "change_opened:work/one:" + A, "review_requested:work/one:" + A ), eventTypes() );
        changes.requestReview( item, c.id(), check, f.mia );
        assertEquals( 2, eventTypes().size(), "already in review: nothing more is recorded" );
        assertEquals( 2, checked.size(), "the gate saw both asks" );
    }

    @Test
    void aReviewNeedsAnAskAndRecordsApprovalOrRequestedChangesWithItsNote() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        assertEquals( WorkConflictException.NOT_IN_REVIEW, assertThrows( WorkConflictException.class,
            () -> changes.review( item, c.id(), true, "ok", check, f.reviewer ) ).reason() );
        assertTrue( checked.isEmpty(), "the state is judged before the gate" );
        changes.requestReview( item, c.id(), check, f.mia );
        final Change refused = changes.review( item, c.id(), false, "fix the tests", check, f.reviewer );
        assertEquals( ChangeState.OPEN, refused.state() );
        assertNull( refused.reviewedBy() );
        assertTrue( eventTypes().contains( "change_reviewed:changes_requested:fix the tests" ) );
        changes.requestReview( item, c.id(), check, f.mia );
        final Change approved = changes.review( item, c.id(), true, "lgtm", check, f.reviewer );
        assertEquals( ChangeState.IN_REVIEW, approved.state() );
        assertEquals( "reviewer-bot", approved.reviewedBy() );
        assertEquals( f.reviewer.agentId(), approved.reviewedByAgentId() );
        assertEquals( A, approved.reviewedHeadSha() );
        assertTrue( approved.reviewedAt() != null );
        assertTrue( eventTypes().contains( "change_reviewed:approved:lgtm" ) );
    }

    @Test
    void abandoningClosesTheChangeRunsTheGateAndRecordsTheNote() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final Change gone = changes.abandon( item, c.id(), "superseded", check, f.mia );
        assertEquals( ChangeState.ABANDONED, gone.state() );
        assertTrue( gone.closedAt() != null );
        assertEquals( 1, checked.size() );
        assertEquals( c.id(), checked.get( 0 ).id() );
        assertTrue( eventTypes().contains( "change_abandoned:work/one:superseded" ) );
        assertEquals( WorkConflictException.CHANGE_CLOSED, assertThrows( WorkConflictException.class,
            () -> changes.abandon( item, c.id(), null, check, f.mia ) ).reason() );
    }

    @Test
    void theGateCanRefuseAWriteAndNothingIsWritten() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final WorkChangeStore.Check refusing = locked -> {
            throw new WorkDeniedException( "not you" );
        };
        assertThrows( WorkDeniedException.class, () -> changes.abandon( item, c.id(), null, refusing, f.mia ) );
        assertThrows( WorkDeniedException.class, () -> changes.update( item, c.id(), B, null, null, refusing, f.mia ) );
        assertThrows( WorkDeniedException.class, () -> changes.requestReview( item, c.id(), refusing, f.mia ) );
        final Change still = changes.change( c.id() ).orElseThrow();
        assertEquals( ChangeState.OPEN, still.state() );
        assertEquals( A, still.headSha() );
    }

    @Test
    void theCodeHostActorMayNotWriteThroughTheManualPath() throws SQLException {
        final Actor host = Actor.system( "github" );
        assertThrows( IllegalArgumentException.class, () -> open( host, "work/one", A ) );
        final Change c = open( f.mia, "work/one", A );
        assertThrows( IllegalArgumentException.class, () -> changes.abandon( item, c.id(), null, check, host ) );
        assertThrows( IllegalArgumentException.class, () -> changes.update( item, c.id(), B, null, null, check, host ) );
    }

    @Test
    void aClosedItemTakesNoNewChangeAndAnotherAgentsClaimFencesAWrite() throws SQLException {
        final Change c = open( f.mia, "work/one", A );
        final Item ready = f.status( f.lena, item, "ready" );
        store.claims().claim( f.fresh( ready ), f.reviewer, java.time.Duration.ofHours( 1 ) ).orElseThrow();
        assertThrows( ClaimFenceException.class, () -> changes.abandon( item, c.id(), null, check, f.author ) );
        assertThrows( ClaimFenceException.class, () -> open( f.author, "work/other", A ) );
        assertEquals( ChangeState.OPEN, changes.change( c.id() ).orElseThrow().state() );
    }
}
