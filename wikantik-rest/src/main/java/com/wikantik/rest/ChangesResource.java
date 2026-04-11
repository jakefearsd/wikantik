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
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.util.BaseUrlResolver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * REST servlet for the changes feed used by OpenWebUI's sync script and other
 * indexing pipelines.
 *
 * <p>Mapped to <code>/api/changes</code>. Handles:
 * <ul>
 *   <li><code>GET /api/changes</code> &mdash; list all recently changed pages (full export)</li>
 *   <li><code>GET /api/changes?since=2026-04-11T00:00:00Z</code> &mdash; pages modified after the timestamp</li>
 * </ul>
 */
public class ChangesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ChangesResource.class );

    /**
     * Local Gson that preserves null fields so the {@code since} key is always
     * present in the response (even on a full export where the client did not
     * supply a {@code since} parameter).
     */
    private static final Gson NULL_SAFE_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" )
            .serializeNulls()
            .create();

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        Date since = null;
        final String sinceParam = request.getParameter( "since" );
        if ( sinceParam != null && !sinceParam.isBlank() ) {
            try {
                since = parseIso8601( sinceParam );
            } catch ( final ParseException e ) {
                LOG.warn( "Rejected /api/changes request with invalid 'since' parameter: {}", sinceParam );
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid 'since' parameter (expected ISO 8601): " + sinceParam );
                return;
            }
        }

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Set< Page > pages = ( since != null )
                ? pm.getRecentChanges( since )
                : pm.getRecentChanges();

        final String baseUrl = BaseUrlResolver.resolve( engine, request, null );
        final String baseNoSlash = baseUrl.endsWith( "/" )
                ? baseUrl.substring( 0, baseUrl.length() - 1 )
                : baseUrl;

        final List< Map< String, Object > > out = new ArrayList<>( pages.size() );
        for ( final Page p : pages ) {
            if ( since != null && p.getLastModified() != null
                    && p.getLastModified().before( since ) ) {
                continue;
            }
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "slug", p.getName() );
            entry.put( "modified_at", p.getLastModified() );
            entry.put( "url", baseNoSlash + "/wiki/" + p.getName() );
            out.add( entry );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "since", since );
        result.put( "generated_at", new Date() );
        result.put( "pages", out );

        LOG.debug( "GET /api/changes since={} returned {} pages", since, out.size() );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( NULL_SAFE_GSON.toJson( result ) );
    }

    /**
     * Parses an ISO 8601 datetime. Accepts {@code yyyy-MM-dd'T'HH:mm:ss'Z'},
     * the same with milliseconds, a timezone offset form, or a bare
     * {@code yyyy-MM-dd} date. All parse attempts run in UTC.
     */
    static Date parseIso8601( final String s ) throws ParseException {
        final String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd"
        };
        ParseException last = null;
        for ( final String p : patterns ) {
            final SimpleDateFormat f = new SimpleDateFormat( p );
            f.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
            f.setLenient( false );
            try {
                return f.parse( s );
            } catch ( final ParseException e ) {
                last = e;
            }
        }
        throw last == null ? new ParseException( s, 0 ) : last;
    }
}
