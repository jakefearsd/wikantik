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

import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import com.wikantik.auth.user.UserDatabase;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST resource for managing DB-backed API keys used by the MCP and
 * OpenAPI tool servers. All requests are pre-authorized by {@link AdminAuthFilter}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /admin/apikeys} — list keys (hash masked)</li>
 *   <li>{@code POST /admin/apikeys} — generate a new key; response contains
 *       the plaintext once and never again</li>
 *   <li>{@code DELETE /admin/apikeys/{id}} — revoke a key</li>
 * </ul>
 */
public class AdminApiKeysResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminApiKeysResource.class );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private ApiKeyService service() {
        return ApiKeyServiceHolder.get( getEngine().getWikiProperties() );
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ApiKeyService svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "API key service unavailable — no datasource configured" );
            return;
        }
        final List< Map< String, Object > > rows = new ArrayList<>();
        for ( final ApiKeyService.Record r : svc.list() ) {
            rows.add( toJsonRow( r ) );
        }
        sendJson( response, Map.of( "keys", rows ) );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ApiKeyService svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "API key service unavailable — no datasource configured" );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String principal = getJsonString( body, "principalLogin" );
        final String label = getJsonString( body, "label" );
        final String scopeWire = getJsonString( body, "scope" );

        if ( principal == null || principal.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "principalLogin is required" );
            return;
        }

        if ( !principalExists( principal ) ) {
            // Reject phantom principals at the front door so we never persist a key whose
            // bearer resolves to "nobody". A silent no-match would either authenticate as
            // guest (confusing audit trails) or accidentally match a future loose-matching
            // policy evaluator; forcing an explicit UserDatabase lookup removes both risks.
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unknown principalLogin '" + principal + "' — no such user in the user database" );
            return;
        }

        final ApiKeyService.Scope scope;
        try {
            scope = ApiKeyService.Scope.fromWire( scopeWire );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid scope — must be one of mcp, tools, all" );
            return;
        }

        final String createdBy = currentLogin( request );
        try {
            final ApiKeyService.Generated generated = svc.generate( principal, label, scope, createdBy );
            LOG.info( "API key generated: id={}, principal={}, scope={}, by={}",
                    generated.record().id(), principal, scope.wire(), createdBy );
            final Map< String, Object > payload = new LinkedHashMap<>( toJsonRow( generated.record() ) );
            payload.put( "token", generated.plaintext() );
            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, payload );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final IllegalStateException e ) {
            LOG.error( "Failed to generate API key: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Key generation failed" );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ApiKeyService svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "API key service unavailable — no datasource configured" );
            return;
        }
        final String idStr = extractPathParam( request );
        if ( idStr == null || idStr.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Key id required in path" );
            return;
        }
        final int id;
        try {
            id = Integer.parseInt( idStr );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Key id must be numeric" );
            return;
        }
        final String revokedBy = currentLogin( request );
        final boolean revoked = svc.revoke( id, revokedBy );
        if ( !revoked ) {
            sendNotFound( response, "Key not found or already revoked: " + id );
            return;
        }
        LOG.info( "API key revoked: id={}, by={}", id, revokedBy );
        sendJson( response, Map.of( "success", true, "id", id ) );
    }

    private static String currentLogin( final HttpServletRequest request ) {
        final Principal p = request.getUserPrincipal();
        return p != null ? p.getName() : null;
    }

    /**
     * Returns {@code true} iff {@code login} resolves to a real account in the user
     * database. A missing {@link UserManager} (fresh deployment with no users table
     * wired yet) counts as "cannot validate, fail closed" so a misconfigured admin
     * panel can't silently mint keys against a non-existent identity.
     *
     * <p>Package-visible so unit tests running without a populated user database can
     * swap in a deterministic predicate, mirroring the override-for-tests pattern
     * used by {@code GetPageTool.canView}.</p>
     */
    boolean principalExists( final String login ) {
        final UserManager userManager = getEngine().getManager( UserManager.class );
        if ( userManager == null ) {
            return false;
        }
        final UserDatabase db = userManager.getUserDatabase();
        if ( db == null ) {
            return false;
        }
        try {
            return db.findByLoginName( login ) != null;
        } catch ( final NoSuchPrincipalException e ) {
            return false;
        }
    }

    /** Converts a Record to the API shape. The key_hash is exposed as a short fingerprint only. */
    private static Map< String, Object > toJsonRow( final ApiKeyService.Record r ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", r.id() );
        m.put( "principalLogin", r.principalLogin() );
        m.put( "label", r.label() );
        m.put( "scope", r.scope().wire() );
        m.put( "fingerprint", fingerprint( r.keyHash() ) );
        m.put( "createdAt", toIso( r.createdAt() ) );
        m.put( "createdBy", r.createdBy() );
        m.put( "lastUsedAt", toIso( r.lastUsedAt() ) );
        m.put( "revokedAt", toIso( r.revokedAt() ) );
        m.put( "revokedBy", r.revokedBy() );
        m.put( "active", r.isActive() );
        return m;
    }

    private static String fingerprint( final String hash ) {
        return hash != null && hash.length() >= 12 ? hash.substring( 0, 12 ) : hash;
    }

    private static String toIso( final Instant i ) {
        return i != null ? i.toString() : null;
    }
}
