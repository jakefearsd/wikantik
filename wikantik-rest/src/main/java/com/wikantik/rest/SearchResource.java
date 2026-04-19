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
import com.wikantik.search.hybrid.HybridSearchService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
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

        // Hybrid path: when HybridSearchService is present and enabled, fuse
        // BM25 + dense rankings. On any failure the service returns the BM25
        // names unchanged so search degrades gracefully, never breaks.
        final List< SearchResult > orderedResults = applyHybridRerank(
            engine, pm, query, safeResults );

        final List< Map< String, Object > > resultList = orderedResults.stream()
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
     * When {@link HybridSearchService} is present and enabled, reorder the BM25
     * {@link SearchResult} collection by the fused BM25+dense ranking and
     * append dense-only hits as minimal results (no contexts, score 0). When
     * hybrid is disabled or the embedder fails closed, returns the original
     * BM25 order verbatim.
     */
    private List< SearchResult > applyHybridRerank( final Engine engine,
                                                    final PageManager pm,
                                                    final String query,
                                                    final Collection< SearchResult > bm25 ) {
        final List< SearchResult > asList = new ArrayList<>( bm25 );
        final HybridSearchService hybrid = engine.getManager( HybridSearchService.class );
        if ( hybrid == null || !hybrid.isEnabled() ) {
            return asList;
        }
        final List< String > bm25Names = new ArrayList<>( asList.size() );
        final Map< String, SearchResult > byName = new LinkedHashMap<>();
        for ( final SearchResult sr : asList ) {
            final String name = sr.getPage() != null ? sr.getPage().getName() : null;
            if ( name == null ) continue;
            bm25Names.add( name );
            byName.putIfAbsent( name, sr );
        }
        final List< String > fused = hybrid.rerank( query, bm25Names );
        if ( fused == bm25Names || fused.equals( bm25Names ) ) {
            return asList;
        }
        final List< SearchResult > out = new ArrayList<>( fused.size() );
        for ( final String name : fused ) {
            final SearchResult existing = byName.get( name );
            if ( existing != null ) {
                out.add( existing );
                continue;
            }
            final Page page = pm.getPage( name );
            if ( page == null ) {
                // Dense index referenced a page that no longer exists — skip.
                LOG.debug( "Hybrid rerank: dense-only page '{}' not found by PageManager; skipping", name );
                continue;
            }
            out.add( new DenseOnlySearchResult( page ) );
        }
        return out;
    }

    /**
     * Minimal {@link SearchResult} for pages surfaced only via the dense index.
     * BM25 did not score these pages so we report {@code score = 0} and no
     * context snippets — the UI treats these as plain ranked hits.
     */
    private static final class DenseOnlySearchResult implements SearchResult {
        private final Page page;
        DenseOnlySearchResult( final Page page ) { this.page = page; }
        @Override public Page getPage() { return page; }
        @Override public int getScore() { return 0; }
        @Override public String[] getContexts() { return new String[ 0 ]; }
    }
}
