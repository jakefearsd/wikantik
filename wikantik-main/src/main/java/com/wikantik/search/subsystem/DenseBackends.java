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
package com.wikantik.search.subsystem;

import java.util.List;

/**
 * The supported values of {@code wikantik.search.dense.backend}, and the rejection
 * thrown for anything else.
 *
 * <p>Two classes branch on this property: {@link SearchWiringHelper} on the real wiring
 * path (where it also derives the incremental-upsert callback and the index reload hook
 * from the concrete index type) and {@link SearchSubsystemFactory} on the degraded
 * fallback path (which only constructs). Those two switches do genuinely different work
 * and are deliberately NOT merged — but the set of accepted names and the message for a
 * rejected one are the same fact, and used to be a hand-copied string literal in both
 * files. Keeping them here means adding a backend cannot leave one copy stale.
 *
 * <p>The two call sites still default differently when the property is absent; that is
 * intentional and pinned by {@code DenseBackendResolutionTest}.
 */
final class DenseBackends {

    /** In-memory brute force. The only backend that needs no {@code DataSource}. */
    static final String INMEMORY = "inmemory";

    /** pgvector HNSW — the only backend that reads through to the database. */
    static final String PGVECTOR = "pgvector";

    /** Lucene HNSW — RAM-backed ANN with incremental upserts; the production default. */
    static final String LUCENE_HNSW = "lucene-hnsw";

    /** Every accepted value, in the order the rejection message lists them. */
    static final List< String > SUPPORTED = List.of( INMEMORY, PGVECTOR, LUCENE_HNSW );

    private DenseBackends() {
    }

    /**
     * Builds the exception for an unrecognised backend name.
     *
     * @param backend the offending configured value, quoted into the message.
     * @return the exception to throw; returned rather than thrown so the call site
     *         keeps its own control flow visible.
     */
    static IllegalArgumentException unsupported( final String backend ) {
        return new IllegalArgumentException(
            "wikantik.search.dense.backend must be one of "
          + String.join( ", ", SUPPORTED.stream().map( b -> "'" + b + "'" ).toList() )
          + ", got: '" + backend + "'" );
    }
}
