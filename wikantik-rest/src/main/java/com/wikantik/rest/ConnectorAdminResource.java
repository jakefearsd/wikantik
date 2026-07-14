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

import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Admin endpoint for the source-connector sync stack ({@code wikantik-connectors}).
 *
 * <ul>
 *   <li>{@code GET  /admin/connectors} — lists every registered connector's {@link ConnectorStatus}.</li>
 *   <li>{@code POST /admin/connectors/{id}/sync} — triggers an immediate sync for connector {@code id},
 *       returning the resulting {@link SyncReport}.</li>
 *   <li>{@code GET  /admin/connectors/{id}/status} — returns the {@link ConnectorStatus} for connector {@code id}.</li>
 * </ul>
 *
 * <p>All endpoints are protected by {@code AdminAuthFilter} (the {@code /admin/*} filter mapping).
 * When the {@link ConnectorRuntime} manager is not registered (connectors disabled or not yet wired),
 * every endpoint responds {@code 503 Service Unavailable}. An unknown connector id responds {@code 404}.
 */
public class ConnectorAdminResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ConnectorAdminResource.class );

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() ) {
            handleList( runtime, response );
            return;
        }

        final String[] segments = path.split( "/" );
        if ( segments.length == 2 && "status".equals( segments[ 1 ] ) ) {
            handleStatus( runtime, segments[ 0 ], response );
        } else {
            sendNotFound( response, "Unknown connector endpoint: " + path );
        }
    }

    private void handleList( final ConnectorRuntime runtime, final HttpServletResponse response ) throws IOException {
        response.setStatus( HttpServletResponse.SC_OK );
        sendJson( response, runtime.list() );
    }

    private void handleStatus( final ConnectorRuntime runtime, final String connectorId,
                                final HttpServletResponse response ) throws IOException {
        try {
            final ConnectorStatus status = runtime.status( connectorId );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, status );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: status requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            sendNotFound( response, e.getMessage() );
        }
    }

    // -------------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() ) {
            sendNotFound( response, "Unknown connector endpoint" );
            return;
        }

        final String[] segments = path.split( "/" );
        if ( segments.length == 2 && "sync".equals( segments[ 1 ] ) ) {
            handleSync( runtime, segments[ 0 ], response );
        } else {
            sendNotFound( response, "Unknown connector endpoint: " + path );
        }
    }

    private void handleSync( final ConnectorRuntime runtime, final String connectorId,
                              final HttpServletResponse response ) throws IOException {
        try {
            LOG.info( "ConnectorAdminResource: sync requested for connector '{}'", connectorId );
            final SyncReport report = runtime.syncNow( connectorId );
            LOG.info( "ConnectorAdminResource: sync complete for connector '{}': {}", connectorId, report );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, report );
        } catch ( final com.wikantik.connectors.runtime.SyncInProgressException e ) {
            LOG.info( "ConnectorAdminResource: sync rejected for connector '{}': {}", connectorId, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: sync requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            sendNotFound( response, e.getMessage() );
        }
    }

    // -------------------------------------------------------------------------
    // Runtime resolution — overridable for tests
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link ConnectorRuntime} manager from the live engine. {@code getManager} is
     * declared on {@link com.wikantik.WikiEngine}, not the {@code Engine} interface returned by
     * {@link #getEngine()}, so this mirrors the resolution pattern used elsewhere in
     * {@code wikantik-rest} for managers not yet exposed via {@link com.wikantik.WikiSubsystems}.
     * Returns {@code null} when the engine is not a {@code WikiEngine} or the manager is not
     * registered (connectors disabled). Protected so tests can inject a stub without engine
     * infrastructure.
     */
    protected ConnectorRuntime resolveRuntime() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( ConnectorRuntime.class ) : null;
    }

    private void sendServiceUnavailable( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "Connector runtime is not available (connectors disabled or not yet wired)" );
    }
}
