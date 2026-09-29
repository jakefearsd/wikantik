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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MentionAttributionRunner}. Uses a real {@link MentionAttributor}
 * (pure, no I/O) so the whole-word-match attribution logic runs for real; only the two
 * repositories are mocked.
 */
class MentionAttributionRunnerTest {

    private KgNodeRepository kgNodes;
    private ChunkEntityMentionRepository mentionRepo;
    private MentionAttributionRunner runner;

    @BeforeEach
    void setUp() {
        kgNodes = mock( KgNodeRepository.class );
        mentionRepo = mock( ChunkEntityMentionRepository.class );
        runner = new MentionAttributionRunner( kgNodes, mentionRepo, new MentionAttributor(), "claude" );
    }

    private static KgNode node( final UUID id, final String name ) {
        return new KgNode( id, name, "Person", "Page", Provenance.HUMAN_AUTHORED,
            Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    private static ExtractionBatchRunner.PageOutcome outcome(
            final String pageName, final ContentChunkRepository.MentionableChunk... chunks ) {
        return new ExtractionBatchRunner.PageOutcome( pageName, List.of( chunks ), null );
    }

    private static ContentChunkRepository.MentionableChunk chunk( final String text ) {
        return new ContentChunkRepository.MentionableChunk( UUID.randomUUID(), "Page", 0, List.of(), text );
    }

    @Test
    void returnsZeroWhenAcceptedIsEmpty() {
        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Napoleon at Waterloo." ) ) ), List.of(), false );
        assertEquals( 0, written );
    }

    @Test
    void returnsZeroWhenOutcomesIsEmpty() {
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );
        final int written = runner.attribute( List.of(), List.of( proposal ), false );
        assertEquals( 0, written );
    }

    @Test
    void returnsZeroAndSkipsWhenNoAcceptedNameResolvesToExistingNode() {
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( null );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Napoleon at Waterloo." ) ) ), List.of( proposal ), false );

        assertEquals( 0, written );
        verify( mentionRepo, never() ).upsertAll( any() );
    }

    @Test
    void skipsEdgeKindProposalsWhenLookingForNodeNames() {
        final var edge = ConsolidatedProposal.newEdge( "sig", "Napoleon", "Waterloo", "fought_at", List.of(), 0.9 );

        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Napoleon at Waterloo." ) ) ), List.of( edge ), false );

        assertEquals( 0, written );
        verify( kgNodes, never() ).getNodeByName( any() );
    }

    @Test
    void deduplicatesRepeatedProposalNamesBeforeLookup() {
        final UUID nodeId = UUID.randomUUID();
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( nodeId, "Napoleon" ) );
        when( mentionRepo.upsertAll( anyList() ) ).thenReturn( 1 );
        final var p1 = ConsolidatedProposal.newNode( "sig1", "Napoleon", "Person", List.of(), 0.9 );
        final var p2 = ConsolidatedProposal.newNode( "sig2", "Napoleon", "Person", List.of(), 0.8 );

        runner.attribute( List.of( outcome( "Page", chunk( "Napoleon at Waterloo." ) ) ), List.of( p1, p2 ), false );

        verify( kgNodes, times( 1 ) ).getNodeByName( "Napoleon" );
    }

    @Test
    void logsAndSkipsWhenGetNodeByNameThrows() {
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenThrow( new RuntimeException( "db down" ) );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Napoleon at Waterloo." ) ) ), List.of( proposal ), false );

        assertEquals( 0, written );
        verify( mentionRepo, never() ).upsertAll( any() );
    }

    @Test
    void attributesMentionsAndUpsertsRowsForResolvedNode() {
        final UUID nodeId = UUID.randomUUID();
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( nodeId, "Napoleon" ) );
        when( mentionRepo.upsertAll( anyList() ) ).thenReturn( 1 );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Napoleon fought at Waterloo." ) ) ), List.of( proposal ), false );

        assertEquals( 1, written );
        verify( mentionRepo ).upsertAll( argThatSingleRowFor( nodeId ) );
    }

    private static List< ChunkEntityMentionRepository.Row > argThatSingleRowFor( final UUID nodeId ) {
        return org.mockito.ArgumentMatchers.argThat( rows ->
            rows.size() == 1 && rows.get( 0 ).nodeId().equals( nodeId ) && rows.get( 0 ).extractor().equals( "claude" ) );
    }

    @Test
    void returnsZeroWithoutUpsertWhenNoChunkTextMatchesResolvedName() {
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( UUID.randomUUID(), "Napoleon" ) );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Unrelated text about ducks." ) ) ), List.of( proposal ), false );

        assertEquals( 0, written );
        verify( mentionRepo, never() ).upsertAll( any() );
    }

    @Test
    void clearsExistingMentionsForEachChunkWhenOverwriteTrue() {
        final var chunk1 = chunk( "Napoleon at Waterloo." );
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( UUID.randomUUID(), "Napoleon" ) );
        when( mentionRepo.upsertAll( anyList() ) ).thenReturn( 1 );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        runner.attribute( List.of( outcome( "Page", chunk1 ) ), List.of( proposal ), true );

        verify( mentionRepo ).deleteByChunkId( chunk1.id() );
    }

    @Test
    void doesNotClearExistingMentionsWhenOverwriteFalse() {
        final var chunk1 = chunk( "Napoleon at Waterloo." );
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( UUID.randomUUID(), "Napoleon" ) );
        when( mentionRepo.upsertAll( anyList() ) ).thenReturn( 1 );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        runner.attribute( List.of( outcome( "Page", chunk1 ) ), List.of( proposal ), false );

        verify( mentionRepo, never() ).deleteByChunkId( any() );
    }

    @Test
    void logsAndContinuesWhenDeleteByChunkIdThrowsUnderOverwrite() {
        final var chunk1 = chunk( "Napoleon at Waterloo." );
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( UUID.randomUUID(), "Napoleon" ) );
        when( mentionRepo.deleteByChunkId( chunk1.id() ) ).thenThrow( new RuntimeException( "delete failed" ) );
        when( mentionRepo.upsertAll( anyList() ) ).thenReturn( 1 );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        final int written = runner.attribute( List.of( outcome( "Page", chunk1 ) ), List.of( proposal ), true );

        assertEquals( 1, written );
    }

    @Test
    void returnsZeroWhenUpsertAllThrows() {
        when( kgNodes.getNodeByName( "Napoleon" ) ).thenReturn( node( UUID.randomUUID(), "Napoleon" ) );
        when( mentionRepo.upsertAll( anyList() ) ).thenThrow( new RuntimeException( "upsert failed" ) );
        final var proposal = ConsolidatedProposal.newNode( "sig", "Napoleon", "Person", List.of(), 0.9 );

        final int written = runner.attribute(
            List.of( outcome( "Page", chunk( "Napoleon at Waterloo." ) ) ), List.of( proposal ), false );

        assertEquals( 0, written );
    }
}
