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
package com.wikantik.insights.runtime;

import com.wikantik.insights.EffectEvaluator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Nightly timer for {@link EffectEvaluator} (content-intelligence design section 7.4.2).
 *
 * <p>Mirrors {@code OntologyRebuildScheduler}: a single daemon thread, disabled by a non-positive
 * interval, first run one interval out rather than at startup. Startup is deliberately not a
 * trigger -- nothing here is time-critical (a change becomes evaluable 28 days after it was
 * applied, so a few hours either way is irrelevant) and a restart loop must not turn into an
 * evaluation loop.</p>
 *
 * <p>A tick never throws. Effect measurement is an offline bookkeeping pass; a failure in it must
 * never surface anywhere near a request path.</p>
 */
public final class EffectEvaluationScheduler {

    private static final Logger LOG = LogManager.getLogger( EffectEvaluationScheduler.class );

    private final EffectEvaluator evaluator;
    private final long intervalHours;
    private final Clock clock;
    private ScheduledExecutorService executor;

    public EffectEvaluationScheduler( final EffectEvaluator evaluator, final long intervalHours ) {
        this( evaluator, intervalHours, Clock.systemUTC() );
    }

    EffectEvaluationScheduler( final EffectEvaluator evaluator, final long intervalHours, final Clock clock ) {
        this.evaluator = evaluator;
        this.intervalHours = intervalHours;
        this.clock = clock;
    }

    /** Starts the timer (no-op when the evaluator is absent or intervalHours &lt;= 0). */
    public void start() {
        if ( evaluator == null || intervalHours <= 0 ) {
            LOG.info( "effect evaluation scheduler disabled (interval={}h, evaluator={})",
                    intervalHours, evaluator == null ? "absent" : "present" );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-insights-effect-evaluation" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::runOnce, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "effect evaluation scheduler started (every {}h)", intervalHours );
    }

    /** One scheduled tick. Never throws. */
    void runOnce() {
        try {
            final int evaluated = evaluator.evaluatePending( LocalDate.now( clock ) );
            if ( evaluated > 0 ) {
                LOG.info( "scheduled effect evaluation recorded {} verdict(s)", evaluated );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "scheduled effect evaluation failed: {}", e.getMessage(), e );
        }
    }

    public void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
        }
    }
}
