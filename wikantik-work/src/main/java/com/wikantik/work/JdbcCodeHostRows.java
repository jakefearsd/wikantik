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

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.NullableColumns;
import com.wikantik.jdbc.TransactionBody;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.wikantik.work.SqlValues.timestamp;

/**
 * The row-level steps every code-host write shares: the per-product advisory lock, change lookups that lock the row,
 * the item share lock, head moves, the "was this commit ever seen" history check, and the system actor's events.
 *
 * <p><b>Lock order</b> (the one place it is written down; every code-host write and {@code JdbcWorkChanges.open}
 * follow it): 1. the product's advisory lock ({@link #lockProduct}); 2. {@code work_change} rows {@code FOR UPDATE},
 * several of them in id order ({@link #lockInIdOrder}); 3. the {@code work_item} row {@code FOR SHARE}. A decision
 * that depends on a change is taken after its row is locked, never on an earlier read. An agent's change write
 * ({@code JdbcWorkChanges.write}) takes 2 for one row and then 3, and never 1, so no cycle is possible.
 */
final class JdbcCodeHostRows {

    /** First key of the advisory lock; the second is the product id. Distinct from the audit chain's one-bigint key. */
    static final int LOCK_CLASS = 1464550216;

    private static final String LIVE = " AND state IN ('open','in_review')";

    /** The item as its row is under a share lock. */
    record LockedItem( boolean closed ) {

        /** Marks an item that no longer targets the product (never handed to a caller). */
        static final LockedItem ELSEWHERE = new LockedItem( false );
    }

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcCodeHostRows( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    Jdbc jdbc() {
        return jdbc;
    }

    /** Takes the product's advisory lock for the rest of the transaction (lock order step 1). */
    static void lockProduct( final Jdbc jdbc, final Connection conn, final long productId ) throws SQLException {
        jdbc.queryOne( conn, "SELECT pg_advisory_xact_lock(?, ?::int)", ps -> {
            ps.setInt( 1, LOCK_CLASS );
            ps.setLong( 2, productId );
        }, rs -> Boolean.TRUE );
    }

    /** One transaction that serialises with every other code-host write of the product. */
    < T > T locked( final long productId, final Actor by, final TransactionBody< T > body ) throws SQLException {
        ActorRules.requireSystem( by );
        return jdbc.inTransaction( conn -> {
            lockProduct( jdbc, conn, productId );
            return body.run( conn );
        } );
    }

    Change byId( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, JdbcWorkChanges.SELECT + " WHERE id = ?", ps -> ps.setLong( 1, id ),
            JdbcWorkChanges.ROW ).orElseThrow( () -> new SQLException( "work_change " + id + " not found" ) );
    }

    /** Locks the given change rows {@code FOR UPDATE} in id order (lock order step 2); nulls are skipped. */
    void lockInIdOrder( final Connection conn, final Long... ids ) throws SQLException {
        final Long[] some = Arrays.stream( ids ).filter( Objects::nonNull ).sorted().distinct().toArray( Long[]::new );
        if ( some.length == 0 ) {
            return;
        }
        jdbc.query( conn, "SELECT id FROM work_change WHERE id = ANY( ?::bigint[] ) ORDER BY id FOR UPDATE",
            ps -> ps.setArray( 1, ps.getConnection().createArrayOf( "bigint", some ) ), rs -> rs.getLong( 1 ) );
    }

