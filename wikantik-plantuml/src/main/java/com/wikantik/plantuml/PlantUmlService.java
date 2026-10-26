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
package com.wikantik.plantuml;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The single, bounded entry point for rendering PlantUML sources. It never throws, never blocks a caller longer than
 * its wait budget (nor longer than the configured timeout, counted from when a pool thread starts the render or,
 * for a render still queued, from when the caller began waiting), and never caches transient failures.
 *
 * <p>Cache entries are one of: a drawn outcome; a permanent refusal ({@code syntax-error}, {@code too-complex},
 * {@code output-too-large}); or {@code too-slow} / {@code render-crashed}, which is either <em>expiring</em> (first
 * occurrence, retried once after {@code slowRetryMs}) or <em>permanent</em> (the retry failed the same way). The
 * cache is bounded by entry count and by approximate bytes ({@link OutcomeCache}). Transient outcomes
 * ({@code busy}, {@code page-budget}, {@code render-failed}) are never stored. Concurrent callers for one key share
 * a single in-flight render.</p>
 *
 * <p>Java cannot stop a CPU-bound PlantUML render, so a timed-out render keeps its pool thread until it finishes.
 * When every pool thread is held by such an overdue render, a new render is refused as {@code busy} at once instead
 * of queueing behind them and holding its caller (a request thread) for the whole budget.</p>
 */
public final class PlantUmlService {

    private static final Logger LOG = LogManager.getLogger( PlantUmlService.class );
    private static final String TOO_SLOW = "too-slow";
    /** PlantUML's own crash report (its "has crashed" image): deterministic, so cached like {@code too-slow}. */
    static final String RENDER_CRASHED = "render-crashed";
    private static final String BUSY = "busy";
    /** Idle render threads exit after this long (core threads included). */
    static final long IDLE_KEEP_ALIVE_MS = 60_000L;

    /** One submitted render; {@code cancelled} and the cache/in-flight/running state are guarded by the service lock. */
    private static final class Job {
        Future< RenderOutcome > future;
        boolean cancelled;
        /** True when this render retries an expired {@code too-slow} / {@code render-crashed} entry. */
        boolean retry;
        /** System.nanoTime() when a pool thread began the render; valid once {@code started} is true. */
        volatile long startedNanos;
        volatile boolean started;
    }

    private final PlantUmlSettings settings;
    private final Function< String, RenderOutcome > engine;
    private final LongSupplier nanoClock;
    private final ThreadPoolExecutor pool;
    private final Object lock = new Object();
    private final OutcomeCache cache;
    private final Map< String, Job > inFlight = new HashMap<>();
    /** Jobs a pool thread is executing right now, including cancelled ones Java could not stop. */
    private final Set< Job > running = new HashSet<>();
    /** Test hooks (package-private): after a job's cache write; before a waiter decides on a timeout; on each join. */
    volatile Runnable completionHook;
    volatile Runnable timeoutDecisionHook;
    volatile Consumer< Future< RenderOutcome > > beforeGetHook;
    final AtomicInteger joinCount = new AtomicInteger();

    /** @param settings the service settings */
    public PlantUmlService( final PlantUmlSettings settings ) {
        this( settings, PlantUmlEngine::render );
    }

    PlantUmlService( final PlantUmlSettings settings, final Function< String, RenderOutcome > engine ) {
        this( settings, engine, System::nanoTime );
    }

    PlantUmlService( final PlantUmlSettings settings, final Function< String, RenderOutcome > engine,
                     final LongSupplier nanoClock ) {
        this( settings, engine, nanoClock, IDLE_KEEP_ALIVE_MS );
    }

    /**
     * @param keepAliveMs how long an idle render thread lingers before it exits (test seam; production uses
     *                    {@value #IDLE_KEEP_ALIVE_MS}). Core threads time out too, so a pool left behind by a
     *                    re-initialised engine drains to zero threads instead of pinning its classloader.
     */
    PlantUmlService( final PlantUmlSettings settings, final Function< String, RenderOutcome > engine,
                     final LongSupplier nanoClock, final long keepAliveMs ) {
        this.settings = settings;
        this.engine = engine;
        this.nanoClock = nanoClock;
        this.cache = new OutcomeCache( settings.cacheEntries(), settings.cacheMaxBytes() );
        final int threads = Math.max( 1, settings.threads() );
        final BlockingQueue< Runnable > queue = settings.queueCapacity() > 0
            ? new ArrayBlockingQueue<>( settings.queueCapacity() ) : new SynchronousQueue<>();
        final AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor( threads, threads, keepAliveMs, TimeUnit.MILLISECONDS, queue, r -> {
            final Thread t = new Thread( null, r, "wikantik-plantuml-" + counter.incrementAndGet(), settings.threadStackBytes() );
            t.setDaemon( true );
            return t;
        } );
        this.pool.allowCoreThreadTimeOut( true );
    }

