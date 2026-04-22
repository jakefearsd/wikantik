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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.search.FrontmatterMetadataCache;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        if ( query == null || query.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Query parameter 'q' is required" );
            return;
        }

        final int limit = parseIntParam( request, "limit", DEFAULT_LIMIT );

        LOG.debug( "GET search: q={}, limit={}", query, limit );

        final Engine engine = getEngine();
        final SearchManager searchManager = engine.getManager( SearchManager.class );
        final Context context = Wiki.context().create( engine, request,
                ContextEnum.WIKI_FIND.getRequestContext() );

        // Kick off the query embedding in parallel with BM25. By the time
        // findPages returns the embedding is usually warm, so the dense
        // pass adds near-zero latency on top of BM25.
        final HybridSearchService hybrid = engine.getManager( HybridSearchService.class );
        final CompletableFuture< Optional< float[] > > embedFuture = hybrid != null
            ? hybrid.prefetchQueryEmbedding( query )
            : CompletableFuture.completedFuture( Optional.empty() );

        final Collection< SearchResult > searchResults;
        try {
            searchResults = searchManager.findPages( query, context );
        } catch ( final Exception e ) {
            LOG.error( "Error executing search for '{}': {}", query, e.getMessage() );
            embedFuture.cancel( true );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error executing search: " + e.getMessage() );
            return;
        }

        final Collection< SearchResult > safeResults = searchResults != null
                ? searchResults
                : List.of();

        final PageManager pm = engine.getManager( PageManager.class );
        final FrontmatterMetadataCache fmCache = engine.getManager( FrontmatterMetadataCache.class );

        // Hybrid path: when HybridSearchService is present and enabled, fuse
        // BM25 + dense rankings using the prefetched embedding. On any failure
        // the service returns the BM25 names unchanged so search degrades
        // gracefully, never breaks.
        final List< SearchResult > orderedResults = applyHybridRerank(
            hybrid, pm, query, safeResults, embedFuture );

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

                    // Frontmatter metadata (summary, tags, cluster) — served from a
                    // (pageName, lastModified)-keyed Caffeine cache so a /api/search call
                    // with 20 hits doesn't re-read and re-parse 20 markdown files.
                    final Map< String, Object > metadata = fmCache != null
                        ? fmCache.get( page.getName(), page.getLastModified() )
                        : Map.of();
                    if ( metadata.get( "summary" ) != null ) {
                        entry.put( "summary", metadata.get( "summary" ).toString() );
                    }
                    if ( metadata.get( "tags" ) != null ) {
                        entry.put( "tags", metadata.get( "tags" ) );
                    }
                    if ( metadata.get( "cluster" ) != null ) {
                        entry.put( "cluster", metadata.get( "cluster" ).toString() );
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
     * BM25 order verbatim. The {@code embedFuture} is the in-flight query
     * embedding kicked off in parallel with BM25; we await it here so the dense
     * pass overlaps with BM25 instead of running serially after it.
     */
    private List< SearchResult > applyHybridRerank( final HybridSearchService hybrid,
                                                    final PageManager pm,
                                                    final String query,
                                                    final Collection< SearchResult > bm25,
                                                    final CompletableFuture< Optional< float[] > > embedFuture ) {
        final List< SearchResult > asList = new ArrayList<>( bm25 );
        if ( hybrid == null || !hybrid.isEnabled() ) {
            embedFuture.cancel( true );
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
        final Optional< float[] > vec = awaitEmbedding( embedFuture );
        final List< String > fused = hybrid.rerankWith( query, bm25Names, vec );
        if ( fused.equals( bm25Names ) ) {
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
     * Block briefly on the in-flight query embedding. The embedding RPC has its
     * own internal timeout/circuit-breaker via {@link com.wikantik.search.hybrid.QueryEmbedder};
     * this outer wait is a request-side guard so a bug there can never wedge a
     * search request. On any failure or timeout we degrade to BM25-only.
     */
    private static final long EMBEDDING_AWAIT_MS = 2_500L;

    private static Optional< float[] > awaitEmbedding( final CompletableFuture< Optional< float[] > > f ) {
        try {
            return f.get( EMBEDDING_AWAIT_MS, TimeUnit.MILLISECONDS );
        } catch ( final TimeoutException te ) {
            f.cancel( true );
            LOG.debug( "Query embedding await timed out after {} ms; falling back to BM25", EMBEDDING_AWAIT_MS );
            return Optional.empty();
        } catch ( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch ( final ExecutionException ee ) {
            LOG.warn( "Query embedding await failed: {}", ee.getCause() != null ? ee.getCause().toString() : ee.toString() );
            return Optional.empty();
        }
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
