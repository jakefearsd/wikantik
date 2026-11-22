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
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One pull request is one change record, and what its deliveries leave does not depend on the order they arrive in:
 * every permutation of a set of host events ends with the change row, the head table and the event rows the host's
 * own order gives, and delivering everything again changes nothing.
 *
 * <p>The host's order of the reference events: opened (11:00, head A), an approval of A by someone other than the
 * author (11:10), closed (11:20), reopened (11:30), a push of B (11:40), an approval of B (11:50), merged (11:55).
 */
@RequiresPostgres
class WorkCodeHostConvergencePostgresTest {

    private static final Instant T1 = at( "11:00" );
    private static final Instant T2 = at( "11:10" );
    private static final Instant T3 = at( "11:20" );
    private static final Instant T4 = at( "11:30" );
    private static final Instant T5 = at( "11:40" );
    private static final Instant T6 = at( "11:50" );
    private static final Instant T7 = at( "11:55" );
    private static final String LATE_NOTE = "approved before the merge; no longer outside policy";

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;
    private String branch;

    @BeforeEach
    void setUp() throws SQLException {
        fresh();
    }

    private void fresh() throws SQLException {
        f = new CodeHostFixture();
        f.emails.put( "mia@example.com", "mia" );
        d = f.delivery( "github" );
        item = f.task();
        branch = "work/" + item.key() + "-fix";
    }

    // ---- the reference events (built after setUp, since the branch names the item)

    private CodeEvent opened() {
        return CodeEvents.opened( branch, "12", A, T1 );
    }

    private CodeEvent approveA() {
        return CodeEvents.approved( branch, "12", A, T2, MIA );
    }

    private CodeEvent closed() {
        return CodeEvents.closed( branch, "12", A, T3 );
    }

    private CodeEvent reopened() {
        return CodeEvents.reopened( branch, "12", A, T4 );
    }

    private CodeEvent pushB() {
        return CodeEvents.push( branch, B, T5, commit( B, "more " + item.key(), at( "11:39" ), true, ANA ) );
    }

    private CodeEvent approveB() {
        return CodeEvents.approved( branch, "12", B, T6, MIA );
    }

    private CodeEvent merged() {
        return CodeEvents.merged( branch, "12", B, M, T7, ANA );
    }

    // ---- what a run leaves

    /** Everything the record holds about the item's changes, with database ids left out. */
    record Outcome( String rows, String heads, List< String > events ) {
    }

    private WorkCodeHostService.Applied apply( final CodeEvent e ) throws SQLException {
        return f.hooks.apply( d, List.of( e ) );
    }

    private String text( final String sql ) throws SQLException {
        try ( var c = f.ds.getConnection(); var s = c.createStatement(); var rs = s.executeQuery( sql ) ) {
            rs.next();
            final String v = rs.getString( 1 );
            return v == null ? "" : v.replace( item.key(), "<item>" );
        }
    }