    /** @return the idle keep-alive of the render threads (test aid) */
    long keepAliveMs() {
        return pool.getKeepAliveTime( TimeUnit.MILLISECONDS );
    }

    /** @return whether idle core render threads exit (test aid) */
    boolean coreThreadsTimeOut() {
        return pool.allowsCoreThreadTimeOut();
    }

    /** @return the number of renders waiting in the pool queue (test aid) */
    int queuedCount() {
        return pool.getQueue().size();
    }

    /** @return the approximate bytes the outcome cache holds (test aid) */
    long cachedBytes() {
        synchronized( lock ) {
            return cache.bytes();
        }
    }

    /**
     * Renders {@code source}, waiting at most {@code waitBudgetMs} (and never longer than the configured timeout).
     *
     * @param source       the PlantUML source
     * @param waitBudgetMs how long this caller may wait; at or below zero only already-known outcomes are returned
     * @return the outcome; never {@code null}, never throws
     */
    public RenderOutcome render( final String source, final long waitBudgetMs ) {
        try {
            return doRender( source, waitBudgetMs );
        } catch( final RuntimeException | Error e ) {
            LOG.warn( "PlantUML render failed unexpectedly: {}", e.toString(), e );
            return RenderOutcome.transientRefusal( "render-failed" );
        }
    }

    private RenderOutcome doRender( final String source, final long waitBudgetMs ) {
        if( !settings.enabled() ) {
            return RenderOutcome.refused( "plantuml-disabled", "PlantUML rendering is disabled", 0 );
        }
        final PlantUmlPolicy.Analysis analysis = PlantUmlPolicy.analyse( source );
        if( !analysis.allowed() ) {
            return RenderOutcome.refused( analysis.reason(), "PlantUML source refused: " + analysis.reason(), 0 );
        }
        final String hash = sha256Hex( source );
        final String key = hash + "|" + PlantUmlEngine.version();
        final Job job;
        final boolean retry;
        synchronized( lock ) {
            final OutcomeCache.Entry hit = cache.get( key );
            if( hit != null && !hit.expired( nanoClock.getAsLong() ) ) {
                return hit.outcome();
            }
            retry = hit != null;
            final Job inFlightJob = inFlight.get( key );
            if( inFlightJob != null ) {
                job = inFlightJob;
                joinCount.incrementAndGet();
            } else if( waitBudgetMs <= 0 ) {
                return RenderOutcome.transientRefusal( "page-budget" );
            } else if( overdueCount() >= pool.getMaximumPoolSize() ) {
                LOG.debug( "Every PlantUML render thread is held by an overdue render; refusing as busy" );
                return RenderOutcome.transientRefusal( BUSY );
            } else {
                job = submit( key, source, hash, retry );
                if( job == null ) {
                    return RenderOutcome.transientRefusal( BUSY );
                }
            }
        }
        return await( job, key, waitBudgetMs, retry );
    }

    /** Must hold {@code lock}. @return how many running renders are past the timeout (Java could not stop them) */
    private int overdueCount() {
        final long now = System.nanoTime();
        final long timeoutNanos = settings.timeoutMs() * 1_000_000L;
        int overdue = 0;
        for( final Job j : running ) {
            if( now - j.startedNanos >= timeoutNanos ) {
                overdue++;
            }
        }
        return overdue;
    }

    /** Must hold {@code lock}. @return the new job, or null when the pool and queue are full */
    private Job submit( final String key, final String source, final String hash, final boolean retry ) {
        final Job job = new Job();
        job.retry = retry;
        try {
            job.future = pool.submit( () -> runJob( job, key, source, hash ) );
        } catch( final RejectedExecutionException e ) {
            LOG.debug( "PlantUML pool saturated; refusing as busy" );
            return null;
        }
        inFlight.put( key, job );
        return job;
    }

    private RenderOutcome runJob( final Job job, final String key, final String source, final String hash ) {
        synchronized( lock ) {
            job.startedNanos = System.nanoTime();
            job.started = true;
            running.add( job );
        }
        RenderOutcome outcome;
        try {
            outcome = finish( engine.apply( source ), hash );
        } catch( final Throwable t ) { // NOPMD - a render must never escape the worker
            LOG.warn( "PlantUML engine threw while rendering {}: {}", key, t.toString(), t );
            outcome = RenderOutcome.transientRefusal( "render-failed" );
        }
        synchronized( lock ) {
            running.remove( job );
            inFlight.remove( key, job );
            if( !job.cancelled && !outcome.transientFailure() ) {
                final boolean expiring = RENDER_CRASHED.equals( outcome.reason() ) && !job.retry;
                cache.put( key, new OutcomeCache.Entry( outcome, expiring ? expiry() : 0L ) );
            }
        }
        final Runnable hook = completionHook;
        if( hook != null ) {
            hook.run();
        }
        return outcome;
    }

