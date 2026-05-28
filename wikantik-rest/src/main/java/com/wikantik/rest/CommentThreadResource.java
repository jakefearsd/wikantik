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
import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.comments.CommentStore;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.comments.mentions.MentionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST servlet for anchored comment threads. Mapped to {@code /api/comment-threads/*}.
 * Replaces the legacy {@code CommentResource}.
 */
public class CommentThreadResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( CommentThreadResource.class );

    protected CommentStore commentStore() {
        return getSubsystems().persistence().comments();
    }

    protected MentionService mentionService() {
        return getSubsystems().persistence().mentions();
    }

    protected PageOwnerService pageOwnerService() {
        return getSubsystems().persistence().pageOwners();
    }

    protected Optional< String > resolveCanonicalId( final String slug ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveCanonicalIdFromSlug( slug );
    }

    protected Optional< String > resolveSlug( final String canonicalId ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
    }

    protected String currentUser( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        return Wiki.session().find( engine, request ).getUserPrincipal().getName();
    }

    /**
     * Routes PATCH requests to {@link #doPatch(HttpServletRequest, HttpServletResponse)}.
     * Mirror PageResource: route PATCH to doPatch explicitly (consistent with the other
     * REST resources); everything else flows through {@link RestServletBase#service} so
     * CORS headers are still applied.
     */
    @Override
    protected void service( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if ( "PATCH".equalsIgnoreCase( request.getMethod() ) ) {
            doPatch( request, response );
        } else {
            super.service( request, response );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String pageName = request.getParameter( "page" );
        if ( pageName == null || pageName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "page query parameter is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "view" ) ) return;
        final Optional< String > canonicalId = resolveCanonicalId( pageName );
        if ( canonicalId.isEmpty() ) {
            sendNotFound( response, "Page not found or not indexed: " + pageName );
            return;
        }
        final String status = normalizeStatus( request.getParameter( "status" ) );
        final List< CommentThread > threads = commentStore().listByCanonicalId( canonicalId.get(), status );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "page", pageName );
        result.put( "threads", threads.stream().map( CommentThreadResource::threadToMap ).toList() );
        sendJson( response, result );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] seg = segments( request );
        if ( seg.length == 0 ) {
            createThread( request, response );
        } else if ( seg.length == 2 && "comments".equals( seg[ 1 ] ) ) {
            addReply( request, response, seg[ 0 ] );
        } else if ( seg.length == 2 && "resolve".equals( seg[ 1 ] ) ) {
            setStatus( request, response, seg[ 0 ], true );
        } else if ( seg.length == 2 && "reopen".equals( seg[ 1 ] ) ) {
            setStatus( request, response, seg[ 0 ], false );
        } else {
            sendNotFound( response, "Unknown comment route" );
        }
    }

    protected void doPatch( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] seg = segments( request );
        if ( seg.length == 3 && "comments".equals( seg[ 1 ] ) ) {
            editComment( request, response, seg[ 0 ], seg[ 2 ] );
        } else {
            sendNotFound( response, "Unknown comment route" );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] seg = segments( request );
        if ( seg.length == 3 && "comments".equals( seg[ 1 ] ) ) {
            deleteComment( request, response, seg[ 0 ], seg[ 2 ] );
        } else if ( seg.length == 1 ) {
            deleteThread( request, response, seg[ 0 ] );
        } else {
            sendNotFound( response, "Unknown comment route" );
        }
    }

    /** Moderator-only thread delete. Requires the page {@code delete} permission;
     *  cascades to all comments in the thread via the DAO's ON DELETE CASCADE. */
    private void deleteThread( final HttpServletRequest request, final HttpServletResponse response,
                               final String threadIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "delete" );
        if ( ctx == null ) return;
        final boolean removed = commentStore().deleteThread( ctx.threadId );
        if ( !removed ) { sendNotFound( response, "Comment thread not found" ); return; }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "deleted", true );
        result.put( "id", ctx.threadId.toString() );
        sendJson( response, result );
    }

    private void createThread( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String pageName = request.getParameter( "page" );
        if ( pageName == null || pageName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "page query parameter is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "comment" ) ) return;
        final Optional< String > canonicalId = resolveCanonicalId( pageName );
        if ( canonicalId.isEmpty() ) {
            sendNotFound( response, "Page not found or not indexed: " + pageName );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String exact = getJsonString( body, "exact" );
        final String text  = getJsonString( body, "text" );
        if ( exact == null || exact.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "exact (selected text) is required" );
            return;
        }
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }
        final TextQuoteSelector anchor = new TextQuoteSelector(
                exact, getJsonString( body, "prefix" ), getJsonString( body, "suffix" ) );
        final String canonical = canonicalId.get();
        final String user = currentUser( request );
        final String owner = pageOwnerService().getOwner( canonical );
        final Optional< String > ownerForMention =
                owner.equals( user ) ? Optional.empty() : Optional.of( owner );
        final CommentThread t = commentStore().createThread(
                canonical, anchor, user, text, mentionService(), ownerForMention );
        sendJson( response, threadToMap( t ) );
    }

    private void addReply( final HttpServletRequest request, final HttpServletResponse response,
                           final String threadIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String text = getJsonString( body, "text" );
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }
        final Comment c = commentStore().addComment(
                ctx.threadId, currentUser( request ), text, mentionService() );
        sendJson( response, commentToMap( c ) );
    }

    private void setStatus( final HttpServletRequest request, final HttpServletResponse response,
                            final String threadIdRaw, final boolean resolve ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final boolean ok = resolve
                ? commentStore().resolve( ctx.threadId, currentUser( request ) )
                : commentStore().reopen( ctx.threadId );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "id", ctx.threadId.toString() );
        result.put( "status", resolve ? CommentThread.RESOLVED : CommentThread.OPEN );
        result.put( "updated", ok );
        sendJson( response, result );
    }

    private void editComment( final HttpServletRequest request, final HttpServletResponse response,
                              final String threadIdRaw, final String commentIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final UUID commentId = parseUuid( commentIdRaw, response );
        if ( commentId == null ) return;
        final Optional< Comment > existing = commentStore().findComment( commentId );
        if ( existing.isEmpty() ) { sendNotFound( response, "Comment not found" ); return; }
        if ( !existing.get().threadId().equals( ctx.threadId ) ) {
            sendNotFound( response, "Comment not found in this thread" );
            return;
        }
        if ( !existing.get().author().equals( currentUser( request ) ) ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN,
                    "Only the comment author can edit this comment" );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String text = getJsonString( body, "text" );
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }
        final Comment updated = commentStore().editComment(
                commentId, existing.get().body(), text, currentUser( request ), mentionService() )
                .orElse( null );
        if ( updated == null ) { sendNotFound( response, "Comment not found" ); return; }
        sendJson( response, commentToMap( updated ) );
    }

    private void deleteComment( final HttpServletRequest request, final HttpServletResponse response,
                                final String threadIdRaw, final String commentIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final UUID commentId = parseUuid( commentIdRaw, response );
        if ( commentId == null ) return;
        final Optional< Comment > existing = commentStore().findComment( commentId );
        if ( existing.isEmpty() ) { sendNotFound( response, "Comment not found" ); return; }
        if ( !existing.get().threadId().equals( ctx.threadId ) ) {
            sendNotFound( response, "Comment not found in this thread" );
            return;
        }
        final boolean isAuthor = existing.get().author().equals( currentUser( request ) );
        final boolean canModerate = hasPagePermission( request, ctx.slug, "delete" );
        if ( !isAuthor && !canModerate ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN,
                    "Only the comment author or a page moderator can delete this comment" );
            return;
        }
        commentStore().deleteComment( commentId );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "deleted", true );
        result.put( "id", commentId.toString() );
        sendJson( response, result );
    }

    private ThreadCtx authorizeThread( final HttpServletRequest request, final HttpServletResponse response,
                                       final String threadIdRaw, final String action ) throws IOException {
        final UUID threadId = parseUuid( threadIdRaw, response );
        if ( threadId == null ) return null;
        final Optional< CommentThread > thread = commentStore().findThread( threadId );
        if ( thread.isEmpty() ) { sendNotFound( response, "Comment thread not found" ); return null; }
        final Optional< String > slug = resolveSlug( thread.get().canonicalId() );
        if ( slug.isEmpty() ) { sendNotFound( response, "Page for thread no longer exists" ); return null; }
        if ( !checkPagePermission( request, response, slug.get(), action ) ) return null;
        return new ThreadCtx( threadId, slug.get() );
    }

    private record ThreadCtx( UUID threadId, String slug ) {}

    private UUID parseUuid( final String raw, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( raw );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid id: " + raw );
            return null;
        }
    }

    private static String normalizeStatus( final String raw ) {
        if ( "open".equals( raw ) || "resolved".equals( raw ) ) return raw;
        return "all";
    }

    private static String[] segments( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.isBlank() || "/".equals( pathInfo ) ) return new String[ 0 ];
        final String trimmed = pathInfo.startsWith( "/" ) ? pathInfo.substring( 1 ) : pathInfo;
        return trimmed.split( "/" );
    }

    private static Map< String, Object > threadToMap( final CommentThread t ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", t.id().toString() );
        m.put( "status", t.status() );
        final Map< String, Object > anchor = new LinkedHashMap<>();
        anchor.put( "exact", t.anchor().exact() );
        anchor.put( "prefix", t.anchor().prefix() );
        anchor.put( "suffix", t.anchor().suffix() );
        m.put( "anchor", anchor );
        m.put( "createdBy", t.createdBy() );
        m.put( "createdAt", t.createdAt() == null ? null : t.createdAt().toString() );
        m.put( "resolvedBy", t.resolvedBy() );
        m.put( "resolvedAt", t.resolvedAt() == null ? null : t.resolvedAt().toString() );
        final List< Map< String, Object > > comments = new ArrayList<>();
        for ( final Comment c : t.comments() ) comments.add( commentToMap( c ) );
        m.put( "comments", comments );
        return m;
    }

    private static Map< String, Object > commentToMap( final Comment c ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", c.id().toString() );
        m.put( "threadId", c.threadId().toString() );
        m.put( "author", c.author() );
        m.put( "body", c.body() );
        m.put( "createdAt", c.createdAt() == null ? null : c.createdAt().toString() );
        m.put( "editedAt", c.editedAt() == null ? null : c.editedAt().toString() );
        return m;
    }
}
