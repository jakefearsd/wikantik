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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiSubsystems;
import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.admin.IndexStatusSnapshot;
import com.wikantik.api.core.Engine;
import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.eval.RetrievalRunResult;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.VerificationCounts;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.core.subsystem.WikiProperties;
import com.wikantik.llm.activity.LlmActivityLog;
import com.wikantik.llm.activity.LlmActivityLogHolder;
import com.wikantik.llm.activity.Subsystem;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the happy-path (and key failure) branches of every
 * {@link AdminOverviewResource} card collector — the baseline
 * {@link AdminOverviewResourceTest} only proves graceful degradation with no
 * engine wired, so none of the real data-shaping logic in
 * {@code collectors(...)} ever ran under test.
 */
class AdminOverviewResourceFullTest {

    private static void installLlmLog( final LlmActivityLog log ) throws Exception {
        final var m = LlmActivityLogHolder.class.getDeclaredMethod( "setForTesting", LlmActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, log );
    }

    @AfterEach
    void tearDown() throws Exception {
        com.wikantik.api.observability.MeterRegistryHolder.clear();
        ApiKeyServiceHolder.setForTesting( null );
        installLlmLog( null );
    }

    private static final class Stub extends AdminOverviewResource {
        private final WikiSubsystems subs;
        Stub( final Engine engine, final WikiSubsystems subs ) {
            setEngine( engine );
            this.subs = subs;
        }
        @Override protected WikiSubsystems getSubsystems() { return subs; }
    }

