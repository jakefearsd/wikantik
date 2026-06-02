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

import com.wikantik.api.knowledge.*;
import com.wikantik.knowledge.extraction.ProposalUpserter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * JDBC repository for {@code kg_proposals} and {@code kg_proposal_reviews} table operations.
 *
 * <p>Extracted from the former JdbcKnowledgeRepository facade in Phase 3 Checkpoint 3 of the
 * wikantik-main subsystem decomposition. SQL strings are verbatim copies; no behaviour
 * change is introduced.</p>
 *
 * <p>{@link #updateTierByProvenance} touches both {@code kg_nodes} and {@code kg_edges}
 * as a side-effect of proposal application; it keeps its original implementation here
 * with a direct DataSource handle so all three tables stay in a single connection scope.</p>
 *
 * @since 1.0
 */
public final class KgProposalRepository extends KgJdbcSupport {

    private static final Logger LOG = LogManager.getLogger( KgProposalRepository.class );

    /**
     * Sentinel value for the {@code machineStatus} filter that maps to
     * {@code AND machine_status IS NULL}. Used by the "Awaiting machine review"
     * admin filter so it can paginate server-side accurately.
     */
    public static final String MACHINE_STATUS_NULL_SENTINEL = "(null)";

    public KgProposalRepository( final DataSource dataSource ) {
        super( dataSource );
    }

    @Override
    protected Logger log() {
        return LOG;
    }

    private static boolean isPoolClosed( final SQLException e ) {
        if ( e == null ) return false;
        final String msg = e.getMessage();
        return msg != null && msg.toLowerCase( java.util.Locale.ROOT ).contains( "data source is closed" );
    }

    private KgProposal mapProposal( final ResultSet rs ) throws SQLException {
        return new KgProposal(
            rs.getObject( "id", UUID.class ),
            rs.getString( "proposal_type" ),
            rs.getString( "source_page" ),
            parseJson( rs.getString( "proposed_data" ) ),
            rs.getDouble( "confidence" ),
            rs.getString( "reasoning" ),
            rs.getString( "status" ),
            rs.getString( "reviewed_by" ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "reviewed_at" ) ),
            rs.getString( "tier" ),
            rs.getString( "machine_status" ),
            rs.getObject( "machine_confidence", Double.class ),
            toInstant( rs.getTimestamp( "machine_judged_at" ) ),
            rs.getString( "machine_model" )
        );
    }

    private static Map< String, Object > buildProposedData( final ConsolidatedProposal cp ) {
        if ( cp.kind() == ConsolidatedProposal.Kind.NEW_NODE ) {
            return Map.of( "name", cp.displayName(), "nodeType", cp.type() );
        }
        return Map.of( "source", cp.source(), "target", cp.target(),
                       "relationship", cp.predicate() );
    }

    // ---- Proposal operations ----

    public KgProposal insertProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        final String sql = "INSERT INTO kg_proposals ( proposal_type, source_page, proposed_data, confidence, reasoning ) "
                + "VALUES ( ?, ?, ?::jsonb, ?, ? )";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, proposalType );
            ps.setString( 2, sourcePage );
            ps.setString( 3, GSON.toJson( proposedData != null ? proposedData : Map.of() ) );
            ps.setDouble( 4, confidence );
            ps.setString( 5, reasoning );
            ps.executeUpdate();
            try ( ResultSet keys = ps.getGeneratedKeys() ) {
                if ( keys.next() ) {
                    final UUID id = keys.getObject( 1, UUID.class );
                    return getProposal( id );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert proposal: {}", e.getMessage(), e );
            throw new RuntimeException( "Failed to insert proposal: " + e.getMessage(), e );
        }
        return null;
    }

    public KgProposal getProposal( final UUID id ) {
        final String sql = "SELECT * FROM kg_proposals WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapProposal( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get proposal '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                             final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_proposals WHERE 1=1" );
        final List< Object > params = new ArrayList<>();

        if ( status != null ) {
            sql.append( " AND status = ?" );
            params.add( status );
        }
        if ( sourcePage != null ) {
            sql.append( " AND source_page = ?" );
            params.add( sourcePage );
        }

        sql.append( " ORDER BY created DESC LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        final List< KgProposal > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapProposal( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list proposals: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public List< KgProposal > listProposalsFiltered( final String status, final String tier,
                                                      final String machineStatus,
                                                      final boolean includeMachineRejected,
                                                      final String sourcePage,
                                                      final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_proposals WHERE 1=1" );
        final List< Object > params = new ArrayList<>();

        appendProposalFilters( sql, params, status, tier, machineStatus, includeMachineRejected, sourcePage );
        // The id-DESC tiebreak is required for stable pagination — without it
        // two rows with equal `created` could swap positions across page
        // boundaries, causing one to appear twice or get skipped entirely.
        sql.append( " ORDER BY created DESC, id DESC LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        final List< KgProposal > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapProposal( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "listProposalsFiltered failed: {}", e.getMessage(), e );
            throw new RuntimeException( "listProposalsFiltered failed: " + e.getMessage(), e );
        }
        return results;
    }

    /**
     * Returns the total number of proposals matching the same filter set as
     * {@link #listProposalsFiltered} ignores the {@code limit}/{@code offset}.
     * Required for the pagination footer in the admin queue ("Showing X–Y of Z").
     *
     * <p>The WHERE clause is shared with {@link #listProposalsFiltered} via
     * {@link #appendProposalFilters} — keeping them in one place prevents the
     * two queries from drifting and mis-stating totals (which would silently
     * truncate pages in the UI).
     */
    public long countProposalsFiltered( final String status, final String tier,
                                         final String machineStatus,
                                         final boolean includeMachineRejected,
                                         final String sourcePage ) {
        final StringBuilder sql = new StringBuilder( "SELECT COUNT(*) FROM kg_proposals WHERE 1=1" );
        final List< Object > params = new ArrayList<>();
        appendProposalFilters( sql, params, status, tier, machineStatus, includeMachineRejected, sourcePage );
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getLong( 1 ) : 0L;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "countProposalsFiltered failed: {}", e.getMessage(), e );
            throw new RuntimeException( "countProposalsFiltered failed: " + e.getMessage(), e );
        }
    }

    /**
     * Appends the shared proposal-filter WHERE conditions to {@code sql} and
     * collects their bind parameters into {@code params}, in the order the
     * placeholders are emitted. Used identically by {@link #listProposalsFiltered}
     * and {@link #countProposalsFiltered} so their filters cannot diverge.
     */
    private static void appendProposalFilters( final StringBuilder sql, final List< Object > params,
                                               final String status, final String tier,
                                               final String machineStatus,
                                               final boolean includeMachineRejected,
                                               final String sourcePage ) {
        if ( status != null ) { sql.append( " AND status = ?" ); params.add( status ); }
        if ( tier != null ) { sql.append( " AND tier = ?" ); params.add( tier ); }
        if ( machineStatus != null ) {
            // The "(null)" sentinel maps to IS NULL — used by the admin queue's
            // "Awaiting machine review" filter, which would otherwise be
            // forced to filter client-side and break pagination accuracy.
            if ( MACHINE_STATUS_NULL_SENTINEL.equals( machineStatus ) ) {
                sql.append( " AND machine_status IS NULL" );
            } else {
                sql.append( " AND machine_status = ?" ); params.add( machineStatus );
            }
        }
        if ( !includeMachineRejected ) {
            sql.append( " AND ( machine_status IS NULL OR machine_status <> 'rejected' )" );
        }
        if ( sourcePage != null ) { sql.append( " AND source_page = ?" ); params.add( sourcePage ); }
    }

    public void updateProposalStatus( final UUID id, final String status, final String reviewedBy ) {
        final String sql = "UPDATE kg_proposals SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setObject( 3, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to update proposal status '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to update proposal status: " + e.getMessage(), e );
        }
    }

    public void applyMachineVerdict( final UUID proposalId, final String verdict,
                                      final double confidence, final String model ) {
        final String newTier;
        final String newStatus;
        if ( "approved".equals( verdict ) ) {
            newTier = "machine"; newStatus = null;
        } else if ( "rejected".equals( verdict ) ) {
            newTier = "none"; newStatus = "rejected";
        } else if ( "abstain".equals( verdict ) ) {
            newTier = "none"; newStatus = null;
        } else {
            throw new IllegalArgumentException( "verdict must be approved|rejected|abstain, got: " + verdict );
        }

        // Two constant SQL templates — one that also flips the human-visible status
        // (rejected), one that does not (approved / abstain). Neither template
        // contains any runtime-variable fragment, so there is no SQL-injection risk.
        final String sqlWithStatus =
            "UPDATE kg_proposals SET " +
            "machine_status = ?, machine_confidence = ?, machine_judged_at = NOW(), " +
            "machine_model = ?, tier = ?, status = ? WHERE id = ?";
        final String sqlNoStatus =
            "UPDATE kg_proposals SET " +
            "machine_status = ?, machine_confidence = ?, machine_judged_at = NOW(), " +
            "machine_model = ?, tier = ? WHERE id = ?";
        final String sql = newStatus != null ? sqlWithStatus : sqlNoStatus;
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            int idx = 1;
            ps.setString( idx++, verdict );
            ps.setDouble( idx++, confidence );
            ps.setString( idx++, model );
            ps.setString( idx++, newTier );
            if ( newStatus != null ) ps.setString( idx++, newStatus );
            ps.setObject( idx, proposalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            if ( isPoolClosed( e ) ) {
                LOG.debug( "applyMachineVerdict({}, {}) skipped — data source closed during shutdown", proposalId, verdict );
                throw new PoolClosedException( "applyMachineVerdict aborted: pool closed", e );
            }
            LOG.warn( "applyMachineVerdict({}, {}) failed: {}", proposalId, verdict,
                e.getMessage(), e );
            throw new RuntimeException( "applyMachineVerdict failed: " + e.getMessage(), e );
        }
    }

    public void applyHumanVerdict( final UUID proposalId, final String verdict,
                                    final String reviewedBy ) {
        if ( !( "approved".equals( verdict ) || "rejected".equals( verdict ) ) ) {
            throw new IllegalArgumentException( "human verdict must be approved|rejected, got: " + verdict );
        }
        final String newTier = "approved".equals( verdict ) ? "human" : "none";
        final String sql = "UPDATE kg_proposals SET " +
            "status = ?, reviewed_by = ?, reviewed_at = NOW(), tier = ? WHERE id = ?";
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, verdict );
            ps.setString( 2, reviewedBy );
            ps.setString( 3, newTier );
            ps.setObject( 4, proposalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            if ( isPoolClosed( e ) ) {
                LOG.debug( "applyHumanVerdict({}, {}) skipped — data source closed during shutdown", proposalId, verdict );
                throw new PoolClosedException( "applyHumanVerdict aborted: pool closed", e );
            }
            LOG.warn( "applyHumanVerdict({}, {}, {}) failed: {}", proposalId, verdict,
                reviewedBy, e.getMessage(), e );
            throw new RuntimeException( "applyHumanVerdict failed: " + e.getMessage(), e );
        }
    }

    public void recordReview( final UUID proposalId, final String reviewerKind,
                              final String reviewerId, final String verdict,
                              final Double confidence, final String rationale ) {
        final String sql = "INSERT INTO kg_proposal_reviews " +
            "(proposal_id, reviewer_kind, reviewer_id, verdict, confidence, rationale) " +
            "VALUES (?, ?, ?, ?, ?, ?)";
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, proposalId );
            ps.setString( 2, reviewerKind );
            ps.setString( 3, reviewerId );
            ps.setString( 4, verdict );
            if ( confidence == null ) ps.setNull( 5, java.sql.Types.DOUBLE );
            else ps.setDouble( 5, confidence );
            ps.setString( 6, rationale );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            if ( isPoolClosed( e ) ) {
                LOG.debug( "recordReview({}, {}) skipped — data source closed during shutdown", proposalId, reviewerKind );
                throw new PoolClosedException( "recordReview aborted: pool closed", e );
            }
            LOG.warn( "recordReview({}, {}, {}) failed: {}", proposalId, reviewerKind,
                verdict, e.getMessage(), e );
            throw new RuntimeException( "recordReview failed: " + e.getMessage(), e );
        }
    }

    public List< KgProposalReview > listReviews( final UUID proposalId ) {
        final String sql = "SELECT * FROM kg_proposal_reviews WHERE proposal_id = ? " +
            "ORDER BY created DESC, id DESC";
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, proposalId );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< KgProposalReview > out = new ArrayList<>();
                while ( rs.next() ) {
                    final Double conf = rs.getObject( "confidence", Double.class );
                    out.add( new KgProposalReview(
                        rs.getObject( "id", UUID.class ),
                        rs.getObject( "proposal_id", UUID.class ),
                        rs.getString( "reviewer_kind" ),
                        rs.getString( "reviewer_id" ),
                        rs.getString( "verdict" ),
                        conf,
                        rs.getString( "rationale" ),
                        rs.getTimestamp( "created" ).toInstant()
                    ) );
                }
                return out;
            }
        } catch ( final SQLException e ) {
            if ( isPoolClosed( e ) ) {
                LOG.debug( "listReviews({}) skipped — data source closed during shutdown", proposalId );
                throw new PoolClosedException( "listReviews aborted: pool closed", e );
            }
            LOG.warn( "listReviews({}) failed: {}", proposalId, e.getMessage(), e );
            throw new RuntimeException( "listReviews failed: " + e.getMessage(), e );
        }
    }

    public List< KgProposal > getProposalsForJudging( final int batch ) {
        final String sql = "SELECT * FROM kg_proposals " +
            "WHERE status = 'pending' AND machine_status IS NULL " +
            "ORDER BY created ASC " +
            "LIMIT ? FOR UPDATE SKIP LOCKED";
        try ( Connection c = dataSource.getConnection() ) {
            final boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit( false );
            try ( PreparedStatement ps = c.prepareStatement( sql ) ) {
                ps.setInt( 1, batch );
                try ( ResultSet rs = ps.executeQuery() ) {
                    final List< KgProposal > out = new ArrayList<>();
                    while ( rs.next() ) out.add( mapProposal( rs ) );
                    c.commit();
                    return out;
                }
            } catch ( final SQLException e ) {
                try { c.rollback(); } catch ( final SQLException ignore ) { /* best effort */ }
                throw e;
            } finally {
                try { c.setAutoCommit( prevAutoCommit ); } catch ( final SQLException ignore ) { /* best effort */ }
            }
        } catch ( final SQLException e ) {
            if ( isPoolClosed( e ) ) {
                LOG.debug( "getProposalsForJudging({}) skipped — data source closed during shutdown", batch );
                throw new PoolClosedException( "getProposalsForJudging aborted: pool closed", e );
            }
            LOG.warn( "getProposalsForJudging({}) failed: {}", batch, e.getMessage(), e );
            throw new RuntimeException( "getProposalsForJudging failed: " + e.getMessage(), e );
        }
    }

    /**
     * Updates the tier of all kg_nodes and kg_edges rows with a given provenance_proposal_id.
     * This method touches both node and edge tables; it keeps the original connection scope
     * to ensure both UPDATEs share the same connection handle.
     *
     * @param proposalId the proposal UUID
     * @param newTier    the new tier ('machine' or 'human')
     * @return the total count of updated rows (nodes + edges)
     */
    public int updateTierByProvenance( final UUID proposalId, final String newTier ) {
        int rows = 0;
        try ( Connection c = dataSource.getConnection() ) {
            try ( PreparedStatement ps = c.prepareStatement(
                    "UPDATE kg_nodes SET tier = ? WHERE provenance_proposal_id = ?" ) ) {
                ps.setString( 1, newTier );
                ps.setObject( 2, proposalId );
                rows += ps.executeUpdate();
            }
            try ( PreparedStatement ps = c.prepareStatement(
                    "UPDATE kg_edges SET tier = ? WHERE provenance_proposal_id = ?" ) ) {
                ps.setString( 1, newTier );
                ps.setObject( 2, proposalId );
                rows += ps.executeUpdate();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "updateTierByProvenance({}, {}) failed: {}", proposalId, newTier,
                e.getMessage(), e );
            throw new RuntimeException( "updateTierByProvenance failed: " + e.getMessage(), e );
        }
        return rows;
    }

    public long countPendingProposals() {
        return queryCount( "SELECT COUNT(*) FROM kg_proposals WHERE status = 'pending'" );
    }

    public long countPendingUnjudgedProposals() {
        return queryCount( "SELECT COUNT(*) FROM kg_proposals "
            + "WHERE status = 'pending' AND machine_status IS NULL" );
    }

    /**
     * Single-round-trip breakdown of every pending proposal split by
     * {@code proposal_type} and {@code machine_status}. Surfaced in the schema
     * stats so the admin UI can describe the queue with more than a bare count.
     * Returns {@link SchemaDescription.PendingBreakdown#EMPTY} on SQL failure
     * rather than throwing — the schema header is informational, not
     * load-bearing, and shouldn't crash the page if the count query glitches.
     */
    public SchemaDescription.PendingBreakdown countPendingBreakdown() {
        final String sql = """
            SELECT
                COUNT(*)                                                          AS total,
                COALESCE(SUM(CASE WHEN proposal_type='new-node' THEN 1 ELSE 0 END), 0) AS new_nodes,
                COALESCE(SUM(CASE WHEN proposal_type='new-edge' THEN 1 ELSE 0 END), 0) AS new_edges,
                COALESCE(SUM(CASE WHEN machine_status='approved' THEN 1 ELSE 0 END), 0) AS judge_approved,
                COALESCE(SUM(CASE WHEN machine_status='abstain'  THEN 1 ELSE 0 END), 0) AS judge_abstained,
                COALESCE(SUM(CASE WHEN machine_status IS NULL    THEN 1 ELSE 0 END), 0) AS unjudged
            FROM kg_proposals
            WHERE status = 'pending'
            """;
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            if ( rs.next() ) {
                return new SchemaDescription.PendingBreakdown(
                    rs.getLong( "total" ),
                    rs.getLong( "new_nodes" ),
                    rs.getLong( "new_edges" ),
                    rs.getLong( "judge_approved" ),
                    rs.getLong( "judge_abstained" ),
                    rs.getLong( "unjudged" )
                );
            }
            return SchemaDescription.PendingBreakdown.EMPTY;
        } catch ( final SQLException e ) {
            LOG.warn( "countPendingBreakdown failed, returning empty: {}", e.getMessage(), e );
            return SchemaDescription.PendingBreakdown.EMPTY;
        }
    }

    public ProposalUpserter.Result upsertConsolidatedProposal( final ConsolidatedProposal cp ) {
        final String proposedJson = GSON.toJson( buildProposedData( cp ) );
        final String supportJson  = GSON.toJson( cp.support() );
        final String sql = """
            WITH merged AS (
                SELECT (
                    SELECT jsonb_agg(s ORDER BY (s->>'sourcePage'))
                    FROM (
                        SELECT DISTINCT ON (s->>'sourcePage') s
                        FROM jsonb_array_elements(
                            COALESCE(kp.support, '[]'::jsonb) || ?::jsonb
                        ) s
                        ORDER BY (s->>'sourcePage'), (s->>'confidence')::numeric DESC
                    ) deduped
                ) AS support_merged
                FROM kg_proposals kp
                WHERE kp.signature = ? AND kp.status = 'pending'
            )
            INSERT INTO kg_proposals
                (proposal_type, source_page, proposed_data, confidence, reasoning,
                 signature, support, support_count, first_seen_at, last_seen_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb,
                    jsonb_array_length(?::jsonb), NOW(), NOW())
            ON CONFLICT (signature) WHERE status = 'pending' DO UPDATE
            SET support       = COALESCE((SELECT support_merged FROM merged), kg_proposals.support),
                support_count = jsonb_array_length(COALESCE((SELECT support_merged FROM merged), kg_proposals.support)),
                confidence    = GREATEST(kg_proposals.confidence, EXCLUDED.confidence),
                last_seen_at  = NOW()
            RETURNING (xmax = 0) AS inserted, support_count
            """;
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, supportJson );
            ps.setString( 2, cp.signature() );
            ps.setString( 3, cp.kind() == ConsolidatedProposal.Kind.NEW_NODE ? "new-node" : "new-edge" );
            ps.setString( 4, cp.support().isEmpty() ? null : cp.support().get( 0 ).sourcePage() );
            ps.setString( 5, proposedJson );
            ps.setDouble( 6, cp.aggregateConfidence() );
            ps.setString( 7, "consolidated by " + cp.support().size() + " support(s)" );
            ps.setString( 8, cp.signature() );
            ps.setString( 9, supportJson );
            ps.setString( 10, supportJson );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( rs.next() ) {
                    return new ProposalUpserter.Result( rs.getBoolean( 1 ), rs.getInt( 2 ) );
                }
                throw new IllegalStateException( "upsert returned no rows for signature " + cp.signature() );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "upsertConsolidatedProposal failed for signature {}: {}", cp.signature(), e.getMessage() );
            throw new RuntimeException( "upsert failed: " + e.getMessage(), e );
        }
    }
}
