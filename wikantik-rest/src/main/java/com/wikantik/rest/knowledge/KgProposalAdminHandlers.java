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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.ProposalConflictFlags;
import com.wikantik.rest.KnowledgeJsonMapper;

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
 * Proposal-queue admin handlers extracted verbatim from {@code AdminKnowledgeResource}: listing
 * proposals (with per-row conflict-flag enrichment), creating/approving/rejecting/judging a
 * single proposal, and the {@code bulk-action} endpoint that applies one action to a batch of
 * proposal ids.
 * <p>
 * Package-private-in-spirit: the type is {@code public} only because it must be constructed and
 * invoked from {@code com.wikantik.rest.AdminKnowledgeResource}'s dispatch table, which lives in a
 * different package — it is not part of any documented public API of {@code wikantik-rest}.
 */
public final class KgProposalAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( KgProposalAdminHandlers.class );

    /** Hard upper bound on a single page fetch from /admin/knowledge-graph/proposals. */
    private static final int MAX_PROPOSAL_PAGE_SIZE = 500;

    private final Supplier< KgCurationOps > curationOps;

    public KgProposalAdminHandlers( final Supplier< KgCurationOps > curationOps ) {
        this.curationOps = curationOps;
    }

    // --- GET handlers ---

    public void handleGetProposals( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        // GET /admin/knowledge-graph/proposals/{id}/reviews
        if ( segments.length >= 3 && "reviews".equals( segments[2] ) ) {
            final UUID proposalId = AdminKnowledgeIo.parseUuid( segments[1], response );
            if ( proposalId == null ) return;
            AdminKnowledgeIo.sendJson( response, Map.of( "reviews", service.listReviews( proposalId ).stream()
                .map( r -> {
                    final Map< String, Object > m = new LinkedHashMap<>();
                    m.put( "id", r.id().toString() );
                    m.put( "reviewer_kind", r.reviewerKind() );
                    m.put( "reviewer_id", r.reviewerId() );
                    m.put( "verdict", r.verdict() );
                    m.put( "confidence", r.confidence() );
                    m.put( "rationale", r.rationale() != null ? r.rationale() : "" );
                    m.put( "created", r.created().toString() );
                    return m;
                } ).toList() ) );
            return;
        }

        final String status = request.getParameter( "status" );
        final String sourcePage = request.getParameter( "source_page" );
        final String tier = request.getParameter( "tier" );
        final String machineStatus = request.getParameter( "machine_status" );
        final boolean includeMachineRejected = Boolean.parseBoolean(
            request.getParameter( "include_machine_rejected" ) );
        // Server-enforced upper bound — the UI should never request more than
        // a few hundred at a time, but the REST surface guards against
        // pathological queries (single request fetching every proposal).
        final int rawLimit = AdminKnowledgeIo.parseIntParam( request, "limit", 50 );
        final int limit = Math.max( 1, Math.min( rawLimit, MAX_PROPOSAL_PAGE_SIZE ) );
        final int offset = Math.max( 0, AdminKnowledgeIo.parseIntParam( request, "offset", 0 ) );

        final List< KgProposal > proposals;
        final long totalCount;
        if ( tier != null || machineStatus != null || includeMachineRejected ) {
            proposals = service.listProposals( status, tier, machineStatus,
                includeMachineRejected, sourcePage, limit, offset );
            totalCount = service.countProposals( status, tier, machineStatus,
                includeMachineRejected, sourcePage );
        } else {
            proposals = service.listProposals( status, sourcePage, limit, offset );
            // The simple overload doesn't expose tier / machine_status filtering;
            // route through the extended count with the equivalent defaults so
            // the totals add up to what the client filtered on.
            totalCount = service.countProposals( status, null, null, false, sourcePage );
        }
        AdminKnowledgeIo.sendJson( response, Map.of(
            "proposals", proposals.stream().map( p -> proposalToMap( service, p ) ).toList(),
            "total_count", totalCount,
            "limit", limit,
            "offset", offset
        ) );
    }

    // --- POST handlers ---

    public void handlePostProposal( final KnowledgeGraphService service,
                                     final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final String[] segments ) throws IOException {
        // POST /admin/knowledge-graph/proposals/bulk-action — handled before any per-id dispatch
        if ( segments.length == 2 && "bulk-action".equals( segments[1] ) ) {
            doBulkProposalAction( service, request, response );
            return;
        }
        // POST /admin/knowledge-graph/proposals — create a new proposal
        if ( segments.length == 1 ) {
            final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
            if ( body == null ) return;
            final String proposalType = AdminKnowledgeIo.getJsonString( body, "proposal_type" );
            if ( proposalType == null || proposalType.isBlank() ) {
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "proposal_type is required" );
                return;
            }
            final String sourcePage = AdminKnowledgeIo.getJsonString( body, "source_page" );
            final Map< String, Object > proposedData = body.has( "proposed_data" )
                    ? AdminKnowledgeIo.GSON.fromJson( body.get( "proposed_data" ), AdminKnowledgeIo.MAP_TYPE ) : Map.of();
            final double confidence = AdminKnowledgeIo.getJsonDouble( body, "confidence", 0.5 );
            final String reasoning = AdminKnowledgeIo.getJsonString( body, "reasoning" );
            final KgProposal proposal = service.submitProposal( proposalType, sourcePage,
                    proposedData, confidence, reasoning );
            AdminKnowledgeIo.sendJson( response, KnowledgeJsonMapper.proposalToMap( proposal ) );
            return;
        }
        // POST /admin/knowledge-graph/proposals/{id}/approve or /reject
        if ( segments.length < 3 ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Expected: /proposals or /proposals/{id}/approve or /proposals/{id}/reject" );
            return;
        }
        final UUID proposalId = AdminKnowledgeIo.parseUuid( segments[1], response );
        if ( proposalId == null ) return;

        final String action = segments[2];
        final String reviewedBy = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";

        final KgCurationOps ops = curationOps.get();
        switch ( action ) {
            case "approve" -> {
                final KgCurationOps.ApproveOutcome outcome = ops.tryApprove( proposalId, reviewedBy );
                if ( outcome.error().isPresent() ) {
                    AdminKnowledgeIo.sendNotFound( response, outcome.error().get() );
                    return;
                }
                final KgProposal approved = service.getProposal( proposalId );
                final Map< String, Object > approveResult = new LinkedHashMap<>( KnowledgeJsonMapper.proposalToMap( approved ) );
                approveResult.put( "warnings", outcome.warnings() );
                AdminKnowledgeIo.sendJson( response, approveResult );
            }
            case "reject" -> {
                final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
                if ( body == null ) return;
                final String reason = AdminKnowledgeIo.getJsonString( body, "reason" );
                final java.util.Optional< String > err = ops.tryRejectProposal( proposalId, reviewedBy, reason );
                if ( err.isPresent() ) {
                    AdminKnowledgeIo.sendNotFound( response, err.get() );
                    return;
                }
                final KgProposal rejected = service.getProposal( proposalId );
                AdminKnowledgeIo.sendJson( response, KnowledgeJsonMapper.proposalToMap( rejected ) );
            }
            case "judge" -> {
                try {
                    final JudgeVerdict v = service.judgeNow( proposalId, reviewedBy );
                    final Map< String, Object > result = new LinkedHashMap<>();
                    result.put( "verdict", v.verdict() );
                    result.put( "confidence", v.confidence() );
                    result.put( "rationale", v.rationale() );
                    result.put( "model", v.model() );
                    AdminKnowledgeIo.sendJson( response, result );
                } catch ( final IllegalStateException e ) {
                    LOG.warn( "judgeNow refused for proposal {}: {}", proposalId, e.getMessage() );
                    AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage() );
                } catch ( final IllegalArgumentException e ) {
                    AdminKnowledgeIo.sendNotFound( response, e.getMessage() );
                }
            }
            default -> AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unknown action: " + action + ". Use 'approve', 'reject', or 'judge'." );
        }
    }

    // --- Bulk proposal action ---

    /**
     * Handles {@code POST /admin/knowledge-graph/proposals/bulk-action}.
     *
     * <p>Request body:
     * {@code { "action": "approve"|"reject"|"judge", "ids": ["uuid1", ...], "reason": "..." }}
     * (reason required for reject at the request level — missing reason → 400).
     *
     * <p>Returns a standard bulk-result envelope:
     * {@code { "succeeded": [...], "failed": [{id, error}], "status": "completed",
     * "message": "N of M proposals approved" }}.
     *
     * <p>Loops over all ids without aborting on first failure.
     * Emits a single audit log entry per bulk call.
     */
    private void doBulkProposalAction( final KnowledgeGraphService service,
                                        final HttpServletRequest request,
                                        final HttpServletResponse response ) throws IOException {
        final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
        if ( body == null ) return;

        final String action = AdminKnowledgeIo.getJsonString( body, "action" );
        if ( action == null || action.isBlank() ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "action is required (supported: approve, reject, judge)" );
            return;
        }
        if ( !"approve".equals( action ) && !"reject".equals( action ) && !"judge".equals( action ) ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unsupported action '" + action + "' — supported: approve, reject, judge" );
            return;
        }

        // For reject: require a top-level reason (request-level invariant, not per-id).
        final String reason;
        if ( "reject".equals( action ) ) {
            reason = AdminKnowledgeIo.getJsonString( body, "reason" );
            if ( reason == null || reason.isBlank() ) {
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "reason is required for action 'reject'" );
                return;
            }
        } else {
            reason = null;
        }

        final JsonElement idsEl = body.get( "ids" );
        if ( idsEl == null || !idsEl.isJsonArray() ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "ids is required and must be a JSON array" );
            return;
        }
        final JsonArray idsArr = idsEl.getAsJsonArray();
        if ( idsArr.isEmpty() ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "ids must not be empty" );
            return;
        }

        final String actor = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";
        final KgCurationOps ops = curationOps.get();
        final List< String > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();
        final Map< String, List< String > > warningsByProposal = new LinkedHashMap<>();

        for ( final JsonElement idEl : idsArr ) {
            final String idStr = idEl.isJsonPrimitive() ? idEl.getAsString() : null;
            if ( idStr == null || idStr.isBlank() ) {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idEl.toString() );
                f.put( "error", "id must be a non-blank string" );
                failed.add( f );
                continue;
            }
            final UUID proposalId;
            try {
                proposalId = UUID.fromString( idStr );
            } catch ( final IllegalArgumentException e ) {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idStr );
                f.put( "error", "Invalid UUID: " + idStr );
                failed.add( f );
                continue;
            }

            if ( "approve".equals( action ) ) {
                final KgCurationOps.ApproveOutcome outcome = ops.tryApprove( proposalId, actor );
                if ( outcome.error().isPresent() ) {
                    final Map< String, Object > f = new LinkedHashMap<>();
                    f.put( "id", idStr );
                    f.put( "error", outcome.error().get() );
                    failed.add( f );
                } else {
                    succeeded.add( idStr );
                    if ( !outcome.warnings().isEmpty() ) {
                        warningsByProposal.put( idStr, outcome.warnings() );
                    }
                }
            } else {
                final java.util.Optional< String > err;
                switch ( action ) {
                    case "reject"  -> err = ops.tryRejectProposal( proposalId, actor, reason );
                    default        -> err = ops.tryJudgeProposal( proposalId, actor );
                }
                if ( err.isEmpty() ) {
                    succeeded.add( idStr );
                } else {
                    final Map< String, Object > f = new LinkedHashMap<>();
                    f.put( "id", idStr );
                    f.put( "error", err.get() );
                    failed.add( f );
                }
            }
        }

        LOG.info( "bulk action={} resource=kg-proposals actor={} attempted={} succeeded={} failed={}",
                action, actor, idsArr.size(), succeeded.size(), failed.size() );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "succeeded", succeeded );
        result.put( "failed", failed );
        result.put( "status", "completed" );
        result.put( "message", succeeded.size() + " of " + idsArr.size() + " proposals " + action + "d" );
        if ( !warningsByProposal.isEmpty() ) {
            result.put( "warnings_by_proposal", warningsByProposal );
        }
        AdminKnowledgeIo.sendJson( response, result );
    }

    /**
     * Serialises a proposal for the admin UI. When {@code service} is non-null,
     * also enriches with two cross-reference flags the queue uses to surface
     * conflicts before the admin acts:
     * <ul>
     *   <li>{@code node_exists} — for {@code new-node} proposals, whether the
     *       proposed name already resolves to an existing KG node (likely
     *       duplicate);</li>
     *   <li>{@code edge_previously_rejected} — for {@code new-edge} proposals,
     *       whether the same {@code (source, target, relationship)} tuple has
     *       been rejected before (so the same suggestion is being made
     *       repeatedly).</li>
     * </ul>
     * Both lookups are best-effort — exceptions are logged at WARN and the
     * flag is omitted rather than failing the whole listing.
     */
    private Map< String, Object > proposalToMap( final KnowledgeGraphService service, final KgProposal p ) {
        final Map< String, Object > map = KnowledgeJsonMapper.proposalToMap( p );
        if ( service != null ) {
            map.putAll( ProposalConflictFlags.forProposal( service, p, true ) );
        }
        return map;
    }
}
