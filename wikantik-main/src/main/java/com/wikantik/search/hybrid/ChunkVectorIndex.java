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
package com.wikantik.search.hybrid;

import java.util.List;

/**
 * Placeholder interface pending the Phase 2 implementation. Defines the
 * minimal API the {@link DenseRetriever} needs from a chunk-level vector
 * index: cosine top-k over all indexed chunks, a readiness probe, and the
 * expected query-vector dimension. Phase 2's real implementation will adapt
 * to this contract so nothing here needs to change when it lands.
 */
public interface ChunkVectorIndex {

    /**
     * Return the top-{@code k} chunks by cosine similarity against the given
     * query vector, sorted descending by score.
     */
    List< ScoredChunk > topKChunks( float[] queryVec, int k );

    /** Whether the index is populated and available for querying. */
    boolean isReady();

    /** The expected dimension of query vectors passed to {@link #topKChunks}. */
    int dimension();
}
