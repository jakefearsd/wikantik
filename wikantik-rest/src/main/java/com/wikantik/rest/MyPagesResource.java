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

import com.wikantik.api.comments.PageOwnership;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.comments.PageOwnerService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST servlet listing the pages owned by the current user.
 * Mapped to {@code /api/me/pages/*}.
 *
 * <ul>
 *   <li>{@code GET /api/me/pages?limit=N} — {@code {pages:[{canonicalId,slug,title,assignedAt}]}}, newest-first.</li>
 * </ul>
 *
 * Requires authentication; anonymous callers get a 401.
 */
public class MyPagesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 100;

    /** Seam — page-owner service, overridable for unit tests. */
    protected PageOwnerService pageOwners() {
        return getSubsystems().persistence().pageOwners();
    }

    /** Seam — slug resolution from canonical id, overridable for unit tests. */
    protected Optional< String > resolveSlug( final String canonicalId ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
    }

    /** Seam — current authenticated user's login, overridable for unit tests. */
    protected String currentUser( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        return Wiki.session().find( engine, request ).getLoginPrincipal().getName();
    }

    /** Seam — auth gate, overridable for unit tests. */
    protected boolean isAuthenticated( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session s = Wiki.session().find( engine, request );
        return s != null && s.isAuthenticated();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if ( !isAuthenticated( request ) ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Login required" );
            return;
        }
        final int limit = clampLimit( request.getParameter( "limit" ) );
        final List< PageOwnership > rows = pageOwners().listByOwner( currentUser( request ), limit, 0 );
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final PageOwnership r : rows ) {
            final Optional< String > slug = resolveSlug( r.canonicalId() );
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "canonicalId", r.canonicalId() );
            m.put( "slug", slug.orElse( r.canonicalId() ) );
            m.put( "title", slug.orElse( r.canonicalId() ) );
            m.put( "assignedAt", r.assignedAt() == null ? null : r.assignedAt().toString() );
            out.add( m );
        }
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "pages", out );
        sendJson( response, body );
    }

    private static int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            return Math.max( 1, Math.min( MAX_LIMIT, Integer.parseInt( raw ) ) );
        } catch ( final NumberFormatException e ) {
            return DEFAULT_LIMIT;
        }
    }
}
