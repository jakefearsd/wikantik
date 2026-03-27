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

import java.io.IOException;
import java.util.Map;

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
     * Sets CORS headers on the response.
     *
     * @param response the HTTP response
     */
    protected void setCorsHeaders( final HttpServletResponse response ) {
        response.setHeader( "Access-Control-Allow-Origin", "*" );
        response.setHeader( "Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS" );
        response.setHeader( "Access-Control-Allow-Headers", "Content-Type, Authorization" );
    }

    /**
     * Handles CORS preflight requests.
     */
    @Override
    protected void doOptions( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        setCorsHeaders( response );
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
        setCorsHeaders( response );
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
        setCorsHeaders( response );
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

}
