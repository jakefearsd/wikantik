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
package com.wikantik.search.embedding;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link BootstrapEmbeddingIndexer}. Uses a mocked
 * {@link EmbeddingIndexService} + mocked {@link DataSource} so we can assert
 * every state-machine transition without hitting a real database.
 */
class BootstrapEmbeddingIndexerTest {

    private static final String MODEL = "qwen3-embedding-0.6b";

    private static DataSource stubDataSourceReturningChunkCount( final long count ) throws Exception {
        final DataSource ds = mock( DataSource.class );
        final Connection c = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );
        when( ds.getConnection() ).thenReturn( c );
        when( c.prepareStatement( any( String.class ) ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        when( rs.next() ).thenReturn( true );
        when( rs.getLong( 1 ) ).thenReturn( count );
        return ds;
    }

    /**
     * The sibling embedding container is routinely not warm yet when the wiki finishes booting, so
     * the very first reconcile attempt races it and loses. The run is one-shot per process and
     * never re-arms, and the index-reload hook fires only on the success branch — so a single cold
     * start leaves stale rows unembedded until somebody restarts the wiki again.
     *
     * <p>Observed in production across five consecutive restarts spanning 19 days: every one ended
     * "stale-reconcile FAILED ... Embedding backend unavailable after 3 retries" with committed=0,
     * and "stale-reconcile COMPLETED" never appeared once. A backend that is merely slow to warm
     * must not be treated as a permanent failure.</p>
     */
    @Test
    void staleReconcileRetriesWhileTheEmbeddingBackendIsStillWarmingUp() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        // Cold for the first two attempts, warm on the third — a booting Ollama sidecar.
        when( index.indexStale( MODEL ) )
            .thenThrow( new IllegalStateException( "Embedding backend unavailable after 3 retries" ) )
            .thenThrow( new IllegalStateException( "Embedding backend unavailable after 3 retries" ) )
            .thenReturn( 7 );

        final DataSource ds = stubDataSourceReturningChunkCount( 22001L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final java.util.concurrent.atomic.AtomicInteger postRuns =
            new java.util.concurrent.atomic.AtomicInteger();
        try {
            final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer(
                ds, index, MODEL, postRuns::incrementAndGet, ex,
                /*staleAttempts*/ 3, java.time.Duration.ofMillis( 20 ) );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 10, TimeUnit.SECONDS ) );

            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state(),
                "a backend that is still warming must not end the run in a terminal FAILED state" );
            verify( index, times( 3 ) ).indexStale( MODEL );
            assertEquals( 1, postRuns.get(),
                "the dense index reload hook must fire once the reconcile finally succeeds" );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_runsStaleReconcileEvenWhenAlreadyPopulated() throws Exception {
        // Previously returned SKIPPED_ALREADY_POPULATED — now always reconciles stale rows.
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenReturn( 0 ); // 0 stale = no-op but still runs
        final DataSource ds = stubDataSourceReturningChunkCount( 100L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
            assertEquals( 100L, boot.progress().chunksTotal() );
            verify( index, times( 1 ) ).indexStale( MODEL );
            verify( index, never() ).indexAll( any() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_skipsWhenNoChunks() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            assertEquals( BootstrapEmbeddingIndexer.State.SKIPPED_NO_CHUNKS,
                boot.progress().state() );
            assertEquals( 0L, boot.progress().chunksTotal() );
            verify( index, never() ).indexStale( any() );
            verify( index, never() ).indexAll( any() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_runsIndexStaleAndTransitionsToCompleted() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenReturn( 7 );
        final DataSource ds = stubDataSourceReturningChunkCount( 7L );
        final AtomicInteger cbCalls = new AtomicInteger();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, cbCalls::incrementAndGet, ex );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
            assertEquals( 7L, boot.progress().chunksTotal() );
            assertNotNull( boot.progress().startedAt() );
            assertNotNull( boot.progress().completedAt() );
            assertNull( boot.progress().errorMessage() );
            assertEquals( 1, cbCalls.get() );
            verify( index, times( 1 ) ).indexStale( MODEL );
            verify( index, never() ).indexAll( any() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_indexStaleFailureLandsInFailedState() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenThrow( new RuntimeException( "backend down" ) );
        final DataSource ds = stubDataSourceReturningChunkCount( 3L );
        final AtomicInteger cbCalls = new AtomicInteger();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            // A persistently failing backend must still end in FAILED — the retry policy is
            // narrowed here only so the test does not sit through the production back-off.
            final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer(
                ds, index, MODEL, cbCalls::incrementAndGet, ex,
                /*staleAttempts*/ 2, java.time.Duration.ofMillis( 10 ) );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.FAILED, boot.progress().state() );
            assertEquals( "backend down", boot.progress().errorMessage() );
            // post-run callback must only run on success
            assertEquals( 0, cbCalls.get() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_isIdempotentAfterFirstDispatch() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenReturn( 0 );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            ex.shutdown();
            ex.awaitTermination( 5, TimeUnit.SECONDS );
            boot.startIfNeeded(); // already COMPLETED — must be a no-op
            boot.startIfNeeded();
            // indexStale dispatched exactly once; second and third calls short-circuit.
            verify( index, times( 1 ) ).indexStale( MODEL );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void forceStart_rejectsWhenAlreadyRunning() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        // Block the executor so RUNNING is observable before indexStale returns.
        when( index.indexStale( MODEL ) ).thenAnswer( inv -> {
            Thread.sleep( 200 );
            return 1;
        } );
        final DataSource ds = stubDataSourceReturningChunkCount( 1L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            assertEquals( BootstrapEmbeddingIndexer.State.RUNNING, boot.progress().state() );
            assertThrows( IllegalStateException.class, boot::forceStart );
            ex.shutdown();
            ex.awaitTermination( 5, TimeUnit.SECONDS );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void forceStart_reindexAfterReconcileCompleted() throws Exception {
        // startIfNeeded reconciles stale rows; forceStart then triggers a full reindex.
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenReturn( 0 );
        when( index.indexAll( MODEL ) ).thenReturn( 10 );
        final DataSource ds = stubDataSourceReturningChunkCount( 10L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
            // Force a full reindex on top of the completed reconcile.
            final ExecutorService ex2 = Executors.newSingleThreadExecutor();
            final BootstrapEmbeddingIndexer boot2 =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex2 );
            boot2.forceStart();
            ex2.shutdown();
            assertEquals( true, ex2.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot2.progress().state() );
            verify( index, times( 1 ) ).indexAll( MODEL );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void forceStart_deletesExistingRowsBeforeReindexSoProgressBarStartsFromZero() throws Exception {
        // Without delete-first, indexAll upserts in place: live rowCount stays
        // at the existing value the entire run, so the admin progress bar is
        // pinned at 100% and tells the operator nothing. Forcing a delete
        // first makes the live counter accurately track 0 → N progress.
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexAll( MODEL ) ).thenReturn( 50 );
        final DataSource ds = stubDataSourceReturningChunkCount( 50L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.forceStart();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
            final InOrder order = inOrder( index );
            order.verify( index ).deleteByModel( MODEL );
            order.verify( index ).indexAll( MODEL );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_doesNotDeleteWhenReconciling() throws Exception {
        // Reconcile path must never delete; deleteByModel is forceStart-only.
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenReturn( 5 );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
            verify( index, never() ).deleteByModel( any() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void forceStart_deleteFailureLandsInFailedStateAndSkipsIndexAll() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.deleteByModel( MODEL ) ).thenThrow( new RuntimeException( "delete bombed" ) );
        final DataSource ds = stubDataSourceReturningChunkCount( 50L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.forceStart();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.FAILED, boot.progress().state() );
            assertNotNull( boot.progress().errorMessage() );
            verify( index, never() ).indexAll( any() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void constructorRejectsBlankModel() throws Exception {
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        assertThrows( IllegalArgumentException.class,
            () -> new BootstrapEmbeddingIndexer( ds, index, " ", null,
                Executors.newSingleThreadExecutor() ) );
    }

    @Test
    void constructorRejectsNullIndexService() throws Exception {
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        assertThrows( IllegalArgumentException.class,
            () -> new BootstrapEmbeddingIndexer( ds, null, MODEL, null,
                Executors.newSingleThreadExecutor() ) );
    }

    @Test
    void modelCodeReturnsConfiguredValue() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            assertEquals( MODEL, boot.modelCode() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void forceStart_skipsWhenNoChunks() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.forceStart();
            assertEquals( BootstrapEmbeddingIndexer.State.SKIPPED_NO_CHUNKS, boot.progress().state() );
            assertEquals( 0L, boot.progress().chunksTotal() );
            verify( index, never() ).deleteByModel( any() );
            verify( index, never() ).indexAll( any() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void forceStart_indexAllFailureLandsInFailedState() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexAll( MODEL ) ).thenThrow( new RuntimeException( "boom" ) );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.forceStart();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.FAILED, boot.progress().state() );
            assertEquals( "boom", boot.progress().errorMessage() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_executorRejectionLandsInFailedState() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.shutdown(); // any submit() from here on throws RejectedExecutionException
        final BootstrapEmbeddingIndexer boot =
            new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
        boot.startIfNeeded();
        assertEquals( BootstrapEmbeddingIndexer.State.FAILED, boot.progress().state() );
        assertTrue( boot.progress().errorMessage().contains( "executor rejected task" ),
            boot.progress().errorMessage() );
        verify( index, never() ).indexStale( any() );
    }

    @Test
    void forceStart_executorRejectionLandsInFailedStateAfterSuccessfulDelete() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.deleteByModel( MODEL ) ).thenReturn( 3 );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.shutdown();
        final BootstrapEmbeddingIndexer boot =
            new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
        boot.forceStart();
        assertEquals( BootstrapEmbeddingIndexer.State.FAILED, boot.progress().state() );
        assertTrue( boot.progress().errorMessage().contains( "executor rejected task" ),
            boot.progress().errorMessage() );
        verify( index, times( 1 ) ).deleteByModel( MODEL );
        verify( index, never() ).indexAll( any() );
    }

    @Test
    void staleReconcile_interruptedDuringBackoffAbandonsRunWithFailedState() throws Exception {
        final AtomicReference< Thread > workerThread = new AtomicReference<>();
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenAnswer( inv -> {
            workerThread.set( Thread.currentThread() );
            throw new RuntimeException( "cold" );
        } );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer(
                ds, index, MODEL, null, ex, /*staleAttempts*/ 2, Duration.ofSeconds( 5 ) );
            boot.startIfNeeded();

            final long deadline = System.currentTimeMillis() + 5_000;
            while ( workerThread.get() == null && System.currentTimeMillis() < deadline ) {
                Thread.sleep( 10 );
            }
            assertNotNull( workerThread.get(), "the retry-backoff worker thread should have been captured" );
            Thread.sleep( 50 ); // give the worker a moment to actually enter Thread.sleep(staleRetryDelay)
            workerThread.get().interrupt();

            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.FAILED, boot.progress().state() );
            assertEquals( "cold", boot.progress().errorMessage() );
            // interrupted during the backoff sleep -> the loop breaks, no second attempt is made
            verify( index, times( 1 ) ).indexStale( MODEL );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void close_isNoOpWhenExecutorIsInjectedRatherThanOwned() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        final ExecutorService ex = mock( ExecutorService.class );
        final BootstrapEmbeddingIndexer boot =
            new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
        boot.close();
        verify( ex, never() ).shutdown();
        verify( ex, never() ).shutdownNow();
    }

    @Test
    void close_shutsDownOwnedExecutorCleanlyWhenIdle() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        // The 4-arg public constructor builds and owns its own executor.
        final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer( ds, index, MODEL, null );
        boot.close(); // nothing was ever submitted; must return promptly
    }

    @Test
    void close_forcesShutdownWhenTaskExceedsGracePeriod() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final CountDownLatch neverCounts = new CountDownLatch( 1 );
        when( index.indexAll( MODEL ) ).thenAnswer( inv -> {
            neverCounts.await();
            return 0;
        } );
        final DataSource ds = stubDataSourceReturningChunkCount( 1L );
        final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer( ds, index, MODEL, null );
        try {
            boot.forceStart();
            // give the executor a moment to actually start running indexAll on its worker thread
            Thread.sleep( 100 );
            final long t0 = System.nanoTime();
            boot.close(); // shutdown() + awaitTermination(5s) times out -> shutdownNow()
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 );
            assertTrue( elapsedMs >= 4_900,
                "close() must wait out the full 5s grace period before forcing shutdown: " + elapsedMs );
        } finally {
            neverCounts.countDown();
        }
    }

    @Test
    void close_interruptedWhileAwaitingTerminationForcesShutdownAndRestoresInterruptFlag() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        final CountDownLatch neverCounts = new CountDownLatch( 1 );
        when( index.indexAll( MODEL ) ).thenAnswer( inv -> {
            neverCounts.await();
            return 0;
        } );
        final DataSource ds = stubDataSourceReturningChunkCount( 1L );
        final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer( ds, index, MODEL, null );
        final AtomicBoolean closerInterruptedFlagAfterReturn = new AtomicBoolean();
        try {
            boot.forceStart();
            Thread.sleep( 100 );
            final Thread closer = new Thread( () -> {
                boot.close();
                closerInterruptedFlagAfterReturn.set( Thread.currentThread().isInterrupted() );
            } );
            closer.start();
            Thread.sleep( 150 ); // let it enter awaitTermination(5, SECONDS)
            closer.interrupt();
            closer.join( 2_000 );
            assertFalse( closer.isAlive(),
                "close() must return promptly once interrupted, not wait out the full 5s" );
            assertTrue( closerInterruptedFlagAfterReturn.get(),
                "the calling thread's interrupt flag must be restored" );
        } finally {
            neverCounts.countDown();
        }
    }

    @Test
    void postRunCallbackExceptionsAreSwallowed() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.indexStale( MODEL ) ).thenReturn( 1 );
        final DataSource ds = stubDataSourceReturningChunkCount( 1L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot = new BootstrapEmbeddingIndexer(
                ds, index, MODEL, () -> { throw new RuntimeException( "callback boom" ); }, ex );
            boot.startIfNeeded();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            // Run still completes — callback exception is caught and logged.
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }
}
