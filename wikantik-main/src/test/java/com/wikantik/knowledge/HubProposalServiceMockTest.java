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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock-based coverage for {@link HubProposalService} branches a real-database run
 * ({@link HubProposalServiceTest}) doesn't reach: builder validation and its default
 * review-percentile path, every early-return step (no centroid vectors, no hub nodes,
 * hubs dropped for too few members, non-hub edge sources, unresolvable centroids, no
 * candidate scores), the exists()==false/isRejected()==true skip branch, and the
 * catch-and-wrap of an unexpected {@link RuntimeException} into
 * {@link HubProposalService.HubProposalException}.
 */
class HubProposalServiceMockTest {

    private static KgNode hubNode( final String name ) {
        return new KgNode( UUID.randomUUID(), name, "hub", name,
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ), null, null, "human", null );
    }

    private static Map< String, Object > relatedEdge( final String source, final String target ) {
        return Map.of( "source_name", source, "target_name", target );
    }

    @Test
    void buildThrowsWhenRequiredDependenciesMissing() {
        assertThrows( IllegalStateException.class, () -> HubProposalService.builder().build() );
        assertThrows( IllegalStateException.class, () -> HubProposalService.builder()
            .kgNodes( mock( KgNodeRepository.class ) )
            .build() );
    }

    @Test
    void reviewPercentileFromPropertiesUsesTheProvidedValue() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        when( similarity.allCentroids() ).thenReturn( Map.of() );

        final java.util.Properties props = new java.util.Properties();
        props.setProperty( HubProposalService.PROP_REVIEW_PERCENTILE, "77" );
        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentileFromProperties( props )
            .build();

        // No public getter for reviewPercentile; exercising build()+generateProposals() with an
        // empty centroid set is enough to prove the property parsed without throwing and the
        // service is otherwise usable.
        assertEquals( 0, service.generateProposals() );
    }

    @Test
    void generateProposals_returnsZero_whenNoCentroidVectorsYet() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        when( similarity.allCentroids() ).thenReturn( Map.of() );

        // Deliberately omit .reviewPercentile(...) so build() takes its default-value branch.
        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .build();

        assertEquals( 0, service.generateProposals() );
        verify( kgNodes, never() ).queryNodes( any(), any(), anyInt(), anyInt() );
    }

    @Test
    void generateProposals_returnsZero_whenNoHubNodesExist() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        when( similarity.allCentroids() ).thenReturn( Map.of( "Java", new float[]{ 1f } ) );
        when( kgNodes.queryNodes( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentile( 50 )
            .build();

        assertEquals( 0, service.generateProposals() );
        verify( kgEdges, never() ).queryEdgesWithNames( anyString(), any(), anyInt(), anyInt() );
    }

    @Test
    void generateProposals_returnsZero_whenEveryHubHasFewerThanTwoMembers() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        when( similarity.allCentroids() ).thenReturn( Map.of( "Java", new float[]{ 1f } ) );
        when( kgNodes.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( hubNode( "TechHub" ) ) );
        // TechHub has exactly one distinct member -> dropped in step 1c; the edge from a
        // non-hub source is ignored in step 1b (edgesFromNonHubs branch).
        when( kgEdges.queryEdgesWithNames( eq( "related_to" ), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of(
                relatedEdge( "TechHub", "Java" ),
                relatedEdge( "NotAHub", "Python" ) ) );

        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentile( 50 )
            .build();

        assertEquals( 0, service.generateProposals() );
        verify( proposalRepo, never() ).saveCentroid( anyString(), any(), anyInt(), anyInt() );
    }

    @Test
    void generateProposals_returnsZero_whenNoHubHasAResolvableCentroid() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        // TechHub has 2 distinct members, but neither has a mention-centroid vector, so
        // computeCentroid(...) returns null for it -> centroids map ends up empty.
        when( similarity.allCentroids() ).thenReturn( Map.of( "Other", new float[]{ 1f, 0f } ) );
        when( kgNodes.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( hubNode( "TechHub" ) ) );
        when( kgEdges.queryEdgesWithNames( eq( "related_to" ), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of(
                relatedEdge( "TechHub", "Java" ),
                relatedEdge( "TechHub", "Python" ) ) );

        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentile( 50 )
            .build();

        assertEquals( 0, service.generateProposals() );
        verify( proposalRepo, never() ).insertProposal( anyString(), anyString(), anyDouble(), anyDouble() );
    }

    @Test
    void generateProposals_returnsZero_whenNoCandidatePagesRemainAfterFiltering() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        // Vectors map contains only the hub's own members -> after skipping hub names and
        // existing members, scoreCandidates() has nothing left to score.
        when( similarity.allCentroids() ).thenReturn( Map.of(
            "Java", new float[]{ 1f, 0f },
            "Python", new float[]{ 0.9f, 0.1f } ) );
        when( kgNodes.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( hubNode( "TechHub" ) ) );
        when( kgEdges.queryEdgesWithNames( eq( "related_to" ), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of(
                relatedEdge( "TechHub", "Java" ),
                relatedEdge( "TechHub", "Python" ) ) );

        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentile( 0 )
            .build();

        assertEquals( 0, service.generateProposals() );
        verify( proposalRepo ).saveCentroid( eq( "TechHub" ), any(), eq( 0 ), eq( 2 ) );
    }

    @Test
    void generateProposals_skipsCandidate_whenNotExistingButFlaggedRejected() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        when( similarity.allCentroids() ).thenReturn( Map.of(
            "Java", new float[]{ 1f, 0f },
            "Python", new float[]{ 0.9f, 0.1f },
            "Rust", new float[]{ 0.8f, 0.2f } ) );
        when( kgNodes.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( hubNode( "TechHub" ) ) );
        when( kgEdges.queryEdgesWithNames( eq( "related_to" ), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of(
                relatedEdge( "TechHub", "Java" ),
                relatedEdge( "TechHub", "Python" ) ) );
        when( proposalRepo.exists( "TechHub", "Rust" ) ).thenReturn( false );
        when( proposalRepo.isRejected( "TechHub", "Rust" ) ).thenReturn( true );

        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentile( 0 )
            .build();

        assertEquals( 0, service.generateProposals() );
        verify( proposalRepo, never() ).insertProposal( anyString(), anyString(), anyDouble(), anyDouble() );
    }

    @Test
    void generateProposals_wrapsUnexpectedRuntimeExceptionInHubProposalException() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository kgEdges = mock( KgEdgeRepository.class );
        final HubProposalRepository proposalRepo = mock( HubProposalRepository.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        when( similarity.allCentroids() ).thenReturn( Map.of( "Java", new float[]{ 1f } ) );
        when( kgNodes.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenThrow( new RuntimeException( "kaboom" ) );

        final HubProposalService service = HubProposalService.builder()
            .kgNodes( kgNodes ).kgEdges( kgEdges ).proposalRepo( proposalRepo ).similarity( similarity )
            .reviewPercentile( 50 )
            .build();

        final HubProposalService.HubProposalException ex =
            assertThrows( HubProposalService.HubProposalException.class, service::generateProposals );
        assertTrue( ex.getMessage().contains( "kaboom" ) );
        assertTrue( ex.getCause() instanceof RuntimeException );
    }
}
