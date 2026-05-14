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

    /**
     * Counts nodes that would be returned by {@link #queryNodes} with the same filters.
     * Used by the admin Node Explorer footer; the AdminTable bulk-delete confirm reads
     * the total alongside the visible page to give the operator the actual scope.
     */
    long countNodes( Map< String, Object > filters, Set< Provenance > provenanceFilter );

    /**
     * Admin-only listing of orphaned knowledge-graph nodes — nodes with no incident
     * edges (neither incoming nor outgoing in {@code kg_edges}). Always runs with the
     * {@code kg_excluded_pages} filter bypassed so curators can find disconnected
     * entities regardless of whether their source page is currently admitted by the
     * cluster inclusion policy.
     *
     * <p>Supported filter keys (all optional, additive):</p>
     * <ul>
     *   <li>{@code node_type} — exact match on {@code n.node_type}</li>
     *   <li>{@code source_page} — exact match on {@code n.source_page}</li>
     *   <li>{@code source_page_excluded} — three-state {@link Boolean}:
     *       {@code true} keeps only nodes whose source page is on {@code kg_excluded_pages},
     *       {@code false} keeps only nodes whose source page is NOT on the table,
     *       {@code null} (or absent) returns both.</li>
     * </ul>
     *
     * <p>Stub nodes (no {@code source_page}) are returned regardless of the
     * {@code source_page_excluded} filter — they cannot be classified either way.</p>
     */
    List< KgNode > listOrphanedNodes( Map< String, Object > filters, int limit, int offset );

    /**
     * Counts orphaned nodes that would be returned by
     * {@link #listOrphanedNodes(Map, int, int)} with the same filters. Used by the
     * admin tooling to size cleanup work alongside the paginated listing.
     */
    long countOrphanedNodes( Map< String, Object > filters );

    // --- Edge operations ---

    KgEdge upsertEdge( UUID sourceId, UUID targetId, String relationshipType,
                       Provenance provenance, Map< String, Object > properties );

    void deleteEdge( UUID id );

    /** Remove all human-authored edges from sourceId that are NOT in the given set of (targetName, relationshipType). */
    void diffAndRemoveStaleEdges( UUID sourceId, Set< Map.Entry< String, String > > currentEdges );

    List< KgEdge > getEdgesForNode( UUID nodeId, String direction );

    /**
     * Returns up to {@code limit} content chunks that mention the given node,
     * ranked by extractor confidence DESC, then chunk_index ASC. Surfaced in
     * the admin Edge Explorer so curators can see the surrounding passage and
     * disambiguate the node (especially acronyms / initialisms) without
     * leaving the page. Empty list if the node has no mention rows.
     */
    List< NodeMention > getMentionsForNode( UUID nodeId, int limit );

    /**
     * Elevates an existing edge to human-curated status in place: sets
     * {@code tier='human'} and {@code provenance='human-curated'}, writes a
     * {@code 'confirm'} row to {@code kg_edge_audit}. Idempotent — calling on
     * an already-curated edge is a no-op apart from refreshing
     * {@code modified} and writing a new audit row. Returns the updated edge,
     * or {@code null} if the id doesn't exist.
     */
    KgEdge confirmEdge( UUID edgeId, String actor );

    /** Query edges with resolved source/target names for display. */
    List< Map< String, Object > > queryEdges( String relationshipType, String searchName,
                                               int limit, int offset );

    /**
     * Same as {@link #queryEdges(String, String, int, int)} but with an additional
     * {@code endpointKind} filter: {@code "page"} keeps only edges whose endpoints
     * are both wiki-page-like (node_type != 'concept'), {@code "entity"} keeps only
     * edges between LLM-extracted concepts, anything else (or null) returns the union.
     */
    List< Map< String, Object > > queryEdges( String relationshipType, String searchName,
                                               String endpointKind, int limit, int offset );

    /**
     * Counts edges that would be returned by {@link #queryEdges} with the same filter
     * arguments. Used by the admin UI to render a total alongside the paginated list.
     */
    long countEdges( String relationshipType, String searchName );

    /** Counts edges with the same semantics as {@link #queryEdges(String, String, String, int, int)}. */
    long countEdges( String relationshipType, String searchName, String endpointKind );

    /**
     * Returns a single edge by id, or null if missing. Used for audit before-state
     * capture in the admin curation flow.
     */
    KgEdge getEdge( UUID id );

    /**
     * Deletes an edge and, in the same transaction, inserts a kg_rejections row so
     * a future re-extraction proposing the same triple will be auto-rejected.
     * Idempotent on the rejection insert.
     */
    void deleteEdgeAndRecordRejection( UUID edgeId, String actor, String reason );

    /**
     * Bulk-deletes every edge matching the same filter that {@link #queryEdges} applies.
     * The caller's {@code expectedCount} is compared with the actual matched-row count
     * before deletion; a mismatch raises {@link IllegalStateException} to signal snapshot
     * drift back to the operator.
     *
     * @return the number of rows deleted
     */
    int bulkDeleteEdges( String relationshipType, String searchName, int expectedCount );

    /** Bulk-deletes edges with the same semantics as {@link #queryEdges(String, String, String, int, int)}. */
    int bulkDeleteEdges( String relationshipType, String searchName, String endpointKind, int expectedCount );

    /**
     * Returns up to {@code limit} audit rows for the given edge id, newest first.
     * Empty list if the edge has no audit history (or never existed).
     */
    List< Map< String, Object > > getEdgeAudit( UUID edgeId, int limit );

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

    // --- Admin-bypass overloads ---

    /**
     * Like {@link #queryNodes(Map, Set, int, int)} but, when {@code adminBypass} is {@code true},
     * omits the {@code kg_excluded_pages} filter so admin curators see nodes from all pages.
     */
    List< KgNode > queryNodes( Map< String, Object > filters, Set< Provenance > provenanceFilter,
                               int limit, int offset, boolean adminBypass );

    /**
     * Like {@link #searchKnowledge(String, Set, int)} but, when {@code adminBypass} is {@code true},
     * omits the {@code kg_excluded_pages} filter so admin curators see nodes from all pages.
     */
    List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter,
                                    int limit, boolean adminBypass );

    /**
     * Like {@link #getNode(UUID)} but, when {@code adminBypass} is {@code true},
     * omits the {@code kg_excluded_pages} filter so admin curators can retrieve nodes
     * from excluded pages.
     */
    KgNode getNode( UUID id, boolean adminBypass );

    /**
     * Like {@link #getNodeByName(String)} but, when {@code adminBypass} is {@code true},
     * omits the {@code kg_excluded_pages} filter so admin curators can retrieve nodes
     * from excluded pages.
     */
    KgNode getNodeByName( String name, boolean adminBypass );

    // --- Search ---

    List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter, int limit );

    // --- Proposal management ---

    KgProposal submitProposal( String proposalType, String sourcePage,
                               Map< String, Object > proposedData,
                               double confidence, String reasoning );

    List< KgProposal > listProposals( String status, String sourcePage, int limit, int offset );

    /** Fetches a single proposal by id, or {@code null} if not found. */
    KgProposal getProposal( UUID proposalId );

    /** Extended filter overload — supports tier, machine_status, and include_machine_rejected. */
    List< KgProposal > listProposals( String status, String tier, String machineStatus,
                                      boolean includeMachineRejected, String sourcePage,
                                      int limit, int offset );

    /**
     * Returns the total count matching the same filters as {@link #listProposals}
     * (the extended overload), with {@code limit}/{@code offset} ignored. Used
     * by the paginated admin queue to render "Showing X–Y of Z".
     */
    long countProposals( String status, String tier, String machineStatus,
                         boolean includeMachineRejected, String sourcePage );

    /** Returns the audit history of review actions for a single proposal. */
    List< KgProposalReview > listReviews( UUID proposalId );

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

    // --- Tier-aware overloads (T13) ---

    GraphSnapshot snapshotGraph( com.wikantik.api.core.Session viewer, Tier minTier );

    List< KgNode > searchKnowledge( String query, java.util.Set< Provenance > provenanceFilter,
                                     int limit, Tier minTier );

    TraversalResult traverseByCoMention( String startNodeName, int maxDepth, int minSharedChunks, Tier minTier );

    // --- Synchronous judge trigger (impl in T15) ---

    JudgeVerdict judgeNow( java.util.UUID proposalId, String triggeredBy );

    // --- Judge runner status ---

    /**
     * Returns the number of pending proposals that have not yet been evaluated
     * by the machine judge (machine_status IS NULL).
     */
    long countPendingUnjudgedProposals();
}
