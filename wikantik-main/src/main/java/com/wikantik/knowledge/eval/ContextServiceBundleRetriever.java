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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Adapts the live {@link ContextRetrievalService} into a
 * {@link BundleEvalRunner.BundleRetriever}: runs retrieval and flattens each
 * page's contributing chunks into rank-ordered {@link BundleSection}s, resolving
 * the page slug to its canonical_id. Pages whose slug does not resolve are skipped
 * (they cannot match a gold canonical_id anyway).
 */
public final class ContextServiceBundleRetriever implements BundleEvalRunner.BundleRetriever {

    private final ContextRetrievalService service;
    private final Function< String, Optional< String > > slugToCanonicalId;

    public ContextServiceBundleRetriever( final ContextRetrievalService service,
                                          final Function< String, Optional< String > > slugToCanonicalId ) {
        this.service = service;
        this.slugToCanonicalId = slugToCanonicalId;
    }

    @Override
    public List< BundleSection > apply( final String query ) {
        final RetrievalResult result = service.retrieve(
            new ContextQuery( query, ContextQuery.MAX_PAGES_CAP, ContextQuery.MAX_CHUNKS_PER_PAGE_CAP, null ) );
        final List< BundleSection > sections = new ArrayList<>();
        for ( final RetrievedPage page : result.pages() ) {
            final Optional< String > id = slugToCanonicalId.apply( page.name() );
            if ( id.isEmpty() ) {
                continue;
            }
            for ( final RetrievedChunk chunk : page.contributingChunks() ) {
                sections.add( new BundleSection( id.get(), chunk.headingPath(), chunk.text() ) );
            }
        }
        return sections;
    }
}
