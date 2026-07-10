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
import static org.junit.jupiter.api.Assertions.assertSame;

class SectionRerankChainTest {

    private static CandidateSection sec( final String head, final double score ) {
        return new CandidateSection( "p", List.of( head ), head + " text", score );
    }

    @Test
    void emptyChain_returnsInputUnchanged() {
        final List< CandidateSection > in = List.of( sec( "A", 0.9 ), sec( "B", 0.1 ) );
        assertSame( in, new SectionRerankChain( List.of() ).rerank( "q", in ) );
    }

    @Test
    void stagesApplyInOrder_eachFedPriorOutput() {
        // Stage 1 reverses; stage 2 reverses again -> original order, proving both ran in sequence.
        final SectionReranker reverse = ( q, s ) -> { final var c = new java.util.ArrayList<>( s ); java.util.Collections.reverse( c ); return c; };
        final List< CandidateSection > in = List.of( sec( "A", 0.9 ), sec( "B", 0.5 ), sec( "C", 0.1 ) );
        final var out = new SectionRerankChain( List.of( reverse, reverse ) ).rerank( "q", in );
        assertEquals( List.of( "A", "B", "C" ), out.stream().map( c -> c.headingPath().get( 0 ) ).toList() );
    }

    @Test
    void aThrowingStage_isSkipped_priorOrderKept() {
        final SectionReranker boom = ( q, s ) -> { throw new IllegalStateException( "boom" ); };
        final List< CandidateSection > in = List.of( sec( "A", 0.9 ), sec( "B", 0.1 ) );
        // chain: boom then identity -> boom is caught, input preserved
        final var out = new SectionRerankChain( List.of( boom, ( q, s ) -> s ) ).rerank( "q", in );
        assertEquals( List.of( "A", "B" ), out.stream().map( c -> c.headingPath().get( 0 ) ).toList() );
    }
}
