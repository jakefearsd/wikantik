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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.wikantik.jdbc.NullableColumns.setNullableLong;

/**
 * The dispatch transaction. A batch of events is one transaction (a failure leaves rows and cursor untouched). When a
 * batch fails for a reason that will repeat (a poison event), the same events are tried again one per transaction; an
 * event that still fails that way is skipped: the cursor moves past it, a {@code work_notify_skipped} row keeps it, an
 * ERROR is logged and the planner is told. A failure that goes away by itself ({@link TransientFailure}) skips
 * nothing and is retried by the next call exactly as before.
 */
final class JdbcNotifyDispatch {

    private static final Logger LOG = LogManager.getLogger( JdbcNotifyDispatch.class );
    private static final int MAX_MESSAGE = 500;

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
    /** Moves the cursor forward only: never back over an event another pass already consumed. */
    private static final String MOVE_CURSOR_FORWARD = MOVE_CURSOR + " AND (tx, event_id) < (?::xid8, ?)";
    private static final String INSERT_NOTICE = "INSERT INTO work_notification "
        + "(recipient, event_id, project_id, item_id, reason) VALUES (?,?,?,?,?) ON CONFLICT (recipient, event_id) DO NOTHING";
    private static final String INSERT_SKIPPED = "INSERT INTO work_notify_skipped (event_id, tx, error_class, "
        + "error_message) VALUES (?, ?::xid8, ?, ?)";

    private record Cursor( String tx, long eventId ) {
    }

    private record Fetched( WorkEvent event, String tx ) {
    }

    /** A planning or fact-reading failure for one event, carried out of its (rolled back) transaction. */
    private static final class EventFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Fetched at;

