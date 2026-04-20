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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Prometheus meter names, tag keys, and tag values that operator
 * dashboards and Grafana alerts depend on. A rename or tag removal here is
 * an operator-visible break — this test forces it to be deliberate.
 */
class ToolsMetricsBridgeTest {

    private static final String PFX = "wikantik.tools";

    @Test
    void nullRegistryIsNoOp() {
        // Observability module may not be installed in every deployment.
        ToolsMetricsBridge.register( null, new ToolsMetrics() );
        // No exception = pass.
    }

    @Test
    void nullMetricsIsNoOp() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        ToolsMetricsBridge.register( reg, null );
        assertTrue( reg.getMeters().isEmpty(),
                "register(reg, null) must not create meters against live sources" );
    }

    @Test
    void registersAllRequestCountersWithCorrectTags() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final ToolsMetrics metrics = new ToolsMetrics();
        ToolsMetricsBridge.register( reg, metrics );

        // search_wiki: success + error
        assertFunctionCounter( reg, PFX + ".requests", "endpoint", "search_wiki", "status", "success" );
        assertFunctionCounter( reg, PFX + ".requests", "endpoint", "search_wiki", "status", "error" );

        // get_page: success + not_found + error
        assertFunctionCounter( reg, PFX + ".requests", "endpoint", "get_page", "status", "success" );
        assertFunctionCounter( reg, PFX + ".requests", "endpoint", "get_page", "status", "not_found" );
        assertFunctionCounter( reg, PFX + ".requests", "endpoint", "get_page", "status", "error" );

        // openapi served
        assertFunctionCounter( reg, PFX + ".requests", "endpoint", "openapi", "status", "success" );
    }

    @Test
    void registersAggregateCounters() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        ToolsMetricsBridge.register( reg, new ToolsMetrics() );

        assertNotNull( reg.find( PFX + ".search.results_returned" ).functionCounter(),
                "results_returned meter must be registered" );
        assertNotNull( reg.find( PFX + ".get_page.truncated" ).functionCounter(),
                "get_page.truncated meter must be registered" );
    }

    @Test
    void countersReadLiveStateFromSharedToolsMetrics() {
        // Verifies the FunctionCounter wiring: bumping the source object must
        // be reflected in the registered meter without any bridge-side bookkeeping.
        final MeterRegistry reg = new SimpleMeterRegistry();
        final ToolsMetrics metrics = new ToolsMetrics();
        ToolsMetricsBridge.register( reg, metrics );

        metrics.recordSearchSuccess( 7 );
        metrics.recordSearchSuccess( 3 );
        metrics.recordGetPageSuccess( /*truncated*/ true );
        metrics.recordGetPageSuccess( /*truncated*/ false );
        metrics.recordGetPageNotFound();
        metrics.recordOpenapiServed();

        assertEquals( 2.0, reg.find( PFX + ".requests" )
                .tag( "endpoint", "search_wiki" ).tag( "status", "success" )
                .functionCounter().count() );
        assertEquals( 10.0, reg.find( PFX + ".search.results_returned" )
                .functionCounter().count(),
                "results_returned must accumulate the resultCount arg across calls" );

        assertEquals( 2.0, reg.find( PFX + ".requests" )
                .tag( "endpoint", "get_page" ).tag( "status", "success" )
                .functionCounter().count() );
        assertEquals( 1.0, reg.find( PFX + ".get_page.truncated" )
                .functionCounter().count(),
                "truncated counter must increment only when recordGetPageSuccess(true)" );

        assertEquals( 1.0, reg.find( PFX + ".requests" )
                .tag( "endpoint", "get_page" ).tag( "status", "not_found" )
                .functionCounter().count() );
        assertEquals( 1.0, reg.find( PFX + ".requests" )
                .tag( "endpoint", "openapi" ).tag( "status", "success" )
                .functionCounter().count() );
    }

    @Test
    void unknownTagCombinationsAreNotRegistered() {
        // If an operator's Grafana panel targets a tag combination we don't
        // actually publish, the panel will silently plot zero. Pin the
        // inverse: these combinations must be absent so a test breaks the
        // day someone adds a stray meter.
        final MeterRegistry reg = new SimpleMeterRegistry();
        ToolsMetricsBridge.register( reg, new ToolsMetrics() );

        // get_page has no "not_found" counterpart under search_wiki
        assertNull( reg.find( PFX + ".requests" )
                .tag( "endpoint", "search_wiki" ).tag( "status", "not_found" )
                .functionCounter(),
                "search_wiki.not_found would confuse operators — must not exist" );
        // openapi is a read-only spec fetch; no error/not_found variants
        assertNull( reg.find( PFX + ".requests" )
                .tag( "endpoint", "openapi" ).tag( "status", "error" )
                .functionCounter(),
                "openapi.error is not a real outcome — must not be published" );
    }

    @Test
    void onlyExpectedMetersArePublished() {
        // Upper-bound the surface area: if the total meter count drifts,
        // some later change has added meters without updating operator
        // dashboards. Keep this lock in sync with the bridge intentionally.
        final MeterRegistry reg = new SimpleMeterRegistry();
        ToolsMetricsBridge.register( reg, new ToolsMetrics() );

        final List< Meter > meters = reg.getMeters();
        // 6 request counters + results_returned + truncated = 8
        assertEquals( 8, meters.size(),
                "ToolsMetricsBridge publishes exactly 8 meters; update this test when the contract changes intentionally" );
    }

    private static void assertFunctionCounter( final MeterRegistry reg,
                                               final String name,
                                               final String tagKey1, final String tagValue1,
                                               final String tagKey2, final String tagValue2 ) {
        final FunctionCounter fc = reg.find( name )
                .tag( tagKey1, tagValue1 )
                .tag( tagKey2, tagValue2 )
                .functionCounter();
        assertNotNull( fc, "Missing meter: " + name
                + " [" + tagKey1 + "=" + tagValue1 + "," + tagKey2 + "=" + tagValue2 + "]" );
    }
}
