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

import com.wikantik.analytics.AnalyticsSettings;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.JndiDataSources;
import com.wikantik.insights.readers.ReaderReport;
import com.wikantik.insights.readers.ReaderReportView;
import com.wikantik.insights.runtime.ReaderReports;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /admin/insights/readers?days=} -- what readers did on each page, from the reader
 * rollups the analytics read-back fills, with a search-clicks versus organic-landings coverage
 * check. {@code days} defaults to 28 and is clamped to 1-90; pages come most-viewed first.
 * Behind {@code AdminAuthFilter} (AllPermission), so no further check is made here.
 */
public class InsightsReadersResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( InsightsReadersResource.class );
    private static final Gson NULL_SAFE_GSON = new GsonBuilder().serializeNulls().create();

    static final int DEFAULT_DAYS = 28;
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 90;
    static final int PAGE_LIMIT = 200;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final Integer days = requestedDays( request, response );
        if ( days == null ) {
            return;
        }
        final ReaderReport report = buildReport();
        if ( report == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Reader analytics is not available (no configured datasource)." );
            return;
        }
        final String site = siteHost();
        try {
            final Map< String, Object > out = new LinkedHashMap<>();
            out.put( "pages", ReaderReportView.pages( report.pages( site, days, PAGE_LIMIT ) ) );
            out.put( "coverage", ReaderReportView.coverage( report.coverage( site, days ) ) );
            final Instant refreshed = report.refreshedAt( site );
            out.put( "refreshedAt", refreshed == null ? null : refreshed.toString() );
            out.put( "days", days );
            response.setStatus( HttpServletResponse.SC_OK );
            response.setContentType( "application/json" );
            response.setCharacterEncoding( "UTF-8" );
            response.getWriter().write( NULL_SAFE_GSON.toJson( out ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "reader insights query failed for site {}: {}", site, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to load reader analytics." );
        }
    }

    private Integer requestedDays( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String raw = request.getParameter( "days" );
        if ( raw == null || raw.isBlank() ) {
            return DEFAULT_DAYS;
        }
        try {
            return Math.min( MAX_DAYS, Math.max( MIN_DAYS, Integer.parseInt( raw.trim() ) ) );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "days must be an integer (" + MIN_DAYS + "-" + MAX_DAYS + ")" );
            return null;
        }
    }

    /** The analytics site host; package-private so tests can fix it without engine properties. */
    String siteHost() {
        final Engine engine = getEngine();
        return engine == null ? "" : AnalyticsSettings.siteHostOf( engine.getWikiProperties() );
    }

    /** Resolves the JNDI DataSource; package-private so tests can inject a report. Null when unconfigured. */
    ReaderReport buildReport() {
        final Engine engine = getEngine();
        if ( engine == null ) {
            return null;
        }
        final String datasourceName = AbstractJDBCDatabase.datasourceName( engine.getWikiProperties() );
        try {
            final DataSource ds = JndiDataSources.lookup( datasourceName,
                    "InsightsReadersResource", AbstractJDBCDatabase.PROP_DATASOURCE );
            return ReaderReports.create( ds, engine.getFrontPage() );
        } catch ( final NoRequiredPropertyException e ) {
            LOG.warn( "no JNDI datasource resolved for reader analytics ({}): {}", datasourceName, e.getMessage() );
            return null;
        }
    }
}
