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

import com.wikantik.api.comments.MentionFeedItem;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.comments.mentions.MentionFeedDao;
import com.wikantik.comments.mentions.MentionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST servlet backing the per-user mention feed and unread tracking.
 * Mapped to {@code /api/me/mentions/*}.
 *
 * <ul>
 *   <li>{@code GET /api/me/mentions} — feed list, newest-first, cursor pagination via {@code before}.</li>
 *   <li>{@code GET /api/me/mentions/unread-count} — {@code {count: N}}.</li>
 *   <li>{@code POST /api/me/mentions/{id}/read} — mark one row read; 403 if not the addressee.</li>
 *   <li>{@code POST /api/me/mentions/mark-all-read} — mark all unread for current user.</li>
 * </ul>
 *
 * All routes require authentication; anonymous callers get a 401.
 */
public class MyMentionsResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 50;

    /** Seam — the feed DAO, overridable for unit tests. */
    protected MentionFeedDao mentionFeed() {
        return getSubsystems().persistence().mentionFeed();
    }

    /** Seam — the mention service, overridable for unit tests. */
    protected MentionService mentionService() {
        return getSubsystems().persistence().mentions();
    }

    /** Seam — slug resolution from canonical id, overridable for unit tests. */
    protected Optional< String > resolveSlug( final String canonicalId ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
    }

    /** Seam — current authenticated user's login, overridable for unit tests. */
    protected String currentUser( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        return Wiki.session().find( engine, request ).getUserPrincipal().getName();
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
        final String[] seg = segments( request );
        if ( seg.length == 0 ) {
            list( request, response );
        } else if ( seg.length == 1 && "unread-count".equals( seg[ 0 ] ) ) {
            unreadCount( request, response );
        } else {
            sendNotFound( response, "Unknown mentions route" );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if ( !isAuthenticated( request ) ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Login required" );
            return;
        }
        final String[] seg = segments( request );
        if ( seg.length == 1 && "mark-all-read".equals( seg[ 0 ] ) ) {
            markAllRead( request, response );
        } else if ( seg.length == 2 && "read".equals( seg[ 1 ] ) ) {
            markOneRead( request, response, seg[ 0 ] );
        } else {
            sendNotFound( response, "Unknown mentions route" );
        }
    }

    // ---- handlers ----

    private void list( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final String me = currentUser( request );
        final MentionFeedDao.Status status = "unread".equals( request.getParameter( "status" ) )
                ? MentionFeedDao.Status.UNREAD : MentionFeedDao.Status.ALL;
        final int limit = clampLimit( request.getParameter( "limit" ) );
        final Optional< Instant > before = parseInstant( request.getParameter( "before" ) );

        final List< MentionFeedItem > rows = mentionFeed().list( me, status, limit, before );
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final MentionFeedItem r : rows ) {
            final String slug = resolveSlug( r.canonicalId() ).orElse( r.canonicalId() );
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "id", r.id().toString() );
            m.put( "threadId", r.threadId().toString() );
            m.put( "commentId", r.commentId().toString() );
            m.put( "canonicalId", r.canonicalId() );
            m.put( "pageName", slug );
            m.put( "snippet", r.snippet() );
            m.put( "mentionedBy", r.mentionedBy() );
            m.put( "isOwnerMention", r.isOwnerMention() );
            m.put( "mentionedAt", r.mentionedAt() == null ? null : r.mentionedAt().toString() );
            m.put( "readAt", r.readAt() == null ? null : r.readAt().toString() );
            out.add( m );
        }
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "mentions", out );
        sendJson( response, body );
    }

    private void unreadCount( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "count", mentionService().unreadCount( currentUser( request ) ) );
        sendJson( response, body );
    }

    private void markOneRead( final HttpServletRequest request, final HttpServletResponse response,
                              final String idRaw ) throws IOException {
        final UUID id;
        try {
            id = UUID.fromString( idRaw );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid id: " + idRaw );
            return;
        }
        final boolean ok = mentionService().markRead( id, currentUser( request ) );
        if ( !ok ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN, "Mention not found or not yours" );
            return;
        }
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "ok", true );
        sendJson( response, body );
    }

    private void markAllRead( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final int n = mentionService().markAllRead( currentUser( request ) );
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "updated", n );
        sendJson( response, body );
    }

    // ---- helpers ----

    private static int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            return Math.max( 1, Math.min( MAX_LIMIT, Integer.parseInt( raw ) ) );
        } catch ( final NumberFormatException e ) {
            return DEFAULT_LIMIT;
        }
    }

    private static Optional< Instant > parseInstant( final String raw ) {
        if ( raw == null || raw.isBlank() ) return Optional.empty();
        try {
            return Optional.of( Instant.parse( raw ) );
        } catch ( final DateTimeParseException e ) {
            return Optional.empty();
        }
    }

    private static String[] segments( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.isBlank() || "/".equals( pathInfo ) ) return new String[ 0 ];
        final String trimmed = pathInfo.startsWith( "/" ) ? pathInfo.substring( 1 ) : pathInfo;
        return trimmed.split( "/" );
    }
}
