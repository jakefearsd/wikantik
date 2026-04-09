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
package com.wikantik.knowledge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for Hub membership proposals and Hub centroid embeddings.
 */
public class HubProposalRepository {

    private static final Logger LOG = LogManager.getLogger( HubProposalRepository.class );

    public record HubProposal( int id, String hubName, String pageName,
                                double rawSimilarity, double percentileScore,
                                String status, String reason,
                                String reviewedBy, Instant reviewedAt,
                                Instant created ) {}

    private final DataSource dataSource;

    public HubProposalRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public void insertProposal( final String hubName, final String pageName,
                                 final double rawSimilarity, final double percentileScore ) {
        final String sql = "INSERT INTO hub_proposals (hub_name, page_name, raw_similarity, percentile_score) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (hub_name, page_name) DO NOTHING";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, pageName );
            ps.setDouble( 3, rawSimilarity );
            ps.setDouble( 4, percentileScore );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert hub proposal [{} -> {}]: {}", hubName, pageName, e.getMessage() );
        }
    }

    public List< HubProposal > listProposals( final String status, final String hubName,
                                                final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder(
            "SELECT id, hub_name, page_name, raw_similarity, percentile_score, status, reason, "
            + "reviewed_by, reviewed_at, created FROM hub_proposals WHERE status = ?" );
        if ( hubName != null ) {
            sql.append( " AND hub_name = ?" );
        }
        sql.append( " ORDER BY percentile_score DESC LIMIT ? OFFSET ?" );

        final List< HubProposal > result = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            int idx = 1;
            ps.setString( idx++, status );
            if ( hubName != null ) {
                ps.setString( idx++, hubName );
            }
            ps.setInt( idx++, limit );
            ps.setInt( idx, offset );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.add( mapRow( rs ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list hub proposals: {}", e.getMessage() );
        }
        return result;
    }

    public List< HubProposal > listProposalsAboveThreshold( final double minPercentile ) {
        final String sql = "SELECT id, hub_name, page_name, raw_similarity, percentile_score, status, reason, "
            + "reviewed_by, reviewed_at, created FROM hub_proposals "
            + "WHERE status = 'pending' AND percentile_score >= ? ORDER BY percentile_score DESC";
        final List< HubProposal > result = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setDouble( 1, minPercentile );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.add( mapRow( rs ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list proposals above threshold: {}", e.getMessage() );
        }
        return result;
    }

    public void updateStatus( final int id, final String status,
                               final String reviewedBy, final String reason ) {
        final String sql = "UPDATE hub_proposals SET status = ?, reviewed_by = ?, reason = ?, "
            + "reviewed_at = NOW() WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setString( 3, reason );
            ps.setInt( 4, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to update hub proposal {}: {}", id, e.getMessage() );
        }
    }

    public void bulkUpdateStatus( final List< Integer > ids, final String status,
                                    final String reviewedBy, final String reason ) {
        if ( ids.isEmpty() ) return;
        final String placeholders = String.join( ",", ids.stream().map( i -> "?" ).toList() );
        final String sql = "UPDATE hub_proposals SET status = ?, reviewed_by = ?, reason = ?, "
            + "reviewed_at = NOW() WHERE id IN (" + placeholders + ")";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setString( 3, reason );
            int idx = 4;
            for ( final int id : ids ) {
                ps.setInt( idx++, id );
            }
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to bulk update hub proposals: {}", e.getMessage() );
        }
    }

    public boolean isRejected( final String hubName, final String pageName ) {
        final String sql = "SELECT 1 FROM hub_proposals WHERE hub_name = ? AND page_name = ? AND status = 'rejected'";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, pageName );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to check rejection for [{} -> {}]: {}", hubName, pageName, e.getMessage() );
            return false;
        }
    }

    public boolean exists( final String hubName, final String pageName ) {
        final String sql = "SELECT 1 FROM hub_proposals WHERE hub_name = ? AND page_name = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, pageName );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            return false;
        }
    }

    public int countByStatus( final String status ) {
        final String sql = "SELECT COUNT(*) FROM hub_proposals WHERE status = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getInt( 1 ) : 0;
            }
        } catch ( final SQLException e ) {
            return 0;
        }
    }

    public void saveCentroid( final String hubName, final float[] centroid,
                               final int modelVersion, final int memberCount ) {
        final String sql = "INSERT INTO hub_centroids (hub_name, centroid, model_version, member_count) "
            + "VALUES (?, ?::vector, ?, ?) ON CONFLICT (hub_name) DO UPDATE SET "
            + "centroid = EXCLUDED.centroid, model_version = EXCLUDED.model_version, "
            + "member_count = EXCLUDED.member_count, created = NOW()";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, vectorToString( centroid ) );
            ps.setInt( 3, modelVersion );
            ps.setInt( 4, memberCount );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to save centroid for hub '{}': {}", hubName, e.getMessage() );
        }
    }

    public float[] loadCentroid( final String hubName ) {
        final String sql = "SELECT centroid::text FROM hub_centroids WHERE hub_name = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            try ( final ResultSet rs = ps.executeQuery() ) {
                if ( rs.next() ) {
                    return parseVector( rs.getString( 1 ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to load centroid for hub '{}': {}", hubName, e.getMessage() );
        }
        return null;
    }

    public void clearCentroids() {
        try ( final Connection conn = dataSource.getConnection();
              final Statement st = conn.createStatement() ) {
            st.execute( "DELETE FROM hub_centroids" );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to clear hub centroids: {}", e.getMessage() );
        }
    }

    private static HubProposal mapRow( final ResultSet rs ) throws SQLException {
        return new HubProposal(
            rs.getInt( "id" ),
            rs.getString( "hub_name" ),
            rs.getString( "page_name" ),
            rs.getDouble( "raw_similarity" ),
            rs.getDouble( "percentile_score" ),
            rs.getString( "status" ),
            rs.getString( "reason" ),
            rs.getString( "reviewed_by" ),
            rs.getTimestamp( "reviewed_at" ) != null ? rs.getTimestamp( "reviewed_at" ).toInstant() : null,
            rs.getTimestamp( "created" ).toInstant()
        );
    }

    private static String vectorToString( final float[] vec ) {
        final StringBuilder sb = new StringBuilder( "[" );
        for ( int i = 0; i < vec.length; i++ ) {
            if ( i > 0 ) sb.append( ',' );
            sb.append( vec[ i ] );
        }
        sb.append( ']' );
        return sb.toString();
    }

    private static float[] parseVector( final String text ) {
        final String inner = text.substring( 1, text.length() - 1 ); // strip [ and ]
        final String[] parts = inner.split( "," );
        final float[] vec = new float[ parts.length ];
        for ( int i = 0; i < parts.length; i++ ) {
            vec[ i ] = Float.parseFloat( parts[ i ].trim() );
        }
        return vec;
    }
}
