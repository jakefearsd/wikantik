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

import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;

/**
 * Page-gated candidate source: hybrid (BM25+dense) retrieval picks the top pages,
 * then {@link SectionAssembler} takes the top-S sections per page. This is the
 * original bundle path; {@link DenseChunkSectionSource} is preferred when the dense
 * index is available (it does not gate sections behind the page pre-select).
 */
public final class RetrievalSectionSource implements SectionCandidateSource {

    private final ContextRetrievalService retrieval;
    private final SectionAssembler assembler;

    public RetrievalSectionSource( final ContextRetrievalService retrieval, final int sectionsPerPage ) {
        this.retrieval = retrieval;
        this.assembler = new SectionAssembler( sectionsPerPage );
    }

    @Override
    public SectionCandidates candidates( final String query ) {
        return SectionCandidates.of( assembler.assemble( retrieval.retrieve( new ContextQuery(
            query, ContextQuery.MAX_PAGES_CAP, ContextQuery.MAX_CHUNKS_PER_PAGE_CAP, null ) ) ), -1.0 );
    }
}
