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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.wikantik.work.JdbcCodeHostRows.ts;

/**
 * Heads and approvals by the code host's own times ({@code work_codehost_head}), so the final head and the approval
 * on it come out the same whatever order the deliveries arrive in.
 *
 * <p>Every commit the host reports as a change's head is kept with the earliest host time it was reported. The host's
 * view of the head is the latest of those by host time, ties broken by the larger sha (a fixed, if arbitrary, order).
 * The change's head moves only when that view moves: a redelivered or overtaken report of an older commit leaves it
 * where it is, and so does a report that changes nothing the host knew, which keeps a head an agent or a person set
 * since. When the host has no view yet (an agent's change) a commit the change already had is not set again (P5-3).
 * A merged change's head is the commit that was merged and never moves; reports stamped after its merge are dropped.
 *
 * <p>Each commit the host reports as a head is written once to the activity log ({@code change_updated}, to the
 * commit), the first time it is reported, unless an event of the change already names it; the head moving writes
 * nothing, so the log does not depend on the order reports arrive in.
 *
 * <p>Each head report prunes the change's rows to the {@link #KEPT} most recent reports and approvals (by host time),
 * plus the row of its current head and the row of the host's newest head report, so a sender cannot grow the table
 * without bound through head reports, and the newest head is never lost to later approvals of older commits. An
 * approval prunes nothing: an approval of a commit not yet reported as a head is kept until the next head report,
 * so it still stands when that commit's push arrives. The limit that remains (outside the order guarantee): more
 * than {@code KEPT} later-stamped head reports or approvals arriving, interleaved with head reports, before a
 * commit becomes the head can drop that commit's approval; its redelivery then logs a second
 * {@code change_reviewed}. Only a sender holding the hook secret can produce that many.
 *
 * <p>An approval belongs to the commit it approved. The earliest the host reported for each commit is kept (ties: the
 * smaller name); it stands on the change only while that commit is the head, so an approval that arrives before the
 * push of its commit is applied when the commit becomes the head, and one on a commit since replaced never counts.
 * An approval an agent or a person gave is never replaced. Each commit's first host approval writes one
 * {@code change_reviewed} event, whether or not it ever stands. The author's own approval is never recorded (the
 * caller drops it).
 */
final class JdbcCodeHostHeads {

    /** How many head reports of one change are kept (the most recent by host time). */
    static final int KEPT = 200;

    /** An approval the host reported for one commit. */
    record Approval( String login, String external, Instant at ) {
    }

    private final JdbcCodeHostRows rows;

    JdbcCodeHostHeads( final JdbcCodeHostRows rows ) {
        this.rows = rows;
    }

    /**
     * Records that the host reported {@code sha} as the head at {@code at} (no move) and writes its event when the
     * commit is new to the change; true when anything was written.
     */
    boolean record( final Connection conn, final Item item, final Change c, final String sha, final Instant at,
                    final Actor by ) throws SQLException {
        if ( sha == null ) {
            return false;
        }
        final boolean wrote = rows.jdbc().update( conn, "INSERT INTO work_codehost_head (change_id, sha, seen_at) "
            + "VALUES (?,?,?) ON CONFLICT (change_id, sha) DO UPDATE SET seen_at = EXCLUDED.seen_at "
            + "WHERE work_codehost_head.seen_at IS NULL OR EXCLUDED.seen_at < work_codehost_head.seen_at", ps -> {
                ps.setLong( 1, c.id() );
                ps.setString( 2, sha );
                ps.setTimestamp( 3, ts( at ) );
            } ) > 0;
        if ( !wrote ) {
            return false;
        }
        prune( conn, c.id() );
        if ( !kept( conn, c.id(), sha ) ) {
            return false;
        }
        if ( !named( conn, item, c.id(), sha ) ) {
            rows.event( conn, item, c.id(), by, WorkEvent.CHANGE_UPDATED, sha, null );
        }
        return true;
    }

