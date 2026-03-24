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
import com.wikantik.api.managers.ReferenceManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST servlet for page backlinks (referrers).
 * <p>
 * Mapped to {@code /api/backlinks/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/backlinks/PageName} - List pages that link to this page</li>
 * </ul>
 */
public class BacklinksResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( BacklinksResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        LOG.debug( "GET backlinks: {}", pageName );

        final Engine engine = getEngine();
        final ReferenceManager refManager = engine.getManager( ReferenceManager.class );

        final Set< String > referrers = refManager.findReferrers( pageName );

        final List< String > backlinks = referrers == null
                ? List.of()
                : referrers.stream().sorted().toList();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", pageName );
        result.put( "backlinks", backlinks );
        result.put( "count", backlinks.size() );

        sendJson( response, result );
    }

}
