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
}
