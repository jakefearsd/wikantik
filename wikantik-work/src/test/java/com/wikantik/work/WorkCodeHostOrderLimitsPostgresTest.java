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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edges of the order guarantee (#157 item 3): what the record converges to where the last-writer-wins register
 * meets something outside it, and what it does where it cannot converge (characterised, not promised).
 */
@RequiresPostgres
class WorkCodeHostOrderLimitsPostgresTest {

    private static final CodeEvent.Person LENA = new CodeEvent.Person( "lenagh", "Lena L", "lena@example.com" );

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;
    private String branch;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        f.emails.put( "mia@example.com", "mia" );
        f.emails.put( "lena@example.com", "lena" );
        d = f.delivery( "github" );
        item = f.task();
        branch = "work/" + item.key() + "-fix";
        // late enough that no host time below is clamped to the work clock
        f.clock.set( Instant.parse( "2026-03-01T23:00:00Z" ) );
    }

    private void apply( final CodeEvent... events ) throws SQLException {
        f.hooks.apply( d, List.of( events ) );
    }

    private String record() throws SQLException {
        return f.changes( item ).stream().map( c -> String.join( "/", c.state().wire(), String.valueOf( c.reviewNumber() ),
            c.headSha(), String.valueOf( c.mergeKind() ), String.valueOf( c.policyFlag() ), String.valueOf( c.reviewedBy() ),
            String.valueOf( c.reviewedAt() ), String.valueOf( c.mergedSha() ), String.valueOf( c.closedAt() ) ) )
            .sorted().collect( Collectors.joining( " | " ) );
    }

    /** The code host's events on the item, without ids or times, sorted. */
    private List< String > log() throws SQLException {
        final List< String > out = new ArrayList<>();
        try ( var c = f.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT type || '/' || COALESCE( to_value, '-' ) || '/' || COALESCE( note, '-' ) "
                  + "FROM work_event WHERE actor_kind = 'system' ORDER BY 1" ) ) {
            while ( rs.next() ) {
                out.add( rs.getString( 1 ).replace( item.key(), "<item>" ) );
            }
        }
        return out;
    }

    /**
     * Past the 200 lifecycle events a change remembers, a close is no longer logged, so it must not move
     * {@code closed_at} either: no state change without its event.
     */
    @Test
    void pastTheLifecycleBoundALosingCloseMovesNothing() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.closed( branch, "12", A, at( "11:10" ) ) );
        f.set( f.lena, item, "status", "cancelled" );
        apply( CodeEvents.reopened( branch, "12", A, at( "11:40" ) ) );
        final long change = f.only( item ).id();
        f.exec( "INSERT INTO work_codehost_lifecycle (change_id, kind, at) SELECT " + change + ", 'reopened', "
            + "TIMESTAMPTZ '2026-03-01 05:00:00+00' + g * INTERVAL '1 second' FROM generate_series( 1, "
            + JdbcCodeHostLifecycle.KEPT + " ) g" );
        final long events = f.count( "SELECT count(*) FROM work_event" );
        apply( CodeEvents.closed( branch, "12", A, at( "11:30" ) ) );
        assertEquals( events, f.count( "SELECT count(*) FROM work_event" ), "past the bound nothing is logged" );
        assertEquals( at( "11:10" ), f.only( item ).closedAt(), "so closed_at does not move either" );
    }

    private static String sha( final int i ) {
        return String.format( Locale.ROOT, "%07x0", i + 0x1000000 );
    }

    // ---- the item's own closed status

    /**
     * A closed item holds no live change, so once a person closes the item a reopen the host reports later moves the
     * register but leaves the change abandoned, in any order of the deliveries after the closing; and the item being
     * reopened by a person afterwards does not revive it. The item's status is a person's act, outside the register.
     */
    @Test
    void onAClosedItemTheChangeStaysAbandonedWhateverTheHostSaysAfter() throws SQLException {
        final List< CodeEvent > after = List.of( CodeEvents.reopened( branch, "12", A, at( "11:20" ) ),
            CodeEvents.closed( branch, "12", A, at( "11:30" ) ), CodeEvents.reopened( branch, "12", A, at( "11:40" ) ) );
        final int[][] orders = { { 0, 1, 2 }, { 0, 2, 1 }, { 1, 0, 2 }, { 1, 2, 0 }, { 2, 0, 1 }, { 2, 1, 0 } };
        String expected = null;
        List< String > expectedLog = null;
        for ( final int[] order : orders ) {
            setUp();
            apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.closed( branch, "12", A, at( "11:10" ) ) );
            f.set( f.lena, item, "status", "cancelled" );
            for ( final int i : order ) {
                apply( after.get( i ) );
            }
            final Change c = f.only( item );
            assertEquals( ChangeState.ABANDONED, c.state(), "a closed item holds no live change" );
            assertEquals( at( "11:30" ), c.closedAt(), "the latest close reported" );
            final String got = record();
            final List< String > log = log();
            if ( expected == null ) {
                expected = got;
                expectedLog = log;
            }
            final String which = java.util.Arrays.toString( order );
            assertEquals( expected, got, which );
            assertEquals( expectedLog, log, which );
            // a redelivery of all three changes nothing and logs nothing
            final long events = f.count( "SELECT count(*) FROM work_event" );
            for ( final CodeEvent e : after ) {
                apply( e );
            }
            assertEquals( got, record(), which + " redelivered" );
            assertEquals( events, f.count( "SELECT count(*) FROM work_event" ), which + " redelivered" );
        }
        // reopening the item does not revive the change the host reopened while the item was closed
        f.set( f.lena, item, "status", "ready" );
        assertEquals( ChangeState.ABANDONED, f.only( item ).state() );
    }

    // ---- a push stamped after the merge that arrives before it

    /**
     * A push to the pull request's branch stamped after its merge: arriving after the merge it is new work on the
     * branch (a change of its own); arriving before it, the pull request's change is still live and takes it as a
     * head report, and the merge then sets the head back to the commit that was merged. The two orders do not
     * converge: one pull request is one change and a change is never split, so a report already taken cannot later
     * become another change. Characterised here; the merged change is the same in both.
     */
    @Test
    void aPushStampedAfterTheMergeThatArrivesBeforeItIsTakenByTheLiveChange() throws SQLException {
        final CodeEvent opened = CodeEvents.opened( branch, "12", A, at( "11:00" ) );
        final CodeEvent merged = CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA );
        final CodeEvent late = CodeEvents.push( branch, B, at( "11:30" ), commit( B, "after the merge", at( "11:29" ), true,
            ANA ) );
        apply( opened, merged, late );
        final List< Change > inOrder = f.changes( item );
        assertEquals( 2, inOrder.size(), "in the host's order the later push is new work on the branch" );
        final Change mergedInOrder = inOrder.get( 0 );

        setUp();
        apply( opened, late, merged );
        final List< Change > early = f.changes( item );
        assertEquals( 1, early.size(), "arriving first, the push was a head report of the live change" );
        final Change mergedEarly = early.get( 0 );
        assertEquals( mergedInOrder.headSha(), mergedEarly.headSha(), "the merged head is the merged commit either way" );
        assertEquals( A, mergedEarly.headSha() );
        assertEquals( mergedInOrder.mergeKind(), mergedEarly.mergeKind() );
        assertEquals( mergedInOrder.policyFlag(), mergedEarly.policyFlag() );
        assertEquals( mergedInOrder.closedAt(), mergedEarly.closedAt() );
    }

    // ---- more than 200 head reports on one change

    @Test
    void moreThanTwoHundredHeadReportsKeepTheNewestHeadInAnyOrder() throws SQLException {
        final int n = JdbcCodeHostHeads.KEPT + 40;
        final List< CodeEvent > updates = new ArrayList<>();
        final Instant start = at( "08:00" );
        for ( int i = 0; i < n; i++ ) {
            updates.add( CodeEvents.updated( branch, "12", sha( i ), start.plusSeconds( 60L * i ) ) );
        }
        final List< CodeEvent > shuffled = new ArrayList<>( updates );
        Collections.shuffle( shuffled, new Random( 157 ) );
        apply( CodeEvents.opened( branch, "12", A, at( "07:00" ) ) );
        for ( final CodeEvent e : shuffled ) {
            apply( e );
        }
        assertEquals( sha( n - 1 ), f.only( item ).headSha(), "the newest head by host time" );
        assertTrue( f.count( "SELECT count(*) FROM work_codehost_head" ) <= JdbcCodeHostHeads.KEPT + 2,
            "the bound stays" );
    }

    /**
     * Approvals of older commits stamped after the newest push must not crowd the newest head report out of the kept
     * rows: arriving before it, they would otherwise leave the change on an old head that a different order moves.
     */
    @Test
    void lateApprovalsOfOldCommitsNeverCrowdOutTheNewestHead() throws SQLException {
        final CodeEvent newest = CodeEvents.updated( branch, "12", B, at( "12:00" ) );
        final List< CodeEvent > approvals = new ArrayList<>();
        for ( int i = 0; i < JdbcCodeHostHeads.KEPT + 5; i++ ) {
            approvals.add( CodeEvents.approved( branch, "12", sha( i ), at( "13:00" ).plusSeconds( i ), MIA ) );
        }
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ) );
        for ( final CodeEvent e : approvals ) {
            apply( e );
        }
        apply( newest );
        final String approvalsFirst = record();
        assertEquals( B, f.only( item ).headSha() );
        assertTrue( f.count( "SELECT count(*) FROM work_codehost_head" ) <= JdbcCodeHostHeads.KEPT + 2,
            "a head report prunes back to the bound" );

        setUp();
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), newest );
        for ( final CodeEvent e : approvals ) {
            apply( e );
        }
        assertEquals( approvalsFirst, record() );
    }

    /**
     * An approval of a commit whose head report has not arrived yet survives any number of later-stamped approvals of
     * other commits: when the commit's push arrives it stands, exactly as when the push came first, and a redelivery
     * of the approval logs nothing (review I-1).
     */
    @Test
    void anApprovalBeforeItsPushSurvivesLaterApprovalsOfOtherCommits() throws SQLException {
        final String x = "f0f0f0f1";
        final CodeEvent approvalOfX = CodeEvents.approved( branch, "12", x, at( "12:05" ), MIA );
        final CodeEvent pushOfX = CodeEvents.updated( branch, "12", x, at( "12:00" ) );
        final List< CodeEvent > others = new ArrayList<>();
        for ( int i = 0; i < JdbcCodeHostHeads.KEPT; i++ ) {
            others.add( CodeEvents.approved( branch, "12", sha( i ), at( "13:00" ).plusSeconds( i ), MIA ) );
        }
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), approvalOfX );
        for ( final CodeEvent e : others ) {
            apply( e );
        }
        apply( pushOfX );
        final Change approvalFirst = f.only( item );
        assertEquals( x, approvalFirst.headSha() );
        assertEquals( "mia", approvalFirst.reviewedBy(), "the approval of x stands once x is the head" );
        assertEquals( x, approvalFirst.reviewedHeadSha() );
        final String recordApprovalFirst = record();
        final List< String > logApprovalFirst = log();
        final long events = f.count( "SELECT count(*) FROM work_event" );
        apply( approvalOfX );
        assertEquals( events, f.count( "SELECT count(*) FROM work_event" ), "a redelivered approval logs nothing" );

        setUp();
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), pushOfX, approvalOfX );
        for ( final CodeEvent e : others ) {
            apply( e );
        }
        assertEquals( recordApprovalFirst, record() );
        assertEquals( logApprovalFirst, log() );
    }

    // ---- register growth (M-4): why the rows of an old merged change are not pruned

    /**
     * The lifecycle and head rows are what makes a replay write nothing: they remember which closes, reopens, heads
     * and approvals were already logged. Without them a redelivered close of a merged change (stamped before its
     * merge) and a redelivered approval each log their event again. So they cannot be pruned without breaking replay
     * idempotency; each row is paired with a {@code work_event} row that is kept for good anyway, so pruning them
     * would not bound the code host's share of the database either.
     */
    @Test
    void forgettingAMergedChangesRegisterWouldLogARedeliveryAgain() throws SQLException {
        final CodeEvent close = CodeEvents.closed( branch, "12", A, at( "11:10" ) );
        final CodeEvent approval = CodeEvents.approved( branch, "12", A, at( "11:16" ), MIA );
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), close, CodeEvents.reopened( branch, "12", A, at( "11:15" ) ),
            approval, CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) );
        final String abandoned = "SELECT count(*) FROM work_event WHERE type = 'change_abandoned'";
        final String reviewed = "SELECT count(*) FROM work_event WHERE type = 'change_reviewed'";
        apply( close, approval );
        assertEquals( 1, f.count( abandoned ), "with the register, a replay logs nothing" );
        assertEquals( 1, f.count( reviewed ) );
        f.exec( "DELETE FROM work_codehost_lifecycle" );
        f.exec( "DELETE FROM work_codehost_head WHERE sha <> '" + A + "'" );
        f.exec( "UPDATE work_codehost_head SET approved_at = NULL, approved_by = NULL, external_approved_by = NULL" );
        apply( close, approval );
        assertEquals( 2, f.count( abandoned ), "without it, the same close is logged twice" );
        assertEquals( 2, f.count( reviewed ), "and the same approval" );
    }

    // ---- two reviewers approving one commit

    @Test
    void ofTwoApprovalsOfOneCommitTheEarliestByHostTimeStandsInEitherOrder() throws SQLException {
        final CodeEvent byMia = CodeEvents.approved( branch, "12", A, at( "11:20" ), MIA );
        final CodeEvent byLena = CodeEvents.approved( branch, "12", A, at( "11:10" ), LENA );
        final CodeEvent opened = CodeEvents.opened( branch, "12", A, at( "11:00" ) );
        apply( opened, byMia, byLena );
        final Change lateFirst = f.only( item );
        assertEquals( "lena", lateFirst.reviewedBy() );
        assertEquals( at( "11:10" ), lateFirst.reviewedAt() );
        final String recordLateFirst = record();
        final long events = f.count( "SELECT count(*) FROM work_event WHERE type = 'change_reviewed'" );

        setUp();
        apply( opened, byLena, byMia );
        assertEquals( "lena", f.only( item ).reviewedBy() );
        assertEquals( recordLateFirst, record() );
        assertEquals( events, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_reviewed'" ),
            "one change_reviewed per commit, whichever approval came first" );
    }
}
