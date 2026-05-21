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
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaffeineCacheMetricsBridgeTest {

    @BeforeEach
    void clearDedup() {
        CaffeineCacheMetricsBridge.resetForTest();
    }

    @Test
    void register_publishesSizeGauge() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final Cache< String, String > cache = Caffeine.newBuilder()
            .maximumSize( 100 )
            .recordStats()
            .build();
        cache.put( "k1", "v1" );
        cache.put( "k2", "v2" );

        CaffeineCacheMetricsBridge.register( reg, "test", cache );

        final Gauge g = reg.find( "wikantik_cache.size" ).tag( "cache", "test" ).gauge();
        assertNotNull( g, "size gauge should be registered with cache=test tag" );
        assertEquals( 2.0, g.value(), 0.0001, "estimatedSize should match cache contents" );
    }

    @Test
    void register_publishesHitMissCounters_andCountsRiseOnAccess() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final Cache< String, String > cache = Caffeine.newBuilder()
            .maximumSize( 100 )
            .recordStats()
            .build();
        cache.put( "k1", "v1" );

        CaffeineCacheMetricsBridge.register( reg, "test", cache );

        // Drive a hit and a miss
        assertNotNull( cache.getIfPresent( "k1" ), "hit" );
        assertNull( cache.getIfPresent( "k2" ), "miss" );

        final FunctionCounter hits   = reg.find( "wikantik_cache.hits"   ).tag( "cache", "test" ).functionCounter();
        final FunctionCounter misses = reg.find( "wikantik_cache.misses" ).tag( "cache", "test" ).functionCounter();
        assertNotNull( hits );
        assertNotNull( misses );
        assertEquals( 1.0, hits.count(),   0.0001 );
        assertEquals( 1.0, misses.count(), 0.0001 );
    }

    @Test
    void register_publishesEvictionsCounter() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final Cache< String, String > cache = Caffeine.newBuilder()
            .maximumSize( 2 )
            .recordStats()
            .build();
        // Force evictions: capacity=2, insert 4 entries
        cache.put( "k1", "v1" );
        cache.put( "k2", "v2" );
        cache.put( "k3", "v3" );
        cache.put( "k4", "v4" );
        cache.cleanUp(); // force the eviction policy to run

        CaffeineCacheMetricsBridge.register( reg, "test", cache );

        final FunctionCounter evic = reg.find( "wikantik_cache.evictions" ).tag( "cache", "test" ).functionCounter();
        assertNotNull( evic );
        // At least 2 entries must have been evicted to fit the size cap.
        assertEquals( true, evic.count() >= 2.0, "evictions should be >= 2, was " + evic.count() );
    }

    @Test
    void register_isIdempotent_secondCallNoop() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final Cache< String, String > cache = Caffeine.newBuilder()
            .maximumSize( 100 )
            .recordStats()
            .build();

        CaffeineCacheMetricsBridge.register( reg, "test", cache );
        CaffeineCacheMetricsBridge.register( reg, "test", cache );

        // Counting meters with this name should be exactly 4: size + hits + misses + evictions.
        final long count = reg.getMeters().stream()
            .filter( m -> m.getId().getName().startsWith( "wikantik_cache." ) )
            .filter( m -> "test".equals( m.getId().getTag( "cache" ) ) )
            .count();
        assertEquals( 4L, count, "re-registration must not duplicate meters" );
    }

    @Test
    void register_differentCacheNames_publishedIndependently() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final Cache< String, String > a = Caffeine.newBuilder().maximumSize( 10 ).recordStats().build();
        final Cache< String, String > b = Caffeine.newBuilder().maximumSize( 10 ).recordStats().build();
        a.put( "x", "y" );

        CaffeineCacheMetricsBridge.register( reg, "alpha", a );
        CaffeineCacheMetricsBridge.register( reg, "beta",  b );

        assertEquals( 1.0,
            reg.find( "wikantik_cache.size" ).tag( "cache", "alpha" ).gauge().value(), 0.0001 );
        assertEquals( 0.0,
            reg.find( "wikantik_cache.size" ).tag( "cache", "beta"  ).gauge().value(), 0.0001 );
    }

    @Test
    void register_nullArgsAreNoop() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final Cache< String, String > cache = Caffeine.newBuilder().build();
        CaffeineCacheMetricsBridge.register( null, "test", cache );
        CaffeineCacheMetricsBridge.register( reg, null, cache );
        CaffeineCacheMetricsBridge.register( reg, "test", null );
        CaffeineCacheMetricsBridge.register( reg, "", cache );
        assertEquals( 0, reg.getMeters().size(), "no meters should be registered when any arg is missing" );
    }
}
