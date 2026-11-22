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
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.wikantik.jdbc.NullableColumns.getInstant;

/** The PostgreSQL {@link WorkNotificationStore}: the dispatch transaction, the inbox and the email preference. */
final class JdbcWorkNotifications implements WorkNotificationStore {

    private static final String INBOX_SELECT = "SELECT n.id AS notice_id, n.reason, n.created_at AS notice_at, "
        + "n.read_at, p.key AS project_key, i.title AS item_title, " + JdbcWorkStore.EVENT_COLUMNS
        + " FROM work_notification n JOIN work_event e ON e.id = n.event_id JOIN work_project p ON p.id = e.project_id "
        + "LEFT JOIN work_item i ON i.id = e.item_id WHERE n.recipient = ? AND ";
    /**
     * What a reader may read (two array parameters: the projects they see, the projects where they only report): any
     * notice in the first, and in the second only a notice about an item the reader filed themselves (a person's
     * report, never an agent's) of a kind {@link ReporterEvents#notifiedSql} allows. The inner join on the item means an
     * item-less notice never matches the second branch. A notice must also be live ({@link FlagNotices}): a
     * {@code change_flagged} notice whose flag was cleared, superseded or acknowledged is not read, counted or marked.
     */
    private static final String READABLE = "( ( n.project_id = ANY( ? ) OR ( n.project_id = ANY( ? ) AND EXISTS ( "
        + "SELECT 1 FROM work_event re JOIN work_item ri ON ri.id = re.item_id WHERE re.id = n.event_id "
        + "AND ri.id IS NOT NULL AND ri.reporter = n.recipient AND ri.reporter_agent_id IS NULL AND "
        + ReporterEvents.notifiedSql( "re" ) + " ) ) ) AND " + FlagNotices.liveNoticeSql( "n" ) + " )";

    private final Jdbc jdbc;
    private final JdbcNotifyDispatch dispatcher;
    private final JdbcNotifyRetention retention;
    private final JdbcNotifyEmail email;

    JdbcWorkNotifications( final Jdbc jdbc, final JdbcWorkWatches watches ) {
        this.jdbc = jdbc;
        this.dispatcher = new JdbcNotifyDispatch( jdbc, new JdbcDispatchFacts( jdbc, watches ) );
        this.retention = new JdbcNotifyRetention( jdbc );
        this.email = new JdbcNotifyEmail( jdbc );
    }

    @Override
    public int dispatch( final int maxEvents, final NoticePlanner planner ) throws SQLException {
        if ( maxEvents < 1 ) {
            throw new IllegalArgumentException( "maxEvents must be at least 1, not " + maxEvents );
        }
        return dispatcher.dispatch( maxEvents, planner );
    }

    @Override
    public Pruned prune( final Instant before, final int batchSize, final int maxBatches ) throws SQLException {
        return retention.prune( before, batchSize, maxBatches );
    }

    @Override
    public List< NoticeView > inbox( final String recipient, final Readable readable, final Long beforeId,
                                     final int limit ) throws SQLException {
        if ( readable.none() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, INBOX_SELECT + READABLE
                + ( beforeId == null ? "" : " AND n.id < ?" ) + " ORDER BY n.id DESC LIMIT ?", ps -> {
                    ps.setString( 1, recipient );
                    ps.setArray( 2, ids( conn, readable.visible() ) );
                    ps.setArray( 3, ids( conn, readable.reportOnly() ) );
                    int next = 4;
                    if ( beforeId != null ) {
                        ps.setLong( next++, beforeId );
                    }
                    ps.setInt( next, limit );
                }, rs -> new NoticeView( rs.getLong( "notice_id" ), NoticeReason.fromWire( rs.getString( "reason" ) ),
                    getInstant( rs, "notice_at" ), getInstant( rs, "read_at" ), JdbcWorkStore.EVENT_ROW.map( rs ),
                    rs.getString( "project_key" ), rs.getString( "item_title" ) ) ) );
    }

    @Override
    public int unread( final String recipient, final Readable readable ) throws SQLException {
        if ( readable.none() ) {
            return 0;
        }
        return jdbc.withConnection( conn -> jdbc.queryOne( conn, "SELECT COUNT(*) FROM work_notification n "
                + "WHERE n.recipient = ? AND n.read_at IS NULL AND " + READABLE, ps -> {
                    ps.setString( 1, recipient );
                    ps.setArray( 2, ids( conn, readable.visible() ) );
                    ps.setArray( 3, ids( conn, readable.reportOnly() ) );
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
    public int markAllRead( final String recipient, final Readable readable, final Instant at )
            throws SQLException {
        if ( readable.none() ) {
            return 0;
        }
        return jdbc.withConnection( conn -> jdbc.update( conn, "UPDATE work_notification n SET read_at = ? "
                + "WHERE n.recipient = ? AND n.read_at IS NULL AND " + READABLE, ps -> {
                    ps.setTimestamp( 1, Timestamp.from( at ) );
                    ps.setString( 2, recipient );
                    ps.setArray( 3, ids( conn, readable.visible() ) );
                    ps.setArray( 4, ids( conn, readable.reportOnly() ) );
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
