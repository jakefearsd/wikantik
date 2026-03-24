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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for authentication information.
 * <p>
 * Mapped to {@code /api/auth/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/auth/user} - Returns current session/user info</li>
 * </ul>
 */
public class AuthResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AuthResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );

        if ( "user".equals( action ) ) {
            handleGetUser( request, response );
        } else {
            sendNotFound( response, "Unknown auth endpoint: " + action );
        }
    }

    /**
     * Returns current user session information.
     */
    private void handleGetUser( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "GET auth/user" );

        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "authenticated", session.isAuthenticated() );

        if ( session.isAuthenticated() ) {
            result.put( "username", session.getUserPrincipal().getName() );
            result.put( "loginPrincipal", session.getLoginPrincipal().getName() );
        } else {
            result.put( "username", "anonymous" );
            result.put( "loginPrincipal", session.getLoginPrincipal().getName() );
        }

        final List< String > roles = Arrays.stream( session.getRoles() )
                .map( Principal::getName )
                .toList();
        result.put( "roles", roles );

        sendJson( response, result );
    }

}
