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
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST servlet listing the pages the current user authored — i.e. is the
 * recorded author (most-recent editor) of — newest-first. Mapped to
 * {@code /api/me/pages/*}.
 *
 * <ul>
 *   <li>{@code GET /api/me/pages?limit=N} — {@code {pages:[{slug,title,lastModified}]}}.</li>
 * </ul>
 *
 * <p>"Authored" means {@link Page#getAuthor()} matches the caller's login. This
 * is the author of the current version (the last person to save), so a page
 * someone else last edited will not appear; full version history is not walked.
 * Backed by {@link PageManager#getRecentChanges()}, which already returns pages
 * ordered most-recently-modified-first and carries author + last-modified on
 * each {@link Page} (the same scan that powers {@code /api/recent-changes}).</p>
 *
 * <p>Requires authentication; anonymous callers get a 401.</p>
 */
public class MyPagesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 100;

    /** Seam — candidate pages (recent changes, newest-first), overridable for unit tests. */
    protected Collection< Page > candidatePages() {
        return getSubsystems().page().pages().getRecentChanges();
    }

    /**
     * Seam — the set of identity names that count as "me", overridable for unit
     * tests. A page is mine when its {@link Page#getAuthor()} matches any of
     * these. We include both the user principal (wiki name — this is what
     * {@code PageResource} stores as the author on save) and the login principal,
     * so authorship recorded under either representation is matched.
     */
    protected Set< String > currentUserIdentities( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session s = Wiki.session().find( engine, request );
        final Set< String > ids = new HashSet<>();
        if ( s.getUserPrincipal() != null && s.getUserPrincipal().getName() != null ) {
            ids.add( s.getUserPrincipal().getName() );
        }
        if ( s.getLoginPrincipal() != null && s.getLoginPrincipal().getName() != null ) {
            ids.add( s.getLoginPrincipal().getName() );
        }
        return ids;
    }

    /** Seam — auth gate, overridable for unit tests. */
    protected boolean isAuthenticated( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session s = Wiki.session().find( engine, request );
        return s != null && s.isAuthenticated();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        if ( !isAuthenticated( request ) ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Login required" );
            return;
        }
        final Set< String > me = currentUserIdentities( request );
        final int limit = clampLimit( request.getParameter( "limit" ) );

        final List< Page > mine = new ArrayList<>();
        for ( final Page p : candidatePages() ) {
            final String author = p.getAuthor();
            if ( author != null && me.stream().anyMatch( author::equalsIgnoreCase ) ) {
                mine.add( p );
            }
        }
        // Newest-first; pages with no timestamp sort last.
        mine.sort( Comparator.comparing( Page::getLastModified,
                Comparator.nullsLast( Comparator.naturalOrder() ) ).reversed() );

        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final Page p : mine ) {
            if ( out.size() >= limit ) break;
            final Date lm = p.getLastModified();
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "slug", p.getName() );
            m.put( "title", p.getName() );
            m.put( "lastModified", lm == null ? null : lm.toInstant().toString() );
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
