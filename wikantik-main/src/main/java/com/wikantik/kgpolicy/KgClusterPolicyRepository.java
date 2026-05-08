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
package com.wikantik.kgpolicy;

import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
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
import java.util.List;
import java.util.Optional;

/**
 * JDBC-based data access layer for the {@code kg_cluster_policy} and
 * {@code kg_policy_audit} tables.
 *
 * <p>Follows the same plain-JDBC conventions as
 * {@link com.wikantik.knowledge.chunking.ContentChunkRepository}: try-with-resources
 * throughout, {@code LOG.warn()} on every {@link SQLException} before wrapping
 * in a {@link RuntimeException} with a context message.</p>
 *
 * @since 1.0
 */
public class KgClusterPolicyRepository {

    private static final Logger LOG = LogManager.getLogger( KgClusterPolicyRepository.class );

    private final DataSource ds;

    public KgClusterPolicyRepository( final DataSource ds ) {
        this.ds = ds;
    }

    /**
     * Returns the policy for the given cluster, or empty if no row exists.
     *
     * @param cluster cluster name
     * @return policy wrapped in Optional
     */
    public Optional< ClusterPolicy > find( final String cluster ) {
        final String sql = "SELECT cluster, action, reason, set_by, set_at, reviewed_at "
                         + "FROM kg_cluster_policy WHERE cluster = ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, cluster );
            try( ResultSet rs = ps.executeQuery() ) {
                if( rs.next() ) {
                    return Optional.of( map( rs ) );
                }
                return Optional.empty();
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find policy for cluster '{}': {}", cluster, e.getMessage(), e );
            throw new RuntimeException( "find policy for " + cluster, e );
        }
    }

