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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionFactory;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Base class for all REST/JSON servlets. Provides JSON serialization,
 * CORS headers, error helpers, and engine lookup.
 */
public abstract class RestServletBase extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( RestServletBase.class );

    /** Shared Gson instance with pretty printing and ISO 8601 date format. */
    protected static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" )
            .create();

    private Engine engine;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config );
        LOG.info( "{} initialized", getClass().getSimpleName() );
    }

    /**
     * Returns the wiki Engine instance.
     *
     * @return the Engine
     */
    protected Engine getEngine() {
        return engine;
    }

    /**
     * Allows subclasses (and tests) to override the engine.
     *
     * @param engine the Engine to use
     */
    protected void setEngine( final Engine engine ) {
        this.engine = engine;
    }

    /**
     * Returns whether cross-origin requests are allowed for this servlet.
     * Subclasses (e.g. admin servlets) can override this to return {@code false},
     * which suppresses CORS headers and enforces same-origin policy.
     *
     * @return {@code true} if cross-origin requests should be allowed (default)
     */
    protected boolean isCrossOriginAllowed() {
        return true;
    }

    public static final String PROP_ALLOWED_ORIGINS = "wikantik.cors.allowedOrigins";

    /**
     * Sets CORS headers on the response when {@link #isCrossOriginAllowed()} is true.
     * Echoes the request's {@code Origin} header only when it matches one of the
     * comma-separated values in the {@code wikantik.cors.allowedOrigins} property
     * (exact match, or wildcard with {@code *} — see {@link #originMatches}),
     * and always sets {@code Vary: Origin} so caches don't reuse responses across
     * different origins. Never sets {@code Access-Control-Allow-Credentials}: the
     * wiki's session cookie must not be exposed to arbitrary cross-origin JS.
     *
     * @param request  the HTTP request (used to read the {@code Origin} header)
     * @param response the HTTP response
     */
    protected void setCorsHeaders( final HttpServletRequest request, final HttpServletResponse response ) {
        if ( !isCrossOriginAllowed() ) {
            return;
        }
        response.setHeader( "Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS" );
        response.setHeader( "Access-Control-Allow-Headers", "Content-Type, Authorization" );
        final String origin = request.getHeader( "Origin" );
        if ( origin == null || origin.isEmpty() ) {
            return;
        }
        response.setHeader( "Vary", "Origin" );
        final Engine eng = getEngine();
        final String allowed = ( eng != null )
                ? eng.getWikiProperties().getProperty( PROP_ALLOWED_ORIGINS, "" )
                : "";
        for ( final String candidate : allowed.split( "," ) ) {
            if ( originMatches( origin, candidate.trim() ) ) {
                response.setHeader( "Access-Control-Allow-Origin", origin );
                return;
            }
        }
    }

    /**
     * Matches an {@code Origin} header value against a single whitelist entry.
     * Plain entries match exactly (case-insensitive). Entries containing
     * {@code *} are treated as wildcards: each {@code *} matches one-or-more
     * characters except {@code /}, so {@code https://*.example.com} matches
     * any subdomain depth under {@code example.com} but never the apex, a
     * sibling host like {@code evil-example.com}, a different scheme, or a
     * URL with path-injection trickery.
     */
    static boolean originMatches( final String origin, final String candidate ) {
        if ( candidate.isEmpty() ) {
            return false;
        }
        if ( candidate.indexOf( '*' ) < 0 ) {
            return origin.equalsIgnoreCase( candidate );
        }
        final StringBuilder regex = new StringBuilder();
        int from = 0;
        for ( int star = candidate.indexOf( '*' ); star >= 0; star = candidate.indexOf( '*', from ) ) {
            regex.append( java.util.regex.Pattern.quote( candidate.substring( from, star ) ) );
            regex.append( "[^/]+" );
            from = star + 1;
        }
        regex.append( java.util.regex.Pattern.quote( candidate.substring( from ) ) );
        return java.util.regex.Pattern.compile( regex.toString(),
                java.util.regex.Pattern.CASE_INSENSITIVE ).matcher( origin ).matches();
    }

    /**
     * Applies CORS headers once per request, before dispatching to
     * {@code doGet/doPost/...}. This keeps existing {@code sendJson}/
     * {@code sendError} callers unchanged — they don't need to know
     * about the request to emit a correct {@code Access-Control-Allow-Origin}
     * header.
     */
    @Override
    protected void service( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        setCorsHeaders( request, response );
        super.service( request, response );
    }

    /**
     * Handles CORS preflight requests. (CORS headers are already applied by
     * {@link #service(HttpServletRequest, HttpServletResponse)}.)
     */
    @Override
    protected void doOptions( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        response.setStatus( HttpServletResponse.SC_OK );
    }

    /**
     * Serializes an object to JSON and writes it to the response.
     *
     * @param response the HTTP response
     * @param object   the object to serialize
     * @throws IOException if writing fails
     */
    protected void sendJson( final HttpServletResponse response, final Object object ) throws IOException {
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( GSON.toJson( object ) );
    }

    /**
     * Sends a JSON error response.
     *
     * @param response the HTTP response
     * @param status   the HTTP status code
     * @param message  the error message
     * @throws IOException if writing fails
     */
    protected void sendError( final HttpServletResponse response, final int status, final String message )
            throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( GSON.toJson( Map.of(
                "error", true,
                "status", status,
                "message", message
        ) ) );
    }

    /**
     * Convenience for sending a 404 Not Found error.
     *
     * @param response the HTTP response
     * @param message  the error message
     * @throws IOException if writing fails
     */
    protected void sendNotFound( final HttpServletResponse response, final String message ) throws IOException {
        sendError( response, HttpServletResponse.SC_NOT_FOUND, message );
    }

    /**
     * Extracts the page name from the path info (e.g., "/PageName" from "/api/pages/PageName").
     *
     * @param request the HTTP request
     * @return the page name, or null if no path info
     */
    protected String extractPathParam( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.length() <= 1 ) {
            return null;
        }
        // Strip leading slash
        return pathInfo.substring( 1 );
    }

    /**
     * Like {@link #extractPathParam} but sends a 400 error and returns {@code null}
     * if the path is absent or empty. Handlers use this when the path segment is
     * mandatory, to collapse the usual four-line boilerplate into one.
     */
    protected String requirePathParam( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String value = extractPathParam( request );
        if ( value == null || value.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return null;
        }
        return value;
    }

    /**
     * Loads a page by name and sends a 404 response if it doesn't exist. Returns
     * the {@link Page} or {@code null} if the error response was sent. Callers
     * typically pair this with {@link #requirePathParam} and
     * {@link #checkPagePermission}.
     */
    protected Page requirePage( final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String pageName ) throws IOException {
        final Page page = getEngine().getManager( PageManager.class ).getPage( pageName );
        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return null;
        }
        return page;
    }

    /**
     * Checks whether the current user has the specified permission for the given page.
     * If the page exists, its ACL is evaluated via {@link PermissionFactory#getPagePermission(Page, String)}.
     * If the user lacks permission, a 403 JSON error is sent and this method returns {@code false}.
     *
     * @param request  the HTTP request (used to resolve the user's session)
     * @param response the HTTP response (used to send 403 if denied)
     * @param pageName the wiki page name
     * @param action   the permission action (e.g., "view", "edit", "delete")
     * @return {@code true} if the user is authorized; {@code false} if a 403 was sent
     * @throws IOException if writing the error response fails
     */
    /**
     * Returns {@code true} if the current user has the specified permission for the given page.
     * Unlike {@link #checkPagePermission}, this method does not send a 403 — it only queries.
     */
    protected boolean hasPagePermission( final HttpServletRequest request,
                                          final String pageName,
                                          final String action ) {
        final Engine eng = getEngine();
        final Session session = Wiki.session().find( eng, request );
        final Page page = eng.getManager( PageManager.class ).getPage( pageName );
        final java.security.Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, action )
                : new PagePermission( eng.getApplicationName() + ":" + pageName, action );
        return eng.getManager( AuthorizationManager.class ).checkPermission( session, perm );
    }

    protected boolean checkPagePermission( final HttpServletRequest request,
                                            final HttpServletResponse response,
                                            final String pageName,
                                            final String action ) throws IOException {
        final Engine eng = getEngine();
        final Session session = Wiki.session().find( eng, request );
        final Page page = eng.getManager( PageManager.class ).getPage( pageName );
        // When the page exists, use the Page overload so ACLs embedded in the content
        // are evaluated.  When it doesn't exist yet (e.g. a PUT creating a new page),
        // construct the permission with the wiki's application name so that the
        // policy grant "wiki:*" still matches.
        final java.security.Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, action )
                : new PagePermission( eng.getApplicationName() + ":" + pageName, action );
        final AuthorizationManager authMgr = eng.getManager( AuthorizationManager.class );
        if ( !authMgr.checkPermission( session, perm ) ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN, "Forbidden" );
            return false;
        }
        return true;
    }

    /**
     * Reads and parses the JSON body from the request as a {@link JsonObject}.
     *
     * @param request the HTTP request
     * @return the parsed JSON object
     * @throws IOException if reading or parsing fails
     */
    protected JsonObject readJsonBody( final HttpServletRequest request ) throws IOException {
        try ( final BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        }
    }

    /**
     * Parses the JSON body from the request. On parse failure, sends a 400 error
     * (including the parser exception message so clients can debug malformed
     * payloads) and returns {@code null}.
     *
     * @param request  the HTTP request
     * @param response the HTTP response (used to send 400 on failure)
     * @return the parsed {@link JsonObject}, or {@code null} if parsing failed
     * @throws IOException if writing the error response fails
     */
    protected JsonObject parseJsonBody( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        try ( final BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return null;
        }
    }

    /**
     * Returns a string value from a {@link JsonObject}, or {@code null} if the
     * key is absent or the value is JSON null.
     */
    protected String getJsonString( final JsonObject obj, final String key ) {
        if ( obj.has( key ) && !obj.get( key ).isJsonNull() ) {
            return obj.get( key ).getAsString();
        }
        return null;
    }

    /**
     * Formats a {@link Date} as an ISO 8601 string, or returns {@code null}
     * if the date is null.
     */
    protected String formatDate( final Date date ) {
        if ( date == null ) return null;
        return new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" ).format( date );
    }

    /**
     * Parses an integer request parameter, returning a default if the parameter
     * is absent or not a valid integer.
     *
     * @param request      the HTTP request
     * @param paramName    the parameter name
     * @param defaultValue the value to return if the parameter is missing or invalid
     * @return the parsed integer or the default
     */
    protected int parseIntParam( final HttpServletRequest request, final String paramName, final int defaultValue ) {
        final String value = request.getParameter( paramName );
        if ( value == null ) {
            return defaultValue;
        }
        try {
            return Integer.parseInt( value );
        } catch ( final NumberFormatException e ) {
            return defaultValue;
        }
    }

}
