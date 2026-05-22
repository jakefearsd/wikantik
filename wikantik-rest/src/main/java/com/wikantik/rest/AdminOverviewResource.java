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
import com.wikantik.rest.overview.MetricReads;
import com.wikantik.rest.overview.OverviewAssembler;
import com.wikantik.rest.overview.OverviewSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code GET /admin/overview} — a single read-only dashboard payload that
 * aggregates already-collected signals (Micrometer metrics, in-memory
 * snapshots, DB counts) into one envelope. Mutates nothing.
 *
 * <p>Each card has its own collector running under per-card try/catch in
 * {@link OverviewAssembler}: a source that is {@code null} or throws degrades
 * only its own card (its key lands in {@code degraded}), never the whole
 * page. Auth is via the shared {@code AdminAuthFilter}.</p>
 */
public class AdminOverviewResource extends RestServletBase {

    private static final long serialVersionUID = 1L;

    /** Test seam — {@code doGet} is protected on the servlet base. */
    void doGetForTesting( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        doGet( req, resp );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final MeterRegistry reg = com.wikantik.api.observability.MeterRegistryHolder.get();

        final OverviewSnapshot snapshot = new OverviewAssembler( collectors( reg ) ).assemble();

        final JsonObject data = snapshot.cards();
        final JsonArray degraded = new JsonArray();
        for ( final String key : snapshot.degraded() ) {
            degraded.add( key );
        }
        data.add( "degraded", degraded );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );

        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( envelope.toString() );
    }

    /**
     * Builds the ordered collector map. Each supplier performs ALL of its source
     * access inside the lambda so the assembler can degrade a single card when
     * its source is unavailable. No collector body wraps itself in try/catch —
     * the assembler owns that and logs a {@code LOG.warn} per failure.
     */
    private Map< String, Supplier< JsonObject > > collectors( final MeterRegistry reg ) {
        final Map< String, Supplier< JsonObject > > c = new LinkedHashMap<>();

        // health — status is "Healthy" when an engine is wired; version from Release.
        c.put( "health", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "status", getEngine() != null ? "Healthy" : "Unknown" );
            o.addProperty( "version", com.wikantik.api.Release.getVersionString() );
            return o;
        } );

        // load (metric-backed)
        c.put( "load", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "inflight",  (int) MetricReads.gauge( reg, "wikantik_backpressure.inflight", 0 ) );
            o.addProperty( "permitsMax", (int) MetricReads.gauge( reg, "wikantik_backpressure.permits_max", 0 ) );
            o.addProperty( "rejected",  (long) MetricReads.counter( reg, "wikantik_backpressure.rejected_total", 0 ) );
            return o;
        } );

        // llmActivity (in-memory snapshot)
        c.put( "llmActivity", () -> {
            final var snap = com.wikantik.llm.activity.LlmActivityLogHolder.get().snapshot( Integer.MAX_VALUE, null, null );
            final JsonObject o = new JsonObject();
            o.addProperty( "inFlight", snap.inFlight() );
            o.addProperty( "windowMinutes", snap.windowMinutes() );
            o.addProperty( "capacity", snap.maxRecords() );
            o.addProperty( "count", snap.calls().size() );
            o.addProperty( "errors", (int) snap.calls().stream().filter( v -> "ERROR".equals( String.valueOf( v.status() ) ) ).count() );
            return o;
        } );

        // kgProposals (DB count) — pending proposals waiting for curation.
        c.put( "kgProposals", () -> {
            final long pending = getSubsystems().knowledge().kgService()
                .countProposals( "pending", null, null, false, null );
            final JsonObject o = new JsonObject();
            o.addProperty( "pending", pending );
            return o;
        } );

        // retrieval — latest run's nDCG@5 via the retrieval-quality runner.
        c.put( "retrieval", () -> {
            final var runner = getSubsystems().knowledge().retrievalQualityRunner();
            final var rows = runner.recentRuns( null, null, 1 );
            final JsonObject o = new JsonObject();
            final Double ndcg5 = rows.isEmpty() ? null : rows.get( 0 ).ndcgAt5();
            if ( ndcg5 != null ) {
                o.addProperty( "ndcg5", ndcg5 );
            }
            return o;
        } );

        // searchIndex — content-index snapshot (pages, lucene, chunks, embeddings).
        c.put( "searchIndex", () -> {
            final var snap = getSubsystems().pageGraph().contentIndexRebuildService().snapshot();
            final int total = snap.pages().total();
            final JsonObject o = new JsonObject();
            o.addProperty( "indexable", snap.pages().indexable() );
            o.addProperty( "total", total );
            o.addProperty( "luceneDocs", snap.lucene().documentsIndexed() );
            o.addProperty( "queueDepth", snap.lucene().queueDepth() );
            o.addProperty( "totalChunks", snap.chunks().totalChunks() );
            o.addProperty( "avgTokens", snap.chunks().avgTokens() );
            o.addProperty( "missingChunks", snap.chunks().pagesMissingChunks() );
            o.addProperty( "embeddingsPct", total > 0 ? snap.embeddings().rowCount() * 100L / total : 0 );
            o.addProperty( "vectorIndexSize",
                (long) MetricReads.gauge( reg, "wikantik.search.hybrid.vector_index.size", 0 ) );
            return o;
        } );

        // users — user count, active API keys.
        c.put( "users", () -> {
            final java.security.Principal[] users;
            try {
                users = getSubsystems().auth().users().getUserDatabase().getWikiNames();
            } catch ( final com.wikantik.auth.WikiSecurityException e ) {
                // Re-thrown unchecked so the assembler degrades only this card.
                throw new RuntimeException( "user enumeration failed", e );
            }
            final long locked;
            try {
                locked = getSubsystems().auth().users().getUserDatabase().countLockedUsers();
            } catch ( final com.wikantik.auth.WikiSecurityException e ) {
                // Re-thrown unchecked so the assembler degrades only this card.
                throw new RuntimeException( "locked-user count failed", e );
            }
            final var keys = com.wikantik.auth.apikeys.ApiKeyServiceHolder
                .get( getSubsystems().core().properties().asProperties() ).list();
            final long active = keys.stream().filter( com.wikantik.auth.apikeys.ApiKeyService.Record::isActive ).count();
            final JsonObject o = new JsonObject();
            o.addProperty( "users", users.length );
            o.addProperty( "locked", locked );
            o.addProperty( "apiKeys", active );
            return o;
        } );

        // recent — top 8 LLM call summaries as strings.
        c.put( "recent", () -> {
            final var snap = com.wikantik.llm.activity.LlmActivityLogHolder.get().snapshot( 8, null, null );
            final JsonArray items = new JsonArray();
            for ( final var v : snap.calls() ) {
                items.add( String.valueOf( v.operation() ) + " " + String.valueOf( v.subsystem() ) );
            }
            final JsonObject o = new JsonObject();
            o.add( "items", items );
            return o;
        } );

        // kgSize — node and edge totals.
        c.put( "kgSize", () -> {
            final var svc = getSubsystems().knowledge().kgService();
            final long nodes = svc.countNodes( new LinkedHashMap<>(), null );
            final long edges = svc.countEdges( null, null, null );
            final long stubs = svc.countStubNodes();
            final long orphans = svc.countOrphanedNodes( new java.util.HashMap<>() );
            final JsonObject o = new JsonObject();
            o.addProperty( "nodes", nodes );
            o.addProperty( "edges", edges );
            o.addProperty( "stubs", stubs );
            o.addProperty( "orphans", orphans );
            return o;
        } );

        // extractor (metric-backed)
        c.put( "extractor", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "requests", (long) MetricReads.counter( reg, "wikantik_kg_extractor_requests_total", 0 ) );
            o.addProperty( "triples",  (long) MetricReads.counter( reg, "wikantik_kg_extractor_triples_emitted_total", 0 ) );
            o.addProperty( "failures", (long) MetricReads.counter( reg, "wikantik_kg_extractor_failures_total", 0 ) );
            return o;
        } );

        // judge (metric-backed + DB count)
        c.put( "judge", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "timeouts",     (long) MetricReads.counter( reg, "wikantik.kg_judge.timeouts", 0 ) );
            o.addProperty( "shortCircuit", (long) MetricReads.counter( reg, "wikantik.kg_judge.short_circuit_total", 0 ) );
            o.addProperty( "pending", getSubsystems().knowledge().kgService().countPendingUnjudgedProposals() );
            return o;
        } );

        // renderCache (metric-backed)
        c.put( "renderCache", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "hits",      (long) MetricReads.counter( reg, "wikantik_cache.hits", 0 ) );
            o.addProperty( "misses",    (long) MetricReads.counter( reg, "wikantik_cache.misses", 0 ) );
            o.addProperty( "evictions", (long) MetricReads.counter( reg, "wikantik_cache.evictions", 0 ) );
            o.addProperty( "size",      (long) MetricReads.gauge( reg, "wikantik_cache.size", 0 ) );
            return o;
        } );

        // auth (metric-backed) — tagged success / failure split.
        c.put( "auth", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "logins", (long) MetricReads.counter( reg, "wikantik.auth.logins", "result", "success", 0 ) );
            o.addProperty( "failed", (long) MetricReads.counter( reg, "wikantik.auth.logins", "result", "failure", 0 ) );
            return o;
        } );

        // agentSurface (metric-backed)
        c.put( "agentSurface", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "hubSynthesis", (long) MetricReads.counter( reg, "wikantik_hub_summary_synthesis_total", 0 ) );
            o.addProperty( "hintFailures", (long) MetricReads.counter( reg, "wikantik_agent_hints_derivation_failures_total", 0 ) );
            o.addProperty( "forAgentBytes", (long) MetricReads.summaryMean( reg, "wikantik_for_agent_response_bytes", 0 ) );
            o.addProperty( "forAgentCount", MetricReads.summaryCount( reg, "wikantik_for_agent_response_bytes", 0 ) );
            return o;
        } );

        // contentQuality — page verification distribution from the structural index.
        c.put( "contentQuality", () -> {
            final var counts = getSubsystems().pageGraph().structuralIndexService().verificationCounts();
            final JsonObject o = new JsonObject();
            o.addProperty( "authoritative", counts.authoritative() );
            o.addProperty( "provisional", counts.provisional() );
            o.addProperty( "stale", counts.stale() );
            o.addProperty( "noVerification", counts.noVerification() );
            return o;
        } );

        // retrievalModes — latest nDCG@5 per retrieval mode (omit modes with no run).
        c.put( "retrievalModes", () -> {
            final var runner = getSubsystems().knowledge().retrievalQualityRunner();
            final JsonObject o = new JsonObject();
            final var modes = new java.util.LinkedHashMap< String, com.wikantik.api.eval.RetrievalMode >();
            modes.put( "bm25", com.wikantik.api.eval.RetrievalMode.BM25 );
            modes.put( "hybrid", com.wikantik.api.eval.RetrievalMode.HYBRID );
            modes.put( "hybridGraph", com.wikantik.api.eval.RetrievalMode.HYBRID_GRAPH );
            for ( final var entry : modes.entrySet() ) {
                final var rows = runner.recentRuns( null, entry.getValue(), 1 );
                if ( !rows.isEmpty() && rows.get( 0 ).ndcgAt5() != null ) {
                    o.addProperty( entry.getKey(), rows.get( 0 ).ndcgAt5() );
                }
            }
            return o;
        } );

        // attachments — config-derived (not metrics); omit fields whose property is unset.
        c.put( "attachments", () -> {
            final var props = getSubsystems().core().properties().asProperties();
            final JsonObject o = new JsonObject();
            final String provider = props.getProperty( com.wikantik.api.managers.AttachmentManager.PROP_PROVIDER );
            if ( provider != null && !provider.isBlank() ) {
                o.addProperty( "provider", provider );
            }
            final String maxSize = props.getProperty( com.wikantik.api.managers.AttachmentManager.PROP_MAXSIZE );
            if ( maxSize != null && !maxSize.isBlank() ) {
                o.addProperty( "maxSize", maxSize );
            }
            final String allowed = props.getProperty( com.wikantik.api.managers.AttachmentManager.PROP_ALLOWEDEXTENSIONS );
            if ( allowed != null && !allowed.isBlank() ) {
                o.addProperty( "allowedCount", allowed.split( "," ).length );
            }
            final String forbidden = props.getProperty( com.wikantik.api.managers.AttachmentManager.PROP_FORBIDDENEXTENSIONS );
            if ( forbidden != null && !forbidden.isBlank() ) {
                o.addProperty( "forbiddenCount", forbidden.split( "," ).length );
            }
            return o;
        } );

        return c;
    }
}
