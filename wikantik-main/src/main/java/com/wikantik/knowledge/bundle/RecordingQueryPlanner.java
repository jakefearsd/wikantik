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
package com.wikantik.knowledge.bundle;

import com.wikantik.llm.activity.LlmActivityLog;
import com.wikantik.llm.activity.LlmCall;
import com.wikantik.llm.activity.Subsystem;
import java.util.List;

/**
 * {@link QueryPlanner} decorator that records each decomposition call into an
 * {@link LlmActivityLog}, giving the bundle's query-decomposition planner
 * ({@link LlmQueryPlanner}) the same {@code /admin/llm-activity} observability
 * parity as the embedding/extraction/judge subsystems — so an operator can use the
 * activity log to verify that {@code embeddings-only} mode issues zero chat calls.
 *
 * <p>{@code plan} is contractually fail-closed (never throws, never returns empty),
 * so in practice every call records OK; the catch clause only fires on a
 * contract-violating {@link RuntimeException}, which is still rethrown.</p>
 *
 * <p>Package-private: {@link QueryPlanner} itself is package-private, so this
 * decorator — like {@link LlmQueryPlanner} — can only live inside
 * {@code com.wikantik.knowledge.bundle}.</p>
 */
final class RecordingQueryPlanner implements QueryPlanner {

    private final QueryPlanner delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    RecordingQueryPlanner( final QueryPlanner delegate, final LlmActivityLog log,
                           final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public List< String > plan( final String query ) {
        final LlmCall call = log.begin( Subsystem.QUERY_DECOMPOSITION, backend, model, "chat", query );
        try {
            final List< String > result = delegate.plan( query );
            log.succeed( call, summarise( result ) );
            return result;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    private static String summarise( final List< String > r ) {
        return r == null ? "null result" : "subqueries=" + r.size();
    }
}
