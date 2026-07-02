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
import com.google.gson.reflect.TypeToken;

import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.rest.KnowledgeJsonMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hub-proposal admin handlers extracted verbatim from {@code AdminKnowledgeResource}: listing
 * proposals ({@code GET /hub-proposals}), the bulk actions dispatched under
 * {@code POST /hub-proposals} ({@code generate}, {@code bulk-approve}, {@code bulk-reject},
 * {@code threshold-approve}), and per-id {@code approve}/{@code reject}.
 * <p>
 * Package-private-in-spirit: the type is {@code public} only because it must be constructed and
 * invoked from {@code com.wikantik.rest.AdminKnowledgeResource}'s dispatch table, which lives in a
 * different package — it is not part of any documented public API of {@code wikantik-rest}.
 */
public final class HubProposalAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( HubProposalAdminHandlers.class );

    private final Supplier< HubProposalRepository > hubProposalRepository;
    private final Supplier< NodeMentionSimilarity > nodeMentionSimilarity;
    private final Supplier< HubProposalService > hubProposalService;

    public HubProposalAdminHandlers( final Supplier< HubProposalRepository > hubProposalRepository,
                                      final Supplier< NodeMentionSimilarity > nodeMentionSimilarity,
                                      final Supplier< HubProposalService > hubProposalService ) {
        this.hubProposalRepository = hubProposalRepository;
        this.nodeMentionSimilarity = nodeMentionSimilarity;
        this.hubProposalService = hubProposalService;
    }

    private HubProposalRepository getHubProposalRepo( final HttpServletResponse response ) throws IOException {
        final HubProposalRepository repo = hubProposalRepository.get();
        if ( repo == null ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Hub proposals not configured" );
        }
        return repo;
    }

    public void handleGetHubProposals( final HttpServletRequest request,
                                         final HttpServletResponse response ) throws IOException {
        final HubProposalRepository repo = getHubProposalRepo( response );
        if ( repo == null ) return;

        final String status = request.getParameter( "status" ) != null
            ? request.getParameter( "status" ) : "pending";
        final String hubName = request.getParameter( "hub" );
        final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 50 );
        final int offset = AdminKnowledgeIo.parseIntParam( request, "offset", 0 );

        final var proposals = repo.listProposals( status, hubName, limit, offset );
        final int total = repo.countByStatus( status );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "total", total );
        result.put( "proposals", proposals.stream().map( KnowledgeJsonMapper::hubProposalToMap ).toList() );
        AdminKnowledgeIo.sendJson( response, result );
    }

    @SuppressWarnings( "unchecked" )
    public void handlePostHubProposals( final HttpServletRequest request,
                                          final HttpServletResponse response,
                                          final String[] segments ) throws IOException {
        final HubProposalRepository repo = getHubProposalRepo( response );
        if ( repo == null ) return;

        if ( segments.length < 2 ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Action required" );
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
        final NodeMentionSimilarity sim = nodeMentionSimilarity.get();
        if ( sim == null ) {
            LOG.warn( "Hub proposals generate rejected: NodeMentionSimilarity not registered "
                + "(knowledge graph initialization likely failed — see earlier WARN)" );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                "NodeMentionSimilarity not available — knowledge graph not initialized" );
            return;
        }
        if ( !sim.isReady() ) {
            LOG.warn( "Hub proposals generate rejected: mention-centroid index not populated yet" );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                "Chunk embedding index must be populated before generating proposals" );
            return;
        }
        final HubProposalService service = hubProposalService.get();
        if ( service == null ) {
            LOG.warn( "Hub proposals generate rejected: HubProposalService not registered "
                + "(knowledge graph initialization likely failed — see earlier WARN)" );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubProposalService not available — knowledge graph not initialized" );
            return;
        }
        try {
            final int created = service.generateProposals();
            LOG.info( "Hub proposals generate: completed successfully, {} proposal(s) created", created );
            AdminKnowledgeIo.sendJson( response, Map.of(
                "status", "ok",
                "created", created,
                "message", "Hub proposals generated: " + created + " created" ) );
        } catch ( final RuntimeException e ) {
            LOG.error( "Hub proposals generate failed", e );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub proposals generation failed: " + e.getMessage() );
        }
    }

    private void handleHubProposalsBulkApprove( final HttpServletRequest request,
                                                 final HttpServletResponse response,
                                                 final HubProposalRepository repo ) throws IOException {
        final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
        if ( body == null ) return;
        final List< Integer > ids = AdminKnowledgeIo.GSON.fromJson( body.get( "ids" ),
            new TypeToken< List< Integer > >() {}.getType() );
        final String reviewedBy = optString( body, "reviewedBy", "admin" );
        repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
        AdminKnowledgeIo.sendJson( response, Map.of( "status", "ok" ) );
    }

    private void handleHubProposalsBulkReject( final HttpServletRequest request,
                                                final HttpServletResponse response,
                                                final HubProposalRepository repo ) throws IOException {
        final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
        if ( body == null ) return;
        final List< Integer > ids = AdminKnowledgeIo.GSON.fromJson( body.get( "ids" ),
            new TypeToken< List< Integer > >() {}.getType() );
        final String reviewedBy = optString( body, "reviewedBy", "admin" );
        final String reason = optString( body, "reason", null );
        repo.bulkUpdateStatus( ids, "rejected", reviewedBy, reason );
        AdminKnowledgeIo.sendJson( response, Map.of( "status", "ok" ) );
    }

    private void handleHubProposalsThresholdApprove( final HttpServletRequest request,
                                                      final HttpServletResponse response,
                                                      final HubProposalRepository repo ) throws IOException {
        final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
        if ( body == null ) return;
        final double threshold = AdminKnowledgeIo.getJsonDouble( body, "threshold", Double.NaN );
        if ( Double.isNaN( threshold ) ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "threshold must be a number" );
            return;
        }
        final String reviewedBy = optString( body, "reviewedBy", "admin" );
        final var ids = repo.listProposalsAboveThreshold( threshold ).stream()
            .map( HubProposalRepository.HubProposal::id ).toList();
        repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
        AdminKnowledgeIo.sendJson( response, Map.of( "status", "ok", "approved", ids.size() ) );
    }

    private void handleHubProposalByIdAction( final HttpServletRequest request,
                                                final HttpServletResponse response,
                                                final String[] segments,
                                                final HubProposalRepository repo ) throws IOException {
        if ( segments.length < 3 ) {
            AdminKnowledgeIo.sendNotFound( response, "Unknown hub-proposals action" );
            return;
        }
        final int id;
        try {
            id = Integer.parseInt( segments[1] );
        } catch ( final NumberFormatException e ) {
            LOG.info( "Rejecting hub-proposal request with non-numeric ID '{}': {}", segments[1], e.getMessage() );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID" );
            return;
        }
        switch ( segments[2] ) {
            case "approve" -> {
                repo.updateStatus( id, "approved", "admin", null );
                AdminKnowledgeIo.sendJson( response, Map.of( "status", "ok" ) );
            }
            case "reject" -> {
                final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
                final String reason = body != null ? AdminKnowledgeIo.getJsonString( body, "reason" ) : null;
                repo.updateStatus( id, "rejected", "admin", reason );
                AdminKnowledgeIo.sendJson( response, Map.of( "status", "ok" ) );
            }
            default -> AdminKnowledgeIo.sendNotFound( response, "Unknown action: " + segments[2] );
        }
    }

    /** Returns the string value of {@code key} if present and a JSON primitive, otherwise {@code defaultValue}. */
    private static String optString( final JsonObject body, final String key, final String defaultValue ) {
        return body.has( key ) && body.get( key ).isJsonPrimitive()
            ? body.get( key ).getAsString() : defaultValue;
    }
}
