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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST servlet for individual page operations.
 * <p>
 * Mapped to {@code /api/pages/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/pages/PageName} - Read a page's content and metadata</li>
 *   <li>{@code GET /api/pages/PageName?render=true} - Include rendered HTML in the response</li>
 *   <li>{@code GET /api/pages/PageName?version=N} - Retrieve a specific page version</li>
 *   <li>{@code PUT /api/pages/PageName} - Create or update a page</li>
 *   <li>{@code PATCH /api/pages/PageName} - Update metadata only (merge or replace)</li>
 *   <li>{@code DELETE /api/pages/PageName} - Delete a page</li>
 * </ul>
 */
public class PageResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "view" ) ) return;

        LOG.debug( "GET page: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        // Version-specific retrieval
        final String versionParam = request.getParameter( "version" );
        final Page page;
        final String rawText;

        if ( versionParam != null && !versionParam.isEmpty() ) {
            final int version;
            try {
                version = Integer.parseInt( versionParam );
            } catch ( final NumberFormatException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid version number: " + versionParam );
                return;
            }
            page = pm.getPage( pageName, version );
            if ( page == null ) {
                sendNotFound( response, "Page not found: " + pageName + " version " + version );
                return;
            }
            rawText = pm.getPureText( pageName, version );
            if ( rawText == null ) {
                sendNotFound( response, "Page not found: " + pageName + " version " + version );
                return;
            }
        } else {
            page = pm.getPage( pageName );
            if ( page == null ) {
                sendNotFound( response, "Page not found: " + pageName );
                return;
            }
            rawText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        }

        final ParsedPage parsed = FrontmatterParser.parse( rawText );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", page.getName() );
        result.put( "content", parsed.body() );

        // Rendered HTML option
        if ( "true".equalsIgnoreCase( request.getParameter( "render" ) ) ) {
            try {
                final RenderingManager renderingManager = engine.getManager( RenderingManager.class );
                final Context context = Wiki.context().create( engine, request, page );
                // TODO: Remove with JSP UI — REST API serves the React SPA; route plugin links through /app/
                context.setVariable( Context.VAR_REACT_URL_BASE, engine.getBaseURL() + "/app" );
                final String html = renderingManager.textToHTML( context, rawText );
                result.put( "contentHtml", html );
            } catch ( final Exception e ) {
                LOG.warn( "Failed to render page {}: {}", pageName, e.getMessage() );
                result.put( "contentHtml", null );
            }
        }

        result.put( "metadata", parsed.metadata() );
        result.put( "version", Math.max( page.getVersion(), 1 ) );
        result.put( "author", page.getAuthor() );
        result.put( "lastModified", page.getLastModified() );
        result.put( "exists", true );

        sendJson( response, result );
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "edit" ) ) return;

        LOG.debug( "PUT page: {}", pageName );

        // Parse JSON body
        final JsonObject body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        final String content = body.has( "content" ) ? body.get( "content" ).getAsString() : "";
        final String changeNote = body.has( "changeNote" ) ? body.get( "changeNote" ).getAsString() : null;
        final String author = body.has( "author" ) ? body.get( "author" ).getAsString() : null;
        final int expectedVersion = body.has( "expectedVersion" ) ? body.get( "expectedVersion" ).getAsInt() : -1;
        final String expectedContentHash = body.has( "expectedContentHash" ) ? body.get( "expectedContentHash" ).getAsString() : null;

        // Extract metadata if present
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > metadata = body.has( "metadata" )
                ? GSON.fromJson( body.get( "metadata" ), Map.class )
                : null;

        final SaveOptions.Builder optionsBuilder = SaveOptions.builder()
                .changeNote( changeNote )
                .expectedVersion( expectedVersion )
                .expectedContentHash( expectedContentHash );

        if ( metadata != null ) {
            optionsBuilder.metadata( metadata );
        }

        try {
            final Engine engine = getEngine();

            // Resolve the author: use the explicitly-supplied value from the request body, or
            // fall back to the authenticated session user.  PageSaveHelper creates a headless
            // context (no HttpServletRequest), so without this the author would always resolve
            // to WikiPrincipal.GUEST regardless of who is logged in.
            String effectiveAuthor = author;
            if ( effectiveAuthor == null ) {
                final Session wikiSession = Wiki.session().find( engine, request );
                if ( wikiSession.isAuthenticated() ) {
                    effectiveAuthor = wikiSession.getUserPrincipal().getName();
                }
            }
            optionsBuilder.author( effectiveAuthor );

            final PageSaveHelper helper = new PageSaveHelper( engine );
            final Page saved = helper.saveText( pageName, content, optionsBuilder.build() );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", pageName );
            result.put( "version", Math.max( saved.getVersion(), 1 ) );

            sendJson( response, result );

        } catch ( final VersionConflictException e ) {
            LOG.debug( "Version conflict saving page {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final WikiException e ) {
            LOG.error( "Error saving page {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error saving page: " + e.getMessage() );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "delete" ) ) return;

        LOG.debug( "DELETE page: {}", pageName );

        try {
            final Engine engine = getEngine();
            final PageManager pm = engine.getManager( PageManager.class );
            pm.deletePage( pageName );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", pageName );

            sendJson( response, result );

        } catch ( final Exception e ) {
            LOG.error( "Error deleting page {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error deleting page: " + e.getMessage() );
        }
    }

    /**
     * Routes PATCH requests to {@link #doPatch(HttpServletRequest, HttpServletResponse)}.
     * HttpServlet does not provide a doPatch method by default, so we intercept it here.
     */
    @Override
    protected void service( final HttpServletRequest req, final HttpServletResponse resp )
            throws ServletException, IOException {
        if ( "PATCH".equalsIgnoreCase( req.getMethod() ) ) {
            doPatch( req, resp );
        } else {
            super.service( req, resp );
        }
    }

    /**
     * Handles PATCH requests for metadata-only updates.
     * <p>
     * Accepts a JSON body with:
     * <ul>
     *   <li>{@code metadata} - a map of frontmatter fields to set</li>
     *   <li>{@code action} - "merge" (default) to merge with existing metadata, or "replace" to overwrite</li>
     * </ul>
     */
    @SuppressWarnings( "unchecked" )
    protected void doPatch( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "edit" ) ) return;

        LOG.debug( "PATCH page: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = pm.getPage( pageName );

        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        // Parse JSON body
        final JsonObject body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        if ( !body.has( "metadata" ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "metadata field is required" );
            return;
        }

        final Map< String, Object > callerMetadata = GSON.fromJson( body.get( "metadata" ), Map.class );
        final String action = body.has( "action" ) ? body.get( "action" ).getAsString() : "merge";

        // Read current page text and parse frontmatter
        final String currentText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( currentText );

        // Build new metadata
        final Map< String, Object > newMetadata;
        if ( "replace".equalsIgnoreCase( action ) ) {
            newMetadata = new LinkedHashMap<>( callerMetadata );
        } else {
            // Default: merge (caller wins)
            newMetadata = new LinkedHashMap<>( parsed.metadata() );
            newMetadata.putAll( callerMetadata );
        }

        // Reconstruct page text with updated frontmatter
        final String newText = FrontmatterWriter.write( newMetadata, parsed.body() );

        try {
            final PageSaveHelper helper = new PageSaveHelper( engine );
            final SaveOptions options = SaveOptions.builder()
                    .changeNote( "Metadata " + action + " via REST PATCH" )
                    .build();
            final Page saved = helper.saveText( pageName, newText, options );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", pageName );
            result.put( "version", Math.max( saved.getVersion(), 1 ) );
            result.put( "metadata", newMetadata );

            sendJson( response, result );

        } catch ( final WikiException e ) {
            LOG.error( "Error patching page {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error patching page: " + e.getMessage() );
        }
    }

}
