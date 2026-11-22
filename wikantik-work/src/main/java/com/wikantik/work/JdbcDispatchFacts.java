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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reads, on the dispatch transaction's connection, the facts about an event that {@link Recipients} needs. */
final class JdbcDispatchFacts {

    private static final String UNMENTIONED = """
        SELECT DISTINCT m.mentioned FROM work_mention m
         WHERE m.comment_id = ? AND NOT EXISTS (
               SELECT 1 FROM work_notification n JOIN work_event pe ON pe.id = n.event_id
                WHERE n.recipient = m.mentioned AND n.reason = 'mentioned' AND pe.item_id = ?
                  AND pe.type IN ('commented','comment_edited') AND pe.to_value = ? )
        """;
    private static final String ITEM = "SELECT reporter, reporter_agent_id, "
        + "( ack_required AND acknowledged_at IS NULL AND status = 'done' ) FROM work_item WHERE id = ?";
    /**
     * The project's lead rows of people and groups. V091's CHECK forbids an agent {@code lead} row, so the
     * {@code principal_kind} clause can no longer meet one in a migrated database; it stays as the second line (a row
     * written before the migration, or a constraint dropped by hand), and {@code JdbcDispatchFactsTest} pins it.
     */
    static final String LEADS = "SELECT principal_kind, principal FROM work_member "
        + "WHERE project_id = ? AND role = 'lead' AND principal_kind IN ('user','group')";

    /** The settings event a webhook switched off by failing writes; the only item-less event that notifies anyone. */
    static boolean isWebhookFailure( final WorkEvent e ) {
        return WorkEvent.SETTINGS.equals( e.type() ) && "webhook".equals( e.note() ) && "failed".equals( e.toValue() );
    }

    private record Lead( String kind, String principal ) {
    }

    private record ItemFacts( String reporter, boolean ackPending ) {
    }

    private final Jdbc jdbc;
    private final JdbcWorkWatches watches;

    JdbcDispatchFacts( final Jdbc jdbc, final JdbcWorkWatches watches ) {
        this.jdbc = jdbc;
        this.watches = watches;
    }

    private record CommentFacts( Set< String > mentioned, boolean removed ) {
        static final CommentFacts NONE = new CommentFacts( Set.of(), false );
    }

    /** Applies the event's auto-watch changes, then reads the facts; null when the event notifies nobody by design. */
    EventFacts read( final Connection conn, final WorkEvent e, final WorkNotificationStore.NoticePlanner planner )
            throws SQLException {
        final Long itemId = e.itemId();
        if ( itemId == null && !isWebhookFailure( e ) ) {
            return null;
        }
        final Set< String > leads = new LinkedHashSet<>();
        final Set< String > groups = new LinkedHashSet<>();
        leads( conn, e.projectId(), leads, groups );
        if ( itemId == null ) {
            return new EventFacts( null, Set.of(), Set.of(), leads, groups, false, false );
        }
        final ItemFacts item = item( conn, itemId );
        final CommentFacts comment = comment( conn, e, itemId );
        for ( final AutoWatch.Change c : AutoWatch.of( e, item.reporter(), comment.mentioned(), comment.removed() ) ) {
            if ( !planner.mayWatch( e, c.login() ) ) {
                continue;
            }
            if ( c.subscribe() ) {
                watches.subscribe( conn, itemId, c.login() );
            } else {
                watches.watch( conn, itemId, c.login() );
            }
        }
        return new EventFacts( item.reporter(), watches.watchers( conn, itemId ), comment.mentioned(), leads, groups,
            item.ackPending(), comment.removed() );
    }

    private CommentFacts comment( final Connection conn, final WorkEvent e, final long itemId ) throws SQLException {
        final String to = e.toValue();
        if ( !isComment( e ) || to == null || to.isEmpty() || !to.chars().allMatch( Character::isDigit ) ) {
            return CommentFacts.NONE;
        }
        final long commentId = Long.parseLong( to );
        return new CommentFacts( mentioned( conn, commentId, itemId, to ), commentRemoved( conn, commentId ) );
    }

    private void leads( final Connection conn, final long projectId, final Set< String > leads,
                        final Set< String > groups ) throws SQLException {
        final List< Lead > rows = jdbc.query( conn, LEADS, ps -> ps.setLong( 1, projectId ),
            rs -> new Lead( rs.getString( 1 ), rs.getString( 2 ) ) );
        for ( final Lead l : rows ) {
            ( "group".equals( l.kind() ) ? groups : leads ).add( l.principal() );
        }
    }

    private static boolean isComment( final WorkEvent e ) {
        return WorkEvent.COMMENTED.equals( e.type() ) || WorkEvent.COMMENT_EDITED.equals( e.type() );
    }

    private ItemFacts item( final Connection conn, final long itemId ) throws SQLException {
        return jdbc.queryOne( conn, ITEM, ps -> ps.setLong( 1, itemId ), rs -> {
            final String reporter = rs.getString( 1 );
            rs.getLong( 2 );
            return new ItemFacts( rs.wasNull() ? reporter : null, rs.getBoolean( 3 ) );
        } ).orElse( new ItemFacts( null, false ) );
    }

    private boolean commentRemoved( final Connection conn, final long commentId ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT removed_at IS NOT NULL FROM work_comment WHERE id = ?",
            ps -> ps.setLong( 1, commentId ), rs -> rs.getBoolean( 1 ) ).orElse( false );
    }

    private Set< String > mentioned( final Connection conn, final long commentId, final long itemId, final String to )
            throws SQLException {
        return new LinkedHashSet<>( jdbc.query( conn, UNMENTIONED, ps -> {
            ps.setLong( 1, commentId );
            ps.setLong( 2, itemId );
            ps.setString( 3, to );
        }, rs -> rs.getString( 1 ) ) );
    }
}
