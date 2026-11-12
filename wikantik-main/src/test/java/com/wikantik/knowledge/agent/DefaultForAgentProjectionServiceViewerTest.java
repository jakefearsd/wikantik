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

import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.PreferredPage;
import com.wikantik.api.citation.CitationRef;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.cache.CachingManager;
import com.wikantik.citation.CitationRepository;
import com.wikantik.citation.CitationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The for-agent projection is cached once for every caller, but the fields derived from OTHER pages (the
 * {@code agent_hints.prefer_pages} drawn from cluster-mates, a hub's synthesized summary that quotes their titles, and
 * {@code stale_citations} that name a target page) must hold only pages the caller may view.
 */
class DefaultForAgentProjectionServiceViewerTest {

    private static final Instant NOW = Instant.parse( "2026-11-01T00:00:00Z" );
    private static final PageDescriptor HUB = page( "01HUB", "PubHub", "Pub Hub", PageType.HUB,
            "Index of pages on public things." );
    private static final PageDescriptor SELF = page( "01SELF", "PublicSelf", "Public Self", PageType.ARTICLE, "s" );
    private static final PageDescriptor HIDDEN = page( "01HIDDEN", "PrivRunbook", "Priv Runbook", PageType.ARTICLE, "s" );
    private static final PageDescriptor OPEN = page( "01OPEN", "OpenSibling", "Open Sibling", PageType.ARTICLE, "s" );

    /** The caller may view everything but the private runbook. */
    private static final Predicate< String > GUEST = slug -> !slug.equals( "PrivRunbook" );

    private DefaultForAgentProjectionService svc;
    private final Map< Serializable, Object > cacheStore = new HashMap<>();

    private static PageDescriptor page( final String id, final String slug, final String title, final PageType type,
                                        final String summary ) {
        return new PageDescriptor( id, slug, title, type, "pub", List.of(), summary, NOW, false );
    }

    @BeforeEach
    void setUp() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        for ( final PageDescriptor d : List.of( HUB, SELF, HIDDEN, OPEN ) ) {
            when( idx.getByCanonicalId( d.canonicalId() ) ).thenReturn( Optional.of( d ) );
            when( idx.resolveSlugFromCanonicalId( d.canonicalId() ) ).thenReturn( Optional.of( d.slug() ) );
            when( idx.verificationOf( d.canonicalId() ) ).thenReturn( Optional.empty() );
        }
        when( idx.getCluster( "pub" ) ).thenReturn( Optional.of(
                new ClusterDetails( "pub", HUB, List.of( SELF, HIDDEN, OPEN ), Map.of(), NOW ) ) );
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( anyString(), eq( -1 ) ) ).thenReturn( "" );
        when( pm.getVersionHistory( anyString() ) ).thenReturn( List.of() );
        final ReferenceManager refs = mock( ReferenceManager.class );
        when( refs.findReferrers( anyString() ) ).thenReturn( Set.of() );

        final CitationRepository citations = mock( CitationRepository.class );
        when( citations.findBySource( "01SELF" ) ).thenReturn( List.of(
                row( "01SELF", "01HIDDEN", "## Router address" ),
                row( "01SELF", "01OPEN", "## Open section" ) ) );

        // A cache that really holds what it is given, so a projection built for one caller is reused for another.
        final CachingManager cache = mock( CachingManager.class );
        when( cache.enabled( CachingManager.CACHE_FOR_AGENT ) ).thenReturn( true );
        when( cache.get( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() ) )
                .thenAnswer( inv -> cacheStore.get( inv.< Serializable >getArgument( 1 ) ) );
        doAnswer( inv -> cacheStore.put( inv.getArgument( 1 ), inv.getArgument( 2 ) ) )
                .when( cache ).put( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() );

        svc = new DefaultForAgentProjectionService( idx, pm, cache, new ForAgentMetrics(),
                new AgentHintsDeriver( idx, pm, refs ), new HubSummarySynthesizer(), citations );
    }

    private static CitationRow row( final String source, final String target, final String heading ) {
        return new CitationRow( 1L, source, target, heading, "span", "hash", "claim", 0, 1,
                CitationStatus.STALE, NOW, NOW, NOW );
    }

    private static List< String > preferredIds( final ForAgentProjection p ) {
        return p.agentHints().prefer_pages().stream().map( PreferredPage::canonical_id ).toList();
    }

    @Test
    void preferPagesHoldOnlyPagesTheCallerMayView() {
        final ForAgentProjection guest = svc.project( "01SELF", GUEST ).orElseThrow();
        assertTrue( preferredIds( guest ).contains( "01OPEN" ), "control: the viewable sibling is preferred" );
        assertFalse( preferredIds( guest ).contains( "01HIDDEN" ), "a hidden cluster-mate was preferred" );
        assertFalse( guest.agentHints().prefer_pages().stream().anyMatch( pp -> "Priv Runbook".equals( pp.title() ) ) );

        assertTrue( preferredIds( svc.project( "01SELF", slug -> true ).orElseThrow() ).contains( "01HIDDEN" ),
                "control: a caller who may view it gets it" );
    }

    @Test
    void aProjectionCachedForOneCallerIsFilteredForTheNext() {
        svc.project( "01SELF", slug -> true ).orElseThrow();
        final ForAgentProjection guest = svc.project( "01SELF", GUEST ).orElseThrow();
        assertFalse( preferredIds( guest ).contains( "01HIDDEN" ), "the cached admin projection was served to a guest" );
        assertFalse( guest.staleCitations().stream().anyMatch( c -> "01HIDDEN".equals( c.targetCanonicalId() ) ) );
    }

    @Test
    void staleCitationsNameOnlyTargetsTheCallerMayView() {
        final List< CitationRef > guest = svc.project( "01SELF", GUEST ).orElseThrow().staleCitations();
        assertEquals( List.of( "01OPEN" ), guest.stream().map( CitationRef::targetCanonicalId ).toList() );

        assertEquals( 2, svc.project( "01SELF", slug -> true ).orElseThrow().staleCitations().size(),
                "control: both citations for a caller who may view both targets" );
    }

    @Test
    void hubSummaryQuotesOnlyTitlesTheCallerMayView() {
        final ForAgentProjection guest = svc.project( "01HUB", GUEST ).orElseThrow();
        assertTrue( guest.summarySynthesized() );
        assertTrue( guest.summary().contains( "Open Sibling" ), guest.summary() );
        assertFalse( guest.summary().contains( "Priv Runbook" ), "the hub summary quoted a hidden title" );

        assertTrue( svc.project( "01HUB", slug -> true ).orElseThrow().summary().contains( "Priv Runbook" ),
                "control: the synthesized summary quotes it for a caller who may view it" );
    }
}
