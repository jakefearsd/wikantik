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

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.search.embedding.EmbeddingIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContentIndexRebuildService}, driving {@code runRebuild()} directly
 * (protected, same-package access) rather than through {@code triggerRebuild()}'s background
 * thread, so each phase's error-handling branch can be exercised deterministically with mocked
 * collaborators. The happy-path per-page loop is exercised incidentally by several tests; the
 * primary intent here is each documented failure/edge branch (STARTING wipe failure,
 * {@code getAllPages} failure, per-page Lucene enqueue failure, the embedding hook's error
 * isolation, the outer fatal-exception catch, and {@code drainLucene()}'s interrupt path).
 */
class ContentIndexRebuildServiceTest {

    private PageManager pages;
    private SystemPageRegistry systemPages;
    private LuceneReindexQueue lucene;
    private ContentChunkRepository chunkRepo;
    private ContentChunker chunker;
    private ContentIndexRebuildService service;

    @BeforeEach
    void setUp() throws Exception {
        pages = mock( PageManager.class );
        systemPages = mock( SystemPageRegistry.class );
        lucene = mock( LuceneReindexQueue.class );
        chunkRepo = mock( ContentChunkRepository.class );
        chunker = mock( ContentChunker.class );

        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );
        when( chunkRepo.findByPage( anyString() ) ).thenReturn( List.of() );
        when( chunker.chunk( anyString(), any() ) ).thenReturn( List.of() );
        when( pages.getAllPages() ).thenReturn( List.of() );

        service = new ContentIndexRebuildService( pages, systemPages, lucene, chunkRepo, chunker,
                () -> true, 5L );
    }

    @AfterEach
    void clearInterruptFlag() {
        // A test below deliberately arms the interrupt flag on this (JUnit worker) thread;
        // make sure it never leaks into a later test on a reused thread.
        Thread.interrupted();
    }

    private static Page mockPage( final String name ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    // ---- gauge callbacks --------------------------------------------------------------------

    @Test
    void gaugeCallbacksReflectLiveCountersAfterARun() throws Exception {
        final Page system = mockPage( "SystemPage" );
        final Page normal = mockPage( "NormalPage" );
        when( pages.getAllPages() ).thenReturn( List.of( system, normal ) );
        when( systemPages.isSystemPage( "SystemPage" ) ).thenReturn( true );
        when( systemPages.isSystemPage( "NormalPage" ) ).thenReturn( false );
        when( pages.getPureText( normal ) ).thenReturn( "some content" );

        service.runRebuild();

        assertEquals( 2, service.getPagesIterated() );
        assertEquals( 1, service.getPagesChunked() );
        assertEquals( 1, service.getSystemPagesSkipped() );
    }

    // ---- STARTING phase failure -------------------------------------------------------------

    @Test
    void startingPhaseFailureRecordsErrorAndReturnsToIdle() throws Exception {
        doThrow( new RuntimeException( "index wipe boom" ) ).when( lucene ).clearIndex();

        service.runRebuild();

        assertEquals( ContentIndexRebuildService.State.IDLE, service.currentState() );
        final IndexStatusSnapshot snap = service.snapshot();
        assertEquals( 1, snap.rebuild().errors().size() );
        assertEquals( "<starting>", snap.rebuild().errors().get( 0 ).page() );
    }

    // ---- getAllPages() failures in runRebuild() ----------------------------------------------

    @Test
    void getAllPagesProviderExceptionDuringRebuildRecordsErrorAndReturns() throws Exception {
        when( pages.getAllPages() ).thenThrow( new ProviderException( "provider down" ) );

        service.runRebuild();

        final IndexStatusSnapshot snap = service.snapshot();
        assertEquals( "<get-all-pages>", snap.rebuild().errors().get( 0 ).page() );
        assertEquals( ContentIndexRebuildService.State.IDLE, service.currentState() );
    }

    @Test
    void getAllPagesRuntimeExceptionDuringRebuildRecordsErrorAndReturns() throws Exception {
        when( pages.getAllPages() ).thenThrow( new RuntimeException( "unexpected boom" ) );

        service.runRebuild();

        // snapshot() would itself re-invoke the still-throwing getAllPages() stub (via
        // safeGetAllPages(), which only tolerates ProviderException) — read the protected
        // errors list directly instead of going through snapshot() here.
        assertEquals( 1, service.errors.size() );
        assertEquals( "<get-all-pages>", service.errors.get( 0 ).page() );
        assertEquals( ContentIndexRebuildService.State.IDLE, service.currentState() );
    }

    // ---- per-page Lucene enqueue failure ------------------------------------------------------

    @Test
    void luceneReindexFailurePerPageIsRecordedAndLoopContinues() throws Exception {
        final Page a = mockPage( "A" );
        when( pages.getAllPages() ).thenReturn( List.of( a ) );
        when( systemPages.isSystemPage( "A" ) ).thenReturn( false );
        when( pages.getPureText( a ) ).thenReturn( "content" );
        doThrow( new RuntimeException( "lucene enqueue down" ) ).when( lucene ).reindexPage( a );

        service.runRebuild();

        final IndexStatusSnapshot snap = service.snapshot();
        assertTrue( snap.rebuild().errors().stream().anyMatch( e -> e.page().equals( "A" ) ),
                snap.rebuild().errors().toString() );
        // The chunking half of the per-page work still succeeded despite the Lucene failure.
        assertEquals( 1, service.getPagesChunked() );
    }

    // ---- embedding hook error isolation --------------------------------------------------------

    @Test
    void embeddingHookFailureIsRecordedButRebuildStillReachesIdle() throws Exception {
        final EmbeddingIndexService embedSvc = mock( EmbeddingIndexService.class );
        when( embedSvc.indexAll( eq( "model-x" ), any() ) ).thenThrow( new RuntimeException( "embed backend down" ) );
        service.setEmbeddingHook( embedSvc, "model-x" );

        service.runRebuild();

        final IndexStatusSnapshot snap = service.snapshot();
        assertTrue( snap.rebuild().errors().stream().anyMatch( e -> e.page().equals( "<embedding-indexer>" ) ),
                snap.rebuild().errors().toString() );
        assertEquals( ContentIndexRebuildService.State.IDLE, service.currentState() );
    }

    @Test
    void settingEmbeddingHookToNullClearsIt() throws Exception {
        final EmbeddingIndexService embedSvc = mock( EmbeddingIndexService.class );
        service.setEmbeddingHook( embedSvc, "model-x" );
        service.setEmbeddingHook( null, "model-x" );

        service.runRebuild();

        // With the hook cleared, indexAll() must never be invoked.
        org.mockito.Mockito.verify( embedSvc, org.mockito.Mockito.never() ).indexAll( anyString(), any() );
    }

    // ---- outer fatal-exception catch -----------------------------------------------------------

    @Test
    void fatalExceptionDuringDrainIsCaughtRecordedAndReturnsToIdle() throws Exception {
        doThrow( new RuntimeException( "queue depth broke" ) ).when( lucene ).queueDepth();

        service.runRebuild();

        // snapshot() itself calls lucene.queueDepth() (uncaught there) — read the protected
        // errors list directly instead of going through snapshot() here.
        assertTrue( service.errors.stream().anyMatch( e -> e.page().equals( "<rebuild>" ) ),
                service.errors.toString() );
        assertEquals( ContentIndexRebuildService.State.IDLE, service.currentState() );
    }

    // ---- drainLucene() interrupt handling -------------------------------------------------------

    @Test
    void drainLuceneReturnsEarlyAndRestoresInterruptFlagWhenInterrupted() throws Exception {
        when( lucene.queueDepth() ).thenReturn( 1 ); // never naturally reaches zero
        Thread.currentThread().interrupt();

        service.runRebuild();

        assertTrue( Thread.interrupted(), "interrupt flag should have been restored by drainLucene()" );
        assertEquals( ContentIndexRebuildService.State.IDLE, service.currentState() );
    }

    // ---- snapshot() degrades gracefully when getAllPages() fails -------------------------------

    @Test
    void snapshotDegradesToEmptyPageCountsWhenGetAllPagesFails() throws Exception {
        when( pages.getAllPages() ).thenThrow( new ProviderException( "boom" ) );

        final IndexStatusSnapshot snap = service.snapshot();

        assertEquals( 0, snap.pages().total() );
        assertEquals( 0, snap.pages().system() );
        assertEquals( 0, snap.pages().indexable() );
    }
}
