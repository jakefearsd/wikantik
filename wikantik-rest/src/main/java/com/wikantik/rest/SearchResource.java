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
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedPage;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
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
 * <p>
 * Retrieval logic (BM25 + hybrid rerank + graph rerank) is delegated to
 * {@link ContextRetrievalService}. The {@code contexts} field in the JSON
 * response carries chunk text with heading-path context rather than Lucene
 * highlight fragments — intentional change; chunks are higher-signal.
 */
public class SearchResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SearchResource.class );

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 1_000;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String query = request.getParameter( "q" );
        if ( query == null || query.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Query parameter 'q' is required" );
            return;
        }

        final int limit = parseIntParam( request, "limit", DEFAULT_LIMIT );
        // A negative limit blows up Stream.limit with IllegalArgumentException; a
        // ridiculously large limit forces a full-corpus serialization. Both are
        // user-input errors, not server faults — reject them with a clear 400
        // rather than leaking an HTML 500 stack trace.
        if ( limit < 0 || limit > MAX_LIMIT ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "Query parameter 'limit' must be in [0, " + MAX_LIMIT + "]" );
            return;
        }

        // D26: Lucene-syntax characters (* ? + - ! && || etc.) leaked through to the
        // query parser, so a casual search for "wei*rd" expanded to a wildcard search
        // and "AnD" was treated as a boolean operator. Escape these characters by
        // default; power users can opt in to raw Lucene syntax with ?raw=true.
        final boolean rawSyntax = "true".equalsIgnoreCase( request.getParameter( "raw" ) );
        final String effectiveQuery = rawSyntax ? query : escapeLuceneSpecialChars( query );

        LOG.debug( "GET search: q={}, limit={}", effectiveQuery, limit );

        final Engine engine = getEngine();

        // Delegate retrieval to ContextRetrievalService. The service owns
        // BM25 → hybrid rerank → graph rerank → page shaping.
        final ContextRetrievalService ctxService = engine.getManager( ContextRetrievalService.class );
        if ( ctxService == null ) {
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "ContextRetrievalService not configured" );
            return;
        }
        final RetrievalResult retrieval;
        try {
            retrieval = ctxService.retrieve( new ContextQuery( effectiveQuery, Math.min( limit > 0 ? limit : DEFAULT_LIMIT, ContextQuery.MAX_PAGES_CAP ), 3, null ) );
        } catch ( final RuntimeException e ) {
            // Malformed user queries (e.g. unbalanced brackets, Lucene-reserved
            // operator misuse) surface here as ProviderException wrapping a
            // Lucene ParseException. These are user-input errors — return 400
            // rather than 500 so the client treats them as correctable input.
            if ( isLuceneParseError( e ) ) {
                LOG.debug( "Lucene parse error for query '{}': {}", query, e.getMessage() );
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid search query: " + e.getMessage() );
                return;
            }
            LOG.error( "Retrieval failed for '{}': {}", query, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "retrieval failed: " + e.getMessage() );
            return;
        }

        final List< Map< String, Object > > resultList = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "name", p.name() );
            entry.put( "score", p.score() );
            if ( !p.summary().isEmpty() ) entry.put( "summary", p.summary() );
            if ( !p.tags().isEmpty() ) entry.put( "tags", p.tags() );
            if ( p.cluster() != null ) entry.put( "cluster", p.cluster() );
            if ( p.author() != null ) entry.put( "author", p.author() );
            if ( p.lastModified() != null ) entry.put( "lastModified", p.lastModified() );
            // contexts: previously Lucene highlight fragments; now chunk text.
            // Intentional change — chunks carry heading-path context and are
            // higher-signal than Lucene snippets.
            if ( !p.contributingChunks().isEmpty() ) {
                entry.put( "contexts", p.contributingChunks().stream()
                    .map( com.wikantik.api.knowledge.RetrievedChunk::text ).toList() );
            }
            if ( resultList.size() < limit ) {
                resultList.add( entry );
            }
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "query", query );
        result.put( "results", resultList );
        result.put( "total", resultList.size() );

        sendJson( response, result );
    }

    /**
     * D26: returns {@code query} with all Lucene-syntax control characters
     * backslash-escaped, plus the boolean keywords AND/OR/NOT lower-cased so they
     * do not act as operators. The set of characters comes from Lucene's
     * QueryParserBase.escape() — the canonical list is + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /.
     */
    static String escapeLuceneSpecialChars( final String query ) {
        if ( query == null || query.isEmpty() ) {
            return query;
        }
        final StringBuilder sb = new StringBuilder( query.length() + 8 );
        for ( int i = 0; i < query.length(); i++ ) {
            final char c = query.charAt( i );
            switch ( c ) {
                case '\\': case '+': case '-': case '!': case '(': case ')':
                case '{': case '}': case '[': case ']': case '^': case '"':
                case '~': case '*': case '?': case ':': case '/':
                    sb.append( '\\' ).append( c );
                    break;
                case '&': case '|':
                    // Escape & and | only if doubled (the actual Lucene operators).
                    // Escape both characters of the pair so the escaping is symmetric.
                    if ( i + 1 < query.length() && query.charAt( i + 1 ) == c ) {
                        sb.append( '\\' ).append( c );
                        sb.append( '\\' ).append( c );
                        i++; // consume the second char of the pair
                    } else {
                        sb.append( c );
                    }
                    break;
                default:
                    sb.append( c );
            }
        }
        // Neutralize the boolean keywords by lower-casing them; Lucene matches them
        // case-sensitively so "AnD" is operator but "and" is just a token.
        return sb.toString()
                .replaceAll( "\\bAND\\b", "and" )
                .replaceAll( "\\bOR\\b", "or" )
                .replaceAll( "\\bNOT\\b", "not" );
    }

    /**
     * Walks the cause chain looking for a Lucene {@code ParseException} — the
     * marker that a user's search query is syntactically invalid (not a server
     * fault). Returns true for any such wrapped exception so the servlet can
     * translate it to a 400 response.
     */
    private static boolean isLuceneParseError( final Throwable t ) {
        Throwable cur = t;
        while ( cur != null ) {
            if ( cur instanceof org.apache.lucene.queryparser.classic.ParseException ) return true;
            if ( cur == cur.getCause() ) return false;
            cur = cur.getCause();
        }
        return false;
    }
}
