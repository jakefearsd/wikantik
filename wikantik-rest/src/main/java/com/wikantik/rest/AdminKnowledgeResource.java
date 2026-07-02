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

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.*;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
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
import com.wikantik.rest.knowledge.AdminKnowledgeIo;
import com.wikantik.rest.knowledge.KgJudgeAdminHandlers;
import com.wikantik.rest.knowledge.KgProposalAdminHandlers;

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
 *   <li>{@code POST /admin/knowledge-graph/edges/{id}/confirm} — elevate edge to human-curated</li>
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

    private KgCurationOps getKgCurationOps() {
        return getSubsystems().knowledge().kgCurationOps();
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
        // Constructed fresh on every call (including after deserialization — see #readObject)
        // so the injected suppliers always resolve subsystems from the *current* engine/servlet
        // context, exactly like the inline getSubsystems() calls these handlers used to make
        // directly before they were extracted to com.wikantik.rest.knowledge.
        final KgProposalAdminHandlers proposalHandlers = new KgProposalAdminHandlers(
                this::getKgCurationOps );
        final KgJudgeAdminHandlers judgeHandlers = new KgJudgeAdminHandlers(
                () -> getSubsystems().knowledge().judgeRunner(),
                () -> getSubsystems().knowledge().judgeTimeoutRepository(),
                () -> getSubsystems().knowledge().kgService() );

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
                proposalHandlers::handleGetProposals,
                proposalHandlers::handlePostProposal,
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
                ( svc, req, resp, seg ) -> judgeHandlers.handleGetJudge( req, resp, seg ),
                ( svc, req, resp, seg ) -> judgeHandlers.handlePostJudge( req, resp, seg ),
                null ) );
        m.put( "judge-timeouts", new Resource(
                ( svc, req, resp, seg ) -> judgeHandlers.handleGetJudgeTimeouts( svc, req, resp ),
                null,
                ( svc, req, resp, seg ) -> judgeHandlers.handleDeleteJudgeTimeout( resp, seg ) ) );
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
        if ( segments.length >= 3 && "by-id".equals( segments[1] ) ) {
            // GET /admin/knowledge-graph/nodes/by-id/{uuid}
            // GET /admin/knowledge-graph/nodes/by-id/{uuid}/mentions?limit=N
            // ID-based lookup. Required because Tomcat rejects encoded slashes
            // in path segments by default, so a name like
            // "Automated Storage and Retrieval System (AS/RS)" can't be passed
            // through the /nodes/{name} variant — the request 400s before it
            // reaches this servlet.
            final UUID id = parseUuid( segments[2], response );
            if ( id == null ) return;
            if ( segments.length >= 4 && "mentions".equals( segments[3] ) ) {
                final int limit = parseIntParam( request, "limit", 3 );
                final List< com.wikantik.api.knowledge.NodeMention > mentions =
                    service.getMentionsForNode( id, limit );
                final List< Map< String, Object > > rows = mentions.stream()
                    .map( KnowledgeJsonMapper::mentionToMap )
                    .toList();
                final Map< String, Object > result = new LinkedHashMap<>();
                result.put( "mentions", rows );
                sendJson( response, result );
                return;
            }
            final KgNode node = service.getNode( id, true );
            if ( node == null ) {
                sendNotFound( response, "Node not found: " + id );
                return;
            }
            final Map< String, Object > result = KnowledgeJsonMapper.nodeToMap( node );
            final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
            final Map< UUID, String > nameMap = resolveEdgeNames( service, edges );
            result.put( "edges", edges.stream().map( e -> KnowledgeJsonMapper.enrichEdge( e, nameMap ) ).toList() );
            sendJson( response, result );
            return;
        }
        if ( segments.length >= 2 ) {
            // GET /admin/knowledge-graph/nodes/{name}
            final String name = segments[1];
            final KgNode node = service.getNodeByName( name, true );
            if ( node == null ) {
                sendNotFound( response, "Node not found: " + name );
            } else {
                final Map< String, Object > result = KnowledgeJsonMapper.nodeToMap( node );
                final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
                final Map< UUID, String > nameMap = resolveEdgeNames( service, edges );
                result.put( "edges", edges.stream().map( e -> KnowledgeJsonMapper.enrichEdge( e, nameMap ) ).toList() );
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
            final List< KgNode > nodes = service.queryNodes( filters, null, limit, offset, true );
            final long total = service.countNodes( filters, null );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "nodes", nodes.stream().map( KnowledgeJsonMapper::nodeToMap ).toList() );
            result.put( "total", total );
            sendJson( response, result );
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
        sendJson( response, Map.of( "edges", edges.stream().map( e -> KnowledgeJsonMapper.enrichEdge( e, nameMap ) ).toList() ) );
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

    // --- POST handlers ---

    private void handlePostNode( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        final KgCurationOps ops = getKgCurationOps();
        if ( segments.length >= 2 && "merge".equals( segments[1] ) ) {
            // POST /admin/knowledge-graph/nodes/merge
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            final String sourceIdStr = getJsonString( body, "sourceId" );
            final String targetIdStr = getJsonString( body, "targetId" );
            if ( sourceIdStr == null || targetIdStr == null ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "sourceId and targetId are required" );
                return;
            }
            final UUID sourceId = parseUuid( sourceIdStr, response );
            if ( sourceId == null ) return;
            final UUID targetId = parseUuid( targetIdStr, response );
            if ( targetId == null ) return;

            // Resolve names and update frontmatter BEFORE merge (edges are deleted during merge)
            final KgNode sourceNode = service.getNode( sourceId, true );
            final KgNode targetNode = service.getNode( targetId, true );
            int pagesUpdated = 0;
            if ( sourceNode != null && targetNode != null ) {
                pagesUpdated = renameFrontmatterReferences(
                        service, sourceNode.name(), targetNode.name(), sourceId );
            }

            final java.util.Optional< String > mergeErr = ops.tryMergeNodes( sourceId, targetId, actor( request ) );
            if ( mergeErr.isPresent() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, mergeErr.get() );
                return;
            }
            sendJson( response, Map.of( "merged", true, "targetId", targetId.toString(),
                    "pages_updated", pagesUpdated ) );
        } else {
            // POST /admin/knowledge-graph/nodes — upsert
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            final String name = getJsonString( body, "name" );
            if ( name == null || name.isBlank() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "name is required" );
                return;
            }
            final String nodeType = getJsonString( body, "node_type" );
            final String sourcePage = getJsonString( body, "source_page" );
            final Map< String, Object > properties = body.has( "properties" )
                    ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();
            final KgCurationOps.NodeResult nodeResult = ops.tryUpsertNode( name, nodeType, sourcePage,
                    properties, actor( request ) );
            if ( nodeResult.error().isPresent() ) {
                LOG.warn( "tryUpsertNode: name='{}' sourcePage='{}': {}", name, sourcePage, nodeResult.error().get() );
                sendError( response, HttpServletResponse.SC_CONFLICT, nodeResult.error().get() );
                return;
            }
            final KgNode node = service.getNode( nodeResult.nodeId().get(), true );
            if ( node == null ) {
                sendError( response, HttpServletResponse.SC_CONFLICT,
                    "node not visible after insert (excluded source page or other policy filter)" );
                return;
            }
            sendJson( response, KnowledgeJsonMapper.nodeToMap( node ) );
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
        if ( segments.length >= 3 && "confirm".equals( segments[2] ) ) {
            handlePostEdgeConfirm( service, request, response, segments );
            return;
        }
        handlePostEdgeUpsert( service, request, response );
    }

    private void handlePostEdgeConfirm( final KnowledgeGraphService service,
                                        final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String[] segments ) throws IOException {
        final UUID id = parseUuid( segments[1], response );
        if ( id == null ) return;
        final String actorVal = actor( request );
        final java.util.Optional< String > err = getKgCurationOps().tryConfirmEdge( id, actorVal );
        if ( err.isPresent() ) {
            sendNotFound( response, err.get() );
            return;
        }
        final KgEdge after = service.getEdge( id );
        if ( after == null ) {
            sendNotFound( response, "Edge not found after confirm: " + id );
            return;
        }
        sendJson( response, Map.of(
            "id", after.id().toString(),
            "tier", after.tier(),
            "provenance", after.provenance().value(),
            "confirmed", true ) );
    }

    private void handlePostEdgeUpsert( final KnowledgeGraphService service,
                                       final HttpServletRequest request,
                                       final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String sourceIdStr = getJsonString( body, "source_id" );
        final String targetIdStr = getJsonString( body, "target_id" );
        final String relType = getJsonString( body, "relationship_type" );
        if ( sourceIdStr == null || targetIdStr == null || relType == null || relType.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "source_id, target_id and relationship_type are required" );
            return;
        }
        final UUID sourceId = parseUuid( sourceIdStr, response );
        if ( sourceId == null ) return;
        final UUID targetId = parseUuid( targetIdStr, response );
        if ( targetId == null ) return;
        final Map< String, Object > properties = body.has( "properties" )
                ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();

        // Capture before-state for audit (find existing edge at this triple, if any)
        Map< String, Object > before = null;
        try {
            final List< KgEdge > outbound = service.getEdgesForNode( sourceId, "outbound" );
            for ( final KgEdge e : outbound ) {
                if ( e.targetId().equals( targetId ) && relType.equals( e.relationshipType() ) ) {
                    before = KnowledgeJsonMapper.edgeToMap( e );
                    break;
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "handlePostEdgeUpsert: failed to fetch before-state for audit (src={}, tgt={}, rel={}): {}",
                sourceId, targetId, relType, e.getMessage() );
        }

        // Always stamps HUMAN_CURATED regardless of any provenance value in the body
        final KgCurationOps.EdgeResult result = getKgCurationOps().tryUpsertEdge(
                sourceId, targetId, relType, properties, actor( request ) );
        if ( result.error().isPresent() ) {
            final String msg = result.error().get();
            if ( msg != null && msg.toLowerCase( java.util.Locale.ROOT ).contains( "duplicate" ) ) {
                LOG.warn( "handlePostEdgeUpsert: duplicate key for ({}, {}, {}): {}",
                    sourceId, targetId, relType, msg );
                sendError( response, HttpServletResponse.SC_CONFLICT,
                    "Edge already exists: " + sourceId + " -[" + relType + "]-> " + targetId );
            } else {
                LOG.warn( "handlePostEdgeUpsert: failed for ({}, {}, {}): {}",
                    sourceId, targetId, relType, msg );
                sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg );
            }
            return;
        }

        final KgEdge edge = service.getEdge( result.edgeId().get() );
        if ( edge == null ) {
            LOG.warn( "handlePostEdgeUpsert: edge not readable after upsert (src={}, tgt={}, rel={})",
                sourceId, targetId, relType );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Edge not visible after upsert" );
            return;
        }

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            final String action = before == null ? "CREATE" : "UPDATE";
            final Map< String, Object > after = KnowledgeJsonMapper.edgeToMap( edge );
            try {
                audit.insert( edge.id(), action, before, after, actor( request ), null );
            } catch ( final RuntimeException e ) {
                LOG.warn( "handlePostEdgeUpsert: audit insert failed for edge {}: {}", edge.id(), e.getMessage() );
            }
        }

        sendJson( response, KnowledgeJsonMapper.edgeToMap( edge ) );
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
        final String relType = getJsonString( body, "relationship_type" );
        final String search = getJsonString( body, "search" );
        final String endpointKind = getJsonString( body, "endpoint_kind" );
        final int expectedCount = getJsonInt( body, "expected_count", Integer.MIN_VALUE );
        if ( expectedCount == Integer.MIN_VALUE ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "expected_count must be an integer" );
            return;
        }

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
            reason = getJsonString( body, "reason" );
        } catch ( final RuntimeException e ) {
            // Body is optional — proceed with null reason if parsing fails
            LOG.warn( "handlePostEdgeDeleteAndReject: could not parse body (edge={}): {}", id, e.getMessage() );
        }

        final String actorVal = actor( request );
        final java.util.Optional< String > err = getKgCurationOps().tryDeleteAndRejectEdge( id, actorVal, reason );
        if ( err.isPresent() ) {
            LOG.warn( "handlePostEdgeDeleteAndReject: failed for edge {}: {}", id, err.get() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err.get() );
            return;
        }

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            try {
                audit.insert( id, "DELETE", KnowledgeJsonMapper.edgeToMap( existing ),
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
        final java.util.Optional< String > err = getKgCurationOps().tryDeleteNode( id, null );
        if ( err.isPresent() ) {
            LOG.warn( "handleDeleteNode: failed for node {}: {}", id, err.get() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err.get() );
            return;
        }
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
                before = KnowledgeJsonMapper.edgeToMap( existing );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "handleDeleteEdge: failed to fetch before-state for audit (id={}): {}", id, e.getMessage() );
        }

        final java.util.Optional< String > err = getKgCurationOps().tryDeleteEdge( id, actor( request ) );
        if ( err.isPresent() ) {
            LOG.warn( "handleDeleteEdge: failed for edge {}: {}", id, err.get() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err.get() );
            return;
        }
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
            final KgNode srcNode = service.getNode( edge.sourceId(), true );
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

    /** Delegates to {@link AdminKnowledgeIo#parseUuid} — moved there so the extracted
     *  {@code com.wikantik.rest.knowledge} handler classes can share it too; kept here as a
     *  thin wrapper so the many existing call sites in this class don't need to change. */
    private UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        return AdminKnowledgeIo.parseUuid( str, response );
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
        result.put( "proposals", proposals.stream().map( KnowledgeJsonMapper::hubProposalToMap ).toList() );
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
        final double threshold = getJsonDouble( body, "threshold", Double.NaN );
        if ( Double.isNaN( threshold ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "threshold must be a number" );
            return;
        }
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
                final String reason = body != null ? getJsonString( body, "reason" ) : null;
                repo.updateStatus( id, "rejected", "admin", reason );
                sendJson( response, Map.of( "status", "ok" ) );
            }
            default -> sendNotFound( response, "Unknown action: " + segments[2] );
        }
    }

    /** Returns the string value of {@code key} if present and a JSON primitive, otherwise {@code defaultValue}. */
    private static String optString( final JsonObject body, final String key, final String defaultValue ) {
        return body.has( key ) && body.get( key ).isJsonPrimitive()
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
