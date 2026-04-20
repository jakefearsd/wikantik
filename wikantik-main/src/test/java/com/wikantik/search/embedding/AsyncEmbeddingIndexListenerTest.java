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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@link AsyncEmbeddingIndexListener} using a
 * direct-dispatch executor so assertions can observe indexing calls
 * deterministically.
 */
class AsyncEmbeddingIndexListenerTest {

    private static final String MODEL = "qwen3-embedding-0.6b";

    @Test
    void emptyListIsNoop() {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        try( final AsyncEmbeddingIndexListener listener =
                 new AsyncEmbeddingIndexListener( indexer, MODEL, Executors.newSingleThreadExecutor() ) ) {
            listener.accept( List.of() );
            listener.accept( null );
            verify( indexer, never() ).indexChunks( any(), any() );
        }
    }

    @Test
    void nonEmptyListDelegatesToIndexer() throws InterruptedException {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        when( indexer.indexChunks( any(), eq( MODEL ) ) ).thenReturn( 2 );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try( final AsyncEmbeddingIndexListener listener =
                 new AsyncEmbeddingIndexListener( indexer, MODEL, ex ) ) {
            final List< UUID > ids = List.of( UUID.randomUUID(), UUID.randomUUID() );
            listener.accept( ids );
            // drain: listener does not own the executor so close() is a no-op;
            // shut down the executor ourselves to await completion.
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            verify( indexer, times( 1 ) ).indexChunks( ids, MODEL );
        }
    }

    @Test
    void indexerExceptionIsSwallowedByListener() throws InterruptedException {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        when( indexer.indexChunks( any(), any() ) ).thenThrow( new RuntimeException( "boom" ) );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try( final AsyncEmbeddingIndexListener listener =
                 new AsyncEmbeddingIndexListener( indexer, MODEL, ex ) ) {
            assertDoesNotThrow( () -> listener.accept( List.of( UUID.randomUUID() ) ) );
            ex.shutdown();
            ex.awaitTermination( 5, TimeUnit.SECONDS );
        }
    }

    @Test
    void constructorRejectsBlankModel() {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        assertThrows( IllegalArgumentException.class,
            () -> new AsyncEmbeddingIndexListener( indexer, "  " ) );
    }

    @Test
    void constructorRejectsNullIndexer() {
        assertThrows( IllegalArgumentException.class,
            () -> new AsyncEmbeddingIndexListener( null, MODEL ) );
    }

    @Test
    void postIndexCallbackRunsWithChunkIdsAfterSuccessfulIndex() throws InterruptedException {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        when( indexer.indexChunks( any(), eq( MODEL ) ) ).thenReturn( 1 );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final AtomicInteger cbCalls = new AtomicInteger();
        final AtomicReference< List< UUID > > observed = new AtomicReference<>();
        final UUID id = UUID.randomUUID();
        try( final AsyncEmbeddingIndexListener listener =
                 new AsyncEmbeddingIndexListener( indexer, MODEL, ex ) ) {
            listener.setPostIndexCallback( ids -> {
                observed.set( ids );
                cbCalls.incrementAndGet();
            } );
            listener.accept( List.of( id ) );
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
            assertEquals( 1, cbCalls.get() );
            assertEquals( List.of( id ), observed.get(),
                "callback must receive the same chunk ids that were indexed" );
        }
    }

    @Test
    void postIndexCallbackSkippedWhenIndexFails() throws InterruptedException {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        when( indexer.indexChunks( any(), any() ) ).thenThrow( new RuntimeException( "boom" ) );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final AtomicInteger cbCalls = new AtomicInteger();
        try( final AsyncEmbeddingIndexListener listener =
                 new AsyncEmbeddingIndexListener( indexer, MODEL, ex ) ) {
            listener.setPostIndexCallback( ids -> cbCalls.incrementAndGet() );
            listener.accept( List.of( UUID.randomUUID() ) );
            ex.shutdown();
            ex.awaitTermination( 5, TimeUnit.SECONDS );
            assertEquals( 0, cbCalls.get() );
        }
    }

    @Test
    void postIndexCallbackExceptionIsSwallowed() throws InterruptedException {
        final EmbeddingIndexService indexer = mock( EmbeddingIndexService.class );
        when( indexer.indexChunks( any(), eq( MODEL ) ) ).thenReturn( 1 );
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try( final AsyncEmbeddingIndexListener listener =
                 new AsyncEmbeddingIndexListener( indexer, MODEL, ex ) ) {
            listener.setPostIndexCallback( ids -> { throw new RuntimeException( "callback blew up" ); } );
            assertDoesNotThrow( () -> listener.accept( List.of( UUID.randomUUID() ) ) );
            ex.shutdown();
            assertEquals( true, ex.awaitTermination( 5, TimeUnit.SECONDS ) );
        }
    }
}
