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
package com.wikantik.collab.adapter;

import com.wikantik.collab.CollabMetrics;
import com.wikantik.collab.CollabSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link CollabMetrics} over Micrometer, scraped at {@code /metrics} under docs/MultiEdit.MD section 12.3's names:
 * {@code wikantik_collab_push_total{result}}, {@code wikantik_collab_push_latency_seconds},
 * {@code wikantik_collab_external_merges_total}, {@code wikantik_collab_publish_total{result}} and
 * {@code wikantik_collab_resync_total}; {@link #bindGauges} adds {@code wikantik_collab_sessions_active} and
 * {@code wikantik_collab_participants_active}. Every labelled counter is registered up front, so each series scrapes as
 * 0 before its first event; the label sets are fixed: an unknown push result counts as {@code rejected} and an unknown
 * publish result as {@code refused}, so no caller can grow the series. The push latency publishes a percentile
 * histogram, as the other wikantik latency timers do.
 */
public final class MicrometerCollabMetrics implements CollabMetrics {

    static final String PUSH = "wikantik_collab_push_total";
    static final String PUSH_LATENCY = "wikantik_collab_push_latency_seconds";
    static final String PUBLISH = "wikantik_collab_publish_total";
    static final String EXTERNAL_MERGES = "wikantik_collab_external_merges_total";
    static final String RESYNC = "wikantik_collab_resync_total";
    static final String SESSIONS = "wikantik_collab_sessions_active";
    static final String PARTICIPANTS = "wikantik_collab_participants_active";
    private static final String RESULT = "result";

    private final MeterRegistry meters;
    private final Map< String, Counter > pushes;
    private final Map< String, Counter > publishes;
    private final Timer pushLatency;
    private final Counter externalMerges;
    private final Counter resyncs;

    public MicrometerCollabMetrics( final MeterRegistry meters ) {
        this.meters = meters;
        this.pushes = counters( PUSH, "Collaborative-editing pushes by result",
            List.of( ACCEPTED, REBASE, REJECTED ) );
        this.publishes = counters( PUBLISH, "Collaborative-editing publishes by result",
            List.of( PUBLISHED, UNCHANGED, CONFLICT, REFUSED, FAILED, STORE ) );
        this.pushLatency = Timer.builder( PUSH_LATENCY )
            .description( "Time to answer one collaborative-editing push" )
            .publishPercentileHistogram()
            .register( meters );
        this.externalMerges = Counter.builder( EXTERNAL_MERGES )
            .description( "Outside page writes merged into a collaborative draft" )
            .register( meters );
        this.resyncs = Counter.builder( RESYNC )
            .description( "Collaborative-editing pulls answered 410 resync" )
            .register( meters );
    }

    private Map< String, Counter > counters( final String name, final String description, final List< String > results ) {
        return results.stream().collect( Collectors.toUnmodifiableMap( Function.identity(),
            r -> counter( name, description, r ) ) );
    }

    private Counter counter( final String name, final String description, final String result ) {
        return Counter.builder( name ).description( description ).tag( RESULT, result ).register( meters );
    }

    /**
     * Registers the two gauges over {@code registry}: sessions in memory and participants across them. Micrometer holds
     * the registry weakly; the wiring keeps it alive.
     */
    public void bindGauges( final CollabSessionRegistry registry ) {
        Gauge.builder( SESSIONS, registry, CollabSessionRegistry::liveCount )
            .description( "Collaborative-editing sessions in memory" )
            .register( meters );
        Gauge.builder( PARTICIPANTS, registry, CollabSessionRegistry::participantCount )
            .description( "Participants across the collaborative-editing sessions in memory" )
            .register( meters );
    }

    @Override
    public void push( final String result ) {
        count( pushes, REJECTED, result );
    }

    @Override
    public void pushLatency( final long nanos ) {
        pushLatency.record( Duration.ofNanos( nanos ) );
    }

    @Override
    public void publish( final String result ) {
        count( publishes, REFUSED, result );
    }

    /** Increments {@code result}'s series, or {@code fallback}'s for a label outside the fixed set. */
    private static void count( final Map< String, Counter > known, final String fallback, final String result ) {
        final Counter c = result == null ? null : known.get( result );
        ( c != null ? c : known.get( fallback ) ).increment();
    }

    @Override
    public void externalMerge() {
        externalMerges.increment();
    }

    @Override
    public void resync() {
        resyncs.increment();
    }
}
