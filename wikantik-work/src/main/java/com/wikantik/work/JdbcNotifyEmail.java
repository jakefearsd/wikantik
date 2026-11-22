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

import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import static com.wikantik.jdbc.NullableColumns.getInstant;

/** The email half of the notification store: what is unmailed, and stamping it. */
final class JdbcNotifyEmail {

    private static final String PENDING = "SELECT n.id AS notice_id, n.recipient, n.reason, n.created_at AS notice_at, "
        + "n.read_at, p.key AS project_key, i.title AS item_title, " + JdbcWorkStore.EVENT_COLUMNS
        + ", COALESCE( pr.email_mode, 'each' ) AS email_mode, pr.last_digest_on, c.body AS comment_body "
        + "FROM work_notification n JOIN work_event e ON e.id = n.event_id JOIN work_project p ON p.id = e.project_id "
        + "LEFT JOIN work_item i ON i.id = e.item_id LEFT JOIN work_notify_pref pr ON pr.login = n.recipient "
        + "LEFT JOIN work_comment c ON e.type IN ( 'commented', 'comment_edited' ) "
        + "AND c.id = CASE WHEN e.to_value ~ '^[0-9]{1,18}$' THEN e.to_value::bigint END AND c.removed_at IS NULL "
        + "WHERE n.emailed_at IS NULL AND n.created_at >= ? AND " + FlagNotices.liveNoticeSql( "n" ) + " "
        + "AND ( COALESCE( pr.email_mode, 'each' ) = 'each' OR ( pr.email_mode = 'daily' AND ? "
        + "AND ( pr.last_digest_on IS NULL OR pr.last_digest_on < ? ) ) ) "
        + "ORDER BY n.recipient, n.id LIMIT ?";
    private static final String STAMP = "UPDATE work_notification SET emailed_at = ? WHERE recipient = ? "
        + "AND id = ANY( ? ) AND emailed_at IS NULL";
    private static final String DIGEST = "INSERT INTO work_notify_pref (login, email_mode, last_digest_on) "
        + "VALUES (?, 'daily', ?) ON CONFLICT (login) DO UPDATE SET last_digest_on = EXCLUDED.last_digest_on";

    private final Jdbc jdbc;

    JdbcNotifyEmail( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    List< PendingEmail > pending( final Instant since, final int limit, final boolean dailyOpen,
                                  final LocalDate today ) throws SQLException {
        return jdbc.query( PENDING, ps -> {
            ps.setTimestamp( 1, Timestamp.from( since ) );
            ps.setBoolean( 2, dailyOpen );
            ps.setDate( 3, Date.valueOf( today ) );
            ps.setInt( 4, limit );
        }, rs -> {
            final Date digest = rs.getDate( "last_digest_on" );
            final NoticeView notice = new NoticeView( rs.getLong( "notice_id" ),
                NoticeReason.fromWire( rs.getString( "reason" ) ), getInstant( rs, "notice_at" ),
                getInstant( rs, "read_at" ), JdbcWorkStore.EVENT_ROW.map( rs ), rs.getString( "project_key" ),
                rs.getString( "item_title" ) );
            return new PendingEmail( rs.getString( "recipient" ), EmailMode.fromWire( rs.getString( "email_mode" ) ),
                digest == null ? null : digest.toLocalDate(), notice, rs.getString( "comment_body" ) );
        } );
    }

    void markEmailed( final String recipient, final Collection< Long > ids, final Instant at,
                      final LocalDate digestOn ) throws SQLException {
        if ( ids.isEmpty() ) {
            return;
        }
        jdbc.inTransaction( conn -> {
            final Array array = conn.createArrayOf( "bigint", ids.toArray( new Long[ 0 ] ) );
            jdbc.update( conn, STAMP, ps -> {
                ps.setTimestamp( 1, Timestamp.from( at ) );
                ps.setString( 2, recipient );
                ps.setArray( 3, array );
            } );
            if ( digestOn != null ) {
                jdbc.update( conn, DIGEST, ps -> {
                    ps.setString( 1, recipient );
                    ps.setDate( 2, Date.valueOf( digestOn ) );
                } );
            }
            return null;
        } );
    }
}
