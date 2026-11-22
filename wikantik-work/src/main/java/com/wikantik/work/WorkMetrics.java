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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Metrics for work management: {@code wikantik_work_items{status,level,kind}} and
 * {@code wikantik_work_denied_total{action}} and {@code wikantik_work_gates_total{gate,outcome}}. Deliberately no project, product or item label, so cardinality is
 * bounded by the three enums.
 *
 * <p>The gauges are read on scrape. One scrape reads all of them, so the grouped count and the active-claim count are
 * cached briefly (5 s) and shared; a failing read makes its gauges read zero until the next refresh, and is warned
 * once per outage (with the stack), not on every scrape.</p>
 */
public final class WorkMetrics implements WorkListener {

    private static final Logger LOG = LogManager.getLogger( WorkMetrics.class );

    static final String ITEMS = "wikantik_work_items";
    static final String CLAIMS = "wikantik_work_claims_total";
    static final String CLAIMS_ACTIVE = "wikantik_work_claims_active";
    static final String DENIED = "wikantik_work_denied_total";
    static final String GATES = "wikantik_work_gates_total";
    static final String NOTIFICATIONS = "wikantik_work_notifications_total";
    static final String DEPLOYMENTS = "wikantik_work_deployments_total";
    static final String CODEHOST_EVENTS = "wikantik_work_codehost_events_total";
    private static final long DEFAULT_TTL_NANOS = 5_000_000_000L;

    private record Key( WorkStatus status, WorkLevel level, WorkKind kind ) {
    }

    private final MeterRegistry registry;
    private final WorkStore store;
    private final LongSupplier nanoClock;
    private final long ttlNanos;

    /** One scrape's reads, shared by every gauge until the TTL lapses. */
    private record Snapshot( Map< Key, Long > items, long activeClaims ) {
    }

    private Snapshot snapshot = new Snapshot( Map.of(), 0L );
    private long loadedAt;
    private boolean loaded;
    /** The reads failing right now: each is warned once when it starts failing, not on every scrape. */
    private final Set< String > failing = new HashSet<>();

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

    /** Counts a deployment reaching a state; labelled by state only, never by product. */
    @Override
    public void deployment( final String state ) {
        Counter.builder( DEPLOYMENTS )
            .description( "Deployments by the state they reached" )
            .tag( "state", state )
            .register( registry )
            .increment();
    }

    /** Counts an agent's passage of, or refusal at, a gate; labelled by gate and outcome only. */
    @Override
    public void gate( final String gate, final boolean allowed ) {
        Counter.builder( GATES )
            .description( "Autonomy gates agents met, by gate and outcome" )
            .tag( "gate", gate )
            .tag( "outcome", allowed ? "allowed" : "refused" )
            .register( registry )
            .increment();
    }

    /** Counts notifications by channel and outcome only, never by project or item. */
    @Override
    public void notified( final String channel, final String outcome ) {
        Counter.builder( NOTIFICATIONS )
            .description( "Notifications by channel and outcome" )
            .tag( "channel", channel )
            .tag( "outcome", outcome )
            .register( registry )
            .increment();
    }

    /** Counts code-host events by kind and outcome only, never by product, project or item. */
    @Override
    public void codeHost( final String kind, final String outcome ) {
        Counter.builder( CODEHOST_EVENTS )
            .description( "Code-host events by kind and outcome" )
            .tag( "kind", kind )
            .tag( "outcome", outcome )
            .register( registry )
            .increment();
    }

    private double activeClaims() {
        return current().activeClaims();
    }

    private double countOf( final Key key ) {
        return current().items().getOrDefault( key, 0L );
    }

    private synchronized Snapshot current() {
        final long now = nanoClock.getAsLong();
        if ( !loaded || now - loadedAt >= ttlNanos ) {
            snapshot = new Snapshot( loadItems(), loadActiveClaims() );
            loadedAt = now;
            loaded = true;
        }
        return snapshot;
    }

    private Map< Key, Long > loadItems() {
        try {
            final List< WorkStore.ItemCount > counts = store.itemCounts();
            final Map< Key, Long > out = new HashMap<>();
            for ( final WorkStore.ItemCount c : counts ) {
                out.put( new Key( c.status(), c.level(), c.kind() ), c.count() );
            }
            recovered( ITEMS );
            return out;
        } catch ( final SQLException | RuntimeException e ) {
            failed( ITEMS, "work item counts", e );
            return Map.of();
        }
    }

    private long loadActiveClaims() {
        try {
            final long n = store.claims().activeClaimCount();
            recovered( CLAIMS_ACTIVE );
            return n;
        } catch ( final SQLException | RuntimeException e ) {
            failed( CLAIMS_ACTIVE, "the active claim count", e );
            return 0L;
        }
    }

    /** WARN with the stack when a read starts failing; quiet (DEBUG) while the outage lasts. */
    private void failed( final String read, final String what, final Exception e ) {
        if ( failing.add( read ) ) {
            LOG.warn( "Could not read {} for metrics; reporting zero until it recovers: {}", what, e.getMessage(), e );
        } else {
            LOG.debug( "Still cannot read {} for metrics: {}", what, e.getMessage() );
        }
    }

    private void recovered( final String read ) {
        if ( failing.remove( read ) ) {
            LOG.info( "Metrics read {} recovered", read );
        }
    }
}
