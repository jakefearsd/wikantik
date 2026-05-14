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
import com.wikantik.api.core.Engine;
import java.sql.Connection;
import java.sql.SQLException;
import com.wikantik.api.core.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Default implementation of {@link KnowledgeGraphService}. Delegates data access
 * to the four narrow repositories ({@link KgNodeRepository}, {@link KgEdgeRepository},
 * {@link KgProposalRepository}, {@link KgRejectionRepository}) and adds BFS graph
 * traversal, schema discovery, node merging, and proposal approval/rejection logic.
 *
 * <p><b>Phase 11 Ckpt 6:</b> BFS traversal logic extracted to
 * {@link KgGraphTraversal}; snapshot-building + redaction extracted to
 * {@link KgGraphSnapshotBuilder}. This facade owns schema discovery, node/edge CRUD,
 * proposal lifecycle, and judge triggering.
 *
 * @since 1.0
 */
public class DefaultKnowledgeGraphService implements KnowledgeGraphService {

    private static final Logger LOG = LogManager.getLogger( DefaultKnowledgeGraphService.class );

    private final KgNodeRepository nodes;
    private final KgEdgeRepository edges;
    private final KgProposalRepository proposals;
    private final KgRejectionRepository rejections;
    private final javax.sql.DataSource dataSource;
    @SuppressWarnings("PMD.UnusedPrivateField") // Used in setEngine() setter
    private Engine engine;
    private final com.wikantik.knowledge.judge.KgMaterializationService materialization;
    private final com.wikantik.api.knowledge.KgProposalJudgeService judgeService;

    private final KgEdgeAuditRepository edgeAudit;
    private final com.wikantik.knowledge.chunking.ContentChunkRepository chunks;

    // Composed helpers (Phase 11 Ckpt 6)
    private final KgGraphTraversal       traversal;
    private final KgGraphSnapshotBuilder snapshotBuilder;

    public DefaultKnowledgeGraphService( final KgNodeRepository nodes,
                                          final KgEdgeRepository edges,
                                          final KgProposalRepository proposals,
                                          final KgRejectionRepository rejections,
                                          final javax.sql.DataSource dataSource ) {
        this( nodes, edges, proposals, rejections, dataSource, null, null, null, null );
    }

    public DefaultKnowledgeGraphService( final KgNodeRepository nodes,
                                          final KgEdgeRepository edges,
                                          final KgProposalRepository proposals,
                                          final KgRejectionRepository rejections,
                                          final javax.sql.DataSource dataSource,
                                          final Engine engine ) {
        this( nodes, edges, proposals, rejections, dataSource, engine, null, null, null );
    }

    public DefaultKnowledgeGraphService( final KgNodeRepository nodes,
                                          final KgEdgeRepository edges,
                                          final KgProposalRepository proposals,
                                          final KgRejectionRepository rejections,
                                          final javax.sql.DataSource dataSource,
                                          final Engine engine,
                                          final MentionIndex mentionIndex ) {
        this( nodes, edges, proposals, rejections, dataSource, engine, mentionIndex, null, null );
    }

    public DefaultKnowledgeGraphService( final KgNodeRepository nodes,
                                          final KgEdgeRepository edges,
                                          final KgProposalRepository proposals,
                                          final KgRejectionRepository rejections,
                                          final javax.sql.DataSource dataSource,
                                          final Engine engine,
                                          final MentionIndex mentionIndex,
                                          final com.wikantik.knowledge.judge.KgMaterializationService materialization,
                                          final com.wikantik.api.knowledge.KgProposalJudgeService judgeService ) {
        this.nodes           = nodes;
        this.edges           = edges;
        this.proposals       = proposals;
        this.rejections      = rejections;
        this.dataSource      = dataSource;
        this.engine          = engine;
        this.materialization = materialization;
        this.judgeService    = judgeService;
        this.edgeAudit       = new KgEdgeAuditRepository( dataSource );
        this.chunks          = new com.wikantik.knowledge.chunking.ContentChunkRepository( dataSource );
        this.traversal       = new KgGraphTraversal( nodes, edges, mentionIndex );
        this.snapshotBuilder = new KgGraphSnapshotBuilder( nodes, edges, engine );
    }

