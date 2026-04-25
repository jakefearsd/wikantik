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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalRunResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalQualityMetricsTest {

    private static RetrievalRunResult sample( final Double n5, final Double n10,
                                              final Double r20, final Double mrr ) {
        return new RetrievalRunResult( 0L, "core", RetrievalMode.HYBRID,
            n5, n10, r20, mrr,
            Instant.parse( "2026-04-25T03:00:00Z" ),
            Instant.parse( "2026-04-25T03:01:00Z" ),
            5, 0, false );
    }

    @Test
    void bind_registersDurationAndFailureMeters() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.bind( reg );

        assertNotNull( reg.find( "wikantik_retrieval_run_duration_seconds" ).timer() );
        assertNotNull( reg.find( "wikantik_retrieval_run_failed_total" ).counter() );
    }

    @Test
    void recordRun_publishesAggregateGauges() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.bind( reg );

        m.recordRun( sample( 0.85, 0.83, 0.92, 0.74 ) );

        final Gauge ndcg5 = reg.find( "wikantik_retrieval_ndcg_at_5" )
            .tag( "set", "core" ).tag( "mode", "hybrid" ).gauge();
        assertNotNull( ndcg5 );
        assertEquals( 0.85, ndcg5.value(), 1e-9 );

        final Gauge recall = reg.find( "wikantik_retrieval_recall_at_20" )
            .tag( "set", "core" ).tag( "mode", "hybrid" ).gauge();
        assertEquals( 0.92, recall.value(), 1e-9 );
    }

    @Test
    void recordRun_nullAggregateSurfacesAsNaN() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.bind( reg );

        m.recordRun( sample( null, null, null, null ) );

        final Gauge ndcg5 = reg.find( "wikantik_retrieval_ndcg_at_5" )
            .tag( "set", "core" ).tag( "mode", "hybrid" ).gauge();
        assertNotNull( ndcg5 );
        assertTrue( Double.isNaN( ndcg5.value() ) );
    }

    @Test
    void recordRun_secondRunUpdatesSameGauge() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.bind( reg );

        m.recordRun( sample( 0.50, 0.50, 0.50, 0.50 ) );
        m.recordRun( sample( 0.75, 0.75, 0.75, 0.75 ) );

        final Gauge g = reg.find( "wikantik_retrieval_ndcg_at_5" )
            .tag( "set", "core" ).tag( "mode", "hybrid" ).gauge();
        assertEquals( 0.75, g.value(), 1e-9 );
        // Only one gauge instance per (set, mode) — not two.
        assertEquals( 1, reg.find( "wikantik_retrieval_ndcg_at_5" ).gauges().size() );
    }

    @Test
    void recordRun_differentModesProduceSeparateGauges() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.bind( reg );

        m.recordRun( sample( 0.6, 0.6, 0.6, 0.6 ) );
        m.recordRun( new RetrievalRunResult( 0L, "core", RetrievalMode.BM25,
            0.4, 0.4, 0.4, 0.4,
            Instant.parse( "2026-04-25T03:00:00Z" ),
            Instant.parse( "2026-04-25T03:01:00Z" ),
            5, 0, false ) );

        assertEquals( 2, reg.find( "wikantik_retrieval_ndcg_at_5" ).gauges().size() );
    }

    @Test
    void recordDuration_andFailure_areNoOpsBeforeBind() {
        // Should not throw — exercises the no-registry branch.
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.recordDuration( Duration.ofSeconds( 1 ) );
        m.recordFailure();
    }

    @Test
    void recordRun_nullResultIsNoOp() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        m.bind( reg );

        m.recordRun( null );

        assertEquals( 0, reg.find( "wikantik_retrieval_ndcg_at_5" ).gauges().size() );
    }
}
