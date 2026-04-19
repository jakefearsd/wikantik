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
package com.wikantik.search.embedding.experiment;

import com.wikantik.search.embedding.experiment.ReciprocalRankFusion.Ranking;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {

    @Test
    void singleListPreservesOrder() {
        final Ranking r = new Ranking( List.of( "a", "b", "c", "d" ) );
        assertEquals( List.of( "a", "b", "c", "d" ),
            ReciprocalRankFusion.fuse( List.of( r ), ReciprocalRankFusion.DEFAULT_K ) );
    }

    @Test
    void itemInBothListsOutranksItemInOne() {
        // "a" appears at rank 2 in both; "b" only appears at rank 1 in one.
        // RRF score (k=60): a = 2*(1/62) = 0.032..; b = 1/61 = 0.0164..
        final Ranking r1 = new Ranking( List.of( "b", "a" ) );
        final Ranking r2 = new Ranking( List.of( "c", "a" ) );
        final List< String > fused = ReciprocalRankFusion.fuse( List.of( r1, r2 ), 60 );
        assertEquals( "a", fused.get( 0 ) );
        assertTrue( fused.contains( "b" ) );
        assertTrue( fused.contains( "c" ) );
    }

    @Test
    void higherRankBeatsLowerRankWithinList() {
        final Ranking r = new Ranking( List.of( "top", "middle", "bottom" ) );
        final List< String > fused = ReciprocalRankFusion.fuse( List.of( r ), 60 );
        assertEquals( "top", fused.get( 0 ) );
        assertEquals( "bottom", fused.get( 2 ) );
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertTrue( ReciprocalRankFusion.fuse( List.of(), 60 ).isEmpty() );
        assertTrue( ReciprocalRankFusion.fuse( List.of( new Ranking( List.of() ) ), 60 ).isEmpty() );
    }

    @Test
    void nullItemIsIgnored() {
        final Ranking r = new Ranking( java.util.Arrays.asList( "a", null, "b" ) );
        final List< String > fused = ReciprocalRankFusion.fuse( List.of( r ), 60 );
        assertEquals( List.of( "a", "b" ), fused );
    }

    @Test
    void defaultKIsSixty() {
        assertEquals( 60, ReciprocalRankFusion.DEFAULT_K );
    }

    @Test
    void rejectsNonPositiveK() {
        final Ranking r = new Ranking( List.of( "a" ) );
        assertThrows( IllegalArgumentException.class,
            () -> ReciprocalRankFusion.fuse( List.of( r ), 0 ) );
        assertThrows( IllegalArgumentException.class,
            () -> ReciprocalRankFusion.fuse( List.of( r ), -1 ) );
    }

    @Test
    void threeWayFusionAccumulatesScores() {
        // "x" in all three lists at rank 3; "y" at rank 1 in one list only.
        // x: 3/(60+3) = 0.0476; y: 1/61 = 0.0164 → x wins.
        final Ranking r1 = new Ranking( List.of( "a", "b", "x" ) );
        final Ranking r2 = new Ranking( List.of( "c", "d", "x" ) );
        final Ranking r3 = new Ranking( List.of( "y", "e", "x" ) );
        final List< String > fused = ReciprocalRankFusion.fuse( List.of( r1, r2, r3 ), 60 );
        assertEquals( "x", fused.get( 0 ) );
    }
}
