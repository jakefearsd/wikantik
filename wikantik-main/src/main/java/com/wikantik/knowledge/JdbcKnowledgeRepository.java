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
 * Thin facade over the four narrow KG repositories.
 *
 * <p>Phase 3 Checkpoint 3 of the wikantik-main subsystem decomposition converts this
 * god-class into a delegation layer. All method bodies are single-line calls into
 * {@link KgNodeRepository}, {@link KgEdgeRepository}, {@link KgProposalRepository},
 * or {@link KgRejectionRepository}. Checkpoint 5 will delete this class once all
 * consumers have migrated to the narrow repos.</p>
 *
 * <p>Uses plain JDBC with {@link DataSource} for connection management.
 * JSON properties are serialized/deserialized with Gson.</p>
 *
 * @since 1.0
 */
public class JdbcKnowledgeRepository {

    private static final Logger LOG = LogManager.getLogger( JdbcKnowledgeRepository.class );

    private final DataSource dataSource;
    private final KgNodeRepository nodes;
    private final KgEdgeRepository edges;
    private final KgProposalRepository proposals;
    private final KgRejectionRepository rejections;

    /**
     * Single-arg constructor used by {@code wikantik-extract-cli} and any other
     * caller that builds this class directly from a DataSource.
     */
    public JdbcKnowledgeRepository( final DataSource dataSource ) {
        this( dataSource,
              new KgNodeRepository( dataSource ),
              new KgEdgeRepository( dataSource ),
              new KgProposalRepository( dataSource ),
              new KgRejectionRepository( dataSource ) );
    }

    /**
     * Full-arg constructor used by {@link com.wikantik.persistence.subsystem.PersistenceSubsystemFactory}
     * so the factory can share the same narrow-repo instances between this facade and
     * {@link com.wikantik.persistence.subsystem.PersistenceSubsystem.Services}.
     */
    public JdbcKnowledgeRepository( final DataSource dataSource,
                                     final KgNodeRepository nodes,
                                     final KgEdgeRepository edges,
                                     final KgProposalRepository proposals,
                                     final KgRejectionRepository rejections ) {
        this.dataSource = dataSource;
        this.nodes      = nodes;
        this.edges      = edges;
        this.proposals  = proposals;
        this.rejections = rejections;
    }

