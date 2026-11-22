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

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replayed, reordered or forged-again delivery changes nothing the first one did not: the same rows, the same events,
 * the same final record whatever the order.
 */
@RequiresPostgres
class WorkCodeHostIdempotencePostgresTest {

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

    private String snapshot() throws SQLException {
        try ( var c = f.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT COALESCE( ( SELECT string_agg( row_to_json( c )::text, '|' ORDER BY id ) "
                  + "FROM work_change c ), '' ) || '#' || ( SELECT count(*) FROM work_event )" ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    private CodeEvent push( final String head, final String hhmm ) {
        return CodeEvents.push( branch, head, at( "11:00" ), commit( head, "wip", at( hhmm ), true, ANA ) );
    }

    @Test
    void everyLiveEventTwiceChangesNothing() throws SQLException {
        final List< CodeEvent > all = List.of( push( A, "09:00" ), CodeEvents.opened( branch, "12", A, at( "11:30" ) ),
            CodeEvents.updated( branch, "12", B, at( "11:35" ) ), CodeEvents.approved( branch, "12", B, at( "11:45" ), MIA ),
            CodeEvents.ready( branch, "12", B, at( "11:46" ) ), CodeEvents.closed( branch, "12", B, at( "11:55" ) ) );
        for ( final CodeEvent e : all ) {
            f.hooks.apply( d, List.of( e ) );
            final String once = snapshot();
            final WorkCodeHostService.Applied again = f.hooks.apply( d, List.of( e ) );
            assertEquals( once, snapshot(), "the second delivery of " + e.kind() + " changed something" );
            assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), again, e.kind().toString() );
        }
    }

    @Test
    void anOldPushRedeliveredAfterANewerOneLeavesTheHead() throws SQLException {
        apply( push( A, "09:00" ) );
        apply( push( B, "10:00" ) );
        apply( CodeEvents.opened( branch, "12", B, at( "11:30" ) ) );
        apply( CodeEvents.approved( branch, "12", B, at( "11:45" ), MIA ) );
        final String before = snapshot();
        final WorkCodeHostService.Applied r = apply( push( A, "09:00" ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), r );
        assertEquals( before, snapshot() );
        final Change c = f.only( item );
        assertEquals( B, c.headSha() );
        assertTrue( c.approvedForHead(), "the approval of the newer commit stands" );
    }

    @Test
    void anOldHeadRedeliveredAsAPullRequestUpdateNeverMovesTheHeadBack() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.updated( branch, "12", B, at( "11:35" ) ) );
        apply( CodeEvents.approved( branch, "12", B, at( "11:45" ), MIA ) );
        final String before = snapshot();
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.updated( branch, "12", A, at( "11:35" ) ) ) );
        assertEquals( before, snapshot() );
        assertTrue( f.only( item ).approvedForHead() );
    }

    private static String summary( final Change c ) {
        return String.join( "|", c.headSha(), c.state().wire(), String.valueOf( c.reviewNumber() ),
            String.valueOf( c.firstCommitAt() ), String.valueOf( c.reviewedBy() ), String.valueOf( c.reviewedHeadSha() ),
            String.valueOf( c.openedBy() ), String.valueOf( c.reviewUrl() ), c.source() );
    }

    /** Every order of a push, an opening and an approval of one commit ends in the same single change. */
    @Test
    void deliveriesOfOneCommitInAnyOrderConvergeToTheSameRecord() throws SQLException {
        final int[][] orders = { { 0, 1, 2 }, { 0, 2, 1 }, { 1, 0, 2 }, { 1, 2, 0 }, { 2, 0, 1 }, { 2, 1, 0 } };
        String expected = null;
        for ( final int[] order : orders ) {
            setUp();
            final List< CodeEvent > events = List.of( push( A, "09:00" ),
                CodeEvents.opened( branch, "12", A, at( "11:30" ) ),
                CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
            for ( final int i : order ) {
                f.hooks.apply( d, List.of( events.get( i ) ) );
                f.hooks.apply( d, List.of( events.get( i ) ) );
            }
            final String got = summary( f.only( item ) );
            if ( expected == null ) {
                expected = got;
                assertTrue( expected.startsWith( A + "|in_review|12|2026-03-01T09:00:00Z|mia|" + A + "|ana|" ), expected );
            }
            assertEquals( expected, got, "order " + java.util.Arrays.toString( order ) );
        }
    }

    /** A head the change already had is never set again, in whatever order the deliveries come (P5-3). */
    @Test
    void aHeadAlreadySeenNeverComesBackWhateverTheOrder() throws SQLException {
        apply( push( A, "09:00" ) );
        apply( push( B, "10:00" ) );
        apply( CodeEvents.opened( branch, "12", B, at( "11:30" ) ) );
        apply( CodeEvents.approved( branch, "12", B, at( "11:45" ), MIA ) );
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.updated( branch, "12", A, at( "11:31" ) ) );
        apply( push( A, "09:00" ) );
        final Change c = f.only( item );
        assertEquals( B, c.headSha() );
        assertTrue( c.approvedForHead() );
    }

    @Test
    void anApprovalBeforeTheOpeningStillLandsOnTheSameChange() throws SQLException {
        apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        final Change c = f.only( item );
        assertTrue( c.approvedForHead() );
        assertEquals( "12", c.reviewNumber() );
    }

    @Test
    void anOldOpeningOrUpdateAfterTheCloseKeepsTheChangeClosed() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.closed( branch, "12", A, at( "11:55" ) ) );
        final String before = snapshot();
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.opened( branch, "12", A, at( "11:40" ) ) ), "an opened event older than the close" );
        assertEquals( before, snapshot() );
        apply( CodeEvents.updated( branch, "12", B, at( "11:41" ) ) );
        final Change c = f.only( item );
        assertEquals( ChangeState.ABANDONED, c.state(), "an update older than the close reopens nothing" );
        assertEquals( B, c.headSha(), "the closed change's head is the host's head at the close, whatever the order" );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.updated( branch, "12", B, at( "11:41" ) ) ) );
    }

    @Test
    void aPullRequestOpenedRedeliveredAfterItsMergeOpensNothing() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        f.exec( "UPDATE work_change SET state = 'merged', merge_kind = 'reviewed', merged_sha = '" + CodeEvents.M
            + "', closed_at = '2026-03-01T11:50:00Z'" );
        final String before = snapshot();
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.opened( branch, "12", A, at( "11:59" ) ) ), "even one stamped after the merge" );
        assertEquals( before, snapshot() );
    }

    @Test
    void anAgentMergedChangeIsNotRevivedByAPullRequestEvent() throws SQLException {
        final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
        f.exec( "UPDATE work_change SET state = 'merged', merge_kind = 'unreviewed', merged_sha = '" + CodeEvents.M
            + "', closed_at = '2026-03-01T11:50:00Z' WHERE id = " + mine.id() );
        final String before = snapshot();
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) ) );
        assertEquals( before, snapshot() );
    }
}
