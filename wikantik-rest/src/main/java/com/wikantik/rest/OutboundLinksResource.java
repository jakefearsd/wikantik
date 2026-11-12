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


import com.wikantik.api.managers.ReferenceManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for outbound links (pages this page refers to).
 * <p>
 * Mapped to {@code /api/outbound-links/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/outbound-links/PageName} - List pages this page links to</li>
 * </ul>
 */
public class OutboundLinksResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( OutboundLinksResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        // Accept either path-style (/api/outbound-links/X) or query-string (?page=X).
        // The path form is the primary documented surface; ?page= mirrors backlinks.
        String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            pageName = request.getParameter( "page" );
        }
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Page name is required (use /api/outbound-links/{name} or ?page={name})" );
            return;
        }

        LOG.debug( "GET outbound links: {}", pageName );

        // Authorization: the link list of a restricted page is as sensitive as the page, and a viewable page
        // must not name the restricted pages it links to (same rule as the backlinks list).
        if ( !checkPagePermission( request, response, pageName, "view" ) ) {
            return;
        }

        final ReferenceManager refManager = getSubsystems().page().referenceManager();

        final Collection< String > refersTo = refManager.findRefersTo( pageName );
        final java.util.Set< String > viewable = filterViewable( request,
                refersTo == null ? List.of() : refersTo );

        final List< String > links = refersTo == null
                ? List.of()
                : refersTo.stream().filter( viewable::contains ).sorted().distinct().toList();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", pageName );
        result.put( "links", links );
        result.put( "count", links.size() );

        sendJson( response, result );
    }

}
