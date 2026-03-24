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
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for page version history.
 * <p>
 * Mapped to {@code /api/history/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/history/PageName} - Get version history for a page</li>
 * </ul>
 */
public class HistoryResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( HistoryResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        LOG.debug( "GET history: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        // Check if the page exists
        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        final List< ? extends Page > versions = pm.getVersionHistory( pageName );

        final List< Map< String, Object > > versionList;
        if ( versions == null || versions.isEmpty() ) {
            // Page exists but no version history — return the current page as the sole version
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "version", Math.max( page.getVersion(), 1 ) );
            entry.put( "author", page.getAuthor() );
            entry.put( "lastModified", page.getLastModified() );
            versionList = List.of( entry );
        } else {
            versionList = versions.stream()
                    .map( v -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "version", Math.max( v.getVersion(), 1 ) );
                        entry.put( "author", v.getAuthor() );
                        entry.put( "lastModified", v.getLastModified() );
                        return entry;
                    } )
                    .toList();
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", pageName );
        result.put( "versions", versionList );

        sendJson( response, result );
    }

}
