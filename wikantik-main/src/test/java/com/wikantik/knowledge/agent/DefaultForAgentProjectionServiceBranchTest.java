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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.PreferredPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link DefaultForAgentProjectionService} branches
 * {@link DefaultForAgentProjectionServiceTest} doesn't reach: each of the four
 * inner extractor/adapter helpers throwing (via the package-private
 * all-collaborators constructor added for exactly this purpose — none of the four
 * are otherwise injectable, and none have a natural failure trigger from
 * well-formed input), the runbook-reference-resolution predicates (both the
 * canonical-id lookup itself throwing, and the page-title lookup being caught
 * separately), a genuine hub-summary overlay firing, the overlay-threw branch, the
 * cluster-lookup-threw branch of {@code isClusterHub}, and both cache read/write
 * failure branches.
 */
class DefaultForAgentProjectionServiceBranchTest {

    private StructuralIndexService idx;
    private PageManager pm;
    private CachingManager cache;

    private static final PageDescriptor SIMPLE_PAGE = new PageDescriptor(
        "01ABC", "SomePage", "Some Page", PageType.ARTICLE,
        null, List.of(), "summary", Instant.parse( "2026-04-22T11:10:00Z" ), Optional.empty(), false );

    @BeforeEach
    void setUp() {
        idx = mock( StructuralIndexService.class );
        pm = mock( PageManager.class );
        cache = mock( CachingManager.class );
        when( cache.enabled( anyString() ) ).thenReturn( false );
        when( idx.getByCanonicalId( "01ABC" ) ).thenReturn( Optional.of( SIMPLE_PAGE ) );
        when( idx.verificationOf( "01ABC" ) ).thenReturn( Optional.empty() );
        when( pm.getPureText( "SomePage", -1 ) ).thenReturn( "" );
        when( pm.getVersionHistory( "SomePage" ) ).thenReturn( List.of() );
    }

