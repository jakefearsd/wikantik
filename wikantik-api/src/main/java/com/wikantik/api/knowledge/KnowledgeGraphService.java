package com.wikantik.api.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service interface for the knowledge graph. Provides schema discovery,
 * node/edge CRUD, graph traversal, and proposal management.
 * Shared by the consumption MCP, admin REST endpoints, and admin UI.
 */
public interface KnowledgeGraphService {

    // --- Schema discovery ---

    SchemaDescription discoverSchema();

    // --- Node operations ---

    KgNode getNode( UUID id );

    KgNode getNodeByName( String name );

    KgNode upsertNode( String name, String nodeType, String sourcePage,
                       Provenance provenance, Map< String, Object > properties );

    void deleteNode( UUID id );

    /** Merge duplicate nodes: moves all edges from sourceId to targetId, then deletes sourceId. */
    void mergeNodes( UUID sourceId, UUID targetId );

    List< KgNode > queryNodes( Map< String, Object > filters, Set< Provenance > provenanceFilter,
                               int limit, int offset );

    // --- Edge operations ---

    KgEdge upsertEdge( UUID sourceId, UUID targetId, String relationshipType,
                       Provenance provenance, Map< String, Object > properties );

    void deleteEdge( UUID id );

    /** Remove all human-authored edges from sourceId that are NOT in the given set of (targetName, relationshipType). */
    void diffAndRemoveStaleEdges( UUID sourceId, Set< Map.Entry< String, String > > currentEdges );

    List< KgEdge > getEdgesForNode( UUID nodeId, String direction );

    // --- Traversal ---

    TraversalResult traverse( String startNodeName, String direction,
                              Set< String > relationshipTypes, int maxDepth,
                              Set< Provenance > provenanceFilter );

    // --- Search ---

    List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter, int limit );

    // --- Proposal management ---

    KgProposal submitProposal( String proposalType, String sourcePage,
                               Map< String, Object > proposedData,
                               double confidence, String reasoning );

    List< KgProposal > listProposals( String status, String sourcePage, int limit, int offset );

    KgProposal approveProposal( UUID proposalId, String reviewedBy );

    KgProposal rejectProposal( UUID proposalId, String reviewedBy, String reason );

    // --- Rejection queries ---

    List< KgRejection > listRejections( String sourceName, String targetName,
                                        String relationshipType );

    boolean isRejected( String sourceName, String targetName, String relationshipType );
}
