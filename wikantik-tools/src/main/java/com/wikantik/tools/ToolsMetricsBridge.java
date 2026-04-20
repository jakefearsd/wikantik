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

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes OpenAPI tool-server counters to the process-wide Micrometer
 * {@link MeterRegistry} so they appear on the Prometheus scrape endpoint.
 *
 * <p>Meters registered (all prefixed {@code wikantik.tools}):</p>
 * <ul>
 *   <li>{@code .requests} tagged {@code endpoint=search_wiki|get_page|openapi},
 *       {@code status=success|error|not_found}</li>
 *   <li>{@code .search.results_returned} — total result rows emitted</li>
 *   <li>{@code .get_page.truncated} — get_page responses that hit the truncation cap</li>
 * </ul>
 */
public final class ToolsMetricsBridge {

    private static final Logger LOG = LogManager.getLogger( ToolsMetricsBridge.class );
    private static final String PFX = "wikantik.tools";

    private ToolsMetricsBridge() {}

    public static void register( final MeterRegistry registry, final ToolsMetrics metrics ) {
        if ( registry == null ) {
            LOG.warn( "ToolsMetricsBridge: no shared MeterRegistry available; tool metrics will not publish" );
            return;
        }
        if ( metrics == null ) {
            return;
        }

        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::searchSuccess )
                .tags( "endpoint", "search_wiki", "status", "success" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );
        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::searchError )
                .tags( "endpoint", "search_wiki", "status", "error" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );

        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::getPageSuccess )
                .tags( "endpoint", "get_page", "status", "success" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );
        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::getPageNotFound )
                .tags( "endpoint", "get_page", "status", "not_found" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );
        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::getPageForbidden )
                .tags( "endpoint", "get_page", "status", "forbidden" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );
        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::getPageError )
                .tags( "endpoint", "get_page", "status", "error" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );

        FunctionCounter.builder( PFX + ".requests", metrics, ToolsMetrics::openapiServed )
                .tags( "endpoint", "openapi", "status", "success" )
                .description( "Tool server requests by endpoint and outcome" )
                .register( registry );

        FunctionCounter.builder( PFX + ".search.results_returned", metrics, ToolsMetrics::resultsReturned )
                .description( "Total search result rows returned across all search_wiki calls" )
                .register( registry );

        FunctionCounter.builder( PFX + ".get_page.truncated", metrics, ToolsMetrics::getPageTruncated )
                .description( "get_page responses whose body hit the truncation cap" )
                .register( registry );

        LOG.info( "Tool server metrics published to shared registry" );
    }
}
