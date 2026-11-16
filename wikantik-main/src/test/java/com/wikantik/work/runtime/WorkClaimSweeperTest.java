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
package com.wikantik.work.runtime;

import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkClaimSweeperTest {

    private final WorkService service = mock( WorkService.class );
    private final WorkClaimsService claims = mock( WorkClaimsService.class );

    private static boolean sweeperThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch( t -> "work-claim-sweeper".equals( t.getName() ) && t.isAlive() );
    }

    private static void await( final CountDownLatch latch ) throws InterruptedException {
        assertTrue( latch.await( 5, TimeUnit.SECONDS ), "the sweeper did not get there in time" );
    }

    @Test
    void sweepsOnSchedule() throws Exception {
        final CountDownLatch twice = new CountDownLatch( 2 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            twice.countDown();
            return 0;
        } );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofMillis( 50 ) ) ) {
            await( twice );
        }
    }

    @Test
    void failureIsLoggedAndSweepingContinues() throws Exception {
        final CountDownLatch recovered = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            if ( calls.incrementAndGet() == 1 ) {
                throw new SQLException( "database is down" );
            }
            recovered.countDown();
            return 0;
        } );
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        final AbstractAppender appender = new AbstractAppender( "sweeperTest", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                events.add( e.toImmutable() );
            }
        };
        appender.start();
        final org.apache.logging.log4j.core.Logger log =
            ( org.apache.logging.log4j.core.Logger ) LogManager.getLogger( WorkClaimSweeper.class );
        final org.apache.logging.log4j.Level before = log.getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel( WorkClaimSweeper.class.getName(),
            org.apache.logging.log4j.Level.WARN );
        log.addAppender( appender );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofMillis( 50 ) ) ) {
            await( recovered );
        } finally {
            log.removeAppender( appender );
            org.apache.logging.log4j.core.config.Configurator.setLevel( WorkClaimSweeper.class.getName(), before );
            appender.stop();
        }
        assertTrue( events.stream().anyMatch( e -> e.getLevel() == org.apache.logging.log4j.Level.WARN
                && e.getThrown() != null && "database is down".equals( e.getThrown().getMessage() ) ),
            "the failed pass is logged at WARN with the exception: " + events );
    }

    @Test
    void aFullBatchIsFollowedImmediatelyByTheNextPass() throws Exception {
        final CountDownLatch drained = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            final int n = calls.incrementAndGet();
            if ( n == 3 ) {
                drained.countDown();
            }
            return n < 3 ? 500 : 3;
        } );
        // A one-hour interval: only the immediate follow-up passes can explain three calls.
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofHours( 1 ) ) ) {
            await( drained );
            assertEquals( 3, calls.get() );
        }
    }

    @Test
    void aBacklogIsCappedPerCycle() throws Exception {
        final CountDownLatch capped = new CountDownLatch( WorkClaimSweeper.MAX_PASSES_PER_CYCLE );
        final AtomicInteger calls = new AtomicInteger();
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            calls.incrementAndGet();
            capped.countDown();
            return 500;
        } );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofHours( 1 ) ) ) {
            await( capped );
            Thread.sleep( 200 );
            assertEquals( WorkClaimSweeper.MAX_PASSES_PER_CYCLE, calls.get(),
                "an endless backlog does not monopolise the thread; the rest waits for the next cycle" );
        }
    }

    @Test
    void closeStopsTheThread() throws Exception {
        final CountDownLatch started = new CountDownLatch( 1 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            started.countDown();
            return 0;
        } );
        final WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofHours( 1 ) );
        await( started );
        assertTrue( sweeperThreadAlive() );

        sweeper.close();
        assertFalse( sweeperThreadAlive(), "no live thread named work-claim-sweeper remains after close" );
        sweeper.close();   // idempotent
        assertFalse( sweeperThreadAlive() );
    }

    @Test
    void theThreadIsADaemon() throws Exception {
        final CountDownLatch started = new CountDownLatch( 1 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            started.countDown();
            return 0;
        } );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofHours( 1 ) ) ) {
            await( started );
            assertTrue( Thread.getAllStackTraces().keySet().stream()
                .anyMatch( t -> "work-claim-sweeper".equals( t.getName() ) && t.isDaemon() ) );
        }
    }

    @Test
    void anErrorIsNotSwallowed() throws Exception {
        final CountDownLatch thrown = new CountDownLatch( 1 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            thrown.countDown();
            throw new OutOfMemoryError( "simulated" );
        } );
        final AtomicInteger uncaught = new AtomicInteger();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler( ( t, e ) -> {
            if ( e instanceof OutOfMemoryError ) {
                uncaught.incrementAndGet();
            }
        } );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofMillis( 50 ) ) ) {
            await( thrown );
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
            while ( uncaught.get() == 0 && System.nanoTime() < deadline ) {
                Thread.sleep( 10 );
            }
            assertEquals( 1, uncaught.get(), "the Error reached the thread's uncaught handler" );
        } finally {
            Thread.setDefaultUncaughtExceptionHandler( previous );
        }
    }

    /** Runs {@code body} with the sweeper's log events at {@code level} and above captured. */
    private static List< LogEvent > capturing( final org.apache.logging.log4j.Level level, final ThrowingRunnable body )
            throws Exception {
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        final AbstractAppender appender = new AbstractAppender( "sweeperCapture", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                events.add( e.toImmutable() );
            }
        };
        appender.start();
        final org.apache.logging.log4j.core.Logger log =
            ( org.apache.logging.log4j.core.Logger ) LogManager.getLogger( WorkClaimSweeper.class );
        final org.apache.logging.log4j.Level before = log.getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel( WorkClaimSweeper.class.getName(), level );
        log.addAppender( appender );
        try {
            body.run();
        } finally {
            log.removeAppender( appender );
            org.apache.logging.log4j.core.config.Configurator.setLevel( WorkClaimSweeper.class.getName(), before );
            appender.stop();
        }
        return events;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void anErrorThatStopsTheThreadIsLoggedLoudlyAndTheSweeperReadsDead() throws Exception {
        final CountDownLatch thrown = new CountDownLatch( 1 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            thrown.countDown();
            throw new StackOverflowError( "simulated" );
        } );
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler( ( t, e ) -> { } );
        try {
            final List< LogEvent > events = capturing( org.apache.logging.log4j.Level.ERROR, () -> {
                try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofMillis( 50 ) ) ) {
                    assertTrue( sweeper.alive() || thrown.getCount() == 0 );
                    await( thrown );
                    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
                    while ( sweeper.alive() && System.nanoTime() < deadline ) {
                        Thread.sleep( 10 );
                    }
                    assertFalse( sweeper.alive(), "the thread is dead and says so" );
                }
            } );
            assertTrue( events.stream().anyMatch( e -> e.getLevel() == org.apache.logging.log4j.Level.ERROR
                && e.getMessage().getFormattedMessage().startsWith(
                    "work claim sweeper stopped — claims will not expire until restart" )
                && e.getThrown() instanceof StackOverflowError ), "ERROR naming the consequence: " + events );
        } finally {
            Thread.setDefaultUncaughtExceptionHandler( previous );
        }
    }

    @Test
    void aPassInterruptedByCloseIsNotAFailure() throws Exception {
        final CountDownLatch inPass = new CountDownLatch( 1 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            inPass.countDown();
            try {
                Thread.sleep( 60_000 );
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new SQLException( "interrupted while waiting for the database", e );
            }
            return 0;
        } );
        final List< LogEvent > events = capturing( org.apache.logging.log4j.Level.WARN, () -> {
            final WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofHours( 1 ) );
            await( inPass );
            sweeper.close();
            assertFalse( sweeper.alive() );
        } );
        assertTrue( events.stream().noneMatch( e -> e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN )
            && e.getMessage().getFormattedMessage().contains( "sweep failed" ) ), "no WARN on shutdown: " + events );
    }

    // ---- the standing sweep (phase 3a) runs in the same cycle

    @Test
    void aCycleRunsBothSweeps() throws Exception {
        final CountDownLatch expiry = new CountDownLatch( 2 );
        final CountDownLatch standing = new CountDownLatch( 2 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            expiry.countDown();
            return 0;
        } );
        when( claims.sweepStanding() ).thenAnswer( inv -> {
            standing.countDown();
            return 0;
        } );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofMillis( 50 ) ) ) {
            await( expiry );
            await( standing );
        }
    }

    @Test
    void aFailureInOneSweepDoesNotSkipTheOther() throws Exception {
        final CountDownLatch standing = new CountDownLatch( 2 );
        final CountDownLatch expiry = new CountDownLatch( 3 );
        when( service.claims() ).thenReturn( claims );
        when( claims.sweepExpiredClaims() ).thenAnswer( inv -> {
            expiry.countDown();
            throw new java.sql.SQLException( "expiry sweep down" );
        } );
        when( claims.sweepStanding() ).thenAnswer( inv -> {
            standing.countDown();
            if ( standing.getCount() == 1 ) {
                throw new IllegalStateException( "standing sweep down once" );
            }
            return 0;
        } );
        try ( WorkClaimSweeper sweeper = WorkClaimSweeper.start( service, Duration.ofMillis( 50 ) ) ) {
            await( standing );
            await( expiry );
        }
    }
}
