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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static com.wikantik.work.JdbcCodeHostRows.ts;

/**
 * The lifecycle register of a pull request's one change: which of its lifecycle events (opened, reopened, closed,
 * merged) is the latest, by the host's time and then a fixed rank (merged over closed over reopened over opened).
 * The change's state follows the register, not the order deliveries arrive in: an event with an older key than the
 * one recorded changes no state, one with a newer key applies whatever arrived first. A close abandons the change, a
 * reopen (or a newer opening) revives it, and a merge ends it for good: once merged, no close or reopen changes it,
 * and one stamped after the merge is logged at WARN and ignored.
 *
 * <p>Every lifecycle event the host reports is written to the activity log once, the first time it arrives, whether
 * or not it wins the register (a close as {@code change_abandoned}, a reopen as {@code change_updated} with the note
 * "reopened on the code host"; an opening and a merge have their own events elsewhere), so the log holds the same
 * rows whatever the order. {@code work_codehost_lifecycle} remembers which closes, reopens and merges were seen (at most {@link #KEPT} per
 * change; past that the register still moves but no further event is written).
 */
final class JdbcCodeHostLifecycle {

    private static final Logger LOG = LogManager.getLogger( JdbcCodeHostLifecycle.class );

    /** How many lifecycle events of one change are remembered. */
    static final int KEPT = 200;

    static final String REOPENED_NOTE = "reopened on the code host";
    static final String CLOSED_NOTE = "closed on the code host";

    /** A lifecycle event kind, in rank order (a later constant wins a tie of host times). */
    enum Kind {
        OPENED, REOPENED, CLOSED, MERGED;

        String wire() {
            return name().toLowerCase( Locale.ROOT );
        }

        static Kind fromWire( final String wire ) {
            return valueOf( wire.toUpperCase( Locale.ROOT ) );
        }
    }

    /** A register key: the host time, then the kind's rank. */
    record Key( Instant at, Kind kind ) implements Comparable< Key > {

        @Override
        public int compareTo( final Key o ) {
            final int byTime = at.compareTo( o.at );
            return byTime != 0 ? byTime : kind.compareTo( o.kind );
        }
    }

    private final JdbcCodeHostRows rows;

    JdbcCodeHostLifecycle( final JdbcCodeHostRows rows ) {
        this.rows = rows;
    }

