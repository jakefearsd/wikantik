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
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.Chunk;
import com.wikantik.knowledge.chunking.ChunkDiff;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mockito.invocation.InvocationOnMock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test-support factory for {@link ContentIndexRebuildService}. Hand-rolls
 * lightweight doubles so state-machine tests don't need the testcontainers
 * harness — only the run-loop body will need real storage in Task 10.
 */
final class RebuildTestFactory {

    private RebuildTestFactory() {}

    /**
     * Service over a PageManager that returns zero pages. Rebuild is enabled;
     * the stub {@code runRebuild()} immediately returns to IDLE.
     */
    static ContentIndexRebuildService buildWithNoPages() {
        return baseBuilder( () -> true ).build();
    }

    /**
     * Service whose stub run loop blocks on a latch, so the test can observe
     * a non-IDLE state after the first trigger and assert that a second
     * trigger throws {@link ContentIndexRebuildService.ConflictException}.
     *
     * <p>The blocking is implemented by subclassing the production class and
     * overriding the {@code runRebuild()} seam — the production class stays
     * clean of test hooks.</p>
     */
    static BlockingHandle buildWithBlockingPage() {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final Builder b = baseBuilder( () -> true );
        final ContentIndexRebuildService svc = new ContentIndexRebuildService(
                b.pages, b.systemPages, b.lucene, b.chunkRepo, b.chunker,
                b.rebuildEnabled, b.luceneDrainPollMs ) {
            @Override
            protected void runRebuild() {
                setState( State.RUNNING );
                entered.countDown();
                try {
                    if ( !release.await( 10, TimeUnit.SECONDS ) ) {
                        LOG.warn( "release latch timed out in blocking test runRebuild" );
                    }
                } catch ( final InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                    LOG.warn( "runRebuild interrupted while awaiting release latch", ie );
                } finally {
                    setState( State.IDLE );
                }
            }
        };
        return new BlockingHandle( svc, entered, release );
    }

    /** Service with the kill-switch supplier returning false. */
    static ContentIndexRebuildService buildWithRebuildDisabled() {
        return baseBuilder( () -> false ).build();
    }

    // ---- internals ----

    private static final Logger LOG = LogManager.getLogger( RebuildTestFactory.class );

    /**
     * Handle returned by {@link #buildWithBlockingPage()} so the test can
     * await the RUNNING transition and release the stub loop at end-of-test.
     */
    static final class BlockingHandle {
        final ContentIndexRebuildService service;
        final CountDownLatch entered;
        final CountDownLatch release;

        BlockingHandle( final ContentIndexRebuildService svc,
                        final CountDownLatch entered,
                        final CountDownLatch release ) {
            this.service = svc;
            this.entered = entered;
            this.release = release;
        }

        boolean awaitRunning( final long millis ) throws InterruptedException {
            return entered.await( millis, TimeUnit.MILLISECONDS );
        }

        void releaseRunLoop() {
            release.countDown();
        }
    }

