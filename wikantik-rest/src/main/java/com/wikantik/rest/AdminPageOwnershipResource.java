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
import com.wikantik.api.comments.PageOwnership;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.comments.PageOwnerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for admin page-ownership management. Mapped to
 * {@code /admin/page-ownership/*}; all requests are pre-authorized by
 * {@code AdminAuthFilter} (AllPermission), so no permission check is
 * performed here.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /admin/page-ownership?filter=orphaned&limit=&offset=} — list owners where owner_login IS NULL</li>
 *   <li>{@code GET /admin/page-ownership?filter=by-owner&owner=<login>&limit=&offset=} — list owners by login. {@code owner=<orphaned>} is equivalent to {@code filter=orphaned}.</li>
 *   <li>{@code POST /admin/page-ownership/reassign} {@code {pages: [canonicalId...], newOwner: "login"}} — per-page reassign. {@code newOwner="<orphaned>"} orphans the pages.</li>
 *   <li>{@code POST /admin/page-ownership/reassign-by-user} {@code {fromOwner, toOwner}} — bulk move. {@code fromOwner="<orphaned>"} matches NULL owners; {@code toOwner="<orphaned>"} orphans them.</li>
 * </ul>
 */
public class AdminPageOwnershipResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminPageOwnershipResource.class );

    /** Sentinel value representing the {@code NULL} owner. */
    static final String ORPHANED = "<orphaned>";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 500;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    /* ---- seams (overridable for tests) ---- */

    protected PageOwnerService pageOwners() {
        return getSubsystems().persistence().pageOwners();
    }

    protected UserDatabase users() {
        return getSubsystems().auth().users().getUserDatabase();
    }

    protected String currentUser( final HttpServletRequest request ) {
        return Wiki.session().find( getEngine(), request ).getLoginPrincipal().getName();
    }

    protected boolean userExists( final String login ) {
        if ( login == null || login.isBlank() ) return false;
        try {
            users().findByLoginName( login );
            return true;
        } catch ( final NoSuchPrincipalException e ) {
            return false;
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "userExists({}) WikiSecurityException: {}", login, e.getMessage() );
            return false;
        }
    }

    /* ---- handlers ---- */

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final String filter = request.getParameter( "filter" );
        final String owner  = request.getParameter( "owner" );
        final int limit  = clampLimit( request.getParameter( "limit" ) );
        final int offset = clampNonNeg( request.getParameter( "offset" ) );

        final boolean orphanedMode =
                "orphaned".equals( filter )
                || ( "by-owner".equals( filter ) && ORPHANED.equals( owner ) );

        final List< PageOwnership > rows;
        final int total;
        if ( orphanedMode ) {
            rows  = pageOwners().listOrphaned( limit, offset );
            total = pageOwners().countOrphaned();
        } else if ( "by-owner".equals( filter ) && owner != null && !owner.isBlank() ) {
            rows  = pageOwners().listByOwner( owner, limit, offset );
            total = pageOwners().countByOwner( owner );
        } else {
            sendError( response, 400, "filter must be 'orphaned' or 'by-owner' (with owner=...)" );
            return;
        }

        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "pages", rows.stream().map( AdminPageOwnershipResource::toMap ).toList() );
        body.put( "total", total );
        sendJson( response, body );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final String[] seg = segments( request );
        if ( seg.length == 1 && "reassign".equals( seg[ 0 ] ) ) {
            reassign( request, response );
        } else if ( seg.length == 1 && "reassign-by-user".equals( seg[ 0 ] ) ) {
            reassignByUser( request, response );
        } else {
            sendNotFound( response, "Unknown admin/page-ownership route" );
        }
    }

    private void reassign( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final JsonArray pages = body.has( "pages" ) && body.get( "pages" ).isJsonArray()
                ? body.getAsJsonArray( "pages" ) : null;
        final String newOwner = getJsonString( body, "newOwner" );
        if ( pages == null || pages.isEmpty() ) {
            sendError( response, 400, "pages: non-empty array required" );
            return;
        }
        if ( newOwner == null || newOwner.isBlank() ) {
            sendError( response, 400, "newOwner is required" );
            return;
        }
        // Special: newOwner="<orphaned>" means orphan the pages.
        final String me = currentUser( request );
        final String storedOwner;
        if ( ORPHANED.equals( newOwner ) ) {
            storedOwner = null;
        } else {
            if ( !userExists( newOwner ) ) {
                sendError( response, 400, "newOwner does not exist: " + newOwner );
                return;
            }
            storedOwner = newOwner;
        }
        int updated = 0;
        for ( int i = 0; i < pages.size(); i++ ) {
            final String cid = pages.get( i ).getAsString();
            pageOwners().setOwner( cid, storedOwner, me );
            updated++;
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "updated", updated );
        sendJson( response, result );
    }

    private void reassignByUser( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String fromOwner = getJsonString( body, "fromOwner" );
        final String toOwner   = getJsonString( body, "toOwner" );
        if ( fromOwner == null || fromOwner.isBlank() || toOwner == null || toOwner.isBlank() ) {
            sendError( response, 400, "fromOwner and toOwner are required" );
            return;
        }
        if ( !ORPHANED.equals( toOwner ) && !userExists( toOwner ) ) {
            sendError( response, 400, "toOwner does not exist: " + toOwner );
            return;
        }
        final String me = currentUser( request );
        final int updated;
        if ( ORPHANED.equals( fromOwner ) && ORPHANED.equals( toOwner ) ) {
            updated = 0;  // no-op
        } else if ( ORPHANED.equals( fromOwner ) ) {
            updated = pageOwners().reassignFromOrphaned( toOwner, me );
        } else if ( ORPHANED.equals( toOwner ) ) {
            updated = pageOwners().orphanByOwner( fromOwner, me );
        } else {
            updated = pageOwners().bulkReassign( fromOwner, toOwner, me );
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "updated", updated );
        sendJson( response, result );
    }

    /* ---- helpers ---- */

    private static Map< String, Object > toMap( final PageOwnership p ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "canonicalId", p.canonicalId() );
        m.put( "ownerLogin",  p.ownerLogin() );
        m.put( "assignedBy",  p.assignedBy() );
        m.put( "assignedAt",  p.assignedAt() == null ? null : p.assignedAt().toString() );
        return m;
    }

    private static String[] segments( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.isBlank() || "/".equals( pathInfo ) ) return new String[ 0 ];
        final String trimmed = pathInfo.startsWith( "/" ) ? pathInfo.substring( 1 ) : pathInfo;
        return trimmed.split( "/" );
    }

    private static int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            final int v = Integer.parseInt( raw );
            if ( v <= 0 ) return DEFAULT_LIMIT;
            return Math.min( v, MAX_LIMIT );
        } catch ( final NumberFormatException e ) {
            return DEFAULT_LIMIT;
        }
    }

    private static int clampNonNeg( final String raw ) {
        if ( raw == null || raw.isBlank() ) return 0;
        try {
            final int v = Integer.parseInt( raw );
            return Math.max( v, 0 );
        } catch ( final NumberFormatException e ) {
            return 0;
        }
    }
}
