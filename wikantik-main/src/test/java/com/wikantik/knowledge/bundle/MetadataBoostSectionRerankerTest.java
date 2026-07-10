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

import com.wikantik.api.pagegraph.Confidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataBoostSectionRerankerTest {

    private static CandidateSection sec( final String slug, final double score ) {
        return new CandidateSection( slug, List.of( "H" ), slug + " text", score );
    }

    private static Function< String, Confidence > confidences( final Map< String, Confidence > m ) {
        return slug -> m.getOrDefault( slug, Confidence.PROVISIONAL );
    }

    @Test
    void verifiedRanksAboveStale_whenDenseScoresEqual() {
        // Equal denseScore: the AUTHORITATIVE section must be promoted above the STALE one.
        final var in = List.of( sec( "stalePage", 0.80 ), sec( "authPage", 0.80 ) );
        final var conf = confidences( Map.of( "stalePage", Confidence.STALE, "authPage", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 24 ).rerank( "q", in );
        assertEquals( "authPage", out.get( 0 ).slug() );
        assertEquals( "stalePage", out.get( 1 ).slug() );
    }

    @Test
    void boostDoesNotOverrideARealRelevanceGap() {
        // A much higher denseScore stale section still beats a barely-lower authoritative one:
        // 0.90·(1−0.05)=0.855 > 0.83·(1+0.05)=0.8715? No — pick a gap the small factor cannot cross.
        // stale 0.95·0.95 = 0.9025 vs auth 0.80·1.05 = 0.84 -> stale stays first.
        final var in = List.of( sec( "staleStrong", 0.95 ), sec( "authWeak", 0.80 ) );
        final var conf = confidences( Map.of( "staleStrong", Confidence.STALE, "authWeak", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 24 ).rerank( "q", in );
        assertEquals( "staleStrong", out.get( 0 ).slug(), "a small boost must not cross a real relevance gap" );
    }

    @Test
    void factorZero_isIdentity() {
        final var in = List.of( sec( "a", 0.9 ), sec( "b", 0.8 ) );
        assertSame( in, new MetadataBoostSectionReranker( confidences( Map.of() ), 0.0, 24 ).rerank( "q", in ) );
    }

    @Test
    void nullConfidenceLookup_isIdentity() {
        final var in = List.of( sec( "a", 0.9 ), sec( "b", 0.8 ) );
        assertSame( in, new MetadataBoostSectionReranker( null, 0.05, 24 ).rerank( "q", in ) );
    }

    @Test
    void neverDropsSections_andBeyondWindowUntouched() {
        // window=1: only the first candidate is in the boost window; the rest keep their order and all survive.
        final var in = List.of( sec( "a", 0.9 ), sec( "stale", 0.85 ), sec( "auth", 0.80 ) );
        final var conf = confidences( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 1 ).rerank( "q", in );
        assertEquals( in.size(), out.size() );
        assertTrue( out.containsAll( in ) );
        // beyond the window (index >= 1) order is preserved: stale before auth
        assertEquals( List.of( "a", "stale", "auth" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void window2_reordersWithinWindow_andLeavesRemainderInPlace() {
        // window=2 with 4 candidates: the boost reorders WITHIN the top-2 window (stale/auth swap),
        // but indices 2-3 must stay exactly where they started — proving the window boundary by position.
        final var in = List.of( sec( "stale", 0.80 ), sec( "auth", 0.80 ), sec( "c", 0.70 ), sec( "d", 0.60 ) );
        final var conf = confidences( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 2 ).rerank( "q", in );
        assertEquals( in.size(), out.size() );
        assertTrue( out.containsAll( in ) );
        assertEquals( List.of( "auth", "stale", "c", "d" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void lookupThrows_returnsInputOrder() {
        final var in = List.of( sec( "a", 0.9 ), sec( "b", 0.8 ) );
        final Function< String, Confidence > boom = slug -> { throw new IllegalStateException( "boom" ); };
        assertSame( in, new MetadataBoostSectionReranker( boom, 0.05, 24 ).rerank( "q", in ) );
    }
}
