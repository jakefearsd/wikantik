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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.setNullableLong;

/** The PostgreSQL {@link WorkNotificationStore}: the dispatch transaction, the inbox and the email preference. */
final class JdbcWorkNotifications implements WorkNotificationStore {

    /**
     * The first dispatch plants the cursor at the newest event that exists at that moment, (tx, id), or at (xmin, 0)
     * when the log is empty. Nothing that exists then is ever notified, whatever transactions are open; an event in
     * flight at that instant with a lower transaction id is knowingly skipped (once, at enablement).
     */
    private static final String PLANT_CURSOR = "INSERT INTO work_notify_cursor (name, tx, event_id) "
        + "SELECT 'inbox', COALESCE( newest.tx, pg_snapshot_xmin(pg_current_snapshot()) ), COALESCE( newest.id, 0 ) "
        + "FROM ( SELECT 1 ) one LEFT JOIN LATERAL ( SELECT tx, id FROM work_event ORDER BY tx DESC, id DESC LIMIT 1 ) "
        + "newest ON true ON CONFLICT (name) DO NOTHING";
    private static final String LOCK_CURSOR = "SELECT tx::text, event_id FROM work_notify_cursor "
        + "WHERE name = 'inbox' FOR UPDATE";
    private static final String NEW_EVENTS = "SELECT " + JdbcWorkStore.EVENT_COLUMNS + ", e.tx::text AS tx "
        + JdbcWorkStore.EVENT_FROM + " WHERE (e.tx, e.id) > (?::xid8, ?) "
        + "AND e.tx < pg_snapshot_xmin(pg_current_snapshot()) ORDER BY e.tx, e.id LIMIT ?";
    private static final String MOVE_CURSOR = "UPDATE work_notify_cursor SET tx = ?::xid8, event_id = ? "
        + "WHERE name = 'inbox'";
    private static final String INSERT_NOTICE = "INSERT INTO work_notification "
        + "(recipient, event_id, project_id, item_id, reason) VALUES (?,?,?,?,?) ON CONFLICT (recipient, event_id) DO NOTHING";
    private static final String INBOX_SELECT = "SELECT n.id AS notice_id, n.reason, n.created_at AS notice_at, "
        + "n.read_at, p.key AS project_key, i.title AS item_title, " + JdbcWorkStore.EVENT_COLUMNS
        + " FROM work_notification n JOIN work_event e ON e.id = n.event_id JOIN work_project p ON p.id = e.project_id "
        + "LEFT JOIN work_item i ON i.id = e.item_id WHERE n.recipient = ? AND n.project_id = ANY( ? )";

    private record Cursor( String tx, long eventId ) {
    }

    private record Fetched( WorkEvent event, String tx ) {
    }

    private final Jdbc jdbc;
    private final JdbcDispatchFacts facts;
    private final JdbcNotifyEmail email;

    JdbcWorkNotifications( final Jdbc jdbc, final JdbcWorkWatches watches ) {
        this.jdbc = jdbc;
        this.facts = new JdbcDispatchFacts( jdbc, watches );
        this.email = new JdbcNotifyEmail( jdbc );
    }

    @Override
    public int dispatch( final int maxEvents, final NoticePlanner planner ) throws SQLException {
        if ( maxEvents < 1 ) {
            throw new IllegalArgumentException( "maxEvents must be at least 1, not " + maxEvents );
        }
        return jdbc.inTransaction( conn -> {
            if ( jdbc.update( conn, PLANT_CURSOR, ps -> { } ) > 0 ) {
                return 0;
            }
            final Cursor cursor = jdbc.queryOne( conn, LOCK_CURSOR, ps -> { },
                rs -> new Cursor( rs.getString( 1 ), rs.getLong( 2 ) ) )
                .orElseThrow( () -> new SQLException( "work_notify_cursor has no inbox row" ) );
            final List< Fetched > fetched = jdbc.query( conn, NEW_EVENTS, ps -> {
                ps.setString( 1, cursor.tx() );
                ps.setLong( 2, cursor.eventId() );
                ps.setInt( 3, maxEvents );
            }, rs -> new Fetched( JdbcWorkStore.EVENT_ROW.map( rs ), rs.getString( "tx" ) ) );
            if ( fetched.isEmpty() ) {
                return 0;
            }
            final Fetched last = fetched.get( fetched.size() - 1 );
            final List< WorkEvent > byId = new ArrayList<>();
            fetched.forEach( f -> byId.add( f.event() ) );
            byId.sort( Comparator.comparingLong( WorkEvent::id ) );
            for ( final WorkEvent e : byId ) {
                notify( conn, e, planner );
            }
            jdbc.update( conn, MOVE_CURSOR, ps -> {
                ps.setString( 1, last.tx() );
                ps.setLong( 2, last.event().id() );
            } );
            return fetched.size();
        } );
    }