    /** The change's winning lifecycle event; empty when none reached it. */
    Optional< Key > register( final Connection conn, final long changeId ) throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT lifecycle_at, lifecycle_kind FROM work_change WHERE id = ?",
            ps -> ps.setLong( 1, changeId ), rs -> {
                final Timestamp at = rs.getTimestamp( 1 );
                return Optional.ofNullable( at == null ? null : new Key( at.toInstant(), Kind.fromWire( rs.getString( 2 ) ) ) );
            } ).flatMap( k -> k );
    }

    /** Records the register as {@code key} (no state change). */
    void setRegister( final Connection conn, final long changeId, final Key key ) throws SQLException {
        rows.jdbc().update( conn, "UPDATE work_change SET lifecycle_at = ?, lifecycle_kind = ? WHERE id = ?", ps -> {
            ps.setTimestamp( 1, ts( key.at() ) );
            ps.setString( 2, key.kind().wire() );
            ps.setLong( 3, changeId );
        } );
    }

    /** Remembers that the host reported this lifecycle event; true when it was not remembered yet. */
    boolean remember( final Connection conn, final long changeId, final Key key ) throws SQLException {
        final long seen = rows.jdbc().queryOne( conn, "SELECT count(*) FROM work_codehost_lifecycle WHERE change_id = ?",
            ps -> ps.setLong( 1, changeId ), rs -> rs.getLong( 1 ) ).orElse( 0L );
        if ( seen >= KEPT ) {
            final boolean known = rows.jdbc().queryOne( conn, "SELECT 1 FROM work_codehost_lifecycle WHERE change_id = ? "
                + "AND kind = ? AND at = ?", ps -> bind( ps, changeId, key ), rs -> Boolean.TRUE ).isPresent();
            if ( !known ) {
                LOG.warn( "Code-host change {} has {} lifecycle events on record; a {} is not remembered", changeId,
                    seen, key.kind().wire() );
            }
            return false;
        }
        return rows.jdbc().update( conn, "INSERT INTO work_codehost_lifecycle (change_id, kind, at) VALUES (?,?,?) "
            + "ON CONFLICT DO NOTHING", ps -> bind( ps, changeId, key ) ) > 0;
    }

    private static void bind( final java.sql.PreparedStatement ps, final long changeId, final Key key )
            throws SQLException {
        ps.setLong( 1, changeId );
        ps.setString( 2, key.kind().wire() );
        ps.setTimestamp( 3, ts( key.at() ) );
    }

    /**
     * Applies an opening, a reopening or a close stamped {@code at} to the locked change {@code c}: the log row the
     * first time, then the state when the event wins the register. {@code APPLIED} when anything was written,
     * {@code DUPLICATE} when nothing, {@code IGNORED} for a close or reopen stamped after the change's merge.
     */
    CodeHostOutcome apply( final Connection conn, final Item item, final Change c, final Kind kind, final Instant at,
                           final boolean itemClosed, final Actor by ) throws SQLException {
        final Key key = new Key( at, kind );
        if ( c.state() == ChangeState.MERGED ) {
            if ( kind != Kind.OPENED && c.closedAt() != null && at.isAfter( c.closedAt() ) ) {
                LOG.warn( "Code-host {} stamped after the merge of change {} ignored", kind.wire(), c.id() );
                return CodeHostOutcome.IGNORED;
            }
            return logged( conn, item, c, key, by ) ? CodeHostOutcome.APPLIED : CodeHostOutcome.DUPLICATE;
        }
        final boolean fresh = logged( conn, item, c, key, by );
        boolean wrote = fresh;
        final Optional< Key > reg = register( conn, c.id() );
        if ( reg.isEmpty() || key.compareTo( reg.get() ) > 0 ) {
            setRegister( conn, c.id(), key );
            settle( conn, c, kind, at, itemClosed );
            wrote = true;
        } else if ( fresh && kind == Kind.CLOSED && itemClosed ) {
            // a close that loses the register to a later reopen still dates the abandonment when the change stayed
            // abandoned (its item is closed): closed_at is the latest close the host reported, in any order. Only a
            // close this delivery logged moves it: past the lifecycle bound nothing is logged, so nothing moves.
            wrote |= laterClose( conn, c, at );
        }
        return wrote ? CodeHostOutcome.APPLIED : CodeHostOutcome.DUPLICATE;
    }

    /** Moves an abandoned change's {@code closed_at} up to {@code at} when that is later; true when it wrote. */
    private boolean laterClose( final Connection conn, final Change c, final Instant at ) throws SQLException {
        if ( c.state() != ChangeState.ABANDONED ) {
            return false;
        }
        final long changeId = c.id();
        return rows.jdbc().update( conn, "UPDATE work_change SET closed_at = GREATEST( ?, opened_at ) WHERE id = ? "
            + "AND state = 'abandoned' AND ( closed_at IS NULL OR closed_at < GREATEST( ?, opened_at ) )", ps -> {
                ps.setTimestamp( 1, ts( at ) );
                ps.setLong( 2, changeId );
                ps.setTimestamp( 3, ts( at ) );
            } ) > 0;
    }

    /** The log row of a lifecycle event the first time it is reported; true when written. */
    private boolean logged( final Connection conn, final Item item, final Change c, final Key key, final Actor by )
            throws SQLException {
        // an opening has no log row of its own (the change's change_opened is written once, by whatever event made
        // it), so there is nothing to remember it for
        if ( key.kind() == Kind.OPENED || !remember( conn, c.id(), key ) ) {
            return false;
        }
        if ( key.kind() == Kind.CLOSED ) {
            rows.event( conn, item, c.id(), by, WorkEvent.CHANGE_ABANDONED, c.branch(), CLOSED_NOTE );
        } else if ( key.kind() == Kind.REOPENED ) {
            rows.event( conn, item, c.id(), by, WorkEvent.CHANGE_UPDATED, null, REOPENED_NOTE );
        }
        return true;
    }

    /** The state the winning event gives: abandoned at its time for a close, live again for an opening or reopen. */
    private void settle( final Connection conn, final Change c, final Kind kind, final Instant at,
                         final boolean itemClosed ) throws SQLException {
        if ( kind == Kind.CLOSED ) {
            rows.jdbc().update( conn, "UPDATE work_change SET state = 'abandoned', closed_at = GREATEST( ?, opened_at ) "
                + "WHERE id = ?", ps -> {
                    ps.setTimestamp( 1, ts( at ) );
                    ps.setLong( 2, c.id() );
                } );
            return;
        }
        if ( c.state() != ChangeState.ABANDONED || itemClosed ) {
            // a closed item holds no live change: the register moves, the change stays as it is
            return;
        }
        final ChangeState live = reviewRequested( conn, c ) || c.approvedForHead() ? ChangeState.IN_REVIEW
            : ChangeState.OPEN;
        rows.jdbc().update( conn, "UPDATE work_change SET state = ?, closed_at = NULL WHERE id = ?", ps -> {
            ps.setString( 1, live.wire() );
            ps.setLong( 2, c.id() );
        } );
    }

    /**
     * The host asked for review of the change (an opening that is not a draft, ready for review, a review request):
     * a live change that is open moves to {@code in_review}, and the request is logged once per change, whatever
     * the change's state, so the log does not depend on order. True when anything was written.
     */
    boolean requestReview( final Connection conn, final Item item, final Change c, final Actor by ) throws SQLException {
        boolean wrote = false;
        if ( c.state() == ChangeState.OPEN ) {
            rows.jdbc().update( conn, "UPDATE work_change SET state = ? WHERE id = ?", ps -> {
                ps.setString( 1, ChangeState.IN_REVIEW.wire() );
                ps.setLong( 2, c.id() );
            } );
            wrote = true;
        }
        if ( !reviewRequested( conn, c ) ) {
            rows.event( conn, item, c.id(), by, WorkEvent.REVIEW_REQUESTED, c.branch(), null );
            wrote = true;
        }
        return wrote;
    }

    private boolean reviewRequested( final Connection conn, final Change c ) throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT 1 FROM work_event WHERE item_id = ? AND from_value = ? "
            + "AND type = 'review_requested' LIMIT 1", ps -> {
                ps.setLong( 1, c.itemId() );
                ps.setString( 2, "change:" + c.id() );
            }, rs -> Boolean.TRUE ).isPresent();
    }

    /**
     * Before a merge stamped {@code at} is recorded: a close or reopen the register holds from after the merge is
     * logged at WARN and loses (a merge is terminal), then the register becomes the merge.
     */
    void merged( final Connection conn, final long changeId, final Instant at ) throws SQLException {
        final Optional< Key > reg = register( conn, changeId );
        if ( reg.isPresent() && reg.get().at().isAfter( at ) && reg.get().kind() != Kind.OPENED ) {
            LOG.warn( "Code-host {} stamped after the merge of change {} ignored", reg.get().kind().wire(), changeId );
        }
        final Key key = new Key( at, Kind.MERGED );
        remember( conn, changeId, key );
        setRegister( conn, changeId, key );
    }
}