    public void setEngine( final Engine engine ) {
        this.engine = engine;
        snapshotBuilder.setEngine( engine );
    }

    // --- Schema discovery ---

    /** Vocabulary regex for node_type: lowercase start, lowercase/digits/underscore/hyphen, max 31 chars. */
    private static final java.util.regex.Pattern NODE_TYPE_REGEX =
            java.util.regex.Pattern.compile( "^[a-z][a-z0-9_-]{0,30}$" );

    /**
     * The closed relationship-type vocabulary enforced by the
     * {@code kg_edges_relationship_type_check} CHECK constraint (V027,
     * extended in V030 with {@code generalizes}). Delegates to
     * {@link com.wikantik.api.knowledge.RelationshipTypeVocabulary#CLOSED_VOCAB}
     * so the extraction prompt builders and this schema-discovery surface stay
     * in lock-step. Surfaced in {@link #discoverSchema()} so admin UIs (Edge
     * Explorer's relationship-type dropdown) always have the full vocabulary,
     * not just types that happen to exist in the current graph.
     */
    private static final List< String > RELATIONSHIP_TYPE_VOCABULARY =
            com.wikantik.api.knowledge.RelationshipTypeVocabulary.CLOSED_VOCAB;

    @Override
    public SchemaDescription discoverSchema() {
        final List< String > nodeTypes = nodes.getDistinctNodeTypes();
        // Merge the closed V027 vocabulary with any distinct types already in the graph
        // (forward-compat for hypothetical vocabulary extensions). Vocabulary order
        // first; any unknown types append.
        final LinkedHashSet< String > relTypeSet = new LinkedHashSet<>( RELATIONSHIP_TYPE_VOCABULARY );
        relTypeSet.addAll( edges.getDistinctRelationshipTypes() );
        final List< String > relTypes = new ArrayList<>( relTypeSet );

        final Map< String, Long > propCounts = new LinkedHashMap<>();
        final Map< String, List< String > > propSamples = new LinkedHashMap<>();
        final Set< String > statusSet = new TreeSet<>();
        final List< KgNode > allNodes = nodes.queryNodes( null, null, Integer.MAX_VALUE, 0 );
        for ( final KgNode node : allNodes ) {
            if ( node.properties() != null ) {
                for ( final Map.Entry< String, Object > entry : node.properties().entrySet() ) {
                    final String key = entry.getKey();
                    propCounts.merge( key, 1L, Long::sum );
                    final List< String > samples = propSamples.computeIfAbsent( key, k -> new ArrayList<>() );
                    if ( samples.size() < 5 && entry.getValue() != null ) {
                        samples.add( entry.getValue().toString() );
                    }
                    if ( "status".equals( key ) && entry.getValue() != null ) {
                        statusSet.add( entry.getValue().toString() );
                    }
                }
            }
        }

        final Map< String, SchemaDescription.PropertyInfo > propertyKeys = new LinkedHashMap<>();
        for ( final String key : propCounts.keySet() ) {
            propertyKeys.put( key, new SchemaDescription.PropertyInfo(
                    propCounts.get( key ),
                    propSamples.getOrDefault( key, List.of() )
            ) );
        }

        final long nodeCount = nodes.countNodes();
        final long edgeCount = edges.countEdges();
        final SchemaDescription.PendingBreakdown breakdown = proposals.countPendingBreakdown();

        return new SchemaDescription(
                nodeTypes,
                relTypes,
                new ArrayList<>( statusSet ),
                propertyKeys,
                new SchemaDescription.Stats( nodeCount, edgeCount, breakdown.total(), breakdown )
        );
    }

    // --- Node operations ---

    @Override
    public KgNode getNode( final UUID id ) {
        return nodes.getNode( id );
    }

    @Override
    public KgNode getNode( final UUID id, final boolean adminBypass ) {
        return nodes.getNode( id, adminBypass );
    }

