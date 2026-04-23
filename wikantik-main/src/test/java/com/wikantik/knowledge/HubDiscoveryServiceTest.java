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
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubDiscoveryServiceTest {

    private static final String MODEL = "test-model";

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private HubDiscoveryRepository discoveryRepo;
    private NodeMentionSimilarity similarity;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_discovery_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_centroids" );
            conn.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            conn.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        discoveryRepo = new HubDiscoveryRepository( dataSource );
        similarity = new NodeMentionSimilarity( dataSource, MODEL );
    }

    // Hand-crafted 8-dim vectors that create two tight clusters (cooking + sports) plus an outlier.
    // Each article's vector is unit-normalized by NodeMentionSimilarity's centroid pass, so cluster
    // geometry is faithful.
    private static final Map< String, float[] > COOKING = new LinkedHashMap<>();
    static {
        COOKING.put( "Baking",   new float[]{ 1.0f,  0.0f, 0.02f, 0.0f,  0.01f, 0.0f, 0.0f, 0.0f } );
        COOKING.put( "Roasting", new float[]{ 0.98f, 0.0f, 0.03f, 0.01f, 0.0f,  0.0f, 0.0f, 0.0f } );
        COOKING.put( "Grilling", new float[]{ 0.99f, 0.0f, 0.01f, 0.02f, 0.0f,  0.0f, 0.0f, 0.0f } );
    }
    private static final Map< String, float[] > SPORTS = new LinkedHashMap<>();
    static {
        SPORTS.put( "Soccer",     new float[]{ 0.0f,  1.0f, 0.01f, 0.0f,  0.02f, 0.0f, 0.0f, 0.0f } );
        SPORTS.put( "Basketball", new float[]{ 0.0f,  0.99f, 0.0f, 0.03f, 0.01f, 0.0f, 0.0f, 0.0f } );
        SPORTS.put( "Tennis",     new float[]{ 0.0f,  0.98f, 0.02f, 0.01f, 0.03f, 0.0f, 0.0f, 0.0f } );
    }
    private static final Map< String, float[] > TECH_MEMBERS = new LinkedHashMap<>();
    static {
        TECH_MEMBERS.put( "Java",   new float[]{ 0.0f, 0.0f, 1.0f,  0.0f, 0.0f, 0.0f, 0.0f, 0.0f } );
        TECH_MEMBERS.put( "Python", new float[]{ 0.0f, 0.0f, 0.98f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f } );
    }
    private static final float[] OUTLIER_VEC = new float[]{ 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f };

    @Test
    void generateClusterProposals_findsClusterOfNonMembers() {
        // Existing TechHub with 2 members (Java, Python).
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        for ( final String name : TECH_MEMBERS.keySet() ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        kgRepo.upsertEdge( techHub.id(),
            kgRepo.upsertNode( "Java", "article", "Java", Provenance.HUMAN_AUTHORED, Map.of() ).id(),
            "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(),
            kgRepo.upsertNode( "Python", "article", "Python", Provenance.HUMAN_AUTHORED, Map.of() ).id(),
            "related", Provenance.HUMAN_AUTHORED, Map.of() );

        // Non-members: 3 cooking, 3 sports, and one outlier.
        for ( final String name : COOKING.keySet() ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        for ( final String name : SPORTS.keySet() ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        kgRepo.upsertNode( "Miscellaneous", "article", "Miscellaneous",
            Provenance.HUMAN_AUTHORED, Map.of() );

        MentionFixtures.seedAllByName( dataSource, MODEL, COOKING );
        MentionFixtures.seedAllByName( dataSource, MODEL, SPORTS );
        MentionFixtures.seedAllByName( dataSource, MODEL, TECH_MEMBERS );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Miscellaneous", OUTLIER_VEC );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 5 )
            .build();

        final int created = service.generateClusterProposals();
        assertTrue( created >= 2, "Expected at least 2 discovered clusters, got " + created );

        final var all = discoveryRepo.list( 50, 0 );
        assertEquals( created, all.size() );

        // No hub-member page (Java, Python) should appear in any proposal.
        for ( final var prop : all ) {
            assertFalse( prop.memberPages().contains( "Java" ),
                "Existing hub member 'Java' should not be proposed" );
            assertFalse( prop.memberPages().contains( "Python" ),
                "Existing hub member 'Python' should not be proposed" );
        }

        // At least one proposal should contain the cooking triad as a coherent cluster.
        final boolean cookingFound = all.stream().anyMatch( p ->
            p.memberPages().containsAll( List.of( "Baking", "Roasting", "Grilling" ) ) );
        assertTrue( cookingFound, "Expected a cluster containing Baking, Roasting, Grilling" );
    }

    @Test
    void generateClusterProposals_emptyCorpus_noProposals() {
        kgRepo.upsertNode( "Nothing", "article", "Nothing", Provenance.HUMAN_AUTHORED, Map.of() );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Nothing",
            new float[]{ 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f } );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 6 )
            .build();

        assertEquals( 0, service.generateClusterProposals() );
        assertEquals( 0, discoveryRepo.count() );
    }

    @Test
    void generateClusterProposals_tinyCorpus_noProposals() {
        // Three article pages — below the default minCandidatePool=6.
        for ( final String name : new String[]{ "A", "B", "C" } ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        MentionFixtures.seedMentionByName( dataSource, MODEL, "A", new float[]{ 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f } );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "B", new float[]{ 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f } );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "C", new float[]{ 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f } );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 6 )
            .build();

        assertEquals( 0, service.generateClusterProposals() );
        assertEquals( 0, discoveryRepo.count() );
    }

    @Test
    void generateClusterProposals_exemplarIsClosestToCentroid() {
        for ( final String name : new String[]{ "Baking", "Roasting", "Grilling",
                                                 "Soccer", "Basketball", "Tennis" } ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        MentionFixtures.seedAllByName( dataSource, MODEL, COOKING );
        MentionFixtures.seedAllByName( dataSource, MODEL, SPORTS );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 6 )
            .build();

        service.generateClusterProposals();
        final var proposals = discoveryRepo.list( 10, 0 );
        assertFalse( proposals.isEmpty() );
        for ( final var p : proposals ) {
            assertTrue( p.memberPages().contains( p.exemplarPage() ),
                "Exemplar must be in its cluster's member list" );
            assertTrue( p.coherenceScore() >= 0.0 && p.coherenceScore() <= 1.0 );
        }
    }

    @Test
    void acceptProposal_createsStubPageAndEdges() throws Exception {
        final String[] members = { "Java", "Kotlin", "Scala" };
        for ( final String name : members ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }

        final com.wikantik.knowledge.test.InMemoryPageManager pages =
            new com.wikantik.knowledge.test.InMemoryPageManager();
        for ( final String name : members ) {
            pages.putText( name, "# " + name );
        }
        final com.wikantik.knowledge.test.InMemoryPageSaveHelper helper =
            new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( pages::exists )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        final HubDiscoveryService.AcceptResult result = service.acceptProposal(
            id, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" );

        assertEquals( "JavaHub", result.createdPage() );
        assertEquals( 3, result.memberCount() );
        assertTrue( pages.exists( "JavaHub" ), "Stub page must be written" );
        final String written = pages.getText( "JavaHub" );
        assertTrue( written.contains( "type: hub" ), "Frontmatter must have type: hub" );
        assertTrue( written.contains( "auto-generated: true" ) );
        assertTrue( written.contains( "# JavaHub" ) );
        assertTrue( written.contains( "<!-- TODO: describe this hub -->" ) );
        assertTrue( written.contains( "- [Java](Java)" ) );

        assertNull( discoveryRepo.findById( id ), "Proposal row must be deleted on accept" );
        final var hubNode = kgRepo.queryNodes( Map.of( "name", "JavaHub" ), null, 1, 0 );
        assertEquals( 1, hubNode.size() );
        assertEquals( "hub", hubNode.get( 0 ).nodeType() );
    }

    @Test
    void acceptProposal_collisionWithExistingPage_throwsAndKeepsRow() {
        final com.wikantik.knowledge.test.InMemoryPageManager pages =
            new com.wikantik.knowledge.test.InMemoryPageManager();
        pages.putText( "JavaHub", "existing content" );
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( pages::exists )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );
        for ( final String m : List.of( "Java", "Kotlin", "Scala" ) ) {
            kgRepo.upsertNode( m, "article", m, Provenance.HUMAN_AUTHORED, Map.of() );
        }

        assertThrows( HubNameCollisionException.class, () ->
            service.acceptProposal( id, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" ) );

        assertNotNull( discoveryRepo.findById( id ) );
        assertEquals( "existing content", pages.getText( "JavaHub" ), "Existing page untouched" );
    }

    @Test
    void acceptProposal_memberNotInProposal_throwsIllegalArgument() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( pages::exists )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        assertThrows( IllegalArgumentException.class, () ->
            service.acceptProposal( id, "JavaHub",
                List.of( "Java", "Kotlin", "Evil Injected Page" ), "admin" ) );

        assertNotNull( discoveryRepo.findById( id ) );
    }

    @Test
    void acceptProposal_missingMemberPagesDropped() throws Exception {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        for ( final String m : List.of( "Java", "Kotlin" ) ) {
            kgRepo.upsertNode( m, "article", m, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( name -> kgRepo.queryNodes( Map.of( "name", name ), null, 1, 0 ).size() > 0 )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        final var result = service.acceptProposal( id, "JavaHub",
            List.of( "Java", "Kotlin", "Scala" ), "admin" );
        assertEquals( 2, result.memberCount(), "Scala was missing and should be dropped" );
        assertNull( discoveryRepo.findById( id ) );
    }

    @Test
    void acceptProposal_allMembersMissing_throws() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        assertThrows( HubDiscoveryException.class, () ->
            service.acceptProposal( id, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" ) );
        assertNotNull( discoveryRepo.findById( id ) );
    }

    @Test
    void dismissProposal_marksRowDismissedInsteadOfDeleting() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );
        service.dismissProposal( id, "reviewer1" );

        assertNotNull( discoveryRepo.findById( id ) );
        assertFalse( discoveryRepo.list( 50, 0 ).stream().anyMatch( p -> p.id() == id ) );
        final var dismissed = discoveryRepo.listDismissed( 50, 0 );
        assertEquals( 1, dismissed.size() );
        assertEquals( id, dismissed.get( 0 ).id() );
        assertEquals( "reviewer1", dismissed.get( 0 ).reviewedBy() );
        assertNotNull( dismissed.get( 0 ).reviewedAt() );
    }

    @Test
    void dismissProposal_twice_throwsOnSecondCall() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );
        service.dismissProposal( id, "admin" );
        assertThrows( HubDiscoveryException.class,
            () -> service.dismissProposal( id, "admin" ) );
    }

    @Test
    void runDiscovery_skipsClustersMatchingDismissedSignature() {
        // Seed a dismissed proposal whose sorted member set matches the cooking cluster.
        final int dismissedId = discoveryRepo.insert( "Cooking Hub", "Baking",
            List.of( "Baking", "Grilling", "Roasting" ), 0.9 );
        assertTrue( discoveryRepo.markDismissed( dismissedId, "admin" ) );

        for ( final String name : new String[]{ "Baking", "Roasting", "Grilling",
                                                 "Soccer", "Basketball", "Tennis", "Miscellaneous" } ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        MentionFixtures.seedAllByName( dataSource, MODEL, COOKING );
        MentionFixtures.seedAllByName( dataSource, MODEL, SPORTS );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Miscellaneous", OUTLIER_VEC );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .similarity( similarity )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 5 )
            .build();

        final HubDiscoveryService.RunSummary summary = service.runDiscovery();

        assertTrue( summary.skippedDismissed() >= 1,
            "Expected at least one skippedDismissed in summary, got " + summary.skippedDismissed() );
        final var pending = discoveryRepo.list( 50, 0 );
        for ( final var p : pending ) {
            assertFalse( p.memberPages().containsAll( List.of( "Baking", "Roasting", "Grilling" ) ),
                "Cooking cluster must not be re-proposed: " + p.memberPages() );
        }
    }

    @Test
    void dismissProposal_missingId_throws() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        assertThrows( HubDiscoveryException.class, () ->
            service.dismissProposal( 9999, "admin" ) );
    }

    @Test
    void acceptProposal_missingId_throws() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).similarity( similarity )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        assertThrows( HubDiscoveryException.class, () ->
            service.acceptProposal( 9999, "SomeHub", List.of( "A", "B" ), "admin" ) );
    }
}
