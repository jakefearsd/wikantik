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
package com.wikantik.search.hybrid;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Resilience wrapper around {@link TextEmbeddingClient#embed} for the query path.
 *
 * <p>Every user search triggers a query embedding call, so this wrapper is the
 * line between "hybrid retrieval improves results" and "hybrid retrieval breaks
 * search when the embedding backend hiccups". It therefore:</p>
 *
 * <ul>
 *   <li>Caches recent query vectors in Caffeine so repeat searches skip the backend
 *       entirely.</li>
 *   <li>Bounds each call by a wall-clock timeout so a slow cold start does not
 *       block the request thread.</li>
 *   <li>Runs a hand-rolled CLOSED/OPEN/HALF_OPEN circuit breaker on top of the
 *       timeout so a sustained outage fails fast without hammering the backend.</li>
 *   <li>Counts successes, failures, timeouts, cache hits/misses, and breaker
 *       transitions — exposed as an immutable snapshot via {@link #metrics()}.</li>
 * </ul>
 *
 * <p><strong>{@link #embed(String)} never throws.</strong> Every failure mode
 * collapses to {@link Optional#empty()}. Phase 5 consumes this as the signal to
 * drop back to BM25-only ranking.</p>
 *
 * <p>All state is thread-safe: counters use {@link LongAdder}, breaker mutations
 * are guarded inside {@link Breaker}, and the Caffeine cache is lock-free.</p>
 */
public final class QueryEmbedder {

    private static final Logger LOG = LogManager.getLogger( QueryEmbedder.class );

    private final TextEmbeddingClient client;
    private final QueryEmbedderConfig config;

    private final Cache< String, float[] > cache;
    private final Breaker breaker;

    /* Counters — mutated from many threads, snapshot-read from admin. */
    private final LongAdder callSuccess = new LongAdder();
    private final LongAdder callFailure = new LongAdder();
    private final LongAdder callTimeout = new LongAdder();
    private final LongAdder cacheHit = new LongAdder();
    private final LongAdder cacheMiss = new LongAdder();
    private final LongAdder breakerOpen = new LongAdder();
    private final LongAdder breakerClose = new LongAdder();
    private final LongAdder breakerHalfOpenProbe = new LongAdder();
    private final LongAdder breakerCallRejected = new LongAdder();

    public QueryEmbedder( final TextEmbeddingClient client,
                          final QueryEmbedderConfig config,
                          final Clock clock ) {
        this.client = Objects.requireNonNull( client, "client" );
        this.config = Objects.requireNonNull( config, "config" );
        Objects.requireNonNull( clock, "clock" );
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite( Duration.ofSeconds( config.cacheTtlSeconds() ) )
                .maximumSize( config.cacheMaxEntries() )
                .build();
        this.breaker = new Breaker( config, clock );
    }

    /**
     * Embed a single query and return {@code Optional.of(vector)} on success,
     * {@code Optional.empty()} on any failure. Never throws.
     */
    public Optional< float[] > embed( final String query ) {
        if( query == null ) {
            return Optional.empty();
        }
        final String normalized = normalizeForCache( query );
        if( normalized.isEmpty() ) {
            return Optional.empty();
        }
        final String cacheKey = client.modelName() + "|" + normalized;

        final float[] cached = cache.getIfPresent( cacheKey );
        if( cached != null ) {
            cacheHit.increment();
            return Optional.of( cached );
        }
        cacheMiss.increment();

        final Breaker.Admittance admit = breaker.beforeCall();
        if( admit == Breaker.Admittance.REJECT ) {
            breakerCallRejected.increment();
            callFailure.increment();
            return Optional.empty();
        }
        if( admit == Breaker.Admittance.PROBE ) {
            breakerHalfOpenProbe.increment();
            LOG.warn( "Query embedder circuit breaker HALF_OPEN — admitting probe" );
        }

        boolean success = false;
        try {
            final float[] vec = invokeWithTimeout( query );
            if( vec == null ) {
                // empty or malformed response — treat as failure but no exception was thrown
                callFailure.increment();
                return Optional.empty();
            }
            cache.put( cacheKey, vec );
            callSuccess.increment();
            success = true;
            return Optional.of( vec );
        } catch( final TimeoutException e ) {
            callTimeout.increment();
            callFailure.increment();
            LOG.debug( "Query embedding timed out after {} ms", config.timeoutMs() );
            return Optional.empty();
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            callFailure.increment();
            LOG.debug( "Query embedding interrupted" );
            return Optional.empty();
        } catch( final Throwable t ) {
            // Every other failure mode — runtime exceptions, ExecutionException
            // cause chains, even Errors — collapses to Optional.empty() so the
            // caller can fall back to BM25-only ranking.
            callFailure.increment();
            LOG.debug( "Query embedding failed: {}", t.toString() );
            return Optional.empty();
        } finally {
            breaker.afterCall( success, admit == Breaker.Admittance.PROBE,
                    this::noteBreakerOpen, this::noteBreakerClose );
        }
    }

    /** Current circuit state — cheap read for admin panels and health probes. */
    public CircuitState circuitState() {
        return breaker.currentState();
    }

    /** Immutable counter snapshot. */
    public QueryEmbedderMetrics metrics() {
        return new QueryEmbedderMetrics(
                callSuccess.sum(),
                callFailure.sum(),
                callTimeout.sum(),
                cacheHit.sum(),
                cacheMiss.sum(),
                breakerOpen.sum(),
                breakerClose.sum(),
                breakerHalfOpenProbe.sum(),
                breakerCallRejected.sum() );
    }

    /**
     * No-op kept for binary/source compatibility with earlier versions that owned
     * a dedicated timeout pool. Async I/O is now driven by the embedding client's
     * own non-blocking transport, so there is nothing for this class to shut down.
     */
    public void close() {
        // intentionally empty
    }

    /* ---------- internals ---------- */

    /**
     * Cache-key normalization. Folds case, strips trailing punctuation, and
     * collapses any run of whitespace to a single space so trivial spelling
     * variants of the same query share a slot. Two queries that differ only in
     * punctuation, capitalization, or whitespace will hit the same Caffeine
     * entry; semantically distinct queries keep their own slots.
     */
    static String normalizeForCache( final String raw ) {
        if( raw == null ) {
            return "";
        }
        final String lower = raw.toLowerCase( Locale.ROOT );
        // Collapse internal whitespace (tabs, newlines, multiple spaces) to single spaces,
        // then strip leading/trailing whitespace and trailing punctuation. We preserve
        // punctuation that sits *inside* the query (e.g. "c++") because it carries meaning.
        final String collapsed = WHITESPACE.matcher( lower ).replaceAll( " " ).strip();
        return TRAILING_PUNCT.matcher( collapsed ).replaceAll( "" );
    }

    private static final java.util.regex.Pattern WHITESPACE =
        java.util.regex.Pattern.compile( "\\s+" );
    private static final java.util.regex.Pattern TRAILING_PUNCT =
        java.util.regex.Pattern.compile( "[\\p{Punct}\\s]+$" );

    private float[] invokeWithTimeout( final String query ) throws Exception {
        final CompletableFuture< List< float[] > > future =
                client.embedAsync( List.of( query ), EmbeddingKind.QUERY );
        try {
            final List< float[] > vecs = future.get( config.timeoutMs(), TimeUnit.MILLISECONDS );
            if( vecs == null || vecs.isEmpty() || vecs.get( 0 ) == null ) {
                return null;
            }
            return vecs.get( 0 );
        } catch( final TimeoutException te ) {
            // Cancel propagates to native HttpClient.sendAsync where overridden;
            // for the default supplyAsync wrapper it merely marks the future cancelled,
            // which is acceptable — the worker eventually finishes and is reclaimed.
            future.cancel( true );
            throw te;
        } catch( final CancellationException ce ) {
            final TimeoutException te2 = new TimeoutException( "Query embedding cancelled: " + ce.getMessage() );
            te2.initCause( ce );
            throw te2;
        } catch( final ExecutionException ee ) {
            // Unwrap to the underlying failure so logs and tests see the real cause.
            // CompletionException nesting is common when sendAsync chains .handle().
            Throwable cause = ee.getCause();
            while( cause instanceof CompletionException && cause.getCause() != null ) {
                cause = cause.getCause();
            }
            if( cause instanceof Exception ex ) {
                throw ex; //NOPMD - ex is the unwrapped cause of ee, already preserved
            }
            if( cause instanceof Error err ) {
                throw err; //NOPMD - err is the unwrapped cause of ee, already preserved
            }
            throw ee;
        }
    }

    private void noteBreakerOpen( final CircuitState previous ) {
        breakerOpen.increment();
        LOG.warn( "Query embedder circuit breaker OPEN (previous state={})", previous );
    }

    private void noteBreakerClose( final CircuitState previous ) {
        if( previous == CircuitState.CLOSED ) {
            return;
        }
        breakerClose.increment();
        LOG.warn( "Query embedder circuit breaker CLOSED (recovered from {})", previous );
    }

}
