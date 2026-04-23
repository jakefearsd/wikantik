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
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@Testcontainers( disabledWithoutDocker = true )
class HubOverviewServiceTest {

    private static final String MODEL = "test-model";

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private NodeMentionSimilarity similarity;
    private Map< String, String > pageStore;
    private PageManager pageManager;
    private List< String[] > pageWrites;
    private HubDiscoveryService.PageWriter pageWriter;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            conn.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        similarity = new NodeMentionSimilarity( dataSource, MODEL );

        pageStore = new HashMap<>();
        pageWrites = new ArrayList<>();
        pageManager = mock( PageManager.class );
        lenient().when( pageManager.pageExists( anyString() ) )
            .thenAnswer( inv -> pageStore.containsKey( (String) inv.getArgument( 0 ) ) );
        lenient().when( pageManager.getPageText( anyString(), anyInt() ) )
            .thenAnswer( inv -> pageStore.get( (String) inv.getArgument( 0 ) ) );
        pageWriter = ( name, content ) -> {
            pageWrites.add( new String[]{ name, content } );
            pageStore.put( name, content );
        };
    }

    private HubOverviewService.Builder serviceBuilder() {
        return HubOverviewService.builder()
            .kgRepo( kgRepo )
            .pageManager( pageManager )
            .pageWriter( pageWriter )
            .nearMissThreshold( 0.50 )
            .overlapThreshold( 0.60 )
            .nearMissMaxResults( 10 )
            .mltMaxResults( 10 )
            .luceneMlt( ( seed, max, excludes ) -> Collections.emptyList() )
            .similarity( similarity );
    }

    // Hand-crafted tight-cluster vectors for cooking vs. a loose grab-bag pair.
    // With unit-normalized chunk vectors, the centroid cosine differs clearly between them.
    private static final float[] BAKING   = { 1.0f, 0.0f, 0.02f, 0.0f,  0.01f, 0.0f };
    private static final float[] ROASTING = { 0.98f, 0.0f, 0.03f, 0.01f, 0.0f, 0.0f };
    private static final float[] GRILLING = { 0.99f, 0.0f, 0.01f, 0.02f, 0.0f, 0.0f };
    private static final float[] QUANTUM  = { 0.0f, 0.3f, 0.0f, 0.9f, 0.3f, 0.0f };
    private static final float[] PET      = { 0.0f, 0.9f, 0.0f, 0.3f, 0.0f, 0.3f };
    private static final float[] PASTA    = { 0.95f, 0.0f, 0.05f, 0.04f, 0.02f, 0.0f };
    private static final float[] SOCCER   = { 0.0f, 1.0f, 0.01f, 0.0f, 0.0f, 0.02f };

    // ---- listHubOverviews ----

    @Test
    void listHubOverviews_happyPath_sortsByCoherenceAscending() {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        seedHub( "GrabBagHub", List.of( "QuantumPhysics", "PetGroomer" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GrabBagHub", "stub" );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",         BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting",       ROASTING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "QuantumPhysics", QUANTUM );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "PetGroomer",     PET );

        final HubOverviewService svc = serviceBuilder().build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 2, out.size() );
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
    void listHubOverviews_noMentions_returnsHubsWithNaNCoherence() {
        // When the mention extractor has not populated chunk_entity_mentions yet
        // (Phase 1 reality), listHubOverviews must still enumerate every hub with
        // its member count and inbound-link count — only the coherence and near-miss
        // signals degrade to NaN / 0. This keeps the admin panel useful before
        // Phase 2 lands.
        seedHub( "TechHub", List.of( "Java", "Python" ) );
        pageStore.put( "TechHub", "stub" );

        final HubOverviewService svc = serviceBuilder().build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 1, out.size() );
        assertEquals( "TechHub", out.get( 0 ).name() );
        assertEquals( 2, out.get( 0 ).memberCount() );
        assertEquals( 0, out.get( 0 ).nearMissCount() );
        assertTrue( Double.isNaN( out.get( 0 ).coherence() ),
            "Coherence must be NaN when no centroid vectors exist" );
    }

    @Test
    void listHubOverviews_hubWithAllNonModelMembers_hasNaNCoherenceSortsLast() {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        seedHub( "GhostHub",   List.of( "Phantom1", "Phantom2" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GhostHub", "stub" );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",   BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting", ROASTING );
        // Phantom1/Phantom2 have no mentions — GhostHub centroid should be NaN.

        final HubOverviewService svc = serviceBuilder().build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 2, out.size() );
        assertEquals( "CookingHub", out.get( 0 ).name(), "Finite-coherence hub first" );
        assertEquals( "GhostHub",   out.get( 1 ).name(), "NaN-coherence hub last" );
        assertTrue( Double.isNaN( out.get( 1 ).coherence() ), "GhostHub coherence must be NaN" );
    }

    @Test
    void listHubOverviews_inboundLinks_excludeHubAndSameHubMembers() {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", "stub" );

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

        kgRepo.upsertEdge( newsletter.id(),   bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( foodBlog.id(),     bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( roastingNode.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( hubNode.id(),      bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",   BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting", ROASTING );

        final HubOverviewService svc = serviceBuilder().build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 1, out.size() );
        assertEquals( 2, out.get( 0 ).inboundLinkCount(),
            "Should count Newsletter + FoodBlog only (not CookingHub itself, not Roasting)" );
    }

    @Test
    void listHubOverviews_nearMissCount_thresholdInclusive() {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        kgRepo.upsertNode( "Pasta",  "article", "Pasta",  Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Soccer", "article", "Soccer", Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "CookingHub", "stub" );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",   BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting", ROASTING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Pasta",    PASTA );    // close to cooking
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Soccer",   SOCCER );   // orthogonal

        // Generous threshold so Pasta clears it but Soccer does not.
        final HubOverviewService svc = serviceBuilder()
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
        seedHub( "CookingHub",    List.of( "Baking", "Roasting", "Grilling", "GhostMember" ) );
        seedHub( "GardeningHub",  List.of( "Tomatoes", "Lettuce" ) );
        pageStore.put( "CookingHub",   "stub" );
        pageStore.put( "GardeningHub", "stub" );
        pageStore.put( "Baking",   "..." );
        pageStore.put( "Roasting", "..." );
        pageStore.put( "Grilling", "..." );
        pageStore.put( "Tomatoes", "..." );
        pageStore.put( "Lettuce",  "..." );

        kgRepo.upsertNode( "Pasta", "article", "Pasta", Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "Pasta", "..." );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",    BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting",  ROASTING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Grilling",  GRILLING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Tomatoes",
            new float[]{ 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f } );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Lettuce",
            new float[]{ 0.0f, 0.0f, 0.98f, 0.0f, 0.0f, 0.0f } );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Pasta",     PASTA );

        final HubOverviewService.LuceneMlt mlt = ( seed, max, excludes ) -> {
            if ( "CookingHub".equals( seed ) && !excludes.contains( "Pasta" ) ) {
                return List.of( new HubOverviewService.MoreLikeThisLucene( "Pasta", 4.2 ) );
            }
            return List.of();
        };

        final HubOverviewService svc = serviceBuilder()
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
        for ( int i = 1; i < d.members().size(); i++ ) {
            assertTrue( d.members().get( i - 1 ).cosineToCentroid()
                <= d.members().get( i ).cosineToCentroid(),
                "Members must be sorted ascending by cosine" );
        }
        assertEquals( 1, d.stubMembers().size() );
        assertEquals( "GhostMember", d.stubMembers().get( 0 ).name() );

        assertTrue( d.nearMissTfidf().stream().anyMatch( h -> "Pasta".equals( h.name() ) ),
            "Pasta should appear in near-miss list" );
        assertEquals( 1, d.moreLikeThisLucene().size() );
        assertEquals( "Pasta", d.moreLikeThisLucene().get( 0 ).name() );
        assertTrue( d.overlapHubs().isEmpty() );
    }

    @Test
    void loadDrilldown_luceneThrows_returnsEmptyMltAndContinues() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "Baking",   "..." );
        pageStore.put( "Roasting", "..." );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",   BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting", ROASTING );

        final HubOverviewService.LuceneMlt failingMlt = ( seed, max, excludes ) -> {
            throw new java.io.IOException( "lucene index unavailable" );
        };

        final HubOverviewService svc = serviceBuilder()
            .luceneMlt( failingMlt )
            .build();

        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "CookingHub" );
        assertNotNull( d );
        assertTrue( d.moreLikeThisLucene().isEmpty(),
            "MLT list must be empty when Lucene throws" );
        assertEquals( 2, d.members().size() );
    }

    @Test
    void loadDrilldown_orphanedHub_populatesFromKgOnly() throws Exception {
        seedHub( "OrphanHub", List.of( "Baking", "Roasting" ) );
        // No pageStore entry for "OrphanHub".
        pageStore.put( "Baking",   "..." );
        pageStore.put( "Roasting", "..." );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking",   BAKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Roasting", ROASTING );

        final HubOverviewService svc = serviceBuilder().build();
        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "OrphanHub" );

        assertNotNull( d );
        assertEquals( "OrphanHub", d.name() );
        assertFalse( d.hasBackingPage(), "Orphan hub must report hasBackingPage=false" );
        assertEquals( 2, d.members().size() );
        assertTrue( d.stubMembers().isEmpty() );
    }

    @Test
    void loadDrilldown_unknownHub_returnsNull() {
        // No hub seeded, no mentions.
        final HubOverviewService svc = serviceBuilder().build();
        assertNull( svc.loadDrilldown( "DoesNotExist" ) );
    }

    // ---- removeMember ----

    private static final String SAMPLE_HUB_BODY =
        "# CookingHub\n\nA hand-curated description of cooking techniques.\n";

    private static String hubPageText( final List< String > members ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "---\n" );
        sb.append( "title: CookingHub\n" );
        sb.append( "type: hub\n" );
        sb.append( "related:\n" );
        for ( final String m : members ) sb.append( "- " ).append( m ).append( "\n" );
        sb.append( "---\n" );
        sb.append( SAMPLE_HUB_BODY );
        return sb.toString();
    }

    @Test
    void removeMember_happyPath_writesUpdatedFrontmatterAndPreservesBody() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting", "Grilling" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting", "Grilling" ) ) );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Baking", BAKING );

        final HubOverviewService svc = serviceBuilder().build();
        final HubOverviewService.RemoveMemberResult result =
            svc.removeMember( "CookingHub", "Roasting", "alice" );

        assertEquals( "Roasting", result.removed() );
        assertEquals( 2, result.remainingMemberCount() );
        assertEquals( 1, pageWrites.size() );

        final String written = pageWrites.get( 0 )[ 1 ];
        assertTrue( written.contains( "Baking" ) );
        assertTrue( written.contains( "Grilling" ) );
        assertFalse( written.contains( "- Roasting" ),
            "Roasting should no longer appear as a list item" );
        assertTrue( written.contains( SAMPLE_HUB_BODY ),
            "Original body must be preserved byte-for-byte" );
    }

    @Test
    void removeMember_blankMember_throwsIllegalArgument() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting" ) ) );

        final HubOverviewService svc = serviceBuilder().build();
        assertThrows( IllegalArgumentException.class,
            () -> svc.removeMember( "CookingHub", "  ", "alice" ) );
        assertEquals( 0, pageWrites.size() );
    }

    @Test
    void removeMember_memberNotInRelated_throwsIllegalArgument() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting" ) ) );

        final HubOverviewService svc = serviceBuilder().build();
        assertThrows( IllegalArgumentException.class,
            () -> svc.removeMember( "CookingHub", "DoesNotExist", "alice" ) );
        assertEquals( 0, pageWrites.size() );
    }

    @Test
    void removeMember_pageNotHubType_throwsIllegalArgument() throws Exception {
        kgRepo.upsertNode( "RegularPage", "article", "RegularPage",
            Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "RegularPage",
            "---\ntitle: RegularPage\ntype: article\nrelated:\n- Other\n- More\n---\n# body\n" );

        final HubOverviewService svc = serviceBuilder().build();
        assertThrows( IllegalArgumentException.class,
            () -> svc.removeMember( "RegularPage", "Other", "alice" ) );
        assertEquals( 0, pageWrites.size() );
    }

    @Test
    void removeMember_wouldLeaveFewerThanTwo_throwsHubOverviewException() throws Exception {
        seedHub( "TinyHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "TinyHub", hubPageText( List.of( "Baking", "Roasting" ) )
            .replace( "title: CookingHub", "title: TinyHub" ) );

        final HubOverviewService svc = serviceBuilder().build();
        assertThrows( HubOverviewException.class,
            () -> svc.removeMember( "TinyHub", "Roasting", "alice" ) );
        assertEquals( 0, pageWrites.size(),
            "No write should occur when removal would leave fewer than 2 members" );
    }

    @Test
    void removeMember_saveThrows_wrapsInHubOverviewException() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting", "Grilling" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting", "Grilling" ) ) );

        final HubDiscoveryService.PageWriter throwingWriter = ( name, content ) -> {
            throw new java.io.IOException( "disk full" );
        };

        final HubOverviewService svc = serviceBuilder()
            .pageWriter( throwingWriter )
            .build();

        assertThrows( HubOverviewException.class,
            () -> svc.removeMember( "CookingHub", "Roasting", "alice" ) );
    }

    // ---- helpers ----

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
