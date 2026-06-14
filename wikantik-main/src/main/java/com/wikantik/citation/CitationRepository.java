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
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC store for citation edges (V040: citations table).
 *
 * <p>The core operation is {@link #replaceForSource}: for each save it fully re-derives
 * a source page's outbound citations, preserving the {@code pinned_target_version} and
 * {@code first_seen} for surviving identities (sticky version pin).
 */
public final class CitationRepository {

    private static final Logger LOG = LogManager.getLogger( CitationRepository.class );

    private final DataSource dataSource;

    public CitationRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /**
     * Re-derive a source page's citations: keep surviving identities (preserving id, first_seen,
     * pinned_target_version), update their claim/status/last_checked, insert new identities, and
     * delete identities no longer present. Single transaction.
     */
    public void replaceForSource( final String sourceCanonicalId, final List< CitationRow > rows ) {
        try ( Connection conn = dataSource.getConnection() ) {
            final boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit( false );
            try {
                // Step 2: SELECT existing rows by source -> build identity map
                final Map< String, CitationRow > existing = loadExisting( conn, sourceCanonicalId );
                final Set< String > toDelete = new HashSet<>( existing.keySet() );

                // Steps 3 & 4: update surviving identities, insert new ones
                for ( final CitationRow incoming : rows ) {
                    // Build identity using same formula as CitationRow.identity()
                    final String identity = sourceCanonicalId + " " + incoming.targetCanonicalId() + " "
                            + incoming.targetHeadingPath() + " " + incoming.spanHash() + " " + incoming.ordinal();
                    if ( existing.containsKey( identity ) ) {
                        // Surviving identity: update claim/status/last_checked; preserve pin + first_seen
                        final CitationRow ex = existing.get( identity );
                        updateSurviving( conn, ex.id(), incoming.claimText(), incoming.status() );
                        toDelete.remove( identity );
                    } else {
                        // New identity: INSERT
                        insertNew( conn, sourceCanonicalId, incoming );
                    }
                }

                // Step 5: delete vanished identities
                for ( final String gone : toDelete ) {
                    final CitationRow ex = existing.get( gone );
                    deleteById( conn, ex.id() );
                }

                conn.commit();
            } catch ( final SQLException e ) {
                try {
                    conn.rollback();
                } catch ( final SQLException rollbackEx ) {
                    LOG.warn( "rollback failed after citation replaceForSource error for {}: {}",
                            sourceCanonicalId, rollbackEx.getMessage(), rollbackEx );
                }
                LOG.warn( "citation replaceForSource failed for {}: {}", sourceCanonicalId, e.getMessage(), e );
                throw new IllegalStateException( "citation replaceForSource failed for " + sourceCanonicalId, e );
            } finally {
                conn.setAutoCommit( prevAutoCommit );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "citation replaceForSource failed for {}: {}", sourceCanonicalId, e.getMessage(), e );
            throw new IllegalStateException( "citation replaceForSource failed for " + sourceCanonicalId, e );
        }
    }

    public List< CitationRow > findBySource( final String sourceCanonicalId ) {
        return findBy( "source_canonical_id", sourceCanonicalId );
    }

    public List< CitationRow > findByTarget( final String targetCanonicalId ) {
        return findBy( "target_canonical_id", targetCanonicalId );
    }

    public List< CitationRow > findByStatus( final CitationStatus status ) {
        return findBy( "status", status.wire() );
    }

    public List< CitationRow > findAll() {
        final String sql = "SELECT id, source_canonical_id, target_canonical_id, target_heading_path, "
                         + "span_text, span_hash, claim_text, ordinal, pinned_target_version, status, "
                         + "first_seen, last_checked, last_status_change FROM citations ORDER BY id";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            final List< CitationRow > out = new ArrayList<>();
            while ( rs.next() ) { out.add( rowFrom( rs ) ); }
            return out;
        } catch ( final SQLException e ) {
            LOG.warn( "citation findAll failed: {}", e.getMessage(), e );
            throw new IllegalStateException( "citation findAll failed", e );
        }
    }

    public void updateStatus( final long id, final CitationStatus status, final Instant checkedAt ) {
        final String sql = "UPDATE citations SET status = ?, last_checked = ?, "
                         + "last_status_change = ? WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status.wire() );
            ps.setTimestamp( 2, Timestamp.from( checkedAt ) );
            ps.setTimestamp( 3, Timestamp.from( checkedAt ) );
            ps.setLong( 4, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "citation updateStatus failed for id={}: {}", id, e.getMessage(), e );
            throw new IllegalStateException( "citation updateStatus failed", e );
        }
    }

    public void touchChecked( final long id, final Instant checkedAt ) {
        final String sql = "UPDATE citations SET last_checked = ? WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setTimestamp( 1, Timestamp.from( checkedAt ) );
            ps.setLong( 2, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "citation touchChecked failed for id={}: {}", id, e.getMessage(), e );
            throw new IllegalStateException( "citation touchChecked failed", e );
        }
    }

    public Map< CitationStatus, Integer > countsByStatus() {
        final String sql = "SELECT status, COUNT(*) AS cnt FROM citations GROUP BY status";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            final Map< CitationStatus, Integer > out = new EnumMap<>( CitationStatus.class );
            while ( rs.next() ) {
                out.put( CitationStatus.fromWire( rs.getString( "status" ) ), rs.getInt( "cnt" ) );
            }
            return out;
        } catch ( final SQLException e ) {
            LOG.warn( "citation countsByStatus failed: {}", e.getMessage(), e );
            throw new IllegalStateException( "citation countsByStatus failed", e );
        }
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private Map< String, CitationRow > loadExisting( final Connection conn,
                                                      final String sourceCanonicalId ) throws SQLException {
        final String sql = "SELECT id, source_canonical_id, target_canonical_id, target_heading_path, "
                         + "span_text, span_hash, claim_text, ordinal, pinned_target_version, status, "
                         + "first_seen, last_checked, last_status_change "
                         + "FROM citations WHERE source_canonical_id = ?";
        final Map< String, CitationRow > map = new HashMap<>();
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, sourceCanonicalId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final CitationRow row = rowFrom( rs );
                    map.put( row.identity(), row );
                }
            }
        }
        return map;
    }

    private void updateSurviving( final Connection conn, final long id,
                                   final String claimText, final CitationStatus status ) throws SQLException {
        final String sql = "UPDATE citations SET claim_text = ?, status = ?, last_checked = NOW(), "
                         + "last_status_change = CASE WHEN status <> ? THEN NOW() ELSE last_status_change END "
                         + "WHERE id = ?";
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, claimText );
            ps.setString( 2, status.wire() );
            ps.setString( 3, status.wire() );
            ps.setLong( 4, id );
            ps.executeUpdate();
        }
    }

    private void insertNew( final Connection conn, final String sourceCanonicalId,
                             final CitationRow r ) throws SQLException {
        final String sql = "INSERT INTO citations "
                         + "(source_canonical_id, target_canonical_id, target_heading_path, span_text, "
                         + " span_hash, claim_text, ordinal, pinned_target_version, status, "
                         + " first_seen, last_checked, last_status_change) "
                         + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())";
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, sourceCanonicalId );
            ps.setString( 2, r.targetCanonicalId() );
            ps.setString( 3, r.targetHeadingPath() );
            ps.setString( 4, r.spanText() );
            ps.setString( 5, r.spanHash() );
            ps.setString( 6, r.claimText() );
            ps.setInt( 7, r.ordinal() );
            ps.setObject( 8, r.pinnedTargetVersion() );  // nullable Integer
            ps.setString( 9, r.status().wire() );
            ps.executeUpdate();
        }
    }

    private void deleteById( final Connection conn, final long id ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( "DELETE FROM citations WHERE id = ?" ) ) {
            ps.setLong( 1, id );
            ps.executeUpdate();
        }
    }

    private List< CitationRow > findBy( final String column, final String value ) {
        final String sql = "SELECT id, source_canonical_id, target_canonical_id, target_heading_path, "
                         + "span_text, span_hash, claim_text, ordinal, pinned_target_version, status, "
                         + "first_seen, last_checked, last_status_change "
                         + "FROM citations WHERE " + column + " = ? ORDER BY id";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, value );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< CitationRow > out = new ArrayList<>();
                while ( rs.next() ) { out.add( rowFrom( rs ) ); }
                return out;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "citation findBy {}={} failed: {}", column, value, e.getMessage(), e );
            throw new IllegalStateException( "citation findBy failed", e );
        }
    }

    private static CitationRow rowFrom( final ResultSet rs ) throws SQLException {
        final Timestamp firstSeen = rs.getTimestamp( "first_seen" );
        final Timestamp lastChecked = rs.getTimestamp( "last_checked" );
        final Timestamp lastStatusChange = rs.getTimestamp( "last_status_change" );
        return new CitationRow(
                rs.getLong( "id" ),
                rs.getString( "source_canonical_id" ),
                rs.getString( "target_canonical_id" ),
                rs.getString( "target_heading_path" ),
                rs.getString( "span_text" ),
                rs.getString( "span_hash" ),
                rs.getString( "claim_text" ),
                rs.getInt( "ordinal" ),
                rs.getObject( "pinned_target_version", Integer.class ),
                CitationStatus.fromWire( rs.getString( "status" ) ),
                firstSeen == null ? null : firstSeen.toInstant(),
                lastChecked == null ? null : lastChecked.toInstant(),
                lastStatusChange == null ? null : lastStatusChange.toInstant() );
    }
}
