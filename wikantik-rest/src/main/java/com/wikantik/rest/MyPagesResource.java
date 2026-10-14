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
import com.wikantik.auth.AuthorKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * REST servlet listing the pages the current user authored — i.e. is the
 * recorded author (most-recent editor) of — newest-first. Mapped to
 * {@code /api/me/pages/*}.
 *
 * <ul>
 *   <li>{@code GET /api/me/pages?limit=N} — {@code {pages:[{slug,title,lastModified}]}}.</li>
 * </ul>
 *
 * <p>"Authored" means {@link Page#getAuthor()} <em>equals</em> the caller's login — the value every writer
 * records via {@link AuthorKeys#authorKeyFor}. Full names (legacy authorship) and other spellings of the login
 * never match, so users who share a display name no longer see each other's pages. This is the author of the
 * current version (the last person to save), so a page someone else last edited will not appear; full version
 * history is not walked. Backed by {@link PageManager#getRecentChanges()}, which already returns pages ordered
 * most-recently-modified-first and carries author + last-modified on each {@link Page} (the same scan that
 * powers {@code /api/recent-changes}).</p>
 *
 * <p>Requires authentication; anonymous callers get a 401.</p>
 */
public class MyPagesResource extends RestServletBase {
    private static final Logger LOG = LogManager.getLogger( MyPagesResource.class );


    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 100;

    /** Seam — candidate pages (recent changes, newest-first), overridable for unit tests. */
    protected Collection< Page > candidatePages() {
        return getSubsystems().page().pages().getRecentChanges();
    }

    /**
     * Seam — the stored author strings that count as "me", overridable for unit tests: exactly the caller's
     * login, i.e. what {@link AuthorKeys#authorKeyFor} records when this caller saves a page.
     */
    protected Set< String > currentUserIdentities( final HttpServletRequest request ) {
        final Session s = Wiki.session().find( getEngine(), request );
        if ( s == null || !s.isAuthenticated() ) {
            return Set.of();
        }
        final String key = AuthorKeys.authorKeyFor( s );
        return key == null ? Set.of() : Set.of( key );
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
            if ( author != null && me.contains( author ) ) {
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
            // Client-supplied parameter; falling back to the default is the documented behaviour.
            LOG.debug( "Limit '{}' is not an integer; using default {}: {}", raw, DEFAULT_LIMIT, e.getMessage() );
            return DEFAULT_LIMIT;
        }
    }
}
