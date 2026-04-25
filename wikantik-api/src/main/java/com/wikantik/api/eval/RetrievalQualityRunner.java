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

import java.util.List;

/**
 * Phase 5 of the Agent-Grade Content design — scheduled retrieval-quality
 * CI. Runs the curated query sets stored in {@code retrieval_query_sets}
 * against each {@link RetrievalMode}, persists per-run aggregates to
 * {@code retrieval_runs}, and publishes Prometheus metrics.
 *
 * <p>Implementations are thread-safe; obtain one from the engine's
 * manager registry. {@code /admin/retrieval-quality} is the operator
 * surface that consumes this interface.</p>
 */
public interface RetrievalQualityRunner {

    /**
     * Schedule the nightly run. Idempotent — repeat calls are no-ops once
     * the schedule is active. Implementations decide the wall-clock time
     * (default: 03:00 UTC, configurable).
     */
    void scheduleNightly();

    /**
     * Trigger a single run synchronously. Intended for the admin "run now"
     * button and for tests. Returns the resulting (already-persisted) row.
     *
     * @throws IllegalArgumentException when {@code querySetId} does not exist
     */
    RetrievalRunResult runNow( String querySetId, RetrievalMode mode );

    /**
     * Most recent runs in reverse-chronological order. {@code querySetId}
     * and {@code mode} are optional filters; pass {@code null} to skip
     * either. {@code limit} is clamped to {@code [1, 1000]}.
     */
    List< RetrievalRunResult > recentRuns( String querySetId, RetrievalMode mode, int limit );
}
