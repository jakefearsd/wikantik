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
import static org.junit.jupiter.api.Assertions.*;

class LlmSectionRerankerTest {

    private static CandidateSection sec( String slug, String head ) {
        return new CandidateSection( slug, List.of( head ), head + " text", 0.5 );
    }

    @Test
    void reorders_by_model_ranking_and_appends_unranked() {
        final List<CandidateSection> in = List.of( sec("p","A"), sec("p","B"), sec("p","C") );
        // model says order is 2,3 (1-based) -> B, C, then A appended (unranked)
        final LlmSectionReranker r = new LlmSectionReranker(
            new RerankerConfig( "gemma4:e4b", "http://x", 1000 ),
            ( prompt ) -> "{\"ranking\":[2,3]}" );   // injected responder (no network)
        final List<CandidateSection> out = r.rerank( "q", in );
        assertEquals( List.of("B","C","A"), out.stream().map( s -> s.headingPath().get(0) ).toList() );
    }

    @Test
    void empty_or_garbage_ranking_returns_input_order() {
        final List<CandidateSection> in = List.of( sec("p","A"), sec("p","B") );
        final LlmSectionReranker r = new LlmSectionReranker(
            new RerankerConfig( "m", "http://x", 1000 ), ( p ) -> "not json" );
        assertEquals( in, r.rerank( "q", in ) );
    }
}
