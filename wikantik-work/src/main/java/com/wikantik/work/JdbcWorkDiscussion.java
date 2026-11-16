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

import static com.wikantik.work.SqlValues.nullableLong;
import static com.wikantik.work.SqlValues.setNullableLong;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.RowMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Comments, mentions, links and the "waiting on me" queries behind {@link JdbcWorkStore}. Every write records one
 * {@code work_event} on the same connection, so it commits or rolls back with the row it describes.
 */
final class JdbcWorkDiscussion {

    private static final String COMMENT_COLUMNS = "id, item_id, author, body, created_at, edited_at, removed_at, author_agent_id";

    /** A removed comment keeps its stored body but is returned blank. */
    private static final RowMapper< Comment > COMMENT_ROW = rs -> {
        final Timestamp created = rs.getTimestamp( "created_at" );
        final Timestamp edited = rs.getTimestamp( "edited_at" );
        final Timestamp removed = rs.getTimestamp( "removed_at" );
        return new Comment( rs.getLong( "id" ), rs.getLong( "item_id" ), rs.getString( "author" ),
            removed == null ? rs.getString( "body" ) : "", created == null ? null : created.toInstant(),
            edited == null ? null : edited.toInstant(), removed == null ? null : removed.toInstant(),
            nullableLong( rs, "author_agent_id" ) );
    };

    private static final RowMapper< Link > LINK_ROW = rs -> new Link( rs.getLong( "id" ), rs.getLong( "item_id" ),
        rs.getString( "kind" ), rs.getString( "target" ) );

    private static final String OPEN_ONLY = " AND i.status NOT IN ('done','cancelled') ";
    private static final String NEWEST_FIRST = " ORDER BY i.updated_at DESC, i.id DESC";

    private final Jdbc jdbc;
    private final JdbcWorkStore store;

    JdbcWorkDiscussion( final Jdbc jdbc, final JdbcWorkStore store ) {
        this.jdbc = jdbc;
        this.store = store;
    }

