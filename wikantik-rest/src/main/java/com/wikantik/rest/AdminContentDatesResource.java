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

import com.wikantik.api.core.Page;
import com.wikantik.seo.ContentDateService;
import com.wikantik.seo.ContentDateWiringHelper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admin endpoint that backfills each page's content-changed date from its version history.
 *
 * <ul>
 *   <li>{@code POST /admin/seo/content-dates/backfill?force=true|false} starts one background run and answers
 *       {@code 202}; a second POST while a run is active answers {@code 409}. Without {@code force}, pages that
 *       already have a stored date are skipped, so an interrupted run can simply be started again.</li>
 *   <li>{@code GET /admin/seo/content-dates/status} reports {@code state} (idle|running|done|failed),
 *       {@code total}, {@code done}, {@code failed} and {@code startedAt}.</li>
 * </ul>
 *
 * <p>Protected by {@code AdminAuthFilter} (the {@code /admin/*} filter mapping).
 */
public class AdminContentDatesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminContentDatesResource.class );

    private static final String NO_SERVICE =
        "Content dates are unavailable: no datasource is configured for this wiki";

    /** One backfill run; the counters are written by the worker thread and read by status polls. */
    private static final class Run {
        final Instant startedAt = Instant.now();
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        volatile String state = "running";

        Map< String, Object > toJson() {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "state", state );
            m.put( "total", total.get() );
            m.put( "done", done.get() );
            m.put( "failed", failed.get() );
            m.put( "startedAt", startedAt.toString() );
            return m;
        }
    }

    private volatile Run run;

    /** The service, or null when no datasource is configured. Protected so tests can inject one. */
    protected ContentDateService contentDateService() {
        return ContentDateWiringHelper.serviceOf( getEngine() );
    }

    /** Every page name in the wiki. Protected so tests can inject a list. */
    protected List< String > allPageNames() {
        try {
            return getSubsystems().page().pages().getAllPages().stream().map( Page::getName ).toList();
        } catch( final Exception e ) {
            LOG.warn( "AdminContentDatesResource: could not list pages: {}", e.getMessage(), e );
            throw new IllegalStateException( "could not list pages", e );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if( !"status".equals( extractPathParam( request ) ) ) {
            sendNotFound( response, "Unknown content-dates endpoint: " + extractPathParam( request ) );
            return;
        }
        if( contentDateService() == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, NO_SERVICE );
            return;
        }
        final Run current = run;
        response.setStatus( HttpServletResponse.SC_OK );
        if( current == null ) {
            final com.google.gson.JsonObject idle = new com.google.gson.JsonObject();
            idle.addProperty( "state", "idle" );
            idle.addProperty( "total", 0 );
            idle.addProperty( "done", 0 );
            idle.addProperty( "failed", 0 );
            idle.add( "startedAt", com.google.gson.JsonNull.INSTANCE );
            RestJson.sendJsonKeepingNulls( response, idle );
        } else {
            sendJson( response, current.toJson() );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if( !"backfill".equals( extractPathParam( request ) ) ) {
            sendNotFound( response, "Unknown content-dates endpoint: " + extractPathParam( request ) );
            return;
        }
        final ContentDateService service = contentDateService();
        if( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, NO_SERVICE );
            return;
        }
        final boolean force = Boolean.parseBoolean( request.getParameter( "force" ) );
        final Run started;
        synchronized( this ) {
            if( run != null && "running".equals( run.state ) ) {
                sendError( response, HttpServletResponse.SC_CONFLICT, "A content-date backfill is already running" );
                return;
            }
            started = new Run();
            run = started;
        }
        Thread.ofVirtual().name( "content-date-backfill" ).start( () -> execute( service, started, force ) );
        LOG.info( "AdminContentDatesResource: backfill started (force={})", force );
        response.setStatus( HttpServletResponse.SC_ACCEPTED );
        sendJson( response, Map.of( "state", "running" ) );
    }

    private void execute( final ContentDateService service, final Run r, final boolean force ) {
        try {
            final Map< String, Instant > have = force ? Map.of() : service.allContentDates();
            final List< String > todo = allPageNames().stream().filter( p -> !have.containsKey( p ) ).toList();
            r.total.set( todo.size() );
            for( final String name : todo ) {
                try {
                    service.recompute( name );
                    r.done.incrementAndGet();
                } catch( final RuntimeException e ) {
                    r.failed.incrementAndGet();
                    LOG.warn( "AdminContentDatesResource: backfill failed for page '{}': {}", name, e.getMessage(), e );
                }
            }
            r.state = "done";
            LOG.info( "AdminContentDatesResource: backfill finished total={} done={} failed={}",
                      r.total.get(), r.done.get(), r.failed.get() );
        } catch( final Throwable t ) {
            LOG.error( "AdminContentDatesResource: backfill aborted: {}", t.getMessage(), t );
        } finally {
            // Whatever happened (including an Error), never leave "running": it would 409 every later POST.
            if( !"done".equals( r.state ) ) {
                r.state = "failed";
            }
        }
    }
}