    @Test
    void headingsExtractorFailureIsCaughtAndRecordedAsMissing() {
        final HeadingsOutlineExtractor throwing = mock( HeadingsOutlineExtractor.class );
        when( throwing.extract( any() ) ).thenThrow( new RuntimeException( "headings boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null,
            throwing, new KeyFactsExtractor(), new McpToolHintsResolver(), new RecentChangesAdapter( pm ) );

        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();
        assertTrue( p.headingsOutline().isEmpty() );
        assertTrue( p.missingFields().contains( "headings_outline" ) );
        assertTrue( p.degraded() );
    }

    @Test
    void keyFactsExtractorFailureIsCaughtAndRecordedAsMissing() {
        final KeyFactsExtractor throwing = mock( KeyFactsExtractor.class );
        when( throwing.extract( any(), any() ) ).thenThrow( new RuntimeException( "key facts boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null,
            new HeadingsOutlineExtractor(), throwing, new McpToolHintsResolver(), new RecentChangesAdapter( pm ) );

        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();
        assertTrue( p.keyFacts().isEmpty() );
        assertTrue( p.missingFields().contains( "key_facts" ) );
        assertTrue( p.degraded() );
    }

    @Test
    void recentChangesAdapterFailureIsCaughtAndRecordedAsMissing() {
        final RecentChangesAdapter throwing = mock( RecentChangesAdapter.class );
        when( throwing.recentChanges( anyString(), anyInt() ) ).thenThrow( new RuntimeException( "recents boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null,
            new HeadingsOutlineExtractor(), new KeyFactsExtractor(), new McpToolHintsResolver(), throwing );

        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();
        assertTrue( p.recentChanges().isEmpty() );
        assertTrue( p.missingFields().contains( "recent_changes" ) );
        assertTrue( p.degraded() );
    }

    @Test
    void toolHintsResolverFailureIsCaughtAndRecordedAsMissing() {
        final McpToolHintsResolver throwing = mock( McpToolHintsResolver.class );
        when( throwing.resolve( any(), any(), any() ) ).thenThrow( new RuntimeException( "hints boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null,
            new HeadingsOutlineExtractor(), new KeyFactsExtractor(), throwing, new RecentChangesAdapter( pm ) );

        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();
        assertTrue( p.mcpToolHints().isEmpty() );
        assertTrue( p.missingFields().contains( "mcp_tool_hints" ) );
        assertTrue( p.degraded() );
    }

    @Test
    void runbookReferenceCanonicalIdLookupThrowing_failsTheWholeRunbookBlock() {
        final PageDescriptor runbookPage = new PageDescriptor(
            "01RB", "SomeRunbook", "Some Runbook", PageType.RUNBOOK,
            null, List.of(), null, Instant.now(), Optional.empty(), false );
        when( idx.getByCanonicalId( "01RB" ) ).thenReturn( Optional.of( runbookPage ) );
        when( idx.verificationOf( "01RB" ) ).thenReturn( Optional.empty() );
        when( pm.getVersionHistory( "SomeRunbook" ) ).thenReturn( List.of() );
        when( pm.getPureText( "SomeRunbook", -1 ) ).thenReturn(
            "---\n" +
            "type: runbook\n" +
            "runbook:\n" +
            "  when_to_use:\n" +
            "    - x\n" +
            "  steps:\n" +
            "    - one\n" +
            "    - two\n" +
            "  pitfalls:\n" +
            "    - (none known)\n" +
            "  references:\n" +
            "    - SomeReference\n" +
            "---\n" );
        // The canonical-id lookup is called with no try/catch around it in buildRunbook —
        // a throw here must be caught by buildRunbook's own outer catch.
        when( idx.getByCanonicalId( "SomeReference" ) ).thenThrow( new RuntimeException( "index boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null );
        final ForAgentProjection p = svc.project( "01RB" ).orElseThrow();

        assertNull( p.runbook(), "an exception during reference resolution must leave runbook null" );
        assertTrue( p.missingFields().contains( "runbook" ) );
        assertTrue( p.degraded() );
    }

    @Test
    void runbookReferencePageTitleLookupThrowing_isCaughtAndTreatedAsUnresolved() throws Exception {
        final PageDescriptor runbookPage = new PageDescriptor(
            "01RB", "SomeRunbook", "Some Runbook", PageType.RUNBOOK,
            null, List.of(), null, Instant.now(), Optional.empty(), false );
        when( idx.getByCanonicalId( "01RB" ) ).thenReturn( Optional.of( runbookPage ) );
        when( idx.verificationOf( "01RB" ) ).thenReturn( Optional.empty() );
        when( pm.getVersionHistory( "SomeRunbook" ) ).thenReturn( List.of() );
        when( pm.getPureText( "SomeRunbook", -1 ) ).thenReturn(
            "---\n" +
            "type: runbook\n" +
            "runbook:\n" +
            "  when_to_use:\n" +
            "    - x\n" +
            "  steps:\n" +
            "    - one\n" +
            "    - two\n" +
            "  pitfalls:\n" +
            "    - (none known)\n" +
            "  references:\n" +
            "    - SomeReference\n" +
            "---\n" );
        when( idx.getByCanonicalId( "SomeReference" ) ).thenReturn( Optional.empty() );
        when( pm.pageExists( "SomeReference" ) ).thenThrow( new RuntimeException( "pageExists boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null );
        final ForAgentProjection p = svc.project( "01RB" ).orElseThrow();

        // pageExists() throwing is caught and treated as "does not resolve", so the
        // reference is unresolvable -> REFERENCE_UNRESOLVABLE issue -> runbook null.
        assertNull( p.runbook() );
        assertTrue( p.missingFields().contains( "runbook" ) );
    }

    @Test
    void runbookReferenceResolvesViaPageTitle_noIssueRecorded() throws Exception {
        final PageDescriptor runbookPage = new PageDescriptor(
            "01RB", "SomeRunbook", "Some Runbook", PageType.RUNBOOK,
            null, List.of(), null, Instant.now(), Optional.empty(), false );
        when( idx.getByCanonicalId( "01RB" ) ).thenReturn( Optional.of( runbookPage ) );
        when( idx.verificationOf( "01RB" ) ).thenReturn( Optional.empty() );
        when( pm.getVersionHistory( "SomeRunbook" ) ).thenReturn( List.of() );
        when( pm.getPureText( "SomeRunbook", -1 ) ).thenReturn(
            "---\n" +
            "type: runbook\n" +
            "runbook:\n" +
            "  when_to_use:\n" +
            "    - x\n" +
            "  steps:\n" +
            "    - one\n" +
            "    - two\n" +
            "  pitfalls:\n" +
            "    - (none known)\n" +
            "  references:\n" +
            "    - SomeReference\n" +
            "---\n" );
        // Neither predicate throws; canonical-id lookup misses but the page-title lookup hits,
        // so the reference resolves via the second predicate with no issue recorded.
        when( idx.getByCanonicalId( "SomeReference" ) ).thenReturn( Optional.empty() );
        when( pm.pageExists( "SomeReference" ) ).thenReturn( true );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null );
        final ForAgentProjection p = svc.project( "01RB" ).orElseThrow();

        assertNotNull( p.runbook(), "a reference resolvable by page title must not fail the block" );
        assertFalse( p.missingFields().contains( "runbook" ) );
    }

    @Test
    void staleCitationsQueryFailureIsCaughtAndRecordedAsMissing() {
        final com.wikantik.citation.CitationRepository throwingRepo =
            mock( com.wikantik.citation.CitationRepository.class );
        when( throwingRepo.findBySource( "01ABC" ) ).thenThrow( new RuntimeException( "citations boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, throwingRepo );
        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();

        assertTrue( p.staleCitations().isEmpty() );
        assertTrue( p.missingFields().contains( "stale_citations" ) );
        assertTrue( p.degraded() );
    }

    @Test
    void writeToCacheSucceeds_whenCacheEnabledAndMiss() {
        when( cache.enabled( CachingManager.CACHE_FOR_AGENT ) ).thenReturn( true );
        when( cache.get( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() ) ).thenReturn( null );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null );
        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();

        assertEquals( "01ABC", p.canonicalId() );
        org.mockito.Mockito.verify( cache ).put( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() );
    }

    @Test
    void hubSummaryOverlayFiresForAGenuineHub() {
        final PageDescriptor hubPage = new PageDescriptor(
            "01HUB", "TechHub", "Tech Hub", PageType.HUB,
            "tech", List.of(), "Index of pages on programming languages.",
            Instant.now(), Optional.empty(), false );
        when( idx.getByCanonicalId( "01HUB" ) ).thenReturn( Optional.of( hubPage ) );
        when( idx.verificationOf( "01HUB" ) ).thenReturn( Optional.empty() );
        when( pm.getVersionHistory( "TechHub" ) ).thenReturn( List.of() );
        when( pm.getPureText( "TechHub", -1 ) ).thenReturn( "" );
        when( idx.getCluster( "tech" ) ).thenReturn( Optional.of(
            new ClusterDetails( "tech", hubPage, List.of(), java.util.Map.of(), Instant.now() ) ) );

        final AgentHintsDeriver deriver = mock( AgentHintsDeriver.class );
        when( deriver.derive( "01HUB" ) ).thenReturn( new AgentHintsBlock(
            List.of(),
            List.of( new PreferredPage( "01A", "Java", "article" ),
                     new PreferredPage( "01B", "Python", "article" ) ) ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), deriver, new HubSummarySynthesizer(), null );
        final ForAgentProjection p = svc.project( "01HUB" ).orElseThrow();

        assertTrue( p.summarySynthesized(), "a generic hub summary over a real hub must be overlaid" );
        assertTrue( p.summary().contains( "Java" ) );
        assertFalse( p.degraded() );
    }

    @Test
    void hubSummaryOverlayFailureIsCaughtAndLeavesAuthoredSummaryInPlace() {
        final PageDescriptor hubPage = new PageDescriptor(
            "01HUB", "TechHub", "Tech Hub", PageType.HUB,
            "tech", List.of(), "Index of pages on programming languages.",
            Instant.now(), Optional.empty(), false );
        when( idx.getByCanonicalId( "01HUB" ) ).thenReturn( Optional.of( hubPage ) );
        when( idx.verificationOf( "01HUB" ) ).thenReturn( Optional.empty() );
        when( pm.getVersionHistory( "TechHub" ) ).thenReturn( List.of() );
        when( pm.getPureText( "TechHub", -1 ) ).thenReturn( "" );
        when( idx.getCluster( "tech" ) ).thenReturn( Optional.of(
            new ClusterDetails( "tech", hubPage, List.of(), java.util.Map.of(), Instant.now() ) ) );

        final AgentHintsDeriver deriver = mock( AgentHintsDeriver.class );
        when( deriver.derive( "01HUB" ) ).thenReturn( new AgentHintsBlock(
            List.of(), List.of( new PreferredPage( "01A", "Java", "article" ) ) ) );

        final HubSummarySynthesizer throwingSynth = mock( HubSummarySynthesizer.class );
        when( throwingSynth.maybeOverlay( any(), any(), eq( true ) ) )
            .thenThrow( new RuntimeException( "synth boom" ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), deriver, throwingSynth, null );
        final ForAgentProjection p = svc.project( "01HUB" ).orElseThrow();

        assertFalse( p.summarySynthesized() );
        assertEquals( "Index of pages on programming languages.", p.summary() );
    }

    @Test
    void isClusterHubLookupFailureIsCaughtAndTreatedAsNotAHub() {
        final PageDescriptor page = new PageDescriptor(
            "01P", "SomePage", "Some Page", PageType.ARTICLE,
            "tech", List.of(), "Index of pages on things.",
            Instant.now(), Optional.empty(), false );
        when( idx.getByCanonicalId( "01P" ) ).thenReturn( Optional.of( page ) );
        when( idx.verificationOf( "01P" ) ).thenReturn( Optional.empty() );
        when( pm.getVersionHistory( "SomePage" ) ).thenReturn( List.of() );
        when( pm.getPureText( "SomePage", -1 ) ).thenReturn( "" );
        when( idx.getCluster( "tech" ) ).thenThrow( new RuntimeException( "cluster lookup boom" ) );

        final AgentHintsDeriver deriver = mock( AgentHintsDeriver.class );
        when( deriver.derive( "01P" ) ).thenReturn( new AgentHintsBlock(
            List.of(), List.of( new PreferredPage( "01A", "Java", "article" ) ) ) );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), deriver, new HubSummarySynthesizer(), null );
        final ForAgentProjection p = svc.project( "01P" ).orElseThrow();

        // The cluster lookup threw, so isClusterHub() must treat the page as not a hub,
        // and the overlay must never fire (isHub=false short-circuits maybeOverlay).
        assertFalse( p.summarySynthesized() );
        assertEquals( "Index of pages on things.", p.summary() );
    }

    @Test
    void cacheReadAndWriteFailuresAreCaughtAndDoNotPreventProjection() {
        when( cache.enabled( CachingManager.CACHE_FOR_AGENT ) ).thenReturn( true );
        when( cache.get( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() ) )
            .thenThrow( new RuntimeException( "cache read boom" ) );
        org.mockito.Mockito.doThrow( new RuntimeException( "cache write boom" ) )
            .when( cache ).put( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() );

        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService(
            idx, pm, cache, new ForAgentMetrics(), null, null, null );
        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();

        assertNotNull( p );
        assertEquals( "01ABC", p.canonicalId() );
    }
}
