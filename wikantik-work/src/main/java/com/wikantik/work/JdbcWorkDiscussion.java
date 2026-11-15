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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Comments, mentions, links and the "waiting on me" queries behind {@link JdbcWorkStore}. Every write records one
 * {@code work_event} on the same connection, so it commits or rolls back with the row it describes.
 */
final class JdbcWorkDiscussion {

    private static final String COMMENT_COLUMNS = "id, item_id, author, body, created_at, edited_at, removed_at";

    /** A removed comment keeps its stored body but is returned blank. */
    private static final RowMapper< Comment > COMMENT_ROW = rs -> {
        final Timestamp created = rs.getTimestamp( "created_at" );
        final Timestamp edited = rs.getTimestamp( "edited_at" );
        final Timestamp removed = rs.getTimestamp( "removed_at" );
        return new Comment( rs.getLong( "id" ), rs.getLong( "item_id" ), rs.getString( "author" ),
            removed == null ? rs.getString( "body" ) : "", created == null ? null : created.toInstant(),
            edited == null ? null : edited.toInstant(), removed == null ? null : removed.toInstant() );
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

    Comment addComment( final Item item, final String author, final Actor.Kind kind, final String body,
                        final List< String > mentioned ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final Comment c = jdbc.queryOne( conn, "INSERT INTO work_comment (item_id, author, body) VALUES (?,?,?) "
                    + "RETURNING " + COMMENT_COLUMNS,
                ps -> { ps.setLong( 1, item.id() ); ps.setString( 2, author ); ps.setString( 3, body ); },
                COMMENT_ROW ).orElseThrow( () -> new SQLException( "INSERT work_comment returned no row" ) );
            if ( mentioned != null ) {
                for ( final String login : mentioned ) {
                    jdbc.update( conn, "INSERT INTO work_mention (item_id, comment_id, mentioned) VALUES (?,?,?)",
                        ps -> { ps.setLong( 1, item.id() ); ps.setLong( 2, c.id() ); ps.setString( 3, login ); } );
                }
            }
            store.appendEvent( conn, event( item.projectId(), item.id(), author, kind, WorkEvent.COMMENTED,
                null, String.valueOf( c.id() ) ) );
            return c;
        } );
    }

    Comment editComment( final long commentId, final String body, final String actor ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final Comment c = jdbc.queryOne( conn, "UPDATE work_comment SET body = ?, edited_at = NOW() "
                    + "WHERE id = ? AND removed_at IS NULL RETURNING " + COMMENT_COLUMNS,
                ps -> { ps.setString( 1, body ); ps.setLong( 2, commentId ); }, COMMENT_ROW )
                .orElseThrow( () -> new WorkNotFoundException( "comment " + commentId ) );
            store.appendEvent( conn, event( projectOf( conn, c.itemId() ), c.itemId(), actor, Actor.Kind.HUMAN,
                WorkEvent.COMMENT_EDITED, null, String.valueOf( commentId ) ) );
            return c;
        } );
    }

    void removeComment( final long commentId, final String actor ) throws SQLException {
        jdbc.inTransaction( conn -> {
            final long itemId = jdbc.queryOne( conn, "UPDATE work_comment SET removed_at = NOW() "
                    + "WHERE id = ? AND removed_at IS NULL RETURNING item_id",
                ps -> ps.setLong( 1, commentId ), rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new WorkNotFoundException( "comment " + commentId ) );
            store.appendEvent( conn, event( projectOf( conn, itemId ), itemId, actor, Actor.Kind.HUMAN,
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

    Link addLink( final Item item, final String kind, final String target, final String actor ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final Link l = jdbc.queryOne( conn, "INSERT INTO work_link (item_id, kind, target) VALUES (?,?,?) "
                    + "RETURNING id, item_id, kind, target",
                ps -> { ps.setLong( 1, item.id() ); ps.setString( 2, kind ); ps.setString( 3, target ); }, LINK_ROW )
                .orElseThrow( () -> new SQLException( "INSERT work_link returned no row" ) );
            store.appendEvent( conn, event( item.projectId(), item.id(), actor, Actor.Kind.HUMAN, WorkEvent.LINKED,
                null, kind + ":" + target ) );
            return l;
        } );
    }

    void removeLink( final long linkId, final String actor ) throws SQLException {
        jdbc.inTransaction( conn -> {
            final Link l = jdbc.queryOne( conn, "SELECT id, item_id, kind, target FROM work_link WHERE id = ?",
                ps -> ps.setLong( 1, linkId ), LINK_ROW )
                .orElseThrow( () -> new WorkNotFoundException( "link " + linkId ) );
            jdbc.update( conn, "DELETE FROM work_link WHERE id = ?", ps -> ps.setLong( 1, linkId ) );
            store.appendEvent( conn, event( projectOf( conn, l.itemId() ), l.itemId(), actor, Actor.Kind.HUMAN,
                WorkEvent.UNLINKED, l.kind() + ":" + l.target(), null ) );
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
              JOIN work_item b ON b.status NOT IN ('done','cancelled')
              JOIN work_project bp ON bp.id = b.project_id
             WHERE l.item_id = ? AND l.kind = 'blocked_by'
               AND UPPER( TRIM( l.target ) ) = UPPER( bp.key || '-' || b.seq ) )
            """, ps -> ps.setLong( 1, itemId ), rs -> rs.getBoolean( 1 ) ).orElse( false );
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

    private static WorkEvent event( final long projectId, final long itemId, final String actor,
                                    final Actor.Kind kind, final String type, final String from, final String to ) {
        return new WorkEvent( 0, projectId, itemId, null, null, actor, kind, null, type, from, to, null );
    }
}
