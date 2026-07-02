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
package com.wikantik.rest.knowledge;

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.knowledge.judge.DefaultKgProposalJudgeService;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * LLM-judge admin handlers extracted verbatim from {@code AdminKnowledgeResource}: judge-runner
 * status/trigger ({@code /judge/status}, {@code /judge/run}) and chronic-timeout tracking
 * ({@code /judge-timeouts}).
 * <p>
 * Package-private-in-spirit: the type is {@code public} only because it must be constructed and
 * invoked from {@code com.wikantik.rest.AdminKnowledgeResource}'s dispatch table, which lives in a
 * different package — it is not part of any documented public API of {@code wikantik-rest}.
 */
public final class KgJudgeAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( KgJudgeAdminHandlers.class );

    private final Supplier< JudgeRunner > judgeRunner;
    private final Supplier< KgJudgeTimeoutRepository > judgeTimeoutRepository;
    private final Supplier< KnowledgeGraphService > kgService;

    public KgJudgeAdminHandlers( final Supplier< JudgeRunner > judgeRunner,
                                  final Supplier< KgJudgeTimeoutRepository > judgeTimeoutRepository,
                                  final Supplier< KnowledgeGraphService > kgService ) {
        this.judgeRunner = judgeRunner;
        this.judgeTimeoutRepository = judgeTimeoutRepository;
        this.kgService = kgService;
    }

    public void handleGetJudge( final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length < 2 || !"status".equals( segments[1] ) ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Expected: /judge/status" );
            return;
        }
        final JudgeRunner runner = judgeRunner.get();
        final KnowledgeGraphService svc = kgService.get();
        final long depth = svc == null ? 0L : svc.countPendingUnjudgedProposals();
        if ( runner == null ) {
            AdminKnowledgeIo.sendJson( response, Map.of( "configured", false, "in_flight", false, "queue_depth", depth ) );
            return;
        }
        final JudgeRunner.Status s = runner.status( depth );
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "configured", true );
        body.put( "in_flight", s.inFlight() );
        body.put( "last_run_submitted", s.lastRunSubmitted() );
        body.put( "last_run_completed", s.lastRunCompleted() );
        // Use empty strings (not null) for "not applicable" timestamp / error fields.
        // Default Gson omits null map values, which makes the keys disappear from
        // the response body and breaks clients that probe for their presence.
        body.put( "last_run_started_at",
            s.lastRunStartedAt() != null ? s.lastRunStartedAt().toString() : "" );
        body.put( "last_run_finished_at",
            s.lastRunFinishedAt() != null ? s.lastRunFinishedAt().toString() : "" );
        body.put( "last_run_error", s.lastRunError() != null ? s.lastRunError() : "" );
        body.put( "queue_depth", s.queueDepth() );
        AdminKnowledgeIo.sendJson( response, body );
    }

    public void handlePostJudge( final HttpServletRequest request,
                                  final HttpServletResponse response,
                                  final String[] segments ) throws IOException {
        if ( segments.length < 2 || !"run".equals( segments[1] ) ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Expected: /judge/run" );
            return;
        }
        final JudgeRunner runner = judgeRunner.get();
        if ( runner == null ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "judge runner not configured" );
            return;
        }
        final Thread t = new Thread( runner::runOnceQuietly, "kg-judge-adhoc" );
        t.setDaemon( true );
        t.start();
        response.setStatus( HttpServletResponse.SC_ACCEPTED );
        AdminKnowledgeIo.sendJson( response, Map.of( "status", "started" ) );
    }

    /**
     * GET /admin/knowledge-graph/judge-timeouts?limit=N
     *
     * Lists chronic-timeout proposals (those that have read-timed out the LLM
     * judge at least once), sorted by timeout count desc. Each entry is enriched
     * with the proposal's source/target/relationship so the admin can decide
     * whether to manually approve or reject without an extra round-trip.
     */
    public void handleGetJudgeTimeouts( final KnowledgeGraphService svc,
                                         final HttpServletRequest request,
                                         final HttpServletResponse response ) throws IOException {
        final KgJudgeTimeoutRepository repo = judgeTimeoutRepository.get();
        if ( repo == null ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "judge timeout tracking is not configured" );
            return;
        }
        final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 50 );
        final List< KgJudgeTimeoutRepository.TimeoutRow > rows = repo.listTopChronic( limit );

        final List< Map< String, Object > > out = new ArrayList<>( rows.size() );
        for ( final var r : rows ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "proposal_id", r.proposalId().toString() );
            m.put( "content_sha256", r.contentSha256() );
            m.put( "source_page", r.sourcePage() != null ? r.sourcePage() : "" );
            m.put( "proposal_type", r.proposalType() != null ? r.proposalType() : "" );
            m.put( "model_name", r.modelName() != null ? r.modelName() : "" );
            m.put( "content_bytes", r.contentBytes() );
            m.put( "timeout_count", r.timeoutCount() );
            m.put( "last_error_excerpt", r.lastErrorExcerpt() != null ? r.lastErrorExcerpt() : "" );
            m.put( "base_timeout_seconds", r.baseTimeoutSeconds() );
            m.put( "first_seen", r.firstSeen() != null ? r.firstSeen().toString() : "" );
            m.put( "last_seen",  r.lastSeen()  != null ? r.lastSeen().toString()  : "" );
            // Effective timeout that would be applied on next attempt — surfaces
            // the multiplier admin has been seeing.
            final int multiplier = Math.min( 1 + r.timeoutCount(),
                DefaultKgProposalJudgeService.MAX_TIMEOUT_MULTIPLIER );
            m.put( "next_effective_timeout_seconds", r.baseTimeoutSeconds() * multiplier );
            // Enrich with proposal triple if the proposal still exists. Pending
            // proposals are the actionable ones; if approved/rejected/deleted
            // we still emit the row so the admin has the trail.
            try {
                final com.wikantik.api.knowledge.KgProposal p = svc.getProposal( r.proposalId() );
                if ( p != null ) {
                    final Map< String, Object > pd = p.proposedData();
                    final Map< String, Object > triple = new LinkedHashMap<>();
                    triple.put( "source", pd.get( "source" ) );
                    triple.put( "target", pd.get( "target" ) );
                    triple.put( "relationship", pd.get( "relationship" ) );
                    m.put( "proposal", Map.of(
                        "status", p.status() != null ? p.status() : "",
                        "tier", p.tier() != null ? p.tier() : "",
                        "confidence", p.confidence(),
                        "triple", triple ) );
                } else {
                    m.put( "proposal", Map.of( "status", "missing" ) );
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "judge-timeouts: proposal lookup failed for {}: {}",
                    r.proposalId(), e.getMessage() );
                m.put( "proposal", Map.of( "status", "lookup_error" ) );
            }
            out.add( m );
        }
        AdminKnowledgeIo.sendJson( response, Map.of( "timeouts", out ) );
    }

    /**
     * DELETE /admin/knowledge-graph/judge-timeouts/{proposal_id}
     *
     * Clears a tracked timeout — useful when the admin has manually resolved
     * the underlying problem (rephrased the proposal, restarted Ollama, etc.)
     * and wants the next call to start fresh at the base timeout.
     */
    public void handleDeleteJudgeTimeout( final HttpServletResponse response,
                                           final String[] segments ) throws IOException {
        final KgJudgeTimeoutRepository repo = judgeTimeoutRepository.get();
        if ( repo == null ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "judge timeout tracking is not configured" );
            return;
        }
        final UUID id = AdminKnowledgeIo.parseUuid( segments[ 1 ], response );
        if ( id == null ) return;
        repo.clear( id );
        AdminKnowledgeIo.sendJson( response, Map.of( "status", "cleared", "proposal_id", id.toString() ) );
    }
}
