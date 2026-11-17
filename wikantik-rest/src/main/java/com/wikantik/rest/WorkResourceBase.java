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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.work.Actor;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Project;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkUnavailableException;
import com.wikantik.work.WorkValidationException;
import com.wikantik.work.runtime.WikiWorkActors;
import com.wikantik.work.runtime.WikiWorkDirectory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared behaviour of the {@code /api/work/*} servlets: the feature flag (404 when work management is off), actor
 * resolution (401 when anonymous), method-and-path routing from a small table, the one mapping from the
 * {@code com.wikantik.work} exceptions to HTTP, and the one JSON configuration.
 *
 * <p><b>Names on the wire.</b> People are shown by display name; a login reaches only a wiki admin (see
 * {@link WorkJson}). Requests name people by display name too (a wiki admin may also send a login).
 *
 * <p><b>No distinguishing.</b> A malformed or unknown path is a 404; a not-found body never repeats the key or id.
 */
public abstract class WorkResourceBase extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( WorkResourceBase.class );

    /** The one neutral 404 message. */
    static final String NOT_FOUND = "Not found.";
    static final int MAX_LIMIT = 200;
    /** No work request body is larger than this many characters; longer is a 413. */
    static final long MAX_BODY_CHARS = 262_144L;

    // ---- seams (overridden by unit tests)

    /** The work service, or null when {@code wikantik.work.enabled} is false. */
    protected WorkService workService() {
        return getEngine() instanceof WikiEngine we ? we.workService() : null;
    }

    /**
     * The calling actor, or null when there is none. A request that came through {@link WorkApiKeyFilter} with a
     * verified personal key is its owner as a person (never an admin), and one with a verified agent token is that
     * agent while it is active and someone stands behind it; neither ever falls back to a session. A request
     * that carries a bearer credential the filter did not vouch for is no actor either. Only a request with no bearer
     * credential at all is a session request, so a bearer key always wins over a cookie on the same request.
     */
    protected Actor actor( final HttpServletRequest request ) {
        return WorkCallers.actorOf( workService(), request, this::sessionActor );
    }

    /** The actor of the request's login session, or null for an anonymous one. */
    protected Actor sessionActor( final HttpServletRequest request ) {
        final Session session = Wiki.session().find( getEngine(), request );
        return WikiWorkActors.actorFor( getEngine(), session );
    }

    /** The public display name for a stored login. */
    protected String displayName( final String login ) {
        return new WikiWorkDirectory( getEngine() ).displayName( login );
    }

    /** The opaque id a mention token carries for a login (the comment picker's key); empty when it has none. */
    protected Optional< String > mentionId( final String login ) {
        return new WikiWorkDirectory( getEngine() ).mentionId( login );
    }

    // ---- routing

    /** One request in flight, with the path segments captured by the route pattern. */
    protected final class Call {
        final HttpServletRequest req;
        final HttpServletResponse resp;
        final WorkService svc;
        final Actor actor;
        final List< String > params;

        Call( final HttpServletRequest req, final HttpServletResponse resp, final WorkService svc, final Actor actor,
              final List< String > params ) {
            this.req = req;
            this.resp = resp;
            this.svc = svc;
            this.actor = actor;
            this.params = params;
        }

        String pathParam( final int i ) {
            return params.get( i );
        }

        long idParam( final int i ) {
            try {
                return Long.parseLong( params.get( i ) );
            } catch ( final NumberFormatException e ) {
                throw new WorkNotFoundException( NOT_FOUND );
            }
        }

        String query( final String name ) {
            final String v = req.getParameter( name );
            return v == null || v.isBlank() ? null : v.trim();
        }

        /** The JSON body (at most {@value #MAX_BODY_CHARS} characters), or null after a 400 or 413 has been sent. */
        JsonObject body() throws IOException {
            return RestJson.parseJsonBody( req, resp, MAX_BODY_CHARS );
        }

        /** An optional JSON body: an empty object when there is none (blank, any length), null after a 400 or 413. */
        JsonObject optionalBody() throws IOException {
            return RestJson.parseOptionalJsonBody( req, resp, MAX_BODY_CHARS );
        }

        void json( final Object value ) throws IOException {
            WorkJson.write( resp, HttpServletResponse.SC_OK, toTree( value, actor ) );
        }

        void tree( final JsonElement tree ) throws IOException {
            WorkJson.write( resp, HttpServletResponse.SC_OK, tree );
        }

        void created( final Object value ) throws IOException {
            WorkJson.write( resp, HttpServletResponse.SC_CREATED, toTree( value, actor ) );
        }

        void noContent() {
            resp.setStatus( HttpServletResponse.SC_NO_CONTENT );
        }
    }

    /** A route handler. */
    @FunctionalInterface
    protected interface Handler {
        void handle( Call call ) throws Exception;
    }

    private transient volatile List< WorkRoutes.Route > routeTable;

    /** Adds a route; {@code pattern} is a path under {@code /api/work/} with {@code {}} for a captured segment. */
    protected static void route( final List< Object[] > into, final String method, final String pattern,
                                 final Handler handler ) {
        into.add( new Object[] { method, pattern, handler } );
    }

    /** The routes of this servlet: call {@link #route} for each. */
    protected abstract void routes( List< Object[] > into );

    private List< WorkRoutes.Route > table() {
        List< WorkRoutes.Route > t = routeTable;
        if ( t == null ) {
            final List< Object[] > raw = new ArrayList<>();
            routes( raw );
            t = WorkRoutes.compile( raw );
            routeTable = t;
        }
        return t;
    }

    @Override
    protected void service( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        setCorsHeaders( request, response );
        final WorkService svc = workService();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND );
            return;
        }
        if ( "OPTIONS".equalsIgnoreCase( request.getMethod() ) ) {
            response.setStatus( HttpServletResponse.SC_OK );
            return;
        }
        final Actor actor = WorkCallers.authenticate( this::actor, request, response );
        if ( actor == null ) {
            return;
        }
        final String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase( Locale.ROOT );
        final String[] segs = WorkRoutes.segments( request );
        try {
            for ( final WorkRoutes.Route r : table() ) {
                final List< String > captured = WorkRoutes.match( r, method, segs );
                if ( captured != null ) {
                    r.handler().handle( new Call( request, response, svc, actor, captured ) );
                    return;
                }
            }
            sendError( response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND );
        } catch ( final WorkNotFoundException e ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND );
        } catch ( final WorkDeniedException e ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN, e.reason() );
        } catch ( final WorkValidationException e ) {
            WorkJson.writeValidation( response, e );
        } catch ( final WorkConflictException e ) {
            conflict( request, response, svc, actor, segs, e );
        } catch ( final WorkUnavailableException e ) {
            // Retryable, and never an answer: the cause is logged, the caller is told to try again.
            LOG.warn( "Work API {} {} unavailable: {}", method, String.join( "/", segs ), e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service temporarily unavailable" );
        } catch ( final Exception e ) {
            LOG.warn( "Work API {} {} failed: {}", method, String.join( "/", segs ), e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error." );
        }
    }

    // ---- error bodies

    /**
     * A 409 with its {@code reason}: {@code stale} (and, for an item PATCH, the {@code current} item to merge against),
     * {@code archived} (the project is read-only; no {@code current}, there is nothing to merge), {@code exists},
     * {@code not_claimable} or {@code not_claimed}. A stale hand-off carries {@code current} too.
     */
    protected void conflict( final HttpServletRequest request, final HttpServletResponse response,
                             final WorkService svc, final Actor actor, final String[] segs,
                             final WorkConflictException e ) throws IOException {
        final JsonObject body = WorkJson.errorBody( HttpServletResponse.SC_CONFLICT, e.getMessage() );
        body.addProperty( "reason", e.reason() );
        if ( WorkConflictException.STALE.equals( e.reason() ) && WorkCallers.mergesAgainstCurrent( request, segs ) ) {
            try {
                body.add( "current", toTree( svc.item( actor, segs[ 1 ] ), actor ) );
            } catch ( final Exception inner ) {
                LOG.warn( "Could not load the current item after a conflict on {}: {}", segs[ 1 ],
                    inner.getMessage(), inner );
            }
        }
        WorkJson.write( response, HttpServletResponse.SC_CONFLICT, body );
    }

    // ---- JSON with display names

    /** The value as a JSON tree, people shown as {@link WorkJson} describes for {@code viewer}. */
    protected JsonElement toTree( final Object value, final Actor viewer ) {
        return toTree( value, viewer, lookupFor( viewer ) );
    }

    /** As {@link #toTree(Object, Actor)}, sharing {@code lookup} so a list resolves the registry once. */
    protected JsonElement toTree( final Object value, final Actor viewer, final WorkLookup lookup ) {
        if ( value instanceof JsonElement built ) {
            return built;   // already a response tree: enriching it twice would rename the names
        }
        final Map< String, String > cache = new HashMap<>();
        final Function< String, String > names = login -> cache.computeIfAbsent( login, this::displayName );
        return WorkJson.enrich( WorkJson.GSON.toJsonTree( value ), names,
            new WorkViewer( viewer.login(), viewer.admin(), viewer.agentId() ), lookup );
    }

    /** Agent and queue names for {@code viewer}, read from the registry the first time one is needed. */
    protected WorkLookup lookupFor( final Actor viewer ) {
        return WorkLookup.forViewer( workService(), viewer );
    }

    /** An item list with each item's card extras (commentCount, blocked, productKey, parentKey) beside its fields. */
    protected JsonElement summariesTree( final List< WorkService.ItemSummary > summaries, final Actor viewer ) {
        return WorkSummaries.list( summaries, viewer, lookupFor( viewer ), this::toTree );
    }

    /** Board columns {status, total, items} whose items carry the card extras. */
    protected JsonElement boardTree( final List< WorkService.SummaryColumn > columns, final Actor viewer ) {
        return WorkSummaries.board( columns, viewer, lookupFor( viewer ), this::toTree );
    }

    /** A project's JSON with the viewer's {@code role} and {@code archived} (read-only) added. */
    protected JsonObject projectWithRole( final Project project, final String role ) {
        final JsonObject o = WorkJson.GSON.toJsonTree( project ).getAsJsonObject();
        o.addProperty( "role", role );
        o.addProperty( "archived", project.archivedAt() != null );
        return o;
    }

    /** Every project the caller can see, each with the caller's role (resolved in one pass by the service). */
    protected JsonArray visibleProjects( final Call c ) throws Exception {
        final JsonArray out = new JsonArray();
        for ( final WorkService.ProjectRole pr : c.svc.listProjectsWithRoles( c.actor ) ) {
            out.add( projectWithRole( pr.project(), pr.role().wire() ) );
        }
        return out;
    }

    /** One project with the caller's role (404 when hidden or unknown). */
    protected JsonObject projectJson( final Call c, final String key ) throws Exception {
        final Project p = c.svc.project( c.actor, key );
        return projectWithRole( p, c.svc.roleIn( c.actor, key ).wire() );
    }

    // ---- request parsing helpers

    /** A required string field; missing or blank is a 400 naming the field. */
    protected static String requiredString( final JsonObject body, final String field ) {
        return WorkRequests.requiredString( body, field );
    }

    /** A string field, null when absent or JSON null; a non-string value is a 400. */
    protected static String optString( final JsonObject body, final String field ) {
        return WorkRequests.optString( body, field );
    }

    /** Converts one JSON value of a PATCH {@code changes} object to a plain String, Number, Boolean or null. */
    protected static Object plainValue( final String field, final JsonElement v ) {
        return WorkRequests.plainValue( field, v );
    }

    /** The list-query filter; {@code withStatus} reads the {@code status} parameter too. */
    protected ItemFilter filter( final Call c, final boolean withStatus ) throws Exception {
        return WorkRequests.filter( c, withStatus, clampLimit( c ) );
    }

    /** The {@code limit} parameter clamped to {@value #MAX_LIMIT}; zero means the service default. */
    protected int clampLimit( final Call c ) {
        final int limit = parseIntParam( c.req, "limit", 0 );
        return Math.max( 0, Math.min( limit, MAX_LIMIT ) );
    }
}
