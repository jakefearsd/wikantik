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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Self-service REST resource: a logged-in user manages API keys bound to their OWN
 * principal. Rides the {@code /api/*} filter chain; every operation is scoped to the
 * caller's login and ownership is enforced server-side.
 *
 * <ul>
 *   <li>{@code GET    /api/self/apikeys}            — caller's active keys (metadata only)</li>
 *   <li>{@code POST   /api/self/apikeys}            — generate {label, scope}; token shown once</li>
 *   <li>{@code POST   /api/self/apikeys/{id}/rotate}— revoke + reissue (same label/scope)</li>
 *   <li>{@code DELETE /api/self/apikeys/{id}}       — revoke</li>
 * </ul>
 */
public class SelfApiKeysResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SelfApiKeysResource.class );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private ApiKeyService service() {
        // Pass null — ApiKeyServiceHolder.get() returns the cached instance immediately
        // when one has been installed (production start or test override). Avoids
        // a getSubsystems() call that may fail in lightweight unit-test contexts.
        final com.wikantik.WikiSubsystems sub = getSubsystems();
        final java.util.Properties props = ( sub != null && sub.core() != null )
                ? sub.core().properties().asProperties() : null;
        return ApiKeyServiceHolder.get( props );
    }

    /**
     * Returns the caller's login name if authenticated, else {@code null}. Package-visible
     * so unit tests can inject an identity without a live session.
     */
    String authenticatedLogin( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        return session.isAuthenticated() ? session.getLoginPrincipal().getName() : null;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        final List< Map< String, Object > > rows = new ArrayList<>();
        for ( final ApiKeyService.Record r : svc.listByPrincipal( login ) ) {
            rows.add( selfRow( r ) );
        }
        sendJson( response, Map.of( "keys", rows ) );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        final Integer rotateId = parseRotateId( request.getPathInfo() );
        if ( rotateId != null ) {
            rotate( svc, login, rotateId, response );
            return;
        }
        if ( request.getPathInfo() != null && !"/".equals( request.getPathInfo() ) ) {
            sendNotFound( response, "Not found" );
            return;
        }
        generate( request, svc, login, response );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        final Integer id = parseLeadingId( request.getPathInfo() );
        if ( id == null ) { sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Key id required in path" ); return; }

        // Ownership gate: unknown OR not-owned OR already-revoked all collapse to 404 (no oracle).
        final Optional< ApiKeyService.Record > rec = svc.findById( id );
        if ( rec.isEmpty() || !login.equals( rec.get().principalLogin() ) || !rec.get().isActive() ) {
            sendNotFound( response, "Key not found: " + id );
            return;
        }
        if ( !svc.revoke( id, login ) ) {
            sendNotFound( response, "Key not found or already revoked: " + id );
            return;
        }
        audit( "apikey.revoke", id, rec.get().label(), login );
        LOG.info( "Self API key revoked: id={}, by={}", id, login );
        sendJson( response, Map.of( "success", true, "id", id ) );
    }

    private void generate( final HttpServletRequest request, final ApiKeyService svc,
            final String login, final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;                    // parseJsonBody already sent 400
        final String label = getJsonString( body, "label" );
        final ApiKeyService.Scope scope;
        try {
            scope = ApiKeyService.Scope.fromWire( getJsonString( body, "scope" ) );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid scope — must be one of mcp, tools, all" );
            return;
        }
        try {
            final ApiKeyService.Generated g = svc.generate( login, label, scope, login );
            audit( "apikey.issue", g.record().id(), g.record().label(), login );
            LOG.info( "Self API key generated: id={}, by={}, scope={}", g.record().id(), login, scope.wire() );
            respondWithToken( response, g );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final IllegalStateException e ) {
            LOG.error( "Self API key generation failed for {}: {}", login, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Key generation failed" );
        }
    }

    private void rotate( final ApiKeyService svc, final String login, final int id,
            final HttpServletResponse response ) throws IOException {
        final Optional< ApiKeyService.Record > rec = svc.findById( id );
        if ( rec.isEmpty() || !login.equals( rec.get().principalLogin() ) || !rec.get().isActive() ) {
            sendNotFound( response, "Key not found: " + id );
            return;
        }
        final ApiKeyService.Record old = rec.get();
        if ( !svc.revoke( id, login ) ) {
            sendNotFound( response, "Key not found or already revoked: " + id );
            return;
        }
        try {
            final ApiKeyService.Generated g = svc.generate( login, old.label(), old.scope(), login );
            audit( "apikey.rotate", g.record().id(), g.record().label(), login );
            LOG.info( "Self API key rotated: old={}, new={}, by={}", id, g.record().id(), login );
            respondWithToken( response, g );
        } catch ( final IllegalStateException e ) {
            LOG.error( "Self API key rotate (reissue) failed for {}: {}", login, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Key rotation failed" );
        }
    }

    private void respondWithToken( final HttpServletResponse response, final ApiKeyService.Generated g )
            throws IOException {
        final Map< String, Object > payload = new LinkedHashMap<>( selfRow( g.record() ) );
        payload.put( "token", g.plaintext() );
        response.setStatus( HttpServletResponse.SC_CREATED );
        sendJson( response, payload );
    }

    /** Metadata-only row: never the hash, never the plaintext, never the principal (always self). */
    private static Map< String, Object > selfRow( final ApiKeyService.Record r ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", r.id() );
        m.put( "label", r.label() );
        m.put( "scope", r.scope().wire() );
        m.put( "createdAt", toIso( r.createdAt() ) );
        m.put( "lastUsedAt", toIso( r.lastUsedAt() ) );
        return m;
    }

    private void audit( final String eventType, final int keyId, final String label, final String actor ) {
        try {
            final AuditService a = getEngine() instanceof com.wikantik.WikiEngine we ? we.getAuditService() : null;
            if ( a == null ) return;
            a.record( AuditEntry.builder()
                    .eventTime( Instant.now() )
                    .category( AuditCategory.ADMIN )
                    .eventType( eventType )
                    .outcome( AuditOutcome.SUCCESS )
                    .actorPrincipal( actor )
                    .actorType( "user" )
                    .targetType( "apikey" )
                    .targetId( String.valueOf( keyId ) )
                    .targetLabel( label != null ? label : String.valueOf( keyId ) )
                    .build() );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to record audit entry for {} (key {}): {}", eventType, keyId, e.getMessage(), e );
        }
    }

    /** Returns the id from a "/{id}/rotate" pathInfo, or null if it isn't a rotate path. */
    private static Integer parseRotateId( final String pathInfo ) {
        if ( pathInfo == null ) return null;
        final String[] parts = pathInfo.split( "/" );   // "/7/rotate" -> ["", "7", "rotate"]
        if ( parts.length == 3 && "rotate".equals( parts[2] ) ) {
            try { return Integer.valueOf( parts[1] ); } catch ( final NumberFormatException e ) { return null; }
        }
        return null;
    }

    /** Returns the leading numeric id from a "/{id}" pathInfo, or null. */
    private static Integer parseLeadingId( final String pathInfo ) {
        if ( pathInfo == null ) return null;
        final String[] parts = pathInfo.split( "/" );   // "/7" -> ["", "7"]
        if ( parts.length == 2 && !parts[1].isBlank() ) {
            try { return Integer.valueOf( parts[1] ); } catch ( final NumberFormatException e ) { return null; }
        }
        return null;
    }

    private static String toIso( final Instant i ) {
        return i != null ? i.toString() : null;
    }

    private void unauthorized( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
    }

    private void unavailable( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "API key service unavailable — no datasource configured" );
    }
}
