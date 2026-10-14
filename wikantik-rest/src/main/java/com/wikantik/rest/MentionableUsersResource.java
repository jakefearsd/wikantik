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

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
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
 * Requires a signed-in (authenticated) session; anonymous and cookie-asserted
 * callers get a {@code 401}. Locked accounts are filtered out so muted users
 * cannot be summoned.
 * <p>
 * Members are shown to each other by <em>display name</em> only. Each row is
 * {@code {"id": "<users.uid>", "displayName": "<name>"}} (the id is the <em>stored</em> uid, read through
 * {@link PublicIdentity#userIdForLogin}): no login name (under
 * SSO it is the member's email address) and no other profile field. The
 * picker inserts {@code @[<displayName>](user:<id>)}; the id is the key.
 * <p>
 * Matching rule: case-insensitive <em>substring</em> match on the display name
 * (so a last-name fragment still finds the member) — and on nothing else.
 * Matching on the login would let any signed-in member probe which logins
 * exist ({@code q=jake@} returning a row confirms an address), so the login is
 * never consulted. A member with no display name (or one that is email-shaped, which
 * {@code PublicIdentity} also refuses to show) or no <em>stored</em> uid cannot be shown and is skipped.
 * <p>
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

    /** Seam — the stored-uid lookup, overridable for unit tests. */
    protected PublicIdentity publicIdentity() {
        return getSubsystems().auth().publicIdentity();
    }

    /** Seam — auth gate, overridable for unit tests. */
    protected boolean isAuthenticated( final HttpServletRequest request ) {
        final Session s = Wiki.session().find( getEngine(), request );
        return s != null && s.isAuthenticated();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        // Gate before touching the user database: the member directory is for signed-in
        // members only, so an unauthenticated caller must not be able to enumerate it.
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
            final PublicIdentity identity = publicIdentity();
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
                final String uid = storedUid( identity, p );
                final String displayName = usableDisplayName( p );
                if ( uid.isEmpty() || displayName.isEmpty() ) {
                    // Nothing safe to show or key a mention on; never fall back to the login.
                    LOG.debug( "mentionable: skipping an account with no stored uid or no display name" );
                    continue;
                }
                if ( q.isEmpty() || displayName.toLowerCase( Locale.ROOT ).contains( q ) ) {
                    final Map< String, Object > row = new LinkedHashMap<>();
                    row.put( "id", uid );
                    row.put( "displayName", displayName );
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

    /**
     * The uid <em>stored</em> for the account, or {@code ""} when the row has none. Deliberately not
     * {@code p.getUid()}: for a row whose {@code users.uid} is NULL the profile mapper invents a fresh random value on
     * every load, which no later lookup would resolve, so a mention keyed on it would silently notify nobody.
     * {@link PublicIdentity#userIdForLogin} reads the stored column.
     */
    private static String storedUid( final PublicIdentity identity, final UserProfile p ) {
        final String login = p.getLoginName();
        if ( login == null || login.isBlank() ) {
            return "";
        }
        return identity.userIdForLogin( login ).filter( uid -> !uid.isBlank() ).orElse( "" );
    }

    /**
     * The account's display name, or {@code ""} when it has none that can be shown. An email-shaped name
     * (a legacy account whose full name was its address) is unusable for the same reason
     * {@code PublicIdentity#displayName} masks it: showing it would publish an address.
     */
    private static String usableDisplayName( final UserProfile p ) {
        final String name = p.getFullname() == null ? "" : p.getFullname().trim();
        return name.indexOf( '@' ) >= 0 ? "" : name;
    }

    private static int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            return Math.max( 1, Math.min( MAX_LIMIT, Integer.parseInt( raw ) ) );
        } catch ( final NumberFormatException nfe ) {
            // Client-supplied parameter; falling back to the default is the documented behaviour.
            LOG.debug( "Limit '{}' is not an integer; using default {}: {}", raw, DEFAULT_LIMIT, nfe.getMessage() );
            return DEFAULT_LIMIT;
        }
    }
}
