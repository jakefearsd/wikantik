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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static EmbeddingIndexService.Status emptyStatus() {
        return new EmbeddingIndexService.Status( MODEL, 0, 0, null );
    }

    private static EmbeddingIndexService.Status populatedStatus( final int rows ) {
        return new EmbeddingIndexService.Status( MODEL, 1024, rows, null );
    }

    @Test
    void startIfNeeded_skipsWhenAlreadyPopulated() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( populatedStatus( 42 ) );
        final DataSource ds = stubDataSourceReturningChunkCount( 100L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            assertEquals( BootstrapEmbeddingIndexer.State.SKIPPED_ALREADY_POPULATED,
                boot.progress().state() );
            assertEquals( 42L, boot.progress().chunksTotal() );
            verify( index, never() ).indexAll( any() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_skipsWhenNoChunks() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( emptyStatus() );
        final DataSource ds = stubDataSourceReturningChunkCount( 0L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            assertEquals( BootstrapEmbeddingIndexer.State.SKIPPED_NO_CHUNKS,
                boot.progress().state() );
            assertEquals( 0L, boot.progress().chunksTotal() );
            verify( index, never() ).indexAll( any() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_runsIndexAllAndTransitionsToCompleted() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( emptyStatus() );
        when( index.indexAll( MODEL ) ).thenReturn( 7 );
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
            verify( index, times( 1 ) ).indexAll( MODEL );
        } finally {
            if ( !ex.isTerminated() ) ex.shutdownNow();
        }
    }

    @Test
    void startIfNeeded_indexAllFailureLandsInFailedState() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( emptyStatus() );
        when( index.indexAll( MODEL ) ).thenThrow( new RuntimeException( "backend down" ) );
        final DataSource ds = stubDataSourceReturningChunkCount( 3L );
        final AtomicInteger cbCalls = new AtomicInteger();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, cbCalls::incrementAndGet, ex );
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
        when( index.status( MODEL ) ).thenReturn( populatedStatus( 5 ) );
        final DataSource ds = stubDataSourceReturningChunkCount( 5L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            boot.startIfNeeded();
            boot.startIfNeeded();
            // status probed exactly once — second and third calls are no-ops.
            verify( index, times( 1 ) ).status( MODEL );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void forceStart_rejectsWhenAlreadyRunning() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( emptyStatus() );
        // Block the executor so RUNNING is observable before indexAll returns.
        when( index.indexAll( MODEL ) ).thenAnswer( inv -> {
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
    void forceStart_reindexEvenWhenAlreadyPopulated() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        // First call from startIfNeeded sees populated; forceStart bypasses the check.
        when( index.status( MODEL ) ).thenReturn( populatedStatus( 10 ) );
        when( index.indexAll( MODEL ) ).thenReturn( 10 );
        final DataSource ds = stubDataSourceReturningChunkCount( 10L );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final BootstrapEmbeddingIndexer boot =
                new BootstrapEmbeddingIndexer( ds, index, MODEL, null, ex );
            boot.startIfNeeded();
            assertEquals( BootstrapEmbeddingIndexer.State.SKIPPED_ALREADY_POPULATED,
                boot.progress().state() );
            boot.forceStart();
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( BootstrapEmbeddingIndexer.State.COMPLETED, boot.progress().state() );
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
        when( index.status( MODEL ) ).thenReturn( populatedStatus( 50 ) );
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
    void startIfNeeded_doesNotDeleteWhenTableAlreadyEmpty() throws Exception {
        // Cold-start path: nothing to delete, deleteByModel must not be called.
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( emptyStatus() );
        when( index.indexAll( MODEL ) ).thenReturn( 5 );
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
    void postRunCallbackExceptionsAreSwallowed() throws Exception {
        final EmbeddingIndexService index = mock( EmbeddingIndexService.class );
        when( index.status( MODEL ) ).thenReturn( emptyStatus() );
        when( index.indexAll( MODEL ) ).thenReturn( 1 );
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
