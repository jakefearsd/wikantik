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
import com.wikantik.api.observability.MeterRegistryHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes Prometheus metrics describing retrieval-quality runs:
 *
 * <ul>
 *   <li>{@code wikantik_retrieval_ndcg_at_5{set,mode}}</li>
 *   <li>{@code wikantik_retrieval_ndcg_at_10{set,mode}}</li>
 *   <li>{@code wikantik_retrieval_recall_at_20{set,mode}}</li>
 *   <li>{@code wikantik_retrieval_mrr{set,mode}}</li>
 *   <li>{@code wikantik_retrieval_run_duration_seconds} (timer)</li>
 *   <li>{@code wikantik_retrieval_run_failed_total} (counter)</li>
 * </ul>
 *
 * <p>Gauges are keyed by {@code (set,mode)} and lazily registered on first
 * record. Null aggregate metrics surface as {@code NaN} on the gauge so the
 * scrape preserves the "not scoreable" signal.</p>
 */
public final class RetrievalQualityMetrics {

    private static final Logger LOG = LogManager.getLogger( RetrievalQualityMetrics.class );

    private static final String NDCG_5    = "wikantik_retrieval_ndcg_at_5";
    private static final String NDCG_10   = "wikantik_retrieval_ndcg_at_10";
    private static final String RECALL_20 = "wikantik_retrieval_recall_at_20";
    private static final String MRR       = "wikantik_retrieval_mrr";
    private static final String DURATION  = "wikantik_retrieval_run_duration_seconds";
    private static final String FAILED    = "wikantik_retrieval_run_failed_total";

    private MeterRegistry registry;
    private Timer durationTimer;
    private Counter failedCounter;

    /** (metricName, set, mode) -> mutable holder publishing the latest value. */
    private final Map< String, AtomicReference< Double > > gauges = new ConcurrentHashMap<>();

    public void bind( final MeterRegistry registry ) {
        if ( registry == null ) return;
        this.registry      = registry;
        this.durationTimer = Timer.builder( DURATION ).register( registry );
        this.failedCounter = Counter.builder( FAILED ).register( registry );
    }

    /** Update gauges for one run's aggregate scores. */
    public void recordRun( final RetrievalRunResult result ) {
        if ( result == null ) return;
        setGauge( NDCG_5,    result.querySetId(), result.mode(), result.ndcgAt5() );
        setGauge( NDCG_10,   result.querySetId(), result.mode(), result.ndcgAt10() );
        setGauge( RECALL_20, result.querySetId(), result.mode(), result.recallAt20() );
        setGauge( MRR,       result.querySetId(), result.mode(), result.mrr() );
    }

    /** Record the wall-clock duration of a run for the timer histogram. */
    public void recordDuration( final Duration d ) {
        if ( durationTimer != null && d != null ) {
            durationTimer.record( d );
        }
    }

    /** Increment the failed-run counter; safe when no registry is bound. */
    public void recordFailure() {
        if ( failedCounter != null ) {
            failedCounter.increment();
        }
    }

    private void setGauge( final String name, final String set, final RetrievalMode mode, final Double value ) {
        if ( registry == null ) return;
        final String key = name + "|" + set + "|" + mode.wireName();
        final AtomicReference< Double > ref = gauges.computeIfAbsent( key, k -> {
            final AtomicReference< Double > holder = new AtomicReference<>( null );
            Gauge.builder( name, holder, h -> {
                final Double v = h.get();
                return v == null ? Double.NaN : v;
            } ).tags( Tags.of( "set", set, "mode", mode.wireName() ) ).register( registry );
            return holder;
        } );
        ref.set( value );
    }

    public static RetrievalQualityMetrics resolveAndBind() {
        final RetrievalQualityMetrics m = new RetrievalQualityMetrics();
        final MeterRegistry r = MeterRegistryHolder.get();
        if ( r == null ) {
            LOG.warn( "No shared MeterRegistry — retrieval-quality metrics will NOT be scraped." );
        } else {
            m.bind( r );
        }
        return m;
    }
}
