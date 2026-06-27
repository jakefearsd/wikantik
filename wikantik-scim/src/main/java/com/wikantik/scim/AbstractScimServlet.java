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
package com.wikantik.scim;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Reader;

/**
 * Common base for the SCIM resource servlets ({@link ScimUserResource},
 * {@link ScimGroupResource}). Holds the {@code application/scim+json} content
 * type, a shared Gson instance, and the request-parsing / response-writing
 * helpers that both resources previously carried as identical private copies.
 *
 * <p>Also provides Template Method implementations of the five HTTP verbs
 * (POST, GET, PUT, PATCH via {@code service()}, DELETE) that handle id/name
 * extraction and missing-identifier 400 errors uniformly. Subclasses implement
 * the abstract hooks to supply the identifier label, extraction logic, and the
 * actual per-verb business logic.</p>
 */
abstract class AbstractScimServlet extends HttpServlet {

    protected static final String CONTENT_TYPE = "application/scim+json";
    protected static final Gson GSON = new Gson();

    // -------------------------------------------------------------------------
    // Abstract hooks — subclasses supply identity + handlers
    // -------------------------------------------------------------------------

    /** Returns the human-readable label used in 400 error messages, e.g. {@code "user id"} or {@code "group name"}. */
    protected abstract String identifierLabel();

    /**
     * Extracts the resource identifier from the request path-info.
     * Returns {@code null} when the request targets the collection endpoint.
     */
    protected abstract String extractIdentifier( HttpServletRequest req );

    protected abstract void handleCreate( HttpServletRequest req, HttpServletResponse resp )
            throws IOException;

    protected abstract void handleGet( String id, HttpServletRequest req, HttpServletResponse resp )
            throws IOException;

    protected abstract void handleList( HttpServletRequest req, HttpServletResponse resp )
            throws IOException;

    protected abstract void handleReplace( String id, HttpServletRequest req, HttpServletResponse resp )
            throws IOException;

    protected abstract void handlePatch( String id, HttpServletRequest req, HttpServletResponse resp )
            throws IOException;

    protected abstract void handleDelete( String id, HttpServletResponse resp )
            throws IOException;

    // -------------------------------------------------------------------------
    // Template Method dispatch — final; subclasses must not override these
    // -------------------------------------------------------------------------

    @Override
    protected final void doPost( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        handleCreate( req, resp );
    }

    @Override
    protected final void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String id = extractIdentifier( req );
        if ( id != null ) {
            handleGet( id, req, resp );
        } else {
            handleList( req, resp );
        }
    }

    @Override
    protected final void doPut( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String id = extractIdentifier( req );
        if ( id == null ) {
            sendError( resp, 400, null, "PUT requires a " + identifierLabel() + " in the path" );
            return;
        }
        handleReplace( id, req, resp );
    }

    /** Dispatches PATCH (not a standard HttpServlet override — must override service). */
    @Override
    protected final void service( final HttpServletRequest req, final HttpServletResponse resp )
            throws ServletException, IOException {
        if ( "PATCH".equalsIgnoreCase( req.getMethod() ) ) {
            final String id = extractIdentifier( req );
            if ( id == null ) {
                sendError( resp, 400, null, "PATCH requires a " + identifierLabel() + " in the path" );
                return;
            }
            handlePatch( id, req, resp );
        } else {
            super.service( req, resp );
        }
    }

    @Override
    protected final void doDelete( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String id = extractIdentifier( req );
        if ( id == null ) {
            sendError( resp, 400, null, "DELETE requires a " + identifierLabel() + " in the path" );
            return;
        }
        handleDelete( id, resp );
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Parses a positive int query parameter, falling back to {@code defaultVal}. */
    protected static int parseIntParam( final HttpServletRequest req, final String name,
                                        final int defaultVal ) {
        final String v = req.getParameter( name );
        if ( v == null ) return defaultVal;
        try {
            final int i = Integer.parseInt( v.trim() );
            return i > 0 ? i : defaultVal;
        } catch ( final NumberFormatException e ) {
            return defaultVal;
        }
    }

    /**
     * Reads and parses the request body as a JSON object. On malformed JSON,
     * writes a SCIM {@code invalidSyntax} 400 error and returns {@code null}.
     */
    protected static JsonObject parseBody( final HttpServletRequest req,
                                           final HttpServletResponse resp ) throws IOException {
        try ( final Reader r = req.getReader() ) {
            return JsonParser.parseReader( r ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( resp, 400, "invalidSyntax", "Could not parse JSON body: " + e.getMessage() );
            return null;
        }
    }

    /** Writes a SCIM JSON body with the {@code application/scim+json} content type. */
    protected static void sendScim( final HttpServletResponse resp, final JsonObject body )
            throws IOException {
        resp.setContentType( CONTENT_TYPE );
        resp.getWriter().write( GSON.toJson( body ) );
    }

    /** Writes a SCIM error response (status + scimType + detail). */
    protected static void sendError( final HttpServletResponse resp, final int status,
                                     final String scimType, final String detail ) throws IOException {
        resp.setStatus( status );
        resp.setContentType( CONTENT_TYPE );
        resp.getWriter().write( GSON.toJson( ScimError.body( status, scimType, detail ) ) );
    }
}
