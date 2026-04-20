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
package com.wikantik.tools;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.search.SearchManager;
import com.wikantik.search.hybrid.HybridSearchService;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the {@code search_wiki} tool: BM25 search via {@link SearchManager},
 * optionally reranked by {@link HybridSearchService}, shaped into an LLM-friendly
 * JSON payload with citation URLs and snippets.
 *
 * <p>Uses the same hybrid-rerank logic as {@code SearchResource} so tool-server
 * results stay consistent with the REST API. Failures degrade gracefully: the
 * hybrid service's own failover returns BM25-only results when dense retrieval
 * is unavailable, so no extra error paths are needed here.</p>
 */
class SearchWikiTool {

    private static final Logger LOG = LogManager.getLogger( SearchWikiTool.class );
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int MAX_MAX_RESULTS = 25;

    private final Engine engine;
    private final ToolsConfig config;

    SearchWikiTool( final Engine engine, final ToolsConfig config ) {
        this.engine = engine;
        this.config = config;
    }

    /**
     * @param query      required non-blank search string
     * @param maxResults upper bound clamped to [1, {@value #MAX_MAX_RESULTS}]
     * @param request    HTTP request, used only to build citation URLs when no public base URL is set
     */
    Map< String, Object > execute( final String query, final int maxResults, final HttpServletRequest request ) {
        final int clamped = clampLimit( maxResults );
        final SearchManager sm = engine.getManager( SearchManager.class );
        final PageManager pm = engine.getManager( PageManager.class );

        final Collection< SearchResult > bm25;
        try {
            bm25 = sm.findPages( query, createContext() );
        } catch ( final Exception e ) {
            LOG.warn( "Tool-server search failed for query '{}': {}", query, e.getMessage() );
            final Map< String, Object > error = ResultShaper.orderedMap();
            error.put( "query", query );
            error.put( "results", List.of() );
            error.put( "total", 0 );
            error.put( "error", "search failed: " + e.getMessage() );
            return error;
        }

        final List< SearchResult > ordered = applyHybridRerank( pm, query,
                bm25 != null ? bm25 : List.of() );

        final String publicBaseUrl = config.publicBaseUrl();
        final List< Map< String, Object > > shaped = new ArrayList<>();
        for ( final SearchResult sr : ordered ) {
            if ( shaped.size() >= clamped ) {
                break;
            }
            final Page page = sr.getPage();
            if ( page == null ) {
                continue;
            }
            final Map< String, Object > entry = ResultShaper.orderedMap();
            entry.put( "name", page.getName() );
            entry.put( "url", ResultShaper.citationUrl( page.getName(), request, publicBaseUrl ) );
            entry.put( "score", sr.getScore() );

            final String raw = safePureText( pm, page.getName() );
            final String body = ResultShaper.bodyOnly( raw );
            ResultShaper.applyFrontmatter( entry, ResultShaper.frontmatter( raw ) );
            entry.put( "snippet", ResultShaper.snippet( sr.getContexts(), body ) );

            if ( page.getLastModified() != null ) {
                entry.put( "lastModified", page.getLastModified().toInstant().toString() );
            }
            if ( page.getAuthor() != null ) {
                entry.put( "author", page.getAuthor() );
            }
            shaped.add( entry );
        }

        final Map< String, Object > out = ResultShaper.orderedMap();
        out.put( "query", query );
        out.put( "results", shaped );
        out.put( "total", shaped.size() );
        return out;
    }

    /**
     * Builds a {@link Context} for the search. Overridable so unit tests can skip
     * the {@link Wiki} SPI — in production the anchor page is a throwaway used only
     * to give the Lucene search engine a "current page" for relative references.
     */
    Context createContext() {
        final Page anchor = Wiki.contents().page( engine, "Main" );
        return Wiki.context().create( engine, anchor );
    }

    private static int clampLimit( final int requested ) {
        if ( requested <= 0 ) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min( requested, MAX_MAX_RESULTS );
    }

    private static String safePureText( final PageManager pm, final String name ) {
        try {
            return pm.getPureText( name, -1 );
        } catch ( final Exception e ) {
            LOG.debug( "Could not load pure text for {}: {}", name, e.getMessage() );
            return "";
        }
    }

    /**
     * Mirror of {@code SearchResource.applyHybridRerank}: reorder by fused BM25+dense
     * ranking, appending dense-only hits as {@link DenseOnlySearchResult}. Returns the
     * BM25 order unchanged when hybrid retrieval is disabled or fails closed.
     */
    private List< SearchResult > applyHybridRerank( final PageManager pm, final String query,
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
            if ( name == null ) {
                continue;
            }
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
                continue;
            }
            out.add( new DenseOnlySearchResult( page ) );
        }
        return out;
    }

    /** Minimal {@link SearchResult} for pages surfaced only via the dense index. */
    private static final class DenseOnlySearchResult implements SearchResult {
        private final Page page;
        DenseOnlySearchResult( final Page page ) { this.page = page; }
        @Override public Page getPage() { return page; }
        @Override public int getScore() { return 0; }
        @Override public String[] getContexts() { return new String[ 0 ]; }
    }
}
