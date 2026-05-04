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
package com.wikantik.knowledge.judge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists per-proposal judge-call timeouts so subsequent calls for the same
 * proposal can use a longer timeout, and so chronic-timeout proposals can be
 * surfaced in the admin UI for human review.
 *
 * <p>Rows are upserted on each timeout (counter incremented) and deleted when
 * the LLM HTTP call completes (regardless of verdict — the model responding
 * means the timeout root cause has resolved for this proposal).</p>
 */
public interface KgJudgeTimeoutRepository {

    /**
     * Snapshot of one tracked proposal's timeout history.
     *
     * @param proposalId          KG proposal UUID — same value seen in judge log lines
     * @param contentSha256       hex sha-256 of the user prompt sent to the LLM (diagnostic key
     *                            for grouping repeats of the same content under different proposal IDs)
     * @param sourcePage          page name the proposal was extracted from
     * @param proposalType        proposal type ("new-edge", etc.)
     * @param modelName           model used at the time of the most recent timeout
     * @param contentBytes        UTF-8 byte length of the user prompt at last timeout
     * @param timeoutCount        cumulative timeout count for this proposal
     * @param lastErrorExcerpt    short excerpt of the most recent timeout's error message
     * @param baseTimeoutSeconds  configured base timeout at last timeout
     * @param firstSeen           first time we saw a timeout for this proposal
     * @param lastSeen            most recent timeout
     */
    record TimeoutRow(
        UUID proposalId,
        String contentSha256,
        String sourcePage,
        String proposalType,
        String modelName,
        int contentBytes,
        int timeoutCount,
        String lastErrorExcerpt,
        int baseTimeoutSeconds,
        Instant firstSeen,
        Instant lastSeen
    ) {}

    /** Returns the existing row for a proposal, if any. */
    Optional< TimeoutRow > find( UUID proposalId );

    /**
     * Upserts a timeout: insert with count=1, or increment existing count by 1
     * and refresh metadata + {@code last_seen}. {@code first_seen} is preserved.
     */
    void recordTimeout(
        UUID proposalId,
        String contentSha256,
        String sourcePage,
        String proposalType,
        String modelName,
        int contentBytes,
        String errorExcerpt,
        int baseTimeoutSeconds );

    /** Deletes the row, if present. Called on any non-timeout HTTP completion. */
    void clear( UUID proposalId );

    /**
     * Lists rows ordered by {@code timeout_count DESC, last_seen DESC} —
     * the chronic-timeout list the admin UI surfaces for human review.
     */
    List< TimeoutRow > listTopChronic( int limit );
}