    /**
     * Returns all cluster policies ordered by cluster name.
     *
     * @return list of policies, empty if none exist
     */
    public List< ClusterPolicy > list() {
        final String sql = "SELECT cluster, action, reason, set_by, set_at, reviewed_at "
                         + "FROM kg_cluster_policy ORDER BY cluster";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            final List< ClusterPolicy > out = new ArrayList<>();
            while( rs.next() ) {
                out.add( map( rs ) );
            }
            return out;
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list cluster policies: {}", e.getMessage(), e );
            throw new RuntimeException( "list cluster policies", e );
        }
    }

    /**
     * Inserts or updates the policy for the given cluster.
     * On conflict the existing row is updated with the new action, reason,
     * set_by, and set_at = NOW(); reviewed_at is cleared to NULL.
     *
     * @param cluster cluster name
     * @param action  include or exclude
     * @param reason  human-readable justification (may be null)
     * @param setBy   actor who set the policy
     */
    public void upsert( final String cluster, final ClusterAction action,
                        final String reason, final String setBy ) {
        final String sql = "INSERT INTO kg_cluster_policy "
                         + "( cluster, action, reason, set_by, set_at ) "
                         + "VALUES ( ?, ?, ?, ?, NOW() ) "
                         + "ON CONFLICT (cluster) DO UPDATE SET "
                         + "  action = EXCLUDED.action, "
                         + "  reason = EXCLUDED.reason, "
                         + "  set_by = EXCLUDED.set_by, "
                         + "  set_at = NOW(), "
                         + "  reviewed_at = NULL";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, cluster );
            ps.setString( 2, action.wire() );
            ps.setString( 3, reason );
            ps.setString( 4, setBy );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to upsert policy for cluster '{}': {}", cluster, e.getMessage(), e );
            throw new RuntimeException( "upsert policy for " + cluster, e );
        }
    }

    /**
     * Deletes the policy row for the given cluster. No-op if the cluster
     * has no policy.
     *
     * @param cluster cluster name
     */
    public void delete( final String cluster ) {
        final String sql = "DELETE FROM kg_cluster_policy WHERE cluster = ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, cluster );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete policy for cluster '{}': {}", cluster, e.getMessage(), e );
            throw new RuntimeException( "delete policy for " + cluster, e );
        }
    }

    /**
     * Stamps {@code reviewed_at = NOW()} on the policy row. No-op if the
     * cluster has no policy.
     *
     * @param cluster cluster name
     */
    public void markReviewed( final String cluster ) {
        final String sql = "UPDATE kg_cluster_policy SET reviewed_at = NOW() WHERE cluster = ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, cluster );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to mark reviewed for cluster '{}': {}", cluster, e.getMessage(), e );
            throw new RuntimeException( "markReviewed for " + cluster, e );
        }
    }

    /**
     * Appends one row to {@code kg_policy_audit}.
     *
     * @param cluster   cluster name
     * @param oldAction previous action string (null on first set)
     * @param newAction new action string (include, exclude, cleared, purged)
     * @param reason    human-readable justification (may be null)
     * @param actor     the user or system that made the change
     */
    public void appendAudit( final String cluster, final String oldAction,
                              final String newAction, final String reason, final String actor ) {
        final String sql = "INSERT INTO kg_policy_audit "
                         + "( cluster, old_action, new_action, reason, actor, changed_at ) "
                         + "VALUES ( ?, ?, ?, ?, ?, NOW() )";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, cluster );
            ps.setString( 2, oldAction );
            ps.setString( 3, newAction );
            ps.setString( 4, reason );
            ps.setString( 5, actor );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to append audit for cluster '{}': {}", cluster, e.getMessage(), e );
            throw new RuntimeException( "appendAudit for " + cluster, e );
        }
    }

    /**
     * Lists audit entries in reverse-chronological order.
     *
     * @param cluster if present, only entries for that cluster are returned
     * @param limit   maximum number of rows to return
     * @return audit entries, most recent first
     */
    public List< PolicyAuditEntry > listAudit( final Optional< String > cluster, final int limit ) {
        final boolean filtered = cluster.isPresent();
        final String sql = filtered
            ? "SELECT id, cluster, old_action, new_action, reason, actor, changed_at "
            + "FROM kg_policy_audit WHERE cluster = ? ORDER BY changed_at DESC LIMIT ?"
            : "SELECT id, cluster, old_action, new_action, reason, actor, changed_at "
            + "FROM kg_policy_audit ORDER BY changed_at DESC LIMIT ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            if( filtered ) {
                ps.setString( 1, cluster.get() );
                ps.setInt( 2, limit );
            } else {
                ps.setInt( 1, limit );
            }
            try( ResultSet rs = ps.executeQuery() ) {
                final List< PolicyAuditEntry > out = new ArrayList<>();
                while( rs.next() ) {
                    out.add( new PolicyAuditEntry(
                        rs.getLong( 1 ),
                        rs.getString( 2 ),
                        rs.getString( 3 ),
                        rs.getString( 4 ),
                        rs.getString( 5 ),
                        rs.getString( 6 ),
                        ts( rs, 7 ) ) );
                }
                return out;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list audit entries (cluster={}): {}", cluster, e.getMessage(), e );
            throw new RuntimeException( "listAudit" + cluster.map( c -> " for " + c ).orElse( "" ), e );
        }
    }

    // ---- private helpers ----

    private static ClusterPolicy map( final ResultSet rs ) throws SQLException {
        final String actionStr = rs.getString( 2 );
        final ClusterAction action = ClusterAction.fromWire( actionStr )
            .orElseThrow( () -> new SQLException( "Unknown action value: " + actionStr ) );
        return new ClusterPolicy(
            rs.getString( 1 ),
            action,
            rs.getString( 3 ),
            rs.getString( 4 ),
            ts( rs, 5 ),
            ts( rs, 6 ) );
    }

    private static Instant ts( final ResultSet rs, final int col ) throws SQLException {
        final Timestamp t = rs.getTimestamp( col );
        return t == null ? null : t.toInstant();
    }
}
