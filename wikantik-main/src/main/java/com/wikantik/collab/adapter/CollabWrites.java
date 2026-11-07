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

import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSetJson;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * The in-transaction write steps of {@link JdbcCollabStore}, each on a caller-supplied connection: the log appends
 * (contiguity check, row inserts, guarded head or sequence bump), the snapshot upsert and the session-field update.
 */
final class CollabWrites {

    private final Jdbc jdbc;

    CollabWrites( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    /** Contiguity check, row inserts, then the head bump guarded on the head read under the row lock. */
    void appendUpdatesTx( final Connection conn, final UUID id, final int head,
                                  final List< StoredUpdate > updates ) throws SQLException {
        if( updates.isEmpty() ) {
            return;
        }
        int expected = head;
        for( final StoredUpdate u : updates ) {
            expected++;
            if( u.version() != expected ) {
                throw new IllegalStateException( "update version " + u.version() + " is not " + expected + " (head "
                    + head + ") in collab session " + id );
            }
        }
        for( final StoredUpdate u : updates ) {
            jdbc.update( conn, "INSERT INTO collab_update ( session_id, version, kind, client_id, author_key, changes )"
                + " VALUES ( ?, ?, ?, ?, ?, ?::jsonb )", ps -> {
                    ps.setObject( 1, id );
                    ps.setInt( 2, u.version() );
                    ps.setString( 3, u.kind() );
                    ps.setString( 4, u.clientId() );
                    ps.setString( 5, u.authorKey() );
                    ps.setString( 6, ChangeSetJson.toJson( u.changes() ).toString() );
                } );
        }
        final int moved = jdbc.update( conn, "UPDATE collab_session SET head_version = head_version + ?"
            + " WHERE id = ? AND head_version = ?", ps -> {
                ps.setInt( 1, updates.size() );
                ps.setObject( 2, id );
                ps.setInt( 3, head );
            } );
        if( moved == 0 ) {
            throw new IllegalStateException( "collab session " + id + " moved past head " + head + " during the append" );
        }
    }

    void appendMetaOpsTx( final Connection conn, final UUID id, final long metaSeq,
                                  final List< StoredMetaOp > ops ) throws SQLException {
        if( ops.isEmpty() ) {
            return;
        }
        long expected = metaSeq;
        for( final StoredMetaOp o : ops ) {
            expected++;
            if( o.seq() != expected ) {
                throw new IllegalStateException( "meta seq " + o.seq() + " is not " + expected + " (metaSeq " + metaSeq
                    + ") in collab session " + id );
            }
        }
        for( final StoredMetaOp o : ops ) {
            jdbc.update( conn, "INSERT INTO collab_meta_op ( session_id, seq, author_key, field, value )"
                + " VALUES ( ?, ?, ?, ?, ?::jsonb )", ps -> {
                    ps.setObject( 1, id );
                    ps.setLong( 2, o.seq() );
                    ps.setString( 3, o.authorKey() );
                    ps.setString( 4, o.field() );
                    if( o.value() == null ) {
                        ps.setNull( 5, Types.OTHER );
                    } else {
                        ps.setString( 5, CollabRows.GSON.toJson( o.value() ) );
                    }
                } );
        }
        final int moved = jdbc.update( conn, "UPDATE collab_session SET meta_seq = meta_seq + ?"
            + " WHERE id = ? AND meta_seq = ?", ps -> {
                ps.setLong( 1, ops.size() );
                ps.setObject( 2, id );
                ps.setLong( 3, metaSeq );
            } );
        if( moved == 0 ) {
            throw new IllegalStateException( "collab session " + id + " moved past metaSeq " + metaSeq
                + " during the append" );
        }
    }

    void writeSnapshotTx( final Connection conn, final UUID id, final Snapshot s ) throws SQLException {
        jdbc.update( conn, "INSERT INTO collab_snapshot ( session_id, version, meta_seq, base_page_version, body, meta,"
            + " local_changes ) VALUES ( ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb ) ON CONFLICT ( session_id, version )"
            + " DO UPDATE SET meta_seq = EXCLUDED.meta_seq, base_page_version = EXCLUDED.base_page_version,"
            + " body = EXCLUDED.body, meta = EXCLUDED.meta, local_changes = EXCLUDED.local_changes", ps -> {
                ps.setObject( 1, id );
                ps.setInt( 2, s.version() );
                ps.setLong( 3, s.metaSeq() );
                ps.setInt( 4, s.basePageVersion() );
                ps.setString( 5, s.body() );
                ps.setString( 6, CollabRows.GSON.toJson( s.meta() ) );
                ps.setString( 7, ChangeSetJson.toJson( s.local() ).toString() );
            } );
    }

    /** The mutable-field UPDATE of {@link CollabStore#updateSession}; {@code conn == null} = its own statement. */
    int writeSessionFields( final Connection conn, final SessionRecord s )
            throws SQLException {
        final String sql = "UPDATE collab_session SET page_name = ?, status = ?, base_page_version = ?, base_body = ?,"
            + " base_meta = ?::jsonb, base_content_hash = ?, has_unpublished = ?, pending_publish_version = ?,"
            + " pending_publish_base_version = ?, pending_publish_author = ?, last_edit_at = ? WHERE id = ?";
        final SqlBinder binder = ps -> {
            ps.setString( 1, s.pageName() );
            ps.setString( 2, s.status() );
            ps.setInt( 3, s.basePageVersion() );
            ps.setString( 4, s.baseBody() );
            ps.setString( 5, CollabRows.GSON.toJson( s.baseMeta() ) );
            ps.setString( 6, s.baseContentHash() );
            ps.setBoolean( 7, s.hasUnpublished() );
            CollabRows.setInteger( ps, 8, s.pendingPublishVersion() );
            CollabRows.setInteger( ps, 9, s.pendingPublishBaseVersion() );
            ps.setString( 10, s.pendingPublishAuthor() );
            CollabRows.setInstant( ps, 11, s.lastEditAt() );
            ps.setObject( 12, s.id() );
        };
        return conn == null ? jdbc.update( sql, binder ) : jdbc.update( conn, sql, binder );
    }
}