    /**
     * Drops every report and approval of the change but the {@link #KEPT} most recent, its current head's row and the
     * row of the host's newest head report (the host's view of the head), so that later approvals of older commits
     * can never crowd the newest head out before it becomes the head. At most {@code KEPT + 2} rows stay. Called by
     * head reports only.
     */
    private void prune( final Connection conn, final long changeId ) throws SQLException {
        rows.jdbc().update( conn, """
            DELETE FROM work_codehost_head h WHERE h.change_id = ?
               AND h.sha IS DISTINCT FROM ( SELECT head_sha FROM work_change WHERE id = ? )
               AND h.sha IS DISTINCT FROM ( SELECT l.sha FROM work_codehost_head l WHERE l.change_id = ?
                                             AND l.seen_at IS NOT NULL ORDER BY l.seen_at DESC, l.sha DESC LIMIT 1 )
               AND h.sha NOT IN ( SELECT k.sha FROM work_codehost_head k WHERE k.change_id = ?
                                   ORDER BY GREATEST( k.seen_at, k.approved_at ) DESC NULLS LAST, k.sha DESC LIMIT ? )
            """, ps -> {
                ps.setLong( 1, changeId );
                ps.setLong( 2, changeId );
                ps.setLong( 3, changeId );
                ps.setLong( 4, changeId );
                ps.setInt( 5, KEPT );
            } );
    }

