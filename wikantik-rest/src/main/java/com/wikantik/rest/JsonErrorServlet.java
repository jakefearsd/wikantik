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
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Container error-page handler that returns a JSON body for any servlet error
 * dispatched here (wired in {@code web.xml} as the location of {@code <error-page>}
 * entries for 400/404/405/415/500). Replaces Tomcat's default HTML error pages
 * — which leak the {@code Apache Tomcat/<version>} footer and stack traces —
 * with a stable {@code {"error":true,"status":<code>,"message":"..."}} shape.
 *
 * <p>This also catches errors that occur BEFORE servlet dispatch (e.g. URI
 * validation rejecting encoded slashes or null bytes): Tomcat falls back to the
 * web.xml error-page mappings for container-level errors too.
 *
 * <p>Reproduces D10 — was: Tomcat HTML error pages (with {@code Apache Tomcat/11.0.14}
 * version footer) leaked through {@code /api/*} and {@code /admin/*} on malformed
 * inputs (e.g. {@code GET /api/pages/..%2F..%2Fetc%2Fpasswd}, encoded slashes,
 * null bytes, or 404s under {@code /api/*} that didn't match a servlet).
 */
public class JsonErrorServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Gson GSON = new Gson();

    /**
     * Default messages per status code. Used when the container did not supply an
     * error message, or when the supplied message had to be discarded for
     * containing leakage candidates (Tomcat version, stack frames, paths, etc.).
     */
    private static final Map< Integer, String > DEFAULT_MESSAGE = Map.of(
            400, "Bad Request",
            401, "Unauthorized",
            403, "Forbidden",
            404, "Not Found",
            405, "Method Not Allowed",
            415, "Unsupported Media Type",
            500, "Internal Server Error" );

    @Override
    protected void service( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final int status = readStatus( request );
        final String message = sanitizeMessage(
                readMessage( request ),
                status );

        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );

        // Use a LinkedHashMap so the field order is predictable in the output.
        final Map< String, Object > body = new LinkedHashMap<>( 3 );
        body.put( "error", true );
        body.put( "status", status );
        body.put( "message", message );
        response.getWriter().write( GSON.toJson( body ) );
    }

    private static int readStatus( final HttpServletRequest request ) {
        final Object raw = request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE );
        if ( raw instanceof Integer i ) {
            return i;
        }
        if ( raw instanceof Number n ) {
            return n.intValue();
        }
        return 500;
    }

    private static String readMessage( final HttpServletRequest request ) {
        final Object raw = request.getAttribute( RequestDispatcher.ERROR_MESSAGE );
        return raw == null ? null : raw.toString();
    }

    /**
     * Discards or rewrites any candidate message that could leak server internals.
     * Strips:
     * <ul>
     *   <li>{@code Apache Tomcat/<version>} version strings.</li>
     *   <li>Java exception class names (qualified, with {@code .Exception} or {@code .Error} suffix)
     *       and stack frame fragments (anything matching {@code at com\.…(File\.java:\d+)}).</li>
     *   <li>Filesystem paths under {@code /opt/}, {@code /var/}, {@code /etc/}, {@code /usr/}
     *       and any reference to {@code WEB-INF} or {@code META-INF}.</li>
     * </ul>
     * If sanitisation drains the message dry, falls back to {@link #DEFAULT_MESSAGE}.
     */
    static String sanitizeMessage( final String raw, final int status ) {
        if ( raw == null || raw.isBlank() ) {
            return defaultFor( status );
        }
        String s = raw;
        // Strip Apache Tomcat version strings (e.g. "Apache Tomcat/11.0.14")
        s = s.replaceAll( "(?i)apache\\s+tomcat\\s*/?\\s*[\\d.]+", "" );
        s = s.replaceAll( "(?i)tomcat\\s*/\\s*[\\d.]+", "" );
        s = s.replaceAll( "(?i)apache\\s+tomcat", "" );
        // Strip stack frames (at com.foo.Bar.baz(Bar.java:42))
        s = s.replaceAll( "\\bat\\s+[\\w.$]+\\([\\w.$]+\\.java:\\d+\\)", "" );
        // Strip "at com.foo.Bar..." fragments without parentheses
        s = s.replaceAll( "\\bat\\s+[A-Za-z_][\\w.$]*\\.[A-Za-z_][\\w.$]*", "" );
        // Strip qualified exception class names (java.lang.NullPointerException, com.foo.X.Exception, etc.)
        s = s.replaceAll( "\\b[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)+(?:Exception|Error|Throwable)\\b", "" );
        // Strip simple top-level exception class names
        s = s.replaceAll( "\\b[A-Z][A-Za-z0-9_]*(?:Exception|Error|Throwable)\\b", "" );
        // Strip Java source filename references (e.g. Bar.java:42 standalone)
        s = s.replaceAll( "\\b[A-Za-z_][\\w]*\\.java(?::\\d+)?", "" );
        // Strip absolute filesystem paths under common server roots
        s = s.replaceAll( "/(?:opt|var|etc|usr|home|tmp)/[^\\s\"']*", "" );
        // Strip any path containing WEB-INF or META-INF
        s = s.replaceAll( "[^\\s\"']*(?:WEB-INF|META-INF)[^\\s\"']*", "" );
        // Collapse whitespace runs and trim
        s = s.replaceAll( "\\s+", " " ).trim();
        // Drop empty parens / leading punctuation left behind by stripping
        s = s.replaceAll( "^[\\s\\-:,.;()]+", "" );
        s = s.replaceAll( "[\\s\\-:,.;()]+$", "" );
        if ( s.isEmpty() ) {
            return defaultFor( status );
        }
        return s;
    }

    private static String defaultFor( final int status ) {
        return DEFAULT_MESSAGE.getOrDefault( status, "Error" );
    }
}
