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
package com.wikantik.connectors.state;

import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.runtime.RunRecorder;
import com.wikantik.jdbc.Jdbc;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/** PostgreSQL JDBC store for per-run connector sync history. */
public final class JdbcSyncRunStore implements RunRecorder {

    private final Jdbc jdbc;

    public JdbcSyncRunStore( final DataSource ds ) {
        this.jdbc = new Jdbc( ds );
    }

    /**
     * Start a new sync run, returning its runId. Inserts with status 'running'
     * and prunes all but the newest 100 rows per connector.
     */
    @Override
    public long start( final String connectorId, final String trigger ) {
        try {
            final long id = jdbc.insertReturningKey(
                "INSERT INTO connector_sync_run (connector_id, trigger_kind) VALUES (?,?)",
                ps -> { ps.setString( 1, connectorId ); ps.setString( 2, trigger ); },
                rs -> rs.getLong( 1 ) ).orElseThrow();
            jdbc.update(
                "DELETE FROM connector_sync_run WHERE connector_id=? AND run_id NOT IN ("
                + " SELECT run_id FROM connector_sync_run WHERE connector_id=? ORDER BY run_id DESC LIMIT 100)",
                ps -> { ps.setString( 1, connectorId ); ps.setString( 2, connectorId ); } );
            return id;
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_sync_run start failed for '" + connectorId + "': " + e.getMessage(), e );
        }
    }

    /**
     * Mark a sync run as successfully completed with the given report.
     */
    @Override
    public void finish( final long runId, final SyncReport report ) {
        try {
            jdbc.update(
                "UPDATE connector_sync_run SET finished=now(), status='ok',"
                + " created=?, updated=?, unchanged=?, deleted=?, failed=? WHERE run_id=?",
                ps -> {
                    ps.setInt( 1, report.created() );
                    ps.setInt( 2, report.updated() );
                    ps.setInt( 3, report.unchanged() );
                    ps.setInt( 4, report.deleted() );
                    ps.setInt( 5, report.failed() );
                    ps.setLong( 6, runId );
                } );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_sync_run finish failed for run_id=" + runId + ": " + e.getMessage(), e );
        }
    }

    /**
     * Mark a sync run as failed with the given error message.
     */
    @Override
    public void fail( final long runId, final String error ) {
        try {
            jdbc.update( "UPDATE connector_sync_run SET finished=now(), status='failed', error=? WHERE run_id=?",
                ps -> { ps.setString( 1, error ); ps.setLong( 2, runId ); } );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_sync_run fail failed for run_id=" + runId + ": " + e.getMessage(), e );
        }
    }

    /**
     * Delete every sync-run history row for {@code connectorId}. Called on connector delete so a
     * later same-id recreation starts with a clean run history instead of inheriting the old
     * connector's (misleading) runs.
     */
    public void purgeRuns( final String connectorId ) {
        try {
            jdbc.update( "DELETE FROM connector_sync_run WHERE connector_id=?", ps -> ps.setString( 1, connectorId ) );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_sync_run purge failed for '" + connectorId + "': " + e.getMessage(), e );
        }
    }

    /**
     * List recent sync runs for a connector, newest first, limited to the given count.
     */
    public List< SyncRunRow > list( final String connectorId, final int limit ) {
        try {
            return jdbc.query(
                "SELECT run_id, connector_id, trigger_kind, started, finished, status,"
                + " created, updated, unchanged, deleted, failed, error"
                + " FROM connector_sync_run WHERE connector_id=? ORDER BY run_id DESC LIMIT ?",
                ps -> { ps.setString( 1, connectorId ); ps.setInt( 2, limit ); },
                JdbcSyncRunStore::rowFrom );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_sync_run list failed for '" + connectorId + "': " + e.getMessage(), e );
        }
    }

    private static SyncRunRow rowFrom( final ResultSet rs ) throws SQLException {
        final Timestamp finishedTs = rs.getTimestamp( 5 );
        return new SyncRunRow(
            rs.getLong( 1 ),
            rs.getString( 2 ),
            rs.getString( 3 ),
            rs.getTimestamp( 4 ).toInstant(),
            finishedTs != null ? finishedTs.toInstant() : null,
            rs.getString( 6 ),
            rs.getInt( 7 ),
            rs.getInt( 8 ),
            rs.getInt( 9 ),
            rs.getInt( 10 ),
            rs.getInt( 11 ),
            rs.getString( 12 )
        );
    }
}
