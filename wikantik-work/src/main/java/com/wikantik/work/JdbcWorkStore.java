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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.wikantik.work.SqlValues.actorKindWire;
import static com.wikantik.work.SqlValues.autonomyWire;
import static com.wikantik.work.SqlValues.doneMeansWire;
import static com.wikantik.work.SqlValues.instant;
import static com.wikantik.work.SqlValues.isClosed;
import static com.wikantik.work.SqlValues.localDate;
import static com.wikantik.work.SqlValues.nullableLong;
import static com.wikantik.work.SqlValues.setNullableBoolean;
import static com.wikantik.work.SqlValues.setNullableDate;
import static com.wikantik.work.SqlValues.setNullableLong;
import static com.wikantik.work.SqlValues.timestamp;

/** PostgreSQL implementation of {@link WorkStore}; all access goes through {@link Jdbc}. */
public class JdbcWorkStore extends JdbcWorkCatalog {

    static final String ITEM_SELECT = """
        SELECT i.id, i.project_id, p.key || '-' || i.seq AS item_key, i.level, i.parent_id, i.product_id, i.kind,
               i.title, i.body, i.status, i.priority, i.rank, i.due_on, i.assignee, i.reporter, i.autonomy,
               i.done_means, i.allow_direct, i.version, i.created_at, i.updated_at, i.closed_at,
               i.assignee_queue, i.claimed_by_agent, i.claimed_at, i.claim_expires_at, i.reporter_agent_id
          FROM work_item i JOIN work_project p ON p.id = i.project_id
        """;

    static final RowMapper< Item > ITEM_ROW = rs -> new Item(
        rs.getLong( "id" ), rs.getLong( "project_id" ), rs.getString( "item_key" ),
        WorkLevel.fromWire( rs.getString( "level" ) ), nullableLong( rs, "parent_id" ),
        nullableLong( rs, "product_id" ), WorkKind.fromWire( rs.getString( "kind" ) ), rs.getString( "title" ),
        rs.getString( "body" ), WorkStatus.fromWire( rs.getString( "status" ) ), rs.getInt( "priority" ),
        rs.getDouble( "rank" ), localDate( rs, "due_on" ), rs.getString( "assignee" ), rs.getString( "reporter" ),
        rs.getString( "autonomy" ) == null ? null : Autonomy.fromWire( rs.getString( "autonomy" ) ),
        rs.getString( "done_means" ) == null ? null : DoneMeans.fromWire( rs.getString( "done_means" ) ),
        (Boolean) rs.getObject( "allow_direct" ), rs.getInt( "version" ),
        instant( rs, "created_at" ), instant( rs, "updated_at" ), instant( rs, "closed_at" ),
        rs.getString( "assignee_queue" ), nullableLong( rs, "claimed_by_agent" ), instant( rs, "claimed_at" ),
        instant( rs, "claim_expires_at" ), nullableLong( rs, "reporter_agent_id" ) );

    private static final String EVENT_SELECT = """
        SELECT e.id, e.project_id, e.item_id,
               CASE WHEN i.id IS NULL THEN NULL ELSE p.key || '-' || i.seq END AS item_key,
               e.at, e.actor, e.actor_kind, e.agent_id, e.type, e.from_value, e.to_value, e.note
          FROM work_event e
          JOIN work_project p ON p.id = e.project_id
          LEFT JOIN work_item i ON i.id = e.item_id
        """;

    private static final RowMapper< WorkEvent > EVENT_ROW = rs -> new WorkEvent(
        rs.getLong( "id" ), rs.getLong( "project_id" ), nullableLong( rs, "item_id" ), rs.getString( "item_key" ),
        instant( rs, "at" ), rs.getString( "actor" ), "agent".equals( rs.getString( "actor_kind" ) )
            ? Actor.Kind.AGENT : Actor.Kind.HUMAN,
        nullableLong( rs, "agent_id" ), rs.getString( "type" ), rs.getString( "from_value" ),
        rs.getString( "to_value" ), rs.getString( "note" ) );

    private static final Pattern ITEM_KEY = Pattern.compile( "^([A-Za-z][A-Za-z0-9]{1,9})-(\\d{1,9})$" );

    private static final int DEFAULT_ITEM_LIMIT = 200;
    private static final int CLOSED_COLUMN_CAP = 50;
    private static final double RANK_STEP = 1024.0;

    private final JdbcWorkDiscussion discussion;

    public JdbcWorkStore( final DataSource dataSource ) {
        super( new Jdbc( dataSource ) );
        this.discussion = new JdbcWorkDiscussion( jdbc, this );
    }

