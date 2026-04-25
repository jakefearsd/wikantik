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
import com.wikantik.content.PageRenamer;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.content.WikiToMarkdownConverter;
import com.wikantik.render.RenderingManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li>{@code POST /api/pages/PageName/rename} - Rename a page</li>
 * </ul>
 */
public class PageResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        // GET /api/pages/{name}/similar — similar pages by KGE embedding
        if ( pathParam.endsWith( "/similar" ) ) {
            final String pageName = pathParam.substring( 0, pathParam.length() - "/similar".length() );
            if ( !checkPagePermission( request, response, pageName, "view" ) ) return;
            handleGetSimilarPages( request, response, pageName );
            return;
        }

        final String pageName = pathParam;
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
                LOG.info( "Rejecting page request for '{}' with non-numeric version '{}': {}",
                    pageName, versionParam, e.getMessage() );
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

        // D19: surface the content hash so clients can round-trip GET → PUT with
        // expectedContentHash without computing the digest themselves.
        result.put( "contentHash",
                com.wikantik.api.pages.PageSaveHelper.computeContentHash( rawText ) );

        // Include markup syntax — "wiki" for legacy .txt pages, "markdown" for .md pages,
        // or "likely-wiki" if heuristic detects wiki syntax in a .md page
        String markupSyntax = page.getAttribute( Page.MARKUP_SYNTAX );
        if( markupSyntax == null ) {
            markupSyntax = "markdown";
        }
        if( "markdown".equals( markupSyntax ) && WikiToMarkdownConverter.isLikelyWikiSyntax( parsed.body() ) ) {
            markupSyntax = "likely-wiki";
        }
        result.put( "markupSyntax", markupSyntax );

        // Include the current user's effective permissions for this page
        final Map< String, Boolean > permissions = new LinkedHashMap<>();
        permissions.put( "edit", hasPagePermission( request, pageName, "edit" ) );
        permissions.put( "comment", hasPagePermission( request, pageName, "comment" ) );
        permissions.put( "upload", hasPagePermission( request, pageName, "upload" ) );
        permissions.put( "rename", hasPagePermission( request, pageName, "rename" ) );
        permissions.put( "delete", hasPagePermission( request, pageName, "delete" ) );
        result.put( "permissions", permissions );

        sendJson( response, result );
    }

    /**
     * D21: maximum permitted page-name length. Names longer than this are rejected with
     * a 400 before they reach the storage layer (which would otherwise fail with an
     * absolute filesystem path in the message — see D5). 200 is generous for any
     * reasonable wiki name and still well under typical filesystem path limits.
     */
    static final int MAX_PAGE_NAME_LENGTH = 200;

    /**
     * D22: maximum permitted page body in bytes (UTF-8). Defaults to 256 KB; can be raised
     * via the {@code wikantik.api.maxPageBytes} property.
     */
    static final String PROP_MAX_PAGE_BYTES = "wikantik.api.maxPageBytes";
    static final int DEFAULT_MAX_PAGE_BYTES = 256 * 1024;

    /**
     * D20: opt-in strict-mode property. When {@code true}, PUT requests must supply
     * {@code expectedVersion}; otherwise the request is rejected with a 400 to avoid
     * silently overwriting a concurrent update. Defaults to {@code false} for backward
     * compatibility.
     */
    static final String PROP_REQUIRE_EXPECTED_VERSION = "wikantik.api.write.requireExpectedVersion";

    /**
     * D21: characters never permitted in a wiki page name. Colons, slashes, and
     * backslashes can resolve onto unintended filesystem paths or cluster ids; control
     * chars indicate header injection or copy-paste artefacts.
     */
    static boolean isInvalidPageName( final String name ) {
        if ( name == null || name.isEmpty() || name.length() > MAX_PAGE_NAME_LENGTH ) {
            return true;
        }
        for ( int i = 0; i < name.length(); i++ ) {
            final char c = name.charAt( i );
            if ( c == ':' || c == '\\' || c == '/' || c < 0x20 || c == 0x7F ) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = requirePathParam( request, response );
        if ( pageName == null ) return;
        // D5/D21: reject pathological page names BEFORE any provider call so the
        // response is a sanitized 400, not a 500 with a leaked filesystem path.
        if ( isInvalidPageName( pageName ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Page name is invalid: must be 1-" + MAX_PAGE_NAME_LENGTH
                            + " characters and must not contain ':', '/', '\\', or control chars." );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "edit" ) ) return;

        LOG.debug( "PUT page: {}", pageName );

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String content = body.has( "content" ) ? body.get( "content" ).getAsString() : "";

        // D22: enforce the configurable content-size limit before persistence.
        final int maxBytes = parseMaxPageBytes( getEngine() );
        final int actualBytes = content.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
        if ( actualBytes > maxBytes ) {
            sendError( response, 413,
                    "Page body is " + actualBytes + " bytes; limit is " + maxBytes
                            + " bytes (configurable via " + PROP_MAX_PAGE_BYTES + ")." );
            return;
        }

        final String changeNote = body.has( "changeNote" ) ? body.get( "changeNote" ).getAsString() : null;
        final String author = body.has( "author" ) ? body.get( "author" ).getAsString() : null;
        final int expectedVersion = body.has( "expectedVersion" ) ? body.get( "expectedVersion" ).getAsInt() : -1;
        final String expectedContentHash = body.has( "expectedContentHash" ) ? body.get( "expectedContentHash" ).getAsString() : null;
        final String markupSyntax = body.has( "markupSyntax" ) ? body.get( "markupSyntax" ).getAsString() : null;

        // D20: optional strict mode — refuse a PUT that doesn't carry expectedVersion.
        if ( expectedVersion < 0 && isExpectedVersionRequired( getEngine() ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "expectedVersion is required when " + PROP_REQUIRE_EXPECTED_VERSION
                            + " is true. Pass the version returned by the prior GET to confirm "
                            + "you are not silently overwriting a concurrent update." );
            return;
        }

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
        if ( markupSyntax != null ) {
            optionsBuilder.markupSyntax( markupSyntax );
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
            // D5: do not leak the underlying provider message verbatim — a deep IOException
            // may carry an absolute filesystem path. Log the real cause server-side; return
            // a sanitized message to the client.
            LOG.error( "Error saving page {}: {}", pageName, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    sanitizeSaveErrorMessage( e ) );
        } catch ( final RuntimeException e ) {
            LOG.error( "Unexpected error saving page {}: {}", pageName, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error saving page: internal server error." );
        }
    }

    /**
     * D5: returns a client-safe error message for a failed page save. Stringifies the
     * cause for logging callers but never reveals filesystem paths, JDBC URLs, or
     * stack frames in the response body.
     */
    static String sanitizeSaveErrorMessage( final Throwable t ) {
        // Walk the cause chain looking for a recognisable validation reason; if we
        // find one, surface a short message. Otherwise fall back to a generic string
        // that hides absolute paths and class names.
        Throwable cur = t;
        while ( cur != null ) {
            if ( cur instanceof java.io.IOException ) {
                return "Error saving page: storage I/O failure (see server log).";
            }
            cur = cur.getCause();
        }
        // Fallback: trust only the top-level message and only if it doesn't look like
        // a path or class name.
        final String msg = t.getMessage();
        if ( msg == null || msg.isEmpty() ) {
            return "Error saving page: internal error (see server log).";
        }
        if ( msg.contains( "/" ) || msg.contains( "\\" ) || msg.contains( "." ) && msg.contains( "Exception" ) ) {
            return "Error saving page: internal error (see server log).";
        }
        return "Error saving page: " + msg;
    }

    /** D22: read the configured max-bytes property, falling back to the default. */
    static int parseMaxPageBytes( final Engine engine ) {
        if ( engine == null ) {
            return DEFAULT_MAX_PAGE_BYTES;
        }
        final String raw = engine.getWikiProperties().getProperty( PROP_MAX_PAGE_BYTES );
        if ( raw == null || raw.isBlank() ) {
            return DEFAULT_MAX_PAGE_BYTES;
        }
        try {
            final int v = Integer.parseInt( raw.trim() );
            return v > 0 ? v : DEFAULT_MAX_PAGE_BYTES;
        } catch ( final NumberFormatException nfe ) {
            return DEFAULT_MAX_PAGE_BYTES;
        }
    }

    /** D20: read the strict-mode property; defaults to false. */
    static boolean isExpectedVersionRequired( final Engine engine ) {
        if ( engine == null ) {
            return false;
        }
        return Boolean.parseBoolean(
                engine.getWikiProperties().getProperty( PROP_REQUIRE_EXPECTED_VERSION, "false" ).trim() );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = requirePathParam( request, response );
        if ( pageName == null ) return;
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
    @Override
    protected void doPatch( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = requirePathParam( request, response );
        if ( pageName == null ) return;
        if ( !checkPagePermission( request, response, pageName, "edit" ) ) return;

        LOG.debug( "PATCH page: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = requirePage( request, response, pageName );
        if ( page == null ) return;

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

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
            // D7: the PageSaveHelper builds a headless context so the author would
            // otherwise default to "Guest". Resolve it from the authenticated session
            // the same way doPut does.
            String effectiveAuthor = null;
            final Session wikiSession = Wiki.session().find( engine, request );
            if ( wikiSession.isAuthenticated() ) {
                effectiveAuthor = wikiSession.getUserPrincipal().getName();
            }
            final SaveOptions options = SaveOptions.builder()
                    .changeNote( "Metadata " + action + " via REST PATCH" )
                    .author( effectiveAuthor )
                    .build();
            final Page saved = helper.saveText( pageName, newText, options );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", pageName );
            result.put( "version", Math.max( saved.getVersion(), 1 ) );
            result.put( "author", saved.getAuthor() ); // D7
            result.put( "metadata", newMetadata );

            sendJson( response, result );

        } catch ( final WikiException e ) {
            LOG.error( "Error patching page {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error patching page: " + e.getMessage() );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String pathParam = extractPathParam( request );
        if ( pathParam != null && pathParam.endsWith( "/rename" ) ) {
            final String pageName = pathParam.substring( 0, pathParam.length() - "/rename".length() );
            handleRename( request, response, pageName );
            return;
        }
        sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint" );
    }

    /**
     * Handles a page rename request.
     * <p>
     * Expects a JSON body with:
     * <ul>
     *   <li>{@code newName} - the new page name (required, must not be blank)</li>
     *   <li>{@code changeReferrers} - if {@code true}, update all referring pages (default {@code true})</li>
     * </ul>
     *
     * @param request  the HTTP request
     * @param response the HTTP response
     * @param pageName the current name of the page to rename
     * @throws IOException if writing the response fails
     */
    private void handleRename( final HttpServletRequest request, final HttpServletResponse response,
                                final String pageName ) throws IOException {
        if ( !checkPagePermission( request, response, pageName, "rename" ) ) return;

        LOG.debug( "POST rename page: {} ", pageName );

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        // Validate newName
        if ( !body.has( "newName" ) || body.get( "newName" ).getAsString().isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "newName is required and must not be blank" );
            return;
        }
        final String newName = body.get( "newName" ).getAsString().trim();

        final boolean changeReferrers = !body.has( "changeReferrers" ) || body.get( "changeReferrers" ).getAsBoolean();

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        // Source page must exist
        final Page page = requirePage( request, response, pageName );
        if ( page == null ) return;

        // Target page must not already exist
        if ( pm.getPage( newName ) != null ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, "Page already exists: " + newName );
            return;
        }

        try {
            final PageRenamer renamer = engine.getManager( PageRenamer.class );
            final Context context = Wiki.context().create( engine, request, page );
            final String finalName = renamer.renamePage( context, pageName, newName, changeReferrers );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "oldName", pageName );
            result.put( "newName", finalName );

            sendJson( response, result );

        } catch ( final WikiException e ) {
            LOG.error( "Error renaming page {} to {}: {}", pageName, newName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error renaming page: " + e.getMessage() );
        }
    }

    /**
     * Returns similar pages based on mention-centroid similarity (same embedding
     * stack as hybrid search). Degrades gracefully to an empty list when the
     * embedding index isn't populated or the page has no chunk mentions.
     */
    private void handleGetSimilarPages( final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String pageName ) throws IOException {
        final NodeMentionSimilarity similarity = getEngine().getManager( NodeMentionSimilarity.class );
        if ( similarity == null || !similarity.isReady() ) {
            sendJson( response, Map.of( "similar", List.of() ) );
            return;
        }
        final int limit = parseIntParam( request, "limit", 5 );
        final var similar = similarity.similarTo( pageName, limit );
        sendJson( response, Map.of( "similar", similar.stream().map( s -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "name", s.name() );
            m.put( "similarity", s.score() );
            return m;
        } ).toList() ) );
    }

}