    // ---- Node operations (delegated to KgNodeRepository) ----

    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        return nodes.upsertNode( name, nodeType, sourcePage, provenance, properties );
    }

    public KgNode getNode( final UUID id ) {
        return nodes.getNode( id );
    }

    public KgNode getNodeByName( final String name ) {
        return nodes.getNodeByName( name );
    }

    public void deleteNode( final UUID id ) {
        nodes.deleteNode( id );
    }

    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset ) {
        return nodes.queryNodes( filters, provenanceFilter, limit, offset );
    }

    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                       final int limit ) {
        return nodes.searchNodes( query, provenanceFilter, limit );
    }

    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                       final int limit, final Tier minTier ) {
        return nodes.searchNodes( query, provenanceFilter, limit, minTier );
    }

    public List< KgNode > getAllNodes() {
        return nodes.getAllNodes();
    }

    public List< KgNode > getAllNodes( final Tier minTier ) {
        return nodes.getAllNodes( minTier );
    }

    public KgNode upsertNodeWithProvenance( final String name, final String nodeType,
                                            final String sourcePage, final Provenance provenance,
                                            final Map< String, Object > properties,
                                            final String tier, final UUID provenanceProposalId ) {
        return nodes.upsertNodeWithProvenance( name, nodeType, sourcePage, provenance,
                                               properties, tier, provenanceProposalId );
    }

    public int deleteNodesByProvenance( final UUID proposalId ) {
        return nodes.deleteNodesByProvenance( proposalId );
    }

    public List< String > getDistinctNodeTypes() {
        return nodes.getDistinctNodeTypes();
    }

    public long countNodes() {
        return nodes.countNodes();
    }

    public Map< UUID, String > getNodeNames( final Collection< UUID > ids ) {
        return nodes.getNodeNames( ids );
    }

    // ---- Edge operations (delegated to KgEdgeRepository) ----

    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                              final Provenance provenance, final Map< String, Object > properties ) {
        return edges.upsertEdge( sourceId, targetId, relationshipType, provenance, properties );
    }

    public void upsertEdgeWithProvenance( final UUID sourceId, final UUID targetId,
                                          final String relationshipType, final Provenance provenance,
                                          final Map< String, Object > properties,
                                          final String tier, final UUID provenanceProposalId ) {
        edges.upsertEdgeWithProvenance( sourceId, targetId, relationshipType, provenance,
                                        properties, tier, provenanceProposalId );
    }

    public void deleteEdge( final UUID id ) {
        edges.deleteEdge( id );
    }

    public int deleteEdgesByProvenance( final UUID proposalId ) {
        return edges.deleteEdgesByProvenance( proposalId );
    }

    public List< KgEdge > getAllEdges() {
        return edges.getAllEdges();
    }

    public List< KgEdge > getAllEdges( final Tier minTier ) {
        return edges.getAllEdges( minTier );
    }

    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        return edges.getEdgesForNode( nodeId, direction );
    }

    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        edges.diffAndRemoveStaleEdges( sourceId, currentEdges );
    }

    public List< Map< String, Object > > queryEdgesWithNames( final String relationshipType,
                                                               final String searchName,
                                                               final int limit, final int offset ) {
        return edges.queryEdgesWithNames( relationshipType, searchName, limit, offset );
    }

    public List< String > getDistinctRelationshipTypes() {
        return edges.getDistinctRelationshipTypes();
    }

    public long countEdges() {
        return edges.countEdges();
    }

    // ---- Proposal operations (delegated to KgProposalRepository) ----

    public KgProposal insertProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        return proposals.insertProposal( proposalType, sourcePage, proposedData, confidence, reasoning );
    }

    public KgProposal getProposal( final UUID id ) {
        return proposals.getProposal( id );
    }

    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                             final int limit, final int offset ) {
        return proposals.listProposals( status, sourcePage, limit, offset );
    }

    public List< KgProposal > listProposalsFiltered( final String status, final String tier,
                                                      final String machineStatus,
                                                      final boolean includeMachineRejected,
                                                      final String sourcePage,
                                                      final int limit, final int offset ) {
        return proposals.listProposalsFiltered( status, tier, machineStatus, includeMachineRejected,
                                                sourcePage, limit, offset );
    }

    public void updateProposalStatus( final UUID id, final String status, final String reviewedBy ) {
        proposals.updateProposalStatus( id, status, reviewedBy );
    }

    public void applyMachineVerdict( final UUID proposalId, final String verdict,
                                      final double confidence, final String model ) {
        proposals.applyMachineVerdict( proposalId, verdict, confidence, model );
    }

    public void applyHumanVerdict( final UUID proposalId, final String verdict,
                                    final String reviewedBy ) {
        proposals.applyHumanVerdict( proposalId, verdict, reviewedBy );
    }

    public void recordReview( final UUID proposalId, final String reviewerKind,
                              final String reviewerId, final String verdict,
                              final Double confidence, final String rationale ) {
        proposals.recordReview( proposalId, reviewerKind, reviewerId, verdict, confidence, rationale );
    }

    public List< KgProposalReview > listReviews( final UUID proposalId ) {
        return proposals.listReviews( proposalId );
    }

    public List< KgProposal > getProposalsForJudging( final int batch ) {
        return proposals.getProposalsForJudging( batch );
    }

    public int updateTierByProvenance( final UUID proposalId, final String newTier ) {
        return proposals.updateTierByProvenance( proposalId, newTier );
    }

    public long countPendingProposals() {
        return proposals.countPendingProposals();
    }

    public long countPendingUnjudgedProposals() {
        return proposals.countPendingUnjudgedProposals();
    }

    public ProposalUpserter.Result upsertConsolidatedProposal( final ConsolidatedProposal cp ) {
        return proposals.upsertConsolidatedProposal( cp );
    }

    // ---- Rejection operations (delegated to KgRejectionRepository) ----

    public void insertRejection( final String proposedSource, final String proposedTarget,
                                 final String proposedRelationship,
                                 final String rejectedBy, final String reason ) {
        rejections.insertRejection( proposedSource, proposedTarget, proposedRelationship,
                                    rejectedBy, reason );
    }

    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        return rejections.isRejected( sourceName, targetName, relationshipType );
    }

    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                               final String relationshipType ) {
        return rejections.listRejections( sourceName, targetName, relationshipType );
    }

    public int deleteRejection( final String source, final String target, final String relationship ) {
        return rejections.deleteRejection( source, target, relationship );
    }

    // ---- Public accessors (used by bridge constructors in consuming classes across sub-packages) ----

    public KgNodeRepository nodes()           { return nodes; }
    public KgEdgeRepository edges()           { return edges; }
    public KgProposalRepository proposals()   { return proposals; }
    public KgRejectionRepository rejections() { return rejections; }
    public DataSource dataSource()            { return dataSource; }

    // ---- Teardown (kept here for IT teardown path; Ckpt 5 will migrate this) ----

    /**
     * Deletes all knowledge graph data: edges, proposals, rejections, and nodes.
     * Tables are cleared in FK-safe order.
     */
    public void clearAll() {
        final String[] tables = {
            "kg_proposal_reviews", "kg_edges", "kg_proposals", "kg_rejections", "kg_nodes"
        };
        try ( Connection conn = dataSource.getConnection();
              var stmt = conn.createStatement() ) {
            for ( final String table : tables ) {
                stmt.execute( "DELETE FROM " + table );
            }
            LOG.info( "Cleared all knowledge graph data ({} tables)", tables.length );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to clear knowledge graph data: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }
}
