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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.rest.CollabWire.Answer;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/** Reading {@code /api/collab/*} requests: the route, size-capped JSON bodies and the pull cursors. */
final class CollabRequests {

    private static final Logger LOG = LogManager.getLogger( CollabRequests.class );
    /** One WARN a minute per client address and kind of refusal (final review item 5); the rest at DEBUG. */
    private static final CollabWarnThrottle WARNS = new CollabWarnThrottle( Clock.systemUTC() );

    private CollabRequests() {
    }

    /** {@code /{page}/{action}} as {@code [page, action]}, or null; a page name never contains '/'. */
    static String[] route( final HttpServletRequest request ) {
        final String path = request.getPathInfo();
        if( path == null || path.length() < 2 ) {
            return null;
        }
        final int slash = path.lastIndexOf( '/' );
        if( slash <= 1 || slash == path.length() - 1 ) {
            return null;
        }
        return new String[] { path.substring( 1, slash ), path.substring( slash + 1 ) };
    }

    /**
     * Reads at most {@code maxBytes} before parsing anything (a larger body is 413 {@code oversize}, the rest never
     * read), then parses a JSON object (400 {@code rejected} otherwise; an empty body is an empty object). Returns the
     * {@link JsonObject} or the refusal {@link Answer}.
     */
    static Object readJson( final HttpServletRequest request, final long maxBytes ) throws IOException {
        if( request.getContentLengthLong() > maxBytes ) {
            return oversize( request, maxBytes );
        }
        final byte[] bytes;
        try( InputStream in = request.getInputStream() ) {
            bytes = in.readNBytes( ( int ) Math.min( Integer.MAX_VALUE - 8L, maxBytes + 1 ) );
        }
        if( bytes.length > maxBytes ) {
            return oversize( request, maxBytes );
        }
        final String text = new String( bytes, StandardCharsets.UTF_8 );
        try {
            final JsonElement e = text.isBlank() ? new JsonObject() : JsonParser.parseString( text );
            return e.isJsonObject() ? e.getAsJsonObject() : CollabWire.rejected( "the body must be a JSON object" );
        } catch( final RuntimeException e ) {
            final long quiet = WARNS.admit( "malformed " + request.getRemoteAddr() );
            if( quiet == CollabWarnThrottle.QUIET ) {
                LOG.debug( "collab: malformed JSON body for {}: {}", request.getRequestURI(), e.getMessage() );
            } else {
                LOG.warn( "collab: malformed JSON body for {} from {}: {}{}", request.getRequestURI(),
                    request.getRemoteAddr(), e.getMessage(), CollabWarnThrottle.quietSuffix( quiet ) );
            }
            return CollabWire.rejected( "Invalid JSON body: " + RestJson.sanitizeParseError( e.getMessage() ) );
        }
    }

    private static Answer oversize( final HttpServletRequest request, final long maxBytes ) {
        final long quiet = WARNS.admit( "oversize " + request.getRemoteAddr() );
        if( quiet == CollabWarnThrottle.QUIET ) {
            LOG.debug( "collab: refusing a body over {} bytes for {}", maxBytes, request.getRequestURI() );
        } else {
            LOG.warn( "collab: refusing a body over {} bytes for {} from {}{}", maxBytes, request.getRequestURI(),
                request.getRemoteAddr(), CollabWarnThrottle.quietSuffix( quiet ) );
        }
        final JsonObject o = new JsonObject();
        o.addProperty( CollabWire.REASON, "oversize" );
        o.addProperty( CollabWire.MESSAGE, "the request body is limited to " + maxBytes + " bytes" );
        return new Answer( 413, o );
    }

    /**
     * The pull cursors: {@code since} is required; {@code metaSince}, {@code presenceSince} and {@code eventsSince}
     * default to 0 (everything).
     *
     * @throws NumberFormatException for a missing {@code since}, or any unparseable or negative cursor
     */
    static PullCursor cursor( final HttpServletRequest request ) {
        return new PullCursor( Integer.parseInt( request.getParameter( "since" ) ), cursorParam( request, "metaSince" ),
            cursorParam( request, "presenceSince" ), cursorParam( request, "eventsSince" ) );
    }

    private static long cursorParam( final HttpServletRequest request, final String name ) {
        final String raw = request.getParameter( name );
        if( raw == null || raw.isBlank() ) {
            return 0;
        }
        final long v = Long.parseLong( raw.trim() );
        if( v < 0 ) {
            throw new NumberFormatException( name + " is negative" );
        }
        return v;
    }
}
