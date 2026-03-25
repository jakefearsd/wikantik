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

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.diff.DifferenceManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST servlet for page version diffs.
 * <p>
 * Mapped to {@code /api/diff/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/diff/PageName?from=1&to=3} - Diff between two versions</li>
 * </ul>
 */
public class DiffResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( DiffResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        final String fromParam = request.getParameter( "from" );
        final String toParam = request.getParameter( "to" );

        if ( fromParam == null || toParam == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Both 'from' and 'to' version parameters are required" );
            return;
        }

        final int fromVersion;
        final int toVersion;
        try {
            fromVersion = Integer.parseInt( fromParam );
            toVersion = Integer.parseInt( toParam );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Version parameters must be integers" );
            return;
        }

        LOG.debug( "GET diff: {} from={} to={}", pageName, fromVersion, toVersion );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        try {
            final String fromText = pm.getPureText( pageName, fromVersion );
            final String toText = pm.getPureText( pageName, toVersion );

            final Context context = Wiki.context().create( engine, page );
            final DifferenceManager diffManager = engine.getManager( DifferenceManager.class );
            final String diff = diffManager.makeDiff( context, fromText, toText );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "page", pageName );
            result.put( "from", fromVersion );
            result.put( "to", toVersion );
            result.put( "diff", diff );

            sendJson( response, result );

        } catch ( final Exception e ) {
            LOG.error( "Error computing diff for {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error computing diff: " + e.getMessage() );
        }
    }

}
