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

import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Admin REST endpoint that triggers and monitors the full-corpus entity
 * extraction batch. Mapped to {@code /admin/knowledge/extract-mentions/*} and
 * protected by {@link AdminAuthFilter}.
 *
 * <ul>
 *   <li>{@code POST /admin/knowledge/extract-mentions?force=true|false} — starts
 *       a batch run. Returns {@code 202 Accepted} with the current
 *       {@link BootstrapEntityExtractionIndexer.Status}, or {@code 409 Conflict}
 *       if a run is already in flight.</li>
 *   <li>{@code GET  /admin/knowledge/extract-mentions} — returns the current
 *       status (including {@code IDLE} / {@code COMPLETED} / {@code ERROR}).</li>
 * </ul>
 *
 * <p>The batch does not block the request — it runs on a dedicated background
 * thread; the POST returns immediately. Watch the logs (INFO) for per-page
 * timings and the final summary, or poll GET for a progress snapshot.</p>
 */
public class AdminExtractionResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminExtractionResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final BootstrapEntityExtractionIndexer indexer = getEngine()
                .getManager( BootstrapEntityExtractionIndexer.class );
        if ( indexer == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Entity extraction batch is not configured (wikantik.knowledge.extractor.backend=disabled?)" );
            return;
        }
        sendJson( response, statusToMap( indexer.status() ) );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final BootstrapEntityExtractionIndexer indexer = getEngine()
                .getManager( BootstrapEntityExtractionIndexer.class );
        if ( indexer == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Entity extraction batch is not configured" );
            return;
        }
        final boolean cancelled = indexer.cancel();
        if ( !cancelled ) {
            sendError( response, HttpServletResponse.SC_CONFLICT,
                "No extraction run is currently in progress" );
            return;
        }
        LOG.info( "Admin requested batch extraction cancel" );
        sendJson( response, statusToMap( indexer.status() ) );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final BootstrapEntityExtractionIndexer indexer = getEngine()
                .getManager( BootstrapEntityExtractionIndexer.class );
        if ( indexer == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Entity extraction batch is not configured (wikantik.knowledge.extractor.backend=disabled?)" );
            return;
        }
        final boolean force = parseForceParam( request );
        final boolean started = indexer.start( force );
        if ( !started ) {
            response.setStatus( HttpServletResponse.SC_CONFLICT );
            final Map< String, Object > body = new LinkedHashMap<>( statusToMap( indexer.status() ) );
            body.put( "message", "A full-corpus extraction run is already in progress" );
            sendJson( response, body );
            return;
        }
        LOG.info( "Admin triggered batch extraction: force={}", force );
        response.setStatus( HttpServletResponse.SC_ACCEPTED );
        sendJson( response, statusToMap( indexer.status() ) );
    }

    private static boolean parseForceParam( final HttpServletRequest request ) {
        final String raw = request.getParameter( "force" );
        if ( raw == null || raw.isBlank() ) return false;
        final String normalized = raw.trim().toLowerCase( Locale.ROOT );
        return "true".equals( normalized ) || "yes".equals( normalized ) || "1".equals( normalized );
    }

    private static Map< String, Object > statusToMap( final BootstrapEntityExtractionIndexer.Status s ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "state", s.state().name() );
        m.put( "totalPages", s.totalPages() );
        m.put( "processedPages", s.processedPages() );
        m.put( "failedPages", s.failedPages() );
        m.put( "totalChunks", s.totalChunks() );
        m.put( "processedChunks", s.processedChunks() );
        m.put( "failedChunks", s.failedChunks() );
        m.put( "mentionsWritten", s.mentionsWritten() );
        m.put( "proposalsFiled", s.proposalsFiled() );
        m.put( "elapsedMs", s.elapsedMs() );
        m.put( "forceOverwrite", s.forceOverwrite() );
        m.put( "concurrency", s.concurrency() );
        m.put( "startedAt", isoOrNull( s.startedAt() ) );
        m.put( "finishedAt", isoOrNull( s.finishedAt() ) );
        if ( s.lastError() != null ) m.put( "lastError", s.lastError() );
        return m;
    }

    private static String isoOrNull( final Instant i ) {
        return i == null ? null : i.toString();
    }
}
