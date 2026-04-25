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
package com.wikantik.api.eval;

import java.time.Instant;

/**
 * Aggregate result of one retrieval-quality run — a single row of
 * {@code retrieval_runs} surfaced as a value type. {@link #runId} is
 * {@code 0L} for synthetic results that have not (yet) been persisted.
 *
 * <p>Metrics are nullable {@link Double}s because a run with zero scoreable
 * queries (e.g. every expected_id was a deleted canonical_id) has no
 * meaningful nDCG / Recall / MRR — surfacing {@code null} is more honest
 * than emitting a synthetic zero.</p>
 *
 * @param runId            primary key from {@code retrieval_runs}, or {@code 0} when transient
 * @param querySetId       e.g. {@code core-agent-queries}
 * @param mode             {@link RetrievalMode}
 * @param ndcgAt5          aggregated nDCG at cutoff 5, or {@code null} when no queries scored
 * @param ndcgAt10         aggregated nDCG at cutoff 10
 * @param recallAt20       aggregated recall at cutoff 20
 * @param mrr              aggregated mean reciprocal rank
 * @param startedAt        wall-clock start
 * @param finishedAt       wall-clock end ({@code null} during an in-flight run)
 * @param queriesEvaluated number of queries with at least one expected id that contributed to aggregation
 * @param queriesSkipped   number of queries skipped (empty {@code expected_ids} or all expected ids unresolved)
 * @param degraded         {@code true} if the run could not exercise the requested mode end-to-end (e.g. embedder down for HYBRID)
 */
public record RetrievalRunResult(
    long runId,
    String querySetId,
    RetrievalMode mode,
    Double ndcgAt5,
    Double ndcgAt10,
    Double recallAt20,
    Double mrr,
    Instant startedAt,
    Instant finishedAt,
    int queriesEvaluated,
    int queriesSkipped,
    boolean degraded
) {
    public RetrievalRunResult {
        if ( querySetId == null || querySetId.isBlank() ) {
            throw new IllegalArgumentException( "querySetId must not be blank" );
        }
        if ( mode == null ) {
            throw new IllegalArgumentException( "mode must not be null" );
        }
        if ( startedAt == null ) {
            throw new IllegalArgumentException( "startedAt must not be null" );
        }
        if ( queriesEvaluated < 0 || queriesSkipped < 0 ) {
            throw new IllegalArgumentException( "queriesEvaluated/Skipped must be non-negative" );
        }
    }
}
