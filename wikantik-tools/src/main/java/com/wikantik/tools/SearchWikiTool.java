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

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes the {@code search_wiki} tool: retrieves context via
 * {@link ContextRetrievalService} and shapes results into an LLM-friendly JSON
 * payload with citation URLs and snippets.
 *
 * <p>Delegates hybrid rerank, dense retrieval, and BM25 fallback to the
 * service, keeping this class focused on response shaping.</p>
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
        final ContextRetrievalService ctxService = engine.getManager( ContextRetrievalService.class );
        if ( ctxService == null ) {
            final Map< String, Object > error = ResultShaper.orderedMap();
            error.put( "query", query );
            error.put( "results", List.of() );
            error.put( "total", 0 );
            error.put( "error", "ContextRetrievalService not configured" );
            return error;
        }
        final RetrievalResult retrieval;
        try {
            retrieval = ctxService.retrieve( new ContextQuery( query, Math.min( clamped, 20 ), 3, null ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Tool-server search failed for query '{}': {}", query, e.getMessage() );
            final Map< String, Object > error = ResultShaper.orderedMap();
            error.put( "query", query );
            error.put( "results", List.of() );
            error.put( "total", 0 );
            error.put( "error", "search failed: " + e.getMessage() );
            return error;
        }

        final String publicBaseUrl = config.publicBaseUrl();
        final List< Map< String, Object > > shaped = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            if ( shaped.size() >= clamped ) break;
            final Map< String, Object > entry = ResultShaper.orderedMap();
            entry.put( "name", p.name() );
            entry.put( "url", ResultShaper.citationUrl( p.name(), request, publicBaseUrl ) );
            entry.put( "score", p.score() );
            if ( !p.summary().isEmpty() ) entry.put( "summary", p.summary() );
            if ( !p.tags().isEmpty() ) entry.put( "tags", p.tags() );
            if ( p.cluster() != null ) entry.put( "cluster", p.cluster() );
            if ( !p.contributingChunks().isEmpty() ) {
                // Rich array for new consumers — matches MCP retrieve_context shape.
                final List< Map< String, Object > > chunksOut = new ArrayList<>( p.contributingChunks().size() );
                for ( final RetrievedChunk c : p.contributingChunks() ) {
                    final Map< String, Object > chunkEntry = ResultShaper.orderedMap();
                    chunkEntry.put( "headingPath", c.headingPath() );
                    chunkEntry.put( "text", c.text() );
                    chunkEntry.put( "chunkScore", c.chunkScore() );
                    chunksOut.add( chunkEntry );
                }
                entry.put( "contributingChunks", chunksOut );

                // Back-compat: single snippet from the top chunk, truncated to 320 chars.
                final RetrievedChunk c0 = p.contributingChunks().get( 0 );
                final String snippet = c0.text().length() > 320
                    ? c0.text().substring( 0, 320 ) + "…"
                    : c0.text();
                entry.put( "snippet", snippet );
            }

            if ( !p.relatedPages().isEmpty() ) {
                final List< Map< String, Object > > relatedOut = new ArrayList<>( p.relatedPages().size() );
                for ( final RelatedPage r : p.relatedPages() ) {
                    final Map< String, Object > rEntry = ResultShaper.orderedMap();
                    rEntry.put( "name", r.name() );
                    rEntry.put( "reason", r.reason() );
                    relatedOut.add( rEntry );
                }
                entry.put( "relatedPages", relatedOut );
            }

            if ( p.lastModified() != null ) {
                entry.put( "lastModified", p.lastModified().toInstant().toString() );
            }
            if ( p.author() != null ) entry.put( "author", p.author() );
            shaped.add( entry );
        }
        final Map< String, Object > out = ResultShaper.orderedMap();
        out.put( "query", query );
        out.put( "results", shaped );
        out.put( "total", shaped.size() );
        return out;
    }

    private static int clampLimit( final int requested ) {
        if ( requested <= 0 ) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min( requested, MAX_MAX_RESULTS );
    }
}
