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
package com.wikantik.its.dense;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrity of the fixture corpus itself — pure string comparison, no server, no
 * database, no embedder.
 *
 * <p>Deliberately a separate class from {@link DenseBundleIT}. That one's {@code @BeforeAll}
 * probes the embedder and seeds five pages over HTTP, so every test it contains — including
 * this one, when it lived there — was gated on the whole stack being up. The zero-overlap
 * invariant is what makes {@code DenseBundleIT}'s central retrieval assertion mean anything,
 * and a broken invariant is most worth hearing about on a run where the environment is also
 * broken, not least.</p>
 */
class DenseProbeCorpusIT {

    /**
     * {@link DenseProbeCorpus#SEMANTIC_QUERY} must share no word with the page it is
     * expected to retrieve.
     *
     * <p>The BM25 chunk index holds the seeded pages (it refreshes on every save), so the
     * lexical ranker is a genuine competitor in {@link DenseBundleIT}. Zero overlap is the
     * only thing preventing it from satisfying that suite's rank-1 assertion without any
     * embedding taking place — which would leave the module green while dense retrieval
     * was entirely broken.</p>
     */
    @Test
    void querySharesNoWordWithTheTargetPage() {
        final Set< String > shared = new LinkedHashSet<>(
            DenseProbeCorpus.words( DenseProbeCorpus.SEMANTIC_QUERY ) );
        shared.retainAll( DenseProbeCorpus.words(
            DenseProbeCorpus.TARGET + "\n" + DenseProbeCorpus.FIXTURES.get( DenseProbeCorpus.TARGET ) ) );

        assertTrue( shared.isEmpty(),
            "This module requires zero lexical overlap between the query and '"
                + DenseProbeCorpus.TARGET + "'. The BM25 chunk index holds these pages, so "
                + "any shared word would let the lexical ranker satisfy DenseBundleIT's "
                + "retrieval assertion without a single embedding being involved. "
                + "Shared words: " + shared );
    }
}
