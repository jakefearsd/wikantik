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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageAggregatorTest {

    private static final double EPS = 1e-9;

    private static ScoredChunk chunk( final String page, final double score ) {
        return new ScoredChunk( UUID.randomUUID(), page, score );
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        final PageAggregator agg = new PageAggregator();
        assertTrue( agg.aggregate( List.of(), PageAggregation.MAX ).isEmpty() );
    }

    @Test
    void nullInputProducesEmptyOutput() {
        final PageAggregator agg = new PageAggregator();
        assertTrue( agg.aggregate( null, PageAggregation.SUM_TOP_3 ).isEmpty() );
    }

    @Test
    void nullStrategyThrows() {
        final PageAggregator agg = new PageAggregator();
        assertThrows( IllegalArgumentException.class,
            () -> agg.aggregate( List.of( chunk( "A", 0.5 ) ), null ) );
    }

    @Test
    void maxStrategyRanksByBestChunkPerPage() {
        final PageAggregator agg = new PageAggregator();
        final List< ScoredPage > pages = agg.aggregate( List.of(
            chunk( "A", 0.60 ),
            chunk( "B", 0.90 ),
            chunk( "A", 0.80 ),
            chunk( "B", 0.10 )
        ), PageAggregation.MAX );
        assertEquals( 2, pages.size() );
        assertEquals( "B", pages.get( 0 ).pageName() );
        assertEquals( 0.90, pages.get( 0 ).score(), EPS );
        assertEquals( "A", pages.get( 1 ).pageName() );
        assertEquals( 0.80, pages.get( 1 ).score(), EPS );
    }

    @Test
    void sumTop3AggregatesAndRanksCorrectly() {
        final PageAggregator agg = new PageAggregator();
        final List< ScoredPage > pages = agg.aggregate( List.of(
            chunk( "A", 0.3 ), chunk( "A", 0.4 ), chunk( "A", 0.5 ), chunk( "A", 0.1 ),
            chunk( "B", 0.9 )
        ), PageAggregation.SUM_TOP_3 );
        assertEquals( "A", pages.get( 0 ).pageName() );
        assertEquals( 1.2, pages.get( 0 ).score(), EPS );
        assertEquals( "B", pages.get( 1 ).pageName() );
        assertEquals( 0.9, pages.get( 1 ).score(), EPS );
    }

    @Test
    void inputScoresDoNotNeedToBeSorted() {
        final PageAggregator agg = new PageAggregator();
        final List< ScoredPage > pages = agg.aggregate( List.of(
            chunk( "A", 0.1 ),
            chunk( "A", 0.9 ),
            chunk( "A", 0.5 )
        ), PageAggregation.MAX );
        assertEquals( 0.9, pages.get( 0 ).score(), EPS );
    }

    @Test
    void meanTop3ComputesMeanOfTopThree() {
        final PageAggregator agg = new PageAggregator();
        final List< ScoredPage > pages = agg.aggregate( List.of(
            chunk( "A", 0.6 ), chunk( "A", 0.9 ), chunk( "A", 0.3 ), chunk( "A", 0.1 )
        ), PageAggregation.MEAN_TOP_3 );
        assertEquals( 0.6, pages.get( 0 ).score(), EPS );
    }
}
