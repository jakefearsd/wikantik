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
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

/**
 * Static JSON/request helper bodies shared by {@link RestServletBase} (whose {@code protected}
 * instance methods delegate here) and by servlet-adjacent helper classes in sub-packages — e.g.
 * {@code com.wikantik.rest.knowledge.AdminKnowledgeIo} — that cannot reach {@code protected}
 * members declared on {@code RestServletBase} because they neither share its package nor extend
 * it. Before this class existed, that second call site kept its own hand-maintained "verbatim
 * copy" of each method body; the two implementations could silently drift apart. Extracting the
 * logic here gives both call sites exactly one implementation.
 * <p>
 * Uses {@link RestServletBase#GSON} directly (this class lives in the same package, so the
 * {@code protected} field is visible) rather than constructing a second {@code Gson} instance —
 * this guarantees byte-identical JSON serialization at both call sites.
 */
public final class RestJson {

    private static final Logger LOG = LogManager.getLogger( RestJson.class );

    private RestJson() {
    }

    /**
     * Serializes an object to JSON and writes it to the response.
     *
     * @param response the HTTP response
     * @param object   the object to serialize
     * @throws IOException if writing fails
     */
    public static void sendJson( final HttpServletResponse response, final Object object ) throws IOException {
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( RestServletBase.GSON.toJson( object ) );
    }

    /**
     * Sends a JSON error response.
     *
     * @param response the HTTP response
     * @param status   the HTTP status code
     * @param message  the error message
     * @throws IOException if writing fails
     */
    public static void sendError( final HttpServletResponse response, final int status, final String message )
            throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( RestServletBase.GSON.toJson( Map.of(
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
    public static void sendNotFound( final HttpServletResponse response, final String message ) throws IOException {
        sendError( response, HttpServletResponse.SC_NOT_FOUND, message );
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
    public static JsonObject parseJsonBody( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        try ( BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            // D23: do not echo the raw GSON parser message — it can include URLs to the
            // GSON troubleshooting docs and class names that leak the parser library to
            // API consumers. Log the full cause server-side and return a sanitized
            // human-readable summary.
            LOG.warn( "Rejecting malformed JSON body for {}: {}", request.getRequestURI(), e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid JSON body: " + sanitizeParseError( e.getMessage() ) );
            return null;
        }
    }

    /**
     * D23: returns a short, library-neutral description of the parser failure. Strips
     * any URLs (e.g. links to GSON's documentation) and Java class names, so the
     * client never sees that we use GSON.
     */
    public static String sanitizeParseError( final String raw ) {
        if ( raw == null || raw.isEmpty() ) {
            return "could not parse body as JSON object";
        }
        // Drop URL fragments
        String s = raw.replaceAll( "https?://\\S+", "" );
        // Drop fully-qualified class names (com.google.gson.x.y) and com/google/gson references
        s = s.replaceAll( "(?i)gson", "" );
        s = s.replaceAll( "[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_.]*Exception", "parse error" );
        // Trim trailing whitespace, collapse runs
        s = s.replaceAll( "\\s+", " " ).trim();
        if ( s.isEmpty() ) {
            return "could not parse body as JSON object";
        }
        return s;
    }

    /**
     * Returns a string value from a {@link JsonObject}, or {@code null} if the
     * key is absent, JSON null, or a non-primitive value (object/array). Guarding
     * on {@code isJsonPrimitive} (rather than only {@code !isJsonNull}) keeps
     * {@code getAsString()} from throwing {@code UnsupportedOperationException} on
     * a JSON object/array — which otherwise surfaces as a 500 instead of a 400.
     */
    public static String getJsonString( final JsonObject obj, final String key ) {
        if ( obj.has( key ) && obj.get( key ).isJsonPrimitive() ) {
            return obj.get( key ).getAsString();
        }
        return null;
    }

    /**
     * Returns an int from a {@link JsonObject}, or {@code def} if the key is
     * absent, JSON null, or not a parseable number. Never throws on malformed
     * input (a non-numeric or non-primitive value yields {@code def} rather than
     * a 500).
     */
    public static int getJsonInt( final JsonObject obj, final String key, final int def ) {
        if ( obj.has( key ) && obj.get( key ).isJsonPrimitive() ) {
            try {
                return obj.get( key ).getAsInt();
            } catch ( final NumberFormatException e ) {
                return def;
            }
        }
        return def;
    }

    /**
     * Returns a double from a {@link JsonObject}, or {@code def} if the key is
     * absent, JSON null, or not a parseable number. Never throws on malformed input.
     */
    public static double getJsonDouble( final JsonObject obj, final String key, final double def ) {
        if ( obj.has( key ) && obj.get( key ).isJsonPrimitive() ) {
            try {
                return obj.get( key ).getAsDouble();
            } catch ( final NumberFormatException e ) {
                return def;
            }
        }
        return def;
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
    public static int parseIntParam( final HttpServletRequest request, final String paramName, final int defaultValue ) {
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
