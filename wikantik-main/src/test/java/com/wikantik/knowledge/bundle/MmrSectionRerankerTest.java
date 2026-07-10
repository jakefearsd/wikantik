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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmrSectionRerankerTest {

    private static CandidateSection sec( final String slug, final String head, final String text, final double score ) {
        return new CandidateSection( slug, List.of( head ), text, score );
    }

    @Test
    void singleOrEmpty_returnedUnchanged() {
        assertEquals( List.of(), new MmrSectionReranker( 0.7 ).rerank( "q", List.of() ) );
        final List< CandidateSection > one = List.of( sec( "p", "A", "alpha", 0.9 ) );
        assertSame( one, new MmrSectionReranker( 0.7 ).rerank( "q", one ) );
    }

    @Test
    void topRelevanceSectionStaysFirst() {
        // Highest denseScore must remain the first pick (MMR's first selection = argmax relevance).
        final var in = List.of(
            sec( "p1", "A", "alpha beta gamma", 0.95 ),
            sec( "p2", "B", "delta epsilon zeta", 0.40 ),
            sec( "p3", "C", "eta theta iota", 0.30 ) );
        final var out = new MmrSectionReranker( 0.7 ).rerank( "q", in );
        assertEquals( "p1", out.get( 0 ).slug() );
    }

    @Test
    void nearDuplicateIsDemotedBelowNovelLowerRankedSection() {
        // dense order: X (0.90), X' near-duplicate of X (0.85), Y novel (0.80).
        // Identity keeps X, X', Y. MMR must demote X' below Y because X' duplicates the already-picked X.
        final var in = List.of(
            sec( "px",  "X",  "cache eviction lru policy ttl", 0.90 ),
            sec( "px2", "X2", "cache eviction lru policy ttl entries", 0.85 ),  // near-duplicate of X
            sec( "py",  "Y",  "postgres vacuum autovacuum bloat", 0.80 ) );     // novel
        final var out = new MmrSectionReranker( 0.5 ).rerank( "q", in );
        assertEquals( "px", out.get( 0 ).slug(), "top relevance stays first" );
        assertEquals( "py", out.get( 1 ).slug(), "novel section promoted above the near-duplicate" );
        assertEquals( "px2", out.get( 2 ).slug(), "near-duplicate demoted last" );
    }

    @Test
    void neverDropsSections() {
        final var in = List.of(
            sec( "p1", "A", "alpha", 0.9 ), sec( "p2", "B", "alpha", 0.8 ), sec( "p3", "C", "beta", 0.7 ) );
        final var out = new MmrSectionReranker( 0.7 ).rerank( "q", in );
        assertEquals( in.size(), out.size(), "MMR reorders but never drops" );
        assertTrue( out.containsAll( in ) );
    }

    @Test
    void injectedSimilarityThrows_returnsInputOrder() {
        final var in = List.of( sec( "p1", "A", "alpha", 0.9 ), sec( "p2", "B", "beta", 0.8 ) );
        final MmrSectionReranker mmr = new MmrSectionReranker( 0.7,
            ( a, b ) -> { throw new IllegalStateException( "sim boom" ); } );
        assertSame( in, mmr.rerank( "q", in ), "any failure degrades to the input order" );
    }
}