    private static Builder baseBuilder( final BooleanSupplier enabled ) {
        final PageManager pm = mock( PageManager.class );
        try {
            lenient().when( pm.getAllPages() ).thenReturn( Collections.< Page >emptyList() );
        } catch ( final Exception e ) {
            // Mockito's lenient stub cannot throw at stubbing time — this try/catch
            // only exists because getAllPages() declares a checked ProviderException.
            throw new AssertionError( "unreachable: stubbing cannot throw", e );
        }

        final SystemPageRegistry sp = mock( SystemPageRegistry.class );
        lenient().when( sp.isSystemPage( anyString() ) ).thenReturn( false );

        final LuceneReindexQueue lucene = new NoopLuceneQueue();

        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.stats() )
            .thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );

        final ContentChunker chunker =
            new ContentChunker( new ContentChunker.Config( 900, 8 ) );

        return new Builder( pm, sp, lucene, chunkRepo, chunker, enabled, 25L );
    }

    private static final class Builder {
        final PageManager pages;
        final SystemPageRegistry systemPages;
        final LuceneReindexQueue lucene;
        final ContentChunkRepository chunkRepo;
        final ContentChunker chunker;
        final BooleanSupplier rebuildEnabled;
        final long luceneDrainPollMs;

        Builder( final PageManager pages,
                 final SystemPageRegistry systemPages,
                 final LuceneReindexQueue lucene,
                 final ContentChunkRepository chunkRepo,
                 final ContentChunker chunker,
                 final BooleanSupplier rebuildEnabled,
                 final long luceneDrainPollMs ) {
            this.pages = pages;
            this.systemPages = systemPages;
            this.lucene = lucene;
            this.chunkRepo = chunkRepo;
            this.chunker = chunker;
            this.rebuildEnabled = rebuildEnabled;
            this.luceneDrainPollMs = luceneDrainPollMs;
        }

        ContentIndexRebuildService build() {
            return new ContentIndexRebuildService(
                pages, systemPages, lucene, chunkRepo, chunker,
                rebuildEnabled, luceneDrainPollMs );
        }
    }

    /** Minimal {@link LuceneReindexQueue} for tests that never index. */
    private static final class NoopLuceneQueue implements LuceneReindexQueue {
        @Override public void reindexPage( final Page page ) {}
        @Override public int queueDepth() { return 0; }
        @Override public int documentCount() { return 0; }
        @Override public Instant lastUpdateInstant() { return null; }
        @Override public void clearIndex() {}
    }

    // -------------------------------------------------------------------
    // Task 10 extensions: run-loop test fixtures
    // -------------------------------------------------------------------

    /** Shared counters observable via the static accessor methods. */
    private static final AtomicInteger LUCENE_CLEAR_CALLS = new AtomicInteger( 0 );
    private static final AtomicInteger CHUNKS_DELETE_ALL_CALLS = new AtomicInteger( 0 );

    /** Test accessor: true iff lucene.clearIndex() was called at least once in the current fixture. */
    static boolean luceneClearedAtLeastOnce() {
        return LUCENE_CLEAR_CALLS.get() > 0;
    }

    /** Test accessor: true iff chunkRepo.deleteAll() was called at least once in the current fixture. */
    static boolean chunksClearedAtLeastOnce() {
        return CHUNKS_DELETE_ALL_CALLS.get() > 0;
    }

    private static void resetCallCounters() {
        LUCENE_CLEAR_CALLS.set( 0 );
        CHUNKS_DELETE_ALL_CALLS.set( 0 );
    }

    /**
     * Builds a service over {@code regular} regular pages and {@code system}
     * system pages. Each page has a body long enough to produce at least
     * one chunk. Uses an in-memory fake {@link ContentChunkRepository} that
     * tracks writes per page, and an in-memory {@link LuceneReindexQueue}
     * that never actually indexes.
     */
    static ContentIndexRebuildService build( final int regular, final int system ) {
        resetCallCounters();
        final List< Page > allPages = new ArrayList<>();
        final Set< String > systemNames = new HashSet<>();
        for ( int i = 1; i <= regular; i++ ) {
            allPages.add( fakePage( "Page" + i, longBody( "Page" + i ) ) );
        }
        for ( int i = 1; i <= system; i++ ) {
            final String name = "SysPage" + i;
            systemNames.add( name );
            allPages.add( fakePage( name, longBody( name ) ) );
        }

        final PageManager pm = mock( PageManager.class );
        try {
            lenient().when( pm.getAllPages() ).thenReturn( allPages );
        } catch ( final Exception e ) {
            throw new AssertionError( "unreachable", e );
        }
        lenient().when( pm.getPureText( any( Page.class ) ) ).thenAnswer(
            (InvocationOnMock inv) -> {
                final Page p = inv.getArgument( 0, Page.class );
                return p.<String>getAttribute( "__body" );
            } );

        final SystemPageRegistry sp = mock( SystemPageRegistry.class );
        lenient().when( sp.isSystemPage( anyString() ) ).thenAnswer(
            (InvocationOnMock inv) -> systemNames.contains( inv.getArgument( 0, String.class ) ) );

        final ContentChunkRepository repo = fakeChunkRepo();
        final LuceneReindexQueue lucene = fakeLuceneQueue();

        final ContentChunker chunker =
            new ContentChunker( new ContentChunker.Config( 900, 8 ) );

        return new ContentIndexRebuildService(
            pm, sp, lucene, repo, chunker, () -> true, 10L );
    }

    /**
     * Builds a service over three regular pages where the chunker throws
     * for the named page only.
     */
    static ContentIndexRebuildService buildWithThrowingChunker( final String throwingPageName ) {
        resetCallCounters();
        final List< Page > allPages = new ArrayList<>();
        for ( int i = 1; i <= 3; i++ ) {
            allPages.add( fakePage( "Page" + i, longBody( "Page" + i ) ) );
        }

        final PageManager pm = mock( PageManager.class );
        try {
            lenient().when( pm.getAllPages() ).thenReturn( allPages );
        } catch ( final Exception e ) {
            throw new AssertionError( "unreachable", e );
        }
        lenient().when( pm.getPureText( any( Page.class ) ) ).thenAnswer(
            (InvocationOnMock inv) -> inv.getArgument( 0, Page.class ).<String>getAttribute( "__body" ) );

        final SystemPageRegistry sp = mock( SystemPageRegistry.class );
        lenient().when( sp.isSystemPage( anyString() ) ).thenReturn( false );

        final ContentChunkRepository repo = fakeChunkRepo();
        final LuceneReindexQueue lucene = fakeLuceneQueue();

        final ContentChunker chunker = new ContentChunker(
                new ContentChunker.Config( 900, 8 ) ) {
            @Override
            public List< Chunk > chunk( final String pageName, final ParsedPage page ) {
                if ( throwingPageName.equals( pageName ) ) {
                    throw new RuntimeException( "boom for " + pageName );
                }
                return super.chunk( pageName, page );
            }
        };

        return new ContentIndexRebuildService(
            pm, sp, lucene, repo, chunker, () -> true, 10L );
    }

    /**
     * Builds a service over two regular pages whose backing chunk table is
     * pre-populated. Also instruments both the fake {@link LuceneReindexQueue}
     * and the fake {@link ContentChunkRepository} to record
     * {@code clearIndex()} / {@code deleteAll()} calls so the test can assert
     * the STARTING phase truly wiped both.
     */
    static ContentIndexRebuildService buildWithPreloadedChunks() {
        resetCallCounters();
        final List< Page > allPages = new ArrayList<>();
        allPages.add( fakePage( "Page1", longBody( "Page1" ) ) );
        allPages.add( fakePage( "Page2", longBody( "Page2" ) ) );

        final PageManager pm = mock( PageManager.class );
        try {
            lenient().when( pm.getAllPages() ).thenReturn( allPages );
        } catch ( final Exception e ) {
            throw new AssertionError( "unreachable", e );
        }
        lenient().when( pm.getPureText( any( Page.class ) ) ).thenAnswer(
            (InvocationOnMock inv) -> inv.getArgument( 0, Page.class ).<String>getAttribute( "__body" ) );

        final SystemPageRegistry sp = mock( SystemPageRegistry.class );
        lenient().when( sp.isSystemPage( anyString() ) ).thenReturn( false );

        // Pre-populate the fake chunk repo so the STARTING wipe has something to do.
        final InMemoryChunkStore store = new InMemoryChunkStore();
        store.putPreloaded( "Page1", List.of(
            new ChunkDiff.Stored( UUID.randomUUID(), 0, "stale-hash-1" ) ) );
        store.putPreloaded( "Page2", List.of(
            new ChunkDiff.Stored( UUID.randomUUID(), 0, "stale-hash-2" ) ) );

        final ContentChunkRepository repo = fakeChunkRepoFromStore( store );
        final LuceneReindexQueue lucene = fakeLuceneQueue();

        final ContentChunker chunker =
            new ContentChunker( new ContentChunker.Config( 900, 8 ) );

        return new ContentIndexRebuildService(
            pm, sp, lucene, repo, chunker, () -> true, 10L );
    }

    /** Creates a {@link Page} fake that stores its body under the {@code __body} attribute. */
    private static Page fakePage( final String name, final String body ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        lenient().when( p.getVersion() ).thenReturn( -1 );
        // Stash body on the Page so the test PageManager mock can fetch it.
        lenient().when( p.<String>getAttribute( "__body" ) ).thenReturn( body );
        return p;
    }

    /**
     * Builds a body long enough to emit at least one chunk. The chunker's
     * merge-forward threshold is 8 estimated tokens (~32 chars); we inflate
     * well past the final-flush minimum so each page produces a chunk.
     */
    private static String longBody( final String name ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "# " ).append( name ).append( "\n\n" );
        sb.append( "Body of page " ).append( name )
          .append( ". This is a paragraph long enough to produce at least one chunk " )
          .append( "by exceeding the merge-forward threshold of the content chunker. " )
          .append( "Filler filler filler filler filler filler filler filler filler filler." );
        return sb.toString();
    }

    /** Build an in-memory-backed {@link ContentChunkRepository} that records deleteAll() calls. */
    private static ContentChunkRepository fakeChunkRepo() {
        return fakeChunkRepoFromStore( new InMemoryChunkStore() );
    }

    private static ContentChunkRepository fakeChunkRepoFromStore( final InMemoryChunkStore store ) {
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.stats() )
            .thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );
        lenient().when( repo.findByPage( anyString() ) ).thenAnswer(
            (InvocationOnMock inv) -> store.findByPage( inv.getArgument( 0, String.class ) ) );
        doAnswer( inv -> {
            CHUNKS_DELETE_ALL_CALLS.incrementAndGet();
            store.clear();
            return null;
        } ).when( repo ).deleteAll();
        doAnswer( inv -> {
            final String pageName = inv.getArgument( 0, String.class );
            final ChunkDiff.Diff diff = inv.getArgument( 1, ChunkDiff.Diff.class );
            store.apply( pageName, diff );
            return null;
        } ).when( repo ).apply( anyString(), any( ChunkDiff.Diff.class ) );
        return repo;
    }

    /** Build a Lucene reindex queue that records clearIndex() calls. */
    private static LuceneReindexQueue fakeLuceneQueue() {
        return new LuceneReindexQueue() {
            @Override public void reindexPage( final Page page ) {}
            @Override public int queueDepth() { return 0; }
            @Override public int documentCount() { return 0; }
            @Override public Instant lastUpdateInstant() { return null; }
            @Override public void clearIndex() { LUCENE_CLEAR_CALLS.incrementAndGet(); }
        };
    }

    /**
     * Tiny in-memory stand-in for the chunk table. Only exposes the subset
     * of the repository contract exercised by the run loop.
     */
    private static final class InMemoryChunkStore {
        private final Map< String, List< ChunkDiff.Stored > > byPage = new HashMap<>();

        synchronized void putPreloaded( final String page, final List< ChunkDiff.Stored > rows ) {
            byPage.put( page, new ArrayList<>( rows ) );
        }

        synchronized List< ChunkDiff.Stored > findByPage( final String page ) {
            return new ArrayList<>( byPage.getOrDefault( page, List.of() ) );
        }

        synchronized void apply( final String page, final ChunkDiff.Diff diff ) {
            final List< ChunkDiff.Stored > current = new ArrayList<>( byPage.getOrDefault( page, List.of() ) );
            // apply deletes
            current.removeIf( s -> diff.deletes().contains( s.id() ) );
            // apply updates — bump hash
            final Map< UUID, ChunkDiff.Update > updById = new HashMap<>();
            for ( final ChunkDiff.Update u : diff.updates() ) {
                updById.put( u.existingId(), u );
            }
            for ( int i = 0; i < current.size(); i++ ) {
                final ChunkDiff.Stored s = current.get( i );
                final ChunkDiff.Update u = updById.get( s.id() );
                if ( u != null ) {
                    current.set( i, new ChunkDiff.Stored(
                        s.id(), u.replacement().chunkIndex(), u.replacement().contentHash() ) );
                }
            }
            // apply inserts
            for ( final Chunk ins : diff.inserts() ) {
                current.add( new ChunkDiff.Stored( UUID.randomUUID(),
                    ins.chunkIndex(), ins.contentHash() ) );
            }
            byPage.put( page, current );
        }

        synchronized void clear() {
            byPage.clear();
        }
    }
}
