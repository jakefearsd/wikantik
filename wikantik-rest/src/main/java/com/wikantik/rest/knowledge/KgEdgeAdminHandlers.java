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
package com.wikantik.rest.knowledge;

import com.google.gson.JsonObject;

import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.rest.KnowledgeJsonMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Edge-curation admin handlers extracted verbatim from {@code AdminKnowledgeResource}: edge
 * list/get-by-node/audit reads, and the upsert/confirm/bulk-delete/delete-and-reject/delete
 * curation actions (including their best-effort audit-row writes — moved byte-verbatim, since
 * that block is audit-coverage-sensitive).
 * <p>
 * Package-private-in-spirit: the type is {@code public} only because it must be constructed and
 * invoked from {@code com.wikantik.rest.AdminKnowledgeResource}'s dispatch table, which lives in a
 * different package — it is not part of any documented public API of {@code wikantik-rest}.
 */
public final class KgEdgeAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( KgEdgeAdminHandlers.class );

    private final Supplier< KgCurationOps > curationOps;

    public KgEdgeAdminHandlers( final Supplier< KgCurationOps > curationOps ) {
        this.curationOps = curationOps;
    }

    // --- GET handlers ---

    public void handleGetEdges( final KnowledgeGraphService service,
                                final HttpServletRequest request,
                                final HttpServletResponse response,
                                final String[] segments ) throws IOException {
        if ( segments.length < 2 ) {
            // GET /admin/knowledge-graph/edges — list all edges (paginated, with names)
            final String relType = request.getParameter( "relationship_type" );
            final String search = request.getParameter( "search" );
            final String endpointKind = request.getParameter( "endpoint_kind" );
            final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 50 );
            final int offset = AdminKnowledgeIo.parseIntParam( request, "offset", 0 );
            final long total = service.countEdges( relType, search, endpointKind );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "edges", service.queryEdges( relType, search, endpointKind, limit, offset ) );
            result.put( "total", total );
            AdminKnowledgeIo.sendJson( response, result );
            return;
        }
        // GET /admin/knowledge-graph/edges/{id}/audit
        if ( segments.length >= 3 && "audit".equals( segments[2] ) ) {
            handleGetEdgeAudit( service, request, response, segments );
            return;
        }
        final UUID nodeId = AdminKnowledgeIo.parseUuid( segments[1], response );
        if ( nodeId == null ) return;

        final String direction = request.getParameter( "direction" ) != null
                ? request.getParameter( "direction" ) : "both";
        final List< KgEdge > edges = service.getEdgesForNode( nodeId, direction );
        final Map< UUID, String > nameMap = AdminKnowledgeIo.resolveEdgeNames( service, edges );
        AdminKnowledgeIo.sendJson( response, Map.of( "edges", edges.stream().map( e -> KnowledgeJsonMapper.enrichEdge( e, nameMap ) ).toList() ) );
    }

    private void handleGetEdgeAudit( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        final UUID id = AdminKnowledgeIo.parseUuid( segments[1], response );
        if ( id == null ) return;
        final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 20 );
        AdminKnowledgeIo.sendJson( response, Map.of( "audit", service.getEdgeAudit( id, limit ) ) );
    }

    // --- POST handlers ---

    public void handlePostEdgeDispatch( final KnowledgeGraphService service,
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
        final UUID id = AdminKnowledgeIo.parseUuid( segments[1], response );
        if ( id == null ) return;
        final String actorVal = AdminKnowledgeIo.actor( request );
        final java.util.Optional< String > err = curationOps.get().tryConfirmEdge( id, actorVal );
        if ( err.isPresent() ) {
            AdminKnowledgeIo.sendNotFound( response, err.get() );
            return;
        }
        final KgEdge after = service.getEdge( id );
        if ( after == null ) {
            AdminKnowledgeIo.sendNotFound( response, "Edge not found after confirm: " + id );
            return;
        }
        AdminKnowledgeIo.sendJson( response, Map.of(
            "id", after.id().toString(),
            "tier", after.tier(),
            "provenance", after.provenance().value(),
            "confirmed", true ) );
    }

    private void handlePostEdgeUpsert( final KnowledgeGraphService service,
                                       final HttpServletRequest request,
                                       final HttpServletResponse response ) throws IOException {
        final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
        if ( body == null ) return;
        final String sourceIdStr = AdminKnowledgeIo.getJsonString( body, "source_id" );
        final String targetIdStr = AdminKnowledgeIo.getJsonString( body, "target_id" );
        final String relType = AdminKnowledgeIo.getJsonString( body, "relationship_type" );
        if ( sourceIdStr == null || targetIdStr == null || relType == null || relType.isBlank() ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "source_id, target_id and relationship_type are required" );
            return;
        }
        final UUID sourceId = AdminKnowledgeIo.parseUuid( sourceIdStr, response );
        if ( sourceId == null ) return;
        final UUID targetId = AdminKnowledgeIo.parseUuid( targetIdStr, response );
        if ( targetId == null ) return;
        final Map< String, Object > properties = body.has( "properties" )
                ? AdminKnowledgeIo.GSON.fromJson( body.get( "properties" ), AdminKnowledgeIo.MAP_TYPE ) : Map.of();

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
        final KgCurationOps.EdgeResult result = curationOps.get().tryUpsertEdge(
                sourceId, targetId, relType, properties, AdminKnowledgeIo.actor( request ) );
        if ( result.error().isPresent() ) {
            final String msg = result.error().get();
            if ( msg != null && msg.toLowerCase( java.util.Locale.ROOT ).contains( "duplicate" ) ) {
                LOG.warn( "handlePostEdgeUpsert: duplicate key for ({}, {}, {}): {}",
                    sourceId, targetId, relType, msg );
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_CONFLICT,
                    "Edge already exists: " + sourceId + " -[" + relType + "]-> " + targetId );
            } else {
                LOG.warn( "handlePostEdgeUpsert: failed for ({}, {}, {}): {}",
                    sourceId, targetId, relType, msg );
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg );
            }
            return;
        }

        final KgEdge edge = service.getEdge( result.edgeId().get() );
        if ( edge == null ) {
            LOG.warn( "handlePostEdgeUpsert: edge not readable after upsert (src={}, tgt={}, rel={})",
                sourceId, targetId, relType );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Edge not visible after upsert" );
            return;
        }

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            final String action = before == null ? "CREATE" : "UPDATE";
            final Map< String, Object > after = KnowledgeJsonMapper.edgeToMap( edge );
            try {
                audit.insert( edge.id(), action, before, after, AdminKnowledgeIo.actor( request ), null );
            } catch ( final RuntimeException e ) {
                LOG.warn( "handlePostEdgeUpsert: audit insert failed for edge {}: {}", edge.id(), e.getMessage() );
            }
        }

        AdminKnowledgeIo.sendJson( response, KnowledgeJsonMapper.edgeToMap( edge ) );
    }

    private void handlePostEdgeBulkDelete( final KnowledgeGraphService service,
                                           final HttpServletRequest request,
                                           final HttpServletResponse response ) throws IOException {
        final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
        if ( body == null ) return;

        if ( !body.has( "expected_count" ) ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "expected_count is required for bulk-delete" );
            return;
        }
        final String relType = AdminKnowledgeIo.getJsonString( body, "relationship_type" );
        final String search = AdminKnowledgeIo.getJsonString( body, "search" );
        final String endpointKind = AdminKnowledgeIo.getJsonString( body, "endpoint_kind" );
        final int expectedCount = AdminKnowledgeIo.getJsonInt( body, "expected_count", Integer.MIN_VALUE );
        if ( expectedCount == Integer.MIN_VALUE ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
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
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            return;
        }

        // Write one DELETE audit row per snapshot row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null ) {
            final String reason = "bulk delete via filter: relationship_type="
                + relType + ", search=" + search;
            final String actorVal = AdminKnowledgeIo.actor( request );
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

        AdminKnowledgeIo.sendJson( response, Map.of( "deleted", deleted ) );
    }

    private void handlePostEdgeDeleteAndReject( final KnowledgeGraphService service,
                                                final HttpServletRequest request,
                                                final HttpServletResponse response,
                                                final String[] segments ) throws IOException {
        final UUID id = AdminKnowledgeIo.parseUuid( segments[1], response );
        if ( id == null ) return;

        final KgEdge existing = service.getEdge( id );
        if ( existing == null ) {
            AdminKnowledgeIo.sendNotFound( response, "Edge not found: " + id );
            return;
        }

        String reason = null;
        try {
            final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
            if ( body == null ) return;
            reason = AdminKnowledgeIo.getJsonString( body, "reason" );
        } catch ( final RuntimeException e ) {
            // Body is optional — proceed with null reason if parsing fails
            LOG.warn( "handlePostEdgeDeleteAndReject: could not parse body (edge={}): {}", id, e.getMessage() );
        }

        final String actorVal = AdminKnowledgeIo.actor( request );
        final java.util.Optional< String > err = curationOps.get().tryDeleteAndRejectEdge( id, actorVal, reason );
        if ( err.isPresent() ) {
            LOG.warn( "handlePostEdgeDeleteAndReject: failed for edge {}: {}", id, err.get() );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err.get() );
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

        AdminKnowledgeIo.sendJson( response, Map.of( "deleted", true, "rejected", true ) );
    }

    // --- DELETE handlers ---

    public void handleDeleteEdge( final KnowledgeGraphService service,
                                  final HttpServletRequest request,
                                  final HttpServletResponse response,
                                  final String idStr ) throws IOException {
        final UUID id = AdminKnowledgeIo.parseUuid( idStr, response );
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

        final java.util.Optional< String > err = curationOps.get().tryDeleteEdge( id, AdminKnowledgeIo.actor( request ) );
        if ( err.isPresent() ) {
            LOG.warn( "handleDeleteEdge: failed for edge {}: {}", id, err.get() );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err.get() );
            return;
        }
        LOG.info( "Knowledge graph edge deleted: {}", id );

        // Write audit row (best-effort)
        final var audit = getAuditRepo( service );
        if ( audit != null && before != null ) {
            try {
                audit.insert( id, "DELETE", before, null, AdminKnowledgeIo.actor( request ), null );
            } catch ( final RuntimeException e ) {
                LOG.warn( "handleDeleteEdge: audit insert failed for edge {}: {}", id, e.getMessage() );
            }
        }

        AdminKnowledgeIo.sendJson( response, Map.of( "deleted", true ) );
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
}
