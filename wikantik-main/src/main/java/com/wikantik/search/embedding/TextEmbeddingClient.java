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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Turns text into dense vectors via a remote embedding service. Implementations
 * wrap a specific backend (Ollama, TEI, OpenAI-compatible, etc.) but share a
 * backend-agnostic contract so the indexer and query path do not couple to any
 * particular server.
 *
 * <p>Implementations are expected to apply the per-model prefix for the given
 * {@link EmbeddingKind} before sending the text to the backend.</p>
 */
public interface TextEmbeddingClient {

    /**
     * Embeds the given texts in order. The returned list has one vector per input.
     * Each vector has exactly {@link #dimension()} elements.
     *
     * @throws EmbeddingException if the backend call fails or returns an unexpected shape
     */
    List< float[] > embed( List< String > texts, EmbeddingKind kind );

    /**
     * Asynchronous variant of {@link #embed}. Default implementation wraps the
     * synchronous call on the common ForkJoinPool; backends with native non-blocking
     * I/O (e.g. {@link java.net.http.HttpClient#sendAsync}) should override to avoid
     * pinning a worker thread per in-flight request.
     *
     * <p>Failures surface via the returned future's exceptional completion. The
     * cause matches what {@link #embed} would have thrown synchronously.</p>
     */
    default CompletableFuture< List< float[] > > embedAsync( final List< String > texts,
                                                              final EmbeddingKind kind ) {
        return CompletableFuture.supplyAsync( () -> embed( texts, kind ), ForkJoinPool.commonPool() );
    }

    /** Output dimension of every vector produced by this client. */
    int dimension();

    /**
     * Human-readable name of the model being served (e.g. {@code "nomic-embed-v1.5"}).
     * Stored in the embedding table so mixed-model rows can be detected after a
     * model swap.
     */
    String modelName();
}
