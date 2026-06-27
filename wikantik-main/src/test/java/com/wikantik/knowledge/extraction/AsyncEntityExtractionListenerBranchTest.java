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

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractedMention;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.ProposedEdge;
import com.wikantik.api.knowledge.ProposedNode;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for {@link AsyncEntityExtractionListener} that cover
 * paths not exercised by the prefilter / skip tests already in place.
 *
 * <p>Each test asserts a concrete outcome: a collaborator called (or NOT called)
 * with specific arguments, a return value, or a state check. No test asserts
 * nothing or only not-null.</p>
 */
class AsyncEntityExtractionListenerBranchTest {

    // -----------------------------------------------------------------------
    // accept() guard paths
    // -----------------------------------------------------------------------

    @Test
    void accept_withNullList_doesNotCallExtractor() {
        final Fixture f = Fixture.noopExtractor();
        f.listener().accept( null );
        verify( f.extractor(), never() ).extract( any(), any() );
    }

    @Test
    void accept_withEmptyList_doesNotCallExtractor() {
        final Fixture f = Fixture.noopExtractor();
        f.listener().accept( List.of() );
        verify( f.extractor(), never() ).extract( any(), any() );
    }

    @Test
    void accept_whenConfigDisabled_doesNotCallExtractor() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "disabled" );
        final EntityExtractor extractor = mockExtractor();
        final AsyncEntityExtractionListener listener = buildListener( extractor,
            EntityExtractorConfig.fromProperties( p ) );
        listener.accept( List.of( UUID.randomUUID() ) );
        verify( extractor, never() ).extract( any(), any() );
    }

    /**
     * When the executor has been shut down it throws {@link java.util.concurrent.RejectedExecutionException}
     * from {@code submit()}. The listener must absorb the rejection without propagating to the caller.
     */
    @Test
    void accept_whenExecutorRejected_doesNotThrowToCaller() {
        final EntityExtractor extractor = mockExtractor();
        final ExecutorService shuttingDown = Executors.newSingleThreadExecutor();
        shuttingDown.shutdown(); // any submit() now rejects

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final KgNodeRepository nodeRepo = Mockito.mock( KgNodeRepository.class );
        final KgProposalRepository proposalRepo = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejectionRepo = Mockito.mock( KgRejectionRepository.class );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, cfg, chunkRepo, mentionRepo, nodeRepo, proposalRepo, rejectionRepo,
            new SimpleMeterRegistry(), shuttingDown );

        // Must NOT throw — listener catches RejectedExecutionException and logs a warn.
        listener.accept( List.of( UUID.randomUUID() ) );
        // Assertion: the extractor was never called (task was rejected).
        verify( extractor, never() ).extract( any(), any() );
    }

    // -----------------------------------------------------------------------
    // runExtractionSync guard paths
    // -----------------------------------------------------------------------

    @Test
    void runExtractionSync_withNullList_returnsEmpty() {
        final Fixture f = Fixture.noopExtractor();
        final AsyncEntityExtractionListener.RunResult r = f.listener().runExtractionSync( null );
        assertEquals( AsyncEntityExtractionListener.RunResult.EMPTY, r );
    }

    @Test
    void runExtractionSync_withEmptyList_returnsEmpty() {
        final Fixture f = Fixture.noopExtractor();
        final AsyncEntityExtractionListener.RunResult r = f.listener().runExtractionSync( List.of() );
        assertEquals( AsyncEntityExtractionListener.RunResult.EMPTY, r );
    }

    @Test
    void runExtractionSync_whenConfigDisabled_returnsEmpty() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "disabled" );
        final EntityExtractor extractor = mockExtractor();
        final AsyncEntityExtractionListener listener = buildListener( extractor,
            EntityExtractorConfig.fromProperties( p ) );
        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( UUID.randomUUID() ) );
        assertEquals( 0, r.mentionsWritten() );
        assertEquals( 0, r.proposalsFiled() );
        verify( extractor, never() ).extract( any(), any() );
    }

    @Test
    void runExtractionSync_whenChunkRepoReturnsEmpty_returnsEmptyAndExtractorNotCalled() {
        final EntityExtractor extractor = mockExtractor();
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        when( chunkRepo.findByIds( any() ) ).thenReturn( List.of() );

        final AsyncEntityExtractionListener listener = buildListenerWithChunkRepo(
            extractor, enabledConfig(), chunkRepo );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( UUID.randomUUID() ) );

        assertEquals( AsyncEntityExtractionListener.RunResult.EMPTY, r );
        verify( extractor, never() ).extract( any(), any() );
    }

    // -----------------------------------------------------------------------
    // Rate-limit gate — exercised via the async accept() path
    // -----------------------------------------------------------------------

    /**
     * The rate-limit gate is only consulted on the async {@code accept()} path
     * ({@code bypassRateLimit=false}). It prevents a re-save storm from calling
     * the extractor on every save within the configured window. We use a
     * synchronous (same-thread) executor so both tasks complete before we assert.
     */
    @Test
    void rateLimitGate_samePageWithinWindowViaAccept_suppressesSecondRun() throws Exception {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();
        when( extractor.extract( any(), any() ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final KgNodeRepository nodeRepo = Mockito.mock( KgNodeRepository.class );
        final KgProposalRepository proposalRepo = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejectionRepo = Mockito.mock( KgRejectionRepository.class );
        when( nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "RateLimitPage", 0, List.of(),
                "PostgreSQL is a relational database." ) ) );

        // Large rate-limit window so both accept() calls land within it.
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.per_page_min_interval_ms", "60000" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        // Use a same-thread executor so tasks run synchronously inside accept().
        final ExecutorService sameThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try ( final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, cfg, chunkRepo, mentionRepo, nodeRepo, proposalRepo, rejectionRepo,
                new SimpleMeterRegistry(), sameThread ) ) {

            // First accept() — passes the rate-limit gate.
            listener.accept( List.of( chunk ) );
            // Second accept() for the same page within the window — rate-limited.
            listener.accept( List.of( chunk ) );

            // Drain the single-thread executor so both tasks complete before we assert.
            sameThread.shutdown();
            sameThread.awaitTermination( 5, java.util.concurrent.TimeUnit.SECONDS );

            // The extractor was called exactly once — the second run was suppressed.
            verify( extractor, Mockito.times( 1 ) ).extract( any(), any() );
        }
    }

    // -----------------------------------------------------------------------
    // Extractor throws — failure isolation
    // -----------------------------------------------------------------------

    @Test
    void extractorThrows_processesNextChunkAndReturnsResult() {
        final UUID chunk1 = UUID.randomUUID();
        final UUID chunk2 = UUID.randomUUID();

        final EntityExtractor extractor = mockExtractor();
        // First call throws, second succeeds.
        when( extractor.extract( any(), any() ) )
            .thenThrow( new RuntimeException( "extractor boom" ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final KgNodeRepository nodeRepo = Mockito.mock( KgNodeRepository.class );
        final KgProposalRepository proposalRepo = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejectionRepo = Mockito.mock( KgRejectionRepository.class );
        when( nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk1, "SomePage", 0, List.of(),
                "PostgreSQL is a relational database." ),
            new ContentChunkRepository.MentionableChunk( chunk2, "SomePage", 1, List.of(),
                "Alice manages the engineering org." ) ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, cfg, chunkRepo, mentionRepo, nodeRepo, proposalRepo, rejectionRepo,
            new SimpleMeterRegistry() );

        // Must NOT throw; should still attempt both chunks.
        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk1, chunk2 ) );

        // Both chunks attempted (first threw, second succeeded with empty result).
        verify( extractor, Mockito.times( 2 ) ).extract( any(), any() );
        assertEquals( 0, r.proposalsFiled() );
    }

    // -----------------------------------------------------------------------
    // loadExistingNodes failure — degrades to empty list
    // -----------------------------------------------------------------------

    @Test
    void loadExistingNodesFails_degradesToEmptyDictionaryAndStillExtracts() {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();
        when( extractor.extract( any(), any() ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final KgNodeRepository nodeRepo = Mockito.mock( KgNodeRepository.class );
        final KgProposalRepository proposalRepo = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejectionRepo = Mockito.mock( KgRejectionRepository.class );

        // queryNodes throws — listener must degrade to empty dictionary and proceed.
        when( nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB down" ) );
        when( chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "APage", 0, List.of(),
                "Alice manages the engineering org." ) ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, cfg, chunkRepo, mentionRepo, nodeRepo, proposalRepo, rejectionRepo,
            new SimpleMeterRegistry() );

        // Must NOT throw; extraction still proceeds with an empty node dictionary.
        listener.runExtractionSync( List.of( chunk ) );
        verify( extractor, Mockito.times( 1 ) ).extract( any(), any() );
    }

    // -----------------------------------------------------------------------
    // persistProposals — confidence threshold gate
    // -----------------------------------------------------------------------

    @Test
    void nodeProposal_belowConfidenceThreshold_notFiled() {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();

        // Confidence 0.3 is below the default threshold of 0.6.
        final ProposedNode lowConfNode = new ProposedNode(
            "LowConf", "concept", Map.of(), 0.3, "uncertain" );
        when( extractor.extract( any(), any() ) ).thenReturn(
            new ExtractionResult( List.of( lowConfNode ), List.of(), List.of(), "test", Duration.ZERO ) );

        final Repos repos = Repos.empty();
        when( repos.nodeRepo().queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( repos.chunkRepo().findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "PageX", 0, List.of(),
                "Alice manages the engineering org." ) ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.6" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, EntityExtractorConfig.fromProperties( p ),
            repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(),
            new SimpleMeterRegistry() );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk ) );

        verify( repos.proposalRepo(), never() ).insertProposal( any(), any(), anyMap(), anyDouble(), any() );
        assertEquals( 0, r.proposalsFiled() );
    }

    @Test
    void nodeProposal_aboveThreshold_isFiled() {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();

        // Confidence 0.9 is above the threshold of 0.6.
        final ProposedNode highConfNode = new ProposedNode(
            "HighConf", "organization", Map.of(), 0.9, "certain" );
        when( extractor.extract( any(), any() ) ).thenReturn(
            new ExtractionResult( List.of( highConfNode ), List.of(), List.of(), "test", Duration.ZERO ) );

        final Repos repos = Repos.empty();
        when( repos.nodeRepo().queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( repos.chunkRepo().findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "PageY", 0, List.of(),
                "Alice manages the engineering org." ) ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.6" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, EntityExtractorConfig.fromProperties( p ),
            repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(),
            new SimpleMeterRegistry() );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk ) );

        verify( repos.proposalRepo() ).insertProposal(
            eq( "new-node" ), eq( "PageY" ), anyMap(), eq( 0.9 ), any() );
        assertEquals( 1, r.proposalsFiled() );
    }

    // -----------------------------------------------------------------------
    // persistProposals — edge rejected by rejectionRepository
    // -----------------------------------------------------------------------

    @Test
    void edgeProposal_rejectedByRepository_notFiled() {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();

        final ProposedEdge rejectedEdge = new ProposedEdge(
            "Alice", "ACME", "works_at", Map.of(), 0.95, "clear" );
        when( extractor.extract( any(), any() ) ).thenReturn(
            new ExtractionResult( List.of(), List.of( rejectedEdge ), List.of(), "test", Duration.ZERO ) );

        final Repos repos = Repos.empty();
        when( repos.nodeRepo().queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( repos.chunkRepo().findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "PageZ", 0, List.of(),
                "Alice works at ACME Corp." ) ) );
        when( repos.rejectionRepo().isRejected( "Alice", "ACME", "works_at" ) ).thenReturn( true );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, EntityExtractorConfig.fromProperties( p ),
            repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(),
            new SimpleMeterRegistry() );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk ) );

        verify( repos.proposalRepo(), never() )
            .insertProposal( eq( "new-edge" ), any(), anyMap(), anyDouble(), any() );
        assertEquals( 0, r.proposalsFiled() );
    }

    @Test
    void edgeProposal_notRejected_isFiled() {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();

        final ProposedEdge edge = new ProposedEdge(
            "Bob", "ACME", "manages", Map.of(), 0.85, "clear" );
        when( extractor.extract( any(), any() ) ).thenReturn(
            new ExtractionResult( List.of(), List.of( edge ), List.of(), "test", Duration.ZERO ) );

        final Repos repos = Repos.empty();
        when( repos.nodeRepo().queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( repos.chunkRepo().findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "PageQ", 0, List.of(),
                "Bob manages ACME Corp." ) ) );
        when( repos.rejectionRepo().isRejected( any(), any(), any() ) ).thenReturn( false );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, EntityExtractorConfig.fromProperties( p ),
            repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(),
            new SimpleMeterRegistry() );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk ) );

        verify( repos.proposalRepo() ).insertProposal(
            eq( "new-edge" ), eq( "PageQ" ), anyMap(), eq( 0.85 ), any() );
        assertEquals( 1, r.proposalsFiled() );
    }

    // -----------------------------------------------------------------------
    // persistMentions — mention with null nodeName skipped
    // -----------------------------------------------------------------------

    @Test
    void mention_withNullNodeName_upsertAllNotCalled() {
        final UUID chunk = UUID.randomUUID();
        final EntityExtractor extractor = mockExtractor();

        final ExtractedMention nullNameMention = new ExtractedMention( chunk, null, 0.9 );
        when( extractor.extract( any(), any() ) ).thenReturn(
            new ExtractionResult( List.of(), List.of(), List.of( nullNameMention ), "test", Duration.ZERO ) );

        final Repos repos = Repos.empty();
        when( repos.nodeRepo().queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( repos.chunkRepo().findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "PageM", 0, List.of(),
                "Alice manages ACME Corp." ) ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, EntityExtractorConfig.fromProperties( p ),
            repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(),
            new SimpleMeterRegistry() );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk ) );

        // Null nodeName → rows list stays empty → upsertAll never called.
        verify( repos.mentionRepo(), never() ).upsertAll( any() );
        assertEquals( 0, r.mentionsWritten() );
    }

    @Test
    void mention_resolvedViaExistingNodeDictionary_upserted() {
        final UUID chunk = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();

        final EntityExtractor extractor = mockExtractor();
        final ExtractedMention mention = new ExtractedMention( chunk, "PostgreSQL", 0.95 );
        when( extractor.extract( any(), any() ) ).thenReturn(
            new ExtractionResult( List.of(), List.of(), List.of( mention ), "test", Duration.ZERO ) );

        final Repos repos = Repos.empty();

        // Existing node with name matching the mention (case-insensitive).
        final KgNode existingNode = new KgNode(
            nodeId, "PostgreSQL", "technology", "PostgreSQL", null,
            Map.of(), Instant.now(), Instant.now(), "human", null );
        when( repos.nodeRepo().queryNodes( anyMap(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( existingNode ) );
        when( repos.mentionRepo().upsertAll( any() ) ).thenReturn( 1 );
        when( repos.chunkRepo().findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunk, "PageDB", 0, List.of(),
                "PostgreSQL is a relational database system." ) ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_too_short", "false" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "false" );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, EntityExtractorConfig.fromProperties( p ),
            repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(),
            new SimpleMeterRegistry() );

        final AsyncEntityExtractionListener.RunResult r =
            listener.runExtractionSync( List.of( chunk ) );

        verify( repos.mentionRepo() ).upsertAll( Mockito.argThat( rows ->
            !rows.isEmpty() && rows.get( 0 ).nodeId().equals( nodeId ) ) );
        assertEquals( 1, r.mentionsWritten() );
    }

    // -----------------------------------------------------------------------
    // close() — ownsExecutor=false is a no-op
    // -----------------------------------------------------------------------

    @Test
    void close_whenNotOwnsExecutor_doesNotShutdownExecutor() {
        final EntityExtractor extractor = mockExtractor();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Properties p = new Properties();
            p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
            final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

            final Repos repos = Repos.empty();
            // The 5-arg constructor with an explicit executor sets ownsExecutor=false.
            final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, cfg, repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
                repos.proposalRepo(), repos.rejectionRepo(),
                new SimpleMeterRegistry(), executor );
            listener.close();

            // Executor must still be alive — close() with ownsExecutor=false is a no-op.
            assert !executor.isShutdown() : "executor should NOT be shut down";
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static EntityExtractor mockExtractor() {
        final EntityExtractor e = Mockito.mock( EntityExtractor.class );
        when( e.code() ).thenReturn( "test" );
        return e;
    }

    private static EntityExtractorConfig enabledConfig() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        return EntityExtractorConfig.fromProperties( p );
    }

    private static AsyncEntityExtractionListener buildListener(
            final EntityExtractor extractor,
            final EntityExtractorConfig cfg ) {
        final Repos repos = Repos.empty();
        return new AsyncEntityExtractionListener(
            extractor, cfg, repos.chunkRepo(), repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(), new SimpleMeterRegistry() );
    }

    private static AsyncEntityExtractionListener buildListenerWithChunkRepo(
            final EntityExtractor extractor,
            final EntityExtractorConfig cfg,
            final ContentChunkRepository chunkRepo ) {
        final Repos repos = Repos.empty();
        return new AsyncEntityExtractionListener(
            extractor, cfg, chunkRepo, repos.mentionRepo(), repos.nodeRepo(),
            repos.proposalRepo(), repos.rejectionRepo(), new SimpleMeterRegistry() );
    }

    /** Convenience record grouping the five repository mocks. */
    private record Repos(
        ContentChunkRepository chunkRepo,
        ChunkEntityMentionRepository mentionRepo,
        KgNodeRepository nodeRepo,
        KgProposalRepository proposalRepo,
        KgRejectionRepository rejectionRepo
    ) {
        static Repos empty() {
            return new Repos(
                Mockito.mock( ContentChunkRepository.class ),
                Mockito.mock( ChunkEntityMentionRepository.class ),
                Mockito.mock( KgNodeRepository.class ),
                Mockito.mock( KgProposalRepository.class ),
                Mockito.mock( KgRejectionRepository.class ) );
        }
    }

    /** Tiny helper for tests that set up a Fixture with a noop extractor. */
    private record Fixture(
        EntityExtractor extractor,
        AsyncEntityExtractionListener listener
    ) {
        static Fixture noopExtractor() {
            final EntityExtractor extractor = mockExtractor();
            when( extractor.extract( any(), any() ) )
                .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );
            return new Fixture( extractor, buildListener( extractor, enabledConfig() ) );
        }
    }
}
