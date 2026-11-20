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
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.content.PageRenamer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /api/pages/{name}/rename}: renames a page, optionally rewriting the pages that link to it. Lives outside
 * {@link PageResource} to keep that servlet's complexity in check.
 */
final class PageRenameHandler {

    private static final Logger LOG = LogManager.getLogger( PageRenameHandler.class );

    private final RestServletBase servlet;

    PageRenameHandler( final RestServletBase servlet ) {
        this.servlet = servlet;
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
    void handle( final HttpServletRequest request, final HttpServletResponse response,
                                final String pageName ) throws IOException {
        if ( !servlet.checkPagePermission( request, response, pageName, "rename" ) ) return;

        LOG.debug( "POST rename page: {} ", pageName );

        final JsonObject body = servlet.parseJsonBody( request, response );
        if ( body == null ) return;

        // Validate newName
        final String rawNewName = servlet.getJsonString( body, "newName" );
        if ( rawNewName == null || rawNewName.isBlank() ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "newName is required and must not be blank" );
            return;
        }
        final String newName = rawNewName.trim();

        final boolean changeReferrers = servlet.getJsonBoolean( body, "changeReferrers", true );

        final Engine engine = servlet.getEngine();
        final PageManager pm = servlet.getSubsystems().page().pages();

        // Source page must exist
        final Page page = servlet.requirePage( request, response, pageName );
        if ( page == null ) return;

        // Target page must not already exist
        if ( pm.getPage( newName ) != null ) {
            servlet.sendError( response, HttpServletResponse.SC_CONFLICT, "Page already exists: " + newName );
            return;
        }

        try {
            final PageRenamer renamer = servlet.getSubsystems().page().pageRenamer();
            final Context context = Wiki.context().create( engine, request, page );
            final String finalName = renamer.renamePage( context, pageName, newName, changeReferrers );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "oldName", pageName );
            result.put( "newName", finalName );

            servlet.sendJson( response, result );

        } catch ( final WikiException e ) {
            LOG.error( "Error renaming page {} to {}: {}", pageName, newName, e.getMessage() );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error renaming page: " + e.getMessage() );
        }
    }
}
