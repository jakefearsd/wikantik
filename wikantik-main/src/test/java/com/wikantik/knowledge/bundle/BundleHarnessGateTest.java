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

import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.GoldSection;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.knowledge.eval.BundleCorpusLoader;
import com.wikantik.knowledge.eval.BundleEvalReport;
import com.wikantik.knowledge.eval.BundleEvalRunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Always-on gate: proves the {@link BundleHarnessAdapter} + scoring path loses
 * zero gold sections when retrieval perfectly surfaces all golds.
 *
 * <p>When the fake {@link ContextRetrievalService} returns each question's gold
 * sections verbatim as retrievable chunks, the assembled bundle must cover every
 * gold section, yielding {@code overallRecall == 1.0}. This validates the full
 * adapter/scoring-path pipeline end-to-end with no I/O, no network, and no
 * container dependency.</p>
 *
 * <p><strong>Real-corpus recall tier omitted by design.</strong> The real-corpus
 * recall gate (bundle ≥ dense baseline ~0.41@5 from
 * {@code eval/bundle-corpus/baseline-notes.md}) is validated LIVE via the
 * embedder/reranker spike, NOT as a {@code @Testcontainers} JUnit tier. The
 * reason: the Phase-0 embedding snapshot was never materialized, so there is no
 * deterministic dense-retrieval fixture to run against in CI. The live spike
 * exercises the real pipeline; this test guards the adapter and metrics wiring.</p>
 */
class BundleHarnessGateTest {

    @Test
    void perfectRetrievalYieldsFullRecall() {
        final List< BundleEvalQuestion > corpus =
            BundleCorpusLoader.load( Path.of( "..", "eval", "bundle-corpus", "queries.csv" ) );

        // Index corpus by query text for the fake retriever.
        final Map< String, BundleEvalQuestion > byQuery = new LinkedHashMap<>();
        for ( final BundleEvalQuestion q : corpus ) {
            byQuery.put( q.query(), q );
        }

        final ContextRetrievalService fakeRetrieval = new ContextRetrievalService() {
            @Override
            public RetrievalResult retrieve( final ContextQuery q ) {
                final BundleEvalQuestion question = byQuery.get( q.query() );
                if ( question == null ) {
                    return new RetrievalResult( q.query(), List.of(), 0 );
                }
                // Group gold sections by canonicalId → one RetrievedPage per id.
                final Map< String, List< GoldSection > > byCanonical = new LinkedHashMap<>();
                for ( final GoldSection g : question.goldSections() ) {
                    byCanonical.computeIfAbsent( g.canonicalId(), k -> new ArrayList<>() ).add( g );
                }
                final List< RetrievedPage > pages = new ArrayList<>();
                for ( final Map.Entry< String, List< GoldSection > > entry : byCanonical.entrySet() ) {
                    final String canonicalId = entry.getKey();
                    final List< RetrievedChunk > chunks = new ArrayList<>();
                    for ( final GoldSection g : entry.getValue() ) {
                        chunks.add( new RetrievedChunk( g.headingPath(), "gold", 1.0, List.of() ) );
                    }
                    pages.add( new RetrievedPage(
                        canonicalId,   // name == canonicalId (identity slug)
                        "",            // url
                        1.0,           // score
                        "",            // summary
                        "",            // cluster
                        List.of(),     // tags
                        chunks,        // contributingChunks
                        List.of(),     // relatedPages
                        "",            // author
                        null           // lastModified
                    ) );
                }
                return new RetrievalResult( q.query(), pages, pages.size() );
            }

            @Override
            public RetrievedPage getPage( final String pageName ) {
                return null;
            }

            @Override
            public PageList listPages( final PageListFilter filter ) {
                return null;
            }

            @Override
            public List< MetadataValue > listMetadataValues( final String field ) {
                return List.of();
            }
        };

        final DefaultBundleAssemblyService service = new DefaultBundleAssemblyService(
            fakeRetrieval,
            ( query, sections ) -> sections,  // identity reranker
            slug -> Optional.of( slug ),       // canonicalIdOf: slug IS the canonicalId
            slug -> 1,                         // versionOf
            50,                                // maxSections: large enough to surface all golds
            10                                 // sectionsPerPage: large enough
        );

        final BundleHarnessAdapter adapter = new BundleHarnessAdapter( service );
        final BundleEvalReport report = new BundleEvalRunner( adapter, 5 ).run( corpus );

        assertEquals( 1.0, report.overallRecall(), 1e-9,
            "Perfect retrieval must yield recall == 1.0; the adapter or scoring path is dropping sections" );
        assertEquals( corpus.size(), report.questionsScored(),
            "questionsScored must equal corpus size" );
    }
}
