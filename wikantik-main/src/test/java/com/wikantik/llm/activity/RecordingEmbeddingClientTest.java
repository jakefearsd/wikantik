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
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class RecordingEmbeddingClientTest {

    private static TextEmbeddingClient delegate( final boolean fail ) {
        return new TextEmbeddingClient() {
            public List< float[] > embed( final List< String > texts, final EmbeddingKind kind ) {
                if ( fail ) {
                    throw new RuntimeException( "embed backend down" );
                }
                return List.of( new float[] { 1f }, new float[] { 2f } );
            }
            public int dimension() { return 1; }
            public String modelName() { return "nomic-embed"; }
        };
    }

    @Test
    void recordsOkOnSyncEmbed() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( false ), log, "ollama", "nomic-embed" );

        assertEquals( 2, rec.embed( List.of( "a", "b" ), EmbeddingKind.QUERY ).size() );
        final LlmActivityLog.Snapshot snap = log.snapshot( 10, null, null );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( "EMBEDDING", snap.calls().get( 0 ).subsystem() );
    }

    @Test
    void recordsErrorAndRethrowsOnSyncEmbedFailure() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( true ), log, "ollama", "nomic-embed" );

        assertThrows( RuntimeException.class,
                      () -> rec.embed( List.of( "a" ), EmbeddingKind.QUERY ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void recordsOkOnAsyncEmbed() throws Exception {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( false ), log, "ollama", "nomic-embed" );

        rec.embedAsync( List.of( "a", "b" ), EmbeddingKind.QUERY ).get();
        assertEquals( "OK", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void recordsErrorOnAsyncEmbedFailure() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( true ), log, "ollama", "nomic-embed" );

        final CompletableFuture< List< float[] > > f =
            rec.embedAsync( List.of( "a" ), EmbeddingKind.QUERY );
        assertThrows( ExecutionException.class, f::get );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void delegatesDimensionAndModelName() {
        final RecordingEmbeddingClient rec = new RecordingEmbeddingClient(
            delegate( false ), new LlmActivityLog( true, 60, 100, 500 ), "ollama", "nomic-embed" );
        assertEquals( 1, rec.dimension() );
        assertTrue( rec.modelName().contains( "nomic" ) );
    }
}
