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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/** Applies an ordered list of {@link SectionReranker}s, each fed the previous stage's output.
 *  Each stage already degrades to its input on failure; the chain additionally catches any stage
 *  that throws and keeps the prior order, so the chain is fail-closed by composition. An empty
 *  chain returns the input unchanged (identity). Reorder-only: no stage drops sections. */
final class SectionRerankChain implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( SectionRerankChain.class );

    private final List< SectionReranker > stages;

    SectionRerankChain( final List< SectionReranker > stages ) {
        this.stages = List.copyOf( stages );
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        List< CandidateSection > cur = sections;
        for ( final SectionReranker stage : stages ) {
            try {
                cur = stage.rerank( query, cur );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Rerank stage {} failed ({}); keeping prior order",
                    stage.getClass().getSimpleName(), e.getMessage() );
            }
        }
        return cur;
    }

    List< SectionReranker > stages() {
        return stages;
    }
}
