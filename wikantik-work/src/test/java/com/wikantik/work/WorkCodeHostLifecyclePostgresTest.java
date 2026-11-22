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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle register of a pull request's one change: closing and reopening move the same record back and forth
 * by the host's times, a merge ends it, an older or repeated event changes nothing, and an event without a host time
 * is never given one.
 */
@RequiresPostgres
class WorkCodeHostLifecyclePostgresTest {

    private static final Instant T1 = at( "11:00" );
    private static final Instant T3 = at( "11:20" );
    private static final Instant T4 = at( "11:30" );
    private static final Instant T5 = at( "11:40" );
    private static final Instant T7 = at( "11:55" );

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;
    private String branch;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        f.emails.put( "mia@example.com", "mia" );
        d = f.delivery( "generic" );
        item = f.task();
        branch = "work/" + item.key() + "-fix";
    }

    private WorkCodeHostService.Applied apply( final CodeEvent e ) throws SQLException {
        return f.hooks.apply( d, List.of( e ) );
    }

    private String snapshot() throws SQLException {
        try ( var c = f.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT COALESCE( ( SELECT string_agg( row_to_json( c )::text, '|' ORDER BY id ) "
                  + "FROM work_change c ), '' ) || '#' || ( SELECT count(*) FROM work_event ) || '#' "
                  + "|| ( SELECT count(*) FROM work_codehost_head )" ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    private CodeEvent withAt( final CodeEvent e, final Instant at ) {
        return new CodeEvent( e.kind(), e.repo(), e.defaultBranch(), e.branch(), e.baseBranch(), e.before(), e.after(),
            at, e.commits(), e.review(), e.actor(), e.mergedSha() );
    }

    // ---- N1

    @Test
    void aMergeBeforeAnEarlierCloseAndReopenIsOneMergedChange() throws SQLException {
        apply( CodeEvents.merged( branch, "12", A, M, T7, ANA ) );
        apply( CodeEvents.closed( branch, "12", A, T3 ) );
        apply( CodeEvents.reopened( branch, "12", A, T4 ) );
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        final Change c = f.only( item );
        assertEquals( ChangeState.MERGED, c.state() );
        assertEquals( T1, c.openedAt(), "the change began when the pull request was opened" );
        assertEquals( T7, c.closedAt() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change WHERE state = 'abandoned'" ) );
    }

    @Test
    void oneChangeIsClosedAndReopenedInPlace() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        final long id = f.only( item ).id();
        apply( CodeEvents.closed( branch, "12", A, T3 ) );
        assertEquals( ChangeState.ABANDONED, f.only( item ).state() );
        assertEquals( T3, f.only( item ).closedAt() );
        apply( CodeEvents.reopened( branch, "12", A, T4 ) );
        final Change c = f.only( item );
        assertEquals( id, c.id(), "reopening revives the same record" );
        assertEquals( ChangeState.IN_REVIEW, c.state(), "its review was requested when it opened" );
        assertNull( c.closedAt() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_updated' "
            + "AND note = 'reopened on the code host'" ) );
    }

    @Test
    void aCloseOrReopenStampedAfterTheMergeIsIgnored() throws Exception {
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        apply( CodeEvents.merged( branch, "12", A, M, T5, ANA ) );
        final String before = snapshot();
        final List< String > warned = WarnCapture.of( JdbcCodeHostLifecycle.class, () -> {
            assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( CodeEvents.closed( branch, "12", A, T7 ) ) );
            assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( CodeEvents.reopened( branch, "12", A, T7 ) ) );
        } );
        assertEquals( 2, warned.size(), warned.toString() );
        assertEquals( before, snapshot() );
    }

    // ---- N2: precedence across a close and a reopen, both arrival orders

    @Test
    void aCloseAndReopenKeepTheAgentsAttributionAndAPersonsApproval() throws SQLException {
        for ( final boolean closeFirst : new boolean[] { true, false } ) {
            setUp();
            final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
            apply( CodeEvents.opened( branch, "12", A, T1 ) );
            f.svc.changes().review( f.mia, mine.id(), true, null );
            if ( closeFirst ) {
                apply( CodeEvents.closed( branch, "12", A, T3 ) );
                apply( CodeEvents.reopened( branch, "12", A, T4 ) );
            } else {
                apply( CodeEvents.reopened( branch, "12", A, T4 ) );
                apply( CodeEvents.closed( branch, "12", A, T3 ) );
            }
            final Change c = f.only( item );
            final String why = closeFirst ? "close first" : "reopen first";
            assertEquals( mine.id(), c.id(), why );
            assertTrue( c.live(), why );
            assertEquals( f.author.agentId(), c.openedByAgentId(), why );
            assertEquals( List.of( f.author.agentId() ), c.headAgentIds(), why );
            assertEquals( "mia", c.reviewedBy(), why );
            assertTrue( c.approvedForHead(), why );
        }
    }

    // ---- N3: replays and events without a host time

    @Test
    void aReplayedCloseOrOpeningChangesNothing() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        apply( CodeEvents.closed( branch, "12", A, T3 ) );
        String before = snapshot();
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( CodeEvents.opened( branch, "12", A, T1 ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( CodeEvents.closed( branch, "12", A, T3 ) ) );
        assertEquals( before, snapshot() );
        apply( CodeEvents.reopened( branch, "12", A, T4 ) );
        before = snapshot();
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( CodeEvents.closed( branch, "12", A, T3 ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( CodeEvents.opened( branch, "12", A, T1 ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( CodeEvents.reopened( branch, "12", A, T4 ) ) );
        assertEquals( before, snapshot() );
        assertTrue( f.only( item ).live() );
    }

    @Test
    void anEventWithoutAHostTimeIsIgnoredNeverStampedWithTheClock() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        final String before = snapshot();
        for ( final Instant none : new Instant[] { null, Instant.parse( "1969-12-31T23:59:59Z" ) } ) {
            assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ),
                apply( withAt( CodeEvents.closed( branch, "12", A, T3 ), none ) ) );
            assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ),
                apply( withAt( CodeEvents.push( branch, B, T3, commit( B, "x", T3, true, ANA ) ), none ) ) );
        }
        assertEquals( before, snapshot() );
        assertTrue( f.only( item ).live() );
    }

    // ---- forged times

    @Test
    void aForgedEventWithAnOldTimeCannotChangeTheState() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        apply( CodeEvents.closed( branch, "12", A, T3 ) );
        apply( CodeEvents.reopened( branch, "12", A, T4 ) );
        apply( CodeEvents.closed( branch, "12", A, at( "11:25" ) ) );
        apply( CodeEvents.opened( branch, "12", A, at( "11:21" ) ) );
        final Change c = f.only( item );
        assertTrue( c.live(), "an older close loses to the newer reopen" );
        assertNull( c.closedAt() );
        assertEquals( "reopened/" + T4, lifecycle( c.id() ), "the register keeps the newest key" );
    }

    private String lifecycle( final long id ) throws SQLException {
        try ( var c = f.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT lifecycle_kind, lifecycle_at FROM work_change WHERE id = " + id ) ) {
            rs.next();
            return rs.getString( 1 ) + "/" + rs.getTimestamp( 2 ).toInstant();
        }
    }

    @Test
    void aForgedFutureEventIsClampedToTheClockAndARealLaterEventStillWins() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, T1 ) );
        apply( CodeEvents.closed( branch, "12", A, Instant.parse( "2030-01-01T00:00:00Z" ) ) );
        final Change closed = f.only( item );
        assertEquals( ChangeState.ABANDONED, closed.state() );
        assertEquals( CodeHostFixture.START, closed.closedAt(), "clamped to the work clock" );
        f.clock.advance( Duration.ofMinutes( 10 ) );
        apply( CodeEvents.reopened( branch, "12", A, CodeHostFixture.START.plus( Duration.ofMinutes( 5 ) ) ) );
        assertTrue( f.only( item ).live(), "the clamped close cannot pin the record in the future" );
    }

    // ---- the head table is bounded

    @Test
    void theHeadTableKeepsOnlyTheMostRecentReports() throws SQLException {
        final Instant start = at( "08:00" );
        final int pushes = JdbcCodeHostHeads.KEPT + 5;
        String last = null;
        for ( int i = 0; i < pushes; i++ ) {
            last = String.format( "%08x", 0x10000000 + i );
            final Instant t = start.plusSeconds( 60L * i );
            apply( CodeEvents.push( branch, last, t, commit( last, "wip", t, true, ANA ) ) );
        }
        final long id = f.only( item ).id();
        assertEquals( JdbcCodeHostHeads.KEPT, f.count( "SELECT count(*) FROM work_codehost_head WHERE change_id = " + id ) );
        assertEquals( last, f.only( item ).headSha() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_codehost_head WHERE sha = '10000000'" ), "the oldest went" );
        final String before = snapshot();
        apply( CodeEvents.push( branch, "0fffffff", at( "07:00" ), commit( "0fffffff", "old", at( "07:00" ), true, ANA ) ) );
        assertEquals( JdbcCodeHostHeads.KEPT, f.count( "SELECT count(*) FROM work_codehost_head WHERE change_id = " + id ) );
        assertEquals( last, f.only( item ).headSha() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_codehost_head WHERE sha = '0fffffff'" ),
            "a report older than every one kept is not kept: " + before );
    }
}