    private JsonObject invoke( final Engine engine, final WikiSubsystems subs ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        new Stub( engine, subs ).doGetForTesting( req, resp );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_OK );
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    @Test
    void allCardsAssembleWithoutDegradingWhenEverySourceIsHealthy() throws Exception {
        // --- metrics ---
        // Gauges registered via reg.gauge(name, Number) hold only a WEAK reference to the
        // boxed value; without a local strong reference the value can be collected before
        // the servlet reads it, silently reading back as NaN -> 0. Keep the boxed numbers
        // alive for the whole test via gaugeAnchors.
        final List< Number > gaugeAnchors = new java.util.ArrayList<>();
        final MeterRegistry reg = new SimpleMeterRegistry();
        com.wikantik.api.observability.MeterRegistryHolder.set( reg );
        gaugeAnchors.add( reg.gauge( "wikantik_backpressure.inflight", 4 ) );
        gaugeAnchors.add( reg.gauge( "wikantik_backpressure.permits_max", 32 ) );
        reg.counter( "wikantik_backpressure.rejected_total" ).increment( 2 );
        reg.counter( "wikantik_kg_extractor_requests_total" ).increment( 10 );
        reg.counter( "wikantik_kg_extractor_triples_emitted_total" ).increment( 40 );
        reg.counter( "wikantik_kg_extractor_failures_total" ).increment( 1 );
        reg.counter( "wikantik.kg_judge.timeouts" ).increment( 3 );
        reg.counter( "wikantik.kg_judge.short_circuit_total" ).increment( 5 );
        reg.counter( "wikantik_cache.hits" ).increment( 100 );
        reg.counter( "wikantik_cache.misses" ).increment( 8 );
        reg.counter( "wikantik_cache.evictions" ).increment( 2 );
        gaugeAnchors.add( reg.gauge( "wikantik_cache.size", 512 ) );
        reg.counter( "wikantik.auth.logins", "result", "success" ).increment( 20 );
        reg.counter( "wikantik.auth.logins", "result", "failure" ).increment( 4 );
        reg.counter( "wikantik_hub_summary_synthesis_total" ).increment( 6 );
        reg.counter( "wikantik_agent_hints_derivation_failures_total" ).increment( 1 );
        gaugeAnchors.add( reg.gauge( "wikantik.search.hybrid.vector_index.size", 999 ) );
        final DistributionSummary bytes = DistributionSummary.builder( "wikantik_for_agent_response_bytes" ).register( reg );
        bytes.record( 1000 );
        bytes.record( 3000 );

        // --- LLM activity: one ok + one error call ---
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "x" ), "ok" );
        log.fail( log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "y" ),
                new RuntimeException( "boom" ) );
        installLlmLog( log );

        // --- KG service ---
        final KnowledgeGraphService kg = mock( KnowledgeGraphService.class );
        when( kg.countProposals( "pending", null, null, false, null ) ).thenReturn( 5L );
        when( kg.countNodes( anyMap(), any() ) ).thenReturn( 200L );
        when( kg.countEdges( null, null, null ) ).thenReturn( 300L );
        when( kg.countStubNodes() ).thenReturn( 7L );
        when( kg.countOrphanedNodes( anyMap() ) ).thenReturn( 9L );
        when( kg.countPendingUnjudgedProposals() ).thenReturn( 11L );

        // --- retrieval quality runner: overall + per-mode ---
        final RetrievalQualityRunner runner = mock( RetrievalQualityRunner.class );
        when( runner.recentRuns( null, null, 1 ) ).thenReturn(
                List.of( retrievalRow( RetrievalMode.HYBRID, 0.83 ) ) );
        when( runner.recentRuns( null, RetrievalMode.BM25, 1 ) ).thenReturn(
                List.of( retrievalRow( RetrievalMode.BM25, 0.5 ) ) );
        when( runner.recentRuns( null, RetrievalMode.HYBRID, 1 ) ).thenReturn(
                List.of( retrievalRow( RetrievalMode.HYBRID, 0.83 ) ) );
        // HYBRID_GRAPH has no run yet — must be omitted from the JSON, not null-valued.
        when( runner.recentRuns( null, RetrievalMode.HYBRID_GRAPH, 1 ) ).thenReturn( List.of() );

        // --- content index snapshot ---
        final ContentIndexRebuildService indexSvc = mock( ContentIndexRebuildService.class );
        final IndexStatusSnapshot snap = new IndexStatusSnapshot(
                new IndexStatusSnapshot.Pages( 120, 20, 100 ),
                new IndexStatusSnapshot.Lucene( 95, 3, Instant.EPOCH ),
                new IndexStatusSnapshot.Chunks( 90, 10, 450, 80, 5, 300 ),
                new IndexStatusSnapshot.Embeddings( "nomic", 768, 80, Instant.EPOCH ),
                new IndexStatusSnapshot.Rebuild( "IDLE", null, 0, 0, 0, 0, 0, 0, 0, List.of() ) );
        when( indexSvc.snapshot() ).thenReturn( snap );

        // --- structural index (content quality) ---
        final StructuralIndexService structural = mock( StructuralIndexService.class );
        when( structural.verificationCounts() ).thenReturn( new VerificationCounts( 40, 30, 10, 5 ) );

        // --- users + API keys ---
        final UserDatabase userDb = mock( UserDatabase.class );
        when( userDb.getWikiNames() ).thenReturn( new Principal[] {
                new com.wikantik.auth.WikiPrincipal( "alice" ),
                new com.wikantik.auth.WikiPrincipal( "bob" ),
                new com.wikantik.auth.WikiPrincipal( "carol" ) } );
        when( userDb.countLockedUsers() ).thenReturn( 1L );
        final UserManager userManager = mock( UserManager.class );
        when( userManager.getUserDatabase() ).thenReturn( userDb );

        final ApiKeyService apiKeys = mock( ApiKeyService.class );
        final ApiKeyService.Record active = new ApiKeyService.Record(
                1, "hash1", "alice", "laptop", ApiKeyService.Scope.ALL,
                Instant.EPOCH, "alice", null, null, null );
        final ApiKeyService.Record revoked = new ApiKeyService.Record(
                2, "hash2", "bob", "phone", ApiKeyService.Scope.MCP,
                Instant.EPOCH, "bob", null, Instant.EPOCH, "admin" );
        when( apiKeys.list() ).thenReturn( List.of( active, revoked ) );
        ApiKeyServiceHolder.setForTesting( apiKeys );

        // --- attachment config (all four fields present) ---
        final Properties props = new Properties();
        props.setProperty( com.wikantik.api.managers.AttachmentManager.PROP_PROVIDER, "filesystem" );
        props.setProperty( com.wikantik.api.managers.AttachmentManager.PROP_MAXSIZE, "10485760" );
        props.setProperty( com.wikantik.api.managers.AttachmentManager.PROP_ALLOWEDEXTENSIONS, "png,jpg,pdf" );
        props.setProperty( com.wikantik.api.managers.AttachmentManager.PROP_FORBIDDENEXTENSIONS, "exe,bat" );
        final WikiProperties wikiProps = mock( WikiProperties.class );
        when( wikiProps.asProperties() ).thenReturn( props );

        final WikiSubsystems subs = new WikiSubsystems(
                new com.wikantik.core.subsystem.CoreSubsystem.Services(
                        wikiProps, null, null, null, null, null, null, null, null, null, null, null ),
                null,
                new com.wikantik.auth.subsystem.AuthSubsystem.Services(
                        null, null, userManager, null, null, null, null, null ),
                null, null, null,
                new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services(
                        kg, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, runner, null, null ),
                new com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Services(
                        structural, null, null, indexSvc, null, null, null, null ) );

        final Engine engine = mock( Engine.class );

        final JsonObject env = invoke( engine, subs );
        assertEquals( 4, gaugeAnchors.size(), "keep gauge anchors reachable past invoke()" );
        final JsonObject data = env.getAsJsonObject( "data" );

        // No card should have degraded — the strongest possible regression signal:
        // any collector throwing (NPE from a missed wiring, wrong record position, etc.)
        // would show up here immediately.
        assertEquals( 0, data.getAsJsonArray( "degraded" ).size(),
                "degraded: " + data.getAsJsonArray( "degraded" ) );

        final JsonObject health = data.getAsJsonObject( "health" );
        assertEquals( "Healthy", health.get( "status" ).getAsString() );

        final JsonObject load = data.getAsJsonObject( "load" );
        assertEquals( 4, load.get( "inflight" ).getAsInt() );
        assertEquals( 32, load.get( "permitsMax" ).getAsInt() );
        assertEquals( 2, load.get( "rejected" ).getAsInt() );

        final JsonObject llm = data.getAsJsonObject( "llmActivity" );
        assertEquals( 2, llm.get( "count" ).getAsInt() );
        assertEquals( 1, llm.get( "errors" ).getAsInt() );
        assertEquals( 0, llm.get( "inFlight" ).getAsInt() );

        assertEquals( 5, data.getAsJsonObject( "kgProposals" ).get( "pending" ).getAsInt() );
        assertEquals( 0.83, data.getAsJsonObject( "retrieval" ).get( "ndcg5" ).getAsDouble() );

        final JsonObject search = data.getAsJsonObject( "searchIndex" );
        assertEquals( 100, search.get( "indexable" ).getAsInt() );
        assertEquals( 120, search.get( "total" ).getAsInt() );
        assertEquals( 95, search.get( "luceneDocs" ).getAsInt() );
        assertEquals( 3, search.get( "queueDepth" ).getAsInt() );
        assertEquals( 450, search.get( "totalChunks" ).getAsInt() );
        assertEquals( 80, search.get( "avgTokens" ).getAsInt() );
        assertEquals( 10, search.get( "missingChunks" ).getAsInt() );
        assertEquals( 66, search.get( "embeddingsPct" ).getAsLong() ); // 80*100/120
        assertEquals( 999, search.get( "vectorIndexSize" ).getAsLong() );

        final JsonObject users = data.getAsJsonObject( "users" );
        assertEquals( 3, users.get( "users" ).getAsInt() );
        assertEquals( 1, users.get( "locked" ).getAsInt() );
        assertEquals( 1, users.get( "apiKeys" ).getAsInt() ); // only "active" counts

        final var items = data.getAsJsonObject( "recent" ).getAsJsonArray( "items" );
        assertEquals( 2, items.size() );

        final JsonObject kgSize = data.getAsJsonObject( "kgSize" );
        assertEquals( 200, kgSize.get( "nodes" ).getAsInt() );
        assertEquals( 300, kgSize.get( "edges" ).getAsInt() );
        assertEquals( 7, kgSize.get( "stubs" ).getAsInt() );
        assertEquals( 9, kgSize.get( "orphans" ).getAsInt() );

        final JsonObject extractor = data.getAsJsonObject( "extractor" );
        assertEquals( 10, extractor.get( "requests" ).getAsInt() );
        assertEquals( 40, extractor.get( "triples" ).getAsInt() );
        assertEquals( 1, extractor.get( "failures" ).getAsInt() );

        final JsonObject judge = data.getAsJsonObject( "judge" );
        assertEquals( 3, judge.get( "timeouts" ).getAsInt() );
        assertEquals( 5, judge.get( "shortCircuit" ).getAsInt() );
        assertEquals( 11, judge.get( "pending" ).getAsInt() );

        final JsonObject cache = data.getAsJsonObject( "renderCache" );
        assertEquals( 100, cache.get( "hits" ).getAsInt() );
        assertEquals( 8, cache.get( "misses" ).getAsInt() );
        assertEquals( 2, cache.get( "evictions" ).getAsInt() );
        assertEquals( 512, cache.get( "size" ).getAsInt() );

        final JsonObject auth = data.getAsJsonObject( "auth" );
        assertEquals( 20, auth.get( "logins" ).getAsInt() );
        assertEquals( 4, auth.get( "failed" ).getAsInt() );

        final JsonObject agent = data.getAsJsonObject( "agentSurface" );
        assertEquals( 6, agent.get( "hubSynthesis" ).getAsInt() );
        assertEquals( 1, agent.get( "hintFailures" ).getAsInt() );
        assertEquals( 2000.0, agent.get( "forAgentBytes" ).getAsDouble() ); // mean of 1000,3000
        assertEquals( 2, agent.get( "forAgentCount" ).getAsInt() );

        final JsonObject quality = data.getAsJsonObject( "contentQuality" );
        assertEquals( 40, quality.get( "authoritative" ).getAsInt() );
        assertEquals( 30, quality.get( "provisional" ).getAsInt() );
        assertEquals( 10, quality.get( "stale" ).getAsInt() );
        assertEquals( 5, quality.get( "noVerification" ).getAsInt() );

        final JsonObject modes = data.getAsJsonObject( "retrievalModes" );
        assertEquals( 0.5, modes.get( "bm25" ).getAsDouble() );
        assertEquals( 0.83, modes.get( "hybrid" ).getAsDouble() );
        assertFalse( modes.has( "hybridGraph" ), "mode with no run must be omitted, not null" );

        final JsonObject attachments = data.getAsJsonObject( "attachments" );
        assertEquals( "filesystem", attachments.get( "provider" ).getAsString() );
        assertEquals( "10485760", attachments.get( "maxSize" ).getAsString() );
        assertEquals( 3, attachments.get( "allowedCount" ).getAsInt() );
        assertEquals( 2, attachments.get( "forbiddenCount" ).getAsInt() );
    }

    @Test
    void usersCardDegradesWhenUserDatabaseThrowsOnGetWikiNames() throws Exception {
        final UserDatabase userDb = mock( UserDatabase.class );
        when( userDb.getWikiNames() ).thenThrow( new WikiSecurityException( "db down" ) );
        final UserManager userManager = mock( UserManager.class );
        when( userManager.getUserDatabase() ).thenReturn( userDb );

        final WikiSubsystems subs = new WikiSubsystems(
                null, null,
                new com.wikantik.auth.subsystem.AuthSubsystem.Services(
                        null, null, userManager, null, null, null, null, null ),
                null, null, null, null, null );

        final JsonObject data = invoke( mock( Engine.class ), subs ).getAsJsonObject( "data" );
        final var degraded = new java.util.HashSet< String >();
        data.getAsJsonArray( "degraded" ).forEach( e -> degraded.add( e.getAsString() ) );
        assertTrue( degraded.contains( "users" ), "users card must degrade when getWikiNames() throws" );
        assertFalse( data.has( "users" ), "a degraded card must not also appear in data" );
    }

    @Test
    void usersCardDegradesWhenUserDatabaseThrowsOnCountLockedUsers() throws Exception {
        final UserDatabase userDb = mock( UserDatabase.class );
        when( userDb.getWikiNames() ).thenReturn( new Principal[] { new com.wikantik.auth.WikiPrincipal( "alice" ) } );
        when( userDb.countLockedUsers() ).thenThrow( new WikiSecurityException( "locked count failed" ) );
        final UserManager userManager = mock( UserManager.class );
        when( userManager.getUserDatabase() ).thenReturn( userDb );

        final WikiSubsystems subs = new WikiSubsystems(
                null, null,
                new com.wikantik.auth.subsystem.AuthSubsystem.Services(
                        null, null, userManager, null, null, null, null, null ),
                null, null, null, null, null );

        final JsonObject data = invoke( mock( Engine.class ), subs ).getAsJsonObject( "data" );
        final var degraded = new java.util.HashSet< String >();
        data.getAsJsonArray( "degraded" ).forEach( e -> degraded.add( e.getAsString() ) );
        assertTrue( degraded.contains( "users" ), "users card must degrade when countLockedUsers() throws" );
    }

    @Test
    void attachmentsCardOmitsBlankOrMissingFields() throws Exception {
        final Properties props = new Properties();
        props.setProperty( com.wikantik.api.managers.AttachmentManager.PROP_PROVIDER, "  " ); // blank
        props.setProperty( com.wikantik.api.managers.AttachmentManager.PROP_ALLOWEDEXTENSIONS, "png" );
        // maxSize + forbidden left entirely unset
        final WikiProperties wikiProps = mock( WikiProperties.class );
        when( wikiProps.asProperties() ).thenReturn( props );

        final WikiSubsystems subs = new WikiSubsystems(
                new com.wikantik.core.subsystem.CoreSubsystem.Services(
                        wikiProps, null, null, null, null, null, null, null, null, null, null, null ),
                null, null, null, null, null, null, null );

        final JsonObject data = invoke( mock( Engine.class ), subs ).getAsJsonObject( "data" );
        final JsonObject attachments = data.getAsJsonObject( "attachments" );
        assertFalse( attachments.has( "provider" ), "blank provider must be omitted" );
        assertFalse( attachments.has( "maxSize" ), "unset maxSize must be omitted" );
        assertTrue( attachments.has( "allowedCount" ) );
        assertEquals( 1, attachments.get( "allowedCount" ).getAsInt() );
        assertFalse( attachments.has( "forbiddenCount" ), "unset forbidden must be omitted" );
    }

    private static RetrievalRunResult retrievalRow( final RetrievalMode mode, final double ndcg5 ) {
        return new RetrievalRunResult( 1L, "core-agent-queries", mode, ndcg5, null, null, null,
                Instant.EPOCH, Instant.EPOCH, 10, 0, false );
    }
}