    /**
     * The id of the item's one change for this pull request, read without a lock; null when none. The change with
     * the pull request's number is the one (whatever its state: a closed pull request's change is reopened, never
     * replaced, and idx_work_change_review keeps it unique); only when no change carries the number is a live change
     * on the branch taken, and only one no pull request is linked to yet (an agent's or a push's change), which the
     * caller then adopts: a change linked to one pull request is never handed to another from the same branch.
     */
    Long prChangeId( final Connection conn, final long itemId, final String number, final String branch )
            throws SQLException {
        final Optional< Long > byNumber = jdbc.queryOne( conn, "SELECT id FROM work_change WHERE item_id = ? "
                + "AND review_number = ?", ps -> {
                    ps.setLong( 1, itemId );
                    ps.setString( 2, number );
                }, rs -> rs.getLong( 1 ) );
        if ( byNumber.isPresent() ) {
            return byNumber.get();
        }
        return jdbc.queryOne( conn, "SELECT id FROM work_change WHERE item_id = ? AND branch = ? "
                + "AND review_number IS NULL" + LIVE + " ORDER BY id LIMIT 1", ps -> {
                    ps.setLong( 1, itemId );
                    ps.setString( 2, branch );
                }, rs -> rs.getLong( 1 ) ).orElse( null );
    }

    /** The change with this id locked {@code FOR UPDATE} (lock order step 2); null for a null id. */
    Change lockedChange( final Connection conn, final Long id ) throws SQLException {
        if ( id == null ) {
            return null;
        }
        lockInIdOrder( conn, id );
        return byId( conn, id );
    }

    /**
     * The changes of the item on the branch a push stamped {@code at} belongs to, locked in id order: every live
     * change, every change the code host's close abandoned (a pull request that may be reopened keeps following its
     * branch), and every merged change merged at or after {@code at} (the push came before that merge).
     */
    List< Change > pushTargets( final Connection conn, final long itemId, final String branch, final Instant at )
            throws SQLException {
        return jdbc.query( conn, JdbcWorkChanges.SELECT + " WHERE item_id = ? AND branch = ? AND ( state IN "
            + "('open','in_review') OR ( state = 'abandoned' AND lifecycle_kind = 'closed' ) "
            + "OR ( state = 'merged' AND closed_at >= ? ) ) ORDER BY id FOR UPDATE", ps -> {
                ps.setLong( 1, itemId );
                ps.setString( 2, branch );
                ps.setTimestamp( 3, ts( at ) );
            }, JdbcWorkChanges.ROW );
    }

    /**
     * Lowers a code-host change's {@code opened_at} to {@code at} when the host reports something of it earlier, and
     * keeps {@code first_commit_at} at or before it; true when it wrote. A change an agent or a person opened keeps
     * the time they reported.
     */
    boolean lowerOpened( final Connection conn, final long id, final Instant at ) throws SQLException {
        return jdbc.update( conn, "UPDATE work_change SET opened_at = ?, first_commit_at = CASE WHEN first_commit_at > ? "
            + "THEN ? ELSE first_commit_at END WHERE id = ? AND source = 'codehost' AND opened_at > ?", ps -> {
                ps.setTimestamp( 1, ts( at ) );
                ps.setTimestamp( 2, ts( at ) );
                ps.setTimestamp( 3, ts( at ) );
                ps.setLong( 4, id );
                ps.setTimestamp( 5, ts( at ) );
            } ) > 0;
    }

