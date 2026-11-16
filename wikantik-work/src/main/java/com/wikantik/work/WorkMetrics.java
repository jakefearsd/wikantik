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
package com.wikantik.work;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Metrics for work management: {@code wikantik_work_items{status,level,kind}} and
 * {@code wikantik_work_denied_total{action}}. Deliberately no project, product or item label, so cardinality is
 * bounded by the three enums.
 *
 * <p>The gauges are read on scrape. One scrape reads all of them, so the grouped count is cached briefly and shared;
 * a failing query is logged and every gauge reads zero until the next refresh.</p>
 */
public final class WorkMetrics implements WorkListener {

    private static final Logger LOG = LogManager.getLogger( WorkMetrics.class );

    static final String ITEMS = "wikantik_work_items";
    static final String CLAIMS = "wikantik_work_claims_total";
    static final String CLAIMS_ACTIVE = "wikantik_work_claims_active";
    static final String DENIED = "wikantik_work_denied_total";
    private static final long DEFAULT_TTL_NANOS = 5_000_000_000L;

    private record Key( WorkStatus status, WorkLevel level, WorkKind kind ) {
    }

    private final MeterRegistry registry;
    private final WorkStore store;
    private final LongSupplier nanoClock;
    private final long ttlNanos;

    private Map< Key, Long > snapshot = Map.of();
    private long loadedAt;
    private boolean loaded;

    public WorkMetrics( final MeterRegistry registry, final WorkStore store ) {
        this( registry, store, System::nanoTime, DEFAULT_TTL_NANOS );
    }

    WorkMetrics( final MeterRegistry registry, final WorkStore store, final LongSupplier nanoClock,
                 final long ttlNanos ) {
        this.registry = registry;
        this.store = store;
        this.nanoClock = nanoClock;
        this.ttlNanos = ttlNanos;
        for ( final WorkStatus status : WorkStatus.values() ) {
            for ( final WorkLevel level : WorkLevel.values() ) {
                for ( final WorkKind kind : WorkKind.values() ) {
                    final Key key = new Key( status, level, kind );
                    Gauge.builder( ITEMS, this, m -> m.countOf( key ) )
                        .strongReference( true )
                        .description( "Work items by status, level and kind" )
                        .tag( "status", status.wire() )
                        .tag( "level", level.wire() )
                        .tag( "kind", kind.wire() )
                        .register( registry );
                }
            }
        }
        Gauge.builder( CLAIMS_ACTIVE, this, WorkMetrics::activeClaims )
            .strongReference( true )
            .description( "Work item claims currently held by agents" )
            .register( registry );
    }

    /** Records that the service refused an action. */
    @Override
    public void denied( final Action action ) {
        Counter.builder( DENIED )
            .description( "Work management actions refused by policy" )
            .tag( "action", action.name().toLowerCase( Locale.ROOT ) )
            .register( registry )
            .increment();
    }

    /** Counts a claim attempt or ending, labelled by outcome only. */
    @Override
    public void claim( final ClaimOutcome outcome ) {
        Counter.builder( CLAIMS )
            .description( "Work claim attempts and endings by outcome" )
            .tag( "outcome", outcome.wire() )
            .register( registry )
            .increment();
    }

    private double activeClaims() {
        try {
            return store.claims().activeClaimCount();
        } catch ( final SQLException | RuntimeException e ) {
            LOG.warn( "Could not read the active claim count for metrics; reporting zero: {}", e.getMessage(), e );
            return 0.0;
        }
    }

    private double countOf( final Key key ) {
        return current().getOrDefault( key, 0L );
    }

    private synchronized Map< Key, Long > current() {
        final long now = nanoClock.getAsLong();
        if ( !loaded || now - loadedAt >= ttlNanos ) {
            snapshot = load();
            loadedAt = now;
            loaded = true;
        }
        return snapshot;
    }

    private Map< Key, Long > load() {
        try {
            final List< WorkStore.ItemCount > counts = store.itemCounts();
            final Map< Key, Long > out = new HashMap<>();
            for ( final WorkStore.ItemCount c : counts ) {
                out.put( new Key( c.status(), c.level(), c.kind() ), c.count() );
            }
            return out;
        } catch ( final SQLException | RuntimeException e ) {
            LOG.warn( "Could not read work item counts for metrics; reporting zero: {}", e.getMessage(), e );
            return Map.of();
        }
    }
}
