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
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST servlet backing the @-mention autocomplete picker in the comment
 * composer. Mapped to {@code /api/users/mentionable}.
 * <p>
 * Login-required (anonymous callers get a 401 — the picker is only useful to
 * authenticated users and exposing the user list publicly would be a privacy
 * leak). Locked accounts are filtered out so muted users cannot be summoned.
 * <p>
 * Matching rules:
 * <ul>
 *   <li>{@code login_name} — case-insensitive <em>prefix</em> match (the
 *       typical "I typed @al, find me Alice" pattern).</li>
 *   <li>{@code full_name} — case-insensitive <em>substring</em> match (so a
 *       last-name fragment still finds the user).</li>
 * </ul>
 * Default page size {@value #DEFAULT_LIMIT}; hard cap {@value #MAX_LIMIT} —
 * the picker is a typeahead, not a directory browser, so unbounded responses
 * would just be wasted bytes.
 */
public class MentionableUsersResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( MentionableUsersResource.class );
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 10;

    /** Seam — the user database lookup, overridable for unit tests. */
    protected UserDatabase users() {
        return getSubsystems().auth().users().getUserDatabase();
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
        final String qRaw = request.getParameter( "q" );
        final String q = qRaw == null ? "" : qRaw.trim().toLowerCase( Locale.ROOT );
        final int limit = clampLimit( request.getParameter( "limit" ) );

        final List< Map< String, Object > > out = new ArrayList<>();
        try {
            final UserDatabase db = users();
            final Principal[] wikiNames = db.getWikiNames();
            for ( final Principal wikiName : wikiNames ) {
                final UserProfile p;
                try {
                    p = db.findByWikiName( wikiName.getName() );
                } catch ( final NoSuchPrincipalException e ) {
                    // Profile vanished between enumeration and lookup — skip it.
                    LOG.debug( "mentionable: profile vanished for wikiName={}", wikiName.getName() );
                    continue;
                }
                if ( p == null || p.isLocked() ) continue;
                final String login = p.getLoginName() == null ? "" : p.getLoginName().toLowerCase( Locale.ROOT );
                final String full  = p.getFullname()  == null ? "" : p.getFullname().toLowerCase( Locale.ROOT );
                if ( q.isEmpty() || login.startsWith( q ) || full.contains( q ) ) {
                    final Map< String, Object > row = new LinkedHashMap<>();
                    row.put( "loginName", p.getLoginName() );
                    row.put( "fullName",  p.getFullname() );
                    out.add( row );
                    if ( out.size() >= limit ) break;
                }
            }
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "mentionable: failed to enumerate users: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to list users" );
            return;
        }

        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "users", out );
        sendJson( response, body );
    }

    private static int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            return Math.max( 1, Math.min( MAX_LIMIT, Integer.parseInt( raw ) ) );
        } catch ( final NumberFormatException nfe ) {
            return DEFAULT_LIMIT;
        }
    }
}
