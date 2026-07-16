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
 * {@link SectionReranker} decorator that records each rerank call into an
 * {@link LlmActivityLog}, giving the bundle's listwise LLM reranker
 * ({@link LlmSectionReranker}) the same {@code /admin/llm-activity} observability
 * parity as the embedding/extraction/judge subsystems — so an operator can use the
 * activity log to verify that {@code embeddings-only} mode issues zero chat calls.
 *
 * <p>{@link SectionReranker} implementations are contractually required to degrade
 * to the input order on failure rather than throw, so in practice every call records
 * OK; the catch clause only fires on a contract-violating {@link RuntimeException},
 * which is still rethrown (never swallowed).</p>
 *
 * <p>Package-private: {@link SectionReranker#rerank} is typed over the package-private
 * {@link CandidateSection}, so this decorator — like {@link LlmSectionReranker} itself —
 * can only live inside {@code com.wikantik.knowledge.bundle}.</p>
 */
final class RecordingSectionReranker implements SectionReranker {

    private final SectionReranker delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    RecordingSectionReranker( final SectionReranker delegate, final LlmActivityLog log,
                              final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        final LlmCall call = log.begin( Subsystem.SECTION_RERANK, backend, model, "chat",
                                        describe( query, sections ) );
        try {
            final List< CandidateSection > result = delegate.rerank( query, sections );
            log.succeed( call, size( result ) + " sections" );
            return result;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    private static String describe( final String query, final List< CandidateSection > sections ) {
        return "query=" + query + " sections=" + size( sections );
    }

    private static int size( final List< ? > list ) {
        return list == null ? 0 : list.size();
    }
}
