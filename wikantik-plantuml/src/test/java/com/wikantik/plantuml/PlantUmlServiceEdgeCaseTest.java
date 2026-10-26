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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remaining {@link PlantUmlService} paths: unexpected failures, size bounds per variant, clock edge, single-flight. */
class PlantUmlServiceEdgeCaseTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"5\" height=\"5\"/></svg>";
    private static final RenderOutcome DRAWN = RenderOutcome.drawn( new PlantUmlDrawing( SVG, SVG ) );
    private static final String SRC = "@startuml\nA -> B\n@enduml";

    private final ExecutorService callers = Executors.newCachedThreadPool( r -> {
        final Thread t = new Thread( r );
        t.setDaemon( true );
        return t;
    } );

    @AfterEach
    void tearDown() {
        callers.shutdownNow();
    }

    private static PlantUmlSettings settings( final int threads, final int maxSvg ) {
        return new PlantUmlSettings( true, threads, 16, 1_048_576L, 5_000L, maxSvg, 600_000L, 50 );
    }

    @Test
    void nullSourceIsATransientRenderFailureNotAnException() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 2, 100_000 ), src -> { calls.incrementAndGet(); return DRAWN; } );

        final RenderOutcome out = s.render( null, 1_000 );

        assertEquals( "render-failed", out.reason() );
        assertTrue( out.transientFailure() );
        assertEquals( 0, calls.get(), "the engine is never reached" );
        assertEquals( 0L, s.cachedBytes(), "and nothing is cached" );
    }

    @Test
    void aMissingVariantIsOutputTooLarge() {
        final PlantUmlService s = new PlantUmlService( settings( 2, 100_000 ),
            src -> RenderOutcome.drawn( new PlantUmlDrawing( SVG, null ) ) );
        final RenderOutcome out = s.render( SRC, 1_000 );
        assertEquals( "output-too-large", out.reason() );
        assertFalse( out.transientFailure(), "deterministic, so cached" );
    }

    @Test
    void eitherVariantOverTheCapRefusesTheWholeDrawing() {
        final String big = "<svg>" + "x".repeat( 400 ) + "</svg>";
        final PlantUmlService darkBig = new PlantUmlService( settings( 2, 200 ),
            src -> RenderOutcome.drawn( new PlantUmlDrawing( "<svg/>", big ) ) );
        assertEquals( "output-too-large", darkBig.render( SRC, 1_000 ).reason() );
        final PlantUmlService lightBig = new PlantUmlService( settings( 2, 200 ),
            src -> RenderOutcome.drawn( new PlantUmlDrawing( big, "<svg/>" ) ) );
        assertEquals( "output-too-large", lightBig.render( SRC, 1_000 ).reason() );
    }

    @Test
    void sizeCapIsMeasuredInUtf8BytesOnTheRawSvg() {
        final String wide = "<svg>" + "€".repeat( 40 ) + "</svg>"; // 52 chars, 132 bytes
        final PlantUmlService s = new PlantUmlService( settings( 2, 100 ),
            src -> RenderOutcome.drawn( new PlantUmlDrawing( wide, wide ) ) );
        assertEquals( "output-too-large", s.render( SRC, 1_000 ).reason() );
    }

    @Test
    void aFirstCrashThatLandsOnTheZeroNanoTimeStillExpiresAndRetries() {
        // expiry() maps a computed deadline of exactly 0 (which OutcomeCache reads as "never expires") to 1
        final PlantUmlSettings st = settings( 2, 100_000 );
        final AtomicLong now = new AtomicLong( -st.slowRetryMs() * 1_000_000L );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( st, src -> {
            calls.incrementAndGet();
            return RenderOutcome.refused( "render-crashed", "PlantUML failed while drawing the diagram", 0 );
        }, now::get );

        assertEquals( "render-crashed", s.render( SRC, 1_000 ).reason() );
        assertEquals( "render-crashed", s.render( SRC, 1_000 ).reason() );
        assertEquals( 1, calls.get(), "unexpired" );

        now.set( 5L );
        assertEquals( "render-crashed", s.render( SRC, 1_000 ).reason() );
        assertEquals( 2, calls.get(), "the zero-deadline entry was expiring, not permanent" );
    }

    @Test
    void manyThreadsRequestingOneSourceShareOneRenderAndOneOutcome() throws Exception {
        final int n = 12;
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 4, 100_000 ), src -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                assertTrue( release.await( 10, TimeUnit.SECONDS ) );
            } catch( final InterruptedException e ) {
                Thread.currentThread().interrupt();
            }
            return DRAWN;
        } );

        final List< Future< RenderOutcome > > results = new ArrayList<>();
        results.add( callers.submit( () -> s.render( SRC, 8_000 ) ) );
        assertTrue( started.await( 5, TimeUnit.SECONDS ) );
        for( int i = 1; i < n; i++ ) {
            results.add( callers.submit( () -> s.render( SRC, 8_000 ) ) );
        }
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while( s.joinCount.get() < n - 1 && System.nanoTime() < deadline ) {
            Thread.onSpinWait();
        }
        assertEquals( n - 1, s.joinCount.get(), "every other caller joined the single in-flight render" );
        release.countDown();

        RenderOutcome first = null;
        for( final Future< RenderOutcome > f : results ) {
            final RenderOutcome out = f.get( 10, TimeUnit.SECONDS );
            assertTrue( out.isDrawn() );
            if( first == null ) {
                first = out;
            }
            assertSame( first, out, "all callers receive the very same outcome" );
        }
        assertEquals( 1, calls.get(), "exactly one render ran" );
        assertSame( first, s.render( SRC, 1_000 ), "and the result is now served from the cache" );
        assertEquals( 1, calls.get() );
    }

    @Test
    void differentSourcesDoNotShareARender() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = new PlantUmlService( settings( 2, 100_000 ), src -> { calls.incrementAndGet(); return DRAWN; } );
        assertTrue( s.render( SRC, 1_000 ).isDrawn() );
        assertTrue( s.render( SRC + "\n' different", 1_000 ).isDrawn() );
        assertEquals( 2, calls.get() );
    }
}
