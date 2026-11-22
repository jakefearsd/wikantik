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

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getInstant;

/**
 * The PostgreSQL {@link WorkWebhookStore}. Each state change appends its {@code settings} event through the catalog in
 * the same transaction. The delivery cursor of a new or re-enabled webhook is planted at the newest event that exists
 * (the same rule as the inbox cursor), so history is never replayed.
 */
final class JdbcWorkWebhooks implements WorkWebhookStore {

    private static final String SELECT = "SELECT id, project_id, url, secret, format, event_types, owner, created_at, "
        + "cursor_tx::text AS cursor_tx, cursor_event_id, failing_since, last_error, last_delivered_at, disabled_at "
        + "FROM work_webhook";

    /** One row, (tx, id) of the newest event, or (xmin, 0) when the log is empty. */
    private static final String SEED = "SELECT COALESCE( n.tx, pg_snapshot_xmin(pg_current_snapshot()) ) AS tx, "
        + "COALESCE( n.id, 0 ) AS id FROM ( SELECT 1 ) one LEFT JOIN LATERAL "
        + "( SELECT tx, id FROM work_event ORDER BY tx DESC, id DESC LIMIT 1 ) n ON true";

    private static final String INSERT = "INSERT INTO work_webhook (project_id, url, secret, format, event_types, owner, "
        + "cursor_tx, cursor_event_id) SELECT ?, ?, ?, ?, ?, ?, seed.tx, seed.id FROM ( " + SEED + " ) seed";

    private static final String PENDING = "SELECT " + JdbcWorkStore.EVENT_COLUMNS + ", e.tx::text AS tx, "
        + "p.key AS project_key, i.title AS item_title, i.status AS item_status, i.type AS item_type "
        + JdbcWorkStore.EVENT_FROM + " WHERE e.project_id = ? AND e.item_id IS NOT NULL "
        + "AND (e.tx, e.id) > (?::xid8, ?) AND e.tx < pg_snapshot_xmin(pg_current_snapshot()) "
        + "ORDER BY e.tx, e.id LIMIT ?";

