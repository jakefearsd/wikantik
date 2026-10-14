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

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystemBridge;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.permissions.PermissionFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the {@code search_wiki} tool: retrieves context via
 * {@link ContextRetrievalService} and shapes results into an LLM-friendly JSON
 * payload with citation URLs and snippets.
 *
 * <p>Delegates hybrid rerank, dense retrieval, and BM25 fallback to the
 * service, keeping this class focused on response shaping.</p>
 *
 * <p>The retrieval service applies no view-ACL filtering of its own, so this class drops every
 * result (and every {@code relatedPages} reference) the caller may not view, using the same
 * {@link PermissionFilter} rule {@link GetPageTool} enforces for {@code get_page}. Hidden results
 * are removed before the result cap and {@code total} are computed, and retrieval is always asked for
 * the full {@link ContextQuery#MAX_PAGES_CAP} so that a restricted hit ranked inside the top
 * {@code maxResults} is replaced by the next visible one instead of shortening the response. That
 * <em>narrows</em> the count oracle; it cannot close it entirely: if more than
 * {@code MAX_PAGES_CAP - maxResults} restricted pages outrank the visible ones the response can still
 * come back short (the same residual as the knowledge MCP's {@code retrieve_context}).</p>
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
     * @param request    the caller's HTTP request: its session decides which pages may be viewed, and it builds
     *                   citation URLs when no public base URL is set
     */
    Map< String, Object > execute( final String query, final int maxResults, final HttpServletRequest request ) {
        final int clamped = clampLimit( maxResults );
        final ContextRetrievalService ctxService =
                KnowledgeSubsystemBridge.fromLegacyEngine( engine ).contextRetrievalService();
        if ( ctxService == null ) {
            return errorResult( query, "ContextRetrievalService not configured" );
        }
        final RetrievalResult retrieval;
        try {
            // Over-fetch to the cap: hidden pages are dropped afterwards and the survivors trimmed to
            // maxResults, so restricted hits near the top do not shorten the response.
            retrieval = ctxService.retrieve( new ContextQuery( query, ContextQuery.MAX_PAGES_CAP, 3, null ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Tool-server search failed for query '{}': {}", query, e.getMessage() );
            return errorResult( query, "search failed: " + e.getMessage() );
        }

        // No catch: if the permission check itself fails the request fails (the servlet answers 500)
        // rather than falling back to unfiltered results.
        final Set< String > viewable = viewableNames( request, candidateNames( retrieval.pages() ) );

        final String publicBaseUrl = config.publicBaseUrl();
        final List< Map< String, Object > > shaped = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            if ( shaped.size() >= clamped ) break;
            if ( !viewable.contains( p.name() ) ) continue;
            shaped.add( buildEntry( p, request, publicBaseUrl, viewable ) );
        }
        final Map< String, Object > out = ResultShaper.orderedMap();
        out.put( "query", query );
        out.put( "results", shaped );
        out.put( "total", shaped.size() );

        logQuery( query, retrieval );
        return out;
    }

    /**
     * Returns the subset of {@code pageNames} the caller bound to {@code request} may view. Silent
     * (no {@code access.denied} audit rows): hiding restricted hits from a bulk result is a
     * visibility concern, not an enforcement denial. Package-visible so unit tests can substitute a
     * stub gate; production resolves the wiki session exactly as {@link GetPageTool#canView} does
     * (a stateless API-key request resolves to the anonymous guest session).
     */
    Set< String > viewableNames( final HttpServletRequest request, final Collection< String > pageNames ) {
        final Session session = Wiki.session().find( engine, request );
        return new PermissionFilter( engine ).filterViewableQuietly( session, pageNames );
    }

    /** Every page name the response could mention: each result plus each of its related pages. */
    private static Set< String > candidateNames( final List< RetrievedPage > pages ) {
        final Set< String > names = new LinkedHashSet<>();
        for ( final RetrievedPage p : pages ) {
            addName( names, p.name() );
            for ( final RelatedPage r : p.relatedPages() ) {
                addName( names, r.name() );
            }
        }
        return names;
    }

    private static void addName( final Set< String > names, final String name ) {
        if ( name != null && !name.isBlank() ) {
            names.add( name );
        }
    }

    /** Builds the fixed-error-shape response body shared by both {@link #execute} failure paths. */
    private static Map< String, Object > errorResult( final String query, final String message ) {
        final Map< String, Object > error = ResultShaper.orderedMap();
        error.put( "query", query );
        error.put( "results", List.of() );
        error.put( "total", 0 );
        error.put( "error", message );
        return error;
    }

    /** Shapes one {@link RetrievedPage} into its LLM-facing JSON entry. */
    private static Map< String, Object > buildEntry( final RetrievedPage p, final HttpServletRequest request,
                                                      final String publicBaseUrl, final Set< String > viewable ) {
        final Map< String, Object > entry = ResultShaper.orderedMap();
        entry.put( "name", p.name() );
        entry.put( "url", ResultShaper.citationUrl( p.name(), request, publicBaseUrl ) );
        entry.put( "score", p.score() );
        if ( !p.summary().isEmpty() ) entry.put( "summary", p.summary() );
        if ( !p.tags().isEmpty() ) entry.put( "tags", p.tags() );
        if ( p.cluster() != null ) entry.put( "cluster", p.cluster() );
        appendChunks( entry, p.contributingChunks() );
        appendRelatedPages( entry, p.relatedPages(), viewable );
        if ( p.lastModified() != null ) {
            entry.put( "lastModified", p.lastModified().toInstant().toString() );
        }
        if ( p.author() != null ) entry.put( "author", p.author() );
        return entry;
    }

    /** No-op when {@code chunks} is empty; otherwise adds the rich {@code contributingChunks}
     *  array plus the back-compat top-chunk {@code snippet}. */
    private static void appendChunks( final Map< String, Object > entry, final List< RetrievedChunk > chunks ) {
        if ( chunks.isEmpty() ) return;
        // Rich array for new consumers — matches MCP retrieve_context shape.
        final List< Map< String, Object > > chunksOut = new ArrayList<>( chunks.size() );
        for ( final RetrievedChunk c : chunks ) {
            final Map< String, Object > chunkEntry = ResultShaper.orderedMap();
            chunkEntry.put( "headingPath", c.headingPath() );
            chunkEntry.put( "text", c.text() );
            chunkEntry.put( "chunkScore", c.chunkScore() );
            chunksOut.add( chunkEntry );
        }
        entry.put( "contributingChunks", chunksOut );

        // Back-compat: single snippet from the top chunk, truncated to 320 chars.
        final RetrievedChunk c0 = chunks.get( 0 );
        final String snippet = c0.text().length() > 320
            ? c0.text().substring( 0, 320 ) + "…"
            : c0.text();
        entry.put( "snippet", snippet );
    }

    /** Adds the {@code relatedPages} array, listing only pages the caller may view; no-op when none remain. */
    private static void appendRelatedPages( final Map< String, Object > entry, final List< RelatedPage > related,
                                            final Set< String > viewable ) {
        final List< Map< String, Object > > relatedOut = new ArrayList<>( related.size() );
        for ( final RelatedPage r : related ) {
            if ( !viewable.contains( r.name() ) ) continue;
            final Map< String, Object > rEntry = ResultShaper.orderedMap();
            rEntry.put( "name", r.name() );
            rEntry.put( "reason", r.reason() );
            relatedOut.add( rEntry );
        }
        if ( relatedOut.isEmpty() ) return;
        entry.put( "relatedPages", relatedOut );
    }

    /** Harvests the query for corpus-grounding (async + fail-open). Agent-by-construction surface. */
    private void logQuery( final String query, final RetrievalResult retrieval ) {
        final QueryLogService qlog = engine instanceof WikiEngine we ? we.queryLogService() : null;
        if ( qlog != null ) {
            qlog.log( query, ActorType.AGENT, SourceSurface.TOOLS_SEARCH_WIKI, retrieval.pages().size() );
        }
    }

    private static int clampLimit( final int requested ) {
        if ( requested <= 0 ) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min( requested, MAX_MAX_RESULTS );
    }
}
