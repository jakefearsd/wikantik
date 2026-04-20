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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
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

    /** Sleeps longer than the timeout window to drive TimeoutException. */
    static final class SlowClient implements TextEmbeddingClient {
        private final long sleepMs;
        SlowClient( final long sleepMs ) { this.sleepMs = sleepMs; }
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
