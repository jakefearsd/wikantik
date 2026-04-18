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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageAggregationTest {

    private static final double EPS = 1e-9;

    @Test
    void maxReturnsFirstElement() {
        assertEquals( 0.9, PageAggregation.MAX.aggregate( List.of( 0.9, 0.8, 0.7 ) ), EPS );
    }

    @Test
    void meanTop3OfThreeIsAverage() {
        assertEquals( 0.7, PageAggregation.MEAN_TOP_3.aggregate( List.of( 0.9, 0.7, 0.5 ) ), EPS );
    }

    @Test
    void meanTop3HandlesShorterLists() {
        assertEquals( 0.8, PageAggregation.MEAN_TOP_3.aggregate( List.of( 0.9, 0.7 ) ), EPS );
        assertEquals( 0.9, PageAggregation.MEAN_TOP_3.aggregate( List.of( 0.9 ) ), EPS );
    }

    @Test
    void sumTop3SumsTopThree() {
        assertEquals( 2.1, PageAggregation.SUM_TOP_3.aggregate( List.of( 0.9, 0.7, 0.5, 0.3 ) ), EPS );
    }

    @Test
    void sumTop3HandlesShorterLists() {
        assertEquals( 0.9, PageAggregation.SUM_TOP_3.aggregate( List.of( 0.9 ) ), EPS );
    }

    @Test
    void sumTop5SumsTopFive() {
        assertEquals( 3.0, PageAggregation.SUM_TOP_5.aggregate( List.of( 1.0, 0.8, 0.6, 0.4, 0.2, 0.1 ) ), EPS );
    }

    @Test
    void sumTop5HandlesShorterLists() {
        assertEquals( 1.5, PageAggregation.SUM_TOP_5.aggregate( List.of( 1.0, 0.5 ) ), EPS );
    }

    @Test
    void singleScoreWorksForAllStrategies() {
        final List< Double > one = List.of( 0.42 );
        for( final PageAggregation a : PageAggregation.values() ) {
            assertEquals( 0.42, a.aggregate( one ), EPS );
        }
    }

    @Test
    void emptyListThrowsForAllStrategies() {
        for( final PageAggregation a : PageAggregation.values() ) {
            assertThrows( IllegalArgumentException.class, () -> a.aggregate( List.of() ) );
        }
    }

    @Test
    void nullListThrowsForAllStrategies() {
        for( final PageAggregation a : PageAggregation.values() ) {
            assertThrows( IllegalArgumentException.class, () -> a.aggregate( null ) );
        }
    }
}
