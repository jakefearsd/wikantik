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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * Drives the rebuild loop end-to-end with a real (3-page) corpus and a mock
     * embedder. The mock blocks inside indexAll, fires two batch-progress
     * callbacks, then unblocks — giving the test a synchronization point at
     * which {@code state == EMBEDDING} and {@code embeddingsIndexed > 0} are
     * both observable from {@code snapshot()}. This is the contract the admin
     * UI's progress bar relies on.
     */
    @Test
    void embeddingPhaseTransitionsToEmbeddingStateAndStreamsProgressCounter() throws Exception {
        final ContentIndexRebuildService svc = RebuildTestFactory.build( 3, 0 );
        final EmbeddingIndexService embed = mock( EmbeddingIndexService.class );
        when( embed.status( MODEL ) )
            .thenReturn( new EmbeddingIndexService.Status( MODEL, 768, 0, null ) );
        final CountDownLatch midRun = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        when( embed.indexAll( eq( MODEL ), any( IntConsumer.class ) ) )
            .thenAnswer( inv -> {
                final IntConsumer cb = inv.getArgument( 1, IntConsumer.class );
                cb.accept( 5 );  // first batch flushed — UI should see 5
                midRun.countDown();
                if ( !release.await( 5, TimeUnit.SECONDS ) ) {
                    throw new AssertionError( "test never released embedding mock" );
                }
                cb.accept( 12 );  // second batch — final running count before commit
                return 12;        // returned upserted total
            } );
        svc.setEmbeddingHook( embed, MODEL );

        svc.triggerRebuild();
        assertTrue( midRun.await( 5, TimeUnit.SECONDS ),
            "embedding phase never reached the mid-run sync point" );

        // Mid-run snapshot: state must be EMBEDDING and the live counter must
        // reflect the first batch the mock streamed via the IntConsumer.
        final IndexStatusSnapshot mid = svc.snapshot();
        assertEquals( "EMBEDDING", mid.rebuild().state() );
        assertEquals( 5, mid.rebuild().embeddingsIndexed() );

        release.countDown();

        // After completion, the rebuild returns to IDLE and the counter holds
        // the total upserted value reported by indexAll.
        for ( int i = 0; i < 200 && !"IDLE".equals( svc.snapshot().rebuild().state() ); i++ ) {
            Thread.sleep( 10 );
        }
        final IndexStatusSnapshot end = svc.snapshot();
        assertEquals( "IDLE", end.rebuild().state() );
        assertEquals( 12, end.rebuild().embeddingsIndexed() );
    }
}
