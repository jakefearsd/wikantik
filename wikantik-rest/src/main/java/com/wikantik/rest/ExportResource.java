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

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.export.ExportPreview;
import com.wikantik.export.ExportSelection;
import com.wikantik.export.ExportService;
import com.wikantik.export.ExportTooLargeException;

/**
 * {@code GET /api/export}, {@code /api/export/preview}, {@code /api/export/options} — bulk
 * export of viewer-visible content as an Obsidian-compatible vault zip.
 *
 * <p>Every request requires an authenticated session carrying the {@code export} wiki
 * permission ({@link WikiPermission#EXPORT}). {@code /options} returns the cluster/tag picker
 * lists; {@code /preview} returns a cheap size estimate for a selection without doing any
 * conversion work; the bare path streams the zip itself.
 *
 * <p>{@code wikantik.baseURL} defaults to blank, which would make every wiki link written
 * into the export relative and useless once opened outside the wiki. When the property is
 * blank, {@link ExportService} falls back to a base URL derived from this request
 * ({@code scheme://host[:port]} + the context path) rather than emitting relative links.
 */
public class ExportResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ExportResource.class );

    /** Seam for tests. Derives the {@code wikantik.baseURL} fallback from {@code req}. */
    protected ExportService exportService( final HttpServletRequest req ) {
        return ExportService.fromSubsystems( getEngine(), getSubsystems(), ExportRequestParser.requestBaseUrl( req ) );
    }

    /**
     * Enforces the {@code export} wiki permission. Overridable so tests can exercise the
     * 403 branch without depending on policy-grant fixtures.
     */
    protected boolean canExport( final Session session ) {
        final AuthorizationManager auth = AuthSubsystemBridge.fromLegacyEngine( getEngine() ).authorization();
        return auth.checkPermission( session, WikiPermission.EXPORT );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final Session session = Wiki.session().find( getEngine(), req );
        if ( !session.isAuthenticated() ) {
            sendError( resp, HttpServletResponse.SC_UNAUTHORIZED, "Login required to export" );
            return;
        }
        if ( !canExport( session ) ) {
            sendError( resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden: export permission required" );
            return;
        }
        final String sub = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        switch ( sub ) {
            case "", "/" -> download( req, resp, session );
            case "/preview" -> preview( req, resp, session );
            case "/options" -> sendJson( resp, exportService( req ).options() );
            default -> sendNotFound( resp, "Unknown export path: " + sub );
        }
    }

    private void preview( final HttpServletRequest req, final HttpServletResponse resp, final Session session )
            throws IOException {
        final ExportSelection selection;
        try {
            selection = ExportRequestParser.parseSelection( req );
        } catch ( final IllegalArgumentException e ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
            return;
        }
        final ExportPreview preview = exportService( req ).preview( session, selection );
        sendJson( resp, preview );
    }

    private void download( final HttpServletRequest req, final HttpServletResponse resp, final Session session )
            throws IOException {
        final ExportSelection selection;
        try {
            selection = ExportRequestParser.parseSelection( req );
        } catch ( final IllegalArgumentException e ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
            return;
        }
        final ExportService service = exportService( req );
        final ExportService.PreparedExport prepared;
        try {
            prepared = service.prepare( session, selection );
        } catch ( final ExportTooLargeException e ) {
            sendTooLarge( resp, e );
            return;
        }
        if ( prepared.pages().isEmpty() ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "Selection matches no pages" );
            return;
        }
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "application/zip" );
        resp.setHeader( "Content-Disposition", "attachment; filename=\"" + prepared.fileName() + "\"" );
        resp.setHeader( "Cache-Control", "no-store" );
        try {
            service.stream( prepared, resp.getOutputStream() );
        } catch ( final IOException e ) {
            // Client disconnected mid-stream (or a similar transport failure) after headers and a
            // 200 status were already committed — the status can no longer change. Not swallowed
            // silently: logged at DEBUG (not WARN) because a client hanging up on a large download
            // is routine, not a defect.
            LOG.debug( "Export stream aborted (client disconnect?): {}", e.getMessage() );
        }
    }

    private void sendTooLarge( final HttpServletResponse resp, final ExportTooLargeException e ) throws IOException {
        resp.setStatus( HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE );
        resp.setContentType( "application/json" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.getWriter().write( GSON.toJson( Map.of(
                "error", true,
                "status", HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "message", e.getMessage(),
                "count", e.count(),
                "cap", e.cap() ) ) );
    }

}
