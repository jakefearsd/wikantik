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
package com.wikantik.api.connectors;

/** Pull-based external source. {@link #poll} returns changed items since the cursor. */
public interface SourceConnector {
    /** Stable id; namespaces item URIs and sync-state rows. */
    String connectorId();
    /**
     * Poll the source. MUST never throw — the orchestrator calls this without a try/catch; degrade
     * every internal failure to a batch instead.
     *
     * <p><b>Untrusted-enumeration contract (full-corpus connectors):</b> when the poll cannot be
     * trusted as a full snapshot — a fetch/API failure, a missing credential, a scan error — return
     * {@code complete=false} with the <em>input</em> cursor (partially fetched items may still be
     * included; they are ingested). Absence-means-deleted only holds for a trustworthy snapshot: an
     * empty or partial batch returned as {@code complete=true} would make the orchestrator tombstone
     * (hard-delete) every derived page the poll happened to miss. An authoritative per-item "gone"
     * (HTTP 404/410) does NOT taint the batch — that absence is real.
     *
     * @param cursor last persisted checkpoint, or {@code null} for a full initial sync.
     */
    SyncBatch poll( SyncCursor cursor );
    /**
     * Whether this connector's {@link #poll} reflects the <em>full current source set</em>, so a
     * previously-synced URI that is absent from a poll means "deleted at source" (the orchestrator
     * tombstones it). Full-corpus connectors (filesystem, crawler, sitemap, drive) return {@code true}.
     * Windowed sources (e.g. an RSS/Atom feed showing only the latest N entries) return {@code false}
     * so aged-out items are <em>retained</em> (their derived pages and sync state are simply left
     * untouched — there is no separate archive state).
     */
    default boolean reflectsFullCorpus() { return true; }
}
