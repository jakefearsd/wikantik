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
