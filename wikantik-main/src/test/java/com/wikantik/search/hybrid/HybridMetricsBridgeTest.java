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

import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the metric names/tags that operators dashboard against. Any rename or
 * tag change must be deliberate — update this test and the operator docs in
 * lockstep.
 */
class HybridMetricsBridgeTest {

    private static QueryEmbedderMetrics fixedMetrics() {
        return new QueryEmbedderMetrics(
            /*callSuccess*/     10,
            /*callFailure*/     3,
            /*callTimeout*/     1,
            /*cacheHit*/        20,
            /*cacheMiss*/       5,
            /*breakerOpen*/     2,
            /*breakerClose*/    2,
            /*breakerHalfOpenProbe*/ 1,
            /*breakerCallRejected*/  7
        );
    }

    @Test
    void registersEmbedderCallsByResult() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.metrics() ).thenReturn( fixedMetrics() );
        when( embedder.circuitState() ).thenReturn( CircuitState.CLOSED );

        HybridMetricsBridge.register( reg, embedder, null, null );

        assertEquals( 10.0, reg.find( "wikantik.search.hybrid.embedder.calls" )
            .tag( "result", "success" ).functionCounter().count() );
        assertEquals( 3.0, reg.find( "wikantik.search.hybrid.embedder.calls" )
            .tag( "result", "failure" ).functionCounter().count() );
        assertEquals( 1.0, reg.find( "wikantik.search.hybrid.embedder.calls" )
            .tag( "result", "timeout" ).functionCounter().count() );
    }

    @Test
    void registersCacheAndBreakerMeters() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.metrics() ).thenReturn( fixedMetrics() );
        when( embedder.circuitState() ).thenReturn( CircuitState.OPEN );

        HybridMetricsBridge.register( reg, embedder, null, null );

        assertEquals( 20.0, reg.find( "wikantik.search.hybrid.embedder.cache" )
            .tag( "result", "hit" ).functionCounter().count() );
        assertEquals( 5.0, reg.find( "wikantik.search.hybrid.embedder.cache" )
            .tag( "result", "miss" ).functionCounter().count() );

        assertEquals( 2.0, reg.find( "wikantik.search.hybrid.embedder.breaker.transitions" )
            .tag( "to", "open" ).functionCounter().count() );
        assertEquals( 2.0, reg.find( "wikantik.search.hybrid.embedder.breaker.transitions" )
            .tag( "to", "close" ).functionCounter().count() );
        assertEquals( 1.0, reg.find( "wikantik.search.hybrid.embedder.breaker.transitions" )
            .tag( "to", "half_open" ).functionCounter().count() );

        assertEquals( 7.0, reg.find( "wikantik.search.hybrid.embedder.breaker.rejected" )
            .functionCounter().count() );

        // CircuitState.OPEN → 2.0
        assertEquals( 2.0, reg.find( "wikantik.search.hybrid.embedder.circuit_state" )
            .gauge().value() );
    }

    @Test
    void registersBootstrapStateGauge() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final BootstrapEmbeddingIndexer boot = mock( BootstrapEmbeddingIndexer.class );
        when( boot.progress() ).thenReturn( new BootstrapEmbeddingIndexer.Progress(
            BootstrapEmbeddingIndexer.State.RUNNING, 42, java.time.Instant.now(), null, null ) );

        HybridMetricsBridge.register( reg, null, boot, null );

        assertEquals( 3.0, reg.find( "wikantik.search.hybrid.bootstrap.state" ).gauge().value() );
        assertEquals( 42.0, reg.find( "wikantik.search.hybrid.bootstrap.chunks_total" ).gauge().value() );
    }

    @Test
    void nullRegistryIsNoOp() {
        // Must not NPE when the observability extension hasn't run.
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        HybridMetricsBridge.register( null, embedder, null, null );
    }

    @Test
    void skipsEmbedderMetersWhenEmbedderMissing() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        HybridMetricsBridge.register( reg, null, null, null );
        final List< Meter > meters = reg.getMeters();
        assertTrue( meters.isEmpty(), "no meters should register when all sources are null" );
    }

    @Test
    void vectorIndexGaugeOnlyForInMemoryImpl() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        // Interface-only mock has no size() to read — gauge should be skipped.
        final ChunkVectorIndex iface = mock( ChunkVectorIndex.class );
        HybridMetricsBridge.register( reg, null, null, iface );
        assertEquals( null, reg.find( "wikantik.search.hybrid.vector_index.size" ).gauge() );
    }

    @Test
    void bootstrapSkippedStatesEncodedAsExpected() {
        for( final var entry : List.of(
                java.util.Map.entry( BootstrapEmbeddingIndexer.State.IDLE, 0d ),
                java.util.Map.entry( BootstrapEmbeddingIndexer.State.SKIPPED_ALREADY_POPULATED, 1d ),
                java.util.Map.entry( BootstrapEmbeddingIndexer.State.SKIPPED_NO_CHUNKS, 2d ),
                java.util.Map.entry( BootstrapEmbeddingIndexer.State.RUNNING, 3d ),
                java.util.Map.entry( BootstrapEmbeddingIndexer.State.COMPLETED, 4d ),
                java.util.Map.entry( BootstrapEmbeddingIndexer.State.FAILED, 5d ) ) ) {
            final MeterRegistry reg = new SimpleMeterRegistry();
            final BootstrapEmbeddingIndexer boot = mock( BootstrapEmbeddingIndexer.class );
            when( boot.progress() ).thenReturn( new BootstrapEmbeddingIndexer.Progress(
                entry.getKey(), 0, null, null, null ) );
            HybridMetricsBridge.register( reg, null, boot, null );
            assertNotNull( reg.find( "wikantik.search.hybrid.bootstrap.state" ).gauge() );
            assertEquals( entry.getValue(),
                reg.find( "wikantik.search.hybrid.bootstrap.state" ).gauge().value(),
                "unexpected encoding for " + entry.getKey() );
        }
    }
}
