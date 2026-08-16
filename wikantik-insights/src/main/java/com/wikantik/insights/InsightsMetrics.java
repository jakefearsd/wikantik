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
package com.wikantik.insights;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded-cardinality ingest health metrics.
 *
 * <p>LOAD-BEARING RULE (spec D2): no metric emitted here may carry a page slug, query string,
 * or session id as a label. Only {@code engine} and {@code outcome} are permitted, and both are
 * enumerable — three engines, a handful of outcomes. Per-entity facts live in SQL, not in the
 * time-series database. {@code InsightsMetricsCardinalityTest} enforces this.</p>
 */
public class InsightsMetrics {

    static final String ROWS_METRIC = "wikantik.insights.ingest.rows";
    static final String LAST_SUCCESS_METRIC = "wikantik.insights.ingest.last_success_timestamp";

    private static final String OUTCOME_OK = "ok";

    private final MeterRegistry registry;

    /** Strong references to the gauge-backing values, keyed by engine.
     *
     *  <p>Two Micrometer behaviours make this map necessary rather than decorative, and both
     *  silently break the staleness alert this gauge exists to drive:</p>
     *  <ol>
     *    <li>{@code registry.gauge( name, tags, number )} binds its value at <em>first</em>
     *        registration. Calling it again with the same name and tags returns the existing
     *        gauge without rebinding, so a freshly-computed timestamp would be discarded and the
     *        gauge would report the first success forever.</li>
     *    <li>The registry holds only a <em>weak</em> reference to the state object. An autoboxed
     *        {@code Double} with no other referent becomes collectable immediately, after which
     *        the gauge reports {@code NaN}.</li>
     *  </ol>
     *  Holding a mutable {@link AtomicLong} strongly here and mutating it in place avoids both. */
    private final Map< String, AtomicLong > lastSuccessByEngine = new ConcurrentHashMap<>();

    public InsightsMetrics( final MeterRegistry registry ) {
        this.registry = registry;
    }

    /**
     * Record the outcome of one snapshot ingest.
     *
     * @param engine  search engine the snapshot came from ({@code google}, {@code bing}, {@code yandex})
     * @param outcome {@code ok} or {@code error}
     * @param rows    rows written; ignored for a failed ingest
     */
    public void recordIngest( final String engine, final String outcome, final int rows ) {
        Counter.builder( ROWS_METRIC )
               .tag( "engine", engine )
               .tag( "outcome", outcome )
               .register( registry )
               .increment( rows );

        if ( OUTCOME_OK.equals( outcome ) ) {
            recordSuccessTimestamp( engine, System.currentTimeMillis() / 1000L );
        }
    }

    /** Package-private so tests can pin the clock instead of racing wall time. */
    void recordSuccessTimestamp( final String engine, final long epochSeconds ) {
        lastSuccessByEngine.computeIfAbsent( engine, key -> {
            final AtomicLong holder = new AtomicLong();
            Gauge.builder( LAST_SUCCESS_METRIC, holder, AtomicLong::doubleValue )
                 .tags( List.of( Tag.of( "engine", key ) ) )
                 .description( "Epoch seconds of the last successful visibility ingest. "
                             + "Staleness on this gauge is the alert condition, not error count." )
                 .register( registry );
            return holder;
        } ).set( epochSeconds );
    }
}
