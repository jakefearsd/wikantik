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

import com.wikantik.api.knowledge.*;
import com.wikantik.rest.knowledge.HubProposalAdminHandlers;
import com.wikantik.rest.knowledge.KgEdgeAdminHandlers;
import com.wikantik.rest.knowledge.KgJudgeAdminHandlers;
import com.wikantik.rest.knowledge.KgMaintenanceAdminHandlers;
import com.wikantik.rest.knowledge.KgNodeAdminHandlers;
import com.wikantik.rest.knowledge.KgProposalAdminHandlers;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.*;

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
        final HubProposalAdminHandlers hubProposalHandlers = new HubProposalAdminHandlers(
                () -> getSubsystems().knowledge().hubProposalRepository(),
                () -> getSubsystems().knowledge().nodeMentionSimilarity(),
                () -> getSubsystems().knowledge().hubProposalService() );
        final KgMaintenanceAdminHandlers maintenanceHandlers = new KgMaintenanceAdminHandlers(
                () -> getSubsystems().page().pages(),
                () -> getSubsystems().core().systemPageRegistry(),
                () -> getSubsystems().knowledge().nodeMentionSimilarity(),
                this::getEngine );

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
                ( svc, req, resp, seg ) -> maintenanceHandlers.handleGetEmbeddings( req, resp, seg ),
                ( svc, req, resp, seg ) -> maintenanceHandlers.handlePostEmbeddings( resp, seg ),
                null ) );
        m.put( "pages-without-frontmatter", Resource.get(
                ( svc, req, resp, seg ) -> maintenanceHandlers.handleGetPagesWithoutFrontmatter( req, resp ) ) );
        m.put( "hub-proposals", new Resource(
                ( svc, req, resp, seg ) -> hubProposalHandlers.handleGetHubProposals( req, resp ),
                ( svc, req, resp, seg ) -> hubProposalHandlers.handlePostHubProposals( req, resp, seg ),
                null ) );
        m.put( "backfill-frontmatter", new Resource(
                ( svc, req, resp, seg ) -> maintenanceHandlers.handleGetBackfillStatus( resp ),
                ( svc, req, resp, seg ) -> maintenanceHandlers.handlePostBackfillFrontmatter( resp ),
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
                ( svc, req, resp, seg ) -> maintenanceHandlers.handlePostSyncHubMemberships( resp ) ) );
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

}
