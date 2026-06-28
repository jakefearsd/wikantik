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

/**
 * Raised when an embedding backend call fails or returns an unexpected shape.
 *
 * <p>The {@link #isTransient()} flag distinguishes two failure modes:</p>
 * <ul>
 *   <li><b>Transient</b> ({@code isTransient() == true}): a backend transport
 *       problem — HTTP 503/429/5xx, connection refused, or interrupted I/O. The
 *       <em>chunk content</em> is not the cause; the indexer should retry with
 *       backoff, and abort (leaving chunks un-embedded for the next reconcile)
 *       if the backend stays down. Chunks must NOT be marked poisoned.</li>
 *   <li><b>Permanent / poison-pill</b> ({@code isTransient() == false}, the
 *       default): the chunk content or response shape is the problem — a 4xx
 *       response, non-JSON body, wrong vector dimension, etc. The indexer should
 *       skip the chunk and continue.</li>
 * </ul>
 */
public class EmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 2L;

    /**
     * {@code true} when the failure is a backend transport issue; the chunk
     * content is not at fault. {@code false} (default) for poison-pill inputs
     * or malformed responses where the chunk itself is the problem.
     */
    private final boolean transientFailure;

    /** Permanent failure (poison-pill). Callers that don't set the flag get the safe default. */
    public EmbeddingException( final String message ) {
        this( message, false );
    }

    /** Permanent failure (poison-pill) with a cause. */
    public EmbeddingException( final String message, final Throwable cause ) {
        this( message, cause, false );
    }

    /**
     * @param transientFailure {@code true} for backend transport failures that
     *                         should be retried; {@code false} for poison-pill inputs
     */
    public EmbeddingException( final String message, final boolean transientFailure ) {
        super( message );
        this.transientFailure = transientFailure;
    }

    /**
     * @param transientFailure {@code true} for backend transport failures that
     *                         should be retried; {@code false} for poison-pill inputs
     */
    public EmbeddingException( final String message, final Throwable cause,
                               final boolean transientFailure ) {
        super( message, cause );
        this.transientFailure = transientFailure;
    }

    /**
     * Returns {@code true} when the failure is a transient backend issue (HTTP
     * 503/429/5xx, connection error). The indexer should retry the batch with
     * backoff and, if the backend stays unavailable, abort the reconcile without
     * marking chunks as poisoned.
     *
     * <p>Returns {@code false} for permanent failures (4xx, malformed response,
     * bad input): the chunk itself is the problem and should be skipped.</p>
     */
    public boolean isTransient() {
        return transientFailure;
    }
}