    private void notify( final Connection conn, final WorkEvent e, final NoticePlanner planner ) throws SQLException {
        final EventFacts f = facts.read( conn, e, planner );
        if ( f == null ) {
            return;
        }
        for ( final Map.Entry< String, NoticeReason > row : planner.plan( e, f ).entrySet() ) {
            jdbc.update( conn, INSERT_NOTICE, ps -> {
                ps.setString( 1, row.getKey() );
                ps.setLong( 2, e.id() );
                ps.setLong( 3, e.projectId() );
                setNullableLong( ps, 4, e.itemId() );
                ps.setString( 5, row.getValue().wire() );
            } );
        }
    }

    @Override
    public List< NoticeView > inbox( final String recipient, final Set< Long > projectIds, final Long beforeId,
                                     final int limit ) throws SQLException {
        if ( projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, INBOX_SELECT + ( beforeId == null ? "" : " AND n.id < ?" )
                + " ORDER BY n.id DESC LIMIT ?", ps -> {
                    ps.setString( 1, recipient );
                    ps.setArray( 2, ids( conn, projectIds ) );
                    int next = 3;
                    if ( beforeId != null ) {
                        ps.setLong( next++, beforeId );
                    }
                    ps.setInt( next, limit );
                }, rs -> new NoticeView( rs.getLong( "notice_id" ), NoticeReason.fromWire( rs.getString( "reason" ) ),
                    getInstant( rs, "notice_at" ), getInstant( rs, "read_at" ), JdbcWorkStore.EVENT_ROW.map( rs ),
                    rs.getString( "project_key" ), rs.getString( "item_title" ) ) ) );
    }

    @Override
    public int unread( final String recipient, final Set< Long > projectIds ) throws SQLException {
        if ( projectIds.isEmpty() ) {
            return 0;
        }
        return jdbc.withConnection( conn -> jdbc.queryOne( conn, "SELECT COUNT(*) FROM work_notification "
                + "WHERE recipient = ? AND read_at IS NULL AND project_id = ANY( ? )", ps -> {
                    ps.setString( 1, recipient );
                    ps.setArray( 2, ids( conn, projectIds ) );
                }, rs -> rs.getInt( 1 ) ).orElse( 0 ) );
    }

    @Override
    public int markRead( final String recipient, final long itemId, final Instant at ) throws SQLException {
        return jdbc.update( "UPDATE work_notification SET read_at = ? WHERE recipient = ? AND item_id = ? "
            + "AND read_at IS NULL", ps -> {
                ps.setTimestamp( 1, Timestamp.from( at ) );
                ps.setString( 2, recipient );
                ps.setLong( 3, itemId );
            } );
    }

    @Override
    public int markAllRead( final String recipient, final Set< Long > projectIds, final Instant at )
            throws SQLException {
        if ( projectIds.isEmpty() ) {
            return 0;
        }
        return jdbc.withConnection( conn -> jdbc.update( conn, "UPDATE work_notification SET read_at = ? "
                + "WHERE recipient = ? AND read_at IS NULL AND project_id = ANY( ? )", ps -> {
                    ps.setTimestamp( 1, Timestamp.from( at ) );
                    ps.setString( 2, recipient );
                    ps.setArray( 3, ids( conn, projectIds ) );
                } ) );
    }

    @Override
    public EmailMode emailMode( final String login ) throws SQLException {
        return jdbc.queryOne( "SELECT email_mode FROM work_notify_pref WHERE login = ?",
            ps -> ps.setString( 1, login ), rs -> EmailMode.fromWire( rs.getString( 1 ) ) ).orElse( EmailMode.EACH );
    }

    @Override
    public void setEmailMode( final String login, final EmailMode mode ) throws SQLException {
        jdbc.update( "INSERT INTO work_notify_pref (login, email_mode) VALUES (?,?) "
            + "ON CONFLICT (login) DO UPDATE SET email_mode = EXCLUDED.email_mode", ps -> {
                ps.setString( 1, login );
                ps.setString( 2, mode.wire() );
            } );
    }

    @Override
    public List< PendingEmail > pendingEmail( final Instant since, final int limit, final boolean dailyOpen,
                                              final LocalDate today ) throws SQLException {
        return email.pending( since, limit, dailyOpen, today );
    }

    @Override
    public void markEmailed( final String recipient, final Collection< Long > ids, final Instant at,
                             final LocalDate digestOn ) throws SQLException {
        email.markEmailed( recipient, ids, at, digestOn );
    }

    private static java.sql.Array ids( final Connection conn, final Set< Long > projectIds ) throws SQLException {
        return conn.createArrayOf( "bigint", projectIds.toArray( new Long[ 0 ] ) );
    }
}
