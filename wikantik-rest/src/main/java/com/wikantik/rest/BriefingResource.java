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
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.api.spi.Wiki;
import com.wikantik.knowledge.briefing.MarkdownBriefingRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code GET /api/briefing} — assembles a session-start context briefing (explicit pins/clusters
 * plus an optional retrieval-driven prompt) and returns it as JSON, or as injection-ready markdown
 * with {@code format=md}. No answer synthesis (ADR-0001).
 */
public class BriefingResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( BriefingResource.class );

    private static final Gson BRIEFING_GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Resolves the briefing assembly service from the knowledge subsystem.
     * Package-visible for test overrides via anonymous subclass.
     */
    protected BriefingAssemblyService briefingService() {
        final com.wikantik.WikiSubsystems subs = getSubsystems();
        return subs == null ? null : subs.knowledge().briefingAssemblyService();
    }

    /** Retrieval-query log, or {@code null} when logging is disabled/unwired. Test-overridable. */
    protected QueryLogService queryLogService() {
        return getEngine() instanceof WikiEngine we ? we.queryLogService() : null;
    }

    /** Briefing telemetry log, or {@code null} when logging is disabled/unwired. Test-overridable. */
    protected BriefingLogService briefingLogService() {
        return getEngine() instanceof WikiEngine we ? we.briefingLogService() : null;
    }

    /** Infers the caller's {@link ActorType} from the request's auth. Test-overridable. */
    protected ActorType actorType( final HttpServletRequest req ) {
        return RetrievalActorClassifier.classify( req, Wiki.session().find( getEngine(), req ) );
    }

    /** Comma-split helper: blank/absent -> empty list; trims and drops empty tokens. */
    private static List< String > splitCsv( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return List.of();
        }
        return Arrays.stream( raw.split( "," ) ).map( String::trim )
                .filter( s -> !s.isEmpty() ).toList();
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final List< String > pins = splitCsv( req.getParameter( "pins" ) );
        final List< String > clusters = splitCsv( req.getParameter( "clusters" ) );
        final String prompt = req.getParameter( "prompt" );

        final Integer budget;
        final String budgetRaw = req.getParameter( "budget" );
        if ( budgetRaw == null || budgetRaw.isBlank() ) {
            budget = null;
        } else {
            try {
                budget = Integer.parseInt( budgetRaw.trim() );
            } catch ( final NumberFormatException e ) {
                writeError( resp, 400, "invalid budget" );
                return;
            }
        }

        final ScopeMode mode;
        try {
            mode = ScopeMode.fromWire( req.getParameter( "scope_mode" ) );
        } catch ( final IllegalArgumentException e ) {
            writeError( resp, 400, e.getMessage() );
            return;
        }

        final String format = req.getParameter( "format" );
        if ( format != null && !format.isBlank() && !"json".equals( format ) && !"md".equals( format ) ) {
            writeError( resp, 400, "format must be 'json' or 'md'" );
            return;
        }

        final BriefingRequest request = new BriefingRequest( pins, clusters, prompt, budget, mode );
        if ( !request.hasAnySource() ) {
            writeError( resp, 400, "at least one of pins, clusters, prompt is required" );
            return;
        }

        final BriefingAssemblyService svc = briefingService();
        if ( svc == null ) {
            writeError( resp, 503, "briefing assembly service unavailable" );
            return;
        }

        final ContextBriefing briefing;
        try {
            briefing = svc.assemble( request );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Briefing assembly failed for pins={} clusters={}: {}", pins, clusters, e.getMessage(), e );
            writeError( resp, 500, "briefing assembly failed" );
            return;
        }

        // Authorization: sections and items must honour each source page's view ACL. The
        // assembly service does not filter, so drop anything the caller cannot view before
        // serializing — pointers included, so restricted pages don't even leak a title (404-hiding
        // semantics).
        final Set< String > allSlugs = new LinkedHashSet<>();
        briefing.sections().stream().map( BundleSection::slug ).filter( Objects::nonNull ).forEach( allSlugs::add );
        briefing.items().stream().map( BriefingItem::slug ).filter( Objects::nonNull ).forEach( allSlugs::add );
        final Set< String > viewable = filterViewable( req, allSlugs );

        final List< BundleSection > gatedSections = briefing.sections().stream()
                .filter( s -> viewable.contains( s.slug() ) ).toList();
        final List< BriefingItem > gatedItems = briefing.items().stream()
                .filter( i -> viewable.contains( i.slug() ) ).toList();
        final BundleCoverage coverage = BundleCoverage.recount( briefing.coverage(), gatedSections );

        final ContextBriefing gated = new ContextBriefing( briefing.prompt(), gatedSections, coverage,
                gatedItems, briefing.warnings(), briefing.budgetTokens(), briefing.usedTokens() );

        resp.setStatus( 200 );
        if ( "md".equals( format ) ) {
            resp.setContentType( "text/markdown; charset=UTF-8" );
            resp.getWriter().write( MarkdownBriefingRenderer.render( gated ) );
        } else {
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( BRIEFING_GSON.toJson( gated ) );
        }

        // Telemetry (fail-open; interfaces are contracted never to throw, never affects the
        // response written above).
        final boolean promptPresent = prompt != null && !prompt.isBlank();
        final QueryLogService qlog = queryLogService();
        if ( qlog != null && promptPresent ) {
            qlog.log( prompt, actorType( req ), SourceSurface.API_BRIEFING, gated.sections().size() );
        }

        final BriefingLogService blog = briefingLogService();
        if ( blog != null ) {
            final int pinCount = (int) gated.items().stream()
                    .filter( i -> i.included() && "pin".equals( i.origin() ) ).count();
            final int pointerCount = (int) gated.items().stream().filter( i -> !i.included() ).count();
            blog.log( new BriefingLogEntry( String.join( ",", pins ), String.join( ",", clusters ),
                    promptPresent, gated.budgetTokens(), gated.usedTokens(), gated.sections().size(),
                    pinCount, pointerCount, SourceSurface.API_BRIEFING.wire() ) );
        }
    }

    private static void writeError( final HttpServletResponse resp, final int status, final String message )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( "{\"error\":\"" + message.replace( "\"", "'" ) + "\"}" );
    }
}
