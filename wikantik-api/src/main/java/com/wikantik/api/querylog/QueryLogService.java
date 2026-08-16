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
package com.wikantik.api.querylog;

/**
 * Records real retrieval queries (text + who asked + which surface + how many results) so the
 * traffic can later ground the eval corpus. Implementations MUST be fail-open and non-blocking:
 * {@link #log} never throws and never delays the retrieval path — a logging failure drops the
 * record, it never degrades search.
 */
public interface QueryLogService {

    /**
     * Record one query. No-op when logging is disabled, the query is blank, or the write fails.
     *
     * @param query       the raw query text (never persisted as restricted content — text only)
     * @param actor        who issued it (see {@link ActorType})
     * @param surface      the entry point (see {@link SourceSurface})
     * @param resultCount  number of sections/pages returned, or {@code null} if unknown
     */
    void log( String query, ActorType actor, SourceSurface surface, Integer resultCount );

    /**
     * Full form used by the bundle/briefing surfaces: also records the bundle coverage confidence
     * and the caller's session hash (design §6.2/D8). Default delegates to the 4-arg {@link
     * #log(String, ActorType, SourceSurface, Integer)}, dropping the two new fields — sufficient
     * for any implementation written before this capability existed, so it keeps compiling and
     * behaving identically without an override. {@code JdbcQueryLogService} overrides this to
     * persist all six columns in one write.
     *
     * @param query       the raw query text
     * @param actor        who issued it
     * @param surface      the entry point
     * @param resultCount  number of sections/pages returned, or {@code null} if unknown
     * @param coverage     bundle coverage confidence ({@code strong|partial|weak|unknown}, see
     *                     {@code BundleCoverage}), or {@code null} for surfaces with no bundle
     * @param sessionHash  the D8 session hash, or {@code null} for surfaces with no browser session
     *                     (e.g. MCP/agent callers)
     */
    default void log( String query, ActorType actor, SourceSurface surface, Integer resultCount,
                       String coverage, String sessionHash ) {
        log( query, actor, surface, resultCount );
    }

    /**
     * Attaches a result click to the most recent {@code retrieval_query_log} row that matches
     * {@code sessionHash} and {@code queryText}, recording the 1-based rank of the clicked result.
     * No-op default (fail-open) for implementations that don't track clicks; no-op — not an
     * exception — when no matching row is found. Never throws.
     *
     * @param sessionHash  the D8 session hash the original query was logged under
     * @param queryText    the exact query text the original row was logged with
     * @param rank         1-based rank of the clicked result
     */
    default void recordClick( String sessionHash, String queryText, int rank ) {
    }
}
