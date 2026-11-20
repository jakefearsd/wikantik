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

import com.wikantik.jdbc.NullableColumns;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.RowMapper;
import com.wikantik.jdbc.SqlBinder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.wikantik.work.SqlValues.autonomyWire;
import static com.wikantik.work.SqlValues.doneMeansWire;
import static com.wikantik.work.SqlValues.isClosed;
import static com.wikantik.jdbc.NullableColumns.setNullableBoolean;
import static com.wikantik.jdbc.NullableColumns.setNullableDate;
import static com.wikantik.jdbc.NullableColumns.setNullableLong;

/**
 * The one statement sequence that changes an item: lock the row, fence an agent off another agent's claim, update,
 * record the revision and the events, and end the claim the change ends. Split from {@link JdbcWorkStore} to keep
 * both under the complexity limits.
 */
final class JdbcWorkItemWrites {

    private static final long NO_HOLDER = -1L;
    private static final double RANK_STEP = 1024.0;

    private final Jdbc jdbc;
    private final JdbcWorkStore store;

    JdbcWorkItemWrites( final Jdbc jdbc, final JdbcWorkStore store ) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** True when the change moves or reassigns the item, which ends any claim on it. */
    private static boolean endsClaim( final Item current, final Item next ) {
        return current.status() != next.status() || !Objects.equals( current.assignee(), next.assignee() )
            || !Objects.equals( current.queue(), next.queue() );
    }

    WorkStore.ItemWrite writeItem( final Item current, final Item next, final Actor by, final String note,
                                final boolean asHandoff, final CloseRules rules ) throws SQLException {
        final boolean ackRequired = rules.ackRequired();
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        JdbcWorkCatalog.requireOneTarget( next );
        final boolean clearClaim = endsClaim( current, next );
        final boolean textChanged = !Objects.equals( current.title(), next.title() )
            || !Objects.equals( current.body(), next.body() );
        final boolean closing = !isClosed( current.status() ) && isClosed( next.status() );
        final boolean reopening = isClosed( current.status() ) && !isClosed( next.status() );
        final int version = current.version() + 1;
        final String body = next.body() == null ? "" : next.body();
        return jdbc.inTransaction( conn -> {
            final long holder = lockHolder( conn, current.id() );
            requireNotFenced( by, holder );
            requireNoLiveChange( conn, current, next );
            requireShipped( conn, current, next, rules );
            final int rows = update( conn, current, next, body, new Flags( clearClaim, closing, reopening, ackRequired,
                next.status() == WorkStatus.DONE && current.status() != WorkStatus.DONE,
                current.status() == WorkStatus.DONE && next.status() != WorkStatus.DONE ) );
            if ( rows == 0 ) {
                throw new WorkConflictException( "Item " + current.key() + " changed since version "
                    + current.version() );
            }
            if ( textChanged ) {
                store.insertRevision( conn, current.id(), version, next.title(), body, by );
            }
            for ( final WorkEvent e : WorkEventDiff.changeEvents( current, next, by, note, asHandoff ) ) {
                store.appendEvent( conn, e );
            }
            final boolean ended = clearClaim && holder != NO_HOLDER;
            if ( ended ) {
                store.appendEvent( conn, WorkEventDiff.releasedEvent( current, next, by, holder ) );
            }
            return new WorkStore.ItemWrite( store.itemById( conn, current.id() ), ended ? holder : null );
        } );
    }

    private record Ranked( long id, double rank ) {
    }

