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

    // denseScore is deliberately uniform/irrelevant: the boost works on INCOMING ORDER, not denseScore.
    private static CandidateSection sec( final String slug ) {
        return new CandidateSection( slug, List.of( "H" ), slug + " text", 0.5 );
    }

    private static Function< String, Confidence > conf( final Map< String, Confidence > m ) {
        return slug -> m.getOrDefault( slug, Confidence.PROVISIONAL );
    }

    @Test
    void authoritativeAdjacentToStale_isPromoted() {
        // incoming order [stale, auth]; positions 1.5 -> auth (i=1) key -0.5 < stale (i=0) key 1.5 -> [auth, stale]
        final var in = List.of( sec( "stale" ), sec( "auth" ) );
        final var c = conf( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "auth", "stale" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void ignoresDenseScore_usesIncomingOrder() {
        // Same incoming order as above but give 'stale' the HIGHER denseScore — must not change the result,
        // proving the boost is rank-based (composable), not denseScore-based.
        final var in = List.of(
            new CandidateSection( "stale", List.of( "H" ), "t", 0.99 ),
            new CandidateSection( "auth", List.of( "H" ), "t", 0.01 ) );
        final var c = conf( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "auth", "stale" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void equalConfidence_preservesIncomingOrder_exactly() {
        // all PROVISIONAL -> keys = i -> order unchanged. This is the composability guarantee:
        // metadata-boost does not disturb an upstream stage's ordering among same-confidence items.
        final var in = List.of( sec( "a" ), sec( "b" ), sec( "c" ), sec( "d" ) );
        final var out = new MetadataBoostSectionReranker( conf( Map.of() ), 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "a", "b", "c", "d" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void boundedByPositions_cannotCrossALargeGap() {
        // incoming [p0,p1,p2,p3,auth4]; positions 1.5 -> auth key 2.5 -> auth moves 4->3, not to the top.
        final var in = List.of( sec( "p0" ), sec( "p1" ), sec( "p2" ), sec( "p3" ), sec( "auth4" ) );
        final var c = conf( Map.of( "auth4", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "p0", "p1", "p2", "auth4", "p3" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void positionsZero_isIdentity() {
        final var in = List.of( sec( "a" ), sec( "b" ) );
        assertSame( in, new MetadataBoostSectionReranker( conf( Map.of() ), 0.0, 24 ).rerank( "q", in ) );
    }

    @Test
    void nullConfidenceLookup_isIdentity() {
        final var in = List.of( sec( "a" ), sec( "b" ) );
        assertSame( in, new MetadataBoostSectionReranker( null, 1.5, 24 ).rerank( "q", in ) );
    }

    @Test
    void neverDrops_andBeyondWindowUntouched() {
        // window=2: only indices 0,1 are boostable; indices 2,3 keep their order and all survive.
        final var in = List.of( sec( "stale0" ), sec( "auth1" ), sec( "x2" ), sec( "y3" ) );
        final var c = conf( Map.of( "stale0", Confidence.STALE, "auth1", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 2 ).rerank( "q", in );
        assertEquals( in.size(), out.size() );
        assertTrue( out.containsAll( in ) );
        // window [stale0,auth1] -> [auth1,stale0]; tail [x2,y3] unchanged
        assertEquals( List.of( "auth1", "stale0", "x2", "y3" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void lookupThrows_returnsInputOrder() {
        final var in = List.of( sec( "a" ), sec( "b" ) );
        final Function< String, Confidence > boom = slug -> { throw new IllegalStateException( "boom" ); };
        assertSame( in, new MetadataBoostSectionReranker( boom, 1.5, 24 ).rerank( "q", in ) );
    }
}
