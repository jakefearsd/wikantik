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

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.derived.ConnectorConfigService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoint for injecting, listing, and deleting connector secrets held by the
 * encrypted-at-rest {@link CredentialStore} (the {@code wikantik-connectors} credential
 * layer, P2 of the connector-credential-encryption design).
 *
 * <ul>
 *   <li>{@code GET    /admin/connector-credentials/{id}} — lists the secret <em>names</em>
 *       stored for connector {@code id}. Never returns secret values.</li>
 *   <li>{@code POST   /admin/connector-credentials/{id}/{name}} — stores a secret. The raw
 *       request body is the secret value; the response echoes only {@code connectorId} and
 *       {@code name} — never the secret itself.</li>
 *   <li>{@code DELETE /admin/connector-credentials/{id}/{name}} — deletes a stored secret.</li>
 * </ul>
 *
 * <p>All endpoints are protected by {@code AdminAuthFilter} (the {@code /admin/*} filter
 * mapping). When the {@link CredentialStore} manager is not registered, or is registered but
 * disabled (no master key configured), every endpoint responds {@code 503 Service Unavailable}.
 * A malformed or unmatched path responds {@code 404}.
 *
 * <p><strong>Secret hygiene:</strong> secret values are never written to a response body, never
 * logged, and never included in an exception message constructed here.
 */
public class ConnectorCredentialsResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ConnectorCredentialsResource.class );

    /** Maximum accepted secret length, in characters. */
    private static final int MAX_SECRET_LENGTH = 8192;

    // -------------------------------------------------------------------------
    // GET  /admin/connector-credentials/{id}
    // -------------------------------------------------------------------------

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final CredentialStore store = resolveStore();
        if ( store == null || !store.enabled() ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() ) {
            sendNotFound( response, "Connector id is required" );
            return;
        }

        final String[] segments = path.split( "/" );
        if ( segments.length != 1 ) {
            sendNotFound( response, "Unknown connector-credentials endpoint: " + path );
            return;
        }

        final List< String > names = store.list( segments[ 0 ] );
        response.setStatus( HttpServletResponse.SC_OK );
        sendJson( response, names );
    }

    // -------------------------------------------------------------------------
    // POST /admin/connector-credentials/{id}/{name}
    // -------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final CredentialStore store = resolveStore();
        if ( store == null || !store.enabled() ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        final String[] segments = path == null || path.isEmpty() ? new String[ 0 ] : path.split( "/" );
        if ( segments.length != 2 ) {
            sendNotFound( response, "Unknown connector-credentials endpoint: " + path );
            return;
        }

        final String connectorId = segments[ 0 ];
        final String name = segments[ 1 ];

        final String rawSecret = readSecret( request );
        if ( rawSecret == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "Secret exceeds maximum length of " + MAX_SECRET_LENGTH + " characters" );
            return;
        }
        // Strip surrounding whitespace (e.g. a trailing newline from a heredoc or editor) before
        // the blank check and storage — API keys/PATs/OAuth tokens never legitimately contain
        // leading/trailing whitespace, so a stray newline would otherwise be silently persisted
        // as part of the secret and break a future connector's auth.
        final String secret = rawSecret.strip();
        if ( secret.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Secret must not be blank" );
            return;
        }

        store.put( connectorId, name, secret );
        LOG.info( "ConnectorCredentialsResource: secret '{}' stored for connector '{}'", name, connectorId );
        recordAudit( "connector.credential.set", currentLogin( request ), connectorId, name );
        rebuildBestEffort();
        response.setStatus( HttpServletResponse.SC_CREATED );
        sendJson( response, Map.of( "connectorId", connectorId, "name", name ) );
    }

    // -------------------------------------------------------------------------
    // DELETE /admin/connector-credentials/{id}/{name}
    // -------------------------------------------------------------------------

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final CredentialStore store = resolveStore();
        if ( store == null || !store.enabled() ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        final String[] segments = path == null || path.isEmpty() ? new String[ 0 ] : path.split( "/" );
        if ( segments.length != 2 ) {
            sendNotFound( response, "Unknown connector-credentials endpoint: " + path );
            return;
        }

        final String connectorId = segments[ 0 ];
        final String name = segments[ 1 ];

        store.delete( connectorId, name );
        LOG.info( "ConnectorCredentialsResource: secret '{}' deleted for connector '{}'", name, connectorId );
        recordAudit( "connector.credential.delete", currentLogin( request ), connectorId, name );
        rebuildBestEffort();
        response.setStatus( HttpServletResponse.SC_NO_CONTENT );
    }

    // -------------------------------------------------------------------------
    // Body reading
    // -------------------------------------------------------------------------

    /**
     * Reads the raw request body (the secret value) up to {@link #MAX_SECRET_LENGTH} + 1
     * characters. Returns {@code null} when the body exceeds {@link #MAX_SECRET_LENGTH},
     * signalling the caller to reject with a 400 — the partial content is never surfaced
     * in a log line or exception message.
     */
    private String readSecret( final HttpServletRequest request ) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try ( BufferedReader reader = request.getReader() ) {
            final char[] buf = new char[ 1024 ];
            int n;
            while ( ( n = reader.read( buf ) ) != -1 ) {
                sb.append( buf, 0, n );
                if ( sb.length() > MAX_SECRET_LENGTH ) {
                    return null;
                }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Store resolution — overridable for tests
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link CredentialStore} manager from the live engine. {@code getManager} is
     * declared on {@link com.wikantik.WikiEngine}, not the {@code Engine} interface returned by
     * {@link #getEngine()}, so this mirrors the resolution pattern used by
     * {@code ConnectorAdminResource.resolveRuntime}. Returns {@code null} when the engine is not
     * a {@code WikiEngine} or the manager is not registered. Protected so tests can inject a stub
     * without engine infrastructure.
     */
    protected CredentialStore resolveStore() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( CredentialStore.class ) : null;
    }

    /**
     * Resolves the {@link ConnectorConfigService} manager. {@code null} when the engine is not a
     * {@code WikiEngine} or the manager is not registered — the post-mutation rebuild is then a
     * no-op. Protected so tests can inject a stub.
     */
    protected ConnectorConfigService resolveConfigService() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( ConnectorConfigService.class ) : null;
    }

    /** Best-effort hot-rebuild of the live connector registry after a credential change (e.g. a
     *  rotated gdrive client_secret or github token) so the new secret reaches the running
     *  connector immediately. Never fails the mutation response — a broken rebuild is logged and
     *  swallowed here. */
    private void rebuildBestEffort() {
        try {
            final ConnectorConfigService configService = resolveConfigService();
            if ( configService != null ) {
                configService.rebuild();
            }
        } catch ( final Exception e ) {
            LOG.warn( "ConnectorCredentialsResource: post-mutation connector rebuild failed: {}", e.getMessage(), e );
        }
    }

    private void sendServiceUnavailable( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "Credential store is not available (no master key configured or not yet wired)" );
    }

    /** Records a {@code category=ADMIN} audit entry mirroring {@link ConnectorAdminResource}'s
     *  idiom exactly: {@code getEngine() instanceof WikiEngine} to reach {@link
     *  com.wikantik.WikiEngine#getAuditService()}, wrapped in try/catch so a broken audit backend
     *  never fails the mutation itself (mutation already succeeded by the time this runs).
     *  {@code targetLabel} is the credential NAME — never its value. */
    private void recordAudit( final String eventType, final String actorLogin, final String connectorId,
                               final String targetLabel ) {
        try {
            final AuditService audit = getEngine() instanceof com.wikantik.WikiEngine wikiEngine
                    ? wikiEngine.getAuditService() : null;
            if ( audit != null ) {
                final AuditEntry.Builder entry = AuditEntry.builder()
                        .eventTime( Instant.now() )
                        .category( AuditCategory.ADMIN )
                        .eventType( eventType )
                        .outcome( AuditOutcome.SUCCESS )
                        .actorPrincipal( actorLogin )
                        .actorType( "user" )
                        .targetType( "connector" )
                        .targetId( connectorId );
                if ( targetLabel != null ) entry.targetLabel( targetLabel );
                audit.record( entry.build() );
            }
        } catch ( final Exception auditEx ) {
            LOG.warn( "Failed to record audit entry for connector {} '{}': {}",
                eventType, connectorId, auditEx.getMessage(), auditEx );
        }
    }

    private static String currentLogin( final HttpServletRequest request ) {
        final Principal p = request.getUserPrincipal();
        return p != null ? p.getName() : null;
    }
}
