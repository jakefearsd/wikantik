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
import com.wikantik.knowledge.EmbeddingService;
import com.wikantik.knowledge.SummaryExtractor;
import com.wikantik.knowledge.TagExtractor;
import com.wikantik.knowledge.TitleDeriver;
import com.wikantik.knowledge.GraphProjector;
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
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST servlet for knowledge graph administration.
 * <p>
 * Currently protected by {@link AdminAuthFilter} (requires Admin role).
 * When a dedicated {@code knowledge-admin} role is needed for non-admin domain experts,
 * add a {@code KnowledgeAdminFilter} that checks for either Admin or knowledge-admin role.
 * <p>
 * Mapped to {@code /admin/knowledge/*}. Protected by {@link AdminAuthFilter}.
 * <ul>
 *   <li>{@code GET  /admin/knowledge/schema} — discover schema</li>
 *   <li>{@code GET  /admin/knowledge/nodes} — query nodes (?node_type=...&amp;limit=...&amp;offset=...)</li>
 *   <li>{@code GET  /admin/knowledge/nodes/{name}} — get node by name</li>
 *   <li>{@code GET  /admin/knowledge/edges/{nodeId}} — get edges for node (?direction=both)</li>
 *   <li>{@code GET  /admin/knowledge/proposals} — list proposals (?status=pending&amp;limit=50)</li>
 *   <li>{@code POST /admin/knowledge/proposals/{id}/approve} — approve a proposal</li>
 *   <li>{@code POST /admin/knowledge/proposals/{id}/reject} — reject a proposal (body: {reason})</li>
 *   <li>{@code POST /admin/knowledge/nodes} — upsert node (manual curation)</li>
 *   <li>{@code DELETE /admin/knowledge/nodes/{id}} — delete a node</li>
 *   <li>{@code POST /admin/knowledge/nodes/merge} — merge two nodes (body: {sourceId, targetId})</li>
 *   <li>{@code POST /admin/knowledge/edges} — upsert edge (manual curation)</li>
 *   <li>{@code DELETE /admin/knowledge/edges/{id}} — delete an edge</li>
 *   <li>{@code POST /admin/knowledge/project-all} — project all pages into knowledge graph</li>
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
        final KnowledgeGraphService service = getEngine().getManager( KnowledgeGraphService.class );
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Knowledge graph is not configured" );
        }
        return service;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final KnowledgeGraphService service = getKnowledgeService( response );
        if ( service == null ) return;

        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || "/".equals( pathInfo ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Path required" );
            return;
        }

        final String[] segments = pathInfo.substring( 1 ).split( "/" );
        final String resource = segments[0];

        switch ( resource ) {
            case "schema" -> handleGetSchema( service, response );
            case "nodes" -> handleGetNodes( service, request, response, segments );
            case "edges" -> handleGetEdges( service, request, response, segments );
            case "proposals" -> handleGetProposals( service, request, response );
            case "embeddings" -> handleGetEmbeddings( request, response, segments );
            case "pages-without-frontmatter" -> handleGetPagesWithoutFrontmatter( request, response );
            case "hub-proposals" -> handleGetHubProposals( request, response );
            case "backfill-frontmatter" -> handleGetBackfillStatus( response );
            default -> sendNotFound( response, "Unknown resource: " + resource );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final KnowledgeGraphService service = getKnowledgeService( response );
        if ( service == null ) return;

        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Path required" );
            return;
        }

        final String[] segments = pathInfo.substring( 1 ).split( "/" );
        final String resource = segments[0];

        switch ( resource ) {
            case "proposals" -> handlePostProposal( service, request, response, segments );
            case "nodes" -> handlePostNode( service, request, response, segments );
            case "edges" -> handlePostEdge( service, request, response );
            case "project-all" -> handleProjectAll( response );
            case "clear-all" -> handleClearAll( service, response );
            case "embeddings" -> handlePostEmbeddings( response, segments );
            case "hub-proposals" -> handlePostHubProposals( request, response, segments );
            case "backfill-frontmatter" -> handlePostBackfillFrontmatter( response );
            case "sync-hub-memberships" -> handlePostSyncHubMemberships( response );
            default -> sendNotFound( response, "Unknown resource: " + resource );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final KnowledgeGraphService service = getKnowledgeService( response );
        if ( service == null ) return;

        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Path required" );
            return;
        }

        final String[] segments = pathInfo.substring( 1 ).split( "/" );
        final String resource = segments[0];

        if ( segments.length < 2 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "ID required in path" );
            return;
        }

        switch ( resource ) {
            case "nodes" -> handleDeleteNode( service, response, segments[1] );
            case "edges" -> handleDeleteEdge( service, response, segments[1] );
            default -> sendNotFound( response, "Unknown resource: " + resource );
        }
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
            // GET /admin/knowledge/nodes/{name}/similar?limit=10
            handleGetSimilarNodes( request, response, segments[1] );
            return;
        }
        if ( segments.length >= 2 && "merge-candidates".equals( segments[1] ) ) {
            // GET /admin/knowledge/nodes/merge-candidates?limit=10
            handleGetMergeCandidates( request, response );
            return;
        }
        if ( segments.length >= 2 ) {
            // GET /admin/knowledge/nodes/{name}
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
            // GET /admin/knowledge/nodes?node_type=...&name=...&limit=...&offset=...
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
            // GET /admin/knowledge/edges — list all edges (paginated, with names)
            final String relType = request.getParameter( "relationship_type" );
            final String search = request.getParameter( "search" );
            final int limit = parseIntParam( request, "limit", 50 );
            final int offset = parseIntParam( request, "offset", 0 );
            sendJson( response, Map.of( "edges", service.queryEdges( relType, search, limit, offset ) ) );
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

    private void handleGetProposals( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response ) throws IOException {
        final String status = request.getParameter( "status" );
        final String sourcePage = request.getParameter( "source_page" );
        final int limit = parseIntParam( request, "limit", 50 );
        final int offset = parseIntParam( request, "offset", 0 );
        final List< KgProposal > proposals = service.listProposals( status, sourcePage, limit, offset );
        sendJson( response, Map.of( "proposals", proposals.stream()
                .map( this::proposalToMap ).toList() ) );
    }

    // --- POST handlers ---

    private void handlePostProposal( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        // POST /admin/knowledge/proposals — create a new proposal
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
        // POST /admin/knowledge/proposals/{id}/approve or /reject
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
            default -> sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unknown action: " + action + ". Use 'approve' or 'reject'." );
        }
    }

    private void handlePostNode( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length >= 2 && "merge".equals( segments[1] ) ) {
            // POST /admin/knowledge/nodes/merge
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
            // POST /admin/knowledge/nodes — upsert
            final JsonObject body = parseJsonBody( request, response );
            if ( body == null ) return;
            final String name = body.get( "name" ).getAsString();
            final String nodeType = body.has( "node_type" ) ? body.get( "node_type" ).getAsString() : null;
            final String sourcePage = body.has( "source_page" ) ? body.get( "source_page" ).getAsString() : null;
            final Map< String, Object > properties = body.has( "properties" )
                    ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();
            final KgNode node = service.upsertNode( name, nodeType, sourcePage,
                    Provenance.HUMAN_AUTHORED, properties );
            sendJson( response, nodeToMap( node ) );
        }
    }

    private void handlePostEdge( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final UUID sourceId = UUID.fromString( body.get( "source_id" ).getAsString() );
        final UUID targetId = UUID.fromString( body.get( "target_id" ).getAsString() );
        final String relType = body.get( "relationship_type" ).getAsString();
        final Map< String, Object > properties = body.has( "properties" )
                ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();
        final KgEdge edge = service.upsertEdge( sourceId, targetId, relType,
                Provenance.HUMAN_AUTHORED, properties );
        sendJson( response, edgeToMap( edge ) );
    }

    private void handleProjectAll( final HttpServletResponse response ) throws IOException {
        final PageManager pm = getEngine().getManager( PageManager.class );
        final GraphProjector projector = getEngine().getManager( GraphProjector.class );
        if ( projector == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "GraphProjector is not available" );
            return;
        }

        try {
            final Collection< ? extends Page > allPages = pm.getAllPages();
            int scanned = 0;
            int projected = 0;
            final List< String > errors = new ArrayList<>();
            for ( final Page page : allPages ) {
                scanned++;
                if ( projector.isSystemPage( page.getName() ) ) {
                    continue;
                }
                try {
                    final String text = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( text );
                    projector.projectPage( page.getName(), parsed.metadata(), parsed.body() );
                    projected++;
                } catch ( final Exception e ) {
                    errors.add( page.getName() + ": " + e.getMessage() );
                }
            }
            LOG.info( "Projected {} of {} pages into knowledge graph ({} errors)",
                    projected, scanned, errors.size() );
            sendJson( response, Map.of( "scanned", scanned, "projected", projected, "errors", errors ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to project all pages", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Projection failed: " + e.getMessage() );
        }
    }

    private void handleClearAll( final KnowledgeGraphService service,
                                 final HttpServletResponse response ) throws IOException {
        try {
            service.clearAll();
            final EmbeddingService embSvc = getEmbeddingService();
            if ( embSvc != null ) {
                embSvc.reset();
            }
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
        sendJson( response, Map.of( "deleted", true ) );
    }

    private void handleDeleteEdge( final KnowledgeGraphService service,
                                   final HttpServletResponse response,
                                   final String idStr ) throws IOException {
        final UUID id = parseUuid( idStr, response );
        if ( id == null ) return;
        service.deleteEdge( id );
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
            final PageManager pm = getEngine().getManager( PageManager.class );
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
            final PageSaveHelper saveHelper = new PageSaveHelper( getEngine() );
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
        final PageManager pm = getEngine().getManager( PageManager.class );
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
                    final PageSaveHelper saveHelper = new PageSaveHelper( getEngine() );
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
        map.put( "properties", edge.properties() );
        map.put( "created", edge.created() != null ? edge.created().toString() : null );
        map.put( "modified", edge.modified() != null ? edge.modified().toString() : null );
        return map;
    }

    private Map< String, Object > proposalToMap( final KgProposal p ) {
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

    private EmbeddingService getEmbeddingService() {
        return getEngine().getManager( EmbeddingService.class );
    }

    private void handleGetPagesWithoutFrontmatter( final HttpServletRequest request,
                                                   final HttpServletResponse response ) throws IOException {
        final PageManager pm = getEngine().getManager( PageManager.class );
        if ( pm == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PageManager not available" );
            return;
        }
        final SystemPageRegistry spr = getEngine().getManager( SystemPageRegistry.class );
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
        final EmbeddingService embSvc = getEmbeddingService();
        if ( embSvc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Embedding service not available" );
            return;
        }
        if ( segments.length >= 2 && "status".equals( segments[1] ) ) {
            // GET /admin/knowledge/embeddings/status
            final var status = embSvc.getStatus();
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "model_version", status.modelVersion() );
            result.put( "dimension", status.dimension() );
            result.put( "entity_count", status.entityCount() );
            result.put( "relation_count", status.relationCount() );
            result.put( "last_trained", status.lastTrained() != null ? status.lastTrained().toString() : null );
            result.put( "training", status.training() );
            result.put( "ready", embSvc.isReady() );
            result.put( "content_ready", status.contentReady() );
            result.put( "content_dimension", status.contentDimension() );
            result.put( "content_entity_count", status.contentEntityCount() );
            result.put( "content_last_trained", status.contentLastTrained() != null ? status.contentLastTrained().toString() : null );
            result.put( "content_training", status.contentTraining() );
            sendJson( response, result );
        } else if ( segments.length >= 2 && "predicted".equals( segments[1] ) ) {
            // GET /admin/knowledge/embeddings/predicted?limit=20
            final int limit = parseIntParam( request, "limit", 20 );
            sendJson( response, Map.of( "predictions", embSvc.predictMissingEdges( limit ) ) );
        } else if ( segments.length >= 2 && "anomalous".equals( segments[1] ) ) {
            // GET /admin/knowledge/embeddings/anomalous?limit=20
            final int limit = parseIntParam( request, "limit", 20 );
            sendJson( response, Map.of( "anomalous", embSvc.getAnomalousEdges( limit ) ) );
        } else if ( segments.length >= 2 && "similar-pages".equals( segments[1] ) ) {
            // GET /admin/knowledge/embeddings/similar-pages?limit=50
            final int limit = parseIntParam( request, "limit", 50 );
            sendJson( response, Map.of( "pairs", embSvc.getTopSimilarPagePairs( limit ) ) );
        } else {
            sendNotFound( response, "Unknown embeddings sub-resource" );
        }
    }

    private void handlePostEmbeddings( final HttpServletResponse response,
                                       final String[] segments ) throws IOException {
        final EmbeddingService embSvc = getEmbeddingService();
        if ( embSvc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Embedding service not available" );
            return;
        }
        if ( segments.length >= 2 && "retrain".equals( segments[1] ) ) {
            // POST /admin/knowledge/embeddings/retrain
            // If the graph is sparse, project all pages first so there's data to train on
            final KnowledgeGraphService kgSvc = getKnowledgeService( response );
            if ( kgSvc == null ) return;
            final long nodeCount = kgSvc.queryNodes( null, null, 1, 0 ).size();
            int projected = 0;
            if ( nodeCount < 2 ) {
                final PageManager pm = getEngine().getManager( PageManager.class );
                final GraphProjector projector = getEngine().getManager( GraphProjector.class );
                if ( pm != null && projector != null ) {
                    try {
                        for ( final Page page : pm.getAllPages() ) {
                            if ( projector.isSystemPage( page.getName() ) ) {
                                continue;
                            }
                            try {
                                final String text = pm.getPureText( page );
                                final ParsedPage parsed = FrontmatterParser.parse( text );
                                projector.projectPage( page.getName(), parsed.metadata(), parsed.body() );
                                projected++;
                            } catch ( final Exception e ) {
                                LOG.warn( "Failed to project page '{}': {}", page.getName(), e.getMessage() );
                            }
                        }
                    } catch ( final Exception e ) {
                        LOG.warn( "Auto-projection before KGE retrain failed: {}", e.getMessage() );
                    }
                    LOG.info( "Auto-projected {} pages before KGE retrain", projected );
                }
            }
            embSvc.retrain();
            final var status = embSvc.getStatus();
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "message", "Retrained" );
            result.put( "model_version", status.modelVersion() );
            result.put( "entity_count", status.entityCount() );
            result.put( "relation_count", status.relationCount() );
            if ( projected > 0 ) result.put( "auto_projected", projected );
            sendJson( response, result );
        } else if ( segments.length >= 2 && "retrain-content".equals( segments[1] ) ) {
            // POST /admin/knowledge/embeddings/retrain-content
            LOG.info( "Content retrain requested via REST" );
            try {
                embSvc.retrainContentModel();
                final var cStatus = embSvc.getStatus();
                final Map< String, Object > cResult = new LinkedHashMap<>();
                cResult.put( "message", "Content model retrained" );
                cResult.put( "content_entity_count", cStatus.contentEntityCount() );
                cResult.put( "content_dimension", cStatus.contentDimension() );
                cResult.put( "content_last_trained",
                        cStatus.contentLastTrained() != null ? cStatus.contentLastTrained().toString() : null );
                sendJson( response, cResult );
            } catch ( final Exception e ) {
                LOG.error( "Content retrain failed via REST: {}", e.getMessage(), e );
                sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Content retrain failed: " + e.getMessage() );
            }
        } else {
            sendNotFound( response, "Unknown embeddings sub-resource" );
        }
    }

    private void handleGetSimilarNodes( final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String nodeName ) throws IOException {
        final EmbeddingService embSvc = getEmbeddingService();
        if ( embSvc == null || !embSvc.isReady() ) {
            sendJson( response, Map.of( "structural", List.of(), "content", List.of() ) );
            return;
        }
        final int limit = parseIntParam( request, "limit", 10 );
        final String type = request.getParameter( "type" ) != null ? request.getParameter( "type" ) : "both";

        final Map< String, Object > result = new LinkedHashMap<>();

        if ( "structural".equals( type ) || "both".equals( type ) ) {
            final var structural = embSvc.getSimilarNodes( nodeName, limit );
            result.put( "structural", structural.stream().map( p -> {
                final Map< String, Object > m = new LinkedHashMap<>();
                m.put( "name", p.entityName() );
                m.put( "similarity", p.score() );
                return m;
            } ).toList() );
        }

        if ( "content".equals( type ) || "both".equals( type ) ) {
            final var content = embSvc.getContentSimilarNodes( nodeName, limit );
            result.put( "content", content.stream().map( cs -> {
                final Map< String, Object > m = new LinkedHashMap<>();
                m.put( "name", cs.name() );
                m.put( "similarity", cs.similarity() );
                return m;
            } ).toList() );
        }

        // Backward compat: also include "similar" key for old clients
        if ( "structural".equals( type ) ) {
            result.put( "similar", result.get( "structural" ) );
        }

        sendJson( response, result );
    }

    private void handleGetMergeCandidates( final HttpServletRequest request,
                                           final HttpServletResponse response ) throws IOException {
        final EmbeddingService embSvc = getEmbeddingService();
        if ( embSvc == null || !embSvc.isReady() ) {
            sendJson( response, Map.of( "candidates", List.of() ) );
            return;
        }
        final int limit = parseIntParam( request, "limit", 10 );
        final var candidates = embSvc.getMergeCandidatesEnhanced( limit, 0.3 );
        sendJson( response, Map.of( "candidates", candidates.stream().map( mc -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "name_a", mc.nameA() );
            m.put( "name_b", mc.nameB() );
            m.put( "structural", mc.structural() );
            m.put( "content", mc.content() );
            m.put( "combined", mc.combined() );
            return m;
        } ).toList() ) );
    }

    private UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( str );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID: " + str );
            return null;
        }
    }

    // --- Hub Proposals ---

    private HubProposalRepository getHubProposalRepo( final HttpServletResponse response ) throws IOException {
        final HubProposalRepository repo = getEngine().getManager( HubProposalRepository.class );
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
            case "generate" -> {
                LOG.info( "Hub proposals: generate endpoint invoked" );
                final EmbeddingService emb = getEngine().getManager( EmbeddingService.class );
                if ( emb == null ) {
                    LOG.warn( "Hub proposals generate rejected: EmbeddingService not registered "
                        + "(knowledge graph initialization likely failed — see earlier WARN)" );
                    sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                        "EmbeddingService not available — knowledge graph not initialized" );
                    return;
                }
                if ( !emb.isContentReady() ) {
                    LOG.warn( "Hub proposals generate rejected: content model not trained yet" );
                    sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                        "Content model must be trained before generating proposals" );
                    return;
                }
                final HubProposalService service = getEngine().getManager( HubProposalService.class );
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
            case "bulk-approve" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final List< Integer > ids = GSON.fromJson( body.get( "ids" ),
                    new TypeToken< List< Integer > >() {}.getType() );
                final String reviewedBy = body.has( "reviewedBy" )
                    ? body.get( "reviewedBy" ).getAsString() : "admin";
                repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
                sendJson( response, Map.of( "status", "ok" ) );
            }
            case "bulk-reject" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final List< Integer > ids = GSON.fromJson( body.get( "ids" ),
                    new TypeToken< List< Integer > >() {}.getType() );
                final String reviewedBy = body.has( "reviewedBy" )
                    ? body.get( "reviewedBy" ).getAsString() : "admin";
                final String reason = body.has( "reason" )
                    ? body.get( "reason" ).getAsString() : null;
                repo.bulkUpdateStatus( ids, "rejected", reviewedBy, reason );
                sendJson( response, Map.of( "status", "ok" ) );
            }
            case "threshold-approve" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final double threshold = body.get( "threshold" ).getAsDouble();
                final String reviewedBy = body.has( "reviewedBy" )
                    ? body.get( "reviewedBy" ).getAsString() : "admin";
                final var above = repo.listProposalsAboveThreshold( threshold );
                final var ids = above.stream()
                    .map( HubProposalRepository.HubProposal::id ).toList();
                repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
                sendJson( response, Map.of( "status", "ok", "approved", ids.size() ) );
            }
            default -> {
                if ( segments.length >= 3 ) {
                    final int id;
                    try {
                        id = Integer.parseInt( segments[1] );
                    } catch ( final NumberFormatException e ) {
                        sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid proposal ID" );
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
                } else {
                    sendNotFound( response, "Unknown hub-proposals action" );
                }
            }
        }
    }

    // --- Backfill ---

    private volatile boolean backfillRunning = false;
    private volatile int backfillTotal = 0;
    private volatile int backfillProcessed = 0;
    private volatile int backfillErrors = 0;

    private void handleGetBackfillStatus( final HttpServletResponse response ) throws IOException {
        sendJson( response, Map.of(
            "running", backfillRunning,
            "total", backfillTotal,
            "processed", backfillProcessed,
            "errors", backfillErrors
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

    private void runBackfill() {
        backfillRunning = true;
        backfillProcessed = 0;
        backfillErrors = 0;
        try {
            final PageManager pm = getEngine().getManager( PageManager.class );
            final SystemPageRegistry spr = getEngine().getManager( SystemPageRegistry.class );
            final var allPages = pm.getAllPages();
            backfillTotal = allPages.size();

            final PageSaveHelper saveHelper = new PageSaveHelper( getEngine() );

            for ( final Page page : allPages ) {
                try {
                    if ( spr != null && spr.isSystemPage( page.getName() ) ) {
                        backfillProcessed++;
                        continue;
                    }
                    final String content = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                    if ( !parsed.metadata().isEmpty() ) {
                        backfillProcessed++;
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
                    backfillProcessed++;
                } catch ( final Exception e ) {
                    backfillErrors++;
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
            final PageManager pm = getEngine().getManager( PageManager.class );
            final PageSaveHelper saveHelper = new PageSaveHelper( getEngine() );
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
