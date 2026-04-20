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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.wikantik.api.core.Engine;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Dispatcher servlet for the OpenAPI tool server. Maps URL paths under {@code /tools}
 * to the individual tool implementations and serves the OpenAPI 3.1 document.
 *
 * <p>The servlet runs behind {@link ToolsAccessFilter} so authentication and rate
 * limiting are already enforced by the time {@code doGet}/{@code doPost} is called.</p>
 */
public class ToolsOpenApiServlet extends HttpServlet {

    private static final Logger LOG = LogManager.getLogger( ToolsOpenApiServlet.class );
    private static final Gson GSON = new Gson();

    private static final String PLACEHOLDER_SPEC = """
        {
          "openapi": "3.1.0",
          "info": {
            "title": "Wikantik Tool Server",
            "version": "0.1.0-skeleton",
            "description": "Tool server is not fully initialized — engine unavailable."
          },
          "paths": {}
        }
        """;

    private final Engine engine;
    private final ToolsConfig config;
    private final ToolsMetrics metrics;
    private final SearchWikiTool searchTool;
    private final GetPageTool getPageTool;

    public ToolsOpenApiServlet() {
        this( null, null, null );
    }

    public ToolsOpenApiServlet( final Engine engine, final ToolsConfig config,
                                final ToolsMetrics metrics ) {
        this.engine = engine;
        this.config = config;
        this.metrics = metrics != null ? metrics : new ToolsMetrics();
        this.searchTool = engine != null && config != null ? new SearchWikiTool( engine, config ) : null;
        this.getPageTool = engine != null && config != null ? new GetPageTool( engine, config ) : null;
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String path = req.getPathInfo();
        if ( path == null || "/".equals( path ) || "/openapi.json".equals( path ) ) {
            serveOpenApi( req, resp );
            return;
        }
        if ( path.startsWith( "/page/" ) ) {
            handleGetPage( path, req, resp );
            return;
        }
        writeNotImplemented( resp );
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String path = req.getPathInfo();
        if ( path == null ) {
            writeNotImplemented( resp );
            return;
        }
        if ( "/search_wiki".equals( path ) ) {
            handleSearch( req, resp );
            return;
        }
        writeNotImplemented( resp );
    }

    private void serveOpenApi( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "application/json" );
        if ( config == null ) {
            resp.getWriter().write( PLACEHOLDER_SPEC );
            return;
        }
        resp.getWriter().write( OpenApiDocument.render( req, config ) );
        metrics.recordOpenapiServed();
    }

    private void handleSearch( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        if ( searchTool == null ) {
            writeJsonError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Tool server not initialized (engine unavailable)" );
            return;
        }
        final JsonObject body = readJsonBody( req );
        final String query = body != null && body.has( "query" ) && !body.get( "query" ).isJsonNull()
                ? body.get( "query" ).getAsString() : null;
        if ( query == null || query.isBlank() ) {
            writeJsonError( resp, HttpServletResponse.SC_BAD_REQUEST, "Field 'query' is required" );
            return;
        }
        final int maxResults = body != null && body.has( "maxResults" ) && !body.get( "maxResults" ).isJsonNull()
                ? body.get( "maxResults" ).getAsInt() : 0;

        try {
            final Map< String, Object > result = searchTool.execute( query, maxResults, req );
            final Object results = result.get( "results" );
            final int count = results instanceof java.util.List< ? > list ? list.size() : 0;
            metrics.recordSearchSuccess( count );
            writeJson( resp, HttpServletResponse.SC_OK, result );
        } catch ( final Exception e ) {
            metrics.recordSearchError();
            LOG.warn( "search_wiki failed for '{}': {}", query, e.getMessage() );
            writeJsonError( resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "search failed: " + e.getMessage() );
        }
    }

    private void handleGetPage( final String path, final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        if ( getPageTool == null ) {
            writeJsonError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Tool server not initialized (engine unavailable)" );
            return;
        }
        final String rawName = path.substring( "/page/".length() );
        if ( rawName.isBlank() ) {
            writeJsonError( resp, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        final String pageName = URLDecoder.decode( rawName, StandardCharsets.UTF_8 );
        final int maxChars = parseIntParam( req.getParameter( "maxChars" ) );

        try {
            final Map< String, Object > result = getPageTool.execute( pageName, maxChars, req );
            if ( result == null ) {
                metrics.recordGetPageNotFound();
                writeJsonError( resp, HttpServletResponse.SC_NOT_FOUND, "Page not found: " + pageName );
                return;
            }
            final boolean truncated = Boolean.TRUE.equals( result.get( "truncated" ) );
            metrics.recordGetPageSuccess( truncated );
            writeJson( resp, HttpServletResponse.SC_OK, result );
        } catch ( final Exception e ) {
            metrics.recordGetPageError();
            LOG.warn( "get_page failed for '{}': {}", pageName, e.getMessage() );
            writeJsonError( resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "get_page failed: " + e.getMessage() );
        }
    }

    private static int parseIntParam( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return 0;
        }
        try {
            return Integer.parseInt( raw.strip() );
        } catch ( final NumberFormatException e ) {
            return 0;
        }
    }

    private static JsonObject readJsonBody( final HttpServletRequest req ) {
        try ( final BufferedReader reader = req.getReader() ) {
            final StringBuilder buf = new StringBuilder();
            final char[] chunk = new char[ 1024 ];
            int n;
            while ( ( n = reader.read( chunk ) ) != -1 ) {
                buf.append( chunk, 0, n );
            }
            if ( buf.length() == 0 ) {
                return null;
            }
            final var parsed = JsonParser.parseString( buf.toString() );
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch ( final IOException | JsonSyntaxException e ) {
            LOG.debug( "Could not parse request body as JSON: {}", e.getMessage() );
            return null;
        }
    }

    private static void writeJson( final HttpServletResponse resp, final int status, final Object body )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json" );
        resp.getWriter().write( GSON.toJson( body ) );
    }

    private static void writeJsonError( final HttpServletResponse resp, final int status, final String message )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json" );
        resp.getWriter().write( GSON.toJson( Map.of( "error", message ) ) );
    }

    private static void writeNotImplemented( final HttpServletResponse resp ) throws IOException {
        resp.setStatus( HttpServletResponse.SC_NOT_IMPLEMENTED );
        resp.setContentType( "application/json" );
        resp.getWriter().write( "{\"error\":\"Tool endpoint not yet implemented\"}" );
    }
}
