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

import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL {@link CollabStore} over the V066 tables (plus V067's {@code collab_snapshot.contributors}), through
 * {@link Jdbc}. Every multi-statement write runs in one
 * {@link Jdbc#inTransaction}; each locks the session row first (`SELECT ... FOR UPDATE`), so concurrent writers to
 * one session queue up and the loser sees the winner's head, fails the contiguity check and throws
 * {@link IllegalStateException} with nothing stored (the ordering arbiter of docs/MultiEdit.MD section 12.4). The
 * final {@code UPDATE ... WHERE head_version = ?} re-asserts the head as a belt-and-braces guard.
 *
 * <p>JSON: {@code changes}, {@code local_changes}, {@code base_meta}, {@code meta} and {@code value} are JSONB written
 * with Gson (nulls kept) and read back into plain {@code Map}/{@code List}/{@code String}/{@code Boolean}/{@code Number}
 * values. Numbers come back as {@link Long} when integral and {@link Double} otherwise, whatever Java type they were
 * written as, so a meta value that round-trips through this store is equal to its JSON normalisation, not necessarily
 * to the object that was put in. A null meta-op value is stored as SQL NULL and read back as null.</p>
 */
public final class JdbcCollabStore implements CollabStore {

    private static final String PG_UNIQUE_VIOLATION = "23505";
    private static final String LOCK_SQL = "SELECT head_version, meta_seq FROM collab_session WHERE id = ? FOR UPDATE";

    private final Jdbc jdbc;
    private final CollabWrites writes;
    private final ContributorLedger ledger;

    public JdbcCollabStore( final Jdbc jdbc ) {
        this.jdbc = java.util.Objects.requireNonNull( jdbc, "jdbc" );
        this.writes = new CollabWrites( jdbc );
        this.ledger = new ContributorLedger( jdbc );
    }

    // ---- sessions ------------------------------------------------------------------------------------------

    @Override
    public void createSession( final SessionRecord s ) {
        try {
            jdbc.update( "INSERT INTO collab_session ( id, page_name, status, base_page_version, base_body, base_meta,"
                + " base_content_hash, head_version, meta_seq, has_unpublished, pending_publish_version,"
                + " pending_publish_base_version, pending_publish_author, created_by, created_at, last_edit_at )"
                + " VALUES ( ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )", ps -> {
                    ps.setObject( 1, s.id() );
                    ps.setString( 2, s.pageName() );
                    ps.setString( 3, s.status() );
                    ps.setInt( 4, s.basePageVersion() );
                    ps.setString( 5, s.baseBody() );
                    ps.setString( 6, CollabRows.GSON.toJson( s.baseMeta() ) );
                    ps.setString( 7, s.baseContentHash() );
                    ps.setInt( 8, s.headVersion() );
                    ps.setLong( 9, s.metaSeq() );
                    ps.setBoolean( 10, s.hasUnpublished() );
                    CollabRows.setInteger( ps, 11, s.pendingPublishVersion() );
                    CollabRows.setInteger( ps, 12, s.pendingPublishBaseVersion() );
                    ps.setString( 13, s.pendingPublishAuthor() );
                    ps.setString( 14, s.createdBy() );
                    ps.setTimestamp( 15, Timestamp.from( s.createdAt() ) );
                    CollabRows.setInstant( ps, 16, s.lastEditAt() );
                } );
        } catch( final SQLException e ) {
            throw translate( e, "createSession " + s.pageName() );
        }
    }

    @Override
    public Optional< SessionRecord > session( final UUID id ) {
        return readSession( "SELECT " + CollabRows.SESSION_COLUMNS + " FROM collab_session WHERE id = ?", ps -> ps.setObject( 1, id ) );
    }

    @Override
    public Optional< SessionRecord > sessionForPage( final String pageName ) {
        return readSession( "SELECT " + CollabRows.SESSION_COLUMNS + " FROM collab_session WHERE page_name = ?",
            ps -> ps.setString( 1, pageName ) );
    }

    private Optional< SessionRecord > readSession( final String sql, final SqlBinder binder ) {
        try {
            return jdbc.queryOne( sql, binder, CollabRows::mapSession );
        } catch( final SQLException e ) {
            throw translate( e, "read session" );
        }
    }

    @Override
    public void updateSession( final SessionRecord s ) {
        try {
            if( writes.writeSessionFields( null, s ) == 0 ) {
                throw noSession( s.id() );
            }
        } catch( final SQLException e ) {
            throw translate( e, "updateSession " + s.id() );
        }
    }

    // ---- update log ----------------------------------------------------------------------------------------

    @Override
    public void appendUpdates( final UUID id, final List< StoredUpdate > updates ) {
        try {
            jdbc.inTransaction( conn -> {
                final int head = lockHead( conn, id );
                writes.appendUpdatesTx( conn, id, head, updates );
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "appendUpdates " + id );
        }
    }

    @Override
    public List< StoredUpdate > updatesSince( final UUID id, final int sinceVersion ) {
        try {
            return jdbc.query( "SELECT version, kind, client_id, author_key, changes::text FROM collab_update"
                + " WHERE session_id = ? AND version > ? ORDER BY version", ps -> {
                    ps.setObject( 1, id );
                    ps.setInt( 2, sinceVersion );
                }, CollabRows::mapUpdate );
        } catch( final SQLException e ) {
            throw translate( e, "updatesSince " + id );
        }
    }

    // ---- meta ops ------------------------------------------------------------------------------------------

    @Override
    public void appendMetaOps( final UUID id, final List< StoredMetaOp > ops ) {
        try {
            jdbc.inTransaction( conn -> {
                final long seq = lockMetaSeq( conn, id );
                writes.appendMetaOpsTx( conn, id, seq, ops );
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "appendMetaOps " + id );
        }
    }

    @Override
    public void appendBatch( final UUID id, final List< StoredUpdate > updates, final List< StoredMetaOp > ops ) {
        try {
            jdbc.inTransaction( conn -> {
                final long[] counters = lockCounters( conn, id );
                writes.appendUpdatesTx( conn, id, (int) counters[ 0 ], updates );
                writes.appendMetaOpsTx( conn, id, counters[ 1 ], ops );
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "appendBatch " + id );
        }
    }

    @Override
    public void appendWithSnapshot( final UUID id, final List< StoredUpdate > updates, final List< StoredMetaOp > ops,
                                    final Snapshot snapshot ) {
        try {
            jdbc.inTransaction( conn -> {
                final long[] counters = lockCounters( conn, id );
                if( snapshot.version() != counters[ 0 ] + updates.size()
                        || snapshot.metaSeq() != counters[ 1 ] + ops.size() ) {
                    throw new IllegalStateException( "snapshot at " + snapshot.version() + "/" + snapshot.metaSeq()
                        + " is not the new head " + ( counters[ 0 ] + updates.size() ) + "/" + ( counters[ 1 ] + ops.size() ) );
                }
                writes.appendUpdatesTx( conn, id, (int) counters[ 0 ], updates );
                writes.appendMetaOpsTx( conn, id, counters[ 1 ], ops );
                writes.writeSnapshotTx( conn, id, snapshot );
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "appendWithSnapshot " + id );
        }
    }

    @Override
    public List< StoredMetaOp > metaOpsSince( final UUID id, final long sinceSeq ) {
        try {
            return jdbc.query( "SELECT seq, author_key, field, value::text FROM collab_meta_op"
                + " WHERE session_id = ? AND seq > ? ORDER BY seq", ps -> {
                    ps.setObject( 1, id );
                    ps.setLong( 2, sinceSeq );
                }, rs -> new StoredMetaOp( rs.getLong( 1 ), rs.getString( 2 ), rs.getString( 3 ),
                    rs.getString( 4 ) == null ? null : CollabRows.GSON.fromJson( rs.getString( 4 ), Object.class ) ) );
        } catch( final SQLException e ) {
            throw translate( e, "metaOpsSince " + id );
        }
    }

    // ---- base change ---------------------------------------------------------------------------------------

    @Override
    public void commitBaseChange( final UUID id, final List< StoredUpdate > serverUpdates, final Snapshot snapshot,
                                  final SessionRecord updated ) {
        checkBaseChangeArguments( id, serverUpdates, snapshot, updated );
        try {
            jdbc.inTransaction( conn -> {
                final int head = lockHead( conn, id );
                final int newHead = head + serverUpdates.size();
                if( snapshot.version() != newHead ) {
                    throw new IllegalStateException( "snapshot version " + snapshot.version() + " is not the new head "
                        + newHead );
                }
                writes.appendUpdatesTx( conn, id, head, serverUpdates );
                writes.writeSnapshotTx( conn, id, snapshot );
                if( writes.writeSessionFields( conn, updated ) == 0 ) {
                    throw noSession( id );
                }
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "commitBaseChange " + id );
        }
    }

    private static void checkBaseChangeArguments( final UUID id, final List< StoredUpdate > serverUpdates,
                                                  final Snapshot snapshot, final SessionRecord updated ) {
        if( !updated.id().equals( id ) ) {
            throw new IllegalArgumentException( "session record " + updated.id() + " is not collab session " + id );
        }
        for( final StoredUpdate u : serverUpdates ) {
            if( !StoredUpdate.SERVER.equals( u.kind() ) ) {
                throw new IllegalArgumentException( "commitBaseChange appends server updates only, got " + u );
            }
        }
        if( snapshot.basePageVersion() != updated.basePageVersion() ) {
            throw new IllegalArgumentException( "snapshot base page version " + snapshot.basePageVersion()
                + " is not the session's new base " + updated.basePageVersion() );
        }
    }

    // ---- snapshots -----------------------------------------------------------------------------------------

    @Override
    public void writeSnapshot( final UUID id, final Snapshot s ) {
        try {
            jdbc.inTransaction( conn -> {
                lockHead( conn, id );
                writes.writeSnapshotTx( conn, id, s );
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "writeSnapshot " + id );
        }
    }

    @Override
    public Optional< Snapshot > latestSnapshot( final UUID id ) {
        try {
            return jdbc.queryOne( "SELECT " + CollabRows.SNAPSHOT_COLUMNS
                + " FROM collab_snapshot WHERE session_id = ? ORDER BY version DESC LIMIT 1",
                ps -> ps.setObject( 1, id ), CollabRows::mapSnapshot );
        } catch( final SQLException e ) {
            throw translate( e, "latestSnapshot " + id );
        }
    }

    @Override
    public Optional< Snapshot > snapshotAtOrBelow( final UUID id, final int version ) {
        try {
            return jdbc.queryOne( "SELECT " + CollabRows.SNAPSHOT_COLUMNS
                + " FROM collab_snapshot WHERE session_id = ? AND version <= ? ORDER BY version DESC LIMIT 1", ps -> {
                    ps.setObject( 1, id );
                    ps.setInt( 2, version );
                }, CollabRows::mapSnapshot );
        } catch( final SQLException e ) {
            throw translate( e, "snapshotAtOrBelow " + id + " " + version );
        }
    }

    // ---- truncation and delete -----------------------------------------------------------------------------

    @Override
    public void truncateUpdatesBelow( final UUID id, final int cut ) {
        try {
            jdbc.inTransaction( conn -> {
                lockHead( conn, id );
                final Optional< Integer > floor = jdbc.queryOne( conn, "SELECT coalesce( max( version ), -1 ) FROM collab_snapshot"
                    + " WHERE session_id = ? AND version <= ?", ps -> {
                        ps.setObject( 1, id );
                        ps.setInt( 2, cut );
                    }, rs -> rs.getInt( 1 ) );
                if( floor.orElse( -1 ) >= 0 ) {
                    final int s = floor.get();
                    jdbc.update( conn, "DELETE FROM collab_update WHERE session_id = ? AND version <= ?", ps -> {
                        ps.setObject( 1, id );
                        ps.setInt( 2, s );
                    } );
                    jdbc.update( conn, "DELETE FROM collab_snapshot WHERE session_id = ? AND version < ?", ps -> {
                        ps.setObject( 1, id );
                        ps.setInt( 2, s );
                    } );
                }
                return null;
            } );
        } catch( final SQLException e ) {
            throw translate( e, "truncateUpdatesBelow " + id );
        }
    }

    @Override
    public void deleteSession( final UUID id ) {
        try {
            if( jdbc.update( "DELETE FROM collab_session WHERE id = ?", ps -> ps.setObject( 1, id ) ) == 0 ) {
                throw noSession( id );
            }
        } catch( final SQLException e ) {
            throw translate( e, "deleteSession " + id );
        }
    }

    @Override
    public List< SessionRecord > sessions() {
        try {
            // COLLATE "C": the tie-break is code-point order, as String.compareTo in the in-memory twin
            return jdbc.query( "SELECT " + CollabRows.SESSION_COLUMNS + " FROM collab_session"
                + " ORDER BY coalesce( last_edit_at, created_at ), page_name COLLATE \"C\"", ps -> { },
                CollabRows::mapSession );
        } catch( final SQLException e ) {
            throw translate( e, "sessions" );
        }
    }

    // ---- page-version contributor ledger -------------------------------------------------------------------

    @Override
    public void recordContributors( final String canonicalId, final int pageVersion, final Set< String > authorKeys,
                                    final boolean created ) {
        try {
            ledger.record( canonicalId, pageVersion, authorKeys, created );
        } catch( final SQLException e ) {
            throw translate( e, "recordContributors " + canonicalId + " v" + pageVersion );
        }
    }

    @Override
    public Map< Integer, List< String > > contributorsByVersion( final String canonicalId ) {
        try {
            return ledger.byVersion( canonicalId );
        } catch( final SQLException e ) {
            throw translate( e, "contributorsByVersion " + canonicalId );
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private int lockHead( final Connection conn, final UUID id ) throws SQLException {
        return jdbc.queryOne( conn, LOCK_SQL, ps -> ps.setObject( 1, id ), rs -> rs.getInt( 1 ) )
            .orElseThrow( () -> noSession( id ) );
    }

    private long lockMetaSeq( final Connection conn, final UUID id ) throws SQLException {
        return jdbc.queryOne( conn, LOCK_SQL, ps -> ps.setObject( 1, id ), rs -> rs.getLong( 2 ) )
            .orElseThrow( () -> noSession( id ) );
    }

    /** The head version and meta sequence, read under the session row lock. */
    private long[] lockCounters( final Connection conn, final UUID id ) throws SQLException {
        return jdbc.queryOne( conn, LOCK_SQL, ps -> ps.setObject( 1, id ),
            rs -> new long[] { rs.getInt( 1 ), rs.getLong( 2 ) } ).orElseThrow( () -> noSession( id ) );
    }

    private static IllegalArgumentException noSession( final UUID id ) {
        return new IllegalArgumentException( "no collab session " + id );
    }

    /** A unique violation (page name or session id taken) is the contract's IllegalStateException; the rest is storage. */
    private static IllegalStateException translate( final SQLException e, final String what ) {
        if( PG_UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
            return new IllegalStateException( "collab store refused " + what + ": already exists (" + e.getMessage() + ")", e );
        }
        return new IllegalStateException( "collab store failure in " + what, e );
    }
}
