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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.*;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.SummaryExtractor;
import com.wikantik.knowledge.TagExtractor;
import com.wikantik.knowledge.TitleDeriver;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * REST servlet for knowledge graph administration.
 * <p>
 * Currently protected by {@link AdminAuthFilter} (requires Admin role).
 * When a dedicated {@code knowledge-admin} role is needed for non-admin domain experts,
 * add a {@code KnowledgeAdminFilter} that checks for either Admin or knowledge-admin role.
 * <p>
 * Mapped to {@code /admin/knowledge-graph/*}. Protected by {@link AdminAuthFilter}.
 * <ul>
 *   <li>{@code GET  /admin/knowledge-graph/schema} — discover schema</li>
 *   <li>{@code GET  /admin/knowledge-graph/nodes} — query nodes (?node_type=...&amp;limit=...&amp;offset=...)</li>
 *   <li>{@code GET  /admin/knowledge-graph/nodes/{name}} — get node by name</li>
 *   <li>{@code GET  /admin/knowledge-graph/edges/{nodeId}} — get edges for node (?direction=both)</li>
 *   <li>{@code GET  /admin/knowledge-graph/proposals} — list proposals (?status=pending&amp;limit=50)</li>
 *   <li>{@code POST /admin/knowledge-graph/proposals/{id}/approve} — approve a proposal</li>
 *   <li>{@code POST /admin/knowledge-graph/proposals/{id}/reject} — reject a proposal (body: {reason})</li>
 *   <li>{@code POST /admin/knowledge-graph/nodes} — upsert node (manual curation)</li>
 *   <li>{@code DELETE /admin/knowledge-graph/nodes/{id}} — delete a node</li>
 *   <li>{@code POST /admin/knowledge-graph/nodes/merge} — merge two nodes (body: {sourceId, targetId})</li>
 *   <li>{@code POST /admin/knowledge-graph/edges} — upsert edge (manual curation, stamps HUMAN_CURATED)</li>
 *   <li>{@code POST /admin/knowledge-graph/edges/bulk-delete} — bulk delete by filter</li>
 *   <li>{@code POST /admin/knowledge-graph/edges/{id}/delete-and-reject} — delete + write rejection</li>
 *   <li>{@code GET  /admin/knowledge-graph/edges/{id}/audit} — list edge audit rows</li>
 *   <li>{@code DELETE /admin/knowledge-graph/edges/{id}} — delete an edge</li>
 *   <li>{@code GET  /admin/knowledge-graph/judge-timeouts} — list chronic-timeout proposals (?limit=50)</li>
 *   <li>{@code DELETE /admin/knowledge-graph/judge-timeouts/{proposal_id}} — clear a tracked timeout</li>
 * </ul>
 */
public class AdminKnowledgeResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminKnowledgeResource.class );
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private KnowledgeGraphService getKnowledgeService( final HttpServletResponse response ) throws IOException {
        final KnowledgeGraphService service = getSubsystems().knowledge().kgService();
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Knowledge graph is not configured" );
        }
        return service;
    }

    /**
     * One action for one HTTP verb on one resource. See {@link #resources}.
     */
    @FunctionalInterface
    private interface ResourceAction {
        void invoke( KnowledgeGraphService service, HttpServletRequest request,
                     HttpServletResponse response, String[] segments ) throws IOException;
    }

    /**
     * Declarative binding of a URL resource (e.g. {@code nodes}) to the actions it supports.
     * A {@code null} slot means the verb is not supported for that resource; the dispatcher
     * returns 404 "Unknown resource" rather than 405, which matches the pre-refactor behavior
     * where unsupported verbs simply fell through the switch's {@code default} case.
     */
    private record Resource( ResourceAction get, ResourceAction post, ResourceAction delete ) {
        static Resource get( final ResourceAction a )    { return new Resource( a, null, null ); }
        static Resource post( final ResourceAction a )   { return new Resource( null, a, null ); }
    }

    /**
     * Dispatch table: the single source of truth for which URL resources and verbs this
     * servlet supports. Adding a new endpoint is a one-line change here plus one handler
     * method. Lambdas adapt each handler's specific signature to the common
     * {@link ResourceAction} contract.
     */
    private transient Map< String, Resource > resources = buildResources();

    private Map< String, Resource > buildResources() {
        final Map< String, Resource > m = new LinkedHashMap<>();
        m.put( "schema", Resource.get(
                ( svc, req, resp, seg ) -> handleGetSchema( svc, resp ) ) );
        m.put( "nodes", new Resource(
                this::handleGetNodes,
                this::handlePostNode,
                ( svc, req, resp, seg ) -> handleDeleteNode( svc, resp, seg[ 1 ] ) ) );
        m.put( "edges", new Resource(
                this::handleGetEdges,
                this::handlePostEdgeDispatch,
                ( svc, req, resp, seg ) -> handleDeleteEdge( svc, req, resp, seg[ 1 ] ) ) );
        m.put( "proposals", new Resource(
                ( svc, req, resp, seg ) -> handleGetProposals( svc, req, resp, seg ),
                this::handlePostProposal,
                null ) );
        m.put( "embeddings", new Resource(
                ( svc, req, resp, seg ) -> handleGetEmbeddings( req, resp, seg ),
                ( svc, req, resp, seg ) -> handlePostEmbeddings( resp, seg ),
                null ) );
        m.put( "pages-without-frontmatter", Resource.get(
                ( svc, req, resp, seg ) -> handleGetPagesWithoutFrontmatter( req, resp ) ) );
        m.put( "hub-proposals", new Resource(
                ( svc, req, resp, seg ) -> handleGetHubProposals( req, resp ),
                ( svc, req, resp, seg ) -> handlePostHubProposals( req, resp, seg ),
                null ) );
        m.put( "backfill-frontmatter", new Resource(
                ( svc, req, resp, seg ) -> handleGetBackfillStatus( resp ),
                ( svc, req, resp, seg ) -> handlePostBackfillFrontmatter( resp ),
                null ) );
        m.put( "judge", new Resource(
                ( svc, req, resp, seg ) -> handleGetJudge( req, resp, seg ),
                ( svc, req, resp, seg ) -> handlePostJudge( req, resp, seg ),
                null ) );
        m.put( "judge-timeouts", new Resource(
                ( svc, req, resp, seg ) -> handleGetJudgeTimeouts( svc, req, resp ),
                null,
                ( svc, req, resp, seg ) -> handleDeleteJudgeTimeout( resp, seg ) ) );
        m.put( "clear-all", Resource.post(
                ( svc, req, resp, seg ) -> handleClearAll( svc, resp ) ) );
        m.put( "sync-hub-memberships", Resource.post(
                ( svc, req, resp, seg ) -> handlePostSyncHubMemberships( resp ) ) );
        return Map.copyOf( m );
    }

    private void readObject( final ObjectInputStream in ) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // Dispatch table holds non-serializable method references; rebuild after deserialization.
        resources = buildResources();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( request, response, Resource::get );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( request, response, Resource::post );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( request, response, Resource::delete, segments -> {
            if ( segments.length < 2 ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "ID required in path" );
                return false;
            }
            return true;
        } );
    }

    private void dispatch( final HttpServletRequest request, final HttpServletResponse response,
                           final java.util.function.Function< Resource, ResourceAction > verbPicker )
            throws IOException {
        dispatch( request, response, verbPicker, segments -> true );
    }

    /** Shared routing for all verbs. {@code precondition} is run after segments are parsed
     *  but before resource lookup; returning {@code false} skips dispatch (the precondition
     *  has already written its own error response). */
    private void dispatch( final HttpServletRequest request, final HttpServletResponse response,
                           final java.util.function.Function< Resource, ResourceAction > verbPicker,
                           final SegmentPrecondition precondition )
            throws IOException {
        final KnowledgeGraphService service = getKnowledgeService( response );
        if ( service == null ) return;

        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || "/".equals( pathInfo ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Path required" );
            return;
        }

        final String[] segments = pathInfo.substring( 1 ).split( "/" );
        if ( !precondition.check( segments ) ) return;

        final String resourceName = segments[ 0 ];
        final Resource resource = resources.get( resourceName );
        final ResourceAction action = resource == null ? null : verbPicker.apply( resource );
        if ( action == null ) {
            sendNotFound( response, "Unknown resource: " + resourceName );
            return;
        }
        action.invoke( service, request, response, segments );
    }

    @FunctionalInterface
    private interface SegmentPrecondition {
        boolean check( String[] segments ) throws IOException;
    }

    // --- GET handlers ---

    private void handleGetSchema( final KnowledgeGraphService service,
                                  final HttpServletResponse response ) throws IOException {
        sendJson( response, service.discoverSchema() );
    }

    private void handleGetNodes( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length >= 3 && "similar".equals( segments[2] ) ) {
            // GET /admin/knowledge-graph/nodes/{name}/similar?limit=10
            handleGetSimilarNodes( request, response, segments[1] );
            return;
        }
        if ( segments.length >= 2 ) {
            // GET /admin/knowledge-graph/nodes/{name}
            final String name = segments[1];
            final KgNode node = service.getNodeByName( name );
            if ( node == null ) {
                sendNotFound( response, "Node not found: " + name );
            } else {
                final Map< String, Object > result = nodeToMap( node );
                final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
                final Map< UUID, String > nameMap = resolveEdgeNames( service, edges );
                result.put( "edges", edges.stream().map( e -> enrichEdge( e, nameMap ) ).toList() );
                sendJson( response, result );
            }
        } else {
            // GET /admin/knowledge-graph/nodes?node_type=...&name=...&limit=...&offset=...
            final Map< String, Object > filters = new LinkedHashMap<>();
            if ( request.getParameter( "node_type" ) != null ) {
                filters.put( "node_type", request.getParameter( "node_type" ) );
            }
            if ( request.getParameter( "name" ) != null ) {
                filters.put( "name", request.getParameter( "name" ) );
            }
            if ( request.getParameter( "status" ) != null ) {
                filters.put( "status", request.getParameter( "status" ) );
            }
            final int limit = parseIntParam( request, "limit", 50 );
            final int offset = parseIntParam( request, "offset", 0 );
            final List< KgNode > nodes = service.queryNodes( filters, null, limit, offset );
            sendJson( response, Map.of( "nodes", nodes.stream().map( this::nodeToMap ).toList() ) );
        }
    }

    private void handleGetEdges( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length < 2 ) {
            // GET /admin/knowledge-graph/edges — list all edges (paginated, with names)
            final String relType = request.getParameter( "relationship_type" );
            final String search = request.getParameter( "search" );
            final String endpointKind = request.getParameter( "endpoint_kind" );
            final int limit = parseIntParam( request, "limit", 50 );
            final int offset = parseIntParam( request, "offset", 0 );
            final long total = service.countEdges( relType, search, endpointKind );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "edges", service.queryEdges( relType, search, endpointKind, limit, offset ) );
            result.put( "total", total );
            sendJson( response, result );
            return;
        }
        // GET /admin/knowledge-graph/edges/{id}/audit
        if ( segments.length >= 3 && "audit".equals( segments[2] ) ) {
            handleGetEdgeAudit( service, request, response, segments );
            return;
        }
        final UUID nodeId = parseUuid( segments[1], response );
        if ( nodeId == null ) return;

        final String direction = request.getParameter( "direction" ) != null
                ? request.getParameter( "direction" ) : "both";
        final List< KgEdge > edges = service.getEdgesForNode( nodeId, direction );
        final Map< UUID, String > nameMap = resolveEdgeNames( service, edges );
        sendJson( response, Map.of( "edges", edges.stream().map( e -> enrichEdge( e, nameMap ) ).toList() ) );
    }

    private void handleGetEdgeAudit( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        final UUID id = parseUuid( segments[1], response );
        if ( id == null ) return;
        final int limit = parseIntParam( request, "limit", 20 );
        sendJson( response, Map.of( "audit", service.getEdgeAudit( id, limit ) ) );
    }

    private void handleGetProposals( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        // GET /admin/knowledge-graph/proposals/{id}/reviews
        if ( segments.length >= 3 && "reviews".equals( segments[2] ) ) {
            final UUID proposalId = parseUuid( segments[1], response );
            if ( proposalId == null ) return;
            sendJson( response, Map.of( "reviews", service.listReviews( proposalId ).stream()
                .map( r -> {
                    final Map< String, Object > m = new LinkedHashMap<>();
                    m.put( "id", r.id().toString() );
                    m.put( "reviewer_kind", r.reviewerKind() );
                    m.put( "reviewer_id", r.reviewerId() );
                    m.put( "verdict", r.verdict() );
                    m.put( "confidence", r.confidence() );
                    m.put( "rationale", r.rationale() != null ? r.rationale() : "" );
                    m.put( "created", r.created().toString() );
                    return m;
                } ).toList() ) );
            return;
        }

        final String status = request.getParameter( "status" );
        final String sourcePage = request.getParameter( "source_page" );
        final String tier = request.getParameter( "tier" );
        final String machineStatus = request.getParameter( "machine_status" );
        final boolean includeMachineRejected = Boolean.parseBoolean(
            request.getParameter( "include_machine_rejected" ) );
        // Server-enforced upper bound — the UI should never request more than
        // a few hundred at a time, but the REST surface guards against
        // pathological queries (single request fetching every proposal).
        final int rawLimit = parseIntParam( request, "limit", 50 );
        final int limit = Math.max( 1, Math.min( rawLimit, MAX_PROPOSAL_PAGE_SIZE ) );
        final int offset = Math.max( 0, parseIntParam( request, "offset", 0 ) );

        final List< KgProposal > proposals;
        final long totalCount;
        if ( tier != null || machineStatus != null || includeMachineRejected ) {
            proposals = service.listProposals( status, tier, machineStatus,
                includeMachineRejected, sourcePage, limit, offset );
            totalCount = service.countProposals( status, tier, machineStatus,
                includeMachineRejected, sourcePage );
        } else {
            proposals = service.listProposals( status, sourcePage, limit, offset );
            // The simple overload doesn't expose tier / machine_status filtering;
            // route through the extended count with the equivalent defaults so
            // the totals add up to what the client filtered on.
            totalCount = service.countProposals( status, null, null, false, sourcePage );
        }
        sendJson( response, Map.of(
            "proposals", proposals.stream().map( p -> proposalToMap( service, p ) ).toList(),
            "total_count", totalCount,
            "limit", limit,
            "offset", offset
        ) );
    }

    /** Hard upper bound on a single page fetch from /admin/knowledge-graph/proposals. */
    private static final int MAX_PROPOSAL_PAGE_SIZE = 500;

    // --- POST handlers ---

    private void handlePostProposal( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        // POST /admin/knowledge-graph/proposals/bulk-action — handled before any per-id dispatch
        if ( segments.length == 2 && "bulk-action".equals( segments[1] ) ) {
            doBulkProposalAction( service, request, response );
            return;
        }
        // POST /admin/knowledge-graph/proposals — create a new proposal
        if ( segments.length == 1 ) {
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            final String proposalType = body.get( "proposal_type" ).getAsString();
            final String sourcePage = body.has( "source_page" ) ? body.get( "source_page" ).getAsString() : null;
            final Map< String, Object > proposedData = body.has( "proposed_data" )
                    ? GSON.fromJson( body.get( "proposed_data" ), MAP_TYPE ) : Map.of();
            final double confidence = body.has( "confidence" ) ? body.get( "confidence" ).getAsDouble() : 0.5;
            final String reasoning = body.has( "reasoning" ) ? body.get( "reasoning" ).getAsString() : null;
            final KgProposal proposal = service.submitProposal( proposalType, sourcePage,
                    proposedData, confidence, reasoning );
            sendJson( response, proposalToMap( proposal ) );
            return;
        }
        // POST /admin/knowledge-graph/proposals/{id}/approve or /reject
        if ( segments.length < 3 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Expected: /proposals or /proposals/{id}/approve or /proposals/{id}/reject" );
            return;
        }
        final UUID proposalId = parseUuid( segments[1], response );
        if ( proposalId == null ) return;

        final String action = segments[2];
        final String reviewedBy = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";

        switch ( action ) {
            case "approve" -> {
                final KgProposal approved = service.approveProposal( proposalId, reviewedBy );
                if ( approved == null ) {
                    sendNotFound( response, "Proposal not found: " + proposalId );
                    return;
                }
                writeFrontmatterIfEdge( approved );
                sendJson( response, proposalToMap( approved ) );
            }
            case "reject" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final String reason = body.has( "reason" ) ? body.get( "reason" ).getAsString() : null;
                final KgProposal rejected = service.rejectProposal( proposalId, reviewedBy, reason );
                if ( rejected == null ) {
                    sendNotFound( response, "Proposal not found: " + proposalId );
                    return;
                }
                sendJson( response, proposalToMap( rejected ) );
            }
            case "judge" -> {
                try {
                    final JudgeVerdict v = service.judgeNow( proposalId, reviewedBy );
                    final Map< String, Object > result = new LinkedHashMap<>();
                    result.put( "verdict", v.verdict() );
                    result.put( "confidence", v.confidence() );
                    result.put( "rationale", v.rationale() );
                    result.put( "model", v.model() );
                    sendJson( response, result );
                } catch ( final IllegalStateException e ) {
                    LOG.warn( "judgeNow refused for proposal {}: {}", proposalId, e.getMessage() );
                    sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage() );
                } catch ( final IllegalArgumentException e ) {
                    sendNotFound( response, e.getMessage() );
                }
            }
            default -> sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unknown action: " + action + ". Use 'approve', 'reject', or 'judge'." );
        }
    }

    // --- Bulk proposal action ---

    /**
     * Handles {@code POST /admin/knowledge-graph/proposals/bulk-action}.
     *
     * <p>Request body:
     * {@code { "action": "approve"|"reject"|"judge", "ids": ["uuid1", ...], "reason": "..." }}
     * (reason required for reject at the request level — missing reason → 400).
     *
     * <p>Returns a standard bulk-result envelope:
     * {@code { "succeeded": [...], "failed": [{id, error}], "status": "completed",
     * "message": "N of M proposals approved" }}.
     *
     * <p>Loops over all ids without aborting on first failure.
     * Emits a single audit log entry per bulk call.
     */
    private void doBulkProposalAction( final KnowledgeGraphService service,
                                        final HttpServletRequest request,
                                        final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String action = getJsonString( body, "action" );
        if ( action == null || action.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "action is required (supported: approve, reject, judge)" );
            return;
        }
        if ( !"approve".equals( action ) && !"reject".equals( action ) && !"judge".equals( action ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unsupported action '" + action + "' — supported: approve, reject, judge" );
            return;
        }

        // For reject: require a top-level reason (request-level invariant, not per-id).
        final String reason;
        if ( "reject".equals( action ) ) {
            reason = getJsonString( body, "reason" );
            if ( reason == null || reason.isBlank() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "reason is required for action 'reject'" );
                return;
            }
        } else {
            reason = null;
        }

        final JsonElement idsEl = body.get( "ids" );
        if ( idsEl == null || !idsEl.isJsonArray() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "ids is required and must be a JSON array" );
            return;
        }
        final JsonArray idsArr = idsEl.getAsJsonArray();
        if ( idsArr.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "ids must not be empty" );
            return;
        }

        final String actor = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";
        final List< String > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final JsonElement idEl : idsArr ) {
            final String idStr = idEl.isJsonPrimitive() ? idEl.getAsString() : null;
            if ( idStr == null || idStr.isBlank() ) {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idEl.toString() );
                f.put( "error", "id must be a non-blank string" );
                failed.add( f );
                continue;
            }
            final UUID proposalId;
            try {
                proposalId = UUID.fromString( idStr );
            } catch ( final IllegalArgumentException e ) {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idStr );
                f.put( "error", "Invalid UUID: " + idStr );
                failed.add( f );
                continue;
            }

            final java.util.Optional< String > err;
            switch ( action ) {
                case "approve" -> err = tryApproveProposal( service, proposalId, actor );
                case "reject"  -> err = tryRejectProposal( service, proposalId, actor, reason );
                default        -> err = tryJudgeProposal( service, proposalId, actor );
            }

            if ( err.isEmpty() ) {
                succeeded.add( idStr );
            } else {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idStr );
                f.put( "error", err.get() );
                failed.add( f );
            }
        }

        LOG.info( "bulk action={} resource=kg-proposals actor={} attempted={} succeeded={} failed={}",
                action, actor, idsArr.size(), succeeded.size(), failed.size() );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "succeeded", succeeded );
        result.put( "failed", failed );
        result.put( "status", "completed" );
        result.put( "message", succeeded.size() + " of " + idsArr.size() + " proposals " + action + "d" );
        sendJson( response, result );
    }

    /**
     * Tries to approve a single proposal. Returns empty on success or an error message on failure.
     * Used by both the single-item path and the bulk path to avoid duplicating logic.
     */
    private java.util.Optional< String > tryApproveProposal( final KnowledgeGraphService service,
                                                              final UUID proposalId,
                                                              final String reviewedBy ) {
        try {
            final KgProposal approved = service.approveProposal( proposalId, reviewedBy );
            if ( approved == null ) {
                return java.util.Optional.of( "Not found: " + proposalId );
            }
            writeFrontmatterIfEdge( approved );
            return java.util.Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryApproveProposal: proposal={} actor={}: {}", proposalId, reviewedBy, e.getMessage() );
            return java.util.Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
        }
    }

    /**
     * Tries to reject a single proposal. Returns empty on success or an error message on failure.
     * Used by both the single-item path and the bulk path to avoid duplicating logic.
     */
    private java.util.Optional< String > tryRejectProposal( final KnowledgeGraphService service,
                                                             final UUID proposalId,
                                                             final String reviewedBy,
                                                             final String reason ) {
        try {
            final KgProposal rejected = service.rejectProposal( proposalId, reviewedBy, reason );
            if ( rejected == null ) {
                return java.util.Optional.of( "Not found: " + proposalId );
            }
            return java.util.Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryRejectProposal: proposal={} actor={}: {}", proposalId, reviewedBy, e.getMessage() );
            return java.util.Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
        }
    }

    /**
     * Tries to judge a single proposal now. Returns empty on success or an error message on failure.
     * Judge exceptions (e.g. timeout) surface as per-id failures with a LOG.warn per CLAUDE.md.
     * Used by both the single-item path and the bulk path to avoid duplicating logic.
     */
    private java.util.Optional< String > tryJudgeProposal( final KnowledgeGraphService service,
                                                            final UUID proposalId,
                                                            final String reviewedBy ) {
        try {
            service.judgeNow( proposalId, reviewedBy );
            return java.util.Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryJudgeProposal: proposal={} actor={}: {}", proposalId, reviewedBy, e.getMessage() );
            return java.util.Optional.of( e.getMessage() != null ? e.getMessage() : "Judge error" );
        }
    }

    private void handlePostNode( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length >= 2 && "merge".equals( segments[1] ) ) {
            // POST /admin/knowledge-graph/nodes/merge
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            final UUID sourceId = UUID.fromString( body.get( "sourceId" ).getAsString() );
            final UUID targetId = UUID.fromString( body.get( "targetId" ).getAsString() );

            // Resolve names and update frontmatter BEFORE merge (edges are deleted during merge)
            final KgNode sourceNode = service.getNode( sourceId );
            final KgNode targetNode = service.getNode( targetId );
            int pagesUpdated = 0;
            if ( sourceNode != null && targetNode != null ) {
                pagesUpdated = renameFrontmatterReferences(
                        service, sourceNode.name(), targetNode.name(), sourceId );
            }

            service.mergeNodes( sourceId, targetId );
            sendJson( response, Map.of( "merged", true, "targetId", targetId.toString(),
                    "pages_updated", pagesUpdated ) );
        } else {
            // POST /admin/knowledge-graph/nodes — upsert
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            final String name = body.get( "name" ).getAsString();
            final String nodeType = body.has( "node_type" ) ? body.get( "node_type" ).getAsString() : null;
            final String sourcePage = body.has( "source_page" ) ? body.get( "source_page" ).getAsString() : null;
            final Map< String, Object > properties = body.has( "properties" )
                    ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();
            final KgNode node = service.upsertNode( name, nodeType, sourcePage,
                    Provenance.HUMAN_AUTHORED, properties );
            if ( node == null ) {
                // Insert succeeded but read-back returned null — typically because
                // source_page is in kg_excluded_pages and KgInclusionFilter hides it.
                LOG.warn( "upsertNode returned null for name='{}' source_page='{}' — " +
                    "likely filtered by KG inclusion policy", name, sourcePage );
                sendError( response, HttpServletResponse.SC_CONFLICT,
                    "node not visible after insert (excluded source page or other policy filter)" );
                return;
            }
            sendJson( response, nodeToMap( node ) );
        }
    }

    private void handlePostEdgeDispatch( final KnowledgeGraphService service,
                                         final HttpServletRequest request,
                                         final HttpServletResponse response,
                                         final String[] segments ) throws IOException {
        if ( segments.length >= 2 && "bulk-delete".equals( segments[1] ) ) {
            handlePostEdgeBulkDelete( service, request, response );
            return;
        }
        if ( segments.length >= 3 && "delete-and-reject".equals( segments[2] ) ) {
            handlePostEdgeDeleteAndReject( service, request, response, segments );
            return;
        }
        handlePostEdgeUpsert( service, request, response );
    }

    private void handlePostEdgeUpsert( final KnowledgeGraphService service,
                                       final HttpServletRequest request,
                                       final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final UUID sourceId = UUID.fromString( body.get( "source_id" ).getAsString() );
        final UUID targetId = UUID.fromString( body.get( "target_id" ).getAsString() );
        final String relType = body.get( "relationship_type" ).getAsString();
        final Map< String, Object > properties = body.has( "properties" )
                ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();

        // Capture before-state for audit (find existing edge at this triple, if any)
        Map< String, Object > before = null;
        try {
            final List< KgEdge > outbound = service.getEdgesForNode( sourceId, "outbound" );
            for ( final KgEdge e : outbound ) {
                if ( e.targetId().equals( targetId ) && relType.equals( e.relationshipType() ) ) {
                    before = edgeToMap( e );
                    break;
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "handlePostEdgeUpsert: failed to fetch before-state for audit (src={}, tgt={}, rel={}): {}",
                sourceId, targetId, relType, e.getMessage() );
        }

        final KgEdge edge;
        try {
            // Always stamp HUMAN_CURATED regardless of any provenance value in the body
            edge = service.upsertEdge( sourceId, targetId, relType,
                    Provenance.HUMAN_CURATED, properties );
        } catch ( final RuntimeException e ) {
            // Walk the cause chain for a duplicate key violation
            Throwable cause = e;
            while ( cause != null ) {
                if ( cause.getMessage() != null
                        && cause.getMessage().toLowerCase( java.util.Locale.ROOT ).contains( "duplicate key" ) ) {
                    LOG.warn( "handlePostEdgeUpsert: duplicate key for ({}, {}, {}): {}",
                        sourceId, targetId, relType, e.getMessage() );
                    sendError( response, HttpServletResponse.SC_CONFLICT,
                        "Edge already exists: " + sourceId + " -[" + relType + "]-> " + targetId );
                    return;
                }
                cause = cause.getCause();
            }
            throw e;
        }

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            final String action = before == null ? "CREATE" : "UPDATE";
            final Map< String, Object > after = edgeToMap( edge );
            try {
                audit.insert( edge.id(), action, before, after, actor( request ), null );
            } catch ( final RuntimeException e ) {
                LOG.warn( "handlePostEdgeUpsert: audit insert failed for edge {}: {}", edge.id(), e.getMessage() );
            }
        }

        sendJson( response, edgeToMap( edge ) );
    }

    private void handlePostEdgeBulkDelete( final KnowledgeGraphService service,
                                           final HttpServletRequest request,
                                           final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        if ( !body.has( "expected_count" ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "expected_count is required for bulk-delete" );
            return;
        }
        final String relType = body.has( "relationship_type" )
            ? body.get( "relationship_type" ).getAsString() : null;
        final String search = body.has( "search" ) ? body.get( "search" ).getAsString() : null;
        final String endpointKind = body.has( "endpoint_kind" )
            ? body.get( "endpoint_kind" ).getAsString() : null;
        final int expectedCount = body.get( "expected_count" ).getAsInt();

        // Snapshot before delete for per-row audit
        List< Map< String, Object > > snapshot;
        try {
            snapshot = service.queryEdges( relType, search, endpointKind, expectedCount + 1, 0 );
        } catch ( final RuntimeException e ) {
            LOG.warn( "handlePostEdgeBulkDelete: failed to snapshot edges (relType={}, search={}, endpointKind={}): {}",
                relType, search, endpointKind, e.getMessage() );
            snapshot = List.of();
        }

        final int deleted;
        try {
            deleted = service.bulkDeleteEdges( relType, search, endpointKind, expectedCount );
        } catch ( final IllegalStateException e ) {
            LOG.warn( "handlePostEdgeBulkDelete: count drift (relType={}, search={}, endpointKind={}, expected={}): {}",
                relType, search, endpointKind, expectedCount, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            return;
        }

        // Write one DELETE audit row per snapshot row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            final String reason = "bulk delete via filter: relationship_type="
                + relType + ", search=" + search;
            final String actorVal = actor( request );
            for ( final Map< String, Object > row : snapshot ) {
                try {
                    final Object idObj = row.get( "id" );
                    if ( idObj instanceof String idStr ) {
                        audit.insert( UUID.fromString( idStr ), "DELETE", row, null, actorVal, reason );
                    }
                } catch ( final RuntimeException e ) {
                    LOG.warn( "handlePostEdgeBulkDelete: audit insert failed: {}", e.getMessage() );
                }
            }
        }

        sendJson( response, Map.of( "deleted", deleted ) );
    }

    private void handlePostEdgeDeleteAndReject( final KnowledgeGraphService service,
                                                final HttpServletRequest request,
                                                final HttpServletResponse response,
                                                final String[] segments ) throws IOException {
        final UUID id = parseUuid( segments[1], response );
        if ( id == null ) return;

        final KgEdge existing = service.getEdge( id );
        if ( existing == null ) {
            sendNotFound( response, "Edge not found: " + id );
            return;
        }

        String reason = null;
        try {
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            reason = body.has( "reason" ) ? body.get( "reason" ).getAsString() : null;
        } catch ( final RuntimeException e ) {
            // Body is optional — proceed with null reason if parsing fails
            LOG.warn( "handlePostEdgeDeleteAndReject: could not parse body (edge={}): {}", id, e.getMessage() );
        }

        final String actorVal = actor( request );
        service.deleteEdgeAndRecordRejection( id, actorVal, reason );

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            try {
                audit.insert( id, "DELETE", edgeToMap( existing ),
                    Map.of( "rejected", true ), actorVal, reason );
            } catch ( final RuntimeException e ) {
                LOG.warn( "handlePostEdgeDeleteAndReject: audit insert failed for edge {}: {}",
                    id, e.getMessage() );
            }
        }

        sendJson( response, Map.of( "deleted", true, "rejected", true ) );
    }

    private void handleClearAll( final KnowledgeGraphService service,
                                 final HttpServletResponse response ) throws IOException {
        LOG.info( "Knowledge graph clearAll requested" );
        try {
            service.clearAll();
            LOG.info( "Knowledge graph clearAll completed" );
            sendJson( response, Map.of( "message", "All knowledge graph data cleared" ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to clear knowledge graph", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Clear failed: " + e.getMessage() );
        }
    }

    // --- DELETE handlers ---

    private void handleDeleteNode( final KnowledgeGraphService service,
                                   final HttpServletResponse response,
                                   final String idStr ) throws IOException {
        final UUID id = parseUuid( idStr, response );
        if ( id == null ) return;
        service.deleteNode( id );
        LOG.info( "Knowledge graph node deleted: {}", id );
        sendJson( response, Map.of( "deleted", true ) );
    }

    private void handleDeleteEdge( final KnowledgeGraphService service,
                                   final HttpServletRequest request,
                                   final HttpServletResponse response,
                                   final String idStr ) throws IOException {
        final UUID id = parseUuid( idStr, response );
        if ( id == null ) return;

        // Capture before-state for audit
        Map< String, Object > before = null;
        try {
            final KgEdge existing = service.getEdge( id );
            if ( existing != null ) {
                before = edgeToMap( existing );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "handleDeleteEdge: failed to fetch before-state for audit (id={}): {}", id, e.getMessage() );
        }

        service.deleteEdge( id );
        LOG.info( "Knowledge graph edge deleted: {}", id );

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null && before != null ) {
            try {
                audit.insert( id, "DELETE", before, null, actor( request ), null );
            } catch ( final RuntimeException e ) {
                LOG.warn( "handleDeleteEdge: audit insert failed for edge {}: {}", id, e.getMessage() );
            }
        }

        sendJson( response, Map.of( "deleted", true ) );
    }

    // --- Frontmatter write-back ---

    /**
     * After approving a {@code new-edge} proposal, writes the approved relationship
     * back into the source page's frontmatter. The subsequent page save triggers the
     * graph projector, which recognizes the edge at {@code ai-reviewed} provenance
     * and skips duplication.
     */
    @SuppressWarnings( "unchecked" )
    private void writeFrontmatterIfEdge( final KgProposal proposal ) {
        if ( !"new-edge".equals( proposal.proposalType() ) || proposal.sourcePage() == null ) {
            return;
        }

        final Map< String, Object > data = proposal.proposedData();
        if ( data == null ) return;

        final String target = ( String ) data.get( "target" );
        final String relationship = ( String ) data.get( "relationship" );
        if ( target == null || relationship == null ) return;

        try {
            final PageManager pm = getSubsystems().page().pages();
            final String pageName = proposal.sourcePage().replace( ".md", "" );
            final String pageText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
            if ( pageText == null ) {
                LOG.warn( "Cannot write-back to page '{}': page not found", pageName );
                return;
            }

            final ParsedPage parsed = FrontmatterParser.parse( pageText );
            final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );

            // Add the target to the relationship key (create if needed)
            final Object existing = metadata.get( relationship );
            if ( existing instanceof List ) {
                final List< String > list = new ArrayList<>( ( List< String > ) existing );
                if ( !list.contains( target ) ) {
                    list.add( target );
                    metadata.put( relationship, list );
                }
            } else {
                metadata.put( relationship, new ArrayList<>( List.of( target ) ) );
            }

            final String updatedText = FrontmatterWriter.write( metadata, parsed.body() );
            final PageSaveHelper saveHelper = new PageSaveHelper( getEngine(), pm );
            final SaveOptions options = SaveOptions.builder()
                    .author( "Knowledge Admin" )
                    .changeNote( "Approved knowledge proposal: " + relationship + " → " + target )
                    .build();
            saveHelper.saveText( pageName, updatedText, options );

            LOG.info( "Frontmatter write-back: added {} → {} to page '{}'", relationship, target, pageName );
        } catch ( final WikiException e ) {
            LOG.error( "Failed to write-back frontmatter for proposal {}: {}", proposal.id(), e.getMessage(), e );
        }
    }

    /**
     * Renames all frontmatter references from {@code oldName} to {@code newName}
     * across pages that have inbound edges to the given node.
     *
     * @return the number of pages whose frontmatter was updated
     */
    @SuppressWarnings( "unchecked" )
    private int renameFrontmatterReferences( final KnowledgeGraphService service,
                                             final String oldName, final String newName,
                                             final UUID oldNodeId ) {
        final PageManager pm = getSubsystems().page().pages();
        final List< KgEdge > inbound = service.getEdgesForNode( oldNodeId, "inbound" );

        // Collect unique source pages to update (multiple edges may come from the same page)
        final Set< String > pagesToUpdate = new LinkedHashSet<>();
        for ( final KgEdge edge : inbound ) {
            final KgNode srcNode = service.getNode( edge.sourceId() );
            if ( srcNode != null && srcNode.sourcePage() != null ) {
                pagesToUpdate.add( srcNode.sourcePage().replace( ".md", "" ) );
            }
        }

        int updated = 0;
        for ( final String pageName : pagesToUpdate ) {
            try {
                final String pageText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
                if ( pageText == null ) {
                    continue;
                }

                final ParsedPage parsed = FrontmatterParser.parse( pageText );
                final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
                boolean changed = false;

                for ( final Map.Entry< String, Object > entry : metadata.entrySet() ) {
                    if ( entry.getValue() instanceof List ) {
                        final List< String > list = new ArrayList<>( ( List< String > ) entry.getValue() );
                        final int idx = list.indexOf( oldName );
                        if ( idx >= 0 ) {
                            list.set( idx, newName );
                            // Remove duplicates if newName was already in the list
                            final List< String > deduped = list.stream().distinct().collect( Collectors.toList() );
                            metadata.put( entry.getKey(), deduped );
                            changed = true;
                        }
                    }
                }

                if ( changed ) {
                    final String updatedText = FrontmatterWriter.write( metadata, parsed.body() );
                    final PageSaveHelper saveHelper = new PageSaveHelper( getEngine(), pm );
                    final SaveOptions options = SaveOptions.builder()
                            .author( "Knowledge Admin" )
                            .changeNote( "Merge: renamed " + oldName + " → " + newName )
                            .build();
                    saveHelper.saveText( pageName, updatedText, options );
                    updated++;
                    LOG.info( "Merge frontmatter update: renamed {} → {} in page '{}'",
                            oldName, newName, pageName );
                }
            } catch ( final WikiException e ) {
                LOG.error( "Failed to update frontmatter for merge in page '{}': {}",
                        pageName, e.getMessage(), e );
            }
        }
        return updated;
    }

    // --- Edge name resolution ---

    private Map< UUID, String > resolveEdgeNames( final KnowledgeGraphService service,
                                                   final List< KgEdge > edges ) {
        final Set< UUID > ids = new HashSet<>();
        for ( final KgEdge e : edges ) {
            ids.add( e.sourceId() );
            ids.add( e.targetId() );
        }
        return service.getNodeNames( ids );
    }

    private Map< String, Object > enrichEdge( final KgEdge edge, final Map< UUID, String > nameMap ) {
        final Map< String, Object > m = edgeToMap( edge );
        m.put( "source_name", nameMap.getOrDefault( edge.sourceId(), edge.sourceId().toString() ) );
        m.put( "target_name", nameMap.getOrDefault( edge.targetId(), edge.targetId().toString() ) );
        return m;
    }

    // --- Helpers ---

    private Map< String, Object > nodeToMap( final KgNode node ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", node.id().toString() );
        map.put( "name", node.name() );
        map.put( "node_type", node.nodeType() );
        map.put( "source_page", node.sourcePage() );
        map.put( "provenance", node.provenance().value() );
        map.put( "properties", node.properties() );
        map.put( "is_stub", node.isStub() );
        map.put( "created", node.created() != null ? node.created().toString() : null );
        map.put( "modified", node.modified() != null ? node.modified().toString() : null );
        return map;
    }

    private Map< String, Object > edgeToMap( final KgEdge edge ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", edge.id().toString() );
        map.put( "source_id", edge.sourceId().toString() );
        map.put( "target_id", edge.targetId().toString() );
        map.put( "relationship_type", edge.relationshipType() );
        map.put( "provenance", edge.provenance().value() );
        map.put( "tier", edge.tier() );
        map.put( "properties", edge.properties() );
        map.put( "created", edge.created() != null ? edge.created().toString() : null );
        map.put( "modified", edge.modified() != null ? edge.modified().toString() : null );
        return map;
    }

    private Map< String, Object > proposalToMap( final KgProposal p ) {
        return proposalToMap( null, p );
    }

    /**
     * Serialises a proposal for the admin UI. When {@code service} is non-null,
     * also enriches with two cross-reference flags the queue uses to surface
     * conflicts before the admin acts:
     * <ul>
     *   <li>{@code node_exists} — for {@code new-node} proposals, whether the
     *       proposed name already resolves to an existing KG node (likely
     *       duplicate);</li>
     *   <li>{@code edge_previously_rejected} — for {@code new-edge} proposals,
     *       whether the same {@code (source, target, relationship)} tuple has
     *       been rejected before (so the same suggestion is being made
     *       repeatedly).</li>
     * </ul>
     * Both lookups are best-effort — exceptions are logged at WARN and the
     * flag is omitted rather than failing the whole listing.
     */
    private Map< String, Object > proposalToMap( final KnowledgeGraphService service, final KgProposal p ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", p.id().toString() );
        map.put( "proposal_type", p.proposalType() );
        map.put( "source_page", p.sourcePage() );
        map.put( "proposed_data", p.proposedData() );
        map.put( "confidence", p.confidence() );
        map.put( "reasoning", p.reasoning() );
        map.put( "status", p.status() );
        map.put( "reviewed_by", p.reviewedBy() );
        map.put( "created", p.created() != null ? p.created().toString() : null );
        map.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
        map.put( "tier", p.tier() );
        map.put( "machine_status", p.machineStatus() );
        map.put( "machine_confidence", p.machineConfidence() );
        map.put( "machine_judged_at", p.machineJudgedAt() != null ? p.machineJudgedAt().toString() : null );
        map.put( "machine_model", p.machineModel() );

        if ( service != null && p.proposedData() != null ) {
            try {
                if ( "new-node".equals( p.proposalType() ) ) {
                    final Object name = p.proposedData().get( "name" );
                    if ( name instanceof String s && !s.isBlank() ) {
                        map.put( "node_exists", service.getNodeByName( s ) != null );
                    }
                } else if ( "new-edge".equals( p.proposalType() ) ) {
                    final Object src = p.proposedData().get( "source" );
                    final Object tgt = p.proposedData().get( "target" );
                    final Object rel = p.proposedData().get( "relationship" );
                    if ( src instanceof String s && tgt instanceof String t && rel instanceof String r ) {
                        map.put( "edge_previously_rejected", service.isRejected( s, t, r ) );
                    }
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "Failed to compute conflict flags for proposal {}: {}", p.id(), e.getMessage() );
            }
        }
        return map;
    }

    private Map< String, Object > hubProposalToMap( final HubProposalRepository.HubProposal p ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", p.id() );
        map.put( "hub_name", p.hubName() );
        map.put( "page_name", p.pageName() );
        map.put( "raw_similarity", p.rawSimilarity() );
        map.put( "percentile_score", p.percentileScore() );
        map.put( "status", p.status() );
        map.put( "reason", p.reason() );
        map.put( "reviewed_by", p.reviewedBy() );
        map.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
        map.put( "created", p.created() != null ? p.created().toString() : null );
        return map;
    }


    // --- Embedding handlers ---

    private NodeMentionSimilarity getSimilarity() {
        return getSubsystems().knowledge().nodeMentionSimilarity();
    }

    private void handleGetPagesWithoutFrontmatter( final HttpServletRequest request,
                                                   final HttpServletResponse response ) throws IOException {
        final PageManager pm = getSubsystems().page().pages();
        if ( pm == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PageManager not available" );
            return;
        }
        final SystemPageRegistry spr = getSubsystems().core().systemPageRegistry();
        final int limit = parseIntParam( request, "limit", 100 );
        final int offset = parseIntParam( request, "offset", 0 );
        try {
            final List< Map< String, Object > > pages = new ArrayList<>();
            for ( final Page page : pm.getAllPages() ) {
                if ( spr != null && spr.isSystemPage( page.getName() ) ) {
                    continue;
                }
                final String text = pm.getPureText( page );
                final ParsedPage parsed = FrontmatterParser.parse( text != null ? text : "" );
                if ( parsed.metadata().isEmpty() ) {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    entry.put( "name", page.getName() );
                    entry.put( "lastModified", page.getLastModified() != null
                            ? page.getLastModified().toInstant().toString() : null );
                    pages.add( entry );
                }
            }
            pages.sort( Comparator.comparing( m -> ( String ) m.get( "name" ) ) );
            final int total = pages.size();
            final List< Map< String, Object > > paged = pages.subList(
                    Math.min( offset, total ), Math.min( offset + limit, total ) );
            sendJson( response, Map.of( "total", total, "pages", paged ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list pages without frontmatter", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed: " + e.getMessage() );
        }
    }

    private void handleGetEmbeddings( final HttpServletRequest request,
                                      final HttpServletResponse response,
                                      final String[] segments ) throws IOException {
        final NodeMentionSimilarity sim = getSimilarity();
        if ( segments.length >= 2 && "status".equals( segments[1] ) ) {
            // GET /admin/knowledge-graph/embeddings/status — reports the shared Ollama-backed
            // mention-centroid index.
            final Map< String, Object > result = new LinkedHashMap<>();
            final boolean ready = sim != null && sim.isReady();
            result.put( "ready", ready );
            result.put( "dimension", ready ? sim.dimension() : 0 );
            result.put( "mentioned_node_count", ready ? sim.mentionedNodeNames().size() : 0 );
            sendJson( response, result );
        } else {
            sendNotFound( response, "Unknown embeddings sub-resource" );
        }
    }

    private void handlePostEmbeddings( final HttpServletResponse response,
                                       final String[] segments ) throws IOException {
        // No post actions remain on /admin/knowledge-graph/embeddings — the chunk
        // embedding indexer runs continuously via AsyncEmbeddingIndexListener,
        // so there is nothing to manually retrigger here.
        sendNotFound( response, "Unknown embeddings sub-resource" );
    }

    private void handleGetSimilarNodes( final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String nodeName ) throws IOException {
        final NodeMentionSimilarity sim = getSimilarity();
        if ( sim == null || !sim.isReady() ) {
            sendJson( response, Map.of( "similar", List.of() ) );
            return;
        }
        final int limit = parseIntParam( request, "limit", 10 );
        final var ranked = sim.similarTo( nodeName, limit );
        sendJson( response, Map.of( "similar", ranked.stream().map( s -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "name", s.name() );
            m.put( "similarity", s.score() );
            return m;
        } ).toList() ) );
    }

    private UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( str );
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "Rejecting request with malformed UUID '{}': {}", str, e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID: " + str );
            return null;
        }
    }

    /**
     * Returns the audit repository accessor from the service impl, or null when
     * the service is a non-impl (e.g. Mockito mock). All audit writes are
     * best-effort and must not blow the user-facing response on failure.
     */
    private com.wikantik.knowledge.KgEdgeAuditRepository getAuditRepo(
            final KnowledgeGraphService service ) {
        return service instanceof com.wikantik.knowledge.DefaultKnowledgeGraphService impl
                ? impl.getEdgeAuditRepository() : null;
    }

    /**
     * Derives the name of the acting admin user from the request.
     * Falls back to {@code "admin"} if the container has not set a remote user.
     */
    private String actor( final HttpServletRequest request ) {
        final String remoteUser = request.getRemoteUser();
        return remoteUser != null ? remoteUser : "admin";
    }

    // --- Hub Proposals ---

    private HubProposalRepository getHubProposalRepo( final HttpServletResponse response ) throws IOException {
        final HubProposalRepository repo = getSubsystems().knowledge().hubProposalRepository();
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Hub proposals not configured" );
        }
        return repo;
    }

    private void handleGetHubProposals( final HttpServletRequest request,
                                         final HttpServletResponse response ) throws IOException {
        final HubProposalRepository repo = getHubProposalRepo( response );
        if ( repo == null ) return;

        final String status = request.getParameter( "status" ) != null
            ? request.getParameter( "status" ) : "pending";
        final String hubName = request.getParameter( "hub" );
        final int limit = parseIntParam( request, "limit", 50 );
        final int offset = parseIntParam( request, "offset", 0 );

        final var proposals = repo.listProposals( status, hubName, limit, offset );
        final int total = repo.countByStatus( status );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "total", total );
        result.put( "proposals", proposals.stream().map( this::hubProposalToMap ).toList() );
        sendJson( response, result );
    }

    @SuppressWarnings( "unchecked" )
    private void handlePostHubProposals( final HttpServletRequest request,
                                          final HttpServletResponse response,
                                          final String[] segments ) throws IOException {
        final HubProposalRepository repo = getHubProposalRepo( response );
        if ( repo == null ) return;

        if ( segments.length < 2 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Action required" );
            return;
        }

        switch ( segments[1] ) {
            case "generate"          -> handleHubProposalsGenerate( response );
            case "bulk-approve"      -> handleHubProposalsBulkApprove( request, response, repo );
            case "bulk-reject"       -> handleHubProposalsBulkReject( request, response, repo );
            case "threshold-approve" -> handleHubProposalsThresholdApprove( request, response, repo );
            default                  -> handleHubProposalByIdAction( request, response, segments, repo );
        }
    }

    private void handleHubProposalsGenerate( final HttpServletResponse response ) throws IOException {
        LOG.info( "Hub proposals: generate endpoint invoked" );
        final NodeMentionSimilarity sim = getSubsystems().knowledge().nodeMentionSimilarity();
        if ( sim == null ) {
            LOG.warn( "Hub proposals generate rejected: NodeMentionSimilarity not registered "
                + "(knowledge graph initialization likely failed — see earlier WARN)" );
            sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                "NodeMentionSimilarity not available — knowledge graph not initialized" );
            return;
        }
        if ( !sim.isReady() ) {
            LOG.warn( "Hub proposals generate rejected: mention-centroid index not populated yet" );
            sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                "Chunk embedding index must be populated before generating proposals" );
            return;
        }
        final HubProposalService service = getSubsystems().knowledge().hubProposalService();
        if ( service == null ) {
            LOG.warn( "Hub proposals generate rejected: HubProposalService not registered "
                + "(knowledge graph initialization likely failed — see earlier WARN)" );
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubProposalService not available — knowledge graph not initialized" );
            return;
        }
        try {
            final int created = service.generateProposals();
            LOG.info( "Hub proposals generate: completed successfully, {} proposal(s) created", created );
            sendJson( response, Map.of(
                "status", "ok",
                "created", created,
                "message", "Hub proposals generated: " + created + " created" ) );
        } catch ( final RuntimeException e ) {
            LOG.error( "Hub proposals generate failed", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub proposals generation failed: " + e.getMessage() );
        }
    }

    private void handleHubProposalsBulkApprove( final HttpServletRequest request,
                                                 final HttpServletResponse response,
                                                 final HubProposalRepository repo ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final List< Integer > ids = GSON.fromJson( body.get( "ids" ),
            new TypeToken< List< Integer > >() {}.getType() );
        final String reviewedBy = optString( body, "reviewedBy", "admin" );
        repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
        sendJson( response, Map.of( "status", "ok" ) );
    }

    private void handleHubProposalsBulkReject( final HttpServletRequest request,
                                                final HttpServletResponse response,
                                                final HubProposalRepository repo ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final List< Integer > ids = GSON.fromJson( body.get( "ids" ),
            new TypeToken< List< Integer > >() {}.getType() );
        final String reviewedBy = optString( body, "reviewedBy", "admin" );
        final String reason = optString( body, "reason", null );
        repo.bulkUpdateStatus( ids, "rejected", reviewedBy, reason );
        sendJson( response, Map.of( "status", "ok" ) );
    }

    private void handleHubProposalsThresholdApprove( final HttpServletRequest request,
                                                      final HttpServletResponse response,
                                                      final HubProposalRepository repo ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final double threshold = body.get( "threshold" ).getAsDouble();
        final String reviewedBy = optString( body, "reviewedBy", "admin" );
        final var ids = repo.listProposalsAboveThreshold( threshold ).stream()
            .map( HubProposalRepository.HubProposal::id ).toList();
        repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
        sendJson( response, Map.of( "status", "ok", "approved", ids.size() ) );
    }

    private void handleHubProposalByIdAction( final HttpServletRequest request,
                                                final HttpServletResponse response,
                                                final String[] segments,
                                                final HubProposalRepository repo ) throws IOException {
        if ( segments.length < 3 ) {
            sendNotFound( response, "Unknown hub-proposals action" );
            return;
        }
        final int id;
        try {
            id = Integer.parseInt( segments[1] );
        } catch ( final NumberFormatException e ) {
            LOG.info( "Rejecting hub-proposal request with non-numeric ID '{}': {}", segments[1], e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID" );
            return;
        }
        switch ( segments[2] ) {
            case "approve" -> {
                repo.updateStatus( id, "approved", "admin", null );
                sendJson( response, Map.of( "status", "ok" ) );
            }
            case "reject" -> {
                final JsonObject body = parseJsonBody( request, response );
                final String reason = body != null && body.has( "reason" )
                    ? body.get( "reason" ).getAsString() : null;
                repo.updateStatus( id, "rejected", "admin", reason );
                sendJson( response, Map.of( "status", "ok" ) );
            }
            default -> sendNotFound( response, "Unknown action: " + segments[2] );
        }
    }

    /** Returns the string value of {@code key} if present and non-null, otherwise {@code defaultValue}. */
    private static String optString( final JsonObject body, final String key, final String defaultValue ) {
        return body.has( key ) && !body.get( key ).isJsonNull()
            ? body.get( key ).getAsString() : defaultValue;
    }

    // --- Backfill ---

    private volatile boolean backfillRunning = false;
    private volatile int backfillTotal = 0;
    private final AtomicInteger backfillProcessed = new AtomicInteger();
    private final AtomicInteger backfillErrors = new AtomicInteger();

    private void handleGetBackfillStatus( final HttpServletResponse response ) throws IOException {
        sendJson( response, Map.of(
            "running", backfillRunning,
            "total", backfillTotal,
            "processed", backfillProcessed.get(),
            "errors", backfillErrors.get()
        ) );
    }

    private void handlePostBackfillFrontmatter( final HttpServletResponse response ) throws IOException {
        if ( backfillRunning ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, "Backfill already in progress" );
            return;
        }

        final Thread t = new Thread( this::runBackfill, "frontmatter-backfill" );
        t.setDaemon( true );
        t.start();
        sendJson( response, Map.of( "status", "started" ) );
    }

    @SuppressWarnings( "PMD.UnusedAssignment" ) // `backfillRunning` is read by the backfill status endpoint on other threads.
    private void runBackfill() {
        backfillRunning = true;
        backfillProcessed.set( 0 );
        backfillErrors.set( 0 );
        try {
            final PageManager pm = getSubsystems().page().pages();
            final SystemPageRegistry spr = getSubsystems().core().systemPageRegistry();
            final var allPages = pm.getAllPages();
            backfillTotal = allPages.size();

            final PageSaveHelper saveHelper = new PageSaveHelper( getEngine(), pm );

            for ( final Page page : allPages ) {
                try {
                    if ( spr != null && spr.isSystemPage( page.getName() ) ) {
                        backfillProcessed.incrementAndGet();
                        continue;
                    }
                    final String content = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                    if ( !parsed.metadata().isEmpty() ) {
                        backfillProcessed.incrementAndGet();
                        continue;
                    }

                    final String body = parsed.body();
                    final Map< String, Object > metadata = new LinkedHashMap<>();
                    metadata.put( "title", TitleDeriver.derive( page.getName() ) );
                    metadata.put( "type", "article" );
                    metadata.put( "tags", TagExtractor.extract( body, 3 ) );
                    metadata.put( "summary", SummaryExtractor.extract( body ) );
                    metadata.put( "auto-generated", Boolean.TRUE );

                    final String updated = FrontmatterWriter.write( metadata, body );
                    saveHelper.saveText( page.getName(), updated,
                        SaveOptions.builder().changeNote( "Backfill default frontmatter" ).build() );
                    backfillProcessed.incrementAndGet();
                } catch ( final Exception e ) {
                    backfillErrors.incrementAndGet();
                    LOG.warn( "Backfill failed for page '{}': {}", page.getName(), e.getMessage() );
                }
            }
        } catch ( final Exception e ) {
            LOG.error( "Backfill failed: {}", e.getMessage(), e );
        } finally {
            backfillRunning = false;
        }
    }

    private void handleGetJudge( final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length < 2 || !"status".equals( segments[1] ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Expected: /judge/status" );
            return;
        }
        final com.wikantik.knowledge.judge.JudgeRunner runner =
            getSubsystems().knowledge().judgeRunner();
        final KnowledgeGraphService svc = getSubsystems().knowledge().kgService();
        final long depth = svc == null ? 0L : svc.countPendingUnjudgedProposals();
        if ( runner == null ) {
            sendJson( response, Map.of( "configured", false, "in_flight", false, "queue_depth", depth ) );
            return;
        }
        final com.wikantik.knowledge.judge.JudgeRunner.Status s = runner.status( depth );
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "configured", true );
        body.put( "in_flight", s.inFlight() );
        body.put( "last_run_submitted", s.lastRunSubmitted() );
        body.put( "last_run_completed", s.lastRunCompleted() );
        // Use empty strings (not null) for "not applicable" timestamp / error fields.
        // Default Gson omits null map values, which makes the keys disappear from
        // the response body and breaks clients that probe for their presence.
        body.put( "last_run_started_at",
            s.lastRunStartedAt() != null ? s.lastRunStartedAt().toString() : "" );
        body.put( "last_run_finished_at",
            s.lastRunFinishedAt() != null ? s.lastRunFinishedAt().toString() : "" );
        body.put( "last_run_error", s.lastRunError() != null ? s.lastRunError() : "" );
        body.put( "queue_depth", s.queueDepth() );
        sendJson( response, body );
    }

    private void handlePostJudge( final HttpServletRequest request,
                                  final HttpServletResponse response,
                                  final String[] segments ) throws IOException {
        if ( segments.length < 2 || !"run".equals( segments[1] ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Expected: /judge/run" );
            return;
        }
        final JudgeRunner runner = getSubsystems().knowledge().judgeRunner();
        if ( runner == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "judge runner not configured" );
            return;
        }
        final Thread t = new Thread( runner::runOnceQuietly, "kg-judge-adhoc" );
        t.setDaemon( true );
        t.start();
        response.setStatus( HttpServletResponse.SC_ACCEPTED );
        sendJson( response, Map.of( "status", "started" ) );
    }

    /**
     * GET /admin/knowledge-graph/judge-timeouts?limit=N
     *
     * Lists chronic-timeout proposals (those that have read-timed out the LLM
     * judge at least once), sorted by timeout count desc. Each entry is enriched
     * with the proposal's source/target/relationship so the admin can decide
     * whether to manually approve or reject without an extra round-trip.
     */
    private void handleGetJudgeTimeouts( final KnowledgeGraphService svc,
                                         final HttpServletRequest request,
                                         final HttpServletResponse response ) throws IOException {
        final com.wikantik.knowledge.judge.KgJudgeTimeoutRepository repo =
            getSubsystems().knowledge().judgeTimeoutRepository();
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "judge timeout tracking is not configured" );
            return;
        }
        final int limit = parseIntParam( request, "limit", 50 );
        final List< com.wikantik.knowledge.judge.KgJudgeTimeoutRepository.TimeoutRow > rows =
            repo.listTopChronic( limit );

        final List< Map< String, Object > > out = new ArrayList<>( rows.size() );
        for ( final var r : rows ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "proposal_id", r.proposalId().toString() );
            m.put( "content_sha256", r.contentSha256() );
            m.put( "source_page", r.sourcePage() != null ? r.sourcePage() : "" );
            m.put( "proposal_type", r.proposalType() != null ? r.proposalType() : "" );
            m.put( "model_name", r.modelName() != null ? r.modelName() : "" );
            m.put( "content_bytes", r.contentBytes() );
            m.put( "timeout_count", r.timeoutCount() );
            m.put( "last_error_excerpt", r.lastErrorExcerpt() != null ? r.lastErrorExcerpt() : "" );
            m.put( "base_timeout_seconds", r.baseTimeoutSeconds() );
            m.put( "first_seen", r.firstSeen() != null ? r.firstSeen().toString() : "" );
            m.put( "last_seen",  r.lastSeen()  != null ? r.lastSeen().toString()  : "" );
            // Effective timeout that would be applied on next attempt — surfaces
            // the multiplier admin has been seeing.
            final int multiplier = Math.min( 1 + r.timeoutCount(),
                com.wikantik.knowledge.judge.DefaultKgProposalJudgeService.MAX_TIMEOUT_MULTIPLIER );
            m.put( "next_effective_timeout_seconds", r.baseTimeoutSeconds() * multiplier );
            // Enrich with proposal triple if the proposal still exists. Pending
            // proposals are the actionable ones; if approved/rejected/deleted
            // we still emit the row so the admin has the trail.
            try {
                final com.wikantik.api.knowledge.KgProposal p = svc.getProposal( r.proposalId() );
                if ( p != null ) {
                    final Map< String, Object > pd = p.proposedData();
                    final Map< String, Object > triple = new LinkedHashMap<>();
                    triple.put( "source", pd.get( "source" ) );
                    triple.put( "target", pd.get( "target" ) );
                    triple.put( "relationship", pd.get( "relationship" ) );
                    m.put( "proposal", Map.of(
                        "status", p.status() != null ? p.status() : "",
                        "tier", p.tier() != null ? p.tier() : "",
                        "confidence", p.confidence(),
                        "triple", triple ) );
                } else {
                    m.put( "proposal", Map.of( "status", "missing" ) );
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "judge-timeouts: proposal lookup failed for {}: {}",
                    r.proposalId(), e.getMessage() );
                m.put( "proposal", Map.of( "status", "lookup_error" ) );
            }
            out.add( m );
        }
        sendJson( response, Map.of( "timeouts", out ) );
    }

    /**
     * DELETE /admin/knowledge-graph/judge-timeouts/{proposal_id}
     *
     * Clears a tracked timeout — useful when the admin has manually resolved
     * the underlying problem (rephrased the proposal, restarted Ollama, etc.)
     * and wants the next call to start fresh at the base timeout.
     */
    private void handleDeleteJudgeTimeout( final HttpServletResponse response,
                                           final String[] segments ) throws IOException {
        final com.wikantik.knowledge.judge.KgJudgeTimeoutRepository repo =
            getSubsystems().knowledge().judgeTimeoutRepository();
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "judge timeout tracking is not configured" );
            return;
        }
        final UUID id = parseUuid( segments[ 1 ], response );
        if ( id == null ) return;
        repo.clear( id );
        sendJson( response, Map.of( "status", "cleared", "proposal_id", id.toString() ) );
    }

    private void handlePostSyncHubMemberships( final HttpServletResponse response ) throws IOException {
        try {
            final PageManager pm = getSubsystems().page().pages();
            final PageSaveHelper saveHelper = new PageSaveHelper( getEngine(), pm );
            int synced = 0;
            for ( final Page page : pm.getAllPages() ) {
                try {
                    final String content = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                    if ( "hub".equals( parsed.metadata().get( "type" ) ) ) {
                        saveHelper.saveText( page.getName(), content, SaveOptions.builder().build() );
                        synced++;
                    }
                } catch ( final Exception e ) {
                    LOG.warn( "Hub sync failed for '{}': {}", page.getName(), e.getMessage() );
                }
            }
            sendJson( response, Map.of( "status", "ok", "hubsSynced", synced ) );
        } catch ( final Exception e ) {
            LOG.warn( "Hub sync bootstrap failed: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Hub sync failed: " + e.getMessage() );
        }
    }

}