    /**
     * Gives a column the order a move asked for. A column is ordered by {@code (rank, id)} and a client drops a card
     * between two equal ranks by sending that same rank, so the order is renumbered when the moved card does not sit
     * strictly between its wanted neighbours. Writes rank only: no version, no event, no revision.
     *
     * @return true when ranks were rewritten
     */
    boolean renumberColumn( final long projectId, final WorkStatus status, final long movedItemId,
                            final Long beforeItemId ) throws SQLException {
        // decide on an unlocked read first: the common move needs no renumber and takes no locks
        if ( plan( readColumn( null, projectId, status, "" ), movedItemId, beforeItemId ) == null ) {
            return false;
        }
        return jdbc.inTransaction( conn -> {
            // lock in id order (no deadlock between two renumbers), then order by (rank, id) here: Postgres does not
            // re-sort rows it re-fetched after a lock wait
            final List< Ranked > column = new ArrayList<>( readColumn( conn, projectId, status, " FOR UPDATE" ) );
            column.sort( Comparator.comparingDouble( Ranked::rank ).thenComparingLong( Ranked::id ) );
            final List< Ranked > wanted = plan( column, movedItemId, beforeItemId );
            if ( wanted == null ) {
                return false;
            }
            final List< SqlBinder > binders = new ArrayList<>();
            for ( int i = 0; i < wanted.size(); i++ ) {
                final long id = wanted.get( i ).id();
                final double rank = ( i + 1 ) * RANK_STEP;
                binders.add( ps -> { ps.setDouble( 1, rank ); ps.setLong( 2, id ); } );
            }
            jdbc.batch( conn, "UPDATE work_item SET rank = ? WHERE id = ?", binders );
            return true;
        } );
    }

    private List< Ranked > readColumn( final Connection conn, final long projectId, final WorkStatus status,
                                       final String lock ) throws SQLException {
        final String sql = "SELECT id, rank FROM work_item WHERE project_id = ? AND status = ? ORDER BY "
            + ( lock.isEmpty() ? "rank, id" : "id" ) + lock;
        final SqlBinder binder = ps -> { ps.setLong( 1, projectId ); ps.setString( 2, status.wire() ); };
        final RowMapper< Ranked > mapper = rs -> new Ranked( rs.getLong( 1 ), rs.getDouble( 2 ) );
        return conn == null ? jdbc.query( sql, binder, mapper ) : jdbc.query( conn, sql, binder, mapper );
    }

    /**
     * The order the column should have, or null when the moved card already sits where it was asked to (or is not in
     * the column). A {@code before} no longer in the column falls back to the rank-based position.
     */
    private static List< Ranked > plan( final List< Ranked > column, final long movedItemId, final Long beforeItemId ) {
        final Ranked moved = column.stream().filter( r -> r.id() == movedItemId ).findFirst().orElse( null );
        if ( moved == null ) {
            return null;
        }
        final List< Ranked > wanted = new ArrayList<>( column );
        wanted.remove( moved );
        int at = -1;
        if ( beforeItemId != null ) {
            for ( int i = 0; i < wanted.size(); i++ ) {
                if ( wanted.get( i ).id() == beforeItemId ) {
                    at = i;
                    break;
                }
            }
        }
        if ( at < 0 ) {
            at = (int) wanted.stream().filter( r -> r.rank() <= moved.rank() ).count();
        }
        final boolean afterPrev = at == 0 || wanted.get( at - 1 ).rank() < moved.rank();
        final boolean beforeNext = at == wanted.size() || moved.rank() < wanted.get( at ).rank();
        if ( afterPrev && beforeNext ) {
            return null;
        }
        wanted.add( at, moved );
        return wanted;
    }

