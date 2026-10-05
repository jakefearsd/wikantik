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
package com.wikantik.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

/**
 * Request-parsing and JSON response-writing helpers for {@link ToolsOpenApiServlet}.
 */
final class ToolsHttp {

    private static final Logger LOG = LogManager.getLogger( ToolsHttp.class );
    private static final Gson GSON = new Gson();

    private ToolsHttp() {
    }

    static int parseIntParam( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return 0;
        }
        try {
            return Integer.parseInt( raw.strip() );
        } catch ( final NumberFormatException e ) {
            // Client-supplied parameter; falling back to the default is the documented behaviour.
            LOG.debug( "'{}' is not an integer; using 0: {}", raw, e.getMessage() );
            return 0;
        }
    }

    static JsonObject readJsonBody( final HttpServletRequest req ) {
        try ( BufferedReader reader = req.getReader() ) {
            final StringBuilder buf = new StringBuilder();
            final char[] chunk = new char[ 1024 ];
            int n;
            while ( ( n = reader.read( chunk ) ) != -1 ) {
                buf.append( chunk, 0, n );
            }
            if ( buf.length() == 0 ) {
                return null;
            }
            final var parsed = JsonParser.parseString( buf.toString() );
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch ( final IOException | JsonSyntaxException e ) {
            LOG.debug( "Could not parse request body as JSON: {}", e.getMessage() );
            return null;
        }
    }

    static void writeJson( final HttpServletResponse resp, final int status, final Object body )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json" );
        resp.getWriter().write( GSON.toJson( body ) );
    }

    static void writeJsonError( final HttpServletResponse resp, final int status, final String message )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json" );
        resp.getWriter().write( GSON.toJson( Map.of( "error", message ) ) );
    }

    static void writeNotImplemented( final HttpServletResponse resp ) throws IOException {
        resp.setStatus( HttpServletResponse.SC_NOT_IMPLEMENTED );
        resp.setContentType( "application/json" );
        resp.getWriter().write( "{\"error\":\"Tool endpoint not yet implemented\"}" );
    }
}
