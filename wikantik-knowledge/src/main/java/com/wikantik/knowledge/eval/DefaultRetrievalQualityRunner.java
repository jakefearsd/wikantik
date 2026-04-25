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
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.eval.RetrievalRunResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link RetrievalQualityRunner} — pulls a curated query set from the
 * DAO, executes each query through the requested {@link RetrievalMode} via the
 * injected {@link Retriever}, maps result page names to canonical_ids via the
 * injected {@link CanonicalIdResolver}, computes per-query nDCG@5 / @10 /
 * Recall@20 / MRR via {@link RetrievalMetricsCalculator}, persists the
 * aggregate row and updates Prometheus gauges.
 *
 * <p>The runner depends on narrow functional seams so unit tests can drive it
 * without a real search stack or database. Production wiring lives in
 * {@code WikiEngine.initKnowledgeGraph}.</p>
 */
public final class DefaultRetrievalQualityRunner implements RetrievalQualityRunner, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( DefaultRetrievalQualityRunner.class );

    /** Run a single query through one mode and return ordered page-names. */
    @FunctionalInterface
    public interface Retriever {
        List< String > retrieve( RetrievalMode mode, String query );
    }

    /** Map a page slug / page-name to its canonical_id (empty when absent). */
    @FunctionalInterface
    public interface CanonicalIdResolver {
        Optional< String > canonicalIdForSlug( String slug );
    }

    private final RetrievalQualityDao dao;
    private final Retriever retriever;
    private final CanonicalIdResolver resolver;
    private final RetrievalQualityMetrics metrics;
    private final int nightlyHourUtc;

    private ScheduledExecutorService scheduler;
    private boolean scheduleStarted;

    public DefaultRetrievalQualityRunner( final RetrievalQualityDao dao,
                                          final Retriever retriever,
                                          final CanonicalIdResolver resolver,
                                          final RetrievalQualityMetrics metrics ) {
        this( dao, retriever, resolver, metrics, 3 );
    }

    public DefaultRetrievalQualityRunner( final RetrievalQualityDao dao,
                                          final Retriever retriever,
                                          final CanonicalIdResolver resolver,
                                          final RetrievalQualityMetrics metrics,
                                          final int nightlyHourUtc ) {
        if ( dao == null ) throw new IllegalArgumentException( "dao must not be null" );
        if ( retriever == null ) throw new IllegalArgumentException( "retriever must not be null" );
        if ( resolver == null ) throw new IllegalArgumentException( "resolver must not be null" );
        if ( metrics == null ) throw new IllegalArgumentException( "metrics must not be null" );
        if ( nightlyHourUtc < 0 || nightlyHourUtc > 23 ) {
            throw new IllegalArgumentException( "nightlyHourUtc must be 0..23" );
        }
        this.dao = dao;
        this.retriever = retriever;
        this.resolver = resolver;
        this.metrics = metrics;
        this.nightlyHourUtc = nightlyHourUtc;
    }

    @Override
    public synchronized void scheduleNightly() {
        if ( scheduleStarted ) {
            LOG.debug( "scheduleNightly() called twice; skipping" );
            return;
        }
        if ( scheduler == null ) {
            scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
                final Thread t = new Thread( r, "retrieval-quality-runner" );
                t.setDaemon( true );
                return t;
            } );
        }
        final long initialDelaySec = secondsUntilNextRun( Instant.now() );
        scheduler.scheduleAtFixedRate( this::runAllForNightly,
            initialDelaySec, TimeUnit.DAYS.toSeconds( 1 ), TimeUnit.SECONDS );
        scheduleStarted = true;
        LOG.info( "Retrieval-quality nightly scheduled in {}s (every 24h, hour={}Z)",
            initialDelaySec, nightlyHourUtc );
    }

    /**
     * Body of the nightly schedule — runs every (set, mode) pair sequentially.
     * Visible for tests so the body can be exercised without sleeping until
     * 03:00 UTC.
     */
    void runAllForNightly() {
        try {
            // Stub set list — Phase 5 ships only one curated set, "core-agent-queries".
            // A future migration that adds more sets only needs to tag them as
            // nightly-scheduled here (or extend the DAO with a flag).
            final String[] sets = { "core-agent-queries" };
            for ( final String setId : sets ) {
                if ( !dao.querySetExists( setId ) ) {
                    LOG.debug( "Nightly: query set '{}' not present; skipping", setId );
                    continue;
                }
                for ( final RetrievalMode mode : RetrievalMode.values() ) {
                    try {
                        runNow( setId, mode );
                    } catch ( final RuntimeException e ) {
                        LOG.warn( "Nightly run for set={}, mode={} failed: {}",
                            setId, mode.wireName(), e.getMessage(), e );
                        metrics.recordFailure();
                    }
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Nightly retrieval-quality run failed: {}", e.getMessage(), e );
            metrics.recordFailure();
        }
    }

    @Override
    public RetrievalRunResult runNow( final String querySetId, final RetrievalMode mode ) {
        if ( querySetId == null || querySetId.isBlank() ) {
            throw new IllegalArgumentException( "querySetId must not be blank" );
        }
        if ( mode == null ) {
            throw new IllegalArgumentException( "mode must not be null" );
        }
        if ( !dao.querySetExists( querySetId ) ) {
            throw new IllegalArgumentException( "Unknown query_set_id: " + querySetId );
        }
        final Instant started = Instant.now();
        final List< RetrievalQualityDao.EvalQuery > queries = dao.loadQueries( querySetId );
        if ( queries.isEmpty() ) {
            LOG.warn( "runNow({},{}) — query set has no queries", querySetId, mode.wireName() );
        }

        final List< RetrievalMetricsCalculator.QueryScore > perQuery = new ArrayList<>( queries.size() );
        int evaluated = 0;
        int skipped = 0;
        boolean degraded = false;

        for ( final RetrievalQualityDao.EvalQuery q : queries ) {
            if ( q.expectedIds() == null || q.expectedIds().isEmpty() ) {
                skipped++;
                LOG.debug( "Skipping query '{}' — empty expected_ids (negative-query handling is Phase 5b)",
                    q.queryId() );
                continue;
            }
            final List< String > predictedIds;
            try {
                final List< String > pageNames = retriever.retrieve( mode, q.queryText() );
                predictedIds = resolveAll( pageNames );
            } catch ( final RuntimeException e ) {
                LOG.warn( "runNow: query '{}' failed in mode {}: {}",
                    q.queryId(), mode.wireName(), e.getMessage(), e );
                degraded = true;
                skipped++;
                continue;
            }
            final RetrievalMetricsCalculator.QueryScore qs =
                RetrievalMetricsCalculator.score( predictedIds, q.expectedIds() );
            if ( qs == null ) {
                skipped++;
            } else {
                perQuery.add( qs );
                evaluated++;
            }
        }

        final RetrievalMetricsCalculator.Aggregate agg = RetrievalMetricsCalculator.aggregate( perQuery );
        final Instant finished = Instant.now();
        final RetrievalRunResult transientResult = new RetrievalRunResult(
            0L, querySetId, mode,
            agg.ndcgAt5(), agg.ndcgAt10(), agg.recallAt20(), agg.mrr(),
            started, finished,
            evaluated, skipped, degraded );

        final long runId = dao.insertRun( transientResult );
        final RetrievalRunResult persisted = new RetrievalRunResult(
            runId, querySetId, mode,
            agg.ndcgAt5(), agg.ndcgAt10(), agg.recallAt20(), agg.mrr(),
            started, finished,
            evaluated, skipped, degraded );

        metrics.recordRun( persisted );
        metrics.recordDuration( Duration.between( started, finished ) );
        LOG.info( "Retrieval-quality run set={} mode={} evaluated={} skipped={} ndcg@5={} (runId={})",
            querySetId, mode.wireName(), evaluated, skipped,
            agg.ndcgAt5(), runId );
        return persisted;
    }

    @Override
    public List< RetrievalRunResult > recentRuns( final String querySetId,
                                                  final RetrievalMode mode,
                                                  final int limit ) {
        return dao.recentRuns( querySetId, mode, limit );
    }

    @Override
    public synchronized void close() {
        if ( scheduler != null ) {
            scheduler.shutdownNow();
            scheduler = null;
            scheduleStarted = false;
        }
    }

    // ---- internals ----

    private List< String > resolveAll( final List< String > pageNames ) {
        if ( pageNames == null || pageNames.isEmpty() ) return List.of();
        final List< String > out = new ArrayList<>( pageNames.size() );
        for ( final String name : pageNames ) {
            final Optional< String > id = resolver.canonicalIdForSlug( name );
            id.ifPresent( out::add );
        }
        return out;
    }

    long secondsUntilNextRun( final Instant now ) {
        final ZonedDateTime nowUtc = now.atZone( ZoneOffset.UTC );
        final LocalDate today = nowUtc.toLocalDate();
        final LocalDateTime targetToday = LocalDateTime.of( today, LocalTime.of( nightlyHourUtc, 0 ) );
        final ZonedDateTime nextRun;
        if ( nowUtc.toLocalDateTime().isBefore( targetToday ) ) {
            nextRun = targetToday.atZone( ZoneOffset.UTC );
        } else {
            nextRun = targetToday.plusDays( 1 ).atZone( ZoneOffset.UTC );
        }
        return Math.max( 1L, Duration.between( nowUtc, nextRun ).toSeconds() );
    }

    /** Helper for tests that want to assert the runner's expected_ids are unchanged across modes. */
    @SuppressWarnings( "unused" ) // visible for tests
    Set< String > expectedIdsFor( final RetrievalQualityDao.EvalQuery q ) {
        return q.expectedIds();
    }
}
