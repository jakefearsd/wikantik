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

import com.wikantik.api.bundle.ContextBundle;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBundleAssemblyServiceDecompositionTest {

    // A stub source that records every query it is asked for and returns one section per query.
    private static final class RecordingSource implements SectionCandidateSource {
        final List< String > seen = new ArrayList<>();
        @Override public SectionCandidates candidates( final String query ) {
            seen.add( query );
            return SectionCandidates.of(
                List.of( new CandidateSection( "P-" + query, List.of( query ), query + " body", 0.7 ) ), 0.7, true );
        }
    }

    private static DefaultBundleAssemblyService svc( SectionCandidateSource src, QueryPlanner planner, boolean on ) {
        return DefaultBundleAssemblyServiceTestSupport.withDecomposition( src, planner, on );
    }

    @Test void offDoesNotConsultPlannerAndAsksOnlyOriginalQuery() {
        RecordingSource src = new RecordingSource();
        QueryPlanner planner = q -> { throw new AssertionError( "planner must not be called when disabled" ); };
        ContextBundle b = svc( src, planner, false ).assemble( "canary vs blue-green" );
        assertEquals( List.of( "canary vs blue-green" ), src.seen );     // single pass only
        assertTrue( b.sections().size() >= 1 );
    }

    @Test void onFusesSubQueryCandidates() {
        RecordingSource src = new RecordingSource();
        QueryPlanner planner = q -> List.of( "canary side", "blue-green side" );
        ContextBundle b = svc( src, planner, true ).assemble( "canary vs blue-green" );
        // original + 2 sub-queries all retrieved
        assertTrue( src.seen.contains( "canary vs blue-green" ) );
        assertTrue( src.seen.contains( "canary side" ) );
        assertTrue( src.seen.contains( "blue-green side" ) );
        // both sides present in the fused bundle
        List< String > slugs = b.sections().stream().map( s -> s.slug() ).toList();
        assertTrue( slugs.stream().anyMatch( s -> s.contains( "canary side" ) ) );
        assertTrue( slugs.stream().anyMatch( s -> s.contains( "blue-green side" ) ) );
    }

    @Test void onSubQueryRetrievalFailureDegradesInsteadOfAborting() {
        // One sub-query's retrieval throws; the bundle must still return (fusing the original +
        // the sub-queries that succeeded) rather than propagating the exception. "helps-or-no-ops".
        SectionCandidateSource src = new SectionCandidateSource() {
            @Override public SectionCandidates candidates( final String query ) {
                if ( "bad side".equals( query ) ) throw new RuntimeException( "transient embedder error" );
                return SectionCandidates.of(
                    List.of( new CandidateSection( "P-" + query, List.of( query ), query + " body", 0.7 ) ), 0.7, true );
            }
        };
        QueryPlanner planner = q -> List.of( "good side", "bad side" );
        ContextBundle b = svc( src, planner, true ).assemble( "good vs bad" );
        // no exception; the original + the good sub-query survive, the failed one is skipped
        List< String > slugs = b.sections().stream().map( s -> s.slug() ).toList();
        assertTrue( slugs.stream().anyMatch( s -> s.contains( "good side" ) ) );
        assertTrue( slugs.stream().anyMatch( s -> s.contains( "good vs bad" ) ) );  // original still present
        assertTrue( b.sections().size() >= 1 );
    }

    @Test void onButSingleIntentPlannerPassthroughIsSinglePass() {
        RecordingSource src = new RecordingSource();
        QueryPlanner planner = q -> List.of( q );        // passthrough
        svc( src, planner, true ).assemble( "single topic only" );
        assertEquals( List.of( "single topic only" ), src.seen );
    }
}
