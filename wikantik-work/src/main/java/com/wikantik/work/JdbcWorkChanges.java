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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.setNullableLong;

/** The PostgreSQL {@link WorkChangeStore}. */
final class JdbcWorkChanges implements WorkChangeStore {

    static final String COLUMNS = "id, item_id, product_id, branch, base_branch, head_sha, review_url, state, "
        + "merged_sha, merge_kind, opened_by, opened_by_agent_id, reviewed_by, reviewed_by_agent_id, "
        + "reviewed_head_sha, reviewed_at, merged_by, merged_by_agent_id, opened_at, closed_at, head_agent_ids, "
        + "first_commit_at, source, external_opened_by, external_reviewed_by, external_merged_by, review_number, "
        + "policy_flag, flag_acknowledged_by, flag_acknowledged_at, superseded_by";

    static final String SELECT = "SELECT " + COLUMNS + " FROM work_change";

    private static final String UNIQUE_VIOLATION = "23505";

    static final RowMapper< Change > ROW = rs -> new Change( rs.getLong( "id" ), rs.getLong( "item_id" ),
        rs.getLong( "product_id" ), rs.getString( "branch" ), rs.getString( "base_branch" ),
        rs.getString( "head_sha" ), rs.getString( "review_url" ), ChangeState.fromWire( rs.getString( "state" ) ),
        rs.getString( "merged_sha" ),
        rs.getString( "merge_kind" ) == null ? null : MergeKind.fromWire( rs.getString( "merge_kind" ) ),
        rs.getString( "opened_by" ), NullableColumns.getNullableLong( rs, "opened_by_agent_id" ),
        rs.getString( "reviewed_by" ), NullableColumns.getNullableLong( rs, "reviewed_by_agent_id" ),
        rs.getString( "reviewed_head_sha" ), getInstant( rs, "reviewed_at" ), rs.getString( "merged_by" ),
        NullableColumns.getNullableLong( rs, "merged_by_agent_id" ), getInstant( rs, "opened_at" ), getInstant( rs, "closed_at" ),
        agentIds( rs.getArray( "head_agent_ids" ) ), getInstant( rs, "first_commit_at" ), rs.getString( "source" ),
        rs.getString( "external_opened_by" ), rs.getString( "external_reviewed_by" ),
        rs.getString( "external_merged_by" ), rs.getString( "review_number" ), rs.getString( "policy_flag" ),
        rs.getString( "flag_acknowledged_by" ), getInstant( rs, "flag_acknowledged_at" ),
        NullableColumns.getNullableLong( rs, "superseded_by" ) );

    private static List< Long > agentIds( final java.sql.Array array ) throws SQLException {
        if ( array == null ) {
            return List.of();
        }
        final List< Long > ids = new java.util.ArrayList<>();
        for ( final Object id : ( Object[] ) array.getArray() ) {
            ids.add( ( ( Number ) id ).longValue() );
        }
        return ids;
    }

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkChanges( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    static boolean hasLive( final Jdbc jdbc, final Connection conn, final long itemId ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT 1 FROM work_change WHERE item_id = ? AND state IN ('open','in_review') "
            + "LIMIT 1", ps -> ps.setLong( 1, itemId ), rs -> Boolean.TRUE ).isPresent();
    }

    @Override
    public boolean hasLive( final long itemId ) throws SQLException {
        return jdbc.withConnection( conn -> hasLive( jdbc, conn, itemId ) );
    }

