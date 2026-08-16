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
import com.google.gson.GsonBuilder;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.JndiDataSources;
import com.wikantik.insights.EngineTotal;
import com.wikantik.insights.InsightsStore;
import com.wikantik.insights.JdbcInsightsStore;
import com.wikantik.insights.TrendPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin read endpoint over {@code search_visibility_snapshot}:
 * {@code GET /admin/insights/acquisition?site=&amp;days=}.
 *
 * <p>Returns per-engine totals for the single most recent {@code snapshot_date}, plus a
 * clicks/impressions trend series covering the requested window (default 90 days, clamped to
 * 1–400). Both are read exclusively from page-rollup rows ({@code query_text = ''}): every
 * engine omits low-volume queries for privacy, so summing query rows would undercount page
 * totals — see {@code V050__search_visibility_snapshot.sql}.</p>
 *
 * <p>An engine that emits no page-rollup rows at all (Yandex, as of this writing) is simply
 * absent from {@code engines} — never synthesized as a zero row, since that would misrepresent
 * "no data" as "zero visibility". {@code position} is {@code null} whenever an engine emits no
 * positive position for the date (Yandex again, plus any date where an engine's rows all lack a
 * position); it is serialized as JSON {@code null}, never {@code 0} — {@code 0} would falsely
 * claim rank 1.</p>
 */
public class InsightsResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( InsightsResource.class );

    /** Emits explicit {@code null}s (e.g. a missing position) rather than omitting the field. */
    private static final Gson NULL_SAFE_GSON = new GsonBuilder().serializeNulls().create();

    static final String DEFAULT_SITE = "wiki.wikantik.com";
    static final int DEFAULT_DAYS = 90;
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 400;

    /** Admin-only surface — no cross-origin access. */
    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        final String site = requestedSite( request );
        final Integer days = requestedDays( request, response );
        if ( days == null ) {
            return; // requestedDays already sent the 400 error
        }

        final InsightsStore store = buildStore();
        if ( store == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Search-visibility store is not available (no configured datasource)." );
            return;
        }

        try {
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put( "site", site );

            final Optional<LocalDate> latest = store.latestSnapshotDate( site );
            if ( latest.isEmpty() ) {
                out.put( "snapshotDate", null );
                out.put( "engines", List.of() );
                out.put( "trend", List.of() );
                sendJsonNullSafe( response, out );
                return;
            }

            final LocalDate snapshotDate = latest.get();
            out.put( "snapshotDate", snapshotDate.toString() );
            out.put( "engines", engineRows( store.engineTotals( site, snapshotDate ) ) );

            final LocalDate since = LocalDate.now().minusDays( days - 1L );
            out.put( "trend", trendRows( store.trend( site, since ) ) );

            sendJsonNullSafe( response, out );
        } catch ( final RuntimeException e ) {
            LOG.warn( "acquisition insights query failed for site {}: {}", site, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to load acquisition insights." );
        }
    }

    private static List<Map<String, Object>> engineRows( final List<EngineTotal> totals ) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for ( final EngineTotal t : totals ) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put( "engine", t.engine() );
            row.put( "clicks", t.clicks() );
            row.put( "impressions", t.impressions() );
            row.put( "ctr", ctr( t.clicks(), t.impressions() ) );
            row.put( "position", t.position() ); // may be null -- NULL_SAFE_GSON keeps it null
            rows.add( row );
        }
        return rows;
    }

    private static List<Map<String, Object>> trendRows( final List<TrendPoint> points ) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for ( final TrendPoint p : points ) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put( "snapshotDate", p.snapshotDate().toString() );
            row.put( "engine", p.engine() );
            row.put( "clicks", p.clicks() );
            row.put( "impressions", p.impressions() );
            rows.add( row );
        }
        return rows;
    }

    /** Click-through rate, rounded to 5 decimal places; 0 when there are no impressions. */
    private static double ctr( final long clicks, final long impressions ) {
        if ( impressions <= 0 ) {
            return 0.0;
        }
        final double raw = ( double ) clicks / ( double ) impressions;
        return Math.round( raw * 100_000.0 ) / 100_000.0;
    }

    private static String requestedSite( final HttpServletRequest request ) {
        final String site = request.getParameter( "site" );
        return ( site == null || site.isBlank() ) ? DEFAULT_SITE : site.trim();
    }

    /**
     * Parses and clamps the {@code days} parameter to {@link #MIN_DAYS}..{@link #MAX_DAYS}.
     *
     * @return the clamped day count, or {@code null} if the parameter was present but not a
     *         valid integer (a 400 error has already been sent to {@code response} in that case)
     */
    private Integer requestedDays( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String raw = request.getParameter( "days" );
        if ( raw == null || raw.isBlank() ) {
            return DEFAULT_DAYS;
        }
        try {
            final int parsed = Integer.parseInt( raw.trim() );
            return Math.min( MAX_DAYS, Math.max( MIN_DAYS, parsed ) );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "days must be an integer (" + MIN_DAYS + "-" + MAX_DAYS + ")" );
            return null;
        }
    }

    /**
     * Resolves the configured JNDI DataSource and wraps it in a {@link JdbcInsightsStore}.
     * Package-private (not cached on the instance) so tests can override it to inject a mock
     * store without a live JNDI environment.
     *
     * @return a fresh store, or {@code null} if no datasource is configured/resolvable
     */
    InsightsStore buildStore() {
        final Engine engine = getEngine();
        if ( engine == null ) {
            return null;
        }
        final String datasourceName = engine.getWikiProperties().getProperty(
                AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        try {
            final DataSource ds = JndiDataSources.lookup( datasourceName,
                    "InsightsResource", AbstractJDBCDatabase.PROP_DATASOURCE );
            return new JdbcInsightsStore( ds );
        } catch ( final NoRequiredPropertyException e ) {
            LOG.warn( "no JNDI datasource resolved for search-visibility read ({}): {}",
                    datasourceName, e.getMessage() );
            return null;
        }
    }

    private void sendJsonNullSafe( final HttpServletResponse response, final Object payload ) throws IOException {
        response.setStatus( HttpServletResponse.SC_OK );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( NULL_SAFE_GSON.toJson( payload ) );
    }
}
