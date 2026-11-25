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

import com.wikantik.analytics.AnalyticsSettings;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.insights.JdbcInsightsStore;
import com.wikantik.insights.OpportunityEngine;
import com.wikantik.insights.readers.JdbcReaderRollupStore;
import com.wikantik.insights.readers.JdbcUmamiViews;
import com.wikantik.insights.readers.ReaderAnalyticsReadback;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Properties;
import java.util.UUID;

/**
 * Builds the {@link ContentOpportunityService} at startup, following the same
 * build-once-return-null-when-off pattern as
 * {@code com.wikantik.knowledge.querylog.QueryLogWiring} -- see {@code WikiEngine#initDataSourceSubsystems}
 * for the call site (content-intelligence design §12).
 */
public final class InsightsWiring {

    private static final Logger LOG = LogManager.getLogger( InsightsWiring.class );

    private InsightsWiring() {}

    /**
     * @param ds             the persistence datasource; {@code null} means no-op (returns
     *                       {@code null})
     * @param props          the wiki's merged configuration properties, read via
     *                       {@link InsightsSettings#from(Properties)}
     * @param structuralIndex the live structural index backing {@link WikiPageFacts}; {@code null}
     *                       means no-op (returns {@code null}) -- there is no page-state port to
     *                       build the service with
     * @return a wired {@link ContentOpportunityService}, or {@code null} when {@code ds} is
     *         {@code null}, {@code structuralIndex} is {@code null}, or
     *         {@code wikantik.insights.enabled} is {@code false} -- callers must no-op on
     *         {@code null}, matching {@code queryLogReader}'s contract
     */
    public static ContentOpportunityService build( final DataSource ds, final Properties props,
                                                    final StructuralIndexService structuralIndex,
                                                    final InsightsBackground background ) {
        if ( ds == null ) {
            return null;
        }
        final InsightsSettings settings = InsightsSettings.from( props );
        if ( !settings.enabled() ) {
            return null;
        }
        if ( structuralIndex == null ) {
            LOG.warn( "Content-opportunity engine enabled but no structural index is available -- "
                    + "leaving it unwired" );
            return null;
        }

        final JdbcInsightsStore store = new JdbcInsightsStore( ds );
        final WikiPageFacts pageFacts = new WikiPageFacts( structuralIndex );
        final OpportunityEngine engine = new OpportunityEngine();

        startEffectEvaluation( store, settings, background );

        return new ContentOpportunityService( store, pageFacts, engine, settings );
    }

    /**
     * Starts the nightly effect-measurement pass (design §7.4.2).
     *
     * <p>The scheduler is handed to {@code background}, which the engine closes at shutdown so the
     * thread does not outlive an engine that is stopped and restarted in the same JVM. Nothing here
     * is time-critical -- a change only becomes evaluable 28 days after it was applied -- so a
     * missed tick costs at most one day's delay on a verdict.</p>
     */
    private static void startEffectEvaluation( final JdbcInsightsStore store,
                                               final InsightsSettings settings,
                                               final InsightsBackground background ) {
        final String site = settings.ruleSites().isEmpty() ? null : settings.ruleSites().get( 0 );
        if ( site == null ) {
            LOG.warn( "No configured rules site -- effect evaluation not started" );
            return;
        }
        final com.wikantik.insights.EffectEvaluator evaluator = new com.wikantik.insights.EffectEvaluator(
                store, site, settings.effectWindowDays(), settings.effectMinBaselineImpressions(),
                EFFECT_SNAPSHOT_TOLERANCE_DAYS );
        final EffectEvaluationScheduler scheduler = new EffectEvaluationScheduler( evaluator, EFFECT_INTERVAL_HOURS );
        scheduler.start();
        background.add( scheduler );
    }

    /** What the reader read-back needs from the running engine. */
    public record ReadbackEnv( PageCanonicalIdsDao canonicalIds, String frontPage, String baseUrl,
                               MeterRegistry meters ) { }

    /**
     * Starts the Umami reader read-back when configured ({@link AnalyticsSettings#readbackEnabled()}); a
     * no-op otherwise, or when there is no engine datasource to write rollups to or the configured website id
     * is not a UUID. The rollup store writes to the engine's own {@code ds}; only the views are read from the
     * separate Umami database.
     */
    public static void startReaderReadback( final DataSource ds, final Properties props, final ReadbackEnv env,
                                            final InsightsBackground background ) {
        final AnalyticsSettings settings = AnalyticsSettings.from( props );
        if ( ds == null || !settings.readbackEnabled() ) {
            return;
        }
        final UUID websiteId;
        try {
            websiteId = UUID.fromString( settings.websiteId() );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Reader read-back not started: {} is not a UUID ('{}')", AnalyticsSettings.PROP_WEBSITE_ID,
                    settings.websiteId(), e );
            return;
        }
        final String frontPage = env.frontPage();
        final ReaderAnalyticsReadback readback = new ReaderAnalyticsReadback(
                new JdbcUmamiViews( new Jdbc( UmamiDataSourceFactory.create( settings ) ), websiteId ),
                new JdbcReaderRollupStore( new Jdbc( ds ) ),
                () -> new WikiPathResolver( env.canonicalIds(), frontPage ),
                settings.resolveSiteHost( env.baseUrl() ), settings.readbackDays(), Clock.systemUTC(),
                env.meters() );
        final ReaderAnalyticsScheduler scheduler = new ReaderAnalyticsScheduler( readback, settings.readbackHours() );
        scheduler.start();
        background.add( scheduler );
    }

    /**
     * How far the nearest snapshot may sit from the ideal after-window date. Snapshots are written
     * per collector poll rather than on a fixed calendar, so demanding an exact date match would
     * silently make every verdict {@code insufficient_data}.
     */
    private static final int EFFECT_SNAPSHOT_TOLERANCE_DAYS = 5;

    /** Nightly. The input only changes when a new snapshot lands, which is far less often. */
    private static final long EFFECT_INTERVAL_HOURS = 24;
}
