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

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

    /** D8: cap on {@code limit} to keep responses bounded; requests above this are rejected. */
    static final int MAX_LIMIT = 1_000;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        LOG.debug( "GET page list" );

        final String prefix = request.getParameter( "prefix" );
        final int limit = parseIntParam( request, "limit", DEFAULT_LIMIT );
        final int offset = parseIntParam( request, "offset", 0 );

        // D8: previously the negative limit (-1) propagated all the way to Stream.limit(-1)
        // which throws IllegalArgumentException — surfacing as a 500 with a stack trace.
        // Validate explicitly and return a 400 with a helpful message.
        if ( limit < 0 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "limit must be >= 0 (got " + limit + ")" );
            return;
        }
        if ( limit > MAX_LIMIT ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "limit must be <= " + MAX_LIMIT + " (got " + limit + ")" );
            return;
        }
        if ( offset < 0 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "offset must be >= 0 (got " + offset + ")" );
            return;
        }

        final PageManager pm = getSubsystems().page().pages();

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

        // Cluster membership lives in the structural index (frontmatter-derived),
        // not on the Page itself. Build a slug→cluster map so the sidebar can
        // group pages by cluster. Degrade gracefully if the index is unavailable.
        final Map< String, String > clusterBySlug = loadClusterBySlug();

        final List< Map< String, Object > > pageList = filtered.stream()
                .map( page -> {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    entry.put( "name", page.getName() );
                    entry.put( "lastModified", page.getLastModified() );
                    entry.put( "version", Math.max( page.getVersion(), 1 ) );
                    entry.put( "author", page.getAuthor() );
                    final String cluster = clusterBySlug.get( page.getName() );
                    if ( cluster != null ) {
                        entry.put( "cluster", cluster );
                    }
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
     * Slug → cluster map sourced from the structural index. Returns an empty
     * map (never null) when the index is unavailable or errors, so the page
     * list still renders — just without cluster grouping in the sidebar.
     */
    private Map< String, String > loadClusterBySlug() {
        final Map< String, String > clusterBySlug = new HashMap<>();
        try {
            final StructuralIndexService idx = getSubsystems().pageGraph().structuralIndexService();
            if ( idx == null ) {
                return clusterBySlug;
            }
            for ( final PageDescriptor d : idx.listPagesByFilter( StructuralFilter.none() ) ) {
                if ( d.cluster() != null && !d.cluster().isBlank() ) {
                    clusterBySlug.put( d.slug(), d.cluster() );
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not load cluster metadata for page list: {}", e.getMessage() );
        }
        return clusterBySlug;
    }

}