    @Override
    public KgNode getNodeByName( final String name ) {
        return nodes.getNodeByName( name );
    }

    @Override
    public KgNode getNodeByName( final String name, final boolean adminBypass ) {
        return nodes.getNodeByName( name, adminBypass );
    }

    @Override
    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        if ( nodeType != null && !NODE_TYPE_REGEX.matcher( nodeType ).matches() ) {
            throw new IllegalArgumentException(
                    "invalid node_type: must match /^[a-z][a-z0-9_-]{0,30}$/ (got: '" + nodeType + "')" );
        }
        final KgNode result = nodes.upsertNode( name, nodeType, sourcePage, provenance, properties );
        snapshotBuilder.invalidateCache();
        return result;
    }

    @Override
    public void deleteNode( final UUID id ) {
        nodes.deleteNode( id );
        snapshotBuilder.invalidateCache();
    }

    @Override
    public void mergeNodes( final UUID sourceId, final UUID targetId ) {
        if ( nodes.getNode( sourceId ) == null ) {
            throw new IllegalStateException( "merge source not found: " + sourceId );
        }
        if ( nodes.getNode( targetId ) == null ) {
            throw new IllegalStateException( "merge target not found: " + targetId );
        }
        final List< KgEdge > outbound = edges.getEdgesForNode( sourceId, "outbound" );
        for ( final KgEdge edge : outbound ) {
            edges.upsertEdge( targetId, edge.targetId(), edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }
        final List< KgEdge > inbound = edges.getEdgesForNode( sourceId, "inbound" );
        for ( final KgEdge edge : inbound ) {
            edges.upsertEdge( edge.sourceId(), targetId, edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }
        nodes.deleteNode( sourceId );
        snapshotBuilder.invalidateCache();
    }

    @Override
    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset ) {
        return nodes.queryNodes( filters, provenanceFilter, limit, offset );
    }

    @Override
    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset,
                                      final boolean adminBypass ) {
        return nodes.queryNodes( filters, provenanceFilter, limit, offset, adminBypass );
    }

    @Override
    public long countNodes( final Map< String, Object > filters,
                             final Set< Provenance > provenanceFilter ) {
        return nodes.countNodesWithFilter( filters, provenanceFilter );
    }

    @Override
    public List< KgNode > listOrphanedNodes( final Map< String, Object > filters,
                                              final int limit, final int offset ) {
        return nodes.listOrphanedNodes( filters, limit, offset );
    }

    @Override
    public long countOrphanedNodes( final Map< String, Object > filters ) {
        return nodes.countOrphanedNodes( filters );
    }

    // --- Edge operations ---

    @Override
    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final KgEdge result = edges.upsertEdge( sourceId, targetId, relationshipType, provenance, properties );
        snapshotBuilder.invalidateCache();
        return result;
    }

    @Override
    public void deleteEdge( final UUID id ) {
        edges.deleteEdge( id );
        snapshotBuilder.invalidateCache();
    }

    @Override
    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        edges.diffAndRemoveStaleEdges( sourceId, currentEdges );
        snapshotBuilder.invalidateCache();
    }

    @Override
    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        return edges.getEdgesForNode( nodeId, direction );
    }

    @Override
    public List< com.wikantik.api.knowledge.NodeMention > getMentionsForNode(
            final UUID nodeId, final int limit ) {
        if ( nodeId == null ) return List.of();
        final List< com.wikantik.api.knowledge.NodeMention > real =
            chunks.findMentionsForNode( nodeId, limit ).stream()
                .map( r -> new com.wikantik.api.knowledge.NodeMention(
                    r.chunkId(), r.pageName(), r.chunkIndex(),
                    r.headingPath(), r.text(), r.confidence(), r.extractor() ) )
                .toList();
        if ( !real.isEmpty() ) return real;

        // Fallback: many concept nodes are auto-created as edge endpoints by
        // KgMaterializationService and never visited by the per-chunk entity
        // extractor — so they have zero rows in chunk_entity_mentions. Show
        // the curator the chunks on the originating proposal's source_page
        // that literally contain the entity name, tagged with extractor
        // 'edge-proposal-fallback' so the UI can label them as inferred. No
        // rows are inserted; this is purely a read-time projection.
        final KgNode node = nodes.getNode( nodeId );
        if ( node == null || node.provenanceProposalId() == null ) return List.of();
        final com.wikantik.api.knowledge.KgProposal proposal =
            proposals.getProposal( node.provenanceProposalId() );
        if ( proposal == null
                || proposal.sourcePage() == null
                || proposal.sourcePage().isBlank() ) return List.of();
        return chunks.findChunksOnPageContaining( proposal.sourcePage(), node.name(), limit ).stream()
            .map( c -> new com.wikantik.api.knowledge.NodeMention(
                c.chunkId(), c.pageName(), c.chunkIndex(),
                c.headingPath(), c.text(),
                proposal.confidence(),
                "edge-proposal-fallback" ) )
            .toList();
    }

    @Override
    public List< Map< String, Object > > queryEdges( final String relationshipType,
                                                      final String searchName,
                                                      final int limit, final int offset ) {
        return queryEdges( relationshipType, searchName, null, limit, offset );
    }

    @Override
    public List< Map< String, Object > > queryEdges( final String relationshipType,
                                                      final String searchName,
                                                      final String endpointKind,
                                                      final int limit, final int offset ) {
        return edges.queryEdgesWithNames( relationshipType, searchName, endpointKind, limit, offset );
    }

    @Override
    public long countEdges( final String relationshipType, final String searchName ) {
        return countEdges( relationshipType, searchName, null );
    }

    @Override
    public long countEdges( final String relationshipType, final String searchName,
                             final String endpointKind ) {
        return edges.countEdgesWithFilter( relationshipType, searchName, endpointKind );
    }

    @Override
    public KgEdge getEdge( final UUID id ) {
        return edges.findById( id );
    }

    @Override
    public void deleteEdgeAndRecordRejection( final UUID edgeId, final String actor, final String reason ) {
        edges.deleteEdgeAndRecordRejection( edgeId, actor, reason );
        snapshotBuilder.invalidateCache();
    }

    @Override
    public KgEdge confirmEdge( final UUID edgeId, final String actor ) {
        if ( edgeId == null ) return null;
        final KgEdge before = edges.findById( edgeId );
        if ( before == null ) return null;
        final KgEdge after = edges.elevateToHumanCurated( edgeId );
        if ( after == null ) return null;
        edgeAudit.insert( edgeId, "CONFIRM",
            Map.of( "tier", before.tier() == null ? "" : before.tier(),
                    "provenance", before.provenance().value() ),
            Map.of( "tier", "human",
                    "provenance", after.provenance().value() ),
            actor, null );
        snapshotBuilder.invalidateCache();
        return after;
    }

    @Override
    public int bulkDeleteEdges( final String relationshipType, final String searchName,
                                final int expectedCount ) {
        return bulkDeleteEdges( relationshipType, searchName, null, expectedCount );
    }

    @Override
    public int bulkDeleteEdges( final String relationshipType, final String searchName,
                                final String endpointKind, final int expectedCount ) {
        final long actual = edges.countEdgesWithFilter( relationshipType, searchName, endpointKind );
        if ( actual != expectedCount ) {
            throw new IllegalStateException( "expected " + expectedCount
                    + " rows, found " + actual + " — re-confirm before retrying" );
        }
        final int deleted = edges.bulkDeleteByFilter( relationshipType, searchName, endpointKind );
        snapshotBuilder.invalidateCache();
        return deleted;
    }

    @Override
    public List< Map< String, Object > > getEdgeAudit( final UUID edgeId, final int limit ) {
        return edgeAudit.findByEdgeId( edgeId, limit );
    }

    /**
     * Package-private accessor for the edge audit repository. Used by the admin resource
     * layer to write audit rows directly via the same repo instance.
     */
    public KgEdgeAuditRepository getEdgeAuditRepository() { return edgeAudit; }

    @Override
    public Map< UUID, String > getNodeNames( final Collection< UUID > ids ) {
        return nodes.getNodeNames( ids );
    }

    // --- Traversal (delegates to KgGraphTraversal) ---

    @Override
    public TraversalResult traverse( final String startNodeName, final String direction,
                                     final Set< String > relationshipTypes, final int maxDepth,
                                     final Set< Provenance > provenanceFilter ) {
        return traversal.traverse( startNodeName, direction, relationshipTypes, maxDepth, provenanceFilter );
    }

    @Override
    public TraversalResult traverseByCoMention( final String startNodeName,
                                                 final int maxDepth,
                                                 final int minSharedChunks ) {
        return traversal.traverseByCoMention( startNodeName, maxDepth, minSharedChunks, Tier.MACHINE );
    }

    @Override
    public TraversalResult traverseByCoMention( final String startNodeName, final int maxDepth,
                                                 final int minSharedChunks, final Tier minTier ) {
        return traversal.traverseByCoMention( startNodeName, maxDepth, minSharedChunks, minTier );
    }

    // --- Search ---

    @Override
    public List< KgNode > searchKnowledge( final String query, final Set< Provenance > provenanceFilter,
                                           final int limit ) {
        return searchKnowledge( query, provenanceFilter, limit, Tier.MACHINE );
    }

    @Override
    public List< KgNode > searchKnowledge( final String query, final Set< Provenance > provenanceFilter,
                                           final int limit, final Tier minTier ) {
        return nodes.searchNodes( query, provenanceFilter, limit, minTier );
    }

    @Override
    public List< KgNode > searchKnowledge( final String query, final Set< Provenance > provenanceFilter,
                                           final int limit, final boolean adminBypass ) {
        return nodes.searchNodes( query, provenanceFilter, limit, adminBypass );
    }

    // --- Proposal management ---

    @Override
    public KgProposal submitProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        if ( "new-edge".equals( proposalType ) && proposedData != null ) {
            final String source = Objects.toString( proposedData.get( "source" ), null );
            final String target = Objects.toString( proposedData.get( "target" ), null );
            final String relationship = Objects.toString( proposedData.get( "relationship" ), null );
            if ( source != null && target != null && relationship != null
                    && rejections.isRejected( source, target, relationship ) ) {
                LOG.info( "Proposal rejected: {}->{} [{}] was previously rejected",
                        source, target, relationship );
                return null;
            }
        }
        return proposals.insertProposal( proposalType, sourcePage, proposedData, confidence, reasoning );
    }

    @Override
    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                             final int limit, final int offset ) {
        return proposals.listProposals( status, sourcePage, limit, offset );
    }

    @Override
    public KgProposal getProposal( final UUID proposalId ) {
        return proposals.getProposal( proposalId );
    }

    @Override
    public List< KgProposal > listProposals( final String status, final String tier,
                                             final String machineStatus,
                                             final boolean includeMachineRejected,
                                             final String sourcePage,
                                             final int limit, final int offset ) {
        return proposals.listProposalsFiltered( status, tier, machineStatus, includeMachineRejected,
            sourcePage, limit, offset );
    }

    @Override
    public long countProposals( final String status, final String tier,
                                final String machineStatus,
                                final boolean includeMachineRejected,
                                final String sourcePage ) {
        return proposals.countProposalsFiltered( status, tier, machineStatus, includeMachineRejected,
            sourcePage );
    }

    @Override
    public List< KgProposalReview > listReviews( final UUID proposalId ) {
        return proposals.listReviews( proposalId );
    }

    @Override
    public KgProposal approveProposal( final UUID proposalId, final String reviewedBy ) {
        final KgProposal existing = proposals.getProposal( proposalId );
        if ( existing == null ) {
            return null;
        }
        if ( !"pending".equals( existing.status() ) ) {
            throw new IllegalStateException(
                "proposal already reviewed: status=" + existing.status() );
        }
        proposals.applyHumanVerdict( proposalId, "approved", reviewedBy );
        proposals.recordReview( proposalId, KgProposalReview.REVIEWER_HUMAN, reviewedBy,
            "approved", null, null );
        final KgProposal proposal = proposals.getProposal( proposalId );
        if ( proposal != null && materialization != null ) {
            materialization.promoteToHuman( proposal );
            snapshotBuilder.invalidateCache();
        }
        return proposal;
    }

    @Override
    public KgProposal rejectProposal( final UUID proposalId, final String reviewedBy, final String reason ) {
        final KgProposal proposal = proposals.getProposal( proposalId );
        if ( proposal == null ) {
            return null;
        }
        if ( !"pending".equals( proposal.status() ) ) {
            throw new IllegalStateException(
                "proposal already reviewed: status=" + proposal.status() );
        }
        proposals.applyHumanVerdict( proposalId, "rejected", reviewedBy );
        proposals.recordReview( proposalId, KgProposalReview.REVIEWER_HUMAN, reviewedBy,
            "rejected", null, reason );

        if ( proposal != null ) {
            if ( materialization != null ) {
                materialization.retract( proposal );
                snapshotBuilder.invalidateCache();
            }
            if ( "new-edge".equals( proposal.proposalType() ) && proposal.proposedData() != null ) {
                final String source = Objects.toString( proposal.proposedData().get( "source" ), null );
                final String target = Objects.toString( proposal.proposedData().get( "target" ), null );
                final String relationship = Objects.toString( proposal.proposedData().get( "relationship" ), null );
                if ( source != null && target != null && relationship != null ) {
                    rejections.insertRejection( source, target, relationship, reviewedBy, reason );
                }
            }
        }
        return proposals.getProposal( proposalId );
    }

    // --- Rejection queries ---

    @Override
    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                               final String relationshipType ) {
        return rejections.listRejections( sourceName, targetName, relationshipType );
    }

    @Override
    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        return rejections.isRejected( sourceName, targetName, relationshipType );
    }

    @Override
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
        snapshotBuilder.invalidateCache();
    }

    // --- Graph visualization (delegates to KgGraphSnapshotBuilder) ---

    @Override
    public GraphSnapshot snapshotGraph( final Session viewer ) {
        return snapshotBuilder.snapshotFor( viewer, Tier.MACHINE );
    }

    @Override
    public GraphSnapshot snapshotGraph( final Session viewer, final Tier minTier ) {
        return snapshotBuilder.snapshotFor( viewer, minTier );
    }

    // --- Synchronous judge trigger ---

    @Override
    public JudgeVerdict judgeNow( final UUID proposalId, final String triggeredBy ) {
        if ( judgeService == null ) {
            throw new IllegalStateException( "judgeService not configured" );
        }
        final KgProposal proposal = proposals.getProposal( proposalId );
        if ( proposal == null ) {
            throw new IllegalArgumentException( "no proposal: " + proposalId );
        }
        final JudgeVerdict v = judgeService.judge( proposal );
        proposals.applyMachineVerdict( proposalId, v.verdict(), v.confidence(), v.model() );
        proposals.recordReview( proposalId, KgProposalReview.REVIEWER_MACHINE, v.model(),
            v.verdict(), v.confidence(), v.rationale() );
        if ( JudgeVerdict.APPROVED.equals( v.verdict() ) && materialization != null ) {
            materialization.materializeMachine( proposal );
            snapshotBuilder.invalidateCache();
        } else if ( JudgeVerdict.REJECTED.equals( v.verdict() )
                && "new-edge".equals( proposal.proposalType() ) ) {
            final var data = proposal.proposedData();
            final String src = Objects.toString( data.get( "source" ), null );
            final String tgt = Objects.toString( data.get( "target" ), null );
            final String rel = Objects.toString( data.get( "relationship" ), null );
            if ( src != null && tgt != null && rel != null ) {
                rejections.insertRejection( src, tgt, rel, v.model(), v.rationale() );
            }
        }
        LOG.info( "judgeNow proposal={} verdict={} triggeredBy={}", proposalId, v.verdict(), triggeredBy );
        return v;
    }

    // --- Judge runner status ---

    @Override
    public long countPendingUnjudgedProposals() {
        return proposals.countPendingUnjudgedProposals();
    }
}
