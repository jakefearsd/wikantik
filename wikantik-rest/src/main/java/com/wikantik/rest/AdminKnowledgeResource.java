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
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import com.wikantik.api.knowledge.*;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST servlet for knowledge graph administration.
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
        if ( segments.length >= 2 ) {
            // GET /admin/knowledge/nodes/{name}
            final String name = segments[1];
            final KgNode node = service.getNodeByName( name );
            if ( node == null ) {
                sendNotFound( response, "Node not found: " + name );
            } else {
                final Map< String, Object > result = nodeToMap( node );
                result.put( "edges", service.getEdgesForNode( node.id(), "both" ).stream()
                        .map( this::edgeToMap ).toList() );
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
            final int limit = getIntParam( request, "limit", 50 );
            final int offset = getIntParam( request, "offset", 0 );
            final List< KgNode > nodes = service.queryNodes( filters, null, limit, offset );
            sendJson( response, Map.of( "nodes", nodes.stream().map( this::nodeToMap ).toList() ) );
        }
    }

    private void handleGetEdges( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length < 2 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Node ID required" );
            return;
        }
        final UUID nodeId = parseUuid( segments[1], response );
        if ( nodeId == null ) return;

        final String direction = request.getParameter( "direction" ) != null
                ? request.getParameter( "direction" ) : "both";
        final List< KgEdge > edges = service.getEdgesForNode( nodeId, direction );
        sendJson( response, Map.of( "edges", edges.stream().map( this::edgeToMap ).toList() ) );
    }

    private void handleGetProposals( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response ) throws IOException {
        final String status = request.getParameter( "status" );
        final String sourcePage = request.getParameter( "source_page" );
        final int limit = getIntParam( request, "limit", 50 );
        final int offset = getIntParam( request, "offset", 0 );
        final List< KgProposal > proposals = service.listProposals( status, sourcePage, limit, offset );
        sendJson( response, Map.of( "proposals", proposals.stream()
                .map( this::proposalToMap ).toList() ) );
    }

    // --- POST handlers ---

    private void handlePostProposal( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        // POST /admin/knowledge/proposals/{id}/approve or /reject
        if ( segments.length < 3 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Expected: /proposals/{id}/approve or /proposals/{id}/reject" );
            return;
        }
        final UUID proposalId = parseUuid( segments[1], response );
        if ( proposalId == null ) return;

        final String action = segments[2];
        final String reviewedBy = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";

        switch ( action ) {
            case "approve" -> {
                final KgProposal approved = service.approveProposal( proposalId, reviewedBy );
                sendJson( response, proposalToMap( approved ) );
            }
            case "reject" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final String reason = body.has( "reason" ) ? body.get( "reason" ).getAsString() : null;
                final KgProposal rejected = service.rejectProposal( proposalId, reviewedBy, reason );
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
            service.mergeNodes( sourceId, targetId );
            sendJson( response, Map.of( "merged", true, "targetId", targetId.toString() ) );
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

    private JsonObject parseJsonBody( final HttpServletRequest request,
                                      final HttpServletResponse response ) throws IOException {
        try ( final BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return null;
        }
    }

    private UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( str );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID: " + str );
            return null;
        }
    }

    private int getIntParam( final HttpServletRequest request, final String name, final int defaultVal ) {
        final String val = request.getParameter( name );
        if ( val == null ) return defaultVal;
        try {
            return Integer.parseInt( val );
        } catch ( final NumberFormatException e ) {
            return defaultVal;
        }
    }
}
