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
import com.wikantik.observability.JfrProfilingService;
import com.wikantik.rest.admin.ProfilingResource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Servlet mounting {@link ProfilingResource} at {@code /admin/profiling/jfr/*}.
 * Behind {@code AdminAuthFilter} (AllPermission).
 *
 * <p>Routes by {@link HttpServletRequest#getPathInfo() pathInfo}
 * (the URL part after {@code /admin/profiling/jfr}):</p>
 *
 * <ul>
 *   <li>{@code POST /start} — {@code {"duration_s": 1..600, "label": "..."}}
 *       → {@link com.wikantik.observability.JfrProfilingService.RecordingInfo RecordingInfo} as JSON</li>
 *   <li>{@code POST /stop} — {@code {"recording_id": "..."}} → RecordingInfo (FINISHED)</li>
 *   <li>{@code GET /recordings} → array of RecordingInfo</li>
 *   <li>{@code GET /recordings/{id}} → the {@code .jfr} file (octet-stream)</li>
 * </ul>
 *
 * <p>Profiling directory + size cap are read once from system properties on
 * first request:</p>
 *
 * <ul>
 *   <li>{@code -Dwikantik.profiling.dir} — defaults to {@code /var/wikantik/profiling}</li>
 *   <li>{@code -Dwikantik.profiling.dir.max_bytes} — defaults to 5 GB</li>
 * </ul>
 */
public class AdminProfilingServlet extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminProfilingServlet.class );

    /** Guarded by {@code this}. Lazily constructed so servlet init can't fail on missing dirs. */
    private volatile ProfilingResource delegate;

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final ProfilingResource r = resolveDelegate();
        final String path = pathOrEmpty( req );
        try {
            switch ( path ) {
                case "/start" -> {
                    final JsonObject body = JsonParser.parseReader( req.getReader() ).getAsJsonObject();
                    if ( !body.has( "duration_s" ) ) {
                        resp.sendError( 400, "missing required field: duration_s" );
                        return;
                    }
                    final int dur = getJsonInt( body, "duration_s", Integer.MIN_VALUE );
                    if ( dur == Integer.MIN_VALUE ) {
                        resp.sendError( 400, "duration_s must be an integer" );
                        return;
                    }
                    final String label = getJsonString( body, "label" );
                    writeJson( resp, 200, r.start( dur, label != null ? label : "" ) );
                }
                case "/stop" -> {
                    final JsonObject body = JsonParser.parseReader( req.getReader() ).getAsJsonObject();
                    final String recordingId = getJsonString( body, "recording_id" );
                    if ( recordingId == null ) {
                        resp.sendError( 400, "missing required field: recording_id" );
                        return;
                    }
                    writeJson( resp, 200, r.stop( recordingId ) );
                }
                default -> resp.sendError( HttpServletResponse.SC_NOT_FOUND, "unknown profiling route: " + path );
            }
        } catch ( final ProfilingResource.RestError e ) {
            LOG.info( "POST /admin/profiling/jfr{}: {} {}", path, e.status(), e.getMessage() );
            resp.sendError( e.status(), e.getMessage() );
        } catch ( final com.google.gson.JsonParseException | IllegalStateException e ) {
            // Malformed JSON body or a non-object top-level value — a client error, not a 500.
            LOG.info( "POST /admin/profiling/jfr{}: rejecting malformed body: {}", path, e.getMessage() );
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "invalid JSON body" );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final ProfilingResource r = resolveDelegate();
        final String path = pathOrEmpty( req );
        try {
            if ( "/recordings".equals( path ) || "/recordings/".equals( path ) ) {
                writeJson( resp, 200, r.list() );
                return;
            }
            if ( path.startsWith( "/recordings/" ) ) {
                final String id = path.substring( "/recordings/".length() );
                if ( id.isBlank() || id.contains( "/" ) ) {
                    resp.sendError( 400, "bad recording_id segment" );
                    return;
                }
                final Path file = r.filePathFor( id );
                resp.setContentType( "application/octet-stream" );
                resp.setHeader( "Content-Disposition",
                    "attachment; filename=\"" + file.getFileName() + "\"" );
                if ( !file.toFile().exists() ) {
                    resp.sendError( 404, "recording file not on disk: " + file.getFileName() );
                    return;
                }
                resp.setContentLengthLong( file.toFile().length() );
                Files.copy( file, resp.getOutputStream() );
                return;
            }
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "unknown profiling route: " + path );
        } catch ( final ProfilingResource.RestError e ) {
            LOG.info( "GET /admin/profiling/jfr{}: {} {}", path, e.status(), e.getMessage() );
            resp.sendError( e.status(), e.getMessage() );
        }
    }

    private static String pathOrEmpty( final HttpServletRequest req ) {
        final String p = req.getPathInfo();
        return p == null ? "" : p;
    }

    private static void writeJson( final HttpServletResponse resp, final int status, final String body )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( body );
    }

    private ProfilingResource resolveDelegate() {
        ProfilingResource d = delegate;
        if ( d != null ) return d;
        synchronized ( this ) {
            if ( delegate != null ) return delegate;
            final String dir = System.getProperty( "wikantik.profiling.dir", "/var/wikantik/profiling" );
            final long cap = Long.getLong( "wikantik.profiling.dir.max_bytes", 5L * 1024 * 1024 * 1024 );
            final JfrProfilingService svc = new JfrProfilingService( Paths.get( dir ), cap );
            delegate = new ProfilingResource( svc );
            LOG.info( "AdminProfilingServlet initialised: dir={} cap={}B", dir, cap );
            return delegate;
        }
    }
}
