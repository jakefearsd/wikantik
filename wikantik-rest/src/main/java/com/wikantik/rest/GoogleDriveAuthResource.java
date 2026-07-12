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

import com.wikantik.api.connectors.DriveAuthCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/** Admin OAuth2 consent flow for the Google Drive connector: {@code /admin/connector-oauth/gdrive/{id}/authorize}
 *  starts consent; {@code /admin/connector-oauth/gdrive/callback} stores the resulting refresh token.
 *  The OAuth code and all tokens are never logged or echoed. */
public class GoogleDriveAuthResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( GoogleDriveAuthResource.class );
    private static final String STATE_ATTR = "gdrive.oauth.state";
    private static final String CONN_ATTR  = "gdrive.oauth.connector";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final DriveAuthCoordinator coordinator = resolveCoordinator();
        if ( coordinator == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Drive OAuth not configured" );
            return;
        }
        final String path = request.getPathInfo();   // e.g. /gd/authorize  or  /callback
        if ( path != null && path.endsWith( "/authorize" ) ) {
            handleAuthorize( request, response, coordinator, path );
        } else if ( "/callback".equals( path ) ) {
            handleCallback( request, response, coordinator );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown OAuth route" );
        }
    }

    private void handleAuthorize( final HttpServletRequest request, final HttpServletResponse response,
            final DriveAuthCoordinator coordinator, final String path ) throws IOException {
        final String id = path.substring( 1, path.length() - "/authorize".length() );   // strip leading '/' and suffix
        if ( id.isEmpty() || id.contains( "/" ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Bad connector id" );
            return;
        }
        final byte[] nonce = new byte[ 32 ];
        RANDOM.nextBytes( nonce );
        final String state = Base64.getUrlEncoder().withoutPadding().encodeToString( nonce );
        final Optional< String > url = coordinator.authorizationUrl( id, state );
        if ( url.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown Drive connector: " + id );
            return;
        }
        request.getSession( true ).setAttribute( STATE_ATTR, state );
        request.getSession( true ).setAttribute( CONN_ATTR, id );
        response.sendRedirect( url.get() );   // 302 to Google consent
    }

    private void handleCallback( final HttpServletRequest request, final HttpServletResponse response,
            final DriveAuthCoordinator coordinator ) throws IOException {
        final String stateParam = request.getParameter( "state" );
        final String code = request.getParameter( "code" );
        final Object expectedState = request.getSession().getAttribute( STATE_ATTR );
        final Object connectorId  = request.getSession().getAttribute( CONN_ATTR );
        // single-use: clear regardless of outcome
        request.getSession().removeAttribute( STATE_ATTR );
        request.getSession().removeAttribute( CONN_ATTR );
        if ( expectedState == null || stateParam == null || !expectedState.equals( stateParam ) || connectorId == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid or expired OAuth state" );
            return;
        }
        if ( code == null || code.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Missing authorization code" );
            return;
        }
        final boolean ok = coordinator.completeAuthorization( connectorId.toString(), code );   // never logs the code
        if ( ok ) {
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, Map.of( "connectorId", connectorId.toString(), "status", "authorized" ) );
        } else {
            sendError( response, HttpServletResponse.SC_BAD_GATEWAY, "Authorization failed" );
        }
    }

    /** Resolves the coordinator via the engine; overridable for tests. */
    protected DriveAuthCoordinator resolveCoordinator() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( DriveAuthCoordinator.class ) : null;
    }
}
