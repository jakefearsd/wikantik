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
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgEdgeView;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.PageKnowledgeSlice;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Page-scoped Knowledge-Graph read and curation REST servlet.
 *
 * <p><strong>Routing note:</strong> the natural name for these endpoints would be
 * {@code /api/pages/{name}/knowledge}, but {@code PageResource} already holds the
 * servlet mapping {@code /api/pages/*} and a Servlet url-pattern cannot match a
 * mid-path variable. Therefore this servlet is mapped to {@code /api/page-knowledge/*}
 * and parses {@code request.getPathInfo()} segments directly.</p>
 *
 * <p>Routes ({@code pathInfo} shown):</p>
 * <ul>
 *   <li>{@code GET    /{name}}                       — page KG slice (view permission)</li>
 *   <li>{@code POST   /{name}/entities}               — upsert entity (edit permission)</li>
 *   <li>{@code POST   /{name}/entities/{id}/confirm}  — confirm AI-inferred node to HUMAN_AUTHORED (edit permission)</li>
 *   <li>{@code DELETE /{name}/entities/{id}}          — delete node (edit permission)</li>
 *   <li>{@code POST   /{name}/edges}                  — upsert edge (edit permission); SHACL refusal → 422</li>
 *   <li>{@code POST   /{name}/edges/{id}/confirm}     — confirm edge to HUMAN_CURATED (edit permission)</li>
 *   <li>{@code DELETE /{name}/edges/{id}}             — delete edge (edit permission)</li>
 *   <li>{@code POST   /{name}/edges/{id}/reject}      — delete-and-reject edge with reason (edit permission)</li>
 * </ul>
 *
 * <p>All curation calls route through {@link KgCurationOps} so the Phase-5b SHACL write
 * gate, HUMAN_CURATED provenance stamping, and the audit log apply identically to the
 * admin path.</p>
 */
public class PageKnowledgeResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageKnowledgeResource.class );
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] parts = parseParts( request, response );
        if ( parts == null ) return;

        // GET /{name}
        if ( parts.length == 1 ) {
            final String pageName = parts[ 0 ];
            if ( !checkPagePermission( request, response, pageName, "view" ) ) return;

            final KnowledgeGraphService service = requireService( response );
            if ( service == null ) return;

            final PageKnowledgeSlice slice = service.getPageSlice( pageName );
            sendJson( response, sliceToMap( slice ) );
            return;
        }

        sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown route" );
    }

    // -------------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] parts = parseParts( request, response );
        if ( parts == null ) return;

        if ( parts.length < 2 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Sub-resource required (entities or edges)" );
            return;
        }

        final String pageName = parts[ 0 ];
        final String sub = parts[ 1 ];

        if ( !checkPagePermission( request, response, pageName, "edit" ) ) return;

        final KnowledgeGraphService service = requireService( response );
        if ( service == null ) return;
        final KgCurationOps ops = requireCurationOps( response );
        if ( ops == null ) return;

        final String actor = resolveActor( request );

        switch ( sub ) {
            case "entities" -> {
                if ( parts.length == 2 ) {
                    handleUpsertEntity( request, response, pageName, ops, service, actor );
                } else if ( parts.length == 4 && "confirm".equals( parts[ 3 ] ) ) {
                    handleConfirmEntity( request, response, parts[ 2 ], ops, service, actor );
                } else {
                    sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown entities route" );
                }
            }
            case "edges" -> {
                if ( parts.length == 2 ) {
                    handleUpsertEdge( request, response, ops, actor );
                } else if ( parts.length == 4 && "confirm".equals( parts[ 3 ] ) ) {
                    handleConfirmEdge( request, response, parts[ 2 ], ops, service, actor );
                } else if ( parts.length == 4 && "reject".equals( parts[ 3 ] ) ) {
                    handleRejectEdge( request, response, parts[ 2 ], ops, actor );
                } else {
                    sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown edges route" );
                }
            }
            default -> sendError( response, HttpServletResponse.SC_NOT_FOUND,
                    "Unknown sub-resource: " + sub + ". Use 'entities' or 'edges'." );
        }
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] parts = parseParts( request, response );
        if ( parts == null ) return;

        if ( parts.length != 3 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Expected /{name}/entities/{id} or /{name}/edges/{id}" );
            return;
        }

        final String pageName = parts[ 0 ];
        final String sub = parts[ 1 ];

        if ( !checkPagePermission( request, response, pageName, "edit" ) ) return;

        final KgCurationOps ops = requireCurationOps( response );
        if ( ops == null ) return;

        final String actor = resolveActor( request );

        switch ( sub ) {
            case "entities" -> {
                final UUID nodeId = parseUuid( parts[ 2 ], response );
                if ( nodeId == null ) return;
                final Optional< String > err = ops.tryDeleteNode( nodeId, actor );
                if ( err.isPresent() ) {
                    LOG.warn( "tryDeleteNode: page='{}' node={}: {}", pageName, nodeId, err.get() );
                    sendError( response, 422, err.get() );
                    return;
                }
                LOG.info( "page-knowledge: entity deleted page='{}' node={} actor='{}'", pageName, nodeId, actor );
                sendJson( response, Map.of( "ok", true, "deleted", true ) );
            }
            case "edges" -> {
                final UUID edgeId = parseUuid( parts[ 2 ], response );
                if ( edgeId == null ) return;
                final Optional< String > err = ops.tryDeleteEdge( edgeId, actor );
                if ( err.isPresent() ) {
                    LOG.warn( "tryDeleteEdge: page='{}' edge={}: {}", pageName, edgeId, err.get() );
                    sendError( response, 422, err.get() );
                    return;
                }
                LOG.info( "page-knowledge: edge deleted page='{}' edge={} actor='{}'", pageName, edgeId, actor );
                sendJson( response, Map.of( "ok", true, "deleted", true ) );
            }
            default -> sendError( response, HttpServletResponse.SC_NOT_FOUND,
                    "Unknown sub-resource: " + sub + ". Use 'entities' or 'edges'." );
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleUpsertEntity( final HttpServletRequest request,
                                      final HttpServletResponse response,
                                      final String pageName,
                                      final KgCurationOps ops,
                                      final KnowledgeGraphService service,
                                      final String actor ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String name = getJsonString( body, "name" );
        if ( name == null || name.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "name is required" );
            return;
        }
        final String nodeType = getJsonString( body, "nodeType" );
        final Map< String, Object > properties = body.has( "properties" )
                ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();

        final KgCurationOps.NodeResult result = ops.tryUpsertNode( name, nodeType, pageName, properties, actor );
        if ( result.error().isPresent() ) {
            LOG.warn( "tryUpsertNode: page='{}' name='{}': {}", pageName, name, result.error().get() );
            sendError( response, 422, result.error().get() );
            return;
        }
        final UUID nodeId = result.nodeId().get();
        final KgNode node = service.getNode( nodeId, true );
        if ( node == null ) {
            LOG.warn( "tryUpsertNode: node not visible after insert page='{}' id={}", pageName, nodeId );
            sendError( response, HttpServletResponse.SC_CONFLICT,
                    "node not visible after insert (excluded source page or other policy filter)" );
            return;
        }
        LOG.info( "page-knowledge: entity upserted page='{}' id={} actor='{}'", pageName, nodeId, actor );
        sendJson( response, Map.of( "ok", true, "id", nodeId.toString(), "node", nodeToMap( node ) ) );
    }

    /**
     * Confirm an AI-inferred entity node to HUMAN_AUTHORED provenance by re-upserting
     * it with the authenticated actor via {@link KgCurationOps#tryUpsertNode}.
     *
     * <p><strong>Note:</strong> {@code tryUpsertNode} stamps {@code HUMAN_AUTHORED}
     * (not {@code HUMAN_CURATED}). For entity confirmation the intent is to record
     * a human actor's explicit acceptance of the node, which HUMAN_AUTHORED satisfies.
     * {@code HUMAN_CURATED} is the provenance for edges; nodes do not carry a separate
     * "curated" tier in the current implementation.</p>
     */
    private void handleConfirmEntity( final HttpServletRequest request,
                                       final HttpServletResponse response,
                                       final String idStr,
                                       final KgCurationOps ops,
                                       final KnowledgeGraphService service,
                                       final String actor ) throws IOException {
        final UUID nodeId = parseUuid( idStr, response );
        if ( nodeId == null ) return;

        final KgNode existing = service.getNode( nodeId, true );
        if ( existing == null ) {
            sendNotFound( response, "Entity not found: " + nodeId );
            return;
        }

        final KgCurationOps.NodeResult result = ops.tryUpsertNode(
                existing.name(), existing.nodeType(), existing.sourcePage(),
                existing.properties(), actor );
        if ( result.error().isPresent() ) {
            LOG.warn( "tryUpsertNode (confirm): node={}: {}", nodeId, result.error().get() );
            sendError( response, 422, result.error().get() );
            return;
        }
        final KgNode after = service.getNode( result.nodeId().get(), true );
        if ( after == null ) {
            LOG.warn( "entity confirm: node not visible after re-upsert id={}", nodeId );
            sendError( response, HttpServletResponse.SC_CONFLICT, "node not visible after confirm" );
            return;
        }
        LOG.info( "page-knowledge: entity confirmed id={} actor='{}'", nodeId, actor );
        sendJson( response, Map.of( "ok", true, "confirmed", true, "id", after.id().toString(),
                "provenance", after.provenance().value() ) );
    }

    private void handleUpsertEdge( final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final KgCurationOps ops,
                                    final String actor ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String sourceIdStr = getJsonString( body, "sourceId" );
        final String targetIdStr = getJsonString( body, "targetId" );
        final String relType = getJsonString( body, "relationshipType" );
        if ( sourceIdStr == null || targetIdStr == null || relType == null || relType.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "sourceId, targetId and relationshipType are required" );
            return;
        }
        final UUID sourceId = parseUuid( sourceIdStr, response );
        if ( sourceId == null ) return;
        final UUID targetId = parseUuid( targetIdStr, response );
        if ( targetId == null ) return;
        final Map< String, Object > properties = body.has( "properties" )
                ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();

        final KgCurationOps.EdgeResult result = ops.tryUpsertEdge( sourceId, targetId, relType, properties, actor );
        if ( result.error().isPresent() ) {
            final String msg = result.error().get();
            LOG.warn( "tryUpsertEdge: ({}, {}, {}): {}", sourceId, targetId, relType, msg );
            // SHACL refusal or boundary guard → 422 with violation envelope
            response.setStatus( 422 );
            sendJson( response, edgeRefusalBody( msg ) );
            return;
        }
        LOG.info( "page-knowledge: edge upserted id={} actor='{}'", result.edgeId().get(), actor );
        sendJson( response, Map.of( "ok", true, "id", result.edgeId().get().toString() ) );
    }

    private void handleConfirmEdge( final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String idStr,
                                     final KgCurationOps ops,
                                     final KnowledgeGraphService service,
                                     final String actor ) throws IOException {
        final UUID edgeId = parseUuid( idStr, response );
        if ( edgeId == null ) return;

        final Optional< String > err = ops.tryConfirmEdge( edgeId, actor );
        if ( err.isPresent() ) {
            LOG.warn( "tryConfirmEdge: edge={}: {}", edgeId, err.get() );
            sendError( response, 422, err.get() );
            return;
        }
        LOG.info( "page-knowledge: edge confirmed edge={} actor='{}'", edgeId, actor );
        sendJson( response, Map.of( "ok", true, "confirmed", true, "id", edgeId.toString() ) );
    }

    private void handleRejectEdge( final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final String idStr,
                                    final KgCurationOps ops,
                                    final String actor ) throws IOException {
        final UUID edgeId = parseUuid( idStr, response );
        if ( edgeId == null ) return;

        String reason = null;
        try ( java.io.BufferedReader reader = request.getReader() ) {
            final JsonObject body = JsonParser.parseReader( reader ).getAsJsonObject();
            reason = getJsonString( body, "reason" );
        } catch ( final Exception e ) {
            // Body is optional for reject — proceed with null reason when absent or unparseable.
            LOG.warn( "handleRejectEdge: could not parse body (edge={}): {}", idStr, e.getMessage() );
        }

        final Optional< String > err = ops.tryDeleteAndRejectEdge( edgeId, actor, reason );
        if ( err.isPresent() ) {
            LOG.warn( "tryDeleteAndRejectEdge: edge={}: {}", edgeId, err.get() );
            sendError( response, 422, err.get() );
            return;
        }
        LOG.info( "page-knowledge: edge rejected edge={} actor='{}'", edgeId, actor );
        sendJson( response, Map.of( "ok", true, "deleted", true, "rejected", true ) );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the {@code pathInfo} into non-empty segments by splitting on {@code /}.
     * Segments are NOT percent-decoded — {@code String.split} operates on the raw pathInfo
     * as returned by the container. Returns {@code null} and sends a 400 error when the
     * pathInfo is absent or empty.
     */
    private String[] parseParts( final HttpServletRequest request,
                                  final HttpServletResponse response ) throws IOException {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.length() <= 1 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required in path" );
            return null;
        }
        // Strip leading slash and split; never returns empty segments from leading slash.
        return pathInfo.substring( 1 ).split( "/" );
    }

    private KnowledgeGraphService requireService( final HttpServletResponse response ) throws IOException {
        final KnowledgeGraphService svc = getSubsystems().knowledge().kgService();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Knowledge Graph subsystem is disabled (wikantik.knowledge.enabled=false?)" );
        }
        return svc;
    }

    private KgCurationOps requireCurationOps( final HttpServletResponse response ) throws IOException {
        final KgCurationOps ops = getSubsystems().knowledge().kgCurationOps();
        if ( ops == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Knowledge Graph subsystem is disabled (wikantik.knowledge.enabled=false?)" );
        }
        return ops;
    }

    private String resolveActor( final HttpServletRequest request ) {
        final String remote = request.getRemoteUser();
        return remote != null ? remote : "anonymous";
    }

    private UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( str );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "parseUuid: invalid UUID '{}': {}", str, e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID: " + str );
            return null;
        }
    }

    /**
     * Builds the HTTP 422 violation body for a SHACL edge refusal, mirroring the
     * frontmatter validation error envelope used by {@code PageResource}.
     */
    static Map< String, Object > edgeRefusalBody( final String errorMessage ) {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "error", "kg_edge_refused" );
        body.put( "violations", List.of(
                FieldViolation.of( "edge", Severity.ERROR, "kg.edge.refused", errorMessage ) ) );
        return body;
    }

    /**
     * Projects a {@link PageKnowledgeSlice} to a clean camelCase map for the
     * {@code GET /{name}} response.  Only the keys the panel reads are included
     * ({@code id}, {@code name}, {@code nodeType}, {@code provenance} for
     * entities; {@code id}, {@code sourceId}, {@code targetId},
     * {@code sourceName}, {@code targetName}, {@code relationshipType},
     * {@code provenance} for edges). Provenance is serialised via
     * {@link Provenance#value()} so the frontend receives lowercase strings
     * (e.g. {@code "ai-inferred"}) rather than enum names.
     */
    private Map< String, Object > sliceToMap( final PageKnowledgeSlice slice ) {
        final List< Map< String, Object > > entityMaps = new java.util.ArrayList<>();
        for ( final KgNode n : slice.entities() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "id", n.id().toString() );
            m.put( "name", n.name() );
            m.put( "nodeType", n.nodeType() );
            m.put( "provenance", n.provenance().value() );
            entityMaps.add( m );
        }
        final List< Map< String, Object > > edgeMaps = new java.util.ArrayList<>();
        for ( final KgEdgeView e : slice.edges() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "id", e.id().toString() );
            m.put( "sourceId", e.sourceId().toString() );
            m.put( "targetId", e.targetId().toString() );
            m.put( "sourceName", e.sourceName() );
            m.put( "targetName", e.targetName() );
            m.put( "relationshipType", e.relationshipType() );
            m.put( "provenance", e.provenance().value() );
            edgeMaps.add( m );
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "entities", entityMaps );
        result.put( "edges", edgeMaps );
        return result;
    }

    private Map< String, Object > nodeToMap( final KgNode node ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", node.id().toString() );
        map.put( "name", node.name() );
        map.put( "nodeType", node.nodeType() );
        map.put( "sourcePage", node.sourcePage() );
        map.put( "provenance", node.provenance().value() );
        map.put( "properties", node.properties() );
        return map;
    }
}
