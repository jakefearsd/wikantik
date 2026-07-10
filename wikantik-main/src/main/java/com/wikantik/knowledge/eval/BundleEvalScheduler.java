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

import com.wikantik.api.eval.BundleEvalQuestion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodically runs the frozen bundle-eval corpus against the live bundle path, persists the
 *  result, and logs a regression alert when recall falls below a threshold floor. Disabled when
 *  {@code intervalHours <= 0}. {@link #runOnce} never throws — a failed eval must not affect the app. */
public final class BundleEvalScheduler {

    private static final Logger LOG = LogManager.getLogger( BundleEvalScheduler.class );

    private final BundleEvalRunner.BundleRetriever retriever;
    private final Path corpusCsv;
    private final BundleEvalThresholds thresholds;
    private final BundleEvalRunDao dao;
    private final String configId;
    private final int precisionK;
    private final long intervalHours;
    private ScheduledExecutorService executor;

    public BundleEvalScheduler( final BundleEvalRunner.BundleRetriever retriever, final Path corpusCsv,
                                final BundleEvalThresholds thresholds, final BundleEvalRunDao dao,
                                final String configId, final int precisionK, final long intervalHours ) {
        this.retriever = retriever;
        this.corpusCsv = corpusCsv;
        this.thresholds = thresholds;
        this.dao = dao;
        this.configId = configId;
        this.precisionK = precisionK;
        this.intervalHours = intervalHours;
    }

    /** Starts the timer (no-op when {@code intervalHours <= 0}). First run is one interval out. */
    public void start() {
        if ( intervalHours <= 0 ) {
            LOG.info( "bundle-eval scheduler disabled (interval={}h)", intervalHours );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-bundle-eval-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::runOnce, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "bundle-eval scheduler started (every {}h, precisionK={})", intervalHours, precisionK );
    }

    /** One scheduled tick: load corpus → run → check → persist → alert. Never throws. */
    void runOnce() {
        try {
            final List< BundleEvalQuestion > corpus = BundleCorpusLoader.load( corpusCsv );
            if ( corpus.isEmpty() ) {
                LOG.warn( "bundle-eval corpus empty at {}; skipping run", corpusCsv );
                return;
            }
            final BundleEvalReport report = new BundleEvalRunner( retriever, precisionK ).run( corpus );
            final BundleEvalRegressionCheck.RegressionResult res =
                BundleEvalRegressionCheck.evaluate( report, thresholds );
            dao.insert( BundleEvalRun.from( report, configId, res.regression() ) );
            if ( res.regression() ) {
                LOG.warn( "BUNDLE-EVAL REGRESSION (configId={}, questions={}): {}",
                    configId, report.questionsScored(), res.detail() );
            } else {
                LOG.info( "bundle-eval ok (configId={}, overallRecall={}, questions={})",
                    configId, String.format( "%.3f", report.overallRecall() ), report.questionsScored() );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "bundle-eval run failed: {}", e.getMessage(), e );
        }
    }

    public void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
