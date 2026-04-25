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
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncEntityExtractionListenerPrefilterTest {

    @Test
    void filterSkipsChunksWithNoProperNoun() {
        final EntityExtractor extractor = Mockito.mock( EntityExtractor.class );
        when( extractor.code() ).thenReturn( "test" );
        when( extractor.extract( any(), any() ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final JdbcKnowledgeRepository kgRepo = Mockito.mock( JdbcKnowledgeRepository.class );
        // Existing-nodes lookup goes through queryNodes(Map, String, int, int).
        when( kgRepo.queryNodes( anyMap(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        final UUID keep = UUID.randomUUID();
        final UUID skip = UUID.randomUUID();
        when( chunkRepo.findByIds( List.of( keep, skip ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( keep, "P", 0, List.of(),
                "PostgreSQL is a database." ),
            new ContentChunkRepository.MentionableChunk( skip, "P", 1, List.of(),
                "lower case only." )
        ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.enabled", "true" );
        // Pin the predicate under test so a future default flip in
        // EntityExtractorConfig can't quietly turn this assertion into a no-op.
        p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "true" );
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        final ExecutorService inline = Executors.newSingleThreadExecutor();
        try( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                 extractor, cfg, chunkRepo, mentionRepo, kgRepo,
                 new SimpleMeterRegistry(), inline ) ) {

            final AsyncEntityExtractionListener.RunResult res =
                listener.runExtractionSync( List.of( keep, skip ) );

            assertEquals( 0, res.mentionsWritten() );
            verify( extractor, times( 1 ) ).extract( any(), any() );
            // The kept chunk's id matches what was extracted; the skipped one was never seen.
            verify( extractor ).extract(
                Mockito.argThat( ec -> ec != null && ec.id().equals( keep ) ),
                any() );
        } finally {
            inline.shutdown();
        }
    }
}