    // ---- items

    @Override
    public Item createItem( final Project project, final Item draft, final String actor, final Actor.Kind kind )
            throws SQLException {
        final WorkStatus status = draft.status() == null ? WorkStatus.DRAFT : draft.status();
        final String body = draft.body() == null ? "" : draft.body();
        return jdbc.inTransaction( conn -> {
            final int seq = jdbc.queryOne( conn,
                "UPDATE work_project SET next_seq = next_seq + 1 WHERE id = ? RETURNING next_seq - 1",
                ps -> ps.setLong( 1, project.id() ), rs -> rs.getInt( 1 ) )
                .orElseThrow( () -> new SQLException( "work_project " + project.id() + " not found" ) );
            final double rank = jdbc.queryOne( conn,
                "SELECT COALESCE( MAX( rank ), 0 ) FROM work_item WHERE project_id = ? AND status = ?",
                ps -> { ps.setLong( 1, project.id() ); ps.setString( 2, status.wire() ); },
                rs -> rs.getDouble( 1 ) ).orElse( 0.0 ) + RANK_STEP;
            final long id = jdbc.queryOne( conn, """
                INSERT INTO work_item (project_id, seq, level, parent_id, product_id, kind, title, body, status,
                    priority, rank, due_on, assignee, reporter, autonomy, done_means, allow_direct, closed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? THEN NOW() END)
                RETURNING id
                """,
                ps -> {
                    ps.setLong( 1, project.id() );
                    ps.setInt( 2, seq );
                    ps.setString( 3, draft.level().wire() );
                    setNullableLong( ps, 4, draft.parentId() );
                    setNullableLong( ps, 5, draft.productId() );
                    ps.setString( 6, draft.kind().wire() );
                    ps.setString( 7, draft.title() );
                    ps.setString( 8, body );
                    ps.setString( 9, status.wire() );
                    ps.setInt( 10, draft.priority() );
                    ps.setDouble( 11, rank );
                    setNullableDate( ps, 12, draft.dueOn() );
                    ps.setString( 13, draft.assignee() );
                    ps.setString( 14, actor );
                    ps.setString( 15, autonomyWire( draft.autonomy() ) );
                    ps.setString( 16, doneMeansWire( draft.doneMeans() ) );
                    setNullableBoolean( ps, 17, draft.allowDirect() );
                    ps.setBoolean( 18, isClosed( status ) );
                }, rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new SQLException( "INSERT work_item returned no row" ) );
            insertRevision( conn, id, 1, draft.title(), body, actor );
            appendEvent( conn, new WorkEvent( 0, project.id(), id, null, null, actor, kind, null,
                WorkEvent.CREATED, null, null, null ) );
            return itemById( conn, id );
        } );
    }