    /**
     * The holder as the row is now, under lock: the caller's copy may predate a claim (claims need not bump the
     * version), so the release is decided and named from here.
     */
    private long lockHolder( final Connection conn, final long itemId ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT claimed_by_agent FROM work_item WHERE id = ? FOR UPDATE",
            ps -> ps.setLong( 1, itemId ), rs -> {
                final long v = rs.getLong( 1 );
                return rs.wasNull() ? NO_HOLDER : v;
            } ).orElse( NO_HOLDER );
    }

    /** What the write does besides copying fields. */
    private record Flags( boolean clearClaim, boolean closing, boolean reopening, boolean ackRequired,
                          boolean finishing, boolean leavingDone ) {
    }

    private int update( final Connection conn, final Item current, final Item next, final String body, final Flags f )
            throws SQLException {
    return jdbc.update( conn, """
            UPDATE work_item SET parent_id = ?, product_id = ?, kind = ?, title = ?, body = ?, status = ?,
                priority = ?, rank = ?, due_on = ?, assignee = ?, autonomy = ?, done_means = ?,
                allow_direct = ?, assignee_queue = ?,
                claimed_by_agent = CASE WHEN ? THEN NULL ELSE claimed_by_agent END,
                claimed_at = CASE WHEN ? THEN NULL ELSE claimed_at END,
                claim_expires_at = CASE WHEN ? THEN NULL ELSE claim_expires_at END,
                version = version + 1, updated_at = NOW(),
                closed_at = CASE WHEN ? THEN NOW() WHEN ? THEN NULL ELSE closed_at END,
                ack_required = CASE WHEN ? THEN ? WHEN ? THEN FALSE ELSE ack_required END,
                acknowledged_by = CASE WHEN ? THEN NULL ELSE acknowledged_by END,
                acknowledged_at = CASE WHEN ? THEN NULL ELSE acknowledged_at END
             WHERE id = ? AND version = ?
            """,
            ps -> {
                setNullableLong( ps, 1, next.parentId() );
                setNullableLong( ps, 2, next.productId() );
                ps.setString( 3, next.kind().wire() );
                ps.setString( 4, next.title() );
                ps.setString( 5, body );
                ps.setString( 6, next.status().wire() );
                ps.setInt( 7, next.priority() );
                ps.setDouble( 8, next.rank() );
                setNullableDate( ps, 9, next.dueOn() );
                ps.setString( 10, next.assignee() );
                ps.setString( 11, autonomyWire( next.autonomy() ) );
                ps.setString( 12, doneMeansWire( next.doneMeans() ) );
                setNullableBoolean( ps, 13, next.allowDirect() );
                ps.setString( 14, next.queue() );
                ps.setBoolean( 15, f.clearClaim() );
                ps.setBoolean( 16, f.clearClaim() );
                ps.setBoolean( 17, f.clearClaim() );
                ps.setBoolean( 18, f.closing() );
                ps.setBoolean( 19, f.reopening() );
                ps.setBoolean( 20, f.finishing() );
                ps.setBoolean( 21, f.ackRequired() );
                ps.setBoolean( 22, f.leavingDone() );
                ps.setBoolean( 23, f.finishing() || f.leavingDone() );
                ps.setBoolean( 24, f.finishing() || f.leavingDone() );
                ps.setLong( 25, current.id() );
                ps.setInt( 26, current.version() );
            } );
    }

    /**
     * An agent may not write an item another agent holds. The service checks its own earlier read, but a claim on a
     * review item does not move the version, so a claim that landed after that read is caught only here, under lock.
     * People are never fenced.
     */
    private static void requireNotFenced( final Actor by, final long holder ) {
        if ( by.kind() == Actor.Kind.AGENT && holder != NO_HOLDER && holder != by.agentId() ) {
            throw new ClaimFenceException();
        }
    }

    /**
     * Under an epic whose done-means is "in production", work is done only once a verified deployment carried it.
     * Checked with the item row locked, like the live-change rule; a deployment not yet committed as verified
     * does not count.
     */
    private void requireShipped( final Connection conn, final Item current, final Item next, final CloseRules rules )
            throws SQLException {
        final boolean closes = next.status() == WorkStatus.DONE && current.status() != WorkStatus.DONE;
        if ( closes && rules.needsDeployment() && !JdbcWorkDeployments.shippedVerified( jdbc, conn, current.id() ) ) {
            throw new WorkConflictException( "This closes when a deployment that carries it is verified.",
                WorkConflictException.AWAITING_DEPLOYMENT );
        }
    }

    /**
     * An item with a change that is open or in review cannot be done, and cannot move to another product: checked
     * here, with the item row locked, because opening a change locks the same row.
     */
    private void requireNoLiveChange( final Connection conn, final Item current, final Item next )
            throws SQLException {
        final boolean closes = next.status() == WorkStatus.DONE && current.status() != WorkStatus.DONE;
        final boolean retargets = !Objects.equals( current.productId(), next.productId() );
        if ( ( closes || retargets ) && JdbcWorkChanges.hasLive( jdbc, conn, current.id() ) ) {
            throw new WorkConflictException( "Close or merge this item's open changes first.",
                WorkConflictException.OPEN_CHANGES );
        }
    }
}
