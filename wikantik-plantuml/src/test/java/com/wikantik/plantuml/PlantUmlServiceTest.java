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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantUmlServiceTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"5\" height=\"5\"/></svg>";
    private static final RenderOutcome DRAWN = RenderOutcome.drawn( new PlantUmlDrawing( SVG, SVG ) );
    private static final String SRC = "@startuml\nA -> B\n@enduml";
    private static final String SRC2 = "@startuml\nC -> D\n@enduml";

    private final ExecutorService callers = Executors.newCachedThreadPool( r -> {
        final Thread t = new Thread( r );
        t.setDaemon( true );
        return t;
    } );

    @AfterEach
    void tearDown() {
        callers.shutdownNow();
    }

    private static PlantUmlSettings settings( final int threads, final int queue, final long timeoutMs, final long slowRetryMs,
                                              final int maxSvg ) {
        return new PlantUmlSettings( true, threads, queue, 1_048_576L, timeoutMs, maxSvg, slowRetryMs, 50 );
    }

    private static PlantUmlSettings plain() {
        return settings( 2, 4, 2_000L, 600_000L, 100_000 );
    }

    @Test
    void idlePoolThreadsExit() throws Exception {
        final AtomicReference< Thread > worker = new AtomicReference<>();
        final PlantUmlService s = new PlantUmlService( plain(), src -> { worker.set( Thread.currentThread() ); return DRAWN; },
            System::nanoTime, 50L );
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        final Thread t = worker.get();
        assertTrue( t != null && t.isDaemon() );
        t.join( 2_000L );
        assertFalse( t.isAlive(), "an idle render thread must exit so a redeployed engine's pool does not linger" );
    }

    @Test
    void productionPoolKeepsIdleThreadsForAMinuteThenLetsThemGo() {
        final PlantUmlService s = new PlantUmlService( plain() );
        assertEquals( 60_000L, s.keepAliveMs() );
        assertTrue( s.coreThreadsTimeOut() );
    }

    @Test
    void cachesDrawnOutcome() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> { calls.incrementAndGet(); return DRAWN; } );
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        assertEquals( 1, calls.get() );
    }

    @Test
    void syntaxErrorCached() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(),
            src -> { calls.incrementAndGet(); return RenderOutcome.refused( "syntax-error", "bad", 2 ); } );
        assertEquals( "syntax-error", s.render( SRC, 1_000 ).reason() );
        final RenderOutcome again = s.render( SRC, 1_000 );
        assertEquals( "syntax-error", again.reason() );
        assertEquals( 2, again.line() );
        assertEquals( 1, calls.get() );
    }

    @Test
    void transientEngineRefusalNotCached() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(),
            src -> { calls.incrementAndGet(); return RenderOutcome.transientRefusal( "render-failed" ); } );
        assertEquals( "render-failed", s.render( SRC, 1_000 ).reason() );
        s.render( SRC, 1_000 );
        assertEquals( 2, calls.get() );
    }

    @Test
    void throwingEngineBecomesTransientRenderFailed() {
        final PlantUmlService s = new PlantUmlService( plain(), src -> { throw new IllegalStateException( "boom" ); } );
        final RenderOutcome out = s.render( SRC, 1_000 );
        assertEquals( "render-failed", out.reason() );
        assertTrue( out.transientFailure() );
    }

    @Test
    void busyWhenPoolAndQueueFull() throws Exception {
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 1, 0, 5_000L, 600_000L, 100_000 ), src -> {
            calls.incrementAndGet();
            started.countDown();
            await( release );
            return DRAWN;
        } );
        final Future< RenderOutcome > first = callers.submit( () -> s.render( SRC, 5_000 ) );
        assertTrue( started.await( 2, TimeUnit.SECONDS ) );
        final RenderOutcome busy = s.render( SRC2, 5_000 );
        assertEquals( "busy", busy.reason() );
        assertTrue( busy.transientFailure() );
        release.countDown();
        assertTrue( first.get( 2, TimeUnit.SECONDS ).isDrawn() );
        // The single worker thread takes a moment after its task completes before it can take another (a
        // SynchronousQueue hand-off), so a retry may still meet busy; a cached busy would never clear.
        RenderOutcome later = s.render( SRC2, 5_000 );
        final long deadline = System.nanoTime() + 2_000_000_000L;
        while( "busy".equals( later.reason() ) && System.nanoTime() < deadline ) {
            Thread.onSpinWait();
            later = s.render( SRC2, 5_000 );
        }
        assertTrue( later.isDrawn(), "busy was not cached" );
    }

    @Test
    void concurrentSameSourceJoinsInFlight() throws Exception {
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> {
            calls.incrementAndGet();
            started.countDown();
            await( release );
            return DRAWN;
        } );
        final Future< RenderOutcome > a = callers.submit( () -> s.render( SRC, 2_000 ) );
        assertTrue( started.await( 2, TimeUnit.SECONDS ) );
        final Future< RenderOutcome > b = callers.submit( () -> s.render( SRC, 2_000 ) );
        final long deadline = System.nanoTime() + 2_000_000_000L;
        while( s.joinCount.get() < 1 && System.nanoTime() < deadline ) {
            Thread.onSpinWait();
        }
        assertEquals( 1, s.joinCount.get(), "b joined the in-flight render" );
        release.countDown();
        assertTrue( a.get( 2, TimeUnit.SECONDS ).isDrawn() );
        assertTrue( b.get( 2, TimeUnit.SECONDS ).isDrawn() );
        assertEquals( 1, calls.get() );
    }

    @Test
    void budgetShorterThanTimeoutGivesPageBudgetAndLaterCaches() throws Exception {
        final CountDownLatch release = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> {
            calls.incrementAndGet();
            await( release );
            return DRAWN;
        } );
        final RenderOutcome first = s.render( SRC, 50 );
        assertEquals( "page-budget", first.reason() );
        assertTrue( first.transientFailure() );
        release.countDown();
        // the render kept running; a later call joins it or hits the cache, never re-running the engine
        assertTrue( s.render( SRC, 2_000 ).isDrawn() );
        assertEquals( 1, calls.get() );
    }

    @Test
    void zeroBudgetIsPageBudgetWithoutSubmission() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> { calls.incrementAndGet(); return DRAWN; } );
        assertEquals( "page-budget", s.render( SRC, 0 ).reason() );
        assertEquals( 0, calls.get() );
        s.render( SRC, 1_000 );
        assertTrue( s.render( SRC, 0 ).isDrawn(), "cached outcome needs no budget" );
    }

    @Test
    void timeoutGivesTooSlowThenRetriesOnceAfterTtl() {
        final AtomicLong now = new AtomicLong( 1_000L );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 2, 4, 40L, 100L, 100_000 ), src -> {
            calls.incrementAndGet();
            sleepUntilInterrupted();
            return DRAWN;
        }, now::get );
        final RenderOutcome first = s.render( SRC, 5_000 );
        assertEquals( "too-slow", first.reason() );
        assertFalse( first.transientFailure() );
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason() );
        assertEquals( 1, calls.get(), "unexpired too-slow does not touch the pool" );

        now.addAndGet( 101_000_000L ); // past slowRetryMs
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason() );
        assertEquals( 2, calls.get(), "exactly one retry after the TTL" );

        now.addAndGet( 1_000_000_000_000L ); // far future: the second timeout made it permanent
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason() );
        assertEquals( 2, calls.get() );
    }

    @Test
    void retryThatSucceedsReplacesTooSlow() {
        final AtomicLong now = new AtomicLong( 1_000L );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 2, 4, 40L, 100L, 100_000 ), src -> {
            if( calls.incrementAndGet() == 1 ) {
                sleepUntilInterrupted();
            }
            return DRAWN;
        }, now::get );
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason() );
        now.addAndGet( 101_000_000L );
        assertTrue( s.render( SRC, 5_000 ).isDrawn() );
        assertTrue( s.render( SRC, 5_000 ).isDrawn() );
        assertEquals( 2, calls.get() );
    }

    @Test
    void disabledRefuses() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlSettings d = new PlantUmlSettings( false, 1, 1, 1_048_576L, 1_000L, 1_000, 1_000L, 10 );
        final PlantUmlService s = new PlantUmlService( d, src -> { calls.incrementAndGet(); return DRAWN; } );
        assertEquals( "plantuml-disabled", s.render( SRC, 1_000 ).reason() );
        assertEquals( 0, calls.get() );
    }

    @Test
    void policyRefusalNeverReachesEngine() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> { calls.incrementAndGet(); return DRAWN; } );
        assertEquals( "include", s.render( "@startuml\n!include /etc/passwd\n@enduml", 1_000 ).reason() );
        assertEquals( 0, calls.get() );
    }

    @Test
    void outputTooLarge() {
        final PlantUmlService s = new PlantUmlService( settings( 1, 1, 2_000L, 600_000L, 10 ), src -> DRAWN );
        final RenderOutcome out = s.render( SRC, 1_000 );
        assertEquals( "output-too-large", out.reason() );
        assertFalse( out.transientFailure() );
        assertEquals( "output-too-large", s.render( SRC, 1_000 ).reason() );
    }

    @Test
    void scrubRejectionIsTransient() {
        final RenderOutcome bad = RenderOutcome.drawn( new PlantUmlDrawing( "<svg", "<svg" ) );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> { calls.incrementAndGet(); return bad; } );
        final RenderOutcome out = s.render( SRC, 1_000 );
        assertEquals( "render-failed", out.reason() );
        assertTrue( out.transientFailure() );
        s.render( SRC, 1_000 );
        assertEquals( 2, calls.get() );
    }

    @Test
    void threadsAreDaemon() {
        final AtomicBoolean daemon = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference< String > name = new java.util.concurrent.atomic.AtomicReference<>();
        final PlantUmlService s = new PlantUmlService( plain(), src -> {
            daemon.set( Thread.currentThread().isDaemon() );
            name.set( Thread.currentThread().getName() );
            return DRAWN;
        } );
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        assertTrue( daemon.get() );
        assertTrue( name.get().startsWith( "wikantik-plantuml-" ) );
    }

    @Test
    void cacheEvictsLeastRecentlyUsed() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlSettings tiny = new PlantUmlSettings( true, 1, 1, 1_048_576L, 1_000L, 100_000, 1_000L, 1 );
        final PlantUmlService s = new PlantUmlService( tiny, src -> { calls.incrementAndGet(); return DRAWN; } );
        s.render( SRC, 1_000 );
        s.render( SRC2, 1_000 );
        s.render( SRC, 1_000 );
        assertEquals( 3, calls.get() );
    }

    @Test
    void realRenderScrubbed() {
        final PlantUmlService s = new PlantUmlService( PlantUmlSettings.defaults() );
        final RenderOutcome out = s.render( "@startuml\nskinparam handwritten false\nskinparam rectangle {\n BackgroundColor #red/blue\n}\nrectangle A [[#top]]\nA -> A\n@enduml", 20_000 );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
        assertFalse( out.drawing().lightSvg().contains( "<?plantuml-src" ) );
        final String light = out.drawing().lightSvg();
        assertTrue( light.contains( "id=\"wkf" ), "at least one prefixed id" );
        assertFalse( java.util.regex.Pattern.compile( "\\sid=\"(?!wkf)" ).matcher( light ).find(), "no unprefixed ids" );
        assertNotEquals( out.drawing().lightSvg(), out.drawing().darkSvg() );
    }

    @Test
    void lateCompletionAfterTimeoutIsDiscarded() throws Exception {
        final CountDownLatch release = new CountDownLatch( 1 );
        final CountDownLatch finished = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 1, 1, 40L, 600_000L, 100_000 ), src -> {
            calls.incrementAndGet();
            boolean done = false;
            while( !done ) { // ignores interrupts, like a PlantUML layout stuck in a loop
                try {
                    done = release.await( 5, TimeUnit.SECONDS );
                } catch( final InterruptedException e ) {
                    // deliberately ignored: the engine does not honour cancellation
                }
            }
            return DRAWN;
        } );
        s.completionHook = finished::countDown;
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason() );
        release.countDown();
        assertTrue( finished.await( 2, TimeUnit.SECONDS ) );
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason(), "the late drawing was discarded" );
        assertEquals( 1, calls.get() );
    }

    @Test
    void completionRacingTheTimeoutDecisionKeepsTheDrawing() throws Exception {
        final CountDownLatch release = new CountDownLatch( 1 );
        final CountDownLatch cacheWritten = new CountDownLatch( 1 );
        final CountDownLatch proceed = new CountDownLatch( 1 );
        final PlantUmlService s = new PlantUmlService( settings( 1, 1, 40L, 600_000L, 100_000 ), src -> {
            await( release );
            return DRAWN;
        } );
        // The waiter has decided the run timed out; let the render finish and cache before it takes the lock,
        // while the worker is held before the future completes.
        s.timeoutDecisionHook = () -> {
            release.countDown();
            await( cacheWritten );
        };
        s.completionHook = () -> {
            cacheWritten.countDown();
            await( proceed );
        };
        final Future< RenderOutcome > waiter = callers.submit( () -> s.render( SRC, 5_000 ) );
        final RenderOutcome out = waiter.get( 3, TimeUnit.SECONDS );
        proceed.countDown();
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() );
        assertTrue( s.render( SRC, 5_000 ).isDrawn(), "cache still holds the drawing" );
    }

    @Test
    void joinerThatLosesTheCancellationRaceSeesTooSlow() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 1, 1, 40L, 600_000L, 100_000 ), src -> {
            calls.incrementAndGet();
            sleepUntilInterrupted();
            return DRAWN;
        } );
        final AtomicInteger entrants = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference< Thread > second = new java.util.concurrent.atomic.AtomicReference<>();
        final CountDownLatch secondDone = new CountDownLatch( 1 );
        s.timeoutDecisionHook = () -> {
            if( entrants.incrementAndGet() == 1 ) {
                await( secondDone ); // the first to decide waits until the second has cancelled and returned
            } else {
                second.set( Thread.currentThread() );
            }
        };
        final Future< RenderOutcome > a = callers.submit( () -> finishing( s, second, secondDone ) );
        final Future< RenderOutcome > b = callers.submit( () -> finishing( s, second, secondDone ) );
        // whichever entered second cancels; the first must then report the same too-slow, not a failure
        final RenderOutcome ra = a.get( 3, TimeUnit.SECONDS );
        final RenderOutcome rb = b.get( 3, TimeUnit.SECONDS );
        assertEquals( "too-slow", ra.reason() );
        assertEquals( "too-slow", rb.reason() );
        assertEquals( 1, calls.get() );
    }

    private static RenderOutcome finishing( final PlantUmlService s, final java.util.concurrent.atomic.AtomicReference< Thread > second,
                                            final CountDownLatch secondDone ) {
        final RenderOutcome o = s.render( SRC, 5_000 );
        if( Thread.currentThread() == second.get() ) {
            secondDone.countDown();
        }
        return o;
    }

    @Test
    void waiterBlockedInGetSeesCancellationAsTransientFailure() throws Exception {
        final CountDownLatch release = new CountDownLatch( 1 );
        final PlantUmlService s = new PlantUmlService( plain(), src -> {
            await( release );
            return DRAWN;
        } );
        final AtomicBoolean cancelled = new AtomicBoolean();
        // another waiter's cancel(true) lands while this one is entering Future.get
        s.beforeGetHook = f -> {
            if( cancelled.compareAndSet( false, true ) ) {
                f.cancel( true );
            }
        };
        final RenderOutcome out = s.render( SRC, 5_000 );
        release.countDown();
        assertEquals( "render-failed", out.reason() );
        assertTrue( out.transientFailure() );
    }

    @Test
    void interruptedWaiterRestoresInterruptAndFailsTransiently() throws Exception {
        final CountDownLatch release = new CountDownLatch( 1 );
        final PlantUmlService s = new PlantUmlService( plain(), src -> {
            await( release );
            return DRAWN;
        } );
        final java.util.concurrent.atomic.AtomicReference< RenderOutcome > result = new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicBoolean stillInterrupted = new AtomicBoolean();
        final Future< ? > f = callers.submit( () -> {
            Thread.currentThread().interrupt();
            result.set( s.render( SRC, 5_000 ) );
            stillInterrupted.set( Thread.currentThread().isInterrupted() );
        } );
        f.get( 3, TimeUnit.SECONDS );
        release.countDown();
        assertEquals( "render-failed", result.get().reason() );
        assertTrue( result.get().transientFailure() );
        assertTrue( stillInterrupted.get() );
    }

    /**
     * R9 + I5: a render that never started is not too-slow (the job stays queued, runs and caches), but its caller does
     * not wait for it longer than the timeout: it gets a transient {@code busy}.
     */
    @Test
    void queuedJobCallerGetsBusyAfterTheTimeoutAndTheJobStillRunsAndCaches() throws Exception {
        final CountDownLatch firstStarted = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final CountDownLatch secondDone = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 1, 4, 200L, 600_000L, 100_000 ), src -> {
            calls.incrementAndGet();
            if( src.contains( "SLOW" ) ) {
                firstStarted.countDown();
                await( release );
            }
            return DRAWN;
        } );
        // The first caller leaves at once (page-budget), so nobody times the blocked render out.
        assertEquals( "page-budget", s.render( "@startuml\nSLOW -> B\n@enduml", 1 ).reason() );
        assertTrue( firstStarted.await( 2, TimeUnit.SECONDS ) );
        s.completionHook = () -> {
            if( calls.get() == 2 ) {
                secondDone.countDown();
            }
        };

        final long t0 = System.nanoTime();
        final RenderOutcome queued = s.render( SRC, 10_000 );
        final long waitedMs = ( System.nanoTime() - t0 ) / 1_000_000L;

        assertEquals( "busy", queued.reason() );
        assertTrue( queued.transientFailure() );
        assertTrue( waitedMs < 5_000L, "the caller waited " + waitedMs + " ms of its 10 s budget" );
        assertEquals( 1, s.queuedCount(), "the queued render was not cancelled" );
        assertEquals( 1, calls.get() );
        release.countDown();
        assertTrue( secondDone.await( 2, TimeUnit.SECONDS ) );
        assertTrue( s.render( SRC, 0 ).isDrawn(), "the queued render ran and cached its drawing" );
        assertEquals( 2, calls.get() );
    }

    /**
     * I5: when every pool thread is held by a render that is already past its timeout (Java cannot stop it), a new
     * render is refused as busy at once instead of queueing behind it for the caller's whole budget.
     */
    @Test
    void wedgedPoolFailsFastAsBusy() throws Exception {
        final CountDownLatch release = new CountDownLatch( 1 );
        final CountDownLatch zombieDone = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 1, 4, 40L, 600_000L, 100_000 ), src -> {
            if( calls.incrementAndGet() == 1 ) {
                ignoreInterruptsUntil( release );
            }
            return DRAWN;
        } );
        s.completionHook = zombieDone::countDown;
        assertEquals( "too-slow", s.render( SRC, 5_000 ).reason() );

        final long t0 = System.nanoTime();
        final RenderOutcome out = s.render( SRC2, 5_000 );
        final long waitedMs = ( System.nanoTime() - t0 ) / 1_000_000L;

        assertEquals( "busy", out.reason() );
        assertTrue( out.transientFailure() );
        assertTrue( waitedMs < 1_000L, "waited " + waitedMs + " ms" );
        assertEquals( 0, s.queuedCount(), "nothing was queued behind the overdue render" );
        release.countDown();
        assertTrue( zombieDone.await( 2, TimeUnit.SECONDS ) );
        assertTrue( s.render( SRC2, 5_000 ).isDrawn(), "busy was not cached; the freed thread draws" );
    }

    @Test
    void crashIsCachedThenRetriedOnceAfterTtlThenPermanent() {
        final AtomicLong now = new AtomicLong( 1_000L );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( plain(), src -> {
            calls.incrementAndGet();
            return RenderOutcome.refused( "render-crashed", "PlantUML failed while drawing the diagram", 0 );
        }, now::get );
        final RenderOutcome first = s.render( SRC, 1_000 );
        assertEquals( "render-crashed", first.reason() );
        assertFalse( first.transientFailure() );
        assertEquals( "render-crashed", s.render( SRC, 1_000 ).reason() );
        assertEquals( 1, calls.get(), "an unexpired crash does not re-render" );

        now.addAndGet( 600_000_000_001L ); // past slowRetryMs (10 min)
        assertEquals( "render-crashed", s.render( SRC, 1_000 ).reason() );
        assertEquals( 2, calls.get(), "exactly one retry after the TTL" );

        now.addAndGet( 1_000_000_000_000_000L ); // far future: the second crash made it permanent
        assertEquals( "render-crashed", s.render( SRC, 1_000 ).reason() );
        assertEquals( 2, calls.get() );
    }

    @Test
    void cacheIsBoundedByBytes() {
        final AtomicInteger calls = new AtomicInteger();
        final String big = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>" + "x".repeat( 4_000 ) + "</text></svg>";
        final RenderOutcome drawn = RenderOutcome.drawn( new PlantUmlDrawing( big, big ) );
        // Room for two scrubbed drawings (about 8 KB each), not three.
        final PlantUmlSettings s2 = new PlantUmlSettings( true, 1, 1, 1_048_576L, 1_000L, 100_000, 1_000L, 500, 20_000L );
        final PlantUmlService s = new PlantUmlService( s2, src -> { calls.incrementAndGet(); return drawn; } );
        final String src3 = "@startuml\nE -> F\n@enduml";
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        assertTrue( s.render( SRC2, 1_000 ).isDrawn() );
        assertTrue( s.render( src3, 1_000 ).isDrawn() );
        assertTrue( s.cachedBytes() <= 20_000L, "cached " + s.cachedBytes() + " bytes" );
        assertTrue( s.render( src3, 1_000 ).isDrawn() );
        assertTrue( s.render( SRC2, 1_000 ).isDrawn() );
        assertEquals( 3, calls.get(), "the two most recent stay cached" );
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        assertEquals( 4, calls.get(), "the least recently used drawing was evicted to stay under the byte bound" );
    }

    @Test
    void shippedDefaultsBoundTheHeap() {
        assertEquals( 524_288, PlantUmlSettings.defaults().maxSvgBytes() );
        assertEquals( 67_108_864L, PlantUmlSettings.defaults().cacheMaxBytes() );
    }

    private static void awaitQueued( final PlantUmlService s, final int n ) {
        final long deadline = System.nanoTime() + 2_000_000_000L;
        while( s.queuedCount() < n && System.nanoTime() < deadline ) {
            Thread.onSpinWait();
        }
        assertEquals( n, s.queuedCount() );
    }

    private static void await( final CountDownLatch latch ) {
        try {
            latch.await( 5, TimeUnit.SECONDS );
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( "interrupted", e );
        }
    }

    /** Blocks like a PlantUML layout stuck in a loop: cancellation (interrupts) is ignored. */
    private static void ignoreInterruptsUntil( final CountDownLatch release ) {
        boolean done = false;
        while( !done ) {
            try {
                done = release.await( 5, TimeUnit.SECONDS );
            } catch( final InterruptedException e ) {
                // deliberately ignored: the engine does not honour cancellation
            }
        }
    }

    private static void sleepUntilInterrupted() {
        try {
            Thread.sleep( 5_000 );
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( "cancelled", e );
        }
    }
}
