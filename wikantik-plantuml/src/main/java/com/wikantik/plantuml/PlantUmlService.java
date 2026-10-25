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
import java.util.LinkedHashMap;
import java.util.Map;
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
 * the smaller of the configured timeout and the caller's wait budget, and never caches transient failures.
 *
 * <p>Cache entries are one of: a drawn outcome; a permanent refusal ({@code syntax-error}, {@code too-complex},
 * {@code output-too-large}); or {@code too-slow}, which is either <em>expiring</em> (first timeout, retried once
 * after {@code slowRetryMs}) or <em>permanent</em> (the retry also timed out). Transient outcomes
 * ({@code busy}, {@code page-budget}, {@code render-failed}) are never stored. Concurrent callers for one key share
 * a single in-flight render.</p>
 */
public final class PlantUmlService {

    private static final Logger LOG = LogManager.getLogger( PlantUmlService.class );
    private static final String TOO_SLOW = "too-slow";

    /** A cached outcome; {@code expiresAtNanos == 0} means it never expires (normal LRU lifetime). */
    private record CacheEntry( RenderOutcome outcome, long expiresAtNanos ) {
        boolean expiredSlow( final long now ) {
            return TOO_SLOW.equals( outcome.reason() ) && expiresAtNanos != 0 && now - expiresAtNanos >= 0;
        }
    }

    /** One submitted render; {@code cancelled} and the cache/in-flight maps are guarded by the service lock. */
    private static final class Job {
        Future< RenderOutcome > future;
        boolean cancelled;
        /** System.nanoTime() when a pool thread began the render; valid once {@code started} is true. */
        volatile long startedNanos;
        volatile boolean started;
    }

    private final PlantUmlSettings settings;
    private final Function< String, RenderOutcome > engine;
    private final LongSupplier nanoClock;
    private final ThreadPoolExecutor pool;
    private final Object lock = new Object();
    private final Map< String, CacheEntry > cache;
    private final Map< String, Job > inFlight = new HashMap<>();
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
        this.settings = settings;
        this.engine = engine;
        this.nanoClock = nanoClock;
        final int capacity = Math.max( 1, settings.cacheEntries() );
        this.cache = new LinkedHashMap<>( 16, 0.75f, true ) {
            @Override
            protected boolean removeEldestEntry( final Map.Entry< String, CacheEntry > eldest ) {
                return size() > capacity;
            }
        };
        final int threads = Math.max( 1, settings.threads() );
        final BlockingQueue< Runnable > queue = settings.queueCapacity() > 0
            ? new ArrayBlockingQueue<>( settings.queueCapacity() ) : new SynchronousQueue<>();
        final AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor( threads, threads, 0L, TimeUnit.MILLISECONDS, queue, r -> {
            final Thread t = new Thread( null, r, "wikantik-plantuml-" + counter.incrementAndGet(), settings.threadStackBytes() );
            t.setDaemon( true );
            return t;
        } );
    }

    /** @return the number of renders waiting in the pool queue (test aid) */
    int queuedCount() {
        return pool.getQueue().size();
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
            final CacheEntry hit = cache.get( key );
            final long now = nanoClock.getAsLong();
            if( hit != null && !hit.expiredSlow( now ) ) {
                return hit.outcome();
            }
            retry = hit != null;
            final Job running = inFlight.get( key );
            if( running != null ) {
                job = running;
                joinCount.incrementAndGet();
            } else if( waitBudgetMs <= 0 ) {
                return RenderOutcome.transientRefusal( "page-budget" );
            } else {
                job = submit( key, source, hash );
                if( job == null ) {
                    return RenderOutcome.transientRefusal( "busy" );
                }
            }
        }
        return await( job, key, waitBudgetMs, retry );
    }

    /** Must hold {@code lock}. @return the new job, or null when the pool and queue are full */
    private Job submit( final String key, final String source, final String hash ) {
        final Job job = new Job();
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
        job.startedNanos = System.nanoTime();
        job.started = true;
        RenderOutcome outcome;
        try {
            outcome = finish( engine.apply( source ), hash );
        } catch( final Throwable t ) { // NOPMD - a render must never escape the worker
            LOG.warn( "PlantUML engine threw while rendering {}: {}", key, t.toString(), t );
            outcome = RenderOutcome.transientRefusal( "render-failed" );
        }
        synchronized( lock ) {
            inFlight.remove( key, job );
            if( !job.cancelled && !outcome.transientFailure() ) {
                cache.put( key, new CacheEntry( outcome, 0L ) );
            }
        }
        final Runnable hook = completionHook;
        if( hook != null ) {
            hook.run();
        }
        return outcome;
    }

    /**
     * Waits for the job. The render timeout is measured from when a pool thread starts the job (queue time only
     * spends the caller's budget); when the budget runs out first the render is left running.
     */
    private RenderOutcome await( final Job job, final String key, final long budgetMs, final boolean retry ) {
        final long budgetEnd = System.nanoTime() + budgetMs * 1_000_000L;
        final long timeoutNanos = settings.timeoutMs() * 1_000_000L;
        try {
            while( true ) {
                final long now = System.nanoTime();
                final boolean running = job.started;
                final long runLeft = running ? timeoutNanos - ( now - job.startedNanos ) : Long.MAX_VALUE;
                if( running && runLeft <= 0L ) {
                    return tooSlow( job, key, retry );
                }
                final long budgetLeft = budgetEnd - now;
                if( budgetLeft <= 0L ) {
                    return RenderOutcome.transientRefusal( "page-budget" );
                }
                final long sliceNanos = !running ? Math.min( budgetLeft, 10_000_000L ) : Math.min( budgetLeft, runLeft );
                final Consumer< Future< RenderOutcome > > before = beforeGetHook;
                if( before != null ) {
                    before.accept( job.future );
                }
                try {
                    return job.future.get( Math.max( 1L, sliceNanos / 1_000_000L ), TimeUnit.MILLISECONDS );
                } catch( final TimeoutException e ) {
                    // re-evaluate the limits
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
            final long expiry = retry ? 0L : Math.max( 1L, nanoClock.getAsLong() + settings.slowRetryMs() * 1_000_000L );
            cache.put( key, new CacheEntry( out, expiry ) );
            return out;
        }
    }

    /** A joined render was cancelled by a slower sibling caller: report its too-slow entry if any. */
    private RenderOutcome cachedSlowOr( final String key ) {
        synchronized( lock ) {
            final CacheEntry e = cache.get( key );
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
