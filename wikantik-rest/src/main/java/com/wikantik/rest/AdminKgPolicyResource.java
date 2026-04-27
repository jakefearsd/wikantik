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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Engine;
import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
import com.wikantik.api.kgpolicy.PolicyExplanation;
import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.kgpolicy.ReconciliationJobRunner;
import com.wikantik.kgpolicy.ReconciliationStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /admin/kg-policy/*} — read-only admin endpoints for the KG inclusion policy.
 *
 * <p>Provides cluster policy listings, per-cluster detail, policy explanation,
 * pending-review queue, audit history, reconciliation job status, and a
 * page-count estimate for a proposed policy change.</p>
 */
public class AdminKgPolicyResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminKgPolicyResource.class );

    private static final long STALE_DAYS_DEFAULT = 90;

    private Engine engineOverride;

    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }

    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        try {
            switch ( path ) {
                case "/clusters"       -> doListClusters( resp );
                case "/pending"        -> doPending( resp );
                case "/audit"          -> doAudit( req, resp );
                case "/reconciliation" -> doReconciliation( resp );
                case "/estimate"       -> doEstimate( req, resp );
                default -> {
                    if ( path.startsWith( "/clusters/" ) ) {
                        doClusterDetail( path.substring( "/clusters/".length() ), resp );
                    } else if ( path.startsWith( "/explain/" ) ) {
                        doExplain( path.substring( "/explain/".length() ), resp );
                    } else {
                        resp.setStatus( 404 );
                        resp.setContentType( "application/json; charset=UTF-8" );
                        resp.getWriter().write( "{\"error\":\"unknown path\"}" );
                    }
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "kg-policy GET {} failed: {}", path, e.getMessage() );
            resp.setStatus( 500 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"" + e.getMessage().replace( '"', ' ' ) + "\"}" );
        }
    }

    private void doListClusters( final HttpServletResponse resp ) throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        final StructuralIndexService struct = engine().getManager( StructuralIndexService.class );
        if ( policy == null || struct == null ) {
            unavailable( resp );
            return;
        }

        final Map< String, ClusterPolicy > byName = new HashMap<>();
        for ( final ClusterPolicy p : policy.listClusterPolicies() ) {
            byName.put( p.cluster(), p );
        }

        final JsonArray rows = new JsonArray();
        for ( final ClusterSummary cs : struct.listClusters() ) {
            final ClusterPolicy p = byName.get( cs.name() );
            final JsonObject row = new JsonObject();
            row.addProperty( "cluster",     cs.name() );
            row.addProperty( "page_count",  cs.articleCount() );
            row.addProperty( "action",      p == null ? null : p.action().wire() );
            row.addProperty( "reason",      p == null ? null : p.reason() );
            row.addProperty( "set_by",      p == null ? null : p.setBy() );
            row.addProperty( "set_at",      p == null || p.setAt() == null ? null : p.setAt().toString() );
            row.addProperty( "reviewed_at", p == null || p.reviewedAt() == null ? null : p.reviewedAt().toString() );
            rows.add( row );
        }

        final JsonObject env = new JsonObject();
        env.add( "clusters", rows );
        write( resp, env );
    }

    private void doClusterDetail( final String cluster, final HttpServletResponse resp ) throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) {
            unavailable( resp );
            return;
        }

        final Optional< ClusterPolicy > p = policy.getClusterPolicy( cluster );
        final JsonObject env = new JsonObject();
        if ( p.isPresent() ) {
            final ClusterPolicy cp = p.get();
            env.addProperty( "cluster",     cp.cluster() );
            env.addProperty( "action",      cp.action().wire() );
            env.addProperty( "reason",      cp.reason() );
            env.addProperty( "set_by",      cp.setBy() );
            env.addProperty( "set_at",      cp.setAt() == null ? null : cp.setAt().toString() );
            env.addProperty( "reviewed_at", cp.reviewedAt() == null ? null : cp.reviewedAt().toString() );
        } else {
            env.addProperty( "cluster", cluster );
            env.add( "action", null );
        }

        final JsonArray audits = new JsonArray();
        for ( final PolicyAuditEntry a : policy.listAudit( Optional.of( cluster ), 50 ) ) {
            audits.add( auditJson( a ) );
        }
        env.add( "audit", audits );
        write( resp, env );
    }

    private void doExplain( final String idOrName, final HttpServletResponse resp ) throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) {
            unavailable( resp );
            return;
        }
        try {
            final PolicyExplanation x = policy.explain( idOrName );
            write( resp, explanationJson( x ) );
        } catch ( final IllegalArgumentException nf ) {
            resp.setStatus( 404 );
            resp.setContentType( "application/json; charset=UTF-8" );
            final JsonObject err = new JsonObject();
            err.addProperty( "error", nf.getMessage() );
            resp.getWriter().write( err.toString() );
        }
    }

    private void doPending( final HttpServletResponse resp ) throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        final StructuralIndexService struct = engine().getManager( StructuralIndexService.class );
        if ( policy == null || struct == null ) {
            unavailable( resp );
            return;
        }

        final Map< String, ClusterPolicy > byName = new HashMap<>();
        for ( final ClusterPolicy p : policy.listClusterPolicies() ) {
            byName.put( p.cluster(), p );
        }

        final JsonArray unset = new JsonArray();
        final JsonArray stale = new JsonArray();
        final Instant cutoff = Instant.now().minus( Duration.ofDays( STALE_DAYS_DEFAULT ) );

        for ( final ClusterSummary cs : struct.listClusters() ) {
            final ClusterPolicy p = byName.get( cs.name() );
            if ( p == null ) {
                final JsonObject row = new JsonObject();
                row.addProperty( "cluster",    cs.name() );
                row.addProperty( "page_count", cs.articleCount() );
                unset.add( row );
                continue;
            }
            final boolean isStale = p.reviewedAt() == null || p.reviewedAt().isBefore( cutoff );
            if ( isStale ) {
                final JsonObject row = new JsonObject();
                row.addProperty( "cluster",     cs.name() );
                row.addProperty( "action",      p.action().wire() );
                row.addProperty( "reviewed_at", p.reviewedAt() == null ? null : p.reviewedAt().toString() );
                stale.add( row );
            }
        }

        final JsonObject env = new JsonObject();
        env.add( "unset_clusters",       unset );
        env.add( "stale_reviews",        stale );
        env.add( "recent_count_changes", new JsonArray() );  // not yet implemented
        write( resp, env );
    }

    private void doAudit( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) {
            unavailable( resp );
            return;
        }
        final Optional< String > cluster = Optional.ofNullable( req.getParameter( "cluster" ) );
        final int limit = parseIntOr( req.getParameter( "limit" ), 100 );
        final List< PolicyAuditEntry > entries = policy.listAudit( cluster, limit );
        final JsonArray rows = new JsonArray();
        for ( final PolicyAuditEntry a : entries ) {
            rows.add( auditJson( a ) );
        }
        final JsonObject env = new JsonObject();
        env.add( "audit", rows );
        write( resp, env );
    }

    private void doReconciliation( final HttpServletResponse resp ) throws IOException {
        final ReconciliationJobRunner runner = engine().getManager( ReconciliationJobRunner.class );
        if ( runner == null ) {
            unavailable( resp );
            return;
        }
        final JsonArray rows = new JsonArray();
        for ( final ReconciliationStatus s : runner.allStatuses().values() ) {
            final JsonObject row = new JsonObject();
            row.addProperty( "cluster",      s.cluster() );
            row.addProperty( "state",        s.state().name() );
            row.addProperty( "total_pages",  s.totalPages() );
            row.addProperty( "processed",    s.processed() );
            row.addProperty( "errors",       s.errors() );
            row.addProperty( "started_at",   s.startedAt() == null ? null : s.startedAt().toString() );
            row.addProperty( "finished_at",  s.finishedAt() == null ? null : s.finishedAt().toString() );
            row.addProperty( "error_message", s.errorMessage() );
            rows.add( row );
        }
        final JsonObject env = new JsonObject();
        env.add( "reconciliation", rows );
        write( resp, env );
    }

    private void doEstimate( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String cluster   = req.getParameter( "cluster" );
        final String actionRaw = req.getParameter( "action" );
        if ( cluster == null || actionRaw == null ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"cluster and action required\"}" );
            return;
        }
        final Optional< ClusterAction > action = ClusterAction.fromWire( actionRaw );
        if ( action.isEmpty() ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"action must be include or exclude\"}" );
            return;
        }
        final StructuralIndexService struct = engine().getManager( StructuralIndexService.class );
        if ( struct == null ) {
            unavailable( resp );
            return;
        }
        // Estimate = page count in the cluster. A richer estimate (entities,
        // edges, LLM cost) is deferred — see Open Extensions in the spec.
        final int pageCount = struct.getCluster( cluster )
                .map( details -> details.articles().size() )
                .orElse( 0 );
        final JsonObject env = new JsonObject();
        env.addProperty( "cluster",    cluster );
        env.addProperty( "action",     action.get().wire() );
        env.addProperty( "page_count", pageCount );
        env.addProperty( "note", "Page-count only; entity/edge/LLM-cost estimates deferred" );
        write( resp, env );
    }

    @Override
    protected void doPut( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        try {
            if ( path.startsWith( "/clusters/" ) ) {
                doSetCluster( path.substring( "/clusters/".length() ), req, resp );
            } else {
                resp.setStatus( 404 );
                resp.setContentType( "application/json; charset=UTF-8" );
                resp.getWriter().write( "{\"error\":\"unknown path\"}" );
            }
        } catch ( final RuntimeException e ) {
            error500( resp, "kg-policy PUT " + path, e );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        try {
            if ( path.startsWith( "/clusters/" ) ) {
                doClearCluster( path.substring( "/clusters/".length() ), req, resp );
            } else {
                resp.setStatus( 404 );
                resp.setContentType( "application/json; charset=UTF-8" );
                resp.getWriter().write( "{\"error\":\"unknown path\"}" );
            }
        } catch ( final RuntimeException e ) {
            error500( resp, "kg-policy DELETE " + path, e );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        try {
            if ( path.equals( "/bootstrap" ) ) {
                doBootstrap( req, resp );
            } else if ( path.endsWith( "/review" ) && path.startsWith( "/clusters/" ) ) {
                final String cluster = path.substring( "/clusters/".length(), path.length() - "/review".length() );
                doMarkReviewed( cluster, req, resp );
            } else {
                resp.setStatus( 404 );
                resp.setContentType( "application/json; charset=UTF-8" );
                resp.getWriter().write( "{\"error\":\"unknown path\"}" );
            }
        } catch ( final IllegalStateException conflict ) {
            resp.setStatus( 409 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"" + safe( conflict.getMessage() ) + "\"}" );
        } catch ( final RuntimeException e ) {
            error500( resp, "kg-policy POST " + path, e );
        }
    }

    private void doSetCluster( final String cluster, final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) { unavailable( resp ); return; }

        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;
        final String actionRaw = optString( body, "action" );
        final Optional< ClusterAction > action = ClusterAction.fromWire( actionRaw );
        if ( action.isEmpty() ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"action must be 'include' or 'exclude'\"}" );
            return;
        }
        final String reason = optString( body, "reason" );
        final String actor = actorOf( req );
        policy.setClusterPolicy( cluster, action.get(), reason, actor );

        final JsonObject env = new JsonObject();
        env.addProperty( "cluster", cluster );
        env.addProperty( "action", action.get().wire() );
        env.addProperty( "reason", reason );
        env.addProperty( "actor", actor );
        write( resp, env );
    }

    private void doClearCluster( final String cluster, final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) { unavailable( resp ); return; }
        final String actor = actorOf( req );
        policy.clearClusterPolicy( cluster, actor );
        final JsonObject env = new JsonObject();
        env.addProperty( "cluster", cluster );
        env.addProperty( "cleared", true );
        env.addProperty( "actor", actor );
        write( resp, env );
    }

    private void doMarkReviewed( final String cluster, final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) { unavailable( resp ); return; }
        final String actor = actorOf( req );
        policy.markReviewed( cluster, actor );
        final JsonObject env = new JsonObject();
        env.addProperty( "cluster", cluster );
        env.addProperty( "reviewed", true );
        env.addProperty( "actor", actor );
        write( resp, env );
    }

    private void doBootstrap( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final KgInclusionPolicy policy = engine().getManager( KgInclusionPolicy.class );
        if ( policy == null ) { unavailable( resp ); return; }
        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;
        final List< String > include = stringList( body.getAsJsonArray( "include" ) );
        final List< String > exclude = stringList( body.getAsJsonArray( "exclude" ) );
        final String reason = optString( body, "reason" );
        final String actor = actorOf( req );
        policy.bootstrap( include, exclude, reason, actor );
        final JsonObject env = new JsonObject();
        env.addProperty( "applied", true );
        env.addProperty( "included", include.size() );
        env.addProperty( "excluded", exclude.size() );
        write( resp, env );
    }

    /* ---- helpers ---- */

    private static String actorOf( final HttpServletRequest req ) {
        final String u = req.getRemoteUser();
        return u == null || u.isBlank() ? "unknown" : u;
    }

    private static JsonObject parseBody( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        try {
            final var element = JsonParser.parseReader( req.getReader() );
            if ( !element.isJsonObject() ) {
                resp.setStatus( 400 );
                resp.setContentType( "application/json; charset=UTF-8" );
                resp.getWriter().write( "{\"error\":\"body must be a JSON object\"}" );
                return null;
            }
            return element.getAsJsonObject();
        } catch ( final RuntimeException e ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"invalid JSON: " + safe( e.getMessage() ) + "\"}" );
            return null;
        }
    }

    private static String optString( final JsonObject body, final String key ) {
        if ( body == null || !body.has( key ) || body.get( key ).isJsonNull() ) return null;
        return body.get( key ).getAsString();
    }

    private static List< String > stringList( final JsonArray arr ) {
        if ( arr == null ) return List.of();
        final List< String > out = new ArrayList<>( arr.size() );
        for ( int i = 0; i < arr.size(); i++ ) out.add( arr.get( i ).getAsString() );
        return out;
    }

    private static String safe( final String s ) {
        return s == null ? "" : s.replace( '"', ' ' );
    }

    private static void error500( final HttpServletResponse resp, final String context, final RuntimeException e )
            throws IOException {
        LOG.warn( "{} failed: {}", context, e.getMessage() );
        resp.setStatus( 500 );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( "{\"error\":\"" + safe( e.getMessage() ) + "\"}" );
    }

    private static JsonObject auditJson( final PolicyAuditEntry a ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "id",         a.id() );
        o.addProperty( "cluster",    a.cluster() );
        o.addProperty( "old_action", a.oldAction() );
        o.addProperty( "new_action", a.newAction() );
        o.addProperty( "reason",     a.reason() );
        o.addProperty( "actor",      a.actor() );
        o.addProperty( "changed_at", a.changedAt() == null ? null : a.changedAt().toString() );
        return o;
    }

    private static JsonObject explanationJson( final PolicyExplanation x ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "canonical_id",        x.canonicalId() );
        o.addProperty( "page_name",           x.pageName() );
        o.addProperty( "cluster",             x.cluster() );
        o.addProperty( "system_page",         x.systemPage() );
        o.addProperty( "frontmatter_override", x.frontmatterOverride().orElse( null ) );
        o.addProperty( "cluster_policy",      x.clusterPolicy().map( ClusterAction::wire ).orElse( null ) );
        o.addProperty( "effective_action",    x.effectiveAction().wire() );
        o.addProperty( "exclusion_reason",    x.exclusionReason().map( er -> er.wire() ).orElse( null ) );
        return o;
    }

    private static void unavailable( final HttpServletResponse resp ) throws IOException {
        resp.setStatus( 503 );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( "{\"error\":\"kg-policy components unavailable\"}" );
    }

    private static void write( final HttpServletResponse resp, final JsonObject json ) throws IOException {
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( json.toString() );
    }

    private static int parseIntOr( final String s, final int fallback ) {
        if ( s == null ) return fallback;
        try { return Integer.parseInt( s ); } catch ( final NumberFormatException e ) { return fallback; }
    }
}
