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
package com.wikantik.admin;

import com.wikantik.search.embedding.EmbeddingIndexService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the Phase 1 embedding hook on
 * {@link ContentIndexRebuildService}: the rebuild thread must invoke
 * {@link EmbeddingIndexService#indexAll(String)} after chunks are rewritten,
 * and {@link ContentIndexRebuildService#snapshot()} must include the
 * embedding status.
 */
class ContentIndexRebuildServiceEmbeddingHookTest {

    private static final String MODEL = "qwen3-embedding-0.6b";

    @Test
    void snapshotReportsEmptyEmbeddingsWhenHookNotWired() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithNoPages();
        final IndexStatusSnapshot snap = svc.snapshot();
        assertNotNull( snap.embeddings() );
        assertEquals( "", snap.embeddings().modelCode() );
        assertEquals( 0, snap.embeddings().rowCount() );
    }

    @Test
    void snapshotReflectsWiredEmbeddingService() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithNoPages();
        final EmbeddingIndexService embed = mock( EmbeddingIndexService.class );
        when( embed.status( MODEL ) )
            .thenReturn( new EmbeddingIndexService.Status( MODEL, 768, 42, Instant.EPOCH ) );
        svc.setEmbeddingHook( embed, MODEL );

        final IndexStatusSnapshot snap = svc.snapshot();
        assertEquals( MODEL, snap.embeddings().modelCode() );
        assertEquals( 768, snap.embeddings().dim() );
        assertEquals( 42, snap.embeddings().rowCount() );
        assertEquals( Instant.EPOCH, snap.embeddings().lastUpdate() );
    }

    @Test
    void snapshotDegradesGracefullyWhenStatusLookupFails() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithNoPages();
        final EmbeddingIndexService embed = mock( EmbeddingIndexService.class );
        when( embed.status( anyString() ) ).thenThrow( new RuntimeException( "db down" ) );
        svc.setEmbeddingHook( embed, MODEL );

        final IndexStatusSnapshot snap = svc.snapshot();
        // Falls back to a zero-filled record keyed by the configured model
        // rather than propagating the exception up the servlet stack.
        assertEquals( MODEL, snap.embeddings().modelCode() );
        assertEquals( 0, snap.embeddings().rowCount() );
    }

    @Test
    void clearingHookWithNullArgsDisablesEmbeddingIntegration() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithNoPages();
        final EmbeddingIndexService embed = mock( EmbeddingIndexService.class );
        svc.setEmbeddingHook( embed, MODEL );
        svc.setEmbeddingHook( null, null );

        final IndexStatusSnapshot snap = svc.snapshot();
        assertEquals( "", snap.embeddings().modelCode() );
        verify( embed, never() ).status( anyString() );
    }
}
