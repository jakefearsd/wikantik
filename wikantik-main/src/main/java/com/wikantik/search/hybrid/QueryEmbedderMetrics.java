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

/**
 * Immutable snapshot of {@link QueryEmbedder} counters. Exposed so the admin
 * panel (Phase 7) can surface them without pulling in Micrometer; a separate
 * bridge can translate the snapshot to registry meters when needed.
 *
 * <p>All counters are monotonic increments over the embedder's lifetime.</p>
 *
 * @param callSuccess         successful embed calls (includes probes)
 * @param callFailure         total embed failures: exceptions + timeouts + empty results + breaker rejections
 * @param callTimeout         subset of {@code callFailure} caused by the timeout budget
 * @param cacheHit            embed calls served entirely from cache
 * @param cacheMiss           embed calls that required (or attempted) a backend trip
 * @param breakerOpen         transitions into {@link CircuitState#OPEN}
 * @param breakerClose        transitions into {@link CircuitState#CLOSED} after being non-closed
 * @param breakerHalfOpenProbe  transitions into {@link CircuitState#HALF_OPEN}
 * @param breakerCallRejected calls short-circuited by OPEN state (never reached the client)
 */
public record QueryEmbedderMetrics(
        long callSuccess,
        long callFailure,
        long callTimeout,
        long cacheHit,
        long cacheMiss,
        long breakerOpen,
        long breakerClose,
        long breakerHalfOpenProbe,
        long breakerCallRejected ) {
}
