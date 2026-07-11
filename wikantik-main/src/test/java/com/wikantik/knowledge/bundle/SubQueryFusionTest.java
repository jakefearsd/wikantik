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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubQueryFusionTest {

    private static CandidateSection sec( String slug, String heading, double dense ) {
        return new CandidateSection( slug, List.of( heading ), slug + "/" + heading + " body", dense );
    }

    @Test void fusesTwoListsInterleavingBothSides() {
        // sub-query A surfaces the "canary" side; sub-query B the "blue-green" side.
        SectionCandidates a = SectionCandidates.of(
            List.of( sec( "Canary", "Traffic Splitting", 0.80 ), sec( "Canary", "Analysis", 0.70 ) ), 0.80, true );
        SectionCandidates b = SectionCandidates.of(
            List.of( sec( "BlueGreen", "When canary wins", 0.78 ), sec( "BlueGreen", "Cutover", 0.60 ) ), 0.78, true );

        SectionCandidates fused = new SubQueryFusion( 60 ).fuse( List.of( a, b ) );

        // both top-ranked sections from each side survive and rank above the tails
        List< String > slugs = fused.sections().stream().map( CandidateSection::slug ).toList();
        assertTrue( slugs.indexOf( "Canary" ) >= 0 && slugs.indexOf( "BlueGreen" ) >= 0 );
        // rank-1 of each list (rrf 1/61 each) outranks rank-2 of each list (1/62)
        assertEquals( "Traffic Splitting", fused.sections().get( 0 ).headingPath().get( 0 ) );
        assertEquals( "When canary wins", fused.sections().get( 1 ).headingPath().get( 0 ) );
    }

    @Test void keepsMaxDenseScoreAcrossLists() {
        SectionCandidates a = SectionCandidates.of( List.of( sec( "P", "H", 0.40 ) ), 0.40, true );
        SectionCandidates b = SectionCandidates.of( List.of( sec( "P", "H", 0.90 ) ), 0.90, true );
        SectionCandidates fused = new SubQueryFusion( 60 ).fuse( List.of( a, b ) );
        assertEquals( 1, fused.sections().size() );          // deduped by (slug, headingPath)
        assertEquals( 0.90, fused.sections().get( 0 ).denseScore(), 1e-9 );
        assertEquals( 0.90, fused.topSimilarity(), 1e-9 );
    }

    @Test void denseCosineScaleFalseIfAnyInputFalse() {
        SectionCandidates a = SectionCandidates.of( List.of( sec( "P", "H", 0.4 ) ), 0.4, true );
        SectionCandidates b = SectionCandidates.of( List.of( sec( "Q", "H", 0.0 ) ), -1.0, false );
        assertTrue( ! new SubQueryFusion( 60 ).fuse( List.of( a, b ) ).denseCosineScale() );
    }

    @Test void emptyAndSingleton() {
        assertEquals( 0, new SubQueryFusion( 60 ).fuse( List.of() ).sections().size() );
        SectionCandidates one = SectionCandidates.of( List.of( sec( "P", "H", 0.5 ) ), 0.5, true );
        assertEquals( one, new SubQueryFusion( 60 ).fuse( List.of( one ) ) );
    }
}