    /**
     * Waits for the job. The render timeout is measured from when a pool thread starts the job; a job still queued
     * after the caller has waited the timeout is left queued (it still runs and caches) and the caller gets
     * {@code busy}. When the caller's budget runs out first the render is left running.
     */
    private RenderOutcome await( final Job job, final String key, final long budgetMs, final boolean retry ) {
        final long waitStart = System.nanoTime();
        final long budgetEnd = waitStart + budgetMs * 1_000_000L;
        final long timeoutNanos = settings.timeoutMs() * 1_000_000L;
        try {
            while( true ) {
                final long now = System.nanoTime();
                final boolean started = job.started;
                final long runLeft = started ? timeoutNanos - ( now - job.startedNanos ) : timeoutNanos - ( now - waitStart );
                if( runLeft <= 0L ) {
                    return started ? tooSlow( job, key, retry ) : RenderOutcome.transientRefusal( BUSY );
                }
                final long budgetLeft = budgetEnd - now;
                if( budgetLeft <= 0L ) {
                    return RenderOutcome.transientRefusal( "page-budget" );
                }
                final long sliceNanos = !started ? Math.min( Math.min( budgetLeft, runLeft ), 10_000_000L )
                    : Math.min( budgetLeft, runLeft );
                final Consumer< Future< RenderOutcome > > before = beforeGetHook;
                if( before != null ) {
                    before.accept( job.future );
                }
                try {
                    return job.future.get( Math.max( 1L, sliceNanos / 1_000_000L ), TimeUnit.MILLISECONDS );
                } catch( final TimeoutException e ) {
                    // slice elapsed without a result: loop to re-evaluate the limits
                    LOG.trace( "PlantUML render {} slice elapsed; re-evaluating limits", key );
                }
            }
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "Interrupted while waiting for PlantUML render {}", key );
            return RenderOutcome.transientRefusal( "render-failed" );
        } catch( final ExecutionException | CancellationException e ) {
            LOG.warn( "PlantUML render {} did not complete: {}", key, e.toString() );
            return cachedSlowOr( key );
        }
    }

    /** The run exceeded the timeout: cancel the render and remember that it was too slow. */
    @SuppressWarnings( "PMD.CompareObjectsWithEquals" ) // inFlight identity: is this exact job still the registered one?
    private RenderOutcome tooSlow( final Job job, final String key, final boolean retry ) {
        final Runnable hook = timeoutDecisionHook;
        if( hook != null ) {
            hook.run();
        }
        synchronized( lock ) {
            if( inFlight.get( key ) != job ) { // finished (or already handled) while we were deciding
                return cachedSlowOr( key );
            }
            job.cancelled = true;
            job.future.cancel( true );
            pool.remove( (Runnable) job.future ); // free the queue slot if it never ran
            inFlight.remove( key, job );
            final RenderOutcome out = RenderOutcome.refused( TOO_SLOW,
                "The diagram took longer than " + settings.timeoutMs() + " ms to draw", 0 );
            cache.put( key, new OutcomeCache.Entry( out, retry ? 0L : expiry() ) );
            return out;
        }
    }

    /** @return the expiry of a first {@code too-slow} / {@code render-crashed} entry: one retry after slowRetryMs */
    private long expiry() {
        final long at = nanoClock.getAsLong() + settings.slowRetryMs() * 1_000_000L;
        return at == 0L ? 1L : at;
    }

    /** A joined render was cancelled by a slower sibling caller: report its too-slow entry if any. */
    private RenderOutcome cachedSlowOr( final String key ) {
        synchronized( lock ) {
            final OutcomeCache.Entry e = cache.get( key );
            return e != null ? e.outcome() : RenderOutcome.transientRefusal( "render-failed" );
        }
    }

    private RenderOutcome finish( final RenderOutcome raw, final String hash ) {
        if( !raw.isDrawn() ) {
            return raw;
        }
        final PlantUmlDrawing d = raw.drawing();
        if( tooBig( d.lightSvg() ) || tooBig( d.darkSvg() ) ) {
            return RenderOutcome.refused( "output-too-large", "The drawing exceeds " + settings.maxSvgBytes() + " bytes", 0 );
        }
        final String prefix = "wkf" + hash.substring( 0, 10 );
        try {
            return RenderOutcome.drawn( new PlantUmlDrawing(
                SvgScrubber.scrub( d.lightSvg(), prefix + "l-" ), SvgScrubber.scrub( d.darkSvg(), prefix + "d-" ) ) );
        } catch( final SvgScrubber.Rejected e ) {
            LOG.warn( "PlantUML SVG rejected by scrubber ({}): {}", prefix, e.getMessage() );
            return RenderOutcome.transientRefusal( "render-failed" );
        }
    }

    private boolean tooBig( final String svg ) {
        return svg == null || svg.getBytes( StandardCharsets.UTF_8 ).length > settings.maxSvgBytes();
    }

    private static String sha256Hex( final String s ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( s.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for( final byte b : d ) {
                sb.append( String.format( "%02x", b ) );
            }
            return sb.toString();
        } catch( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
