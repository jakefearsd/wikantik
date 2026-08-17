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
import com.wikantik.WikiEngine;
import com.wikantik.insights.Opportunity;
import com.wikantik.insights.SuppressedRule;
import com.wikantik.insights.runtime.ContentOpportunityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /admin/insights/backlog} -- the opportunity backlog for the admin panel.
 *
 * <p>Reads the same {@link ContentOpportunityService} the MCP tools use, so the panel and the
 * agent can never disagree about what the backlog says. Behind {@code AdminAuthFilter}, which
 * already requires AllPermission, so no additional permission check is performed here.</p>
 *
 * <p>The response carries {@code suppressed} alongside {@code opportunities} for the reason
 * described in the design's section 7.3.0: on this corpus the volume-driven rules are gated off,
 * and a panel that rendered only an empty list would say "nothing to do" when the truth is "not
 * enough traffic to tell".</p>
 *
 * <p>{@code ctrCurveSource} carries the same visibility for the CTR-by-position curve
 * {@code engine_divergence} was priced with -- {@code "imported:<as_of>"} vs {@code "builtin"} --
 * so a jakemon shipment that has quietly gone stale is visible in the panel, not just inferable
 * from the numbers looking different (design §7.3 rule 2, V057).</p>
 */
public class InsightsBacklogResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( InsightsBacklogResource.class );
    private static final Gson NULL_SAFE_GSON = new GsonBuilder().serializeNulls().create();

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final ContentOpportunityService service =
                getEngine() instanceof WikiEngine we ? we.contentOpportunityService() : null;
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The content-intelligence subsystem is not available "
                            + "(needs a configured datasource and wikantik.insights.enabled=true)." );
            return;
        }

        final String site = blankToNull( request.getParameter( "site" ) );
        final String type = blankToNull( request.getParameter( "type" ) );
        final boolean includeSnoozed = Boolean.parseBoolean( request.getParameter( "includeSnoozed" ) );
        final Integer limit = parseLimit( request, response );
        if ( limit == null ) {
            return; // parseLimit already sent the 400
        }
        Double minPriority = null;
        final String minPriorityParam = blankToNull( request.getParameter( "minPriority" ) );
        if ( minPriorityParam != null ) {
            try {
                minPriority = Double.valueOf( minPriorityParam );
            } catch ( final NumberFormatException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "minPriority must be a number." );
                return;
            }
        }

        final ContentOpportunityService.BacklogView view;
        try {
            view = service.backlog( site, type, minPriority, limit, includeSnoozed );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Backlog read failed: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to assemble the opportunity backlog." );
            return;
        }

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "site", view.site() );
        out.put( "generatedAt", view.generatedAt() == null ? null : view.generatedAt().toString() );
        out.put( "opportunities", renderOpportunities( view.opportunities() ) );
        out.put( "count", view.opportunities().size() );
        out.put( "suppressed", renderSuppressed( view.suppressed() ) );
        out.put( "uncalibratedTypes", new ArrayList<>( view.uncalibratedTypes() ) );
        out.put( "ctrCurveSource", view.ctrCurveSource() );

        response.setContentType( "application/json;charset=UTF-8" );
        response.getWriter().write( NULL_SAFE_GSON.toJson( out ) );
    }

    private static List< Map< String, Object > > renderOpportunities( final List< Opportunity > list ) {
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final Opportunity o : list ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "type", o.type() );
            m.put( "target", o.target() );
            m.put( "priority", o.priority() );
            m.put( "calibrated", o.calibrated() );
            m.put( "suggestedAction", o.suggestedAction() );
            m.put( "firstSeen", o.firstSeen() == null ? null : o.firstSeen().toString() );
            m.put( "evidence", o.evidence() );
            out.add( m );
        }
        return out;
    }

    private static List< Map< String, Object > > renderSuppressed( final List< SuppressedRule > list ) {
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final SuppressedRule s : list ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "type", s.type() );
            m.put( "reason", s.reason() );
            m.put( "measured", s.measured() );
            m.put( "required", s.required() );
            out.add( m );
        }
        return out;
    }

    private Integer parseLimit( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String raw = blankToNull( request.getParameter( "limit" ) );
        if ( raw == null ) {
            return DEFAULT_LIMIT;
        }
        final int value;
        try {
            value = Integer.parseInt( raw.strip() );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "limit must be a whole number." );
            return null;
        }
        if ( value < 1 || value > MAX_LIMIT ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT + "." );
            return null;
        }
        return value;
    }

    private static String blankToNull( final String value ) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
