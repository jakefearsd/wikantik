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
package org.apache.wiki.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.WikiContext;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Engine;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * REST API servlet for retrieving recent articles as JSON.
 *
 * <p>Endpoint: {@code GET /api/recent-articles}
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code count} - Maximum number of articles to return (default: 10, max: 100)</li>
 *   <li>{@code since} - Number of days to look back (default: 30)</li>
 *   <li>{@code excerpt} - Include excerpts: true/false (default: true)</li>
 *   <li>{@code excerptLength} - Maximum excerpt length (default: 200)</li>
 *   <li>{@code exclude} - Regex pattern for pages to exclude</li>
 *   <li>{@code include} - Regex pattern for pages to include (only matching pages returned)</li>
 * </ul>
 *
 * <p>Response format (JSON):
 * <pre>{@code
 * {
 *   "articles": [
 *     {
 *       "name": "PageName",
 *       "title": "Page Title",
 *       "author": "username",
 *       "lastModified": "2025-12-08T14:30:00Z",
 *       "excerpt": "First paragraph of content...",
 *       "changeNote": "Updated section X",
 *       "version": 5,
 *       "url": "/wiki/PageName"
 *     }
 *   ],
 *   "total": 50,
 *   "returned": 10,
 *   "query": {
 *     "count": 10,
 *     "sinceDays": 30
 *   }
 * }
 * }</pre>
 *
 * @since 3.0.7
 */
public class RecentArticlesServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( RecentArticlesServlet.class );

    private static final int MAX_COUNT = 100;
    private static final int DEFAULT_COUNT = 10;
    private static final int DEFAULT_SINCE_DAYS = 30;
    private static final int DEFAULT_EXCERPT_LENGTH = 200;

    private Engine engine;
    private Gson gson;

    /**
     * {@inheritDoc}
     */
    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config );

        // Configure Gson with ISO 8601 date format
        gson = new GsonBuilder()
            .setDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" )
            .setPrettyPrinting()
            .create();

        LOG.info( "RecentArticlesServlet initialized" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );

        // CORS headers for SPA support
        response.setHeader( "Access-Control-Allow-Origin", "*" );
        response.setHeader( "Access-Control-Allow-Methods", "GET, OPTIONS" );
        response.setHeader( "Access-Control-Allow-Headers", "Content-Type" );

        // Cache control - allow caching for 60 seconds
        response.setHeader( "Cache-Control", "public, max-age=60" );

        try {
            final RecentArticlesQuery query = parseQuery( request );
            final Context context = createContext( request );

            final RecentArticlesManager manager = engine.getManager( RecentArticlesManager.class );
            if ( manager == null ) {
                sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                           "RecentArticlesManager not available" );
                return;
            }

            final List<ArticleSummary> articles = manager.getRecentArticles( context, query );

            // Build response
            final Map<String, Object> result = new HashMap<>();
            result.put( "articles", articles );
            result.put( "returned", articles.size() );
            result.put( "query", buildQueryInfo( query ) );

            final PrintWriter out = response.getWriter();
            out.print( gson.toJson( result ) );
            out.flush();

        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final Exception e ) {
            LOG.error( "Error processing recent articles request", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       "Internal server error: " + e.getMessage() );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doOptions( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        // Handle CORS preflight
        response.setHeader( "Access-Control-Allow-Origin", "*" );
        response.setHeader( "Access-Control-Allow-Methods", "GET, OPTIONS" );
        response.setHeader( "Access-Control-Allow-Headers", "Content-Type" );
        response.setStatus( HttpServletResponse.SC_OK );
    }

    /**
     * Parses query parameters from the request.
     */
    private RecentArticlesQuery parseQuery( final HttpServletRequest request ) {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        // Parse count
        final String countParam = request.getParameter( "count" );
        if ( countParam != null ) {
            try {
                int count = Integer.parseInt( countParam );
                if ( count <= 0 ) {
                    throw new IllegalArgumentException( "count must be positive" );
                }
                if ( count > MAX_COUNT ) {
                    count = MAX_COUNT;
                }
                query.count( count );
            } catch ( final NumberFormatException e ) {
                throw new IllegalArgumentException( "Invalid count parameter: " + countParam );
            }
        }

        // Parse since
        final String sinceParam = request.getParameter( "since" );
        if ( sinceParam != null ) {
            try {
                final int since = Integer.parseInt( sinceParam );
                if ( since <= 0 ) {
                    throw new IllegalArgumentException( "since must be positive" );
                }
                query.sinceDays( since );
            } catch ( final NumberFormatException e ) {
                throw new IllegalArgumentException( "Invalid since parameter: " + sinceParam );
            }
        }

        // Parse excerpt flag
        final String excerptParam = request.getParameter( "excerpt" );
        if ( excerptParam != null ) {
            query.includeExcerpt( Boolean.parseBoolean( excerptParam ) );
        }

        // Parse excerpt length
        final String excerptLengthParam = request.getParameter( "excerptLength" );
        if ( excerptLengthParam != null ) {
            try {
                final int length = Integer.parseInt( excerptLengthParam );
                if ( length <= 0 ) {
                    throw new IllegalArgumentException( "excerptLength must be positive" );
                }
                query.excerptLength( length );
            } catch ( final NumberFormatException e ) {
                throw new IllegalArgumentException( "Invalid excerptLength parameter: " + excerptLengthParam );
            }
        }

        // Parse exclude pattern
        final String excludeParam = request.getParameter( "exclude" );
        if ( excludeParam != null && !excludeParam.isEmpty() ) {
            query.excludePattern( excludeParam );
        }

        // Parse include pattern
        final String includeParam = request.getParameter( "include" );
        if ( includeParam != null && !includeParam.isEmpty() ) {
            query.includePattern( includeParam );
        }

        return query;
    }

    /**
     * Creates a WikiContext for the request.
     */
    private Context createContext( final HttpServletRequest request ) {
        return Wiki.context().create( engine, request, WikiContext.VIEW );
    }

    /**
     * Builds query info for the response.
     */
    private Map<String, Object> buildQueryInfo( final RecentArticlesQuery query ) {
        final Map<String, Object> info = new HashMap<>();
        info.put( "count", query.getCount() );
        info.put( "sinceDays", query.getSinceDays() );
        info.put( "includeExcerpt", query.isIncludeExcerpt() );
        info.put( "excerptLength", query.getExcerptLength() );
        if ( query.getExcludePattern() != null ) {
            info.put( "excludePattern", query.getExcludePattern() );
        }
        if ( query.getIncludePattern() != null ) {
            info.put( "includePattern", query.getIncludePattern() );
        }
        return info;
    }

    /**
     * Sends an error response as JSON.
     */
    private void sendError( final HttpServletResponse response, final int status, final String message )
            throws IOException {
        response.setStatus( status );
        final Map<String, Object> error = new HashMap<>();
        error.put( "error", true );
        error.put( "status", status );
        error.put( "message", message );

        final PrintWriter out = response.getWriter();
        out.print( gson.toJson( error ) );
        out.flush();
    }
}
