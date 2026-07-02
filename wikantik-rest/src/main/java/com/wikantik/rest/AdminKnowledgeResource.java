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
import com.wikantik.rest.knowledge.AdminKnowledgeIo;
import com.wikantik.rest.knowledge.KgEdgeAdminHandlers;
import com.wikantik.rest.knowledge.KgJudgeAdminHandlers;
import com.wikantik.rest.knowledge.KgNodeAdminHandlers;
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
        final KgNodeAdminHandlers nodeHandlers = new KgNodeAdminHandlers(
                this::getKgCurationOps,
                () -> getSubsystems().knowledge().nodeMentionSimilarity(),
                () -> getSubsystems().page().pages(),
                this::getEngine );
        final KgEdgeAdminHandlers edgeHandlers = new KgEdgeAdminHandlers(
                this::getKgCurationOps );

        final Map< String, Resource > m = new LinkedHashMap<>();
        m.put( "schema", Resource.get(
                ( svc, req, resp, seg ) -> handleGetSchema( svc, resp ) ) );
        m.put( "nodes", new Resource(
                nodeHandlers::handleGetNodes,
                nodeHandlers::handlePostNode,
                ( svc, req, resp, seg ) -> nodeHandlers.handleDeleteNode( svc, resp, seg[ 1 ] ) ) );
        m.put( "edges", new Resource(
                edgeHandlers::handleGetEdges,
                edgeHandlers::handlePostEdgeDispatch,
                ( svc, req, resp, seg ) -> edgeHandlers.handleDeleteEdge( svc, req, resp, seg[ 1 ] ) ) );
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

    // --- POST handlers ---

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
