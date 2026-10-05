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

import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractedMention;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.ProposedEdge;
import com.wikantik.api.knowledge.ProposedNode;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Remaining-branch coverage for {@link AsyncEntityExtractionListener}, complementing
 * the Skip/Prefilter/Branch test classes already in this package: constructor
 * validation, the default executor's thread factory actually firing (only reachable
 * via {@code accept()}, since {@code runExtractionSync} never touches the executor),
 * every debug-guarded log branch (log4j's default test level is not DEBUG, so these
 * are otherwise unreachable — {@link Configurator} raises the level for the duration
 * of one test and restores it), the outer {@code runExtraction} catch, both
 * {@code getNodeByName} dictionary-fallback outcomes, both proposal-insert failure
 * catches, the edge below-confidence-threshold skip, {@code timeout()}, and
 * {@code close()}'s owned-executor branches (normal/timeout/interrupted) — the last
 * exercised via the package-private all-args constructor added for exactly this,
 * since no public constructor pairs a caller-supplied (mockable) executor with
 * {@code ownsExecutor=true}.
 */
class AsyncEntityExtractionListenerCoverageTest {

    private static final String LOGGER_NAME = AsyncEntityExtractionListener.class.getName();

    @AfterEach
    void restoreLogLevel() {
        Configurator.setLevel( LOGGER_NAME, Level.WARN );
    }

    // ---- construction ----

