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
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.search.SearchManager;

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
 * REST servlet for full-text search.
 * <p>
 * Mapped to {@code /api/search}. Handles:
 * <ul>
 *   <li>{@code GET /api/search?q=...&limit=20} - Full-text search</li>
 * </ul>
 */
public class SearchResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SearchResource.class );

    private static final int DEFAULT_LIMIT = 20;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String query = request.getParameter( "q" );
        if ( query == null || query.trim().isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Query parameter 'q' is required" );
            return;
        }

        final int limit = parseIntParam( request, "limit", DEFAULT_LIMIT );

        LOG.debug( "GET search: q={}, limit={}", query, limit );

        final Engine engine = getEngine();
        final SearchManager searchManager = engine.getManager( SearchManager.class );
        final Context context = Wiki.context().create( engine, request,
                ContextEnum.WIKI_FIND.getRequestContext() );

        final Collection< SearchResult > searchResults;
        try {
            searchResults = searchManager.findPages( query, context );
        } catch ( final Exception e ) {
            LOG.error( "Error executing search for '{}': {}", query, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error executing search: " + e.getMessage() );
            return;
        }

        final Collection< SearchResult > safeResults = searchResults != null
                ? searchResults
                : List.of();

        final PageManager pm = engine.getManager( PageManager.class );

        final List< Map< String, Object > > resultList = safeResults.stream()
                .limit( limit )
                .map( sr -> {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    final Page page = sr.getPage();
                    entry.put( "name", page.getName() );
                    entry.put( "score", sr.getScore() );

                    // Author and date from page metadata
                    if ( page.getAuthor() != null ) {
                        entry.put( "author", page.getAuthor() );
                    }
                    if ( page.getLastModified() != null ) {
                        entry.put( "lastModified", page.getLastModified() );
                    }

                    // Frontmatter metadata (summary, tags, cluster)
                    try {
                        final String rawText = pm.getPureText( page.getName(), -1 );
                        if ( rawText != null && !rawText.isEmpty() ) {
                            final ParsedPage parsed = FrontmatterParser.parse( rawText );
                            final var metadata = parsed.metadata();
                            if ( metadata.get( "summary" ) != null ) {
                                entry.put( "summary", metadata.get( "summary" ).toString() );
                            }
                            if ( metadata.get( "tags" ) != null ) {
                                entry.put( "tags", metadata.get( "tags" ) );
                            }
                            if ( metadata.get( "cluster" ) != null ) {
                                entry.put( "cluster", metadata.get( "cluster" ).toString() );
                            }
                        }
                    } catch ( final Exception e ) {
                        LOG.debug( "Could not parse frontmatter for {}: {}", page.getName(), e.getMessage() );
                    }

                    // Lucene context snippets (highlighted match fragments)
                    final String[] contexts = sr.getContexts();
                    if ( contexts != null && contexts.length > 0 ) {
                        entry.put( "contexts", List.of( contexts ) );
                    }

                    return entry;
                } )
                .toList();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "query", query );
        result.put( "results", resultList );
        result.put( "total", resultList.size() );

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
