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

import com.wikantik.work.EmailDetail;
import com.wikantik.work.NotifySettings;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotificationStore;
import com.wikantik.work.WorkNotificationsService;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkWebhooksService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The dispatcher cycle; time comes from a mutable clock and waits are bounded awaits, never sleeps. */
class WorkNotifyDispatcherTest {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private final AtomicReference< Instant > now = new AtomicReference<>( Instant.parse( "2026-03-02T10:00:00Z" ) );

        void advance( final Duration d ) {
            now.updateAndGet( t -> t.plus( d ) );
        }

        @Override public ZoneId getZone() {
            return ZoneId.of( "UTC" );
        }

        @Override public Clock withZone( final ZoneId zone ) {
            return this;
        }

        @Override public Instant instant() {
            return now.get();
        }
    }

    private final WorkService service = mock( WorkService.class );
    private final WorkFeatures features = mock( WorkFeatures.class );
    private final WorkNotificationsService notices = mock( WorkNotificationsService.class );
    private final WorkWebhooksService hooks = mock( WorkWebhooksService.class );
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        when( service.features() ).thenReturn( features );
        when( features.notifications() ).thenReturn( notices );
        when( features.webhooks() ).thenReturn( hooks );
        settings( Duration.ofMillis( 20 ), Duration.ofHours( 1 ) );
    }

    private void settings( final Duration dispatch, final Duration email ) {
        when( notices.settings() ).thenReturn( new NotifySettings( dispatch, false, EmailDetail.SUMMARY, email, 8,
            "", false ) );
    }

    private static void await( final CountDownLatch latch ) throws InterruptedException {
        assertTrue( latch.await( 5, TimeUnit.SECONDS ), "the dispatcher did not get there in time" );
    }

    @Test
    void dispatchesOnSchedule() throws Exception {
        final CountDownLatch twice = new CountDownLatch( 2 );
        when( notices.dispatch( anyInt() ) ).thenAnswer( inv -> {
            twice.countDown();
            return 0;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( twice );
        }
    }

    @Test
    void drainsAFullBatchBacklogWithinOneCycle() throws Exception {
        settings( Duration.ofHours( 1 ), Duration.ofHours( 1 ) );
        final CountDownLatch drained = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        when( notices.dispatch( WorkNotifyDispatcher.FULL_BATCH ) ).thenAnswer( inv -> {
            final int n = calls.incrementAndGet();
            if ( n == 3 ) {
                drained.countDown();
            }
            return n < 3 ? 500 : 3;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( drained );
            assertEquals( 3, calls.get() );
        }
    }

    @Test
    void stopsAfterTenPasses() throws Exception {
        settings( Duration.ofHours( 1 ), Duration.ofHours( 1 ) );
        final CountDownLatch capped = new CountDownLatch( WorkNotifyDispatcher.MAX_PASSES_PER_CYCLE );
        final CountDownLatch emailed = new CountDownLatch( 1 );
        final AtomicInteger calls = new AtomicInteger();
        when( notices.dispatch( anyInt() ) ).thenAnswer( inv -> {
            calls.incrementAndGet();
            capped.countDown();
            return 500;
        } );
        when( notices.sendEmail( any() ) ).thenAnswer( inv -> {
            emailed.countDown();
            return 0;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( capped );
            await( emailed );   // the email step runs after the capped passes, so the count is final here
            assertEquals( WorkNotifyDispatcher.MAX_PASSES_PER_CYCLE, calls.get() );
        }
    }

    @Test
    void aFailedDispatchDoesNotSkipEmail() throws Exception {
        final CountDownLatch emailed = new CountDownLatch( 1 );
        when( notices.dispatch( anyInt() ) ).thenThrow( new SQLException( "database is down" ) );
        when( notices.sendEmail( any() ) ).thenAnswer( inv -> {
            emailed.countDown();
            return 0;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( emailed );
            assertTrue( d.alive(), "a failing step does not end the loop" );
        }
    }

    @Test
    void deliversWebhooksEachCycle() throws Exception {
        final CountDownLatch twice = new CountDownLatch( 2 );
        final AtomicReference< Instant > seen = new AtomicReference<>();
        when( hooks.deliver( any() ) ).thenAnswer( inv -> {
            seen.set( inv.getArgument( 0 ) );
            twice.countDown();
            return 0;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( twice );
            assertEquals( clock.instant(), seen.get(), "the cycle passes the service clock's time" );
        }
    }

    @Test
    void aFailedDeliveryDoesNotSkipEmail() throws Exception {
        final CountDownLatch emailed = new CountDownLatch( 1 );
        final CountDownLatch tried = new CountDownLatch( 1 );
        when( hooks.deliver( any() ) ).thenAnswer( inv -> {
            tried.countDown();
            throw new SQLException( "database is down" );
        } );
        when( notices.sendEmail( any() ) ).thenAnswer( inv -> {
            emailed.countDown();
            return 0;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( tried );
            await( emailed );
            assertTrue( d.alive() );
        }
    }

    @Test
    void emailRunsOnlyWhenItsIntervalHasPassed() throws Exception {
        final AtomicInteger emails = new AtomicInteger();
        final AtomicInteger cycles = new AtomicInteger();
        when( notices.dispatch( anyInt() ) ).thenAnswer( inv -> {
            cycles.incrementAndGet();
            return 0;
        } );
        when( notices.sendEmail( any() ) ).thenAnswer( inv -> {
            emails.incrementAndGet();
            return 0;
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            awaitCount( emails, 1 );   // the email step runs after the dispatch step, on the daemon thread
            assertEquals( 1, emails.get(), "the first cycle mails" );
            awaitCount( cycles, cycles.get() + 3 );
            assertEquals( 1, emails.get(), "the clock has not moved: not due again" );
            clock.advance( Duration.ofMinutes( 59 ) );
            awaitCount( cycles, cycles.get() + 3 );
            assertEquals( 1, emails.get(), "59 minutes of an hour" );
            clock.advance( Duration.ofMinutes( 2 ) );
            awaitCount( emails, 2 );
            assertEquals( 2, emails.get() );
        }
    }

    private static void awaitCount( final AtomicInteger counter, final int target ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
        while ( counter.get() < target && System.nanoTime() < deadline ) {
            TimeUnit.MILLISECONDS.sleep( 2 );   // bounded poll on a counter, not a wait for a time to pass
        }
        assertTrue( counter.get() >= target, "expected " + target + " but saw " + counter.get() );
    }

    @Test
    void closeStopsTheThread() throws Exception {
        final CountDownLatch started = new CountDownLatch( 1 );
        when( notices.dispatch( anyInt() ) ).thenAnswer( inv -> {
            started.countDown();
            return 0;
        } );
        final WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock );
        await( started );
        assertTrue( d.alive() );
        d.close();
        assertFalse( d.alive(), "this dispatcher's thread is gone after close" );
        assertFalse( d.alive() );
        d.close();   // idempotent
    }

    @Test
    void anUncaughtErrorLeavesItNotAlive() throws Exception {
        final CountDownLatch thrown = new CountDownLatch( 1 );
        when( notices.dispatch( anyInt() ) ).thenAnswer( inv -> {
            thrown.countDown();
            throw new StackOverflowError( "simulated" );
        } );
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler( ( t, e ) -> { } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( thrown );
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
            while ( d.alive() && System.nanoTime() < deadline ) {
                TimeUnit.MILLISECONDS.sleep( 5 );
            }
            assertFalse( d.alive(), "the thread is dead and says so" );
        } finally {
            Thread.setDefaultUncaughtExceptionHandler( previous );
        }
    }

    @Test
    void retentionRunsOnceADayAndAgainAtOnceWhenAPassWasFull() throws Exception {
        settings( Duration.ofMillis( 20 ), Duration.ofHours( 1 ) );
        final AtomicInteger prunes = new AtomicInteger();
        final CountDownLatch first = new CountDownLatch( 1 );
        final CountDownLatch second = new CountDownLatch( 2 );
        when( notices.prune( any() ) ).thenAnswer( inv -> {
            final int n = prunes.incrementAndGet();
            first.countDown();
            second.countDown();
            return new WorkNotificationStore.Pruned( n == 1 ? 20000 : 0, n == 1 );
        } );
        try ( WorkNotifyDispatcher d = WorkNotifyDispatcher.start( service, clock ) ) {
            await( first );
            await( second );   // the full first pass is followed by another on the next cycle
            TimeUnit.MILLISECONDS.sleep( 200 );
            assertEquals( 2, prunes.get(), "then once a day: nothing more while the clock stands still" );
        }
    }
}