    @Test
    void constructorRejectsNullExtractorConfigOrRepositories() {
        final Repos r = Repos.empty();
        final EntityExtractorConfig cfg = enabledConfig();
        assertThrows( IllegalArgumentException.class, () -> new AsyncEntityExtractionListener(
            null, cfg, new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ),
            new SimpleMeterRegistry() ) );
        assertThrows( IllegalArgumentException.class, () -> new AsyncEntityExtractionListener(
            mockExtractor(), null, new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ),
            new SimpleMeterRegistry() ) );
        assertThrows( IllegalArgumentException.class, () -> new AsyncEntityExtractionListener(
            mockExtractor(), cfg, new AsyncEntityExtractionListener.Repositories( null, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ),
            new SimpleMeterRegistry() ) );
    }

    @Test
    void timeoutReflectsConfiguredTimeoutMs() {
        final EntityExtractorConfig cfg = configWith( 42_000L, 0.6, 200, 5_000L );
        final Repos r = Repos.empty();
        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                mockExtractor(), cfg, new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ),
                new SimpleMeterRegistry() ) ) {
            assertEquals( Duration.ofMillis( 42_000L ), listener.timeout() );
        }
    }

    // ---- default executor actually creating a thread (only via accept()) ----

    @Test
    void acceptOnDefaultExecutor_actuallySubmitsAndRunsATask() {
        final EntityExtractor extractor = mockExtractor();
        when( extractor.extract( any(), any() ) ).thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Some prose here." ) ) );

        // No explicit executor -> uses defaultExecutor(), whose ThreadFactory only ever
        // fires once a task is actually submitted (accept(), not runExtractionSync()).
        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            listener.accept( List.of( chunkId ) );
            verify( extractor, timeout( 2000 ) ).extract( any(), any() );
        }
    }

    // ---- doRunExtraction: all-chunks-excluded -> eligible.isEmpty() ----

    @Test
    void allChunksExcluded_yieldsEmptyResultWithoutCallingExtractor() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        final KgExcludedPagesRepository excluded = mock( KgExcludedPagesRepository.class );
        when( excluded.findReason( "ExcludedPage" ) ).thenReturn( Optional.of( ExclusionReason.SYSTEM_PAGE ) );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "ExcludedPage", 0, List.of(), "text" ) ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry(), excluded ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( AsyncEntityExtractionListener.RunResult.EMPTY, result );
            verify( extractor, never() ).extract( any(), any() );
        }
    }

    // ---- debug-guarded log lines (require the logger actually at DEBUG) ----

    @Test
    void debugLogging_filteredExcludedAndCompletedLines_areReached() {
        Configurator.setLevel( LOGGER_NAME, Level.DEBUG );

        final EntityExtractor extractor = mockExtractor();
        when( extractor.extract( any(), any() ) ).thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final KgExcludedPagesRepository excluded = mock( KgExcludedPagesRepository.class );
        when( excluded.findReason( "KeepPage" ) ).thenReturn( Optional.empty() );
        when( excluded.findReason( "ExcludedPage" ) ).thenReturn( Optional.of( ExclusionReason.SYSTEM_PAGE ) );

        final UUID keep = UUID.randomUUID();
        final UUID skip = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( keep, "KeepPage", 0, List.of(), "Some prose." ),
            new ContentChunkRepository.MentionableChunk( skip, "ExcludedPage", 0, List.of(), "Other prose." ) ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry(), excluded ) ) {
            // Filtering (some excluded, some kept) + successful completion debug lines.
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( keep, skip ) );
            assertEquals( 0, result.mentionsWritten() );
            verify( extractor, Mockito.times( 1 ) ).extract( any(), any() );
        }
    }

    @Test
    void debugLogging_prefilterDroppedLine_isReached() {
        Configurator.setLevel( LOGGER_NAME, Level.DEBUG );

        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "short" ) ) );

        // prefilterEnabled + skipTooShort with a huge minTokens floor -> this chunk is dropped.
        final EntityExtractorConfig cfg = new EntityExtractorConfig(
            "ollama", "claude-haiku-4-5", "model", "http://x", 120_000L, 0.6, 200, 5_000L, 2,
            /*prefilterEnabled*/ true, /*dryRun*/ false, /*skipPureCode*/ false,
            /*skipNoProperNoun*/ false, /*skipTooShort*/ true, /*minTokens*/ 10_000 );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, cfg, new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ),
                new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( AsyncEntityExtractionListener.RunResult.EMPTY, result );
            verify( extractor, never() ).extract( any(), any() );
        }
    }

    @Test
    void debugLogging_rateLimitSkipLine_isReachedViaAccept() {
        Configurator.setLevel( LOGGER_NAME, Level.DEBUG );

        final EntityExtractor extractor = mockExtractor();
        when( extractor.extract( any(), any() ) ).thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Some prose here." ) ) );

        // Only accept() (not runExtractionSync) applies the rate limit.
        final ImmediateExecutorService direct = new ImmediateExecutorService();
        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry(), direct ) ) {
            listener.accept( List.of( chunkId ) );
            listener.accept( List.of( chunkId ) );   // same page, immediately -> rate-limited
            verify( extractor, Mockito.times( 1 ) ).extract( any(), any() );
        }
    }

    // ---- passesRateLimit: minInterval <= 0 always passes ----

    @Test
    void rateLimitDisabled_whenMinIntervalIsZero_allowsBackToBackRuns() {
        final EntityExtractor extractor = mockExtractor();
        when( extractor.extract( any(), any() ) ).thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Some prose here." ) ) );

        final EntityExtractorConfig cfg = configWith( 120_000L, 0.6, 200, /*perPageMinIntervalMs*/ 0L );
        final ImmediateExecutorService direct = new ImmediateExecutorService();
        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, cfg, new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry(), direct ) ) {
            listener.accept( List.of( chunkId ) );
            listener.accept( List.of( chunkId ) );
            verify( extractor, Mockito.times( 2 ) ).extract( any(), any() );
        }
    }

    // ---- outer runExtraction catch ----

    @Test
    void findByIdsThrowing_isCaughtByOuterCatchAndReturnsEmpty() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.chunkRepo.findByIds( any() ) ).thenThrow( new RuntimeException( "db down" ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( UUID.randomUUID() ) );
            assertEquals( AsyncEntityExtractionListener.RunResult.EMPTY, result );
        }
    }

    // ---- persistMentions: getNodeByName fallback (success + failure) ----

    @Test
    void mentionNotInDictionary_resolvesViaGetNodeByNameFallback() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() ); // empty dictionary
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Alice runs Acme." ) ) );
        when( extractor.extract( any(), any() ) ).thenReturn( new ExtractionResult(
            List.of(), List.of(), List.of( new ExtractedMention( chunkId, "Alice", 0.9 ) ), "test", Duration.ZERO ) );
        final UUID aliceId = UUID.randomUUID();
        when( r.nodeRepo.getNodeByName( "Alice" ) ).thenReturn( new KgNode(
            aliceId, "Alice", "person", "PageA", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) );
        when( r.mentionRepo.upsertAll( any() ) ).thenReturn( 1 );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( 1, result.mentionsWritten() );
            verify( r.mentionRepo ).upsertAll( Mockito.argThat( rows ->
                rows.size() == 1 && rows.get( 0 ).nodeId().equals( aliceId ) ) );
        }
    }

    @Test
    void mentionNotInDictionary_getNodeByNameThrows_mentionIsDropped() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Alice runs Acme." ) ) );
        when( extractor.extract( any(), any() ) ).thenReturn( new ExtractionResult(
            List.of(), List.of(), List.of( new ExtractedMention( chunkId, "Alice", 0.9 ) ), "test", Duration.ZERO ) );
        when( r.nodeRepo.getNodeByName( "Alice" ) ).thenThrow( new RuntimeException( "lookup boom" ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( 0, result.mentionsWritten(), "an unresolvable mention name must be dropped, not thrown" );
            verify( r.mentionRepo, never() ).upsertAll( any() );
        }
    }

    // ---- persistMentions: upsertAll throwing ----

    @Test
    void upsertAllThrows_isCaughtAndReturnsZero() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        final UUID nodeId = UUID.randomUUID();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of(
            new KgNode( nodeId, "Alice", "person", "PageA", Provenance.HUMAN_AUTHORED, Map.of(),
                null, null, "human", null ) ) );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Alice runs Acme." ) ) );
        when( extractor.extract( any(), any() ) ).thenReturn( new ExtractionResult(
            List.of(), List.of(), List.of( new ExtractedMention( chunkId, "Alice", 0.9 ) ), "test", Duration.ZERO ) );
        when( r.mentionRepo.upsertAll( any() ) ).thenThrow( new RuntimeException( "insert boom" ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( 0, result.mentionsWritten() );
        }
    }

    // ---- persistProposals: node insert throws, edge below-threshold skip, edge insert throws ----

    @Test
    void nodeProposalInsertThrows_isCaughtAndSkipped() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Some prose." ) ) );
        when( extractor.extract( any(), any() ) ).thenReturn( new ExtractionResult(
            List.of( new ProposedNode( "NewThing", "concept", Map.of(), 0.9, "because" ) ),
            List.of(), List.of(), "test", Duration.ZERO ) );
        when( r.proposalRepo.insertProposal( Mockito.eq( "new-node" ), any(), any(), Mockito.anyDouble(), any() ) )
            .thenThrow( new RuntimeException( "insert boom" ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( 0, result.proposalsFiled(), "a failed insert must not count as filed" );
        }
    }

    @Test
    void edgeBelowConfidenceThreshold_isSkippedWithoutCheckingRejections() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Some prose." ) ) );
        // confidenceThreshold defaults to 0.6 in enabledConfig(); 0.1 must be skipped.
        when( extractor.extract( any(), any() ) ).thenReturn( new ExtractionResult(
            List.of(), List.of( new ProposedEdge( "A", "B", "related_to", Map.of(), 0.1, "weak" ) ),
            List.of(), "test", Duration.ZERO ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( 0, result.proposalsFiled() );
            verify( r.rejectionRepo, never() ).isRejected( any(), any(), any() );
            verify( r.proposalRepo, never() ).insertProposal( any(), any(), any(), Mockito.anyDouble(), any() );
        }
    }

    @Test
    void edgeProposalInsertThrows_isCaughtAndSkipped() {
        final EntityExtractor extractor = mockExtractor();
        final Repos r = Repos.empty();
        when( r.nodeRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final UUID chunkId = UUID.randomUUID();
        when( r.chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( chunkId, "PageA", 0, List.of(), "Some prose." ) ) );
        when( extractor.extract( any(), any() ) ).thenReturn( new ExtractionResult(
            List.of(), List.of( new ProposedEdge( "A", "B", "related_to", Map.of(), 0.9, "strong" ) ),
            List.of(), "test", Duration.ZERO ) );
        when( r.rejectionRepo.isRejected( "A", "B", "related_to" ) ).thenReturn( false );
        when( r.proposalRepo.insertProposal( Mockito.eq( "new-edge" ), any(), any(), Mockito.anyDouble(), any() ) )
            .thenThrow( new RuntimeException( "insert boom" ) );

        try ( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry() ) ) {
            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( chunkId ) );
            assertEquals( 0, result.proposalsFiled() );
        }
    }

    // ---- close(): owned-executor branches, via the package-private all-args ctor ----

    @Test
    void close_ownsExecutorTrue_normalShutdown_doesNotForce() throws Exception {
        final ExecutorService pool = mock( ExecutorService.class );
        when( pool.awaitTermination( 5, TimeUnit.SECONDS ) ).thenReturn( true );
        newListenerWithOwnedExecutor( pool ).close();
        verify( pool ).shutdown();
        verify( pool, never() ).shutdownNow();
    }

    @Test
    void close_ownsExecutorTrue_timeoutForcesShutdownNow() throws Exception {
        final ExecutorService pool = mock( ExecutorService.class );
        when( pool.awaitTermination( 5, TimeUnit.SECONDS ) ).thenReturn( false );
        newListenerWithOwnedExecutor( pool ).close();
        verify( pool ).shutdownNow();
    }

    @Test
    void close_ownsExecutorTrue_interrupted_forcesShutdownNow() throws Exception {
        final ExecutorService pool = mock( ExecutorService.class );
        when( pool.awaitTermination( 5, TimeUnit.SECONDS ) ).thenThrow( new InterruptedException( "stop" ) );
        try {
            newListenerWithOwnedExecutor( pool ).close();
            verify( pool ).shutdownNow();
            assertTrue( Thread.interrupted(), "close() must re-set this thread's interrupt flag" );
        } finally {
            Thread.interrupted();   // ensure cleared regardless of assertion outcome
        }
    }

    private static AsyncEntityExtractionListener newListenerWithOwnedExecutor( final ExecutorService pool ) {
        final Repos r = Repos.empty();
        return new AsyncEntityExtractionListener(
            mockExtractor(), enabledConfig(), new AsyncEntityExtractionListener.Repositories( r.chunkRepo, r.mentionRepo, r.nodeRepo, r.proposalRepo, r.rejectionRepo ), new SimpleMeterRegistry(),
            new AsyncEntityExtractionListener.ExecutionSetup( pool, /*ownsExecutor*/ true, /*excludedPages*/ null ) );
    }

    // ---- helpers ----

    private static EntityExtractor mockExtractor() {
        final EntityExtractor extractor = Mockito.mock( EntityExtractor.class );
        when( extractor.code() ).thenReturn( "test" );
        return extractor;
    }

    private static EntityExtractorConfig enabledConfig() {
        return configWith( 120_000L, 0.6, 200, 5_000L );
    }

    private static EntityExtractorConfig configWith( final long timeoutMs, final double confidenceThreshold,
                                                       final int maxExistingNodes, final long perPageMinIntervalMs ) {
        return new EntityExtractorConfig(
            "ollama", "claude-haiku-4-5", "model", "http://x", timeoutMs, confidenceThreshold,
            maxExistingNodes, perPageMinIntervalMs, 2,
            /*prefilterEnabled*/ false, false, false, false, false, 0 );
    }

    /** Groups the five repository mocks, mirroring the pattern in the sibling test classes. */
    private static final class Repos {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final KgNodeRepository nodeRepo = Mockito.mock( KgNodeRepository.class );
        final KgProposalRepository proposalRepo = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejectionRepo = Mockito.mock( KgRejectionRepository.class );

        static Repos empty() { return new Repos(); }
    }

    /** Runs every submitted task synchronously on the calling thread. */
    private static final class ImmediateExecutorService extends java.util.concurrent.AbstractExecutorService {
        @Override public void execute( final Runnable command ) { command.run(); }
        @Override public void shutdown() {}
        @Override public List< Runnable > shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination( final long timeout, final TimeUnit unit ) { return true; }
    }
}
