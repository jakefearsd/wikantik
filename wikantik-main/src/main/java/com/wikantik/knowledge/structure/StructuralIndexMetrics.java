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
package com.wikantik.knowledge.structure;

import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.api.structure.IndexHealth;
import com.wikantik.api.structure.StructuralIndexService.StructuralProjectionSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes Prometheus metrics describing the structural index:
 * <ul>
 *   <li>{@code wikantik_structural_index_pages_total}</li>
 *   <li>{@code wikantik_structural_index_clusters_total}</li>
 *   <li>{@code wikantik_structural_index_tags_total}</li>
 *   <li>{@code wikantik_structural_index_unclaimed_total}</li>
 *   <li>{@code wikantik_structural_index_lag_seconds}</li>
 *   <li>{@code wikantik_structural_index_rebuild_duration_seconds} (timer)</li>
 * </ul>
 * When no shared {@link MeterRegistry} is installed (e.g. test harnesses without
 * the observability module), the holder is a no-op — update and recordRebuildMillis
 * still work, they just don't register gauges or persist values to a registry.
 */
public class StructuralIndexMetrics {

    private static final Logger LOG = LogManager.getLogger( StructuralIndexMetrics.class );

    private final AtomicLong pages      = new AtomicLong( 0 );
    private final AtomicLong clusters   = new AtomicLong( 0 );
    private final AtomicLong tags       = new AtomicLong( 0 );
    private final AtomicLong unclaimed  = new AtomicLong( 0 );
    private final AtomicLong lagSeconds = new AtomicLong( 0 );

    private Timer rebuildTimer;

    public void bind( final MeterRegistry registry ) {
        if ( registry == null ) return;
        Gauge.builder( "wikantik_structural_index_pages_total",     pages,     AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_clusters_total",  clusters,  AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_tags_total",      tags,      AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_unclaimed_total", unclaimed, AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_lag_seconds",     lagSeconds, AtomicLong::get ).register( registry );
        this.rebuildTimer = Timer.builder( "wikantik_structural_index_rebuild_duration_seconds" )
                .register( registry );
    }

    public void update( final StructuralProjectionSnapshot snapshot, final IndexHealth health ) {
        pages.set( snapshot.pageCount() );
        clusters.set( snapshot.clusterCount() );
        tags.set( snapshot.tagCount() );
        unclaimed.set( health.unclaimedCanonicalIds() );
        lagSeconds.set( health.lagSeconds() );
    }

    public void recordRebuildMillis( final long ms ) {
        if ( rebuildTimer != null ) {
            rebuildTimer.record( Duration.ofMillis( ms ) );
        }
    }

    public static StructuralIndexMetrics resolveAndBind() {
        final StructuralIndexMetrics m = new StructuralIndexMetrics();
        final MeterRegistry registry = MeterRegistryHolder.get();
        if ( registry == null ) {
            LOG.warn( "No shared MeterRegistry — structural-index metrics will NOT be scraped." );
        } else {
            m.bind( registry );
        }
        return m;
    }
}
