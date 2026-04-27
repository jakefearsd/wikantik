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
package com.wikantik.kgpolicy;

import java.time.Instant;

/**
 * Snapshot of a single cluster reconciliation run — progress counters plus
 * lifecycle state.  Immutable; replaced atomically in
 * {@link ReconciliationJobRunner}'s status map as each page is processed.
 *
 * <p>{@code startedAt} and {@code finishedAt} are {@code null} in the
 * {@code QUEUED} state; {@code finishedAt} is additionally {@code null} while
 * the run is {@code RUNNING}.</p>
 *
 * <p>{@code errorMessage} is non-null only in the {@code ERROR} state.</p>
 */
public record ReconciliationStatus(
        String cluster,
        State state,
        int totalPages,
        int processed,
        int errors,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {
    public enum State { QUEUED, RUNNING, DONE, ERROR }
}
