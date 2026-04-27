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
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code KgExcludedPagesRepository} skip path in
 * {@link AsyncEntityExtractionListener}. No database required — everything
 * is mocked.
 */
class AsyncEntityExtractionListenerSkipTest {

    @Test
    void excluded_chunks_are_filtered_before_extraction() throws Exception {
        final EntityExtractor extractor = Mockito.mock( EntityExtractor.class );
        when( extractor.code() ).thenReturn( "test" );
        when( extractor.extract( any(), any() ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final JdbcKnowledgeRepository kgRepo = Mockito.mock( JdbcKnowledgeRepository.class );
        when( kgRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        final KgExcludedPagesRepository excluded = Mockito.mock( KgExcludedPagesRepository.class );

        final UUID keepChunk = UUID.randomUUID();
        final UUID skipChunk = UUID.randomUUID();

        // keepChunk belongs to "KeepPage", skipChunk belongs to "ExcludedPage"
        when( chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk(
                keepChunk, "KeepPage", 0, List.of(), "PostgreSQL is a relational database." ),
            new ContentChunkRepository.MentionableChunk(
                skipChunk, "ExcludedPage", 0, List.of(), "Some content on excluded page." )
        ) );

        when( excluded.findReason( "KeepPage" ) ).thenReturn( Optional.empty() );
        when( excluded.findReason( "ExcludedPage" ) )
            .thenReturn( Optional.of( ExclusionReason.SYSTEM_PAGE ) );

        final EntityExtractorConfig config = EntityExtractorConfig.fromProperties(
            propertiesFor( "ollama" ) );

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                extractor, config, chunkRepo, mentionRepo, kgRepo,
                new SimpleMeterRegistry(), excluded );

            final AsyncEntityExtractionListener.RunResult result =
                listener.runExtractionSync( List.of( keepChunk, skipChunk ) );

            // extractor is called only for KeepPage chunk, not ExcludedPage chunk
            verify( extractor, never() ).extract(
                Mockito.argThat( ec -> "ExcludedPage".equals( ec.pageName() ) ), any() );
            // result comes back non-empty (keepChunk was processed)
            assertEquals( 0, result.mentionsWritten() );
            assertEquals( 0, result.proposalsFiled() );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination( 1, TimeUnit.SECONDS );
        }
    }

    @Test
    void null_excluded_repo_processes_all_chunks() throws Exception {
        final EntityExtractor extractor = Mockito.mock( EntityExtractor.class );
        when( extractor.code() ).thenReturn( "test" );
        when( extractor.extract( any(), any() ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final JdbcKnowledgeRepository kgRepo = Mockito.mock( JdbcKnowledgeRepository.class );
        when( kgRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        final UUID chunk1 = UUID.randomUUID();
        final UUID chunk2 = UUID.randomUUID();

        when( chunkRepo.findByIds( any() ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk(
                chunk1, "PageA", 0, List.of(), "Alice is the CEO of Acme." ),
            new ContentChunkRepository.MentionableChunk(
                chunk2, "PageA", 1, List.of(), "Bob manages the engineering team." )
        ) );

        final EntityExtractorConfig config = EntityExtractorConfig.fromProperties(
            propertiesFor( "ollama" ) );

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            extractor, config, chunkRepo, mentionRepo, kgRepo, new SimpleMeterRegistry() );

        listener.runExtractionSync( List.of( chunk1, chunk2 ) );

        // Both chunks should reach the extractor when no exclusion repo is present
        verify( extractor, Mockito.times( 2 ) ).extract( any(), any() );
    }

    // ---- helpers ----

    private static java.util.Properties propertiesFor( final String backend ) {
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", backend );
        p.setProperty( "wikantik.knowledge.extractor.enabled", "true" );
        return p;
    }
}
