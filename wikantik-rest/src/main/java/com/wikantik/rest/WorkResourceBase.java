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
import java.util.function.Function;

/**
 * Shared behaviour of the {@code /api/work/*} servlets: the feature flag (404 when work management is off), actor
 * resolution (401 when anonymous), method-and-path routing from a small table, the one mapping from the
 * {@code com.wikantik.work} exceptions to HTTP, and the one JSON configuration.
 *
 * <p><b>Names on the wire.</b> Responses are only ever produced for project members (everyone else gets the same
 * neutral 404), so they carry the stored login next to a display name: {@code assignee} next to
 * {@code assigneeName}, and so on. The UI needs the login to send assignments back.
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

    /** The calling actor, or null for an anonymous session. */
    protected Actor actor( final HttpServletRequest request ) {
        final Session session = Wiki.session().find( getEngine(), request );
        return WikiWorkActors.actorFor( getEngine(), session );
    }

    /** The public display name for a stored login. */
    protected String displayName( final String login ) {
        return new WikiWorkDirectory( getEngine() ).displayName( login );
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

        void json( final Object value ) throws IOException {
            WorkJson.write( resp, HttpServletResponse.SC_OK, toTree( value ) );
        }

        void tree( final JsonElement tree ) throws IOException {
            WorkJson.write( resp, HttpServletResponse.SC_OK, tree );
        }

        void created( final Object value ) throws IOException {
            WorkJson.write( resp, HttpServletResponse.SC_CREATED, toTree( value ) );
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
        final Actor actor = actor( request );
        if ( actor == null ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required." );
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
            sendValidation( response, e );
        } catch ( final WorkConflictException e ) {
            conflict( request, response, svc, actor, segs, e );
        } catch ( final Exception e ) {
            LOG.warn( "Work API {} {} failed: {}", method, String.join( "/", segs ), e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error." );
        }
    }

    // ---- error bodies

    private void sendValidation( final HttpServletResponse response, final WorkValidationException e )
            throws IOException {
        final JsonObject body = WorkJson.errorBody( HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        body.addProperty( "field", e.field() );
        WorkJson.write( response, HttpServletResponse.SC_BAD_REQUEST, body );
    }

    /** A stale version: 409 with the current item when the request addressed one. */
    protected void conflict( final HttpServletRequest request, final HttpServletResponse response,
                             final WorkService svc, final Actor actor, final String[] segs,
                             final WorkConflictException e ) throws IOException {
        final JsonObject body = WorkJson.errorBody( HttpServletResponse.SC_CONFLICT, e.getMessage() );
        if ( segs.length == 2 && "items".equals( segs[ 0 ] ) && "PATCH".equalsIgnoreCase( request.getMethod() ) ) {
            try {
                body.add( "current", toTree( svc.item( actor, segs[ 1 ] ) ) );
            } catch ( final Exception inner ) {
                LOG.warn( "Could not load the current item after a conflict on {}: {}", segs[ 1 ],
                    inner.getMessage(), inner );
            }
        }
        WorkJson.write( response, HttpServletResponse.SC_CONFLICT, body );
    }

    // ---- JSON with display names

    /** The value as a JSON tree with a display name beside every login. */
    protected JsonElement toTree( final Object value ) {
        final Map< String, String > cache = new HashMap<>();
        final Function< String, String > names = login -> cache.computeIfAbsent( login, this::displayName );
        return WorkJson.enrich( WorkJson.GSON.toJsonTree( value ), names );
    }

    /** An item list with each item's card extras (commentCount, blocked, productKey, parentKey) beside its fields. */
    protected JsonElement summariesTree( final List< WorkService.ItemSummary > summaries ) {
        final JsonArray out = new JsonArray();
        summaries.forEach( x -> out.add( summaryTree( x ) ) );
        return out;
    }

    /** Board columns {status, total, items} whose items carry the card extras. */
    protected JsonElement boardTree( final List< WorkService.SummaryColumn > columns ) {
        final JsonArray out = new JsonArray();
        for ( final WorkService.SummaryColumn c : columns ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "status", c.status().wire() );
            o.addProperty( "total", c.total() );
            o.add( "items", summariesTree( c.items() ) );
            out.add( o );
        }
        return out;
    }

    private JsonObject summaryTree( final WorkService.ItemSummary x ) {
        final JsonObject o = toTree( x.item() ).getAsJsonObject();
        o.addProperty( "commentCount", x.commentCount() );
        o.addProperty( "blocked", x.blocked() );
        o.addProperty( "productKey", x.productKey() );
        o.addProperty( "parentKey", x.parentKey() );
        return o;
    }

    /** A project's JSON with the viewer's {@code role} added. */
    protected JsonObject projectWithRole( final Project project, final String role ) {
        final JsonObject o = WorkJson.GSON.toJsonTree( project ).getAsJsonObject();
        o.addProperty( "role", role );
        return o;
    }

    /** Every project the caller can see, each with the caller's role. */
    protected JsonArray visibleProjects( final Call c ) throws Exception {
        final JsonArray out = new JsonArray();
        for ( final Project p : c.svc.listProjects( c.actor ) ) {
            try {
                out.add( projectWithRole( p, c.svc.roleIn( c.actor, p.key() ).wire() ) );
            } catch ( final WorkNotFoundException e ) {
                LOG.debug( "A project became invisible while listing; leaving it out." );
            }
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
