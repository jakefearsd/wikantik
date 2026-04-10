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

import com.wikantik.knowledge.HubDiscoveryException;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryRepository.DismissedProposal;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubNameCollisionException;
import com.wikantik.rest.dto.AcceptProposalRequest;
import com.wikantik.rest.dto.BulkDeleteDismissedRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST servlet for the hub-discovery workflow.
 *
 * <p>Mapped to {@code /admin/knowledge/hub-discovery/*}.
 *
 * <ul>
 *   <li>{@code POST   /run} — trigger a discovery run</li>
 *   <li>{@code GET    /proposals} — list pending proposals (paged)</li>
 *   <li>{@code POST   /proposals/{id}/accept} — accept a proposal</li>
 *   <li>{@code POST   /proposals/{id}/dismiss} — dismiss a proposal</li>
 *   <li>{@code GET    /proposals/dismissed} — list dismissed proposals (paged)</li>
 *   <li>{@code DELETE /proposals/dismissed/{id}} — hard-delete a single dismissed row</li>
 *   <li>{@code POST   /proposals/dismissed/bulk-delete} — bulk hard-delete dismissed rows</li>
 * </ul>
 */
public class AdminHubDiscoveryResource extends RestServletBase {

    private static final Logger LOG = LogManager.getLogger( AdminHubDiscoveryResource.class );
    private static final long serialVersionUID = 1L;

    private static final int MAX_LIMIT = 200;
    private static final int MAX_BULK_DELETE_IDS = 500;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final String path = request.getPathInfo();
        if ( "/proposals".equals( path ) ) {
            handleListProposals( request, response );
        } else if ( "/proposals/dismissed".equals( path ) ) {
            handleListDismissed( request, response );
        } else if ( "/hubs".equals( path ) ) {
            handleListHubs( request, response );
        } else if ( path != null && path.startsWith( "/hubs/" ) ) {
            handleHubDrilldown( path, response );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request,
                           final HttpServletResponse response ) throws IOException {
        final String path = request.getPathInfo();
        if ( path == null ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Path required" );
            return;
        }
        if ( "/run".equals( path ) ) {
            handleRun( request, response );
        } else if ( path.matches( "/proposals/\\d+/accept" ) ) {
            final int id = extractId( path );
            handleAccept( id, request, response );
        } else if ( path.matches( "/proposals/\\d+/dismiss" ) ) {
            final int id = extractId( path );
            handleDismiss( id, request, response );
        } else if ( "/proposals/dismissed/bulk-delete".equals( path ) ) {
            handleBulkDeleteDismissed( request, response );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request,
                             final HttpServletResponse response ) throws IOException {
        final String path = request.getPathInfo();
        if ( path != null && path.matches( "/proposals/dismissed/\\d+" ) ) {
            final int id = Integer.parseInt( path.substring( path.lastIndexOf( '/' ) + 1 ) );
            handleDeleteDismissed( id, response );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
        }
    }

    // ---- handlers ----

    private void handleRun( final HttpServletRequest request,
                             final HttpServletResponse response ) throws IOException {
        final HubDiscoveryService svc = getEngine().getManager( HubDiscoveryService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryService is not available" );
            return;
        }
        try {
            final HubDiscoveryService.RunSummary summary = svc.runDiscovery();
            sendJson( response, summary );
        } catch ( final HubDiscoveryException e ) {
            // LOG.error justified: admin-triggered discovery run failure must surface full stack trace
            LOG.error( "Hub discovery run failed", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub discovery run failed: " + e.getMessage() );
        }
    }

    private void handleListProposals( final HttpServletRequest request,
                                       final HttpServletResponse response ) throws IOException {
        final HubDiscoveryRepository repo = getEngine().getManager( HubDiscoveryRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryRepository is not available" );
            return;
        }
        int limit = parseIntParam( request, "limit", 50 );
        if ( limit > MAX_LIMIT ) {
            limit = MAX_LIMIT;
        }
        if ( limit < 1 ) {
            limit = 1;
        }
        final int offset = Math.max( 0, parseIntParam( request, "offset", 0 ) );

        final List< HubDiscoveryRepository.HubDiscoveryProposal > proposals = repo.list( limit, offset );
        final int total = repo.count();

        final List< Map< String, Object > > serializable = new ArrayList<>();
        for ( final HubDiscoveryRepository.HubDiscoveryProposal p : proposals ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "id", p.id() );
            m.put( "suggestedName", p.suggestedName() );
            m.put( "exemplarPage", p.exemplarPage() );
            m.put( "memberPages", p.memberPages() );
            m.put( "coherenceScore", p.coherenceScore() );
            m.put( "created", p.created() != null ? p.created().toString() : null );
            serializable.add( m );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "total", total );
        result.put( "limit", limit );
        result.put( "offset", offset );
        result.put( "proposals", serializable );
        sendJson( response, result );
    }

    private void handleAccept( final int id,
                                final HttpServletRequest request,
                                final HttpServletResponse response ) throws IOException {
        final HubDiscoveryService svc = getEngine().getManager( HubDiscoveryService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryService is not available" );
            return;
        }

        final AcceptProposalRequest body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = GSON.fromJson( reader, AcceptProposalRequest.class );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return;
        }

        final String name = ( body == null || body.name == null ) ? "" : body.name.trim();
        if ( name.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "name must not be empty" );
            return;
        }
        final List< String > members = ( body.members == null ) ? List.of() : body.members;
        if ( members.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "members must not be empty" );
            return;
        }

        final String reviewer = resolveReviewer( request );
        try {
            final HubDiscoveryService.AcceptResult result = svc.acceptProposal( id, name, members, reviewer );
            sendJson( response, Map.of(
                "createdPage", result.createdPage(),
                "members", result.memberCount() ) );
        } catch ( final HubNameCollisionException e ) {
            sendError( response, HttpServletResponse.SC_CONFLICT,
                "Hub name collides with existing page: " + e.collidingName() );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final HubDiscoveryException e ) {
            final String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if ( msg.contains( "not found" ) ) {
                sendError( response, HttpServletResponse.SC_NOT_FOUND, e.getMessage() );
            } else {
                sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            }
        }
    }

    private void handleDismiss( final int id,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response ) throws IOException {
        final HubDiscoveryService svc = getEngine().getManager( HubDiscoveryService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryService is not available" );
            return;
        }

        final String reviewer = resolveReviewer( request );
        try {
            svc.dismissProposal( id, reviewer );
            response.setStatus( HttpServletResponse.SC_NO_CONTENT );
        } catch ( final HubDiscoveryException e ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, e.getMessage() );
        }
    }

    private void handleListDismissed( final HttpServletRequest request,
                                       final HttpServletResponse response ) throws IOException {
        final HubDiscoveryRepository repo = getEngine().getManager( HubDiscoveryRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryRepository is not available" );
            return;
        }
        int limit = parseIntParam( request, "limit", 50 );
        if ( limit > MAX_LIMIT ) {
            limit = MAX_LIMIT;
        }
        if ( limit < 1 ) {
            limit = 1;
        }
        final int offset = Math.max( 0, parseIntParam( request, "offset", 0 ) );

        final List< DismissedProposal > rows = repo.listDismissed( limit, offset );
        final int total = repo.countDismissed();

        final List< Map< String, Object > > serializable = new ArrayList<>();
        for ( final DismissedProposal p : rows ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "id", p.id() );
            m.put( "suggestedName", p.suggestedName() );
            m.put( "exemplarPage", p.exemplarPage() );
            m.put( "memberPages", p.memberPages() );
            m.put( "coherenceScore", p.coherenceScore() );
            m.put( "created", p.created() != null ? p.created().toString() : null );
            m.put( "reviewedBy", p.reviewedBy() );
            m.put( "reviewedAt", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
            serializable.add( m );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "total", total );
        result.put( "limit", limit );
        result.put( "offset", offset );
        result.put( "proposals", serializable );
        sendJson( response, result );
    }

    private void handleDeleteDismissed( final int id, final HttpServletResponse response )
            throws IOException {
        final HubDiscoveryRepository repo = getEngine().getManager( HubDiscoveryRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryRepository is not available" );
            return;
        }
        if ( repo.deleteDismissed( id ) ) {
            response.setStatus( HttpServletResponse.SC_NO_CONTENT );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND,
                "Dismissed hub discovery proposal " + id + " not found" );
        }
    }

    private void handleBulkDeleteDismissed( final HttpServletRequest request,
                                             final HttpServletResponse response ) throws IOException {
        final HubDiscoveryRepository repo = getEngine().getManager( HubDiscoveryRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryRepository is not available" );
            return;
        }

        final BulkDeleteDismissedRequest body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = GSON.fromJson( reader, BulkDeleteDismissedRequest.class );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return;
        }

        if ( body == null || body.ids == null || body.ids.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "ids must not be empty" );
            return;
        }
        if ( body.ids.size() > MAX_BULK_DELETE_IDS ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "ids exceeds maximum of " + MAX_BULK_DELETE_IDS );
            return;
        }

        final int deleted = repo.deleteDismissedBulk( body.ids );
        sendJson( response, Map.of( "deleted", deleted ) );
    }

    private void handleListHubs( final HttpServletRequest request,
                                  final HttpServletResponse response ) throws IOException {
        final com.wikantik.knowledge.HubOverviewService svc =
            getEngine().getManager( com.wikantik.knowledge.HubOverviewService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubOverviewService is not available" );
            return;
        }
        try {
            final List< com.wikantik.knowledge.HubOverviewService.HubOverviewSummary > hubs =
                svc.listHubOverviews();
            final List< Map< String, Object > > serializable = new ArrayList<>( hubs.size() );
            for ( final var h : hubs ) {
                final Map< String, Object > m = new LinkedHashMap<>();
                m.put( "name", h.name() );
                m.put( "memberCount", h.memberCount() );
                m.put( "inboundLinkCount", h.inboundLinkCount() );
                m.put( "nearMissCount", h.nearMissCount() );
                m.put( "coherence", Double.isNaN( h.coherence() ) ? null : h.coherence() );
                m.put( "hasBackingPage", h.hasBackingPage() );
                serializable.add( m );
            }
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "total", hubs.size() );
            result.put( "hubs", serializable );
            sendJson( response, result );
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered list failure must surface stack trace
            LOG.error( "Hub overview list failed", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub overview list failed: " + e.getMessage() );
        }
    }

    private void handleHubDrilldown( final String path, final HttpServletResponse response )
            throws IOException {
        // path = "/hubs/{encodedName}"
        final String encoded = path.substring( "/hubs/".length() );
        if ( encoded.isEmpty() || encoded.contains( "/" ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
            return;
        }
        final String hubName = java.net.URLDecoder.decode( encoded, java.nio.charset.StandardCharsets.UTF_8 );

        final com.wikantik.knowledge.HubOverviewService svc =
            getEngine().getManager( com.wikantik.knowledge.HubOverviewService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubOverviewService is not available" );
            return;
        }

        try {
            final var d = svc.loadDrilldown( hubName );
            if ( d == null ) {
                sendError( response, HttpServletResponse.SC_NOT_FOUND,
                    "Hub '" + hubName + "' not found" );
                return;
            }
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "name", d.name() );
            result.put( "hasBackingPage", d.hasBackingPage() );
            result.put( "coherence", Double.isNaN( d.coherence() ) ? null : d.coherence() );
            result.put( "members", d.members().stream().map( m -> {
                final Map< String, Object > x = new LinkedHashMap<>();
                x.put( "name", m.name() );
                x.put( "cosineToCentroid", Double.isNaN( m.cosineToCentroid() ) ? null : m.cosineToCentroid() );
                x.put( "hasPage", m.hasPage() );
                return x;
            } ).toList() );
            result.put( "stubMembers", d.stubMembers().stream().map( s ->
                Map.of( "name", s.name() ) ).toList() );
            result.put( "nearMissTfidf", d.nearMissTfidf().stream().map( n ->
                Map.of( "name", n.name(), "cosineToCentroid", n.cosineToCentroid() ) ).toList() );
            result.put( "moreLikeThisLucene", d.moreLikeThisLucene().stream().map( m ->
                Map.of( "name", m.name(), "luceneScore", m.luceneScore() ) ).toList() );
            result.put( "overlapHubs", d.overlapHubs().stream().map( o -> {
                final Map< String, Object > x = new LinkedHashMap<>();
                x.put( "name", o.name() );
                x.put( "centroidCosine", o.centroidCosine() );
                x.put( "sharedMemberCount", o.sharedMemberCount() );
                return x;
            } ).toList() );
            sendJson( response, result );
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered drilldown failure must surface stack trace
            LOG.error( "Hub overview drilldown failed for '{}'", hubName, e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub drilldown failed: " + e.getMessage() );
        }
    }

    // ---- helpers ----

    /**
     * Extracts the numeric id from a path like {@code /proposals/17/accept}.
     */
    private static int extractId( final String path ) {
        final String[] parts = path.split( "/" );
        // path = /proposals/{id}/action -> parts = ["", "proposals", "{id}", "action"]
        return Integer.parseInt( parts[ 2 ] );
    }

    private static String resolveReviewer( final HttpServletRequest request ) {
        final String user = request.getRemoteUser();
        return ( user != null && !user.isEmpty() ) ? user : "admin";
    }
}
