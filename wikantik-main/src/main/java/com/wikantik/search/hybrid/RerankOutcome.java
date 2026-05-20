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
import java.util.Optional;

/**
 * Carries the output of {@link HybridSearchService#rerankWithChunks}: the
 * fused page-name ordering (always present) plus optional dense-retrieval
 * chunks that downstream callers can reuse to avoid a second full-corpus scan.
 *
 * <p>{@link #denseChunks()} is empty when hybrid retrieval was disabled,
 * the query embedding was unavailable, or the dense retriever returned no
 * chunks. Consumers must treat it as advisory: a present list means
 * "you can short-circuit a downstream scan"; an empty list means
 * "fall through to the normal path".</p>
 */
public record RerankOutcome(
    List< String > fusedPageNames,
    Optional< List< ScoredChunk > > denseChunks
) {
    /** Convenience: BM25-only outcome (no dense path ran). */
    public static RerankOutcome bm25Only( final List< String > names ) {
        return new RerankOutcome(
            names == null ? List.of() : names,
            Optional.empty() );
    }
}