    private static final RowMapper< Webhook > ROW = rs -> new Webhook( rs.getLong( "id" ), rs.getLong( "project_id" ),
        URI.create( rs.getString( "url" ) ), WebhookFormat.fromWire( rs.getString( "format" ) ),
        rs.getString( "secret" ), JdbcWorkLabels.fromArray( rs.getArray( "event_types" ) ), rs.getString( "owner" ),
        getInstant( rs, "created_at" ), rs.getString( "cursor_tx" ), rs.getLong( "cursor_event_id" ),
        getInstant( rs, "failing_since" ), rs.getString( "last_error" ), getInstant( rs, "last_delivered_at" ),
        getInstant( rs, "disabled_at" ) );

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkWebhooks( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override
    public Webhook add( final Project p, final URI url, final WebhookFormat format, final String secret,
                        final List< String > eventTypes, final Actor by ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final long id = jdbc.insertReturningKey( conn, INSERT, ps -> {
                ps.setLong( 1, p.id() );
                ps.setString( 2, url.toString() );
                ps.setString( 3, secret );
                ps.setString( 4, format.wire() );
                ps.setArray( 5, types( conn, eventTypes ) );
                ps.setString( 6, by.login() );
            }, rs -> rs.getLong( 1 ) ).orElseThrow();
            catalog.appendEvent( conn, event( p.id(), by.login(), url.getHost(), "added" ) );
            return read( conn, id );
        } );
    }

    @Override
    public Optional< Webhook > byId( final long id ) throws SQLException {
        return jdbc.queryOne( SELECT + " WHERE id = ?", ps -> ps.setLong( 1, id ), ROW );
    }

    @Override
    public List< Webhook > forProject( final long projectId ) throws SQLException {
        return jdbc.query( SELECT + " WHERE project_id = ? ORDER BY id", ps -> ps.setLong( 1, projectId ), ROW );
    }

    @Override
    public List< Webhook > enabled() throws SQLException {
        return jdbc.query( SELECT + " WHERE disabled_at IS NULL ORDER BY id", ps -> { }, ROW );
    }

    private Webhook read( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, SELECT + " WHERE id = ?", ps -> ps.setLong( 1, id ), ROW ).orElseThrow();
    }

    @Override
    public Webhook update( final Webhook current, final URI url, final List< String > eventTypes,
                           final Boolean enabled, final Actor by ) throws SQLException {
        final URI newUrl = url == null ? current.url() : url;
        final List< String > newTypes = eventTypes == null ? current.eventTypes() : eventTypes;
        final boolean reEnable = Boolean.TRUE.equals( enabled ) && current.disabledAt() != null;
        final boolean disable = Boolean.FALSE.equals( enabled ) && current.disabledAt() == null;
        final boolean urlChanged = !newUrl.equals( current.url() );
        final boolean changed = urlChanged || !newTypes.equals( current.eventTypes() )
            || !by.login().equals( current.owner() );
        if ( !reEnable && !disable && !changed ) {
            return current;
        }
        return jdbc.inTransaction( conn -> {
            jdbc.update( conn, updateSql( reEnable, disable, urlChanged ), ps -> {
                ps.setString( 1, newUrl.toString() );
                ps.setArray( 2, types( conn, newTypes ) );
                ps.setString( 3, by.login() );
                ps.setLong( 4, current.id() );
            } );
            if ( changed ) {
                catalog.appendEvent( conn, event( current.projectId(), by.login(), newUrl.getHost(), "changed" ) );
            }
            if ( reEnable || disable ) {
                catalog.appendEvent( conn, event( current.projectId(), by.login(), newUrl.getHost(),
                    reEnable ? "enabled" : "disabled" ) );
            }
            return read( conn, current.id() );
        } );
    }

    private static String updateSql( final boolean reEnable, final boolean disable, final boolean urlChanged ) {
        final StringBuilder set = new StringBuilder( "url = ?, event_types = ?, owner = ?" );
        if ( reEnable ) {
            set.append( ", disabled_at = NULL, failing_since = NULL, last_error = NULL, cursor_tx = seed.tx, "
                + "cursor_event_id = seed.id" );
        } else if ( disable ) {
            set.append( ", disabled_at = NOW()" );
        }
        if ( urlChanged && !reEnable ) {
            set.append( ", failing_since = NULL, last_error = NULL" );
        }
        return ( reEnable ? "WITH seed AS ( " + SEED + " ) " : "" ) + "UPDATE work_webhook SET " + set
            + ( reEnable ? " FROM seed" : "" ) + " WHERE work_webhook.id = ?";
    }

    @Override
    public void remove( final Webhook current, final Actor by ) throws SQLException {
        jdbc.inTransaction( conn -> {
            jdbc.update( conn, "DELETE FROM work_webhook WHERE id = ?", ps -> ps.setLong( 1, current.id() ) );
            catalog.appendEvent( conn, event( current.projectId(), by.login(), current.url().getHost(), "removed" ) );
            return null;
        } );
    }

    @Override
    public List< WebhookEvent > pending( final Webhook w, final int limit ) throws SQLException {
        return jdbc.query( PENDING, ps -> {
            ps.setLong( 1, w.projectId() );
            ps.setString( 2, w.cursorTx() );
            ps.setLong( 3, w.cursorEventId() );
            ps.setInt( 4, limit );
        }, rs -> new WebhookEvent( JdbcWorkStore.EVENT_ROW.map( rs ), rs.getString( "tx" ),
            rs.getString( "project_key" ), rs.getString( "item_title" ),
            WorkStatus.fromWire( rs.getString( "item_status" ) ), rs.getString( "item_type" ) ) );
    }

    @Override
    public boolean advance( final long id, final String fromTx, final long fromEventId, final String toTx,
                            final long toEventId, final Instant deliveredAt ) throws SQLException {
        final String stamp = deliveredAt == null ? ""
            : ", last_delivered_at = ?, failing_since = NULL, last_error = NULL";
        return jdbc.update( "UPDATE work_webhook SET cursor_tx = ?::xid8, cursor_event_id = ?" + stamp
            + " WHERE id = ? AND cursor_tx = ?::xid8 AND cursor_event_id = ?", ps -> {
                int i = 1;
                ps.setString( i++, toTx );
                ps.setLong( i++, toEventId );
                if ( deliveredAt != null ) {
                    ps.setTimestamp( i++, Timestamp.from( deliveredAt ) );
                }
                ps.setLong( i++, id );
                ps.setString( i++, fromTx );
                ps.setLong( i, fromEventId );
            } ) > 0;
    }

    @Override
    public void skipToNewest( final long id ) throws SQLException {
        jdbc.update( "WITH seed AS ( " + SEED + " ) UPDATE work_webhook SET cursor_tx = seed.tx, "
            + "cursor_event_id = seed.id FROM seed WHERE work_webhook.id = ? "
            + "AND ( work_webhook.cursor_tx, work_webhook.cursor_event_id ) < ( seed.tx, seed.id )",
            ps -> ps.setLong( 1, id ) );
    }

    @Override
    public void recordFailure( final long id, final Instant at, final String error ) throws SQLException {
        jdbc.update( "UPDATE work_webhook SET failing_since = COALESCE( failing_since, ? ), last_error = ? WHERE id = ?",
            ps -> {
                ps.setTimestamp( 1, Timestamp.from( at ) );
                ps.setString( 2, error );
                ps.setLong( 3, id );
            } );
    }

    @Override
    public boolean disableForFailure( final Webhook w, final Instant at ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final int changed = jdbc.update( conn, "UPDATE work_webhook SET disabled_at = ? WHERE id = ? "
                + "AND disabled_at IS NULL", ps -> {
                    ps.setTimestamp( 1, Timestamp.from( at ) );
                    ps.setLong( 2, w.id() );
                } );
            if ( changed > 0 ) {
                catalog.appendEvent( conn, event( w.projectId(), w.owner(), w.url().getHost(), "failed" ) );
            }
            return changed > 0;
        } );
    }

    private static WorkEvent event( final long projectId, final String actor, final String host, final String what ) {
        return new WorkEvent( 0, projectId, null, null, null, actor, Actor.Kind.HUMAN, null, WorkEvent.SETTINGS,
            Objects.requireNonNull( host, "host" ), what, "webhook" );
    }

    private static java.sql.Array types( final Connection conn, final List< String > eventTypes ) throws SQLException {
        return conn.createArrayOf( "text", eventTypes.toArray( new String[ 0 ] ) );
    }
}
