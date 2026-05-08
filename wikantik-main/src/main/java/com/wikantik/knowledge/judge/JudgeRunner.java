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
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.PoolClosedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically picks up pending proposals whose machine_status is null and
 * feeds them to the judge service. Verdicts are written via the repository;
 * approvals trigger materialisation; hard rejects update kg_rejections so
 * the same triple won't re-surface from extraction.
 *
 * <p>Cadence + concurrency come from KgJudgeConfig. The runner is started
 * via {@link #schedule()} during WikiEngine boot and exposes
 * {@link #runOnce()} for ad-hoc admin triggers and tests.</p>
 */
public class JudgeRunner implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( JudgeRunner.class );

    private final KgProposalRepository proposals;
    private final KgRejectionRepository rejections;
    private final KgProposalJudgeService judge;
    private final KgMaterializationService materialization;
    private final KgJudgeConfig config;

    private ScheduledExecutorService scheduler;
    private boolean started;

    // --- Runtime progress state ---
    private final AtomicBoolean inFlight           = new AtomicBoolean();
    private final AtomicInteger lastRunSubmitted   = new AtomicInteger();
    private final AtomicInteger lastRunCompleted   = new AtomicInteger();
    private volatile Instant    lastRunStartedAt;
    private volatile Instant    lastRunFinishedAt;
    private volatile String     lastRunError;   // null on success

    /**
     * Immutable snapshot of the runner's current status. {@code queueDepth} is
     * supplied by the caller so the runner does not need a second repo handle.
     */
    public record Status(
        boolean inFlight,
        int lastRunSubmitted,
        int lastRunCompleted,
        Instant lastRunStartedAt,
        Instant lastRunFinishedAt,
        String lastRunError,
        int queueDepth
    ) {}

    /**
     * Returns a consistent snapshot of the runner's current runtime status.
     *
     * @param queueDepth the caller-supplied count of pending-unjudged proposals
     */
    public Status status( final long queueDepth ) {
        return new Status(
            inFlight.get(),
            lastRunSubmitted.get(),
            lastRunCompleted.get(),
            lastRunStartedAt,
            lastRunFinishedAt,
            lastRunError,
            Math.toIntExact( Math.min( Integer.MAX_VALUE, queueDepth ) )
        );
    }

    public JudgeRunner( final KgProposalRepository proposals,
                         final KgRejectionRepository rejections,
                         final KgProposalJudgeService judge,
                         final KgMaterializationService materialization,
                         final KgJudgeConfig config ) {
        this.proposals       = Objects.requireNonNull( proposals, "proposals" );
        this.rejections      = Objects.requireNonNull( rejections, "rejections" );
        this.judge           = Objects.requireNonNull( judge, "judge" );
        this.materialization = Objects.requireNonNull( materialization, "materialization" );
        this.config          = Objects.requireNonNull( config, "config" );
    }

    public synchronized void schedule() {
        if ( started ) return;
        if ( !config.enabled() || !config.cronEnabled() ) {
            LOG.info( "JudgeRunner disabled (enabled={}, cronEnabled={})",
                config.enabled(), config.cronEnabled() );
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "kg-judge-runner" );
            t.setDaemon( true );
            return t;
        } );
        final long intervalMin = Math.max( 1, config.cronIntervalMinutes() );
        scheduler.scheduleAtFixedRate( this::runOnceQuietly,
            intervalMin, intervalMin, TimeUnit.MINUTES );
        started = true;
        LOG.info( "JudgeRunner scheduled every {} min", intervalMin );
    }

    /** Best-effort wrapper used by the scheduler so a single failure doesn't break the cadence. */
    public void runOnceQuietly() {
        try { runOnce(); }
        catch ( final PoolClosedException e ) {
            LOG.debug( "judge runner pass aborted — data source closed during shutdown" );
        }
        catch ( final RuntimeException e ) {
            LOG.warn( "judge runner pass failed: {}", e.getMessage(), e );
        }
    }

    /** Synchronous one-pass; returns the count of proposals submitted for judging. */
    public int runOnce() {
        inFlight.set( true );
        lastRunStartedAt = Instant.now();
        lastRunSubmitted.set( 0 );
        lastRunCompleted.set( 0 );
        lastRunError = null;
        try {
            return runOnceInternal();
        } catch ( final PoolClosedException e ) {
            // Graceful shutdown — do not surface as lastRunError.
            throw e;
        } catch ( final RuntimeException e ) {
            lastRunError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            inFlight.set( false );
            lastRunFinishedAt = Instant.now();
        }
    }

    private int runOnceInternal() {
        final List< KgProposal > batch = proposals.getProposalsForJudging( config.batchSize() );
        if ( batch.isEmpty() ) return 0;

        try ( ExecutorService pool = Executors.newFixedThreadPool( Math.max( 1, config.concurrency() ),
            r -> { final Thread t = new Thread( r, "kg-judge-worker" ); t.setDaemon( true ); return t; } ) ) {
            int submitted = 0;
            for ( final KgProposal proposal : batch ) {
                if ( pastMaxAttempts( proposal ) ) {
                    LOG.debug( "skip {} — past max_attempts ({})", proposal.id(), config.maxAttempts() );
                    continue;
                }
                pool.submit( () -> processOne( proposal ) );
                submitted++;
            }
            lastRunSubmitted.set( submitted );
            pool.shutdown();
            // Allow each worker up to 2 * timeoutSeconds to finish; total bound = batchSize * (2*timeout).
            final long awaitSec = Math.max( 30L, (long) batch.size() * config.timeoutSeconds() * 2L );
            if ( !pool.awaitTermination( awaitSec, TimeUnit.SECONDS ) ) {
                LOG.warn( "judge pool timed out after {}s — {} tasks may still be running",
                    awaitSec, submitted );
            }
            return submitted;
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private boolean pastMaxAttempts( final KgProposal p ) {
        final long abstainCount = proposals.listReviews( p.id() ).stream()
            .filter( r -> KgProposalReview.REVIEWER_MACHINE.equals( r.reviewerKind() ) )
            .filter( r -> JudgeVerdict.ABSTAIN.equals( r.verdict() ) )
            .count();
        return abstainCount >= config.maxAttempts();
    }

    private void processOne( final KgProposal proposal ) {
        try {
            final JudgeVerdict v = judge.judge( proposal );
            if ( v.isTransientUnavailable() ) {
                // Infrastructure failure (Ollama timeout, connection refused,
                // malformed response). The service already logged a WARN with
                // the cause. Don't persist a review or stamp machine_status —
                // leave the proposal at machine_status=NULL so the next cron
                // pass naturally retries it on a (hopefully) warm model. This
                // prevents transient failures from polluting review history or
                // counting against the max_attempts cap.
                LOG.debug( "judge transient-unavailable for proposal {} — leaving for retry: {}",
                    proposal.id(), v.rationale() );
                return;
            }
            proposals.applyMachineVerdict( proposal.id(), v.verdict(), v.confidence(), v.model() );
            proposals.recordReview( proposal.id(), KgProposalReview.REVIEWER_MACHINE, v.model(),
                v.verdict(), v.confidence(), v.rationale() );
            if ( JudgeVerdict.APPROVED.equals( v.verdict() ) ) {
                materialization.materializeMachine( proposal );
            } else if ( JudgeVerdict.REJECTED.equals( v.verdict() )
                    && "new-edge".equals( proposal.proposalType() ) ) {
                final var data = proposal.proposedData();
                final String src = Objects.toString( data.get( "source" ), null );
                final String tgt = Objects.toString( data.get( "target" ), null );
                final String rel = Objects.toString( data.get( "relationship" ), null );
                if ( src != null && tgt != null && rel != null ) {
                    rejections.insertRejection( src, tgt, rel, v.model(), v.rationale() );
                }
            }
            lastRunCompleted.incrementAndGet();
        } catch ( final PoolClosedException e ) {
            LOG.debug( "judge worker exiting for proposal {} — data source closed during shutdown",
                proposal.id() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge processing failed for proposal {}: {}", proposal.id(), e.getMessage(), e );
        }
    }

    @Override
    public synchronized void close() {
        if ( scheduler != null ) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        started = false;
    }
}
