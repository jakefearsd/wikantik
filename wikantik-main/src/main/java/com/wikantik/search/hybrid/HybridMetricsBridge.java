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

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes hybrid-retrieval counters and gauges to the process-wide
 * Micrometer {@link MeterRegistry} so they appear on the Prometheus scrape
 * endpoint served by the observability module.
 *
 * <p>All meters are registered via {@link FunctionCounter} and {@link Gauge}
 * so they lazily read live state from {@link QueryEmbedder#metrics()} and
 * {@link BootstrapEmbeddingIndexer#progress()} — no double-bookkeeping.</p>
 *
 * <p>Registered meters (all prefixed {@code wikantik.search.hybrid}):</p>
 * <ul>
 *   <li>{@code .embedder.calls} tagged {@code result=success|failure|timeout}</li>
 *   <li>{@code .embedder.cache} tagged {@code result=hit|miss}</li>
 *   <li>{@code .embedder.breaker.transitions} tagged {@code to=open|close|half_open}</li>
 *   <li>{@code .embedder.breaker.rejected} — calls short-circuited by OPEN state</li>
 *   <li>{@code .embedder.circuit_state} gauge (0=CLOSED, 1=HALF_OPEN, 2=OPEN)</li>
 *   <li>{@code .vector_index.size} gauge — chunks in the in-memory index</li>
 *   <li>{@code .bootstrap.state} gauge (0=IDLE, 1=SKIPPED_ALREADY_POPULATED,
 *       2=SKIPPED_NO_CHUNKS, 3=RUNNING, 4=COMPLETED, 5=FAILED)</li>
 *   <li>{@code .bootstrap.chunks_total} gauge — planned chunk count for the current run</li>
 * </ul>
 */
public final class HybridMetricsBridge {

    private static final Logger LOG = LogManager.getLogger( HybridMetricsBridge.class );

    private static final String PFX = "wikantik.search.hybrid";

    private HybridMetricsBridge() {}

    /**
     * Register embedder + bootstrap + vector-index meters against {@code registry}.
     * Null-safe on every source — skips the corresponding meters when a
     * dependency is absent so partial wire-ups still publish what they can.
     */
    public static void register( final MeterRegistry registry,
                                 final QueryEmbedder embedder,
                                 final BootstrapEmbeddingIndexer bootstrap,
                                 final ChunkVectorIndex vectorIndex ) {
        if( registry == null ) {
            LOG.warn( "HybridMetricsBridge: no shared MeterRegistry available; hybrid metrics will not publish" );
            return;
        }
        if( embedder != null ) {
            registerEmbedder( registry, embedder );
        }
        if( vectorIndex instanceof InMemoryChunkVectorIndex mem ) {
            Gauge.builder( PFX + ".vector_index.size", mem, InMemoryChunkVectorIndex::size )
                .description( "Chunk vectors currently held by the in-memory hybrid index" )
                .register( registry );
        }
        if( bootstrap != null ) {
            registerBootstrap( registry, bootstrap );
        }
        LOG.info( "Hybrid retrieval metrics published to shared registry "
            + "(embedder={}, bootstrap={}, vectorIndex={})",
            embedder != null, bootstrap != null, vectorIndex != null );
    }

    private static void registerEmbedder( final MeterRegistry registry, final QueryEmbedder embedder ) {
        FunctionCounter.builder( PFX + ".embedder.calls", embedder, e -> e.metrics().callSuccess() )
            .description( "Query embedder calls by terminal outcome" )
            .tag( "result", "success" )
            .register( registry );
        FunctionCounter.builder( PFX + ".embedder.calls", embedder, e -> e.metrics().callFailure() )
            .description( "Query embedder calls by terminal outcome" )
            .tag( "result", "failure" )
            .register( registry );
        FunctionCounter.builder( PFX + ".embedder.calls", embedder, e -> e.metrics().callTimeout() )
            .description( "Query embedder calls by terminal outcome" )
            .tag( "result", "timeout" )
            .register( registry );

        FunctionCounter.builder( PFX + ".embedder.cache", embedder, e -> e.metrics().cacheHit() )
            .description( "Query embedder cache outcome" )
            .tag( "result", "hit" )
            .register( registry );
        FunctionCounter.builder( PFX + ".embedder.cache", embedder, e -> e.metrics().cacheMiss() )
            .description( "Query embedder cache outcome" )
            .tag( "result", "miss" )
            .register( registry );

        FunctionCounter.builder( PFX + ".embedder.breaker.transitions", embedder, e -> e.metrics().breakerOpen() )
            .description( "Circuit breaker state transitions by destination state" )
            .tag( "to", "open" )
            .register( registry );
        FunctionCounter.builder( PFX + ".embedder.breaker.transitions", embedder, e -> e.metrics().breakerClose() )
            .description( "Circuit breaker state transitions by destination state" )
            .tag( "to", "close" )
            .register( registry );
        FunctionCounter.builder( PFX + ".embedder.breaker.transitions", embedder, e -> e.metrics().breakerHalfOpenProbe() )
            .description( "Circuit breaker state transitions by destination state" )
            .tag( "to", "half_open" )
            .register( registry );

        FunctionCounter.builder( PFX + ".embedder.breaker.rejected", embedder, e -> e.metrics().breakerCallRejected() )
            .description( "Query embedder calls short-circuited by an OPEN breaker" )
            .register( registry );

        Gauge.builder( PFX + ".embedder.circuit_state", embedder, HybridMetricsBridge::encodeCircuit )
            .description( "Current circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN)" )
            .register( registry );
    }

    private static double encodeCircuit( final QueryEmbedder embedder ) {
        final CircuitState s = embedder.circuitState();
        return switch( s ) {
            case CLOSED    -> 0d;
            case HALF_OPEN -> 1d;
            case OPEN      -> 2d;
        };
    }

    private static void registerBootstrap( final MeterRegistry registry,
                                           final BootstrapEmbeddingIndexer bootstrap ) {
        Gauge.builder( PFX + ".bootstrap.state", bootstrap, HybridMetricsBridge::encodeBootstrap )
            .description( "Embedding bootstrap state (0=IDLE, 1=SKIPPED_ALREADY_POPULATED, "
                + "2=SKIPPED_NO_CHUNKS, 3=RUNNING, 4=COMPLETED, 5=FAILED)" )
            .register( registry );
        Gauge.builder( PFX + ".bootstrap.chunks_total", bootstrap, b -> b.progress().chunksTotal() )
            .description( "Planned chunk count for the current bootstrap run" )
            .register( registry );
    }

    private static double encodeBootstrap( final BootstrapEmbeddingIndexer bootstrap ) {
        final BootstrapEmbeddingIndexer.State s = bootstrap.progress().state();
        return switch( s ) {
            case IDLE                      -> 0d;
            case SKIPPED_ALREADY_POPULATED -> 1d;
            case SKIPPED_NO_CHUNKS         -> 2d;
            case RUNNING                   -> 3d;
            case COMPLETED                 -> 4d;
            case FAILED                    -> 5d;
        };
    }
}
