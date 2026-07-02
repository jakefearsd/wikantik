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
package com.wikantik.rest.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import com.wikantik.rest.RestServletBase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Small, dependency-free JSON/request helpers shared by the {@code com.wikantik.rest.knowledge}
 * admin handler classes.
 * <p>
 * {@link com.wikantik.rest.RestServletBase} already implements equivalent logic
 * ({@code sendJson}, {@code sendError}, {@code sendNotFound}, {@code parseJsonBody},
 * {@code getJsonString}, {@code getJsonDouble}, {@code parseIntParam}), but those members are
 * {@code protected} and only reachable from {@code com.wikantik.rest} (same package or a
 * subclass). The handler classes in this package are neither, so their bodies — moved verbatim
 * out of {@code AdminKnowledgeResource} — need an equivalent they can actually call. Rather than
 * thread a narrow callback interface through every handler constructor, this class duplicates
 * the handful of small, pure, side-effect-free bodies those handlers need. This is the least
 * invasive option: it changes nothing in {@code RestServletBase} (used by every other REST
 * servlet) and nothing in {@code AdminKnowledgeResource}'s ~40 call sites that stay on the
 * inherited versions.
 * <p>
 * {@code parseUuid} is the exception: it is genuinely local to {@code AdminKnowledgeResource}
 * (not inherited) and is used by both the extracted handlers and the handlers that remain, so it
 * moved here verbatim and {@code AdminKnowledgeResource} now delegates to it.
 */
public final class AdminKnowledgeIo {

    private static final Logger LOG = LogManager.getLogger( AdminKnowledgeIo.class );

    /** Shared Gson instance — same configuration as {@link RestServletBase#GSON}. */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter( Date.class, RestServletBase.UTC_ISO_DATE_SERIALIZER )
            .create();

    /** Same shape as the {@code Map<String, Object>} type token used throughout AdminKnowledgeResource. */
    public static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    private AdminKnowledgeIo() {
    }

    /** Verbatim copy of {@code AdminKnowledgeResource#parseUuid} — genuinely local helper, shared
     *  across handlers that stayed on the resource and handlers that moved out of it. */
    public static UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( str );
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "Rejecting request with malformed UUID '{}': {}", str, e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID: " + str );
            return null;
        }
    }

    /** Verbatim copy of {@link RestServletBase#sendJson}. */
    public static void sendJson( final HttpServletResponse response, final Object object ) throws IOException {
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( GSON.toJson( object ) );
    }

    /** Verbatim copy of {@link RestServletBase#sendError}. */
    public static void sendError( final HttpServletResponse response, final int status, final String message )
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

    /** Verbatim copy of {@link RestServletBase#sendNotFound}. */
    public static void sendNotFound( final HttpServletResponse response, final String message ) throws IOException {
        sendError( response, HttpServletResponse.SC_NOT_FOUND, message );
    }

    /** Verbatim copy of {@link RestServletBase#parseJsonBody}. */
    public static JsonObject parseJsonBody( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        try ( BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            LOG.warn( "Rejecting malformed JSON body for {}: {}", request.getRequestURI(), e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid JSON body: " + sanitizeParseError( e.getMessage() ) );
            return null;
        }
    }

    /** Verbatim copy of {@code RestServletBase#sanitizeParseError}. */
    private static String sanitizeParseError( final String raw ) {
        if ( raw == null || raw.isEmpty() ) {
            return "could not parse body as JSON object";
        }
        String s = raw.replaceAll( "https?://\\S+", "" );
        s = s.replaceAll( "(?i)gson", "" );
        s = s.replaceAll( "[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_.]*Exception", "parse error" );
        s = s.replaceAll( "\\s+", " " ).trim();
        if ( s.isEmpty() ) {
            return "could not parse body as JSON object";
        }
        return s;
    }

    /** Verbatim copy of {@link RestServletBase#getJsonString}. */
    public static String getJsonString( final JsonObject obj, final String key ) {
        if ( obj.has( key ) && obj.get( key ).isJsonPrimitive() ) {
            return obj.get( key ).getAsString();
        }
        return null;
    }

    /** Verbatim copy of {@link RestServletBase#getJsonDouble}. */
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

    /** Verbatim copy of {@link RestServletBase#parseIntParam}. */
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