    @Override
    public Change open( final Item item, final long productId, final ChangeDraft d, final Actor by )
            throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        try {
            return jdbc.inTransaction( conn -> {
                // the code host's lock first (lock order: JdbcCodeHostRows), so a delivery cannot open a change on
                // this branch between the check below and the insert
                JdbcCodeHostRows.lockProduct( jdbc, conn, productId );
                requireOpenItem( conn, item, productId, by );
                if ( d.mergedSha() == null && liveOnBranch( conn, item.id(), d.branch() ) ) {
                    throw new WorkValidationException( "branch", "This item already has an open change on that branch." );
                }
                final long id = insert( conn, item, productId, d, by );
                if ( d.mergedSha() == null ) {
                    event( conn, item, id, by, WorkEvent.CHANGE_OPENED, d.branch(), d.headSha() );
                } else {
                    event( conn, item, id, by, WorkEvent.CHANGE_MERGED, MergeKind.DIRECT.wire(), d.mergedSha() );
                }
                return byId( conn, id );
            } );
        } catch ( final SQLException e ) {
            if ( UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
                throw new WorkValidationException( "branch", "This item already has an open change on that branch." );
            }
            throw e;
        }
    }

    /**
     * Whether a live change of the item is on the branch, whatever pull request the code host linked it to: an agent
     * or a person opens at most one change per branch (the unique index now covers only unlinked changes).
     */
    private boolean liveOnBranch( final Connection conn, final long itemId, final String branch ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT 1 FROM work_change WHERE item_id = ? AND branch = ? "
            + "AND state IN ('open','in_review') LIMIT 1", ps -> {
                ps.setLong( 1, itemId );
                ps.setString( 2, branch );
            }, rs -> Boolean.TRUE ).isPresent();
    }

    /** The item as its row is under a share lock: what a change write must not decide from an earlier read. */
    private record LockedItem( WorkStatus status, Long productId, Long holder ) {
    }

    /**
     * Locks the item row (closing or claiming the item locks it too, so neither can cross a change write) and
     * fences an agent: it may not write a change on an item another agent holds, as it may not write the item.
     */
    private LockedItem lockItem( final Connection conn, final Item item, final Actor by ) throws SQLException {
        final LockedItem locked = jdbc.queryOne( conn,
            "SELECT status, product_id, claimed_by_agent FROM work_item WHERE id = ? FOR SHARE",
            ps -> ps.setLong( 1, item.id() ), rs -> new LockedItem( WorkStatus.fromWire( rs.getString( 1 ) ),
                NullableColumns.getNullableLong( rs, "product_id" ), NullableColumns.getNullableLong( rs, "claimed_by_agent" ) ) )
            .orElseThrow( () -> new SQLException( "work_item " + item.id() + " not found" ) );
        if ( by.kind() == Actor.Kind.AGENT && locked.holder() != null && !locked.holder().equals( by.agentId() ) ) {
            throw new ClaimFenceException();
        }
        return locked;
    }

    /** A change is opened on an open item, against the product the item targets now. */
    private void requireOpenItem( final Connection conn, final Item item, final long productId, final Actor by )
            throws SQLException {
        final LockedItem locked = lockItem( conn, item, by );
        if ( SqlValues.isClosed( locked.status() ) ) {
            throw new WorkValidationException( "status", "This item is closed; reopen it to add a change." );
        }
        if ( locked.productId() == null || locked.productId() != productId ) {
            throw new WorkConflictException( "This item's product changed; read it again." );
        }
    }

    /** Inserts the change: open, or already merged when the draft names the merged commit. */
    private long insert( final Connection conn, final Item item, final long productId, final ChangeDraft d,
                         final Actor by ) throws SQLException {
        final boolean direct = d.mergedSha() != null;
        final String mergedBy = direct ? by.login() : null;
        final Long mergedByAgent = direct ? by.agentId() : null;
        return jdbc.queryOne( conn, """
            INSERT INTO work_change (item_id, product_id, branch, base_branch, head_sha, review_url, state,
                merged_sha, merge_kind, opened_by, opened_by_agent_id, merged_by, merged_by_agent_id, closed_at,
                head_agent_ids, first_commit_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? THEN NOW() ELSE NULL END,
                CASE WHEN ?::bigint IS NULL THEN '{}'::bigint[] ELSE ARRAY[ ?::bigint ] END, ?) RETURNING id
            """, ps -> {
                ps.setLong( 1, item.id() );
                ps.setLong( 2, productId );
                ps.setString( 3, d.branch() );
                ps.setString( 4, d.baseBranch() );
                ps.setString( 5, direct ? d.mergedSha() : d.headSha() );
                ps.setString( 6, d.reviewUrl() );
                ps.setString( 7, ( direct ? ChangeState.MERGED : ChangeState.OPEN ).wire() );
                ps.setString( 8, d.mergedSha() );
                ps.setString( 9, direct ? MergeKind.DIRECT.wire() : null );
                ps.setString( 10, by.login() );
                setNullableLong( ps, 11, by.agentId() );
                ps.setString( 12, mergedBy );
                setNullableLong( ps, 13, mergedByAgent );
                ps.setBoolean( 14, direct );
                // an agent that names a commit when opening is that commit's author
                final Long author = direct || d.headSha() != null ? by.agentId() : null;
                setNullableLong( ps, 15, author );
                setNullableLong( ps, 16, author );
                ps.setTimestamp( 17, d.firstCommitAt() == null ? null
                    : java.sql.Timestamp.from( java.time.Instant.parse( d.firstCommitAt() ) ) );
            }, rs -> rs.getLong( 1 ) )
            .orElseThrow( () -> new SQLException( "INSERT work_change returned no row" ) );
    }

    @Override
    public Optional< Change > change( final long id ) throws SQLException {
        return jdbc.queryOne( SELECT + " WHERE id = ?", ps -> ps.setLong( 1, id ), ROW );
    }

    @Override
    public List< Change > forItem( final long itemId ) throws SQLException {
        return jdbc.query( SELECT + " WHERE item_id = ? ORDER BY id", ps -> ps.setLong( 1, itemId ), ROW );
    }

    @Override
    public Change update( final Item item, final long id, final String headSha, final String reviewUrl,
                          final java.time.Instant firstCommitAt, final Check check, final Actor by )
            throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            check.on( locked );
            final java.time.Instant firstCommit = earlierFirstCommit( locked, firstCommitAt );
            final String head = headSha == null ? locked.headSha() : headSha;
            final boolean moved = !Objects.equals( head, locked.headSha() );
            final String url = reviewUrl == null ? locked.reviewUrl() : reviewUrl.isEmpty() ? null : reviewUrl;
            final boolean firstMoved = !Objects.equals( firstCommit, locked.firstCommitAt() );
            if ( !moved && Objects.equals( url, locked.reviewUrl() ) ) {
                if ( firstMoved ) {
                    setFirstCommit( conn, id, firstCommit );
                    event( conn, item, id, by, WorkEvent.CHANGE_UPDATED, null, "first commit" );
                }
                return;
            }
            if ( firstMoved ) {
                setFirstCommit( conn, id, firstCommit );
            }
            jdbc.update( conn, """
                UPDATE work_change SET head_sha = ?, review_url = ?,
                    reviewed_by = CASE WHEN ? THEN NULL ELSE reviewed_by END,
                    reviewed_by_agent_id = CASE WHEN ? THEN NULL ELSE reviewed_by_agent_id END,
                    reviewed_head_sha = CASE WHEN ? THEN NULL ELSE reviewed_head_sha END,
                    reviewed_at = CASE WHEN ? THEN NULL ELSE reviewed_at END,
                    head_agent_ids = CASE WHEN ?::bigint IS NULL OR ?::bigint = ANY( head_agent_ids )
                        THEN head_agent_ids ELSE array_append( head_agent_ids, ?::bigint ) END
                 WHERE id = ?
                """, ps -> {
                    ps.setString( 1, head );
                    ps.setString( 2, url );
                    ps.setBoolean( 3, moved );
                    ps.setBoolean( 4, moved );
                    ps.setBoolean( 5, moved );
                    ps.setBoolean( 6, moved );
                    // an agent that sets a new commit becomes one of the change's authors, for good
                    final Long author = moved ? by.agentId() : null;
                    setNullableLong( ps, 7, author );
                    setNullableLong( ps, 8, author );
                    setNullableLong( ps, 9, author );
                    ps.setLong( 10, id );
                } );
            event( conn, item, id, by, WorkEvent.CHANGE_UPDATED, head, null );
        } );
    }

    /** The recorded first commit once {@code reported} is considered: it only ever moves earlier. */
    private static java.time.Instant earlierFirstCommit( final Change locked, final java.time.Instant reported ) {
        if ( reported == null ) {
            return locked.firstCommitAt();
        }
        final java.time.Instant best = locked.firstCommitAt() == null || reported.isBefore( locked.firstCommitAt() )
            ? reported : locked.firstCommitAt();
        // never after the change opened: a commit reported later than that was not its first
        return locked.openedAt() != null && best.isAfter( locked.openedAt() ) ? locked.openedAt() : best;
    }

    private void setFirstCommit( final Connection conn, final long id, final java.time.Instant at )
            throws SQLException {
        jdbc.update( conn, "UPDATE work_change SET first_commit_at = ? WHERE id = ?", ps -> {
            ps.setTimestamp( 1, java.sql.Timestamp.from( at ) );
            ps.setLong( 2, id );
        } );
    }

    @Override
    public Change requestReview( final Item item, final long id, final Check check, final Actor by )
            throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            check.on( locked );
            if ( !locked.state().mayBecome( ChangeState.IN_REVIEW ) ) {
                return;
            }
            setState( conn, id, ChangeState.IN_REVIEW );
            event( conn, item, id, by, WorkEvent.REVIEW_REQUESTED, locked.branch(), locked.headSha() );
        } );
    }

    @Override
    public Change review( final Item item, final long id, final boolean approved, final String note,
                          final Check check, final Actor by ) throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            if ( locked.state() != ChangeState.IN_REVIEW ) {
                throw new WorkConflictException( "Ask for a review of this change first.",
                    WorkConflictException.NOT_IN_REVIEW );
            }
            check.on( locked );
            jdbc.update( conn, """
                UPDATE work_change SET state = ?, reviewed_by = ?, reviewed_by_agent_id = ?, reviewed_head_sha = ?,
                    reviewed_at = CASE WHEN ? THEN NOW() ELSE NULL END
                 WHERE id = ?
                """, ps -> {
                    ps.setString( 1, ( approved ? ChangeState.IN_REVIEW : ChangeState.OPEN ).wire() );
                    ps.setString( 2, approved ? by.login() : null );
                    setNullableLong( ps, 3, approved ? by.agentId() : null );
                    ps.setString( 4, approved ? locked.headSha() : null );
                    ps.setBoolean( 5, approved );
                    ps.setLong( 6, id );
                } );
            event( conn, item, id, by, WorkEvent.CHANGE_REVIEWED, approved ? "approved" : "changes_requested", note );
        } );
    }

    @Override
    public Change merge( final Item item, final long id, final String mergedSha,
                         final Function< Change, MergeKind > decide, final Actor by ) throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            final MergeKind kind = decide.apply( locked );
            jdbc.update( conn, "UPDATE work_change SET state = ?, merged_sha = ?, merge_kind = ?, "
                    + "merged_by = ?, merged_by_agent_id = ?, closed_at = NOW() WHERE id = ?",
                ps -> {
                    ps.setString( 1, ChangeState.MERGED.wire() );
                    ps.setString( 2, mergedSha );
                    ps.setString( 3, kind.wire() );
                    ps.setString( 4, by.login() );
                    setNullableLong( ps, 5, by.agentId() );
                    ps.setLong( 6, id );
                } );
            event( conn, item, id, by, WorkEvent.CHANGE_MERGED, kind.wire(), mergedSha );
        } );
    }

    @Override
    public Change abandon( final Item item, final long id, final String note, final Check check, final Actor by )
            throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            check.on( locked );
            jdbc.update( conn, "UPDATE work_change SET state = ?, closed_at = NOW() WHERE id = ?",
                ps -> {
                    ps.setString( 1, ChangeState.ABANDONED.wire() );
                    ps.setLong( 2, id );
                } );
            event( conn, item, id, by, WorkEvent.CHANGE_ABANDONED, locked.branch(), note );
        } );
    }

    @Override
    public Map< String, Counts > counts( final long projectId ) throws SQLException {
        final Map< String, Counts > out = new java.util.LinkedHashMap<>();
        jdbc.query( """
            SELECT p.key || '-' || i.seq AS item_key, COUNT(*) AS live,
                   COUNT(*) FILTER ( WHERE c.state = 'in_review' ) AS in_review
              FROM work_change c JOIN work_item i ON i.id = c.item_id JOIN work_project p ON p.id = i.project_id
             WHERE i.project_id = ? AND c.state IN ('open','in_review')
             GROUP BY p.key, i.seq ORDER BY i.seq
            """, ps -> ps.setLong( 1, projectId ), rs -> {
                out.put( rs.getString( "item_key" ), new Counts( rs.getInt( "live" ), rs.getInt( "in_review" ) ) );
                return null;
            } );
        return out;
    }

    /** What a write does once the row is locked and known to be live. */
    @FunctionalInterface
    private interface Body {
        void run( Connection conn, Change locked ) throws SQLException;
    }

    private Change write( final Item item, final long id, final Actor by, final Body body ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final Change locked = jdbc.queryOne( conn, SELECT + " WHERE id = ? AND item_id = ? FOR UPDATE",
                SqlBinder.positional( List.of( id, item.id() ) ), ROW )
                .orElseThrow( () -> new SQLException( "work_change " + id + " not found" ) );
            if ( !locked.live() ) {
                throw new WorkConflictException( "This change is already merged or abandoned.",
                    WorkConflictException.CHANGE_CLOSED );
            }
            lockItem( conn, item, by );
            body.run( conn, locked );
            return byId( conn, id );
        } );
    }

    private Change byId( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, SELECT + " WHERE id = ?", ps -> ps.setLong( 1, id ), ROW )
            .orElseThrow( () -> new SQLException( "work_change " + id + " not found" ) );
    }

    private void setState( final Connection conn, final long id, final ChangeState state ) throws SQLException {
        jdbc.update( conn, "UPDATE work_change SET state = ? WHERE id = ?", ps -> {
            ps.setString( 1, state.wire() );
            ps.setLong( 2, id );
        } );
    }

    /** One event on the item: {@code from_value} names the change, {@code to_value} and {@code note} say what. */
    private void event( final Connection conn, final Item item, final long changeId, final Actor by,
                        final String type, final String to, final String note ) throws SQLException {
        catalog.appendEvent( conn, new WorkEvent( 0, item.projectId(), item.id(), null, null, by.login(), by.kind(),
            by.agentId(), type, "change:" + changeId, to, note ) );
    }
}
