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

import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link TextEmbeddingClient} decorator that records each embedding call into an
 * {@link LlmActivityLog}. Both the synchronous {@code embed} path and the native
 * async {@code embedAsync} path are recorded; the async path finalizes its record
 * when the returned future completes.
 */
public final class RecordingEmbeddingClient implements TextEmbeddingClient {

    private final TextEmbeddingClient delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    public RecordingEmbeddingClient( final TextEmbeddingClient delegate, final LlmActivityLog log,
                                     final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public List< float[] > embed( final List< String > texts, final EmbeddingKind kind ) {
        final LlmCall call = log.begin( Subsystem.EMBEDDING, backend, model, "embed",
                                        size( texts ) + " texts" );
        try {
            final List< float[] > result = delegate.embed( texts, kind );
            log.succeed( call, size( result ) + " vectors" );
            return result;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    @Override
    public CompletableFuture< List< float[] > > embedAsync( final List< String > texts,
                                                            final EmbeddingKind kind ) {
        final LlmCall call = log.begin( Subsystem.EMBEDDING, backend, model, "embed",
                                        size( texts ) + " texts" );
        return delegate.embedAsync( texts, kind ).whenComplete( ( result, error ) -> {
            if ( error != null ) {
                log.fail( call, error );
            } else {
                log.succeed( call, size( result ) + " vectors" );
            }
        } );
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    private static int size( final List< ? > list ) {
        return list == null ? 0 : list.size();
    }
}
