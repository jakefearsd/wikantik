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
package com.wikantik.llm.activity;

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;

/**
 * {@link EntityExtractor} decorator that records each extraction call into an
 * {@link LlmActivityLog}. {@code extract} is contractually "must never throw", so
 * in practice every call records OK; the catch clause only fires on a
 * contract-violating {@link RuntimeException}, which is still rethrown.
 */
public final class RecordingEntityExtractor implements EntityExtractor {

    private final EntityExtractor delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    public RecordingEntityExtractor( final EntityExtractor delegate, final LlmActivityLog log,
                                     final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public String code() {
        return delegate.code();
    }

    @Override
    public ExtractionResult extract( final ExtractionChunk chunk, final ExtractionContext context ) {
        final String prompt = chunk == null ? "" : chunk.text();
        final LlmCall call = log.begin( Subsystem.ENTITY_EXTRACTION, backend, model, "chat", prompt );
        try {
            final ExtractionResult result = delegate.extract( chunk, context );
            log.succeed( call, summarise( result ) );
            return result;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    private static String summarise( final ExtractionResult r ) {
        if ( r == null ) {
            return "null result";
        }
        return "nodes=" + r.nodes().size()
             + " edges=" + r.edges().size()
             + " mentions=" + r.mentions().size();
    }
}