    Comment addComment( final Item item, final Actor by, final String body, final List< String > mentioned )
            throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final Comment c = jdbc.queryOne( conn, "INSERT INTO work_comment (item_id, author, body, "
                    + "author_agent_id) VALUES (?,?,?,?) RETURNING " + COMMENT_COLUMNS,
                ps -> {
                    ps.setLong( 1, item.id() );
                    ps.setString( 2, by.login() );
                    ps.setString( 3, body );
                    setNullableLong( ps, 4, by.agentId() );
                },
                COMMENT_ROW ).orElseThrow( () -> new SQLException( "INSERT work_comment returned no row" ) );
            insertMentions( conn, item.id(), c.id(), mentioned );
            store.appendEvent( conn, event( item.projectId(), item.id(), by, WorkEvent.COMMENTED,
                null, String.valueOf( c.id() ) ) );
            return c;
        } );
    }

    private void insertMentions( final Connection conn, final long itemId, final long commentId,
                                 final List< String > mentioned ) throws SQLException {
        if ( mentioned == null ) {
            return;
        }
        for ( final String login : mentioned ) {
            jdbc.update( conn, "INSERT INTO work_mention (item_id, comment_id, mentioned) VALUES (?,?,?)",
                ps -> { ps.setLong( 1, itemId ); ps.setLong( 2, commentId ); ps.setString( 3, login ); } );
        }
    }

    Comment editComment( final long commentId, final String body, final Actor by,
                         final List< String > mentioned ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final Comment c = jdbc.queryOne( conn, "UPDATE work_comment SET body = ?, edited_at = NOW() "
                    + "WHERE id = ? AND removed_at IS NULL RETURNING " + COMMENT_COLUMNS,
                ps -> { ps.setString( 1, body ); ps.setLong( 2, commentId ); }, COMMENT_ROW )
                .orElseThrow( () -> new WorkNotFoundException( "comment " + commentId ) );
            // The mentions follow the text: a name taken out of the comment stops waiting on it.
            jdbc.update( conn, "DELETE FROM work_mention WHERE comment_id = ?", ps -> ps.setLong( 1, commentId ) );
            insertMentions( conn, c.itemId(), commentId, mentioned );
            store.appendEvent( conn, event( projectOf( conn, c.itemId() ), c.itemId(), by,
                WorkEvent.COMMENT_EDITED, null, String.valueOf( commentId ) ) );
            return c;
        } );
    }

    void removeComment( final long commentId, final Actor by ) throws SQLException {
        jdbc.inTransaction( conn -> {
            final long itemId = jdbc.queryOne( conn, "UPDATE work_comment SET removed_at = NOW() "
                    + "WHERE id = ? AND removed_at IS NULL RETURNING item_id",
                ps -> ps.setLong( 1, commentId ), rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new WorkNotFoundException( "comment " + commentId ) );
            store.appendEvent( conn, event( projectOf( conn, itemId ), itemId, by,
                WorkEvent.COMMENT_REMOVED, null, String.valueOf( commentId ) ) );
            return null;
        } );
    }

    Optional< Comment > comment( final long id ) throws SQLException {
        return jdbc.queryOne( "SELECT " + COMMENT_COLUMNS + " FROM work_comment WHERE id = ?",
            ps -> ps.setLong( 1, id ), COMMENT_ROW );
    }

    List< Comment > comments( final long itemId ) throws SQLException {
        return jdbc.query( "SELECT " + COMMENT_COLUMNS + " FROM work_comment WHERE item_id = ? "
            + "ORDER BY created_at, id", ps -> ps.setLong( 1, itemId ), COMMENT_ROW );
    }

    Link addLink( final Item item, final String kind, final String target, final Actor by )
            throws SQLException {
        return jdbc.inTransaction( conn -> {
            final Link l = jdbc.queryOne( conn, "INSERT INTO work_link (item_id, kind, target) VALUES (?,?,?) "
                    + "RETURNING id, item_id, kind, target",
                ps -> { ps.setLong( 1, item.id() ); ps.setString( 2, kind ); ps.setString( 3, target ); }, LINK_ROW )
                .orElseThrow( () -> new SQLException( "INSERT work_link returned no row" ) );
            store.appendEvent( conn, event( item.projectId(), item.id(), by, WorkEvent.LINKED,
                null, kind + ":" + target ) );
            return l;
        } );
    }

    /** One statement deletes and returns the row, so of two concurrent removals only one writes an event. */
    void removeLink( final long linkId, final Actor by ) throws SQLException {
        jdbc.inTransaction( conn -> {
            final Optional< Link > removed = jdbc.queryOne( conn,
                "DELETE FROM work_link WHERE id = ? RETURNING id, item_id, kind, target",
                ps -> ps.setLong( 1, linkId ), LINK_ROW );
            if ( removed.isPresent() ) {
                final Link l = removed.get();
                store.appendEvent( conn, event( projectOf( conn, l.itemId() ), l.itemId(), by,
                    WorkEvent.UNLINKED, l.kind() + ":" + l.target(), null ) );
            }
            return null;
        } );
    }

    Optional< Link > link( final long id ) throws SQLException {
        return jdbc.queryOne( "SELECT id, item_id, kind, target FROM work_link WHERE id = ?",
            ps -> ps.setLong( 1, id ), LINK_ROW );
    }

    List< Link > links( final long itemId ) throws SQLException {
        return jdbc.query( "SELECT id, item_id, kind, target FROM work_link WHERE item_id = ? ORDER BY id",
            ps -> ps.setLong( 1, itemId ), LINK_ROW );
    }

    boolean isBlocked( final long itemId ) throws SQLException {
        return jdbc.queryOne( """
            SELECT EXISTS ( SELECT 1
              FROM work_link l
              JOIN work_item li ON li.id = l.item_id
              JOIN work_item b ON b.status NOT IN ('done','cancelled') AND b.project_id = li.project_id
              JOIN work_project bp ON bp.id = b.project_id
             WHERE l.item_id = ? AND l.kind = 'blocked_by'
               AND UPPER( TRIM( l.target ) ) = UPPER( bp.key || '-' || b.seq ) )
            """, ps -> ps.setLong( 1, itemId ), rs -> rs.getBoolean( 1 ) ).orElse( false );
    }

    Map< Long, WorkStore.ItemExtras > itemExtras( final long projectId, final java.util.Collection< Long > itemIds )
            throws SQLException {
        if ( itemIds == null || itemIds.isEmpty() ) {
            return Map.of();
        }
        final Map< Long, WorkStore.ItemExtras > out = new HashMap<>();
        jdbc.withConnection( conn -> jdbc.query( conn, """
            SELECT i.id,
                   ( SELECT COUNT(*) FROM work_comment c WHERE c.item_id = i.id AND c.removed_at IS NULL ) AS comments,
                   EXISTS ( SELECT 1
                              FROM work_link l
                              JOIN work_item b ON b.status NOT IN ('done','cancelled') AND b.project_id = i.project_id
                             WHERE l.item_id = i.id AND l.kind = 'blocked_by'
                               AND UPPER( TRIM( l.target ) ) = UPPER( pp.key || '-' || b.seq ) ) AS blocked,
                   pr.key AS product_key,
                   CASE WHEN par.id IS NULL THEN NULL ELSE pp.key || '-' || par.seq END AS parent_key
              FROM work_item i
              JOIN work_project pp ON pp.id = i.project_id
              LEFT JOIN work_product pr ON pr.id = i.product_id
              LEFT JOIN work_item par ON par.id = i.parent_id
             WHERE i.project_id = ? AND i.id = ANY( ? )
            """,
            ps -> { ps.setLong( 1, projectId ); ps.setArray( 2, projectArray( conn, new java.util.HashSet<>( itemIds ) ) ); },
            rs -> {
                out.put( rs.getLong( "id" ), new WorkStore.ItemExtras( rs.getInt( "comments" ),
                    rs.getBoolean( "blocked" ), rs.getString( "product_key" ), rs.getString( "parent_key" ) ) );
                return null;
            } ) );
        return out;
    }

    /** The newest {@code handoff} event of each item: {@code DISTINCT ON (item_id)} over {@code at DESC, id DESC}. */
    Map< Long, WorkEvent > lastHandoffs( final java.util.Collection< Long > itemIds ) throws SQLException {
        if ( itemIds == null || itemIds.isEmpty() ) {
            return Map.of();
        }
        final Map< Long, WorkEvent > out = new HashMap<>();
        jdbc.withConnection( conn -> jdbc.query( conn, "SELECT DISTINCT ON (e.item_id) "
                + JdbcWorkStore.EVENT_COLUMNS + JdbcWorkStore.EVENT_FROM
                + " WHERE e.type = 'handoff' AND e.item_id = ANY( ? ) ORDER BY e.item_id, e.at DESC, e.id DESC",
            ps -> ps.setArray( 1, projectArray( conn, new java.util.HashSet<>( itemIds ) ) ),
            rs -> {
                final WorkEvent e = JdbcWorkStore.EVENT_ROW.map( rs );
                out.put( e.itemId(), e );
                return null;
            } ) );
        return out;
    }

    List< Item > mentioning( final String login, final Set< Long > projectIds ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, JdbcWorkStore.ITEM_SELECT
                + " WHERE i.project_id = ANY( ? )" + OPEN_ONLY
                + "AND EXISTS ( SELECT 1 FROM work_mention m LEFT JOIN work_comment c ON c.id = m.comment_id "
                + "WHERE m.item_id = i.id AND m.mentioned = ? AND c.removed_at IS NULL )" + NEWEST_FIRST,
            ps -> { ps.setArray( 1, projectArray( conn, projectIds ) ); ps.setString( 2, login ); },
            JdbcWorkStore.ITEM_ROW ) );
    }

    List< Item > assignedOpen( final String login, final Set< Long > projectIds ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, JdbcWorkStore.ITEM_SELECT
                + " WHERE i.project_id = ANY( ? )" + OPEN_ONLY + "AND i.assignee = ?" + NEWEST_FIRST,
            ps -> { ps.setArray( 1, projectArray( conn, projectIds ) ); ps.setString( 2, login ); },
            JdbcWorkStore.ITEM_ROW ) );
    }

    private static java.sql.Array projectArray( final Connection conn, final Set< Long > ids ) throws SQLException {
        return conn.createArrayOf( "bigint", ids.toArray( new Long[ 0 ] ) );
    }

    private long projectOf( final Connection conn, final long itemId ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT project_id FROM work_item WHERE id = ?",
            ps -> ps.setLong( 1, itemId ), rs -> rs.getLong( 1 ) )
            .orElseThrow( () -> new SQLException( "work_item " + itemId + " not found" ) );
    }

    private static WorkEvent event( final long projectId, final long itemId, final Actor by, final String type,
                                    final String from, final String to ) {
        return new WorkEvent( 0, projectId, itemId, null, null, by.login(), by.kind(), by.agentId(), type, from,
            to, null );
    }
}
