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

import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.BundleSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-merge gate for the bundle-quality evaluation corpus.
 *
 * <p><b>Wiring tier (always runs, no embedder/Docker):</b> loads the frozen corpus
 * (`eval/bundle-corpus/queries.csv`) through the real {@link BundleCorpusLoader},
 * proving it parses end to end, and runs it through {@link BundleEvalRunner} with an
 * <em>oracle</em> retriever that returns each question's own gold sections — which must
 * score perfect context recall. This validates the corpus + harness plumbing
 * deterministically.</p>
 *
 * <p><b>Real-corpus tier (TODO, Phase-0 Task 8 Step 4):</b> a {@code @Nested}
 * {@code @Testcontainers(disabledWithoutDocker = true)} class that loads the checked-in
 * embedding snapshot (Task 7), builds the configured {@code ChunkVectorIndex}, runs the
 * corpus through {@link ContextServiceBundleRetriever}, and asserts the per-category
 * floors in {@code eval/bundle-corpus/thresholds.properties}. It is added once the
 * embedding fixture (Task 7) and frozen baseline (Task 9) exist; the floors are 0.0
 * (non-blocking) until then.</p>
 */
class BundleEvalGateTest {

    /** Repo-root-relative path; surefire runs with CWD = the wikantik-main module dir. */
    private static final Path CORPUS = Path.of( "..", "eval", "bundle-corpus", "queries.csv" );

    @Test
    void corpus_file_is_present() {
        assertTrue( Files.isRegularFile( CORPUS ),
            "evaluation corpus not found at " + CORPUS.toAbsolutePath()
            + " (test CWD must be the wikantik-main module dir)" );
    }

    @Test
    void gate_wiring_scores_corpus_with_an_oracle_retriever() {
        final List< BundleEvalQuestion > corpus = BundleCorpusLoader.load( CORPUS );
        assertFalse( corpus.isEmpty(), "corpus must load and be non-empty" );

        // Oracle retriever: returns each question's own gold sections verbatim → perfect recall.
        final BundleEvalRunner.BundleRetriever oracle = query -> corpus.stream()
            .filter( q -> q.query().equals( query ) )
            .findFirst()
            .map( q -> q.goldSections().stream()
                .map( g -> new BundleSection( g.canonicalId(), g.headingPath(), "x" ) )
                .toList() )
            .orElse( List.of() );

        final BundleEvalReport report = new BundleEvalRunner( oracle, 5 ).run( corpus );

        assertEquals( 1.0, report.overallRecall(), 1e-9,
            "an oracle returning each question's golds must achieve perfect context recall — "
            + "a miss means the corpus has duplicate query texts or a malformed gold row" );
        assertEquals( corpus.size(), report.questionsScored() );
    }
}
