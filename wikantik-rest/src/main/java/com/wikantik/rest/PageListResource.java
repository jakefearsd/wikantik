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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for listing pages.
 * <p>
 * Mapped to {@code /api/pages} (no path info). Handles:
 * <ul>
 *   <li>{@code GET /api/pages} - List all pages with optional prefix filter and pagination</li>
 * </ul>
 * <p>
 * Query parameters:
 * <ul>
 *   <li>{@code prefix} - Filter pages whose names start with this prefix</li>
 *   <li>{@code limit} - Maximum number of results (default 100)</li>
 *   <li>{@code offset} - Number of results to skip (default 0)</li>
 * </ul>
 */
public class PageListResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageListResource.class );

    private static final int DEFAULT_LIMIT = 100;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        LOG.debug( "GET page list" );

        final String prefix = request.getParameter( "prefix" );
        final int limit = parseIntParam( request, "limit", DEFAULT_LIMIT );
        final int offset = parseIntParam( request, "offset", 0 );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Collection< Page > allPages;
        try {
            allPages = pm.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.error( "Error listing pages: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error listing pages: " + e.getMessage() );
            return;
        }

        // Filter by prefix if provided, sort alphabetically, then paginate
        List< Page > filtered = allPages.stream()
                .filter( page -> prefix == null || page.getName().startsWith( prefix ) )
                .sorted( Comparator.comparing( Page::getName ) )
                .toList();

        final int total = filtered.size();

        filtered = filtered.stream()
                .skip( offset )
                .limit( limit )
                .toList();

        final List< Map< String, Object > > pageList = filtered.stream()
                .map( page -> {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    entry.put( "name", page.getName() );
                    entry.put( "lastModified", page.getLastModified() );
                    entry.put( "version", Math.max( page.getVersion(), 1 ) );
                    entry.put( "author", page.getAuthor() );
                    return entry;
                } )
                .toList();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pages", pageList );
        result.put( "total", total );
        result.put( "offset", offset );
        result.put( "limit", limit );

        sendJson( response, result );
    }

    /**
     * Parses an integer query parameter with a default value.
     *
     * @param request      the HTTP request
     * @param paramName    the parameter name
     * @param defaultValue the default value if the parameter is missing or invalid
     * @return the parsed value or the default
     */
    private int parseIntParam( final HttpServletRequest request, final String paramName, final int defaultValue ) {
        final String value = request.getParameter( paramName );
        if ( value == null ) {
            return defaultValue;
        }
        try {
            return Integer.parseInt( value );
        } catch ( final NumberFormatException e ) {
            return defaultValue;
        }
    }

}
