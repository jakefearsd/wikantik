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
package com.wikantik.collab.adapter;

import com.wikantik.collab.model.DraftEntry;
import com.wikantik.jdbc.Jdbc;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The light draft reads behind {@link com.wikantik.collab.CollabStore#draftEntries},
 * {@link com.wikantik.collab.CollabStore#unpublishedByPage} and {@link com.wikantik.collab.CollabStore#holdsUpdatesFrom}:
 * no bodies are read. Failures are translated as
 * {@link JdbcCollabStore} translates its own.
 */
final class DraftQueries {

    private final Jdbc jdbc;

    DraftQueries( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    /** An edit: a logged row with an author key ({@link com.wikantik.collab.CollabStore#draftEntries}). */
    private static final String EDIT = " author_key IS NOT NULL";
    private static final String NEWER_THAN_MARKER = " AND ( s.last_edit_at IS NULL OR created_at > s.last_edit_at"
        + " + interval '" + com.wikantik.collab.CollabStore.UNRECORDED_EDIT_SLACK.toSeconds() + " seconds' )";

    /**
     * The real last edit is the later of the marker and the newest retained edit ({@code GREATEST} ignores NULLs); the
     * ordering and its code-point tie-break match the in-memory twin.
     */
    List< DraftEntry > entries() {
        try {
            return jdbc.query( "SELECT id, page_name, status, has_unpublished, pending, created_by, created_at, last_edit"
                + " FROM ( SELECT s.id, s.page_name, s.status, s.has_unpublished,"
                + " s.pending_publish_version IS NOT NULL AS pending, s.created_by, s.created_at,"
                + " GREATEST( s.last_edit_at,"
                + " ( SELECT max( created_at ) FROM collab_update WHERE session_id = s.id AND" + EDIT + " ),"
                + " ( SELECT max( created_at ) FROM collab_meta_op WHERE session_id = s.id AND" + EDIT + " ) )"
                + " AS last_edit FROM collab_session s ) d"
                + " ORDER BY coalesce( last_edit, created_at ), page_name COLLATE \"C\"", ps -> { }, rs -> {
                    final Timestamp last = rs.getTimestamp( 8 );
                    return new DraftEntry( rs.getObject( 1, UUID.class ), rs.getString( 2 ), rs.getString( 3 ),
                        rs.getBoolean( 4 ), rs.getBoolean( 5 ), rs.getString( 6 ), rs.getTimestamp( 7 ).toInstant(),
                        last == null ? null : last.toInstant() );
                } );
        } catch( final SQLException e ) {
            throw JdbcCollabStore.translate( e, "draftEntries" );
        }
    }

    /** {@link com.wikantik.collab.CollabStore#newestLoggedEdit}: two indexed probes within the session's log. */
    Optional< Instant > newestLoggedEdit( final UUID id ) {
        try {
            return jdbc.queryOne( "SELECT GREATEST("
                + " ( SELECT max( created_at ) FROM collab_update WHERE session_id = ? AND" + EDIT + " ),"
                + " ( SELECT max( created_at ) FROM collab_meta_op WHERE session_id = ? AND" + EDIT + " ) )", ps -> {
                    ps.setObject( 1, id );
                    ps.setObject( 2, id );
                }, rs -> {
                    final Timestamp t = rs.getTimestamp( 1 );
                    return Optional.ofNullable( t == null ? null : t.toInstant() );
                } ).flatMap( o -> o );
        } catch( final SQLException e ) {
            throw JdbcCollabStore.translate( e, "newestLoggedEdit " + id );
        }
    }

    /**
     * Page name to its stored {@code has_unpublished} flag, or-ed with "the log holds an edit the marker never recorded"
     * (two {@code EXISTS} probes per draft; no bodies).
     */
    Map< String, Boolean > unpublishedByPage() {
        try {
            final Map< String, Boolean > out = new LinkedHashMap<>();
            jdbc.query( "SELECT s.page_name, s.has_unpublished"
                + " OR EXISTS ( SELECT 1 FROM collab_update WHERE session_id = s.id AND" + EDIT + NEWER_THAN_MARKER + " )"
                + " OR EXISTS ( SELECT 1 FROM collab_meta_op WHERE session_id = s.id AND" + EDIT + NEWER_THAN_MARKER
                + " ) FROM collab_session s", ps -> { },
                rs -> out.put( rs.getString( 1 ), rs.getBoolean( 2 ) ) );
            return out;
        } catch( final SQLException e ) {
            throw JdbcCollabStore.translate( e, "unpublishedByPage" );
        }
    }

    /** {@link com.wikantik.collab.CollabStore#holdsUpdatesFrom}: one lookup within the session's log, no rows read. */
    boolean holdsUpdatesFrom( final UUID id, final String clientId, final String authorKey ) {
        try {
            return jdbc.queryOne( "SELECT 1 FROM collab_update WHERE session_id = ? AND kind = 'text' AND client_id = ?"
                + " AND author_key = ? LIMIT 1", ps -> {
                    ps.setObject( 1, id );
                    ps.setString( 2, clientId );
                    ps.setString( 3, authorKey );
                }, rs -> Boolean.TRUE ).isPresent();
        } catch( final SQLException e ) {
            throw JdbcCollabStore.translate( e, "holdsUpdatesFrom " + id );
        }
    }
}