    private boolean kept( final Connection conn, final long changeId, final String sha ) throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT 1 FROM work_codehost_head WHERE change_id = ? AND sha = ? "
            + "AND seen_at IS NOT NULL", ps -> {
                ps.setLong( 1, changeId );
                ps.setString( 2, sha );
            }, rs -> Boolean.TRUE ).isPresent();
    }

    /** Whether an event of the change already names {@code sha} as a head (its opening note or an update). */
    private boolean named( final Connection conn, final Item item, final long changeId, final String sha )
            throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT 1 FROM work_event WHERE item_id = ? AND from_value = ? "
            + "AND ( ( type = 'change_opened' AND note = ? ) OR ( type = 'change_updated' AND to_value = ? ) ) LIMIT 1",
            ps -> {
                ps.setLong( 1, item.id() );
                ps.setString( 2, "change:" + changeId );
                ps.setString( 3, sha );
                ps.setString( 4, sha );
            }, rs -> Boolean.TRUE ).isPresent();
    }

    /** The host's view of the head: the latest reported commit by host time; null when it reported none. */
    String hostHead( final Connection conn, final long changeId ) throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT sha FROM work_codehost_head WHERE change_id = ? AND seen_at IS NOT NULL "
            + "ORDER BY seen_at DESC, sha DESC LIMIT 1", ps -> ps.setLong( 1, changeId ), rs -> rs.getString( 1 ) )
            .orElse( null );
    }

    /**
     * A head report: records it and, on a change that is not merged, moves the head when the host's view moved to
     * another commit (clearing the approval, then letting the host's approval of the new head stand). True when
     * anything was written.
     */
    boolean observe( final Connection conn, final Item item, final Change c, final String sha, final Instant at,
                     final Actor by ) throws SQLException {
        if ( sha == null ) {
            return false;
        }
        if ( c.state() == ChangeState.MERGED ) {
            return ( c.closedAt() == null || !at.isAfter( c.closedAt() ) ) && record( conn, item, c, sha, at, by );
        }
        final String before = hostHead( conn, c.id() );
        // P5-3: with no host view yet, a commit the change already had is not set again (decided before this report
        // writes its own event naming it)
        final boolean hadIt = before == null && rows.seenOn( conn, item.id(), c.branch(), sha ) != null;
        final boolean wrote = record( conn, item, c, sha, at, by );
        final String after = hostHead( conn, c.id() );
        if ( after == null || after.equals( before ) || after.equals( c.headSha() ) || hadIt && after.equals( sha ) ) {
            return wrote;
        }
        moveTo( conn, item, c, after, by );
        return true;
    }

    /** Makes {@code sha} the change's head (clearing the old approval) and lets the host's approval of it stand. */
    void moveTo( final Connection conn, final Item item, final Change c, final String sha, final Actor by )
            throws SQLException {
        rows.moveHead( conn, c.id(), sha );
        applyStanding( conn, rows.byId( conn, c.id() ) );
    }

    /**
     * Records the host's approval of {@code sha} (the caller has dropped the author's own) and lets it stand when
     * {@code sha} is the head and no agent's or person's approval stands; true when anything was written.
     */
    boolean approve( final Connection conn, final Item item, final Change c, final String sha,
                     final CodeHostWho reviewer, final Instant at, final Actor by ) throws SQLException {
        if ( sha == null ) {
            return false;
        }
        final Optional< Approval > before = approvalOf( conn, c.id(), sha );
        recordApproval( conn, c.id(), sha, reviewer, at );
        final Optional< Approval > after = approvalOf( conn, c.id(), sha );
        if ( after.equals( before ) ) {
            return false;
        }
        if ( before.isEmpty() ) {
            rows.event( conn, item, c.id(), by, WorkEvent.CHANGE_REVIEWED, "approved", null );
        }
        if ( sha.equals( c.headSha() ) && ( !c.approvedForHead() || hostStanding( c, before ) ) ) {
            stand( conn, c, after.orElseThrow() );
        }
        return true;
    }

    /** Keeps the earliest approval of each commit by host time (ties: the smaller name); idempotent. */
    void recordApproval( final Connection conn, final long changeId, final String sha, final CodeHostWho reviewer,
                         final Instant at ) throws SQLException {
        rows.jdbc().update( conn, """
            INSERT INTO work_codehost_head (change_id, sha, approved_by, external_approved_by, approved_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (change_id, sha) DO UPDATE SET approved_by = EXCLUDED.approved_by,
                external_approved_by = EXCLUDED.external_approved_by, approved_at = EXCLUDED.approved_at
             WHERE work_codehost_head.approved_at IS NULL OR EXCLUDED.approved_at < work_codehost_head.approved_at
                OR ( EXCLUDED.approved_at = work_codehost_head.approved_at
                     AND COALESCE( EXCLUDED.approved_by, EXCLUDED.external_approved_by, '' )
                       < COALESCE( work_codehost_head.approved_by, work_codehost_head.external_approved_by, '' ) )
            """, ps -> {
                ps.setLong( 1, changeId );
                ps.setString( 2, sha );
                ps.setString( 3, reviewer.login() );
                ps.setString( 4, reviewer.login() == null ? reviewer.external() : null );
                ps.setTimestamp( 5, ts( at ) );
            } );
        // never pruned here: an approval of a commit whose head report has not arrived yet must still be there when
        // it does, however many later-stamped approvals of other commits come first (review I-1)
    }

    /** The approval the host reported for {@code sha} on the change; empty when none. */
    Optional< Approval > approvalOf( final Connection conn, final long changeId, final String sha ) throws SQLException {
        if ( sha == null ) {
            return Optional.empty();
        }
        return rows.jdbc().queryOne( conn, "SELECT approved_by, external_approved_by, approved_at FROM work_codehost_head "
            + "WHERE change_id = ? AND sha = ? AND approved_at IS NOT NULL", ps -> {
                ps.setLong( 1, changeId );
                ps.setString( 2, sha );
            }, rs -> new Approval( rs.getString( 1 ), rs.getString( 2 ), rs.getTimestamp( 3 ).toInstant() ) );
    }

    /** Whether the approval standing on the change's head is the host's {@code a} (not an agent's or a person's). */
    static boolean hostStanding( final Change c, final Optional< Approval > a ) {
        return a.isPresent() && c.approvedForHead() && c.reviewedByAgentId() == null
            && Objects.equals( c.reviewedBy(), a.get().login() )
            && Objects.equals( c.externalReviewedBy(), a.get().external() ) && a.get().at().equals( c.reviewedAt() );
    }

    /**
     * Lets the host's approval of the head stand when no approval stands on it. True when it wrote. A change that is
     * not merged takes it; a live one moves to {@code in_review}.
     */
    boolean applyStanding( final Connection conn, final Change c ) throws SQLException {
        if ( c.approvedForHead() || c.state() == ChangeState.MERGED ) {
            return false;
        }
        final Optional< Approval > a = approvalOf( conn, c.id(), c.headSha() );
        if ( a.isEmpty() ) {
            return false;
        }
        stand( conn, c, a.get() );
        return true;
    }

    private void stand( final Connection conn, final Change c, final Approval a ) throws SQLException {
        rows.jdbc().update( conn, "UPDATE work_change SET state = CASE WHEN state = 'open' THEN 'in_review' "
                + "ELSE state END, reviewed_by = ?, reviewed_by_agent_id = NULL, external_reviewed_by = ?, "
                + "reviewed_head_sha = head_sha, reviewed_at = ? WHERE id = ?", ps -> {
                    ps.setString( 1, a.login() );
                    ps.setString( 2, a.external() );
                    ps.setTimestamp( 3, ts( a.at() ) );
                    ps.setLong( 4, c.id() );
                } );
    }

    /** Clears an approval standing on the change (the host's, stamped after the merge it would have counted for). */
    void clearApproval( final Connection conn, final long changeId ) throws SQLException {
        rows.jdbc().update( conn, "UPDATE work_change SET reviewed_by = NULL, reviewed_by_agent_id = NULL, "
            + "external_reviewed_by = NULL, reviewed_head_sha = NULL, reviewed_at = NULL WHERE id = ?",
            ps -> ps.setLong( 1, changeId ) );
    }
}
