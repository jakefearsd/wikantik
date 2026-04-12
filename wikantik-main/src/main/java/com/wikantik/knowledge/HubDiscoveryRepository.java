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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for cluster-discovery proposals.
 *
 * <p>Rows have a {@code status} of either {@code 'pending'} or {@code 'dismissed'}.
 * Accept hard-deletes the row (the resulting hub page excludes its members via
 * {@code related} edges so there is no rediscovery concern). Dismiss marks the
 * row {@code status='dismissed'} via {@link #markDismissed(int, String)} so the
 * service can exclude identical clusters from future proposals. Dismissed rows
 * can be cleared individually via {@link #deleteDismissed(int)} or in bulk via
 * {@link #deleteDismissedBulk(List)} to re-enable rediscovery of that cluster.
 */
public class HubDiscoveryRepository {

    private static final Logger LOG = LogManager.getLogger( HubDiscoveryRepository.class );
    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST = new TypeToken< List< String > >() {}.getType();

    public record HubDiscoveryProposal( int id, String suggestedName, String exemplarPage,
                                         List< String > memberPages, double coherenceScore,
                                         Instant created ) {}

    /**
     * Dismissed-proposal projection. Carries the same fields as
     * {@link HubDiscoveryProposal} plus the reviewer and review timestamp.
     */
    public record DismissedProposal( int id, String suggestedName, String exemplarPage,
                                      List< String > memberPages, double coherenceScore,
                                      Instant created, String reviewedBy, Instant reviewedAt ) {}

    private final DataSource dataSource;

    public HubDiscoveryRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public int insert( final String suggestedName, final String exemplarPage,
                        final List< String > memberPages, final double coherenceScore ) {
        final String sql = "INSERT INTO hub_discovery_proposals "
            + "(suggested_name, exemplar_page, member_pages, coherence_score) "
            + "VALUES (?, ?, ?::jsonb, ?)";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, suggestedName );
            ps.setString( 2, exemplarPage );
            ps.setString( 3, GSON.toJson( memberPages ) );
            ps.setDouble( 4, coherenceScore );
            ps.executeUpdate();
            try ( final ResultSet keys = ps.getGeneratedKeys() ) {
                if ( keys.next() ) return keys.getInt( 1 );
            }
            throw new SQLException( "No generated key returned" );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert hub discovery proposal '{}': {}", suggestedName, e.getMessage() );
            throw new RuntimeException( "Failed to insert hub discovery proposal '" + suggestedName + "'", e );
        }
    }

    public HubDiscoveryProposal findById( final int id ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created FROM hub_discovery_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapRow( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to load hub discovery proposal {}: {}", id, e.getMessage() );
            return null;
        }
    }

    public List< HubDiscoveryProposal > list( final int limit, final int offset ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created FROM hub_discovery_proposals "
            + "WHERE status = 'pending' "
            + "ORDER BY created DESC LIMIT ? OFFSET ?";
        final List< HubDiscoveryProposal > out = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, limit );
            ps.setInt( 2, offset );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( mapRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list hub discovery proposals: {}", e.getMessage() );
        }
        return out;
    }

    public int count() {
        final String sql = "SELECT COUNT(*) FROM hub_discovery_proposals WHERE status = 'pending'";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getInt( 1 ) : 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to count hub discovery proposals: {}", e.getMessage() );
            return 0;
        }
    }

    /**
     * Hard-deletes a row regardless of status. Used by the accept path — once a
     * hub page is created, the members are excluded from the pool via the
     * {@code related} edge filter, so the proposal no longer needs retention.
     * Dismissed-row management uses {@link #deleteDismissed(int)} instead.
     */
    public boolean delete( final int id ) {
        final String sql = "DELETE FROM hub_discovery_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete hub discovery proposal {}: {}", id, e.getMessage() );
            return false;
        }
    }

    // ---- Dismissed-proposal operations ----

    /**
     * Marks a pending proposal as {@code dismissed} and records the reviewer.
     * Returns {@code true} only if a pending row existed and was updated.
     */
    public boolean markDismissed( final int id, final String reviewedBy ) {
        final String sql = "UPDATE hub_discovery_proposals "
            + "SET status = 'dismissed', reviewed_by = ?, reviewed_at = NOW() "
            + "WHERE id = ? AND status = 'pending'";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, reviewedBy );
            ps.setInt( 2, id );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to mark hub discovery proposal {} dismissed: {}", id, e.getMessage() );
            return false;
        }
    }

    public List< DismissedProposal > listDismissed( final int limit, final int offset ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created, reviewed_by, reviewed_at "
            + "FROM hub_discovery_proposals "
            + "WHERE status = 'dismissed' "
            + "ORDER BY reviewed_at DESC NULLS LAST, id DESC "
            + "LIMIT ? OFFSET ?";
        final List< DismissedProposal > out = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, limit );
            ps.setInt( 2, offset );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( mapDismissedRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list dismissed hub discovery proposals: {}", e.getMessage() );
        }
        return out;
    }

    public int countDismissed() {
        final String sql = "SELECT COUNT(*) FROM hub_discovery_proposals WHERE status = 'dismissed'";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getInt( 1 ) : 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to count dismissed hub discovery proposals: {}", e.getMessage() );
            return 0;
        }
    }

    /**
     * Deletes a row only if it is currently dismissed. Returns {@code true}
     * when exactly one row was removed. Pending rows are untouched.
     */
    public boolean deleteDismissed( final int id ) {
        final String sql = "DELETE FROM hub_discovery_proposals WHERE id = ? AND status = 'dismissed'";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete dismissed hub discovery proposal {}: {}", id, e.getMessage() );
            return false;
        }
    }

    /**
     * Bulk-deletes dismissed rows matching the given ids. Pending rows among
     * the supplied ids are left untouched. Returns the number of rows deleted.
     */
    public int deleteDismissedBulk( final List< Integer > ids ) {
        if ( ids == null || ids.isEmpty() ) return 0;
        final String sql = "DELETE FROM hub_discovery_proposals "
            + "WHERE status = 'dismissed' AND id = ANY (?)";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            final Integer[] idArray = ids.toArray( new Integer[ 0 ] );
            ps.setArray( 1, conn.createArrayOf( "INTEGER", idArray ) );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to bulk-delete dismissed hub discovery proposals {}: {}",
                ids, e.getMessage() );
            return 0;
        }
    }

    /**
     * Returns {@code true} if a dismissed proposal already exists whose
     * {@code member_pages} JSONB array exactly matches the supplied list. The
     * caller is responsible for passing the canonical (sorted) member list —
     * {@link HubDiscoveryService#runDiscovery()} sorts cluster members before
     * insertion and before calling this method, so equal clusters produce
     * equal JSONB.
     */
    public boolean findDismissedMatchingMembers( final List< String > sortedMembers ) {
        final String sql = "SELECT 1 FROM hub_discovery_proposals "
            + "WHERE status = 'dismissed' AND member_pages = ?::jsonb LIMIT 1";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, GSON.toJson( sortedMembers ) );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to check dismissed-member match for {}: {}",
                sortedMembers, e.getMessage() );
            return false;
        }
    }

    private static HubDiscoveryProposal mapRow( final ResultSet rs ) throws SQLException {
        final List< String > members = GSON.fromJson( rs.getString( "member_pages" ), STRING_LIST );
        return new HubDiscoveryProposal(
            rs.getInt( "id" ),
            rs.getString( "suggested_name" ),
            rs.getString( "exemplar_page" ),
            members,
            rs.getDouble( "coherence_score" ),
            rs.getTimestamp( "created" ).toInstant()
        );
    }

    private static DismissedProposal mapDismissedRow( final ResultSet rs ) throws SQLException {
        final List< String > members = GSON.fromJson( rs.getString( "member_pages" ), STRING_LIST );
        final Timestamp reviewedAtTs = rs.getTimestamp( "reviewed_at" );
        return new DismissedProposal(
            rs.getInt( "id" ),
            rs.getString( "suggested_name" ),
            rs.getString( "exemplar_page" ),
            members,
            rs.getDouble( "coherence_score" ),
            rs.getTimestamp( "created" ).toInstant(),
            rs.getString( "reviewed_by" ),
            reviewedAtTs != null ? reviewedAtTs.toInstant() : null
        );
    }
}