    Item itemById( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ), ITEM_ROW )
            .orElseThrow( () -> new SQLException( "work_item " + id + " not found" ) );
    }

    private void insertRevision( final Connection conn, final long itemId, final int version, final String title,
                                 final String body, final String editedBy ) throws SQLException {
        jdbc.update( conn, "INSERT INTO work_item_revision (item_id, version, title, body, edited_by) "
                + "VALUES (?,?,?,?,?)",
            ps -> {
                ps.setLong( 1, itemId );
                ps.setInt( 2, version );
                ps.setString( 3, title );
                ps.setString( 4, body );
                ps.setString( 5, editedBy );
            } );
    }

    @Override
    public Optional< Item > itemById( final long id ) throws SQLException {
        return jdbc.queryOne( ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ), ITEM_ROW );
    }

    @Override
    public double maxRank( final long projectId, final WorkStatus status ) throws SQLException {
        return jdbc.queryOne( "SELECT COALESCE( MAX( rank ), 0 ) FROM work_item WHERE project_id = ? AND status = ?",
            ps -> { ps.setLong( 1, projectId ); ps.setString( 2, status.wire() ); },
            rs -> rs.getDouble( 1 ) ).orElse( 0.0 );
    }

    @Override
    public Optional< Item > item( final String key ) throws SQLException {
        if ( key == null ) {
            return Optional.empty();
        }
        final Matcher m = ITEM_KEY.matcher( key.trim() );
        if ( !m.matches() ) {
            return Optional.empty();
        }
        return jdbc.queryOne( ITEM_SELECT + " WHERE p.key = ? AND i.seq = ?", ps -> {
            ps.setString( 1, m.group( 1 ).toUpperCase( java.util.Locale.ROOT ) );
            ps.setInt( 2, Integer.parseInt( m.group( 2 ) ) );
        }, ITEM_ROW );
    }

    @Override
    public Item updateItem( final Item current, final Item next, final String actor, final Actor.Kind kind,
                            final String note ) throws SQLException {
        final boolean textChanged = !Objects.equals( current.title(), next.title() )
            || !Objects.equals( current.body(), next.body() );
        final boolean closing = !isClosed( current.status() ) && isClosed( next.status() );
        final boolean reopening = isClosed( current.status() ) && !isClosed( next.status() );
        final int version = current.version() + 1;
        final String body = next.body() == null ? "" : next.body();
        return jdbc.inTransaction( conn -> {
            final int rows = jdbc.update( conn, """
                UPDATE work_item SET parent_id = ?, product_id = ?, kind = ?, title = ?, body = ?, status = ?,
                    priority = ?, rank = ?, due_on = ?, assignee = ?, autonomy = ?, done_means = ?,
                    allow_direct = ?, version = version + 1, updated_at = NOW(),
                    closed_at = CASE WHEN ? THEN NOW() WHEN ? THEN NULL ELSE closed_at END
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
                    ps.setBoolean( 14, closing );
                    ps.setBoolean( 15, reopening );
                    ps.setLong( 16, current.id() );
                    ps.setInt( 17, current.version() );
                } );
            if ( rows == 0 ) {
                throw new WorkConflictException( "Item " + current.key() + " changed since version "
                    + current.version() );
            }
            if ( textChanged ) {
                insertRevision( conn, current.id(), version, next.title(), body, actor );
            }
            for ( final WorkEvent e : WorkEventDiff.changeEvents( current, next, actor, kind, note ) ) {
                appendEvent( conn, e );
            }
            return itemById( conn, current.id() );
        } );
    }

    // ---- queries

    @Override
    public List< Item > items( final ItemFilter f ) throws SQLException {
        final StringBuilder sql = new StringBuilder( ITEM_SELECT ).append( " WHERE TRUE" );
        final List< Object > params = new ArrayList<>();
        ItemQuery.conditions( f, true, sql, params );
        sql.append( f.order() == ItemFilter.Order.DUE ? " ORDER BY i.due_on ASC NULLS LAST, p.key, i.seq LIMIT ?"
            : " ORDER BY p.key, i.seq LIMIT ?" );
        params.add( f.limit() > 0 ? f.limit() : DEFAULT_ITEM_LIMIT );
        return jdbc.query( sql.toString(), SqlBinder.positional( params ), ITEM_ROW );
    }

    @Override
    public List< BoardColumn > board( final long projectId, final ItemFilter f ) throws SQLException {
        final ItemFilter scoped = new ItemFilter( projectId, null, f.assignee(), f.productId(), f.level(),
            f.parentId(), f.text(), f.dueBefore(), 0, null, f.queue() );
        final List< BoardColumn > columns = new ArrayList<>();
        for ( final WorkStatus status : WorkStatus.values() ) {
            final StringBuilder where = new StringBuilder( " WHERE i.status = ?" );
            final List< Object > params = new ArrayList<>();
            params.add( status.wire() );
            ItemQuery.conditions( scoped, false, where, params );
            final int total = jdbc.queryOne( "SELECT COUNT(*) FROM work_item i" + where,
                SqlBinder.positional( params ), rs -> rs.getInt( 1 ) ).orElse( 0 );
            final boolean closed = isClosed( status );
            final String order = closed ? " ORDER BY i.closed_at DESC NULLS LAST, i.id DESC LIMIT " + CLOSED_COLUMN_CAP
                : " ORDER BY i.rank, i.id";
            columns.add( new BoardColumn( status, total, jdbc.query( ITEM_SELECT + where + order,
                SqlBinder.positional( params ), ITEM_ROW ) ) );
        }
        return columns;
    }

    @Override
    public List< ItemRevision > revisions( final long itemId ) throws SQLException {
        return jdbc.query( "SELECT item_id, version, title, body, edited_by, edited_at, edited_by_agent_id FROM work_item_revision "
                + "WHERE item_id = ? ORDER BY version",
            ps -> ps.setLong( 1, itemId ), rs -> new ItemRevision( rs.getLong( "item_id" ), rs.getInt( "version" ),
                rs.getString( "title" ), rs.getString( "body" ), rs.getString( "edited_by" ),
                instant( rs, "edited_at" ), nullableLong( rs, "edited_by_agent_id" ) ) );
    }

    @Override
    public List< WorkEvent > itemEvents( final long itemId ) throws SQLException {
        return jdbc.query( EVENT_SELECT + " WHERE e.item_id = ? ORDER BY e.at, e.id",
            ps -> ps.setLong( 1, itemId ), EVENT_ROW );
    }

    @Override
    public List< WorkEvent > projectEvents( final long projectId, final Instant before, final Long beforeId,
                                            final Long itemId, final int limit ) throws SQLException {
        final StringBuilder sql = new StringBuilder( EVENT_SELECT ).append( " WHERE e.project_id = ?" );
        final List< Object > params = new ArrayList<>();
        params.add( projectId );
        if ( before != null && beforeId != null ) {
            sql.append( " AND (e.at, e.id) < (?, ?)" );
            params.add( timestamp( before ) );
            params.add( beforeId );
        } else if ( before != null ) {
            sql.append( " AND e.at < ?" );
            params.add( timestamp( before ) );
        }
        if ( itemId != null ) {
            sql.append( " AND e.item_id = ?" );
            params.add( itemId );
        }
        sql.append( " ORDER BY e.at DESC, e.id DESC LIMIT ?" );
        params.add( limit > 0 ? limit : DEFAULT_ITEM_LIMIT );
        return jdbc.query( sql.toString(), SqlBinder.positional( params ), EVENT_ROW );
    }

    @Override
    public Set< Long > productIdsUnder( final long epicId ) throws SQLException {
        return new LinkedHashSet<>( jdbc.query( """
            WITH RECURSIVE descendants(id, product_id) AS (
                SELECT id, product_id FROM work_item WHERE parent_id = ?
                UNION
                SELECT c.id, c.product_id FROM work_item c JOIN descendants d ON c.parent_id = d.id )
            SELECT DISTINCT product_id FROM descendants WHERE product_id IS NOT NULL ORDER BY product_id
            """, ps -> ps.setLong( 1, epicId ), rs -> rs.getLong( 1 ) ) );
    }

    // ---- discussion (see JdbcWorkDiscussion)

    @Override
    public Comment addComment( final Item item, final String author, final Actor.Kind kind, final String body,
                               final List< String > mentioned ) throws SQLException {
        return discussion.addComment( item, author, kind, body, mentioned );
    }

    @Override
    public Comment editComment( final long commentId, final String body, final String actor, final Actor.Kind kind,
                                final List< String > mentioned ) throws SQLException {
        return discussion.editComment( commentId, body, actor, kind, mentioned );
    }

    @Override
    public void removeComment( final long commentId, final String actor, final Actor.Kind kind ) throws SQLException {
        discussion.removeComment( commentId, actor, kind );
    }

    @Override
    public Optional< Comment > comment( final long id ) throws SQLException {
        return discussion.comment( id );
    }

    @Override
    public List< Comment > comments( final long itemId ) throws SQLException {
        return discussion.comments( itemId );
    }

    @Override
    public Link addLink( final Item item, final String kind, final String target, final String actor,
                         final Actor.Kind actorKind ) throws SQLException {
        return discussion.addLink( item, kind, target, actor, actorKind );
    }

    @Override
    public void removeLink( final long linkId, final String actor, final Actor.Kind actorKind ) throws SQLException {
        discussion.removeLink( linkId, actor, actorKind );
    }

    @Override
    public Optional< Link > link( final long id ) throws SQLException {
        return discussion.link( id );
    }

    @Override
    public List< Link > links( final long itemId ) throws SQLException {
        return discussion.links( itemId );
    }

    @Override
    public boolean isBlocked( final long itemId ) throws SQLException {
        return discussion.isBlocked( itemId );
    }

    @Override
    public java.util.Map< Long, ItemExtras > itemExtras( final long projectId,
                                                         final java.util.Collection< Long > itemIds )
            throws SQLException {
        return discussion.itemExtras( projectId, itemIds );
    }

    @Override
    public List< Item > mentioning( final String login, final Set< Long > projectIds ) throws SQLException {
        return discussion.mentioning( login, projectIds );
    }

    @Override
    public List< Item > assignedOpen( final String login, final Set< Long > projectIds ) throws SQLException {
        return discussion.assignedOpen( login, projectIds );
    }

    @Override
    public List< ItemCount > itemCounts() throws SQLException {
        return jdbc.query( "SELECT status, level, kind, COUNT(*) AS n FROM work_item GROUP BY status, level, kind",
            ps -> { }, rs -> new ItemCount( WorkStatus.fromWire( rs.getString( "status" ) ),
                WorkLevel.fromWire( rs.getString( "level" ) ), WorkKind.fromWire( rs.getString( "kind" ) ),
                rs.getLong( "n" ) ) );
    }
}
