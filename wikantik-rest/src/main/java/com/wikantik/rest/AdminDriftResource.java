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

import com.wikantik.drift.DriftCount;
import com.wikantik.drift.DriftSnapshotRepository;
import com.wikantik.drift.DriftSweepRecord;
import com.wikantik.drift.DriftSweepService;
import com.wikantik.drift.PageViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin drift dashboard endpoints (AdminAuthFilter gates /admin/*):
 * GET  /admin/drift/summary — latest sweep counts + deltas vs the previous sweep
 * GET  /admin/drift/trend?days=N — sweeps in the window, oldest first
 * GET  /admin/drift/pages?family=F&amp;code=C — live offender list (never persisted)
 * POST /admin/drift/sweep — manual async trigger (202 / 409)
 */
public class AdminDriftResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminDriftResource.class );
    private static final com.google.gson.Gson NULL_SAFE_GSON =
            new com.google.gson.GsonBuilder().serializeNulls().create();

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final DriftSweepService service = service();
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "drift sweep service not available" );
            return;
        }
        final String action = extractPathParam( request );
        if ( "summary".equals( action ) ) {
            handleSummary( response );
        } else if ( "trend".equals( action ) ) {
            handleTrend( request, response );
        } else if ( "pages".equals( action ) ) {
            handlePages( request, response, service );
        } else {
            sendNotFound( response, "Unknown drift endpoint: " + action );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final DriftSweepService service = service();
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "drift sweep service not available" );
            return;
        }
        if ( !"sweep".equals( extractPathParam( request ) ) ) {
            sendNotFound( response, "Unknown drift endpoint" );
            return;
        }
        try {
            service.triggerAsync( "manual" );
            LOG.info( "manual drift sweep triggered" );
            sendJsonWithStatus( response, 202, Map.of( "state", "RUNNING" ) );
        } catch ( final DriftSweepService.SweepAlreadyRunningException e ) {
            sendJsonWithStatus( response, HttpServletResponse.SC_CONFLICT,
                    Map.of( "state", "RUNNING", "error", "a drift sweep is already running" ) );
        }
    }

    private void handleSummary( final HttpServletResponse response ) throws IOException {
        final Optional< DriftSweepRecord > latest = repository().latest();
        final Map< String, Object > out = new LinkedHashMap<>();
        if ( latest.isEmpty() ) {
            out.put( "sweptAt", null );
            out.put( "counts", List.of() );
            sendJsonWithStatus( response, 200, out );
            return;
        }
        final DriftSweepRecord sweep = latest.get();
        final Map< String, Integer > previous = new LinkedHashMap<>();
        repository().previousBefore( sweep.id() ).ifPresent( prev ->
                prev.counts().forEach( c -> previous.put( countKey( c ), c.count() ) ) );

        final List< Map< String, Object > > counts = new ArrayList<>();
        for ( final DriftCount c : sweep.counts() ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "family", c.family() );
            row.put( "code", c.code() );
            row.put( "severity", c.severity() );
            row.put( "count", c.count() );
            final Integer prev = previous.get( countKey( c ) );
            row.put( "delta", prev == null ? null : c.count() - prev );
            counts.add( row );
        }
        out.put( "sweptAt", DateTimeFormatter.ISO_INSTANT.format( sweep.sweptAt() ) );
        out.put( "pagesScanned", sweep.pagesScanned() );
        out.put( "durationMs", sweep.durationMs() );
        out.put( "triggeredBy", sweep.triggeredBy() );
        out.put( "shaclChecked", sweep.shaclChecked() );
        out.put( "counts", counts );
        sendJsonWithStatus( response, 200, out );
    }

    private void handleTrend( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        int days = 30;
        final String daysParam = request.getParameter( "days" );
        if ( daysParam != null ) {
            try {
                days = Math.max( 1, Integer.parseInt( daysParam ) );
            } catch ( final NumberFormatException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "days must be an integer" );
                return;
            }
        }
        final List< Map< String, Object > > sweeps = new ArrayList<>();
        for ( final DriftSweepRecord s : repository().trend( days ) ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "sweptAt", DateTimeFormatter.ISO_INSTANT.format( s.sweptAt() ) );
            row.put( "shaclChecked", s.shaclChecked() );
            row.put( "counts", s.counts().stream().map( c -> Map.of(
                    "family", c.family(), "code", c.code(),
                    "severity", c.severity(), "count", c.count() ) ).toList() );
            sweeps.add( row );
        }
        sendJsonWithStatus( response, 200, Map.of( "sweeps", sweeps ) );
    }

    private void handlePages( final HttpServletRequest request, final HttpServletResponse response,
                              final DriftSweepService service ) throws IOException {
        final String family = request.getParameter( "family" );
        final String code = request.getParameter( "code" );
        if ( family == null || family.isBlank() || code == null || code.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "family and code are required" );
            return;
        }
        final List< Map< String, Object > > pages = new ArrayList<>();
        for ( final PageViolation v : service.currentPageList( family, code ) ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "pageName", v.pageName() );
            row.put( "field", v.field() );
            row.put( "severity", v.severity() );
            row.put( "code", v.code() );
            row.put( "message", v.message() );
            row.put( "suggestion", v.suggestion() );
            pages.add( row );
        }
        sendJsonWithStatus( response, 200, Map.of( "pages", pages ) );
    }

    private static String countKey( final DriftCount c ) {
        return c.family() + "|" + c.code() + "|" + c.severity();
    }

    private DriftSweepService service() {
        return getSubsystems().pageGraph().driftSweepService();
    }

    /** Package-visible seam so the unit test can inject a mock repository. */
    DriftSnapshotRepository repository() {
        return service().repository();
    }

    private void sendJsonWithStatus( final HttpServletResponse response, final int status,
                                     final Object payload ) throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( NULL_SAFE_GSON.toJson( payload ) );
    }
}
