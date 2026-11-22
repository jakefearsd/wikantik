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
import java.util.stream.Collectors;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record a pull request's deliveries leave is the same whatever order they arrive in, and a replayed one changes
 * nothing: heads and approvals go by the host's time, and the approval that decides a merge is the one on the commit
 * that was merged. The lifecycle register and the full permutation sets are in
 * {@link WorkCodeHostConvergencePostgresTest} and {@link WorkCodeHostLifecyclePostgresTest}.
 */
@RequiresPostgres
class WorkCodeHostOrderPostgresTest {

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;
    private String branch;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        f.emails.put( "mia@example.com", "mia" );
        d = f.delivery( "github" );
        item = f.task();
        branch = "work/" + item.key() + "-fix";
    }

    private WorkCodeHostService.Applied apply( final CodeEvent... events ) throws SQLException {
        return f.hooks.apply( d, List.of( events ) );
    }

    /** What the record says, change by change (sorted, so ids do not count): everything a reader or a metric reads. */
    private String record() throws SQLException {
        return f.changes( item ).stream().map( c -> String.join( "/", c.state().wire(), String.valueOf( c.reviewNumber() ),
            c.headSha(), String.valueOf( c.mergeKind() ), String.valueOf( c.policyFlag() ), String.valueOf( c.reviewedBy() ),
            String.valueOf( c.reviewedHeadSha() ), String.valueOf( c.reviewedAt() ), String.valueOf( c.mergedSha() ),
            String.valueOf( c.mergedBy() ), String.valueOf( c.openedBy() ), String.valueOf( c.firstCommitAt() ),
            String.valueOf( c.openedAt() ), String.valueOf( c.closedAt() ), String.valueOf( c.reviewUrl() ) ) )
            .sorted().collect( Collectors.joining( " | " ) );
    }

    private static List< int[] > permutations( final int n ) {
        final List< int[] > out = new ArrayList<>();
        permute( new int[ n ], new boolean[ n ], 0, out );
        return out;
    }

    private static void permute( final int[] cur, final boolean[] used, final int at, final List< int[] > out ) {
        if ( at == cur.length ) {
            out.add( cur.clone() );
            return;
        }
        for ( int i = 0; i < cur.length; i++ ) {
            if ( !used[ i ] ) {
                used[ i ] = true;
                cur[ at ] = i;
                permute( cur, used, at + 1, out );
                used[ i ] = false;
            }
        }
    }

    /** Applies the events in every order, each from a fresh database; returns the record of the first order. */
    private String sameRecordInEveryOrder( final List< CodeEvent > events, final java.util.function.Consumer< String > check )
            throws SQLException {
        String expected = null;
        for ( final int[] order : permutations( events.size() ) ) {
            setUp();
            for ( final int i : order ) {
                apply( events.get( i ) );
            }
            final String got = record();
            check.accept( got );
            if ( expected == null ) {
                expected = got;
            }
            assertEquals( expected, got, "order " + java.util.Arrays.toString( order ) );
        }
        return expected;
    }

    // ---- I2: the head and the approval on it go by the host's time

    private CodeEvent pushB() {
        return CodeEvents.push( branch, B, at( "11:35" ), commit( B, "more", at( "11:34" ), true, ANA ) );
    }

    @Test
    void aPushThatOvertookTheOpeningKeepsItsHeadAndApprovalInEveryOrder() throws SQLException {
        final String record = sameRecordInEveryOrder( List.of( CodeEvents.opened( branch, "12", A, at( "11:30" ) ), pushB(),
            CodeEvents.approved( branch, "12", B, at( "11:45" ), MIA ),
            CodeEvents.merged( branch, "12", B, M, at( "11:50" ), ANA ) ), got -> {
                assertTrue( got.startsWith( "merged/12/" + B + "/REVIEWED/null/mia/" + B + "/" ), got );
            } );
        assertEquals( "merged/12/" + B + "/REVIEWED/null/mia/" + B + "/2026-03-01T11:45:00Z/" + M
            + "/ana/ana/2026-03-01T11:30:00Z/2026-03-01T11:30:00Z/2026-03-01T11:50:00Z/https://github.com/acme/wk/pull/12",
            record, "first commit 11:34 came after the opening at 11:30, so the opening bounds it" );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change WHERE policy_flag IS NOT NULL" ) );
    }

    @Test
    void anApprovalOfACommitSinceReplacedIsUnreviewedInEveryOrder() throws SQLException {
        sameRecordInEveryOrder( List.of( CodeEvents.opened( branch, "12", A, at( "11:30" ) ),
            CodeEvents.approved( branch, "12", A, at( "11:40" ), MIA ),
            CodeEvents.push( branch, B, at( "11:45" ), commit( B, "more", at( "11:44" ), true, ANA ) ),
            CodeEvents.merged( branch, "12", B, M, at( "11:50" ), ANA ) ), got -> {
                assertTrue( got.startsWith( "merged/12/" + B + "/UNREVIEWED/unreviewed/null/null/" ), got );
            } );
    }

    @Test
    void aLateApprovalOfTheMergedCommitIsStillNotTheAuthorsOwn() throws SQLException {
        apply( CodeEvents.merged( branch, "12", B, M, at( "11:50" ), ANA ) );
        assertEquals( "unreviewed", f.only( item ).policyFlag() );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ),
            apply( CodeEvents.approved( branch, "12", B, at( "11:50" ), ANA ) ), "the author approving their own" );
        assertEquals( new WorkCodeHostService.Applied( 1, 0, 0 ),
            apply( CodeEvents.approved( branch, "12", B, at( "11:55" ), MIA ) ), "stamped after the merge: on record" );
        assertEquals( "unreviewed", f.only( item ).policyFlag() );
        f.svc.changes().acknowledgeFlag( f.lena, f.only( item ).id() );
        apply( CodeEvents.approved( branch, "12", B, at( "11:45" ), MIA ) );
        assertEquals( "unreviewed", f.only( item ).policyFlag(), "a lead's acknowledgement is never undone" );
        assertEquals( MergeKind.UNREVIEWED, f.only( item ).mergeKind() );
    }

    // ---- I3: host times are bounded at one place

    @Test
    void anyInstantADeliveryCanCarryIsBoundedNeverThrown() throws SQLException {
        f.clock.set( at( "12:00" ) );
        final Instant[] absurd = { Instant.MIN, Instant.parse( "0001-01-01T00:00:00Z" ),
            Instant.parse( "1969-12-31T23:59:59Z" ) };
        for ( final Instant t : absurd ) {
            setUp();
            f.clock.set( at( "12:00" ) );
            final CodeEvent push = CodeEvents.push( branch, A, t, commit( A, "wip", t, true, ANA ) );
            assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( push ), "no usable time is no event: " + t );
            assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( CodeEvents.closed( branch, "12", A, t ) ) );
            assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ), t.toString() );
        }
        setUp();
        f.clock.set( at( "12:00" ) );
        assertEquals( 1, apply( CodeEvents.push( branch, A, Instant.MAX, commit( A, "wip", Instant.MAX, true, ANA ) ) )
            .applied() );
        final Change c = f.only( item );
        assertEquals( at( "12:00" ), c.openedAt(), "a future time is the work clock's now" );
        assertEquals( at( "12:00" ), c.firstCommitAt() );
        assertEquals( 1, apply( CodeEvents.closed( branch, "12", A, Instant.MAX ) ).applied() );
        assertFalse( f.only( item ).closedAt().isBefore( f.only( item ).openedAt() ) );
    }

    @Test
    void aCloseNeverPrecedesTheOpeningAndAFirstCommitNeverFollowsIt() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.push( branch, B, at( "11:40" ), commit( B, "later", at( "11:35" ), true, ANA ) ) );
        assertEquals( at( "11:30" ), f.only( item ).firstCommitAt(), "a commit after the opening is not the first" );
        final Item other = f.task();
        final String agentBranch = "work/" + other.key() + "-x";
        final Change mine = f.svc.changes().open( f.author, other.key(), new ChangeDraft( agentBranch, null, A, null, null ) );
        apply( CodeEvents.closed( agentBranch, "40", A, at( "11:00" ) ) );
        final Change agents = f.store.changes().change( mine.id() ).orElseThrow();
        assertEquals( ChangeState.ABANDONED, agents.state() );
        assertFalse( agents.closedAt().isBefore( agents.openedAt() ),
            "a close the host stamped before the agent opened the change is not earlier than the opening" );
    }

    @Test
    void anOldButValidCommitTimeIsRecordedAsGiven() throws SQLException {
        apply( CodeEvents.push( branch, A, at( "11:00" ), commit( A, "cherry-picked", Instant.parse( "1999-01-01T00:00:00Z" ),
            true, ANA ) ) );
        assertEquals( Instant.parse( "1999-01-01T00:00:00Z" ), f.only( item ).firstCommitAt(),
            "kept as given: Part 6 caps the lead time it derives" );
    }

    // ---- I6: a change belongs to one pull request

    @Test
    void twoPullRequestsFromOneBranchKeepTheirOwnChanges() throws SQLException {
        apply( CodeEvents.opened( branch, "5", A, at( "11:00" ) ) );
        apply( CodeEvents.opened( branch, "6", A, at( "11:05" ) ) );
        assertEquals( 2, f.changes( item ).size(), "a backport from the same branch is its own change" );
        apply( CodeEvents.merged( branch, "6", A, M, at( "11:10" ), ANA ) );
        apply( CodeEvents.closed( branch, "5", A, at( "11:20" ) ) );
        final List< Change > all = f.changes( item );
        assertEquals( "5", all.get( 0 ).reviewNumber() );
        assertEquals( ChangeState.ABANDONED, all.get( 0 ).state() );
        assertEquals( "6", all.get( 1 ).reviewNumber() );
        assertEquals( ChangeState.MERGED, all.get( 1 ).state() );
        assertEquals( M, all.get( 1 ).mergedSha() );
    }

    @Test
    void aClosedPullRequestReplacedFromTheSameBranchInAnyOrder() throws SQLException {
        sameRecordInEveryOrder( List.of( CodeEvents.opened( branch, "5", A, at( "11:00" ) ),
            CodeEvents.closed( branch, "5", A, at( "11:20" ) ), CodeEvents.opened( branch, "6", A, at( "11:25" ) ),
            CodeEvents.merged( branch, "6", A, M, at( "11:30" ), ANA ) ), got -> { } );
        final List< Change > all = f.changes( item );
        assertEquals( ChangeState.ABANDONED, all.stream().filter( c -> "5".equals( c.reviewNumber() ) ).findFirst()
            .orElseThrow().state() );
        assertEquals( ChangeState.MERGED, all.stream().filter( c -> "6".equals( c.reviewNumber() ) ).findFirst()
            .orElseThrow().state() );
    }

    @Test
    void anAgentsChangeIsAdoptedByOnePullRequestOnly() throws SQLException {
        final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
        apply( CodeEvents.opened( branch, "5", A, at( "11:00" ) ) );
        apply( CodeEvents.opened( branch, "6", A, at( "11:05" ) ) );
        final List< Change > all = f.changes( item );
        assertEquals( 2, all.size() );
        assertEquals( mine.id(), all.get( 0 ).id() );
        assertEquals( "5", all.get( 0 ).reviewNumber(), "adopted by the first pull request" );
        assertEquals( f.author.agentId(), all.get( 0 ).openedByAgentId() );
        assertEquals( "6", all.get( 1 ).reviewNumber(), "the second has its own change" );
        apply( CodeEvents.closed( branch, "6", A, at( "11:10" ) ) );
        assertTrue( f.store.changes().change( mine.id() ).orElseThrow().live(), "closing #6 leaves the agent's change" );
    }

    @Test
    void anAgentCannotOpenASecondChangeOnABranchAPullRequestHolds() throws SQLException {
        apply( CodeEvents.opened( branch, "5", A, at( "11:00" ) ) );
        final WorkValidationException e = org.junit.jupiter.api.Assertions.assertThrows( WorkValidationException.class,
            () -> f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) ) );
        assertEquals( "branch", e.field() );
    }

    @Test
    void aLearnedUsernameKeepsItsFirstAccount() throws Exception {
        final WorkCodeHostStore store = f.store.notifyStores().codeHost();
        store.learn( "github", "octocat", "ana" );
        final List< String > warned = WarnCapture.of( JdbcWorkHooks.class, () -> {
            store.learn( "github", "octocat", "mia" );
            store.learn( "github", "octocat", "ana" );
        } );
        assertEquals( java.util.Optional.of( "ana" ), store.learned( "github", "octocat" ) );
        assertEquals( 1, warned.size(), "a different account is reported, the same one is not: " + warned );
    }

    @Test
    void aFirstCommitReportedByHandIsNeverAfterTheChangeOpened() throws SQLException {
        final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
        f.clock.set( mine.openedAt().plus( java.time.Duration.ofDays( 2 ) ) );
        final Change later = f.svc.changes().update( f.author, mine.id(), null, null,
            mine.openedAt().plus( java.time.Duration.ofDays( 1 ) ).toString() );
        assertEquals( mine.openedAt(), later.firstCommitAt(), "a commit reported after the opening is not its first" );
    }
}
