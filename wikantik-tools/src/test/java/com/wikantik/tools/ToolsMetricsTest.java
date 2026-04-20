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
package com.wikantik.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolsMetricsTest {

    @Test
    void searchSuccessIncrementsBothCounters() {
        final ToolsMetrics metrics = new ToolsMetrics();
        metrics.recordSearchSuccess( 3 );
        metrics.recordSearchSuccess( 2 );
        assertEquals( 2L, metrics.searchSuccess() );
        assertEquals( 5L, metrics.resultsReturned() );
    }

    @Test
    void zeroResultsDoesNotIncrementResultsReturned() {
        final ToolsMetrics metrics = new ToolsMetrics();
        metrics.recordSearchSuccess( 0 );
        assertEquals( 1L, metrics.searchSuccess() );
        assertEquals( 0L, metrics.resultsReturned() );
    }

    @Test
    void truncatedGetPageIncrementsTruncationCounter() {
        final ToolsMetrics metrics = new ToolsMetrics();
        metrics.recordGetPageSuccess( true );
        metrics.recordGetPageSuccess( false );
        assertEquals( 2L, metrics.getPageSuccess() );
        assertEquals( 1L, metrics.getPageTruncated() );
    }

    @Test
    void bridgeRegistersAllExpectedMeters() {
        final ToolsMetrics metrics = new ToolsMetrics();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ToolsMetricsBridge.register( registry, metrics );

        assertNotNull( registry.find( "wikantik.tools.requests" )
                .tags( "endpoint", "search_wiki", "status", "success" ).functionCounter() );
        assertNotNull( registry.find( "wikantik.tools.requests" )
                .tags( "endpoint", "get_page", "status", "not_found" ).functionCounter() );
        assertNotNull( registry.find( "wikantik.tools.search.results_returned" ).functionCounter() );
        assertNotNull( registry.find( "wikantik.tools.get_page.truncated" ).functionCounter() );
    }

    @Test
    void bridgeTolerantOfNullRegistry() {
        final ToolsMetrics metrics = new ToolsMetrics();
        assertDoesNotThrow( () -> ToolsMetricsBridge.register( null, metrics ) );
    }
}
