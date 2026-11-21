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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Page-mutation routes of {@link AdminContentResource}: bulk delete and version purge. */
final class ContentMutationAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( ContentMutationAdminHandlers.class );

    private final AdminContentResource resource;

    ContentMutationAdminHandlers( final AdminContentResource resource ) {
        this.resource = resource;
    }

    void handleBulkDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = resource.parseJsonBody( request, response );
        if ( body == null ) return;

        final JsonArray pagesArray = body.has( "pages" ) && body.get( "pages" ).isJsonArray()
            ? body.getAsJsonArray( "pages" ) : null;
        if ( pagesArray == null || pagesArray.isEmpty() ) {
            resource.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "pages array is required" );
            return;
        }

        final PageManager pm = resource.getSubsystems().page().pages();
        final List< String > deleted = new ArrayList<>();
        final List< Map< String, String > > failed = new ArrayList<>();

        final String actor = resolveActor( request );
        LOG.info( "Bulk delete started by {}: {} page(s) requested",
            actor, pagesArray.size() );

        for ( int i = 0; i < pagesArray.size(); i++ ) {
            final String pageName = pagesArray.get( i ).getAsString();
            try {
                pm.deletePage( pageName );
                deleted.add( pageName );
                LOG.info( "Bulk delete: deleted page {}", pageName );
            } catch ( final ProviderException e ) {
                failed.add( Map.of( "page", pageName, "error", "deletion failed" ) );
                LOG.warn( "Bulk delete: failed to delete {}: {}", pageName, e.getMessage() );
            }
        }

        LOG.info( "Bulk delete finished by {}: {} deleted, {} failed",
            actor, deleted.size(), failed.size() );
        resource.sendJson( response, Map.of( "deleted", deleted, "failed", failed ) );
    }

    void handlePurgeVersions( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = resource.parseJsonBody( request, response );
        if ( body == null ) return;

        final String pageName = resource.getJsonString( body, "page" );
        final int keepLatest = resource.getJsonInt( body, "keepLatest", 1 );

        if ( pageName == null || pageName.isBlank() ) {
            resource.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "page name is required" );
            return;
        }
        if ( keepLatest < 1 ) {
            resource.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "keepLatest must be at least 1" );
            return;
        }

        try {
            final PageManager pm = resource.getSubsystems().page().pages();
            final List< ? extends Page > history = pm.getVersionHistory( pageName );

            if ( history == null || history.isEmpty() ) {
                resource.sendJson( response, Map.of( "purged", 0, "remaining", 0 ) );
                return;
            }

            // History is in descending order (newest first). Keep the first N.
            int purged = 0;
            for ( int i = keepLatest; i < history.size(); i++ ) {
                final Page oldVersion = history.get( i );
                try {
                    pm.deleteVersion( oldVersion );
                    purged++;
                } catch ( final ProviderException e ) {
                    LOG.warn( "Failed to purge version {} of {}: {}", oldVersion.getVersion(), pageName, e.getMessage() );
                }
            }

            LOG.info( "Purged {} old versions of {}, kept latest {}", purged, pageName, keepLatest );
            resource.sendJson( response, Map.of( "purged", purged, "remaining", Math.min( keepLatest, history.size() ) ) );
        } catch ( final Exception e ) {
            resource.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to purge versions" );
        }
    }

    private static String resolveActor( final HttpServletRequest request ) {
        final String user = request.getRemoteUser();
        return ( user != null && !user.isEmpty() ) ? user : "admin";
    }
}
