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
package com.wikantik.insights;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class InsightsMetricsCardinalityTest {

    private static final Set< String > FORBIDDEN = Set.of( "page", "page_path", "query",
                                                           "query_text", "session", "slug" );

    @Test
    void noInsightsMetricCarriesAnUnboundedLabel() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );

        metrics.recordIngest( "bing", "ok", 42 );
        metrics.recordIngest( "google", "error", 0 );

        for ( final Meter m : reg.getMeters() ) {
            for ( final var tag : m.getId().getTags() ) {
                assertFalse( FORBIDDEN.contains( tag.getKey() ),
                        "metric " + m.getId().getName() + " carries unbounded label " + tag.getKey() );
            }
        }
        assertFalse( reg.getMeters().isEmpty(), "metrics must actually be registered" );
    }

    @Test
    void repeatedCallsIncrementWithoutCreatingNewSeries() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );

        metrics.recordIngest( "bing", "ok", 42 );
        final int metersAfterFirst = reg.getMeters().size();

        metrics.recordIngest( "bing", "ok", 8 );
        final int metersAfterSecond = reg.getMeters().size();

        assertEquals( metersAfterFirst, metersAfterSecond,
                "repeated calls for the same engine+outcome must not create new metric series" );

        // Verify the counter was actually incremented
        final Counter counter = reg.find( "wikantik.insights.ingest.rows" )
            .tag( "engine", "bing" )
            .tag( "outcome", "ok" )
            .counter();
        assertNotNull( counter, "counter should exist for bing/ok" );
        assertEquals( 50.0, counter.count(), 0.001,
                "counter should have accumulated both calls: 42 + 8" );
    }

    @Test
    void lastSuccessGaugeUpdatesOnEverySuccessNotJustTheFirst() {
        // registry.gauge(name, tags, Number) binds only on FIRST registration and holds a weak
        // reference to the value. Both failures are silent: the gauge either freezes at the first
        // success or reports NaN once the boxed Double is collected. Since staleness on this gauge
        // IS the alert condition, either one means the alert never fires or fires forever.
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );

        metrics.recordSuccessTimestamp( "bing", 1_000L );
        assertEquals( 1_000.0, gaugeValue( reg, "bing" ), 0.001 );

        metrics.recordSuccessTimestamp( "bing", 2_000L );
        assertEquals( 2_000.0, gaugeValue( reg, "bing" ), 0.001,
                "the gauge must track the latest success, not freeze at the first" );
    }

    @Test
    void lastSuccessGaugeSurvivesGarbageCollection() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );
        metrics.recordSuccessTimestamp( "google", 4_242L );

        System.gc();   // a weakly-referenced backing value would be collected here

        assertEquals( 4_242.0, gaugeValue( reg, "google" ), 0.001,
                "gauge reported NaN after GC — the backing value is not strongly held" );
    }

    @Test
    void eachEngineGetsItsOwnIndependentGauge() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );

        metrics.recordSuccessTimestamp( "bing", 100L );
        metrics.recordSuccessTimestamp( "google", 200L );

        assertEquals( 100.0, gaugeValue( reg, "bing" ), 0.001 );
        assertEquals( 200.0, gaugeValue( reg, "google" ), 0.001 );
    }

    @Test
    void aFailedIngestDoesNotAdvanceTheSuccessTimestamp() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );

        metrics.recordIngest( "bing", "error", 0 );

        assertNull( reg.find( InsightsMetrics.LAST_SUCCESS_METRIC ).tag( "engine", "bing" ).gauge(),
                "an error must never look like a recent success to the staleness alert" );
    }

    private static double gaugeValue( final SimpleMeterRegistry reg, final String engine ) {
        final io.micrometer.core.instrument.Gauge g =
                reg.find( InsightsMetrics.LAST_SUCCESS_METRIC ).tag( "engine", engine ).gauge();
        assertNotNull( g, "gauge not registered for engine " + engine );
        return g.value();
    }
}
