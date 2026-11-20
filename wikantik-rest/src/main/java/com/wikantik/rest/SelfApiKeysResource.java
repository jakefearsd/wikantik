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
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.LegacyScopeUseLog;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Self-service REST resource: a logged-in user manages API keys bound to their OWN
 * principal. Rides the {@code /api/*} filter chain; every operation is scoped to the
 * caller's login and ownership is enforced server-side.
 *
 * <ul>
 *   <li>{@code GET    /api/self/apikeys}            — caller's active keys (metadata only)</li>
 *   <li>{@code POST   /api/self/apikeys}            — generate {label, scope}; token shown once.
 *       A caller without AllPermission may mint only {@link #SELF_SERVICE_SCOPES}</li>
 *   <li>{@code POST   /api/self/apikeys/{id}/rotate}— revoke + reissue (same label/scope)</li>
 *   <li>{@code DELETE /api/self/apikeys/{id}}       — revoke</li>
 * </ul>
 *
 * <p>A work agent's credential is not an API key and never appears here; a key with the {@code work} scope is an
 * ordinary personal key.</p>
 */
public class SelfApiKeysResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SelfApiKeysResource.class );

    /**
     * Scopes a caller without {@link AllPermission} may mint for themselves. {@code mcp_admin} and
     * {@code all} reach the admin MCP surface, so only administrators may self-mint them.
     */
    static final Set< ApiKeyService.Scope > SELF_SERVICE_SCOPES =
            Collections.unmodifiableSet( EnumSet.of( ApiKeyService.Scope.TOOLS, ApiKeyService.Scope.MCP_READ,
                    ApiKeyService.Scope.WORK ) );

    private static final String SELF_SERVICE_SCOPE_NAMES = SELF_SERVICE_SCOPES.stream()
            .map( ApiKeyService.Scope::wire ).collect( Collectors.joining( ", " ) );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private ApiKeyService service() {
        // Pass subsystem properties so ApiKeyServiceHolder.get() can initialize the service
        // from configuration when no test override has been installed. Returns the cached
        // instance immediately when one is available (production start or test override).
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

    /**
     * True when the caller holds {@link AllPermission} (silent check, no audit row). Package-visible
     * so unit tests can inject the decision without a live session.
     */
    boolean callerIsAdmin( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        return AuthSubsystemBridge.fromLegacyEngine( engine ).authorization()
                .isPermitted( session, new AllPermission( engine.getApplicationName() ) );
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

        if ( !SegmentRouteTable.dispatch( ROUTES, new KeyCall( this, svc, login ), "POST", request, response ) ) {
            sendNotFound( response, "Not found" );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        if ( !SegmentRouteTable.dispatch( ROUTES, new KeyCall( this, svc, login ), "DELETE", request, response ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, KEY_ID_REQUIRED );
        }
    }

    /** What a key route acts on: this servlet, the key service, and the signed-in caller. */
    private record KeyCall( SelfApiKeysResource servlet, ApiKeyService svc, String login ) {
    }

    private static final String KEY_ID_REQUIRED = "Key id required in path";

    private static final SegmentRouteTable< SegmentRouteTable.Action< KeyCall > > ROUTES =
        SegmentRouteTable.< SegmentRouteTable.Action< KeyCall > >builder()
            .add( "POST", "", ( k, req, resp, c ) -> k.servlet().generate( req, k.svc(), k.login(), resp ) )
            .add( "POST", "{}/rotate",
                ( k, req, resp, c ) -> k.servlet().rotateKey( req, resp, k.svc(), k.login(), c.get( 0 ) ) )
            .add( "DELETE", "{}", ( k, req, resp, c ) -> k.servlet().revokeKey( resp, k.svc(), k.login(), c.get( 0 ) ) )
            .build();

    private void rotateKey( final HttpServletRequest request, final HttpServletResponse response,
                            final ApiKeyService svc, final String login, final String idRaw ) throws IOException {
        final Integer id = parseId( idRaw );
        if ( id == null ) {
            sendNotFound( response, "Not found" );
            return;
        }
        rotate( svc, login, id, callerIsAdmin( request ), response );
    }

    private void revokeKey( final HttpServletResponse response, final ApiKeyService svc, final String login,
                            final String idRaw ) throws IOException {
        final Integer id = parseId( idRaw );
        if ( id == null ) { sendError( response, HttpServletResponse.SC_BAD_REQUEST, KEY_ID_REQUIRED ); return; }

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
        final boolean admin = callerIsAdmin( request );
        final String scopeWire = getJsonString( body, "scope" );
        final ApiKeyService.Scope scope;
        try {
            // An omitted scope defaults to the broadest scope this caller may mint.
            scope = ( scopeWire == null || scopeWire.isBlank() )
                    ? ( admin ? ApiKeyService.Scope.ALL : ApiKeyService.Scope.MCP_READ )
                    : ApiKeyService.Scope.fromWire( scopeWire );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid scope — must be one of " + ApiKeyService.Scope.validWireNames() );
            return;
        }
        if ( !mayMint( admin, scope ) ) {
            refuseScope( response, login, scope );
            return;
        }
        try {
            final ApiKeyService.Generated g = svc.generate( login, label, scope, login );
            if ( ApiKeyService.Scope.isLegacyWire( scopeWire ) ) {
                LegacyScopeUseLog.mintRequested( "/api/self/apikeys", login, request );
            }
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

    private void rotate( final ApiKeyService svc, final String login, final int id, final boolean admin,
            final HttpServletResponse response ) throws IOException {
        final Optional< ApiKeyService.Record > rec = svc.findById( id );
        if ( rec.isEmpty() || !login.equals( rec.get().principalLogin() ) || !rec.get().isActive() ) {
            sendNotFound( response, "Key not found: " + id );
            return;
        }
        final ApiKeyService.Record old = rec.get();
        // Rotation reissues the same scope, so it is held to the same minting rule as generate;
        // the existing key is left untouched when refused.
        if ( !mayMint( admin, old.scope() ) ) {
            refuseScope( response, login, old.scope() );
            return;
        }
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

    /** True if a caller with the given admin standing may hold a self-minted key of {@code scope}. */
    private static boolean mayMint( final boolean admin, final ApiKeyService.Scope scope ) {
        return admin || SELF_SERVICE_SCOPES.contains( scope );
    }

    private void refuseScope( final HttpServletResponse response, final String login,
            final ApiKeyService.Scope scope ) throws IOException {
        LOG.info( "Self API key scope '{}' refused for non-admin {}", scope.wire(), login );
        sendError( response, HttpServletResponse.SC_FORBIDDEN, "Scope '" + scope.wire()
                + "' requires administrator rights — you may create keys with scope " + SELF_SERVICE_SCOPE_NAMES );
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
        m.put( "legacyScope", r.legacyScope() );
        m.put( "createdAt", toIso( r.createdAt() ) );
        m.put( "lastUsedAt", toIso( r.lastUsedAt() ) );
        return m;
    }

    private void audit( final String eventType, final int keyId, final String label, final String actor ) {
        RestAuditSupport.recordApiKeyAudit( getEngine(), eventType, actor, keyId, label );
    }

    /** Returns the id from a "/{id}/rotate" pathInfo, or null if it isn't a rotate path. */
    /** The whole-number key id in a path segment, or null when it is not one. */
    private static Integer parseId( final String segment ) {
        try {
            return Integer.valueOf( segment );
        } catch ( final NumberFormatException e ) {
            // Client-supplied path; no id means the route does not match.
            LOG.debug( "Path segment '{}' is not an integer id: {}", segment, e.getMessage() );
            return null;
        }
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
