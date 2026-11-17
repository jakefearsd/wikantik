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
import com.wikantik.jdbc.RowMapper;
import com.wikantik.jdbc.SqlBinder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.wikantik.work.SqlValues.instant;
import static com.wikantik.work.SqlValues.setNullableLong;

/** The PostgreSQL {@link WorkChangeStore}. */
final class JdbcWorkChanges implements WorkChangeStore {

    private static final String COLUMNS = "id, item_id, product_id, branch, base_branch, head_sha, review_url, state, "
        + "merged_sha, merge_kind, opened_by, opened_by_agent_id, reviewed_by, reviewed_by_agent_id, "
        + "reviewed_head_sha, reviewed_at, merged_by, merged_by_agent_id, opened_at, closed_at, head_agent_ids";

    private static final String SELECT = "SELECT " + COLUMNS + " FROM work_change";

    private static final String UNIQUE_VIOLATION = "23505";

    private static final RowMapper< Change > ROW = rs -> new Change( rs.getLong( "id" ), rs.getLong( "item_id" ),
        rs.getLong( "product_id" ), rs.getString( "branch" ), rs.getString( "base_branch" ),
        rs.getString( "head_sha" ), rs.getString( "review_url" ), ChangeState.fromWire( rs.getString( "state" ) ),
        rs.getString( "merged_sha" ),
        rs.getString( "merge_kind" ) == null ? null : MergeKind.fromWire( rs.getString( "merge_kind" ) ),
        rs.getString( "opened_by" ), SqlValues.nullableLong( rs, "opened_by_agent_id" ),
        rs.getString( "reviewed_by" ), SqlValues.nullableLong( rs, "reviewed_by_agent_id" ),
        rs.getString( "reviewed_head_sha" ), instant( rs, "reviewed_at" ), rs.getString( "merged_by" ),
        SqlValues.nullableLong( rs, "merged_by_agent_id" ), instant( rs, "opened_at" ), instant( rs, "closed_at" ),
        agentIds( rs.getArray( "head_agent_ids" ) ) );

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
                requireOpenItem( conn, item );
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

    /** Locks the item row: closing the item locks it too, so a new change and a close cannot cross. */
    private void requireOpenItem( final Connection conn, final Item item ) throws SQLException {
        final String status = jdbc.queryOne( conn, "SELECT status FROM work_item WHERE id = ? FOR SHARE",
            ps -> ps.setLong( 1, item.id() ), rs -> rs.getString( 1 ) )
            .orElseThrow( () -> new SQLException( "work_item " + item.id() + " not found" ) );
        if ( SqlValues.isClosed( WorkStatus.fromWire( status ) ) ) {
            throw new WorkValidationException( "status", "This item is closed; reopen it to add a change." );
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
                head_agent_ids)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? THEN NOW() ELSE NULL END,
                CASE WHEN ?::bigint IS NULL THEN '{}'::bigint[] ELSE ARRAY[ ?::bigint ] END) RETURNING id
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
                          final Check check, final Actor by ) throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            check.on( locked );
            final String head = headSha == null ? locked.headSha() : headSha;
            final boolean moved = !Objects.equals( head, locked.headSha() );
            jdbc.update( conn, """
                UPDATE work_change SET head_sha = ?, review_url = COALESCE( ?, review_url ),
                    reviewed_by = CASE WHEN ? THEN NULL ELSE reviewed_by END,
                    reviewed_by_agent_id = CASE WHEN ? THEN NULL ELSE reviewed_by_agent_id END,
                    reviewed_head_sha = CASE WHEN ? THEN NULL ELSE reviewed_head_sha END,
                    reviewed_at = CASE WHEN ? THEN NULL ELSE reviewed_at END,
                    head_agent_ids = CASE WHEN ?::bigint IS NULL OR ?::bigint = ANY( head_agent_ids )
                        THEN head_agent_ids ELSE array_append( head_agent_ids, ?::bigint ) END
                 WHERE id = ?
                """, ps -> {
                    ps.setString( 1, head );
                    ps.setString( 2, reviewUrl );
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

    @Override
    public Change requestReview( final Item item, final long id, final Check check, final Actor by )
            throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            check.on( locked );
            if ( locked.state() == ChangeState.IN_REVIEW ) {
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
            jdbc.update( conn, "UPDATE work_change SET state = 'merged', merged_sha = ?, merge_kind = ?, "
                    + "merged_by = ?, merged_by_agent_id = ?, closed_at = NOW() WHERE id = ?",
                ps -> {
                    ps.setString( 1, mergedSha );
                    ps.setString( 2, kind.wire() );
                    ps.setString( 3, by.login() );
                    setNullableLong( ps, 4, by.agentId() );
                    ps.setLong( 5, id );
                } );
            event( conn, item, id, by, WorkEvent.CHANGE_MERGED, kind.wire(), mergedSha );
        } );
    }

    @Override
    public Change abandon( final Item item, final long id, final String note, final Check check, final Actor by )
            throws SQLException {
        return write( item, id, by, ( conn, locked ) -> {
            check.on( locked );
            jdbc.update( conn, "UPDATE work_change SET state = 'abandoned', closed_at = NOW() WHERE id = ?",
                ps -> ps.setLong( 1, id ) );
            event( conn, item, id, by, WorkEvent.CHANGE_ABANDONED, locked.branch(), note );
        } );
    }

    @Override
    public Map< Long, Counts > counts( final long projectId ) throws SQLException {
        final Map< Long, Counts > out = new HashMap<>();
        jdbc.query( """
            SELECT c.item_id, COUNT(*) AS live, COUNT(*) FILTER ( WHERE c.state = 'in_review' ) AS in_review
              FROM work_change c JOIN work_item i ON i.id = c.item_id
             WHERE i.project_id = ? AND c.state IN ('open','in_review')
             GROUP BY c.item_id
            """, ps -> ps.setLong( 1, projectId ), rs -> {
                out.put( rs.getLong( "item_id" ), new Counts( rs.getInt( "live" ), rs.getInt( "in_review" ) ) );
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
