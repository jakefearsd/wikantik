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

import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.pagegraph.spine.ConfidenceComputer;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import com.wikantik.rest.admin.AgentGradeAuditResource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Servlet adapter that mounts {@link AgentGradeAuditResource} at
 * {@code GET /admin/agent-grade-audit}. Behind {@code AdminAuthFilter}
 * (AllPermission) — no additional auth check inside this class.
 *
 * <p>Lazily constructs the delegate on first request so that the structural
 * index service is guaranteed to be registered by the time any request
 * arrives. Returns HTTP 503 if the structural index or reference manager
 * is unavailable.</p>
 *
 * <p>The {@link ConfidenceComputer} is constructed fresh with
 * {@code name -> false} (no trusted-author promotion — stale window only).
 * A follow-up task should wire a real trusted-author predicate via
 * {@link com.wikantik.pagegraph.spine.TrustedAuthorsDao} once that DAO is
 * accessible through the subsystem bridge.</p>
 */
public class AdminAgentGradeAuditServlet extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminAgentGradeAuditServlet.class );

    /** Guarded by {@code this}. */
    private volatile AgentGradeAuditResource delegate;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final AgentGradeAuditResource resource = resolveDelegate( resp );
        if ( resource == null ) return;   // 503 already written

        final int limit  = parseIntOr( req.getParameter( "limit" ),  50 );
        final int offset = parseIntOr( req.getParameter( "offset" ),  0 );

        final String json = resource.audit( limit, offset );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( json );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AgentGradeAuditResource resolveDelegate( final HttpServletResponse resp )
            throws IOException {
        if ( delegate != null ) return delegate;
        synchronized ( this ) {
            if ( delegate != null ) return delegate;

            final PageGraphSubsystem.Services pg = getSubsystems() == null
                    ? null : getSubsystems().pageGraph();
            if ( pg == null ) {
                unavailable( resp, "page-graph subsystem not yet registered" );
                return null;
            }

            final StructuralIndexService svc = pg.structuralIndexService();
            if ( svc == null ) {
                unavailable( resp, "structural index service unavailable" );
                return null;
            }

            final ReferenceManager refs = pg.referenceManager();
            if ( refs == null ) {
                unavailable( resp, "reference manager not yet initialised" );
                return null;
            }

            // ConfidenceComputer: no trusted-author predicate available through
            // the subsystem bridge in this release — use stale-window-only mode.
            // TODO: wire TrustedAuthorsDao when the bridge exposes it.
            final ConfidenceComputer confidence = new ConfidenceComputer( name -> false );
            delegate = new AgentGradeAuditResource( svc, refs, confidence );
        }
        return delegate;
    }

    private static void unavailable( final HttpServletResponse resp, final String reason )
            throws IOException {
        LOG.warn( "GET /admin/agent-grade-audit: {}", reason );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 503 );
        resp.getWriter().write( "{\"error\":\"" + reason + "\"}" );
    }

    private static int parseIntOr( final String raw, final int fallback ) {
        if ( raw == null || raw.isBlank() ) return fallback;
        try {
            return Integer.parseInt( raw.trim() );
        } catch ( final NumberFormatException e ) {
            return fallback;
        }
    }
}
