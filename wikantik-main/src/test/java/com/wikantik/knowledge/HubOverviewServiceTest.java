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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers( disabledWithoutDocker = true )
class HubOverviewServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private Map< String, String > pageStore; // simple in-memory PageManager backing
    private PageManager pageManager;
    private List< String[] > pageWrites; // captured writes (name, content)
    private HubDiscoveryService.PageWriter pageWriter;
    private TfidfModel model;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );

        pageStore = new HashMap<>();
        pageWrites = new ArrayList<>();
        pageManager = mock( PageManager.class );
        // Default lenient stubs so tests don't error on unstubbed lookups.
        lenient().when( pageManager.pageExists( anyString() ) )
            .thenAnswer( inv -> pageStore.containsKey( (String) inv.getArgument( 0 ) ) );
        lenient().when( pageManager.getPageText( anyString(), anyInt() ) )
            .thenAnswer( inv -> pageStore.get( (String) inv.getArgument( 0 ) ) );
        pageWriter = ( name, content ) -> {
            pageWrites.add( new String[]{ name, content } );
            pageStore.put( name, content );
        };
    }

    /** Builder factory shared across tests. */
    private HubOverviewService.Builder serviceBuilder() {
        return HubOverviewService.builder()
            .kgRepo( kgRepo )
            .pageManager( pageManager )
            .pageWriter( pageWriter )
            .nearMissThreshold( 0.50 )
            .overlapThreshold( 0.60 )
            .nearMissMaxResults( 10 )
            .mltMaxResults( 10 )
            // No-op MLT — overridden per test where needed
            .luceneMlt( ( seed, max, excludes ) -> Collections.emptyList() )
            .contentModel( model );
    }

    // ---- listHubOverviews ----

    @Test
    void listHubOverviews_happyPath_sortsByCoherenceAscending() {
        // Two hubs with distinct coherence: cooking (tight) vs an artificially loose
        // pair where the two members share little vocabulary.
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        seedHub( "GrabBagHub", List.of( "QuantumPhysics", "PetGroomer" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GrabBagHub", "stub" );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting", "QuantumPhysics", "PetGroomer" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough oven baking baking",
                "roasting meat oven temperature seasoning baking oven roast roast",
                "quantum physics wavefunction entanglement schrodinger photon",
                "pet grooming dog cat brushing nails bath fur"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 2, out.size() );
        // GrabBagHub has the lower coherence — it should sort first.
        assertEquals( "GrabBagHub", out.get( 0 ).name() );
        assertEquals( "CookingHub", out.get( 1 ).name() );
        assertTrue( out.get( 0 ).coherence() < out.get( 1 ).coherence(),
            "Expected GrabBagHub coherence < CookingHub coherence" );
        for ( final HubOverviewService.HubOverviewSummary s : out ) {
            assertEquals( 2, s.memberCount() );
            assertTrue( s.hasBackingPage() );
        }
    }

    @Test
    void listHubOverviews_emptyContentModel_returnsEmptyList() {
        // Seed a hub but pass an empty model — service should short-circuit.
        seedHub( "TechHub", List.of( "Java", "Python" ) );
        model = new TfidfModel(); // unbuilt; entityCount == 0

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertTrue( out.isEmpty(), "Expected empty list when content model has 0 entities" );
    }

    @Test
    void listHubOverviews_hubWithAllNonModelMembers_hasNaNCoherenceSortsLast() {
        // CookingHub has 2 model-backed members (will compute coherence).
        // GhostHub has 2 members that are NOT in the model — coherence should be NaN.
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        seedHub( "GhostHub", List.of( "Phantom1", "Phantom2" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GhostHub", "stub" );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough",
                "roasting meat oven temperature seasoning baking"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 2, out.size() );
        assertEquals( "CookingHub", out.get( 0 ).name(),
            "Cooking should sort first (finite coherence)" );
        assertEquals( "GhostHub", out.get( 1 ).name(),
            "GhostHub should sort last (NaN coherence)" );
        assertTrue( Double.isNaN( out.get( 1 ).coherence() ),
            "GhostHub coherence should be NaN" );
    }

    @Test
    void listHubOverviews_inboundLinks_excludeHubAndSameHubMembers() {
        // CookingHub has members Baking + Roasting.
        // External pages "Newsletter" and "FoodBlog" both link to Baking.
        // Roasting also links to Baking — should NOT count (same-hub member).
        // CookingHub itself links to Baking — should NOT count (the hub itself).
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", "stub" );

        // Add the link sources as KG nodes and the links_to edges. We re-upsert the
        // existing Baking/Roasting/CookingHub nodes (idempotent — returns the same row)
        // to capture their ids without going through queryNodes (whose "name" filter is
        // a LIKE, not an exact match).
        final var newsletter = kgRepo.upsertNode( "Newsletter", "article", "Newsletter",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var foodBlog = kgRepo.upsertNode( "FoodBlog", "article", "FoodBlog",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var bakingNode = kgRepo.upsertNode( "Baking", "article", "Baking",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var roastingNode = kgRepo.upsertNode( "Roasting", "article", "Roasting",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var hubNode = kgRepo.upsertNode( "CookingHub", "hub", "CookingHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );

        kgRepo.upsertEdge( newsletter.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( foodBlog.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( roastingNode.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( hubNode.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven",
                "roasting meat oven temperature seasoning"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 1, out.size() );
        assertEquals( 2, out.get( 0 ).inboundLinkCount(),
            "Should count Newsletter + FoodBlog only (not CookingHub itself, not Roasting)" );
    }

    @Test
    void listHubOverviews_nearMissCount_thresholdInclusive() {
        // Build a hub of 2 cooking pages and one near-miss "Pasta" article that
        // shares enough vocabulary to clear the 0.50 threshold but is not a member.
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        kgRepo.upsertNode( "Pasta", "article", "Pasta",
            Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Soccer", "article", "Soccer",
            Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "CookingHub", "stub" );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting", "Pasta", "Soccer" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough oven baking baking",
                "roasting meat oven temperature seasoning baking oven roast",
                // Pasta shares lots of cooking vocab with both members.
                "pasta sauce flour sugar oven dough recipe baking roast meat",
                // Soccer shares nothing.
                "soccer football pitch goal player team league kick stadium"
            ) );

        // Threshold of 0.10 — very generous so Pasta clears it but Soccer does not.
        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .nearMissThreshold( 0.10 )
            .build();

        final var out = svc.listHubOverviews();
        assertEquals( 1, out.size() );
        assertEquals( 1, out.get( 0 ).nearMissCount(),
            "Pasta should count as a near-miss; Soccer should not" );
    }

    // ---- loadDrilldown ----

    @Test
    void loadDrilldown_happyPath_populatesAllSections() throws Exception {
        // Two hubs: CookingHub (3 cooking members + 1 stub member) and GardeningHub
        // (2 gardening members). Pasta is a non-member near-miss for cooking. The
        // two hubs share zero members (sharedMemberCount=0) but their centroids are
        // distant — overlap section will be empty unless we drop the overlap threshold.
        seedHub( "CookingHub", List.of( "Baking", "Roasting", "Grilling", "GhostMember" ) );
        seedHub( "GardeningHub", List.of( "Tomatoes", "Lettuce" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GardeningHub", "stub" );
        // Mark "GhostMember" as missing — pageStore does NOT contain it,
        // so pageManager.pageExists returns false → drilldown classifies as stub.
        pageStore.put( "Baking", "..." );
        pageStore.put( "Roasting", "..." );
        pageStore.put( "Grilling", "..." );
        pageStore.put( "Tomatoes", "..." );
        pageStore.put( "Lettuce", "..." );

        kgRepo.upsertNode( "Pasta", "article", "Pasta",
            Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "Pasta", "..." );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting", "Grilling", "Tomatoes", "Lettuce", "Pasta" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough baking",
                "roasting meat oven temperature seasoning baking",
                "grilling charcoal meat barbecue outdoor fire baking",
                "tomatoes garden vegetable plant water sun soil",
                "lettuce garden vegetable plant water leaf soil",
                "pasta sauce flour sugar oven dough baking roast"
            ) );

        // Stub LuceneMlt: returns one fixed hit, only when seed is "CookingHub".
        final HubOverviewService.LuceneMlt mlt = ( seed, max, excludes ) -> {
            if ( "CookingHub".equals( seed ) && !excludes.contains( "Pasta" ) ) {
                return List.of( new HubOverviewService.MoreLikeThisLucene( "Pasta", 4.2 ) );
            }
            return List.of();
        };

        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .luceneMlt( mlt )
            .nearMissThreshold( 0.10 )
            .overlapThreshold( 0.99 ) // effectively disable overlap section
            .build();

        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "CookingHub" );
        assertNotNull( d );
        assertEquals( "CookingHub", d.name() );
        assertTrue( d.hasBackingPage() );

        // Members: Baking + Roasting + Grilling are real, GhostMember is stub.
        assertEquals( 3, d.members().size() );
        // Members sorted ascending by cosine — verify ordering invariant.
        for ( int i = 1; i < d.members().size(); i++ ) {
            assertTrue( d.members().get( i - 1 ).cosineToCentroid()
                <= d.members().get( i ).cosineToCentroid(),
                "Members must be sorted ascending by cosine" );
        }
        assertEquals( 1, d.stubMembers().size() );
        assertEquals( "GhostMember", d.stubMembers().get( 0 ).name() );

        // Near-miss TF-IDF should include Pasta (cooking-vocab non-member).
        assertTrue( d.nearMissTfidf().stream().anyMatch( h -> "Pasta".equals( h.name() ) ),
            "Pasta should appear in near-miss TF-IDF list" );
        // MLT list contains the stubbed Pasta hit.
        assertEquals( 1, d.moreLikeThisLucene().size() );
        assertEquals( "Pasta", d.moreLikeThisLucene().get( 0 ).name() );
        // Overlap section empty (we set the threshold to 0.99).
        assertTrue( d.overlapHubs().isEmpty() );
    }

    @Test
    void loadDrilldown_luceneThrows_returnsEmptyMltAndContinues() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "Baking", "..." );
        pageStore.put( "Roasting", "..." );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven",
                "roasting meat oven temperature seasoning baking"
            ) );

        final HubOverviewService.LuceneMlt failingMlt = ( seed, max, excludes ) -> {
            throw new java.io.IOException( "lucene index unavailable" );
        };

        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .luceneMlt( failingMlt )
            .build();

        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "CookingHub" );
        assertNotNull( d );
        assertTrue( d.moreLikeThisLucene().isEmpty(),
            "MLT list must be empty when Lucene throws" );
        // Other sections still populated.
        assertEquals( 2, d.members().size() );
    }

    @Test
    void loadDrilldown_orphanedHub_populatesFromKgOnly() throws Exception {
        seedHub( "OrphanHub", List.of( "Baking", "Roasting" ) );
        // Crucially, no pageStore entry for "OrphanHub" — it has no backing wiki page.
        pageStore.put( "Baking", "..." );
        pageStore.put( "Roasting", "..." );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven",
                "roasting meat oven temperature seasoning baking"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "OrphanHub" );

        assertNotNull( d );
        assertEquals( "OrphanHub", d.name() );
        assertFalse( d.hasBackingPage(), "Orphan hub must report hasBackingPage=false" );
        assertEquals( 2, d.members().size() );
        assertTrue( d.stubMembers().isEmpty() );
    }

    @Test
    void loadDrilldown_unknownHub_returnsNull() {
        // No hub seeded.
        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread cake" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        assertNull( svc.loadDrilldown( "DoesNotExist" ) );
    }

    // ---- helpers ----

    /**
     * Creates a hub-typed KG node and {@code related} edges to each member name. Also
     * upserts each member as an article-typed node so the queryNodes call sees them.
     */
    private void seedHub( final String hubName, final List< String > members ) {
        final var hubNode = kgRepo.upsertNode( hubName, "hub", hubName,
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        for ( final String m : members ) {
            final var memberNode = kgRepo.upsertNode( m, "article", m,
                Provenance.HUMAN_AUTHORED, Map.of() );
            kgRepo.upsertEdge( hubNode.id(), memberNode.id(), "related",
                Provenance.HUMAN_AUTHORED, Map.of() );
        }
    }
}