        EventFailure( final Fetched at, final Exception cause ) {
            super( cause );
            this.at = at;
        }
    }

    private final Jdbc jdbc;
    private final JdbcDispatchFacts facts;

    JdbcNotifyDispatch( final Jdbc jdbc, final JdbcDispatchFacts facts ) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    int dispatch( final int maxEvents, final WorkNotificationStore.NoticePlanner planner ) throws SQLException {
        try {
            return jdbc.inTransaction( conn -> batch( conn, maxEvents, planner ) );
        } catch ( final EventFailure f ) {
            throwIfTransient( f );
            LOG.warn( "A work notification batch failed on event {} ({}); trying its events one at a time",
                f.at.event().id(), f.getCause().toString() );
            return oneByOne( maxEvents, planner );
        }
    }

    private int batch( final Connection conn, final int maxEvents, final WorkNotificationStore.NoticePlanner planner )
            throws SQLException {
        if ( jdbc.update( conn, PLANT_CURSOR, ps -> { } ) > 0 ) {
            return 0;
        }
        final List< Fetched > fetched = fetch( conn, lock( conn ), maxEvents );
        if ( fetched.isEmpty() ) {
            return 0;
        }
        final List< Fetched > byId = new ArrayList<>( fetched );
        byId.sort( Comparator.comparingLong( f -> f.event().id() ) );
        for ( final Fetched f : byId ) {
            notice( conn, f, planner );
        }
        moveTo( conn, fetched.get( fetched.size() - 1 ) );
        return fetched.size();
    }

    private int oneByOne( final int maxEvents, final WorkNotificationStore.NoticePlanner planner ) throws SQLException {
        int done = 0;
        while ( done < maxEvents ) {
            final int n = one( planner );
            if ( n == 0 ) {
                break;
            }
            done += n;
        }
        return done;
    }

    private int one( final WorkNotificationStore.NoticePlanner planner ) throws SQLException {
        try {
            return jdbc.inTransaction( conn -> {
                final List< Fetched > fetched = fetch( conn, lock( conn ), 1 );
                if ( fetched.isEmpty() ) {
                    return 0;
                }
                notice( conn, fetched.get( 0 ), planner );
                moveTo( conn, fetched.get( 0 ) );
                return 1;
            } );
        } catch ( final EventFailure f ) {
            throwIfTransient( f );
            skip( f, planner );
            return 1;
        }
    }

    /** Rethrows the underlying failure when it goes away by itself: the next call retries and nothing is skipped. */
    private static void throwIfTransient( final EventFailure f ) throws SQLException {
        final Throwable cause = f.getCause();
        if ( !TransientFailure.is( cause ) ) {
            return;
        }
        if ( cause instanceof SQLException s ) {
            throw s;
        }
        throw ( RuntimeException ) cause;
    }

    private void skip( final EventFailure f, final WorkNotificationStore.NoticePlanner planner ) throws SQLException {
        final Throwable cause = f.getCause();
        final Fetched at = f.at;
        final boolean skipped = jdbc.inTransaction( conn -> {
            lock( conn );
            final int moved = jdbc.update( conn, MOVE_CURSOR_FORWARD, ps -> {
                ps.setString( 1, at.tx() );
                ps.setLong( 2, at.event().id() );
                ps.setString( 3, at.tx() );
                ps.setLong( 4, at.event().id() );
            } );
            if ( moved == 0 ) {
                return false;   // another dispatcher already passed this event: nothing was skipped here
            }
            jdbc.update( conn, INSERT_SKIPPED, ps -> {
                ps.setLong( 1, at.event().id() );
                ps.setString( 2, at.tx() );
                ps.setString( 3, cause.getClass().getName() );
                ps.setString( 4, truncated( cause.getMessage() ) );
            } );
            return true;
        } );
        if ( !skipped ) {
            return;
        }
        LOG.error( "Work notification skipped event {} (type {}, transaction {}): it cannot be turned into notifications "
            + "and was recorded in work_notify_skipped; later events are unaffected", at.event().id(),
            at.event().type(), at.tx(), cause );
        planner.skipped( at.event(), cause );
    }

    private static String truncated( final String message ) {
        if ( message == null ) {
            return "";
        }
        final String clean = message.replace( "\u0000", "" );   // PostgreSQL text cannot hold NUL: the insert would fail for ever
        return clean.length() <= MAX_MESSAGE ? clean : clean.substring( 0, MAX_MESSAGE );
    }

    private Cursor lock( final Connection conn ) throws SQLException {
        return jdbc.queryOne( conn, LOCK_CURSOR, ps -> { },
            rs -> new Cursor( rs.getString( 1 ), rs.getLong( 2 ) ) )
            .orElseThrow( () -> new SQLException( "work_notify_cursor has no inbox row" ) );
    }

    private List< Fetched > fetch( final Connection conn, final Cursor cursor, final int limit ) throws SQLException {
        return jdbc.query( conn, NEW_EVENTS, ps -> {
            ps.setString( 1, cursor.tx() );
            ps.setLong( 2, cursor.eventId() );
            ps.setInt( 3, limit );
        }, rs -> new Fetched( JdbcWorkStore.EVENT_ROW.map( rs ), rs.getString( "tx" ) ) );
    }

    private void moveTo( final Connection conn, final Fetched last ) throws SQLException {
        jdbc.update( conn, MOVE_CURSOR, ps -> {
            ps.setString( 1, last.tx() );
            ps.setLong( 2, last.event().id() );
        } );
    }

    private void notice( final Connection conn, final Fetched f, final WorkNotificationStore.NoticePlanner planner )
            throws SQLException {
        final WorkEvent e = f.event();
        try {
            final EventFacts read = facts.read( conn, e, planner );
            if ( read == null ) {
                return;
            }
            for ( final Map.Entry< String, NoticeReason > row : planner.plan( e, read ).entrySet() ) {
                jdbc.update( conn, INSERT_NOTICE, ps -> {
                    ps.setString( 1, row.getKey() );
                    ps.setLong( 2, e.id() );
                    ps.setLong( 3, e.projectId() );
                    setNullableLong( ps, 4, e.itemId() );
                    ps.setString( 5, row.getValue().wire() );
                } );
            }
        } catch ( final SQLException | RuntimeException ex ) {
            throw new EventFailure( f, ex );
        }
    }
}
