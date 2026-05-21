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
package com.wikantik.observability;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Publishes Caffeine cache stats to Micrometer / Prometheus uniformly across
 * the application. One call to {@link #register(MeterRegistry, String, Cache)}
 * per cache exposes four meters tagged with the cache name:
 *
 * <ul>
 *   <li>{@code wikantik_cache_size{cache=<name>}} — gauge of
 *       {@link Cache#estimatedSize()} (current element count)</li>
 *   <li>{@code wikantik_cache_hits_total{cache=<name>}} — monotonic counter
 *       from {@code stats().hitCount()}</li>
 *   <li>{@code wikantik_cache_misses_total{cache=<name>}} — monotonic counter
 *       from {@code stats().missCount()}</li>
 *   <li>{@code wikantik_cache_evictions_total{cache=<name>}} — monotonic
 *       counter from {@code stats().evictionCount()}</li>
 * </ul>
 *
 * <p>Hit rate is derived in Prometheus / Grafana from the two counters:
 * {@code rate(hits_total[5m]) / (rate(hits_total[5m]) + rate(misses_total[5m]))}.
 * That avoids both a redundant gauge and the off-by-one rounding that comes
 * with publishing pre-computed ratios.</p>
 *
 * <p>The cache MUST have been built with {@code Caffeine.recordStats()}
 * — otherwise {@code stats()} returns zeros and the counters will be flat.
 * This is checked by inspecting the snapshot at register time; if stats are
 * disabled, the registration is logged at WARN and the gauge for
 * {@code estimatedSize} is still wired (it works regardless).</p>
 *
 * <p>Re-registration of the same cache name is a no-op — first registration
 * wins. This is mostly defensive against double-wiring during engine
 * re-init in tests; production wires each cache exactly once.</p>
 */
public final class CaffeineCacheMetricsBridge {

    private static final Logger LOG = LogManager.getLogger( CaffeineCacheMetricsBridge.class );

    /** Common prefix matching {@code HybridMetricsBridge}'s {@code wikantik_search.*}. */
    private static final String SIZE_NAME      = "wikantik_cache.size";
    private static final String HITS_NAME      = "wikantik_cache.hits";
    private static final String MISSES_NAME    = "wikantik_cache.misses";
    private static final String EVICTIONS_NAME = "wikantik_cache.evictions";

    /** Cache names already registered, by registry identity, to dedup. */
    private static final ConcurrentMap< MeterRegistry, ConcurrentMap< String, Boolean > > REGISTERED =
        new ConcurrentHashMap<>();

    private CaffeineCacheMetricsBridge() {}

    /**
     * Register one Caffeine {@link Cache} with the given short {@code name}.
     * {@code name} becomes the {@code cache=} label on all four meters; pick
     * a short snake_case identifier (e.g. {@code "chunk_text"}, not
     * {@code "com.wikantik.knowledge.chunking.ContentChunkRepository#chunkCache"}).
     */
    public static void register( final MeterRegistry registry,
                                  final String name,
                                  final Cache< ?, ? > cache ) {
        if ( registry == null || cache == null || name == null || name.isBlank() ) {
            LOG.warn( "CaffeineCacheMetricsBridge.register skipped: registry={}, cache={}, name={}",
                registry, cache, name );
            return;
        }
        final ConcurrentMap< String, Boolean > seen =
            REGISTERED.computeIfAbsent( registry, r -> new ConcurrentHashMap<>() );
        if ( seen.putIfAbsent( name, Boolean.TRUE ) != null ) {
            // Already registered — silently no-op. Used to be a hard error,
            // but tests that re-init the engine legitimately re-register.
            return;
        }

        Gauge.builder( SIZE_NAME, cache, c -> (double) c.estimatedSize() )
            .tag( "cache", name )
            .description( "Current Caffeine cache element count for " + name )
            .register( registry );

        // stats().hitCount() etc. return long monotonic counters when the
        // cache was built with .recordStats(). On a cache without stats they
        // return 0 and the FunctionCounter just stays at 0 forever — visible
        // in the dashboard as a flat line, which is the right signal.
        FunctionCounter.builder( HITS_NAME, cache, c -> (double) c.stats().hitCount() )
            .tag( "cache", name )
            .description( "Caffeine cache hits since process start for " + name )
            .register( registry );

        FunctionCounter.builder( MISSES_NAME, cache, c -> (double) c.stats().missCount() )
            .tag( "cache", name )
            .description( "Caffeine cache misses since process start for " + name )
            .register( registry );

        FunctionCounter.builder( EVICTIONS_NAME, cache, c -> (double) c.stats().evictionCount() )
            .tag( "cache", name )
            .description( "Caffeine cache evictions since process start for " + name )
            .register( registry );

        // One-line confirmation log so operators can tell at startup which
        // caches are publishing metrics. WARN if the cache was built without
        // recordStats — the gauge will work but hits/misses will be flat.
        final boolean statsEnabled = cache.policy().isRecordingStats();
        if ( statsEnabled ) {
            LOG.info( "CaffeineCacheMetricsBridge: registered cache='{}' (size + hits + misses + evictions)", name );
        } else {
            LOG.warn( "CaffeineCacheMetricsBridge: cache='{}' was NOT built with .recordStats() — "
                + "hits/misses/evictions counters will remain flat. Fix by adding .recordStats() "
                + "in the Caffeine.newBuilder() call.", name );
        }
    }

    /** Test-only escape hatch to drop the cross-registration dedup map. */
    static void resetForTest() {
        REGISTERED.clear();
    }
}
