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
package com.wikantik.drift;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for drift sweep snapshots (V038: drift_sweeps + drift_snapshot_counts).
 * Inserts are transactional: a failed sweep persists nothing.
 */
public final class DriftSnapshotRepository {

    private static final Logger LOG = LogManager.getLogger( DriftSnapshotRepository.class );

    private final Jdbc jdbc;

    public DriftSnapshotRepository( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    /** Persists one sweep + its counts atomically; returns the new sweep id. */
    public long insertSweep( final Instant sweptAt, final int pagesScanned, final long durationMs,
                             final String triggeredBy, final boolean shaclChecked,
                             final List< DriftCount > counts ) {
        try {
            return jdbc.inTransaction( conn -> {
                final long sweepId;
                // Not migrated to Jdbc.update(conn, ...): needs Statement.RETURN_GENERATED_KEYS
                // plus a getGeneratedKeys() read on the same statement — a different shape than
                // the plain executeUpdate() the update() primitive wraps. Same documented gap as
                // KgProposalRepository.insertProposal.
                try ( PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO drift_sweeps ( swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked ) "
                      + "VALUES ( ?, ?, ?, ?, ? )", Statement.RETURN_GENERATED_KEYS ) ) {
                    ps.setTimestamp( 1, Timestamp.from( sweptAt ) );
                    ps.setInt( 2, pagesScanned );
                    ps.setLong( 3, durationMs );
                    ps.setString( 4, triggeredBy );
                    ps.setBoolean( 5, shaclChecked );
                    ps.executeUpdate();
                    try ( ResultSet keys = ps.getGeneratedKeys() ) {
                        if ( !keys.next() ) {
                            throw new SQLException( "no generated key for drift_sweeps insert" );
                        }
                        sweepId = keys.getLong( 1 );
                    }
                }
                final List< SqlBinder > countBinders = counts.stream()
                        .< SqlBinder >map( c -> ps -> {
                            ps.setLong( 1, sweepId );
                            ps.setString( 2, c.family() );
                            ps.setString( 3, c.code() );
                            ps.setString( 4, c.severity() );
                            ps.setInt( 5, c.count() );
                        } )
                        .toList();
                jdbc.batch( conn, "INSERT INTO drift_snapshot_counts ( sweep_id, family, code, severity, \"count\" ) "
                      + "VALUES ( ?, ?, ?, ?, ? )", countBinders );
                return sweepId;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to persist drift sweep (triggeredBy={}): {}", triggeredBy, e.getMessage(), e );
            throw new IllegalStateException( "drift sweep persistence failed", e );
        }
    }

    /** The most recent sweep, with counts. */
    public Optional< DriftSweepRecord > latest() {
        return querySingle( "latest", "SELECT id, swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked "
                          + "FROM drift_sweeps ORDER BY id DESC LIMIT 1", SqlBinder.NONE );
    }

    /** The sweep immediately before {@code sweepId} (for deltas). */
    public Optional< DriftSweepRecord > previousBefore( final long sweepId ) {
        return querySingle( "previousBefore sweepId=" + sweepId, "SELECT id, swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked "
                          + "FROM drift_sweeps WHERE id < ? ORDER BY id DESC LIMIT 1",
                ps -> ps.setLong( 1, sweepId ) );
    }

    /** All sweeps in the last {@code days}, oldest first, with counts. */
    public List< DriftSweepRecord > trend( final int days ) {
        final Timestamp cutoff = Timestamp.from( Instant.now().minus( days, ChronoUnit.DAYS ) );
        final String sql = "SELECT id, swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked "
                         + "FROM drift_sweeps WHERE swept_at >= ? ORDER BY id ASC";
        try {
            return jdbc.query( sql, ps -> ps.setTimestamp( 1, cutoff ), this::rowToRecord );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read drift trend ({} days): {}", days, e.getMessage(), e );
            throw new IllegalStateException( "drift trend query failed", e );
        }
    }

    private Optional< DriftSweepRecord > querySingle( final String description, final String sql, final SqlBinder binder ) {
        try {
            return jdbc.queryOne( sql, binder, this::rowToRecord );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read drift sweep ({}): {}", description, e.getMessage(), e );
            throw new IllegalStateException( "drift sweep query failed", e );
        }
    }

    private DriftSweepRecord rowToRecord( final ResultSet rs ) throws SQLException {
        final long id = rs.getLong( "id" );
        return new DriftSweepRecord(
                id,
                rs.getTimestamp( "swept_at" ).toInstant(),
                rs.getInt( "pages_scanned" ),
                rs.getLong( "duration_ms" ),
                rs.getString( "triggered_by" ),
                rs.getBoolean( "shacl_checked" ),
                countsFor( id ) );
    }

    private List< DriftCount > countsFor( final long sweepId ) throws SQLException {
        return jdbc.query(
                "SELECT family, code, severity, \"count\" FROM drift_snapshot_counts "
              + "WHERE sweep_id = ? ORDER BY family, code, severity",
                ps -> ps.setLong( 1, sweepId ),
                rs -> new DriftCount( rs.getString( "family" ), rs.getString( "code" ),
                        rs.getString( "severity" ), rs.getInt( "count" ) ) );
    }
}
