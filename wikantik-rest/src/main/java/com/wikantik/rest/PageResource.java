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
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 *   <li>{@code POST /api/pages/PageName/rename} - Rename a page</li>
 * </ul>
 */
public class PageResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageResource.class );

    /** Warning code of a save that was stored but whose post-save processing failed. */
    static final String POST_SAVE_FAILED_CODE = "post_save_failed";

    /** Generic warning text for it: the cause stays in the server log (D5). */
    static final String POST_SAVE_FAILED_MESSAGE =
            "Saved; post-save processing failed and some derived data (search index, references) may lag.";

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        final PageReadHandler reader = new PageReadHandler( this );

        // GET /api/pages/{name}/similar — similar pages by chunk-embedding centroid
        if ( pathParam.endsWith( "/similar" ) ) {
            reader.handleSimilarPages( request, response, pathParam.substring( 0, pathParam.length() - "/similar".length() ) );
            return;
        }

        // GET /api/pages/{name}/preview — link-preview card data; missing and unviewable are the same 404
        if ( pathParam.endsWith( "/preview" ) ) {
            reader.handlePreview( request, response, pathParam.substring( 0, pathParam.length() - "/preview".length() ) );
            return;
        }

        // GET /api/pages/{name}/blocks — addressable blocks for the ^id picker; missing and unviewable are the same 404
        if ( pathParam.endsWith( "/blocks" ) ) {
            new PageBlocksHandler( this ).handleGet( request, response,
                    pathParam.substring( 0, pathParam.length() - "/blocks".length() ) );
            return;
        }

        // GET /api/pages/{name}/embed — rendered transclusion body; a missing page is data, not a 404
        if ( pathParam.endsWith( "/embed" ) ) {
            reader.handleEmbed( request, response, pathParam.substring( 0, pathParam.length() - "/embed".length() ) );
            return;
        }

        reader.handleGet( request, response, pathParam );
    }

    /** Upper bound on {@code limit} for the similar-pages endpoint. */
    static final int MAX_SIMILAR_LIMIT = 20;

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

    /** Delegates to {@link PageSaveHandler#isInvalidPageName}. */
    static boolean isInvalidPageName( final String name ) {
        return PageSaveHandler.isInvalidPageName( name );
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        new PageSaveHandler( this ).handlePut( request, response );
    }

    @Override
    protected void doPatch( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        new PageSaveHandler( this ).handlePatch( request, response );
    }

    static Map< String, Object > validationErrorBody( final FrontmatterValidationException e ) {
        return PageSaveErrors.validationErrorBody( e );
    }

    static Map< String, Object > accessRuleShapeErrorBody( final AccessRuleShapeException e ) {
        return PageSaveErrors.accessRuleShapeErrorBody( e );
    }

    static Map< String, Object > mathValidationErrorBody( final ContentValidationException e ) {
        return PageSaveErrors.mathValidationErrorBody( e );
    }

    static String sanitizeSaveErrorMessage( final Throwable t ) {
        return PageSaveErrors.sanitizeSaveErrorMessage( t );
    }

    static int parseMaxPageBytes( final Engine engine ) {
        return PageSaveHandler.parseMaxPageBytes( engine );
    }

    static boolean isExpectedVersionRequired( final Engine engine ) {
        return PageSaveHandler.isExpectedVersionRequired( engine );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = requirePathParam( request, response );
        if ( pageName == null ) return;
        if ( !checkPagePermission( request, response, pageName, "delete" ) ) return;

        LOG.debug( "DELETE page: {}", pageName );

        try {
            final PageManager pm = getSubsystems().page().pages();
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

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String pathParam = extractPathParam( request );
        if ( pathParam != null && pathParam.endsWith( "/rename" ) ) {
            final String pageName = pathParam.substring( 0, pathParam.length() - "/rename".length() );
            new PageRenameHandler( this ).handle( request, response, pageName );
            return;
        }
        // POST /api/pages/{name}/blocks/draft — the server's block model of the editor's unsaved text
        if ( pathParam != null && pathParam.endsWith( "/blocks/draft" ) ) {
            new PageBlocksHandler( this ).handleDraft( request, response,
                    pathParam.substring( 0, pathParam.length() - "/blocks/draft".length() ) );
            return;
        }
        // POST /api/pages/{name}/blocks — give a block a ^id
        if ( pathParam != null && pathParam.endsWith( "/blocks" ) ) {
            new PageBlocksHandler( this ).handlePost( request, response,
                    pathParam.substring( 0, pathParam.length() - "/blocks".length() ) );
            return;
        }
        sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint" );
    }

}
