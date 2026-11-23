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

import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEmbedderTest {

    private FakeClock clock;
    private QueryEmbedder embedder;

    @BeforeEach
    void setUp() {
        clock = new FakeClock( 0 );
        // short cooldown + small window so breaker tests stay quick
        embedder = null; // set per-test
    }

    @AfterEach
    void tearDown() {
        if( embedder != null ) {
            embedder.close();
        }
    }

    private QueryEmbedderConfig testConfig() {
        return new QueryEmbedderConfig( 200, 60, 100, 10, 5, 0.5, 1000 );
    }

    /* ---------- cache + normalization ---------- */

    @Test
    void successReturnsVectorAndCaches() {
        final FakeClient client = new FakeClient( new float[]{ 0.1f, 0.2f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );

        final Optional< float[] > first = embedder.embed( "Hello" );
        assertTrue( first.isPresent() );
        assertArrayEquals( new float[]{ 0.1f, 0.2f }, first.get(), 0.0f );

        // second call should be a cache hit — client should NOT be invoked again
        final Optional< float[] > second = embedder.embed( "Hello" );
        assertTrue( second.isPresent() );
        assertEquals( 1, client.calls.get() );
        final QueryEmbedderMetrics m = embedder.metrics();
        assertEquals( 1, m.cacheHit() );
        assertEquals( 1, m.cacheMiss() );
        assertEquals( 1, m.callSuccess() );
    }

    @Test
    void cacheKeyIsTrimmedAndLowercased() {
        final FakeClient client = new FakeClient( new float[]{ 1.0f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        embedder.embed( "hello" );
        embedder.embed( "  HELLO  " );
        embedder.embed( "Hello" );
        assertEquals( 1, client.calls.get(), "all three should hit the same cache bucket" );
    }

    @Test
    void cacheKeyCollapsesInternalWhitespace() {
        final FakeClient client = new FakeClient( new float[]{ 0.5f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        embedder.embed( "hello world" );
        embedder.embed( "hello   world" );
        embedder.embed( "hello\tworld" );
        embedder.embed( "hello\nworld" );
        assertEquals( 1, client.calls.get(),
            "spacing variants of the same query should share one cache slot" );
    }

    @Test
    void cacheKeyStripsTrailingPunctuation() {
        final FakeClient client = new FakeClient( new float[]{ 0.7f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        embedder.embed( "hello world" );
        embedder.embed( "hello world!" );
        embedder.embed( "hello world?" );
        embedder.embed( "hello world..." );
        embedder.embed( "hello world.,;:" );
        assertEquals( 1, client.calls.get(),
            "punctuation-suffixed variants should share one cache slot" );
    }

    @Test
    void cacheKeyDoesNotCollapseDistinctQueries() {
        final FakeClient client = new FakeClient( new float[]{ 0.9f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        embedder.embed( "hello world" );
        embedder.embed( "world hello" );
        embedder.embed( "hello world today" );
        assertEquals( 3, client.calls.get(),
            "different queries must NOT collide in the cache after normalization" );
    }

    @Test
    void nullQueryReturnsEmptyWithoutCallingClient() {
        final AssertiveClient ac = new AssertiveClient();
        embedder = new QueryEmbedder( ac, testConfig(), clock );
        assertTrue( embedder.embed( null ).isEmpty() );
    }

    @Test
    void blankQueryReturnsEmptyWithoutCallingClient() {
        final AssertiveClient ac = new AssertiveClient();
        embedder = new QueryEmbedder( ac, testConfig(), clock );
        assertTrue( embedder.embed( "   " ).isEmpty() );
        assertTrue( embedder.embed( "" ).isEmpty() );
    }

    /* ---------- failure categorization ---------- */

    @Test
    void clientThrowsProducesEmptyAndFailureCounter() {
        final ExplodingClient client = new ExplodingClient( new RuntimeException( "kaboom" ) );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        assertTrue( embedder.embed( "q" ).isEmpty() );
        assertEquals( 1, embedder.metrics().callFailure() );
        assertEquals( 0, embedder.metrics().callSuccess() );
    }

    @Test
    void clientThrowsErrorStillReturnsEmpty() {
        final ExplodingClient client = new ExplodingClient( new OutOfMemoryError( "bad" ) );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        assertTrue( embedder.embed( "q" ).isEmpty() );
        assertEquals( 1, embedder.metrics().callFailure() );
    }

    @Test
    void emptyResultFromClientCountsAsFailure() {
        final FakeClient client = new FakeClient( List.of() );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        assertTrue( embedder.embed( "q" ).isEmpty() );
        assertEquals( 1, embedder.metrics().callFailure() );
        assertEquals( 0, embedder.metrics().callSuccess() );
    }

    @Test
    void nullVectorFromClientCountsAsFailure() {
        final float[][] oneNull = new float[][]{ null };
        final FakeClient client = FakeClient.ofList( List.of() );
        client.nextResult = java.util.Arrays.asList( oneNull );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        assertTrue( embedder.embed( "q" ).isEmpty() );
        assertEquals( 1, embedder.metrics().callFailure() );
    }

    @Test
    void timeoutReturnsEmptyAndIncrementsTimeout() {
        final SlowClient client = new SlowClient( 2_000 );
        embedder = new QueryEmbedder( client, new QueryEmbedderConfig(
                50, 60, 100, 10, 5, 0.5, 1000 ), clock );
        assertTrue( embedder.embed( "slow" ).isEmpty() );
        final QueryEmbedderMetrics m = embedder.metrics();
        assertEquals( 1, m.callTimeout() );
        assertEquals( 1, m.callFailure() );
    }

    /* ---------- in-flight cap (shed instead of queueing) ---------- */

    @Test
    void callsOverTheInFlightCapAreShedAtOnceWithoutTouchingTheClientOrTheBreaker() throws Exception {
        final GatedClient client = new GatedClient();
        embedder = new QueryEmbedder( client, new QueryEmbedderConfig(
                5_000, 60, 100, 10, 5, 0.5, 1000, 1 ), clock );
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final var held = pool.submit( () -> embedder.embed( "held query" ) );
            assertTrue( client.started.await( 5, TimeUnit.SECONDS ), "the first call reached the client" );

            final long start = System.nanoTime();
            for( int i = 0; i < 20; i++ ) {
                assertTrue( embedder.embed( "overflow " + i ).isEmpty(), "a call over the cap falls back at once" );
            }
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - start );
            assertTrue( elapsedMs < 1_000, "shed calls must not wait for the timeout, took " + elapsedMs + " ms" );
            assertEquals( 1, client.calls.get(), "shed calls never reach the embedding backend" );
            final QueryEmbedderMetrics m = embedder.metrics();
            assertEquals( 20, m.callShed() );
            assertEquals( 0, m.callFailure(), "overload is not a backend failure" );
            assertEquals( 0, m.breakerCallRejected() );
            assertEquals( CircuitState.CLOSED, embedder.circuitState(), "shedding never opens the breaker" );

            client.release( new float[]{ 0.5f } );
            assertTrue( held.get( 5, TimeUnit.SECONDS ).isPresent() );
            client.autoComplete = true;
            assertTrue( embedder.embed( "after release" ).isPresent(), "the permit came back" );
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void thePermitIsReturnedAfterAFailedCall() {
        final CountingFailingClient client = new CountingFailingClient();
        embedder = new QueryEmbedder( client, new QueryEmbedderConfig(
                200, 60, 100, 10, 5, 0.5, 1000, 1 ), clock );
        assertTrue( embedder.embed( "first" ).isEmpty() );
        assertTrue( embedder.embed( "second" ).isEmpty() );
        assertEquals( 2, client.calls.get(), "a failed call must release its permit" );
        assertEquals( 0, embedder.metrics().callShed() );
    }

    @Test
    void thePermitIsReturnedWhenTheClientThrowsAnError() {
        final FlippingErrorClient client = new FlippingErrorClient();
        embedder = new QueryEmbedder( client, new QueryEmbedderConfig(
                200, 60, 100, 10, 5, 0.5, 1000, 1 ), clock );
        assertTrue( embedder.embed( "first" ).isEmpty(), "a JVM Error collapses to the BM25 fallback" );
        client.fail = false;
        assertTrue( embedder.embed( "second" ).isPresent(), "the permit came back after the Error" );
        assertEquals( 0, embedder.metrics().callShed() );
    }

    @Test
    void theDefaultLimitIsAdaptiveAndATimeoutHalvesIt() {
        final SlowClient client = new SlowClient( 2_000 );
        embedder = new QueryEmbedder( client, new QueryEmbedderConfig(
                50, 60, 100, 10, 5, 0.5, 1000 ), clock );
        assertEquals( "adaptive", embedder.concurrencyMode() );
        assertEquals( 4, embedder.concurrencyLimit() );
        assertTrue( embedder.embed( "slow" ).isEmpty() );
        assertEquals( 2, embedder.concurrencyLimit(), "a timed-out embed is a sign of overload" );
        assertEquals( 0, embedder.embedsInFlight() );
    }

    @Test
    void anExplicitCapIsFixedAndIgnoresTimeouts() {
        final SlowClient client = new SlowClient( 2_000 );
        embedder = new QueryEmbedder( client, new QueryEmbedderConfig(
                50, 60, 100, 10, 5, 0.5, 1000, 3 ), clock );
        assertEquals( "fixed", embedder.concurrencyMode() );
        assertTrue( embedder.embed( "slow" ).isEmpty() );
        assertEquals( 3, embedder.concurrencyLimit() );
    }

    @Test
    void breakerRejectionsDoNotMoveTheAdaptiveLimit() {
        final ExplodingClient client = new ExplodingClient( new RuntimeException( "down" ) );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        for( int i = 0; i < 20; i++ ) {
            embedder.embed( "q" + i );   // fails fast, then the breaker opens and rejects
        }
        assertTrue( embedder.metrics().breakerCallRejected() > 0 );
        assertEquals( 4, embedder.concurrencyLimit(), "errors and rejections say nothing about the embedder's speed" );
    }

    /* ---------- cancelling timed-out calls: only on a warm embedder ---------- */

    /** Each call returns a fresh pending future unless {@code answerNow}; remembers every future it handed out. */
    static final class PendingClient implements TextEmbeddingClient {
        final List< CompletableFuture< List< float[] > > > futures = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean answerNow;

        @Override public CompletableFuture< List< float[] > > embedAsync( final List< String > t, final EmbeddingKind k ) {
            final CompletableFuture< List< float[] > > f = answerNow
                    ? CompletableFuture.completedFuture( List.of( new float[]{ 0.7f } ) )
                    : new CompletableFuture<>();
            futures.add( f );
            return f;
        }
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            throw new AssertionError( "the embedder uses embedAsync" );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "pending"; }
        CompletableFuture< List< float[] > > last() { return futures.get( futures.size() - 1 ); }
    }

    private QueryEmbedderConfig fastTimeout() {
        return new QueryEmbedderConfig( 50, 60, 100, 10, 5, 0.5, 1000 );
    }

    @Test
    void aTimedOutEmbedOnAColdEmbedderIsLeftRunningSoTheModelLoadCanFinish() {
        // Ollama aborts a model load when the client disconnects. If every query embed were cancelled at the
        // 2 s timeout, a model needing longer than that to load would never load, and dense search would stay
        // off until some uncancelled request (a save-time re-embed) happened to load it.
        final PendingClient client = new PendingClient();
        embedder = new QueryEmbedder( client, fastTimeout(), clock );
        assertTrue( embedder.embed( "first query after a restart" ).isEmpty() );
        assertFalse( client.last().isCancelled(), "nothing has answered yet: the embedder may be loading its model" );

        client.last().complete( List.of( new float[]{ 0.1f } ) );   // the load finished in the background
        assertTrue( embedder.embed( "next query" ).isEmpty() );
        assertTrue( client.last().isCancelled(), "a warm embedder's timed-out call is abandoned and cancelled" );
    }

    @Test
    void aTimedOutEmbedOnAWarmEmbedderIsCancelledSoItStopsHoldingAQueueSlot() {
        final PendingClient client = new PendingClient();
        embedder = new QueryEmbedder( client, fastTimeout(), clock );
        client.answerNow = true;
        assertTrue( embedder.embed( "warm-up" ).isPresent() );
        client.answerNow = false;
        assertTrue( embedder.embed( "slow one" ).isEmpty() );
        assertTrue( client.last().isCancelled() );
    }

    @Test
    void aRestartedEmbedderIsLeftToLoadOnceNothingHasAnsweredForTheWarmWindow() {
        // Warm a moment ago, then the embedder restarts: the first timeout still looks like overload and is
        // cancelled, but once nothing has answered for WARM_WINDOW a timed-out call is left to finish the load.
        final PendingClient client = new PendingClient();
        embedder = new QueryEmbedder( client, fastTimeout(), clock );
        client.answerNow = true;
        assertTrue( embedder.embed( "warm-up" ).isPresent() );
        client.answerNow = false;
        clock.advanceMillis( 1_000 );
        assertTrue( embedder.embed( "just after the restart" ).isEmpty() );
        assertTrue( client.last().isCancelled(), "an answer a second ago: this looks like overload" );

        clock.advanceMillis( EmbedderWarmth.WINDOW.toMillis() );
        assertTrue( embedder.embed( "still nothing answering" ).isEmpty() );
        assertFalse( client.last().isCancelled(), "nothing has answered for the window: maybe loading" );
    }

    /* ---------- breaker integration ---------- */

    @Test
    void breakerOpensAfterSustainedFailures() {
        final ExplodingClient client = new ExplodingClient( new RuntimeException( "flap" ) );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        // 5 failures with unique queries to avoid cache collisions, minCalls=5, rate=1.0
        for( int i = 0; i < 5; i++ ) {
            embedder.embed( "q" + i );
        }
        assertEquals( CircuitState.OPEN, embedder.circuitState() );
        assertEquals( 1, embedder.metrics().breakerOpen() );
    }

    @Test
    void openBreakerShortCircuitsWithoutCallingClient() {
        final CountingFailingClient client = new CountingFailingClient();
        embedder = new QueryEmbedder( client, testConfig(), clock );
        // First trip the breaker with 5 failing calls
        for( int i = 0; i < 5; i++ ) {
            embedder.embed( "q" + i );
        }
        assertEquals( CircuitState.OPEN, embedder.circuitState() );
        // Now flip the client into "explode if called" mode — any OPEN breaker call that
        // leaks through would surface as AssertionError, which embed() still swallows
        // into Optional.empty() but a non-zero call count would prove the leak.
        final int callsWhileClosed = client.calls.get();
        client.assertNotCalled = true;
        for( int i = 0; i < 3; i++ ) {
            assertTrue( embedder.embed( "after" + i ).isEmpty() );
        }
        assertEquals( callsWhileClosed, client.calls.get(),
                "open breaker must short-circuit before calling the client" );
        final QueryEmbedderMetrics m = embedder.metrics();
        assertTrue( m.breakerCallRejected() >= 3 );
    }

    @Test
    void breakerHalfOpenProbeSuccessReClosesBreaker() {
        final ToggleClient client = new ToggleClient();
        client.failing = true;
        embedder = new QueryEmbedder( client, testConfig(), clock );

        for( int i = 0; i < 5; i++ ) {
            embedder.embed( "q" + i );
        }
        assertEquals( CircuitState.OPEN, embedder.circuitState() );

        // cooldown elapses
        clock.advanceMillis( testConfig().breakerCooldownMs() );
        client.failing = false;
        assertTrue( embedder.embed( "probe" ).isPresent() );
        assertEquals( CircuitState.CLOSED, embedder.circuitState() );
        assertEquals( 1, embedder.metrics().breakerClose() );
        assertTrue( embedder.metrics().breakerHalfOpenProbe() >= 1 );
    }

    @Test
    void breakerHalfOpenProbeFailureReOpensBreaker() {
        final ExplodingClient client = new ExplodingClient( new RuntimeException( "still bad" ) );
        embedder = new QueryEmbedder( client, testConfig(), clock );

        for( int i = 0; i < 5; i++ ) {
            embedder.embed( "q" + i );
        }
        assertEquals( CircuitState.OPEN, embedder.circuitState() );
        clock.advanceMillis( testConfig().breakerCooldownMs() );

        assertTrue( embedder.embed( "probe" ).isEmpty() );
        assertEquals( CircuitState.OPEN, embedder.circuitState() );
        // two OPEN transitions
        assertEquals( 2, embedder.metrics().breakerOpen() );
        // cooldown is reset — we can't probe immediately
        clock.advanceMillis( testConfig().breakerCooldownMs() - 1 );
        final long rejectedBefore = embedder.metrics().breakerCallRejected();
        embedder.embed( "too-soon" );
        assertTrue( embedder.metrics().breakerCallRejected() > rejectedBefore );
    }

    /* ---------- concurrent stress ---------- */

    @Test
    void stressTestNoExceptionEscapesAndBreakerRecovers() throws Exception {
        final FlakyClient client = new FlakyClient();
        // Larger window so some calls succeed even under 50% failures
        final QueryEmbedderConfig cfg = new QueryEmbedderConfig(
                500, 60, 500, 50, 20, 0.9, 500 );
        embedder = new QueryEmbedder( client, cfg, clock );

        final int threads = 16;
        final int perThread = 64;
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        final CountDownLatch start = new CountDownLatch( 1 );
        final CountDownLatch done = new CountDownLatch( threads );

        try {
            for( int t = 0; t < threads; t++ ) {
                final int tid = t;
                pool.submit( () -> {
                    try {
                        start.await();
                        for( int i = 0; i < perThread; i++ ) {
                            // embed must never throw under any input
                            embedder.embed( "q-" + tid + "-" + ( i % 8 ) );
                        }
                    } catch( final Throwable th ) {
                        // If anything escapes, make it visible as a test failure
                        exceptionEscaped = th;
                    } finally {
                        done.countDown();
                    }
                } );
            }
            start.countDown();
            assertTrue( done.await( 30, TimeUnit.SECONDS ), "stress run should finish promptly" );
        } finally {
            pool.shutdownNow();
            pool.awaitTermination( 2, TimeUnit.SECONDS );
        }

        assertNotNullOrFail();
        // Recovery: flip the client to healthy, walk past the cooldown, and embed again.
        client.alwaysFail = false;
        clock.advanceMillis( cfg.breakerCooldownMs() + 1 );
        // Up to a few tries to walk through HALF_OPEN → CLOSED if currently open.
        Optional< float[] > last = Optional.empty();
        for( int i = 0; i < 5 && last.isEmpty(); i++ ) {
            last = embedder.embed( "recover-" + i );
            if( last.isEmpty() ) {
                clock.advanceMillis( cfg.breakerCooldownMs() + 1 );
            }
        }
        assertTrue( last.isPresent(), "breaker should allow traffic after cooldown + healthy client" );
    }

    private volatile Throwable exceptionEscaped;

    private void assertNotNullOrFail() {
        if( exceptionEscaped != null ) {
            throw new AssertionError( "exception escaped embed(): " + exceptionEscaped, exceptionEscaped );
        }
    }

    /* ---------- latency timer ---------- */

    @Test
    void embedRecordsLatencyWhenTimerSet() {
        final FakeClient client = new FakeClient( new float[]{ 0.1f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        final Timer timer = Timer.builder( "wikantik.search.hybrid.embedder.latency" )
                .register( new SimpleMeterRegistry() );
        embedder.setLatencyTimer( timer );

        embedder.embed( "a query" );

        assertEquals( 1, timer.count(),
            "embed() must record one sample to the injected latency Timer" );
    }

    /* ---------- metrics snapshot stability ---------- */

    @Test
    void metricsSnapshotIsImmutable() {
        final FakeClient client = new FakeClient( new float[]{ 1.0f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        final QueryEmbedderMetrics before = embedder.metrics();
        embedder.embed( "x" );
        final QueryEmbedderMetrics after = embedder.metrics();
        // snapshot taken before mutation should not reflect the new success
        assertEquals( 0, before.callSuccess() );
        assertEquals( 1, after.callSuccess() );
    }

    @Test
    void circuitStateGetterDoesNotBlock() {
        final FakeClient client = new FakeClient( new float[]{ 1.0f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        assertNotNull( embedder.circuitState() );
    }

    /* ---------- test doubles ---------- */

    /** Returns the configured vector for every call. */
    static final class FakeClient implements TextEmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile List< float[] > nextResult;

        FakeClient( final float[] vec ) {
            this.nextResult = List.of( vec );
        }

        FakeClient( final List< float[] > result ) {
            this.nextResult = result;
        }

        static FakeClient ofList( final List< float[] > result ) {
            return new FakeClient( result );
        }

        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            assertEquals( EmbeddingKind.QUERY, k );
            calls.incrementAndGet();
            return nextResult;
        }
        @Override public int dimension() { return 2; }
        @Override public String modelName() { return "fake-model"; }
    }

    /** Holds every embedAsync future open until {@link #release} (or completes at once when autoComplete). */
    static final class GatedClient implements TextEmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch( 1 );
        final CompletableFuture< List< float[] > > gate = new CompletableFuture<>();
        volatile boolean autoComplete;

        void release( final float[] vec ) { gate.complete( List.of( vec ) ); }

        @Override public CompletableFuture< List< float[] > > embedAsync( final List< String > t, final EmbeddingKind k ) {
            calls.incrementAndGet();
            started.countDown();
            return autoComplete ? CompletableFuture.completedFuture( List.of( new float[]{ 0.9f } ) ) : gate;
        }
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            throw new AssertionError( "the embedder uses embedAsync" );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "gated"; }
    }

    /** Throws an {@link Error} while {@code fail}, then answers normally. */
    static final class FlippingErrorClient implements TextEmbeddingClient {
        volatile boolean fail = true;
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            if( fail ) { throw new StackOverflowError( "simulated" ); }
            return List.of( new float[]{ 0.3f } );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "flip-error"; }
    }

    /** Always throws the provided failure. */
    static final class ExplodingClient implements TextEmbeddingClient {
        private final Throwable failure;
        ExplodingClient( final Throwable f ) { this.failure = f; }

        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            if( failure instanceof RuntimeException re ) { throw re; }
            if( failure instanceof Error e ) { throw e; }
            throw new RuntimeException( failure );
        }
        @Override public int dimension() { return 2; }
        @Override public String modelName() { return "explode"; }
    }

    /** Answers after longer than the timeout window, to drive TimeoutException. */
    static final class SlowClient implements TextEmbeddingClient {
        private final long sleepMs;
        SlowClient( final long sleepMs ) { this.sleepMs = sleepMs; }

        /**
         * Answers late without holding a thread. The inherited version would park a worker of the JVM-wide
         * common pool for the whole delay, long after the test that asked has finished; on a machine with few
         * cores (pool size = cores - 1) three such tests used every worker and the tests that ran next timed out.
         */
        @Override public CompletableFuture< List< float[] > > embedAsync( final List< String > t, final EmbeddingKind k ) {
            return new CompletableFuture< List< float[] > >()
                    .completeOnTimeout( List.of( new float[]{ 0.0f } ), sleepMs, TimeUnit.MILLISECONDS );
        }
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            try {
                Thread.sleep( sleepMs );
            } catch( final InterruptedException ie ) {
                Thread.currentThread().interrupt();
            }
            return List.of( new float[]{ 0.0f } );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "slow"; }
    }

    /** Flips between failing and succeeding based on a flag. */
    static final class ToggleClient implements TextEmbeddingClient {
        volatile boolean failing;
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            if( failing ) { throw new RuntimeException( "toggled failure" ); }
            return List.of( new float[]{ 0.42f } );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "toggle"; }
    }

    /** Raises AssertionError if ever called — used to prove the breaker short-circuits. */
    static final class AssertiveClient implements TextEmbeddingClient {
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            throw new AssertionError( "client should not have been called" );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "assertive"; }
    }

    /** Fails every call, counts calls, and can be flipped to assert-not-called. */
    static final class CountingFailingClient implements TextEmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean assertNotCalled;
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            if( assertNotCalled ) {
                throw new AssertionError( "client invoked while breaker should be OPEN" );
            }
            calls.incrementAndGet();
            throw new RuntimeException( "fail" );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "counting-failing"; }
    }

    /**
     * Deterministic pseudo-random failures driven by call count. Roughly 50% fail.
     * No real randomness so the test is reproducible.
     */
    static final class FlakyClient implements TextEmbeddingClient {
        private final AtomicInteger calls = new AtomicInteger();
        volatile boolean alwaysFail = true;
        @Override public List< float[] > embed( final List< String > t, final EmbeddingKind k ) {
            final int n = calls.incrementAndGet();
            if( alwaysFail && ( n % 2 == 0 ) ) {
                throw new RuntimeException( "flaky #" + n );
            }
            return List.of( new float[]{ (float) n } );
        }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "flaky"; }
    }
}
