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

    /** Query edges with resolved source/target names for display. */
    List< Map< String, Object > > queryEdges( String relationshipType, String searchName,
                                               int limit, int offset );

    /** Resolve node UUIDs to names in bulk. */
    Map< UUID, String > getNodeNames( Collection< UUID > ids );

    // --- Traversal ---

    TraversalResult traverse( String startNodeName, String direction,
                              Set< String > relationshipTypes, int maxDepth,
                              Set< Provenance > provenanceFilter );

    /**
     * Traverse the co-mention graph: starting from the named node, walk
     * through nodes that share at least {@code minSharedChunks} chunks with
     * the current frontier, up to {@code maxDepth} hops. Returns every
     * visited node plus synthetic "co-mention" edges with the shared-chunk
     * count as a property on each edge.
     *
     * <p>Replaces {@link #traverse} for agent-facing MCP use when the
     * co-mention graph (from {@code chunk_entity_mentions}) is the
     * authoritative relationship source. {@code traverse} remains for
     * admin UI / REST paths that need the full {@code kg_edges} view.</p>
     *
     * @param startNodeName   name of the seed node
     * @param maxDepth        BFS depth limit (1 = direct co-mentions only)
     * @param minSharedChunks minimum count of shared chunks required to follow
     *                        an edge (lower bound 1)
     */
    TraversalResult traverseByCoMention( String startNodeName, int maxDepth, int minSharedChunks );

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

    // --- Bulk operations ---

    /** Deletes all knowledge graph data: nodes, edges, proposals, rejections, and embeddings. */
    void clearAll();

    // --- Graph visualization ---

    /**
     * Builds a snapshot of the entire knowledge graph for visualization.
     * Nodes are classified by role (hub, normal, orphan, stub, restricted).
     * Restricted nodes have sensitive fields redacted based on the viewer's
     * page-level permissions. The underlying data may be cached; per-user
     * redaction is applied on top of the cache.
     *
     * @param viewer the user's session (used for ACL checks); must not be null
     * @return a complete snapshot of all nodes and edges
     */
    GraphSnapshot snapshotGraph( com.wikantik.api.core.Session viewer );
}
