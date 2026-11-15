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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.work.Actor;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Product;
import com.wikantik.work.Project;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import com.wikantik.work.runtime.WikiWorkActors;
import com.wikantik.work.runtime.WikiWorkDirectory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final Set< String > NAME_KEYS = Set.of( "assignee", "reporter", "author", "actor", "editedBy" );

    /** Serialises enums by their {@code wire()} value (or lower-cased name), dates and instants as ISO strings. */
    private static final class WireEnumFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings( { "unchecked", "rawtypes" } )
        public < T > TypeAdapter< T > create( final Gson gson, final TypeToken< T > type ) {
            final Class< ? super T > raw = type.getRawType();
            if ( !Enum.class.isAssignableFrom( raw ) ) {
                return null;
            }
            final Class< ? > enumType = raw.isEnum() ? raw : raw.getSuperclass();
            Method wire = null;
            try {
                wire = enumType.getMethod( "wire" );
            } catch ( final NoSuchMethodException e ) {
                LOG.debug( "{} has no wire(); using its lower-cased name", enumType.getName() );
            }
            final Method wireMethod = wire;
            return new TypeAdapter< T >() {
                @Override
                public void write( final JsonWriter out, final T value ) throws IOException {
                    if ( value == null ) {
                        out.nullValue();
                        return;
                    }
                    try {
                        out.value( wireMethod != null ? String.valueOf( wireMethod.invoke( value ) )
                            : ( ( Enum ) value ).name().toLowerCase( Locale.ROOT ) );
                    } catch ( final ReflectiveOperationException e ) {
                        throw new IOException( "Cannot serialise " + enumType.getName(), e );
                    }
                }

                @Override
                public T read( final JsonReader in ) {
                    throw new UnsupportedOperationException( "Work enums are parsed by hand, not by Gson." );
                }
            };
        }
    }

    private static final Gson WORK_GSON = new GsonBuilder()
        .serializeNulls()
        .registerTypeAdapterFactory( new WireEnumFactory() )
        .registerTypeAdapter( Instant.class, ( com.google.gson.JsonSerializer< Instant > )
            ( src, t, ctx ) -> new JsonPrimitive( src.toString() ) )
        .registerTypeAdapter( LocalDate.class, ( com.google.gson.JsonSerializer< LocalDate > )
            ( src, t, ctx ) -> new JsonPrimitive( src.toString() ) )
        .create();

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
            write( resp, HttpServletResponse.SC_OK, toTree( value ) );
        }

        void tree( final JsonElement tree ) throws IOException {
            write( resp, HttpServletResponse.SC_OK, tree );
        }

        void created( final Object value ) throws IOException {
            write( resp, HttpServletResponse.SC_CREATED, toTree( value ) );
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

    private record Route( String method, String[] pattern, Handler handler ) {
    }

    private transient volatile List< Route > routeTable;

    /** Adds a route; {@code pattern} is a path under {@code /api/work/} with {@code {}} for a captured segment. */
    protected static void route( final List< Object[] > into, final String method, final String pattern,
                                 final Handler handler ) {
        into.add( new Object[] { method, pattern, handler } );
    }

    /** The routes of this servlet: call {@link #route} for each. */
    protected abstract void routes( List< Object[] > into );

    private List< Route > table() {
        List< Route > t = routeTable;
        if ( t == null ) {
            final List< Object[] > raw = new ArrayList<>();
            routes( raw );
            t = new ArrayList<>();
            for ( final Object[] r : raw ) {
                final String pattern = ( String ) r[ 1 ];
                t.add( new Route( ( String ) r[ 0 ], pattern.isEmpty() ? new String[ 0 ] : pattern.split( "/" ),
                    ( Handler ) r[ 2 ] ) );
            }
            routeTable = t;
        }
        return t;
    }

    private static String[] segments( final HttpServletRequest request ) {
        final String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        final String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = servletPath + pathInfo;
        final String prefix = "/api/work";
        if ( path.startsWith( prefix ) ) {
            path = path.substring( prefix.length() );
        }
        final List< String > out = new ArrayList<>();
        for ( final String s : path.split( "/" ) ) {
            if ( !s.isEmpty() ) {
                out.add( s );
            }
        }
        return out.toArray( new String[ 0 ] );
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
        final String[] segs = segments( request );
        try {
            for ( final Route r : table() ) {
                final List< String > captured = match( r, method, segs );
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

    private static List< String > match( final Route r, final String method, final String[] segs ) {
        if ( !r.method().equals( method ) || r.pattern().length != segs.length ) {
            return null;
        }
        final List< String > captured = new ArrayList<>();
        for ( int i = 0; i < segs.length; i++ ) {
            if ( "{}".equals( r.pattern()[ i ] ) ) {
                captured.add( segs[ i ] );
            } else if ( !r.pattern()[ i ].equals( segs[ i ] ) ) {
                return null;
            }
        }
        return captured;
    }

    // ---- error bodies

    private void sendValidation( final HttpServletResponse response, final WorkValidationException e )
            throws IOException {
        final JsonObject body = errorBody( HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        body.addProperty( "field", e.field() );
        write( response, HttpServletResponse.SC_BAD_REQUEST, body );
    }

    /** A stale version: 409 with the current item when the request addressed one. */
    protected void conflict( final HttpServletRequest request, final HttpServletResponse response,
                             final WorkService svc, final Actor actor, final String[] segs,
                             final WorkConflictException e ) throws IOException {
        final JsonObject body = errorBody( HttpServletResponse.SC_CONFLICT, e.getMessage() );
        if ( segs.length == 2 && "items".equals( segs[ 0 ] ) && "PATCH".equalsIgnoreCase( request.getMethod() ) ) {
            try {
                body.add( "current", toTree( svc.item( actor, segs[ 1 ] ) ) );
            } catch ( final Exception inner ) {
                LOG.warn( "Could not load the current item after a conflict on {}: {}", segs[ 1 ],
                    inner.getMessage(), inner );
            }
        }
        write( response, HttpServletResponse.SC_CONFLICT, body );
    }

    private static JsonObject errorBody( final int status, final String message ) {
        final JsonObject body = new JsonObject();
        body.addProperty( "error", true );
        body.addProperty( "status", status );
        body.addProperty( "message", message );
        return body;
    }

    private static void write( final HttpServletResponse response, final int status, final JsonElement tree )
            throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( WORK_GSON.toJson( tree ) );
    }

    // ---- JSON with display names

    /** The value as a JSON tree with a display name beside every login. */
    protected JsonElement toTree( final Object value ) {
        final Map< String, String > cache = new HashMap<>();
        final Function< String, String > names = login -> cache.computeIfAbsent( login, this::displayName );
        return enrich( WORK_GSON.toJsonTree( value ), names );
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
        final JsonObject o = WORK_GSON.toJsonTree( project ).getAsJsonObject();
        o.addProperty( "role", role );
        return o;
    }

    private static JsonElement enrich( final JsonElement e, final Function< String, String > names ) {
        if ( e.isJsonArray() ) {
            for ( final JsonElement child : e.getAsJsonArray() ) {
                enrich( child, names );
            }
        } else if ( e.isJsonObject() ) {
            final JsonObject o = e.getAsJsonObject();
            final Map< String, JsonElement > additions = new LinkedHashMap<>();
            for ( final Map.Entry< String, JsonElement > entry : o.entrySet() ) {
                final JsonElement v = entry.getValue();
                if ( NAME_KEYS.contains( entry.getKey() ) && v.isJsonPrimitive() ) {
                    additions.put( entry.getKey() + "Name", new JsonPrimitive( names.apply( v.getAsString() ) ) );
                }
                enrich( v, names );
            }
            addMemberAndEventNames( o, additions, names );
            additions.forEach( o::add );
        }
        return e;
    }

    private static void addMemberAndEventNames( final JsonObject o, final Map< String, JsonElement > add,
                                                final Function< String, String > names ) {
        final String principal = str( o, "principal" );
        if ( principal != null && o.has( "principalKind" ) ) {
            add.put( "displayName", new JsonPrimitive( "user".equals( str( o, "principalKind" ) )
                ? names.apply( principal ) : principal ) );
        }
        final String type = str( o, "type" );
        if ( "assigned".equals( type ) || "handoff".equals( type ) ) {
            for ( final String side : new String[] { "fromValue", "toValue" } ) {
                String login = str( o, side );
                if ( login != null && "handoff".equals( type ) ) {
                    final int bar = login.indexOf( '|' );
                    login = bar < 0 ? null : login.substring( bar + 1 );
                }
                if ( login != null && !login.isEmpty() ) {
                    add.put( side + "Name", new JsonPrimitive( names.apply( login ) ) );
                }
            }
        }
    }

    private static String str( final JsonObject o, final String key ) {
        final JsonElement v = o.get( key );
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
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
        final String v = optString( body, field );
        if ( v == null || v.isBlank() ) {
            throw new WorkValidationException( field, field + " is required." );
        }
        return v;
    }

    /** A string field, null when absent or JSON null; a non-string value is a 400. */
    protected static String optString( final JsonObject body, final String field ) {
        final JsonElement v = body.get( field );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() ) {
            throw new WorkValidationException( field, field + " must be text." );
        }
        return v.getAsString();
    }

    /** Converts one JSON value of a PATCH {@code changes} object to a plain String, Number, Boolean or null. */
    protected static Object plainValue( final String field, final JsonElement v ) {
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() ) {
            throw new WorkValidationException( field, field + " must be a plain value." );
        }
        final JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isBoolean() ? ( Object ) p.getAsBoolean() : p.isNumber() ? ( Object ) p.getAsNumber() : p.getAsString();
    }

    /** The list-query filter; {@code withStatus} reads the {@code status} parameter too. */
    protected ItemFilter filter( final Call c, final boolean withStatus ) throws Exception {
        Set< WorkStatus > statuses = null;
        if ( withStatus && c.query( "status" ) != null ) {
            statuses = EnumSet.noneOf( WorkStatus.class );
            for ( final String s : c.query( "status" ).split( "," ) ) {
                statuses.add( WorkStatus.fromWire( s.trim() ) );
            }
        }
        Long productId = null;
        if ( c.query( "product" ) != null ) {
            productId = -1L;
            for ( final Product p : c.svc.listProducts( c.actor ) ) {
                if ( p.key().equals( c.query( "product" ) ) ) {
                    productId = p.id();
                }
            }
        }
        final WorkLevel level = c.query( "level" ) == null ? null : WorkLevel.fromWire( c.query( "level" ) );
        final Long parentId = c.query( "parent" ) == null ? null : c.svc.item( c.actor, c.query( "parent" ) ).item().id();
        LocalDate dueBefore = null;
        if ( c.query( "dueBefore" ) != null ) {
            try {
                dueBefore = LocalDate.parse( c.query( "dueBefore" ) );
            } catch ( final DateTimeParseException e ) {
                throw new WorkValidationException( "dueBefore", "dueBefore must be a date such as 2026-12-31." );
            }
        }
        return new ItemFilter( null, statuses, c.query( "assignee" ), productId, level, parentId, c.query( "q" ),
            dueBefore, clampLimit( c ) );
    }

    /** The {@code limit} parameter clamped to {@value #MAX_LIMIT}; zero means the service default. */
    protected int clampLimit( final Call c ) {
        final int limit = parseIntParam( c.req, "limit", 0 );
        return Math.max( 0, Math.min( limit, MAX_LIMIT ) );
    }
}