    /** Locks the item row; null when it no longer targets {@code productId}. */
    LockedItem lockItem( final Connection conn, final long itemId, final long productId ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT status, product_id FROM work_item WHERE id = ? FOR SHARE",
            ps -> ps.setLong( 1, itemId ), rs -> {
                final Long product = NullableColumns.getNullableLong( rs, "product_id" );
                return product == null || product != productId ? LockedItem.ELSEWHERE
                    : new LockedItem( SqlValues.isClosed( WorkStatus.fromWire( rs.getString( "status" ) ) ) );
            } ).filter( l -> l != LockedItem.ELSEWHERE ).orElse( null );
    }

    /**
     * The id of a change of this item and branch that has, or ever had, {@code sha} as its head (its own row, its
     * {@code change_opened} note or a {@code change_updated} value); the newest such change, or null.
     */
    Long seenOn( final Connection conn, final long itemId, final String branch, final String sha ) throws SQLException {
        return seen( conn, itemId, branch, sha, null );
    }

    /**
     * As {@link #seenOn}, among closed changes no other pull request than {@code number} is linked to: what a late
     * pull-request event must not revive (an agent's merged change, this pull request's own history).
     */
    Long seenOnClosed( final Connection conn, final long itemId, final String branch, final String sha,
                       final String number ) throws SQLException {
        return seen( conn, itemId, branch, sha, number );
    }

    /** {@code closedFor} null: any change; else closed changes linked to no pull request or to that one. */
    private Long seen( final Connection conn, final long itemId, final String branch, final String sha,
                       final String closedFor ) throws SQLException {
        if ( sha == null ) {
            return null;
        }
        final String and = closedFor == null ? "" : " AND c.state IN ('merged','abandoned') "
            + "AND ( c.review_number IS NULL OR c.review_number = ? )";
        return jdbc.queryOne( conn, "SELECT max(id) FROM ( SELECT c.id FROM work_change c WHERE c.item_id = ? "
            + "AND c.branch = ? AND c.head_sha = ?" + and + " UNION ALL SELECT c.id FROM work_change c JOIN work_event e "
            + "ON e.from_value = 'change:' || c.id WHERE c.item_id = ? AND c.branch = ? AND e.item_id = c.item_id"
            + and + " AND ( ( e.type = 'change_opened' AND e.note = ? ) OR ( e.type = 'change_updated' "
            + "AND e.to_value = ? ) ) ) seen", ps -> {
                int i = 1;
                ps.setLong( i++, itemId );
                ps.setString( i++, branch );
                ps.setString( i++, sha );
                if ( closedFor != null ) {
                    ps.setString( i++, closedFor );
                }
                ps.setLong( i++, itemId );
                ps.setString( i++, branch );
                if ( closedFor != null ) {
                    ps.setString( i++, closedFor );
                }
                ps.setString( i++, sha );
                ps.setString( i, sha );
            }, rs -> {
                final long id = rs.getLong( 1 );
                return rs.wasNull() ? 0L : id;
            } ).filter( id -> id > 0 ).orElse( null );
    }

    /**
     * Sets a new head and clears any approval of the old one, whoever gave it (an approval is of a commit); an agent
     * id is never appended (the system actor has none, P5-17).
     */
    void moveHead( final Connection conn, final long id, final String head ) throws SQLException {
        jdbc.update( conn, "UPDATE work_change SET head_sha = ?, reviewed_by = NULL, reviewed_by_agent_id = NULL, "
                + "external_reviewed_by = NULL, reviewed_head_sha = NULL, reviewed_at = NULL WHERE id = ?", ps -> {
                    ps.setString( 1, head );
                    ps.setLong( 2, id );
                } );
    }

    void setFirstCommit( final Connection conn, final long id, final Instant at ) throws SQLException {
        jdbc.update( conn, "UPDATE work_change SET first_commit_at = ? WHERE id = ?", ps -> {
            ps.setTimestamp( 1, timestamp( at ) );
            ps.setLong( 2, id );
        } );
    }

    /** One event on the item, by the system actor; {@code from_value} names the change. */
    void event( final Connection conn, final Item item, final long changeId, final Actor by, final String type,
                final String to, final String note ) throws SQLException {
        catalog.appendEvent( conn, new WorkEvent( 0, item.projectId(), item.id(), null, null, by.login(), by.kind(),
            by.agentId(), type, "change:" + changeId, to, note ) );
    }

    /** The latest a first commit can be for this change: when it opened (and so before it closed). */
    static Instant cap( final Change c ) {
        if ( c.openedAt() == null ) {
            return c.closedAt();
        }
        return c.closedAt() != null && c.closedAt().isBefore( c.openedAt() ) ? c.closedAt() : c.openedAt();
    }

    /**
     * The earlier of the recorded and the reported first commit time; never later than {@code cap} (the change's
     * opening, see {@link #cap}); null when neither is known.
     */
    static Instant earlier( final Instant recorded, final Instant reported, final Instant cap ) {
        Instant best = recorded;
        if ( reported != null && ( best == null || reported.isBefore( best ) ) ) {
            best = reported;
        }
        return best != null && cap != null && best.isAfter( cap ) ? cap : best;
    }

    /**
     * Lowers the first commit time when {@code reported} is earlier; true when it wrote. It writes no event: the
     * first commit is the minimum of every report, a fact whose value, not its history, is the record.
     */
    boolean lowerFirstCommit( final Connection conn, final Change c, final Instant reported ) throws SQLException {
        final Instant earlier = earlier( c.firstCommitAt(), reported, cap( c ) );
        if ( earlier == null || earlier.equals( c.firstCommitAt() ) ) {
            return false;
        }
        setFirstCommit( conn, c.id(), earlier );
        return true;
    }

    static Timestamp ts( final Instant at ) {
        return timestamp( at );
    }

    /**
     * What a new code-host change starts with: {@code at} is the host's time of the event that opens it (its
     * {@code opened_at}, lowered by any earlier event later).
     */
    record Fresh( String branch, String base, String head, String number, String url, Instant firstCommit,
                  CodeHostWho opener, Instant at ) {
    }

    long insert( final Connection conn, final Item item, final long productId, final Fresh n, final Actor by )
            throws SQLException {
        final CodeHostWho who = n.opener() == null ? CodeHostWho.UNKNOWN : n.opener();
        final Instant first = earlier( null, n.firstCommit(), n.at() );
        final long id = jdbc.queryOne( conn, """
            INSERT INTO work_change (item_id, product_id, branch, base_branch, head_sha, review_url, review_number,
                state, opened_by, external_opened_by, source, first_commit_at, opened_at)
            VALUES (?,?,?,?,?,?,?, 'open', ?, ?, 'codehost', ?, ?) RETURNING id
            """, ps -> {
                ps.setLong( 1, item.id() );
                ps.setLong( 2, productId );
                ps.setString( 3, n.branch() );
                ps.setString( 4, n.base() );
                ps.setString( 5, n.head() );
                ps.setString( 6, n.url() );
                ps.setString( 7, n.number() );
                ps.setString( 8, who.login() );
                ps.setString( 9, who.login() == null ? who.external() : null );
                ps.setTimestamp( 10, first == null ? null : ts( first ) );
                ps.setTimestamp( 11, ts( n.at() ) );
            }, rs -> rs.getLong( 1 ) ).orElseThrow( () -> new SQLException( "INSERT work_change returned no row" ) );
        // no head in the note: which head a change was first seen with depends on arrival; the heads it had are the
        // change_updated events JdbcCodeHostHeads writes, one per commit
        event( conn, item, id, by, WorkEvent.CHANGE_OPENED, n.branch(), null );
        return id;
    }

    /**
     * Links a live change to the pull request when nothing is linked yet: fills only empty columns (number, link, the
     * outside opener when nobody inside opened it). True when it wrote.
     */
    boolean adopt( final Connection conn, final Change c, final WorkCodeHostStore.ReviewFacts f ) throws SQLException {
        final boolean number = c.reviewNumber() == null && f.number() != null;
        final boolean url = c.reviewUrl() == null && f.url() != null;
        final boolean external = c.openedBy() == null && c.openedByAgentId() == null && c.externalOpenedBy() == null
            && f.author() != null;
        if ( !number && !url && !external ) {
            return false;
        }
        jdbc.update( conn, "UPDATE work_change SET review_number = COALESCE(review_number, ?), "
                + "review_url = COALESCE(review_url, ?), external_opened_by = COALESCE(external_opened_by, ?) "
                + "WHERE id = ?", ps -> {
                    ps.setString( 1, f.number() );
                    ps.setString( 2, f.url() );
                    ps.setString( 3, external ? f.author().external() : null );
                    ps.setLong( 4, c.id() );
                } );
        return true;
    }
}