    private Outcome outcome() throws SQLException {
        final String rows = text( "SELECT string_agg( ( to_jsonb( c ) - 'id' - 'item_id' - 'product_id' )::text, ' | ' "
            + "ORDER BY c.review_number NULLS FIRST, c.branch, c.head_sha ) FROM work_change c" );
        final String heads = text( "SELECT string_agg( h.sha || '/' || COALESCE( h.seen_at::text, '-' ) || '/' "
            + "|| COALESCE( h.approved_by, h.external_approved_by, '-' ) || '/' || COALESCE( h.approved_at::text, '-' ), "
            + "' | ' ORDER BY h.sha ) FROM work_codehost_head h" );
        final List< String > events = new ArrayList<>();
        try ( var c = f.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT type || '/' || COALESCE( to_value, '-' ) || '/' || COALESCE( note, '-' ) "
                  + "FROM work_event WHERE actor_kind = 'system' ORDER BY 1" ) ) {
            while ( rs.next() ) {
                events.add( rs.getString( 1 ).replace( item.key(), "<item>" ) );
            }
        }
        return new Outcome( rows, heads, events );
    }

    private Outcome run( final List< Supplier< CodeEvent > > events, final int[] order ) throws SQLException {
        fresh();
        for ( final int i : order ) {
            apply( events.get( i ).get() );
        }
        return outcome();
    }

    private static int[] hostOrder( final int n ) {
        final int[] order = new int[ n ];
        for ( int i = 0; i < n; i++ ) {
            order[ i ] = i;
        }
        return order;
    }

    static List< int[] > permutations( final int n ) {
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

    private static int position( final int[] order, final int event ) {
        for ( int i = 0; i < order.length; i++ ) {
            if ( order[ i ] == event ) {
                return i;
            }
        }
        throw new IllegalArgumentException( "event " + event + " not in " + Arrays.toString( order ) );
    }

    /** Applies the events in every order from a fresh database; each must leave the host order's outcome. */
    private Outcome everyOrderIsTheHostOrder( final List< Supplier< CodeEvent > > events ) throws SQLException {
        final Outcome host = run( events, hostOrder( events.size() ) );
        for ( final int[] order : permutations( events.size() ) ) {
            assertEquals( host, run( events, order ), "order " + Arrays.toString( order ) );
        }
        return run( events, hostOrder( events.size() ) );
    }

    // ---- convergence

    @Test
    void everyOrderOfOpenCloseReopenAndMergeLeavesTheHostOrderRecord() throws SQLException {
        final Outcome host = everyOrderIsTheHostOrder( List.of( this::opened, this::closed, this::reopened, this::merged ) );
        final Change c = f.only( item );
        assertEquals( ChangeState.MERGED, c.state(), host.rows() );
        assertEquals( T1, c.openedAt() );
        assertEquals( T7, c.closedAt() );
        assertEquals( B, c.headSha() );
        assertEquals( MergeKind.UNREVIEWED, c.mergeKind() );
        assertEquals( "unreviewed", c.policyFlag() );
        assertTrue( host.events().contains( "change_abandoned/" + branch.replace( item.key(), "<item>" )
            + "/closed on the code host" ), host.events().toString() );
    }

    @Test
    void everyOrderOfAPushAndItsApprovalBeforeTheMergeIsReviewed() throws SQLException {
        final List< Supplier< CodeEvent > > events = List.of( this::opened, this::pushB, this::approveB, this::merged );
        final Outcome host = run( events, hostOrder( 4 ) );
        // the event log records what the wiki concluded when: a merge that arrived before its approval was recorded
        // unreviewed and flagged, and the late approval withdrew the flag; everything else is the host order's
        final List< String > lateEvents = new ArrayList<>();
        for ( final String e : host.events() ) {
            lateEvents.add( e.equals( "change_merged/reviewed/" + M ) ? "change_merged/unreviewed/" + M
                : e.equals( "change_reviewed/approved/-" ) ? "change_reviewed/approved/" + LATE_NOTE : e );
        }
        lateEvents.add( "change_flagged/unreviewed/-" );
        lateEvents.sort( null );
        for ( final int[] order : permutations( 4 ) ) {
            final Outcome got = run( events, order );
            final String why = "order " + Arrays.toString( order );
            assertEquals( host.rows(), got.rows(), why );
            assertEquals( host.heads(), got.heads(), why );
            assertEquals( position( order, 2 ) < position( order, 3 ) ? host.events() : lateEvents, got.events(), why );
        }
        fresh();
        for ( final Supplier< CodeEvent > e : events ) {
            apply( e.get() );
        }
        final Change c = f.only( item );
        assertEquals( MergeKind.REVIEWED, c.mergeKind() );
        assertNull( c.policyFlag() );
        assertEquals( "mia", c.reviewedBy() );
        assertEquals( B, c.reviewedHeadSha() );
        assertEquals( T6, c.reviewedAt() );
        assertEquals( T1, c.openedAt() );
    }

    @Test
    void everyOrderOfAnApprovalOfACommitSinceReplacedIsUnreviewed() throws SQLException {
        everyOrderIsTheHostOrder( List.of( this::opened, this::approveA, this::pushB, this::merged ) );
        final Change c = f.only( item );
        assertEquals( MergeKind.UNREVIEWED, c.mergeKind() );
        assertEquals( "unreviewed", c.policyFlag() );
        assertNull( c.reviewedBy() );
        assertEquals( B, c.headSha() );
    }

    @Test
    void aCloseAndAReopenAtTheSameSecondEndClosedInBothOrders() throws SQLException {
        final List< Supplier< CodeEvent > > events = List.of( this::opened,
            () -> CodeEvents.closed( branch, "12", A, T3 ), () -> CodeEvents.reopened( branch, "12", A, T3 ) );
        final Outcome one = run( events, new int[] { 0, 1, 2 } );
        final Outcome other = run( events, new int[] { 0, 2, 1 } );
        assertEquals( one, other );
        final Change c = f.only( item );
        assertEquals( ChangeState.ABANDONED, c.state(), "at one host time a close outranks a reopen" );
        assertEquals( T3, c.closedAt() );
    }

    @Test
    void replayingEveryDeliveryOfAnOrderChangesNothing() throws SQLException {
        final List< Supplier< CodeEvent > > all = List.of( this::opened, this::approveA, this::closed, this::reopened,
            this::pushB, this::approveB, this::merged );
        final Outcome host = run( all, hostOrder( all.size() ) );
        final int[] scrambled = { 6, 2, 4, 0, 5, 3, 1 };
        final Outcome got = run( all, scrambled );
        assertEquals( host.rows(), got.rows() );
        assertEquals( host.heads(), got.heads() );
        final String before = text( "SELECT count(*) || '/' || ( SELECT count(*) FROM work_codehost_lifecycle )"
            + " FROM work_event" );
        for ( final int[] order : List.of( scrambled, hostOrder( all.size() ) ) ) {
            for ( final int i : order ) {
                assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( all.get( i ).get() ),
                    "replay of event " + i );
            }
        }
        assertEquals( got, outcome() );
        assertEquals( before, text( "SELECT count(*) || '/' || ( SELECT count(*) FROM work_codehost_lifecycle )"
            + " FROM work_event" ) );
        final Change c = f.only( item );
        assertEquals( MergeKind.REVIEWED, c.mergeKind() );
        assertNull( c.policyFlag() );
    }
}
