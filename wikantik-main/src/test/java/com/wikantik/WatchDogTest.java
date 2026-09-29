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
package com.wikantik;

import com.wikantik.api.core.Engine;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WatchDog}.
 */
class WatchDogTest {

    private Engine engine;

    @BeforeEach
    void setUp() {
        engine = MockEngineBuilder.engine().build();
    }

    @AfterEach
    void tearDown() {
        // Disable any watchdog created for the current thread so we don't leak
        // WatchDogThread instances across tests.
        try {
            final WatchDog wd = WatchDog.getCurrentWatchDog( engine );
            wd.disable();
        } catch( final Exception ignored ) {
            // best-effort cleanup
        }
    }

    // -----------------------------------------------------------------------
    // State-stack operations
    // -----------------------------------------------------------------------

    @Test
    void testEnterAndExitState_stackEmptyAfterExit() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t1" ) );
        wd.disable();

        Assertions.assertFalse( wd.isStateStackNotEmpty() );

        wd.enterState( "processing" );
        Assertions.assertTrue( wd.isStateStackNotEmpty() );

        wd.exitState();
        Assertions.assertFalse( wd.isStateStackNotEmpty() );
    }

    @Test
    void testEnterState_withTimeout_stackNotEmpty() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t2" ) );
        wd.disable();

        wd.enterState( "slow-op", 60 );
        Assertions.assertTrue( wd.isStateStackNotEmpty() );

        wd.exitState( "slow-op" );
        Assertions.assertFalse( wd.isStateStackNotEmpty() );
    }

    @Test
    void testEnterMultipleStates_stackGrowsAndShrinks() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t3" ) );
        wd.disable();

        wd.enterState( "outer" );
        wd.enterState( "inner" );
        Assertions.assertTrue( wd.isStateStackNotEmpty() );

        wd.exitState();  // pops "inner"
        wd.exitState();  // pops "outer"
        Assertions.assertFalse( wd.isStateStackNotEmpty() );
    }

    @Test
    void testExitState_onEmptyStack_doesNotThrow() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t4" ) );
        wd.disable();

        // Should log a warning but not throw
        Assertions.assertDoesNotThrow( (org.junit.jupiter.api.function.Executable) wd::exitState );
    }

    @Test
    void testExitState_wrongStateName_doesNotThrow() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t5" ) );
        wd.disable();

        wd.enterState( "correct" );
        // Passing wrong state name should log an error but not throw
        Assertions.assertDoesNotThrow( () -> wd.exitState( "wrong" ) );
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Test
    void testToString_idle_containsIdle() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t6" ) );
        wd.disable();

        Assertions.assertTrue( wd.toString().contains( "Idle" ) );
    }

    @Test
    void testToString_withState_containsStateName() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t7" ) );
        wd.disable();

        wd.enterState( "rendering" );
        Assertions.assertTrue( wd.toString().contains( "rendering" ) );

        wd.exitState();
    }

    // -----------------------------------------------------------------------
    // Watchable liveness
    // -----------------------------------------------------------------------

    @Test
    void testIsWatchableAlive_liveWatchable_returnsTrue() {
        final Watchable alive = makeWatchable( "alive-watchable", true );
        final WatchDog wd = new WatchDog( engine, alive );
        wd.disable();

        Assertions.assertTrue( wd.isWatchableAlive() );
    }

    @Test
    void testIsWatchableAlive_deadWatchable_returnsFalse() {
        final Watchable dead = makeWatchable( "dead-watchable", false );
        final WatchDog wd = new WatchDog( engine, dead );
        wd.disable();

        Assertions.assertFalse( wd.isWatchableAlive() );
    }

    // -----------------------------------------------------------------------
    // getCurrentWatchDog — factory / caching behaviour
    // -----------------------------------------------------------------------

    @Test
    void testGetCurrentWatchDog_returnsSameInstanceForSameThread() {
        final WatchDog wd1 = WatchDog.getCurrentWatchDog( engine );
        final WatchDog wd2 = WatchDog.getCurrentWatchDog( engine );
        Assertions.assertSame( wd1, wd2 );
    }

    // -----------------------------------------------------------------------
    // enable / disable lifecycle
    // -----------------------------------------------------------------------

    @Test
    void testEnableAfterDisable_doesNotThrow() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t8" ) );
        wd.disable();
        // Re-enabling should recreate the background thread without error
        Assertions.assertDoesNotThrow( wd::enable );
        // Clean up
        wd.disable();
    }

    @Test
    void testDisableTwice_doesNotThrow() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "t9" ) );
        wd.disable();
        // Second disable should be a no-op
        Assertions.assertDoesNotThrow( wd::disable );
    }

    // -----------------------------------------------------------------------
    // Timeout notification via custom Watchable
    // -----------------------------------------------------------------------

    @Test
    void testTimeoutExceeded_stateIsInStack() throws InterruptedException {
        final List< String > timedOutStates = new ArrayList<>();

        final Watchable watchable = new Watchable() {
            @Override public void timeoutExceeded( final String state ) { timedOutStates.add( state ); }
            @Override public String getName() { return "timeout-test"; }
            @Override public boolean isAlive() { return true; }
        };

        final WatchDog wd = new WatchDog( engine, watchable );
        wd.disable();

        // Enter a state that has already expired (expiry = 0 seconds)
        wd.enterState( "should-timeout", 0 );

        // Give a short pause so the expiry time is passed
        Thread.sleep( 50 );

        // The WatchDogThread is disabled; just verify the state IS in the stack
        Assertions.assertTrue( wd.isStateStackNotEmpty() );

        wd.exitState();
    }

    // -----------------------------------------------------------------------
    // Thread-backed WatchDog
    // -----------------------------------------------------------------------

    @Test
    void testWatchDogForThread_isWatchableAlive_whileThreadAlive() throws InterruptedException {
        final Thread t = new Thread( () -> {
            try { Thread.sleep( 2_000 ); } catch( final InterruptedException ignored ) {}
        } );
        t.start();

        final WatchDog wd = new WatchDog( engine, t );
        wd.disable();

        Assertions.assertTrue( wd.isWatchableAlive() );

        t.interrupt();
        t.join( 500 );
    }

    // -----------------------------------------------------------------------
    // check() — direct invocation (bypasses the 30s background-thread schedule)
    // -----------------------------------------------------------------------

    @Test
    void testCheck_emptyStack_logsWarningAndDoesNotThrow() {
        final WatchDog wd = new WatchDog( engine, makeWatchable( "empty-stack-watchable" ) );
        wd.disable();

        Assertions.assertFalse( wd.isStateStackNotEmpty() );
        Assertions.assertDoesNotThrow( wd::check );
    }

    @Test
    void testCheck_expiredState_invokesTimeoutExceeded() throws InterruptedException {
        final List< String > timedOutStates = new ArrayList<>();
        final Watchable watchable = new Watchable() {
            @Override public void timeoutExceeded( final String state ) { timedOutStates.add( state ); }
            @Override public String getName() { return "check-expired-watchable"; }
            @Override public boolean isAlive() { return true; }
        };

        final WatchDog wd = new WatchDog( engine, watchable );
        wd.disable();

        wd.enterState( "slow-task", 0 ); // expires immediately
        Thread.sleep( 20 );

        // Debug logging is off by default, so this also exercises
        // dumpStackTraceForWatchable()'s early-return branch.
        wd.check();

        Assertions.assertEquals( List.of( "slow-task" ), timedOutStates );
        // check() only peeks the stack — it never pops on timeout.
        Assertions.assertTrue( wd.isStateStackNotEmpty() );
        wd.exitState();
    }

    @Test
    void testCheck_expiredState_debugEnabled_dumpsStackTraceForMatchingThread() throws InterruptedException {
        final String threadName = Thread.currentThread().getName();
        final List< String > timedOutStates = new ArrayList<>();
        final Watchable watchable = new Watchable() {
            @Override public void timeoutExceeded( final String state ) { timedOutStates.add( state ); }
            @Override public String getName() { return threadName; } // matches current thread, so the dump loop finds it
            @Override public boolean isAlive() { return true; }
        };

        final WatchDog wd = new WatchDog( engine, watchable );
        wd.disable();
        wd.enterState( "debug-dump-task", 0 );
        Thread.sleep( 20 );

        final Logger log4jLogger = ( Logger ) org.apache.logging.log4j.LogManager.getLogger( WatchDog.class );
        final Level originalLevel = log4jLogger.getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel( WatchDog.class.getName(), Level.DEBUG );
        try {
            Assertions.assertDoesNotThrow( wd::check );
        } finally {
            org.apache.logging.log4j.core.config.Configurator.setLevel( WatchDog.class.getName(), originalLevel );
        }

        Assertions.assertEquals( List.of( "debug-dump-task" ), timedOutStates );
        wd.exitState();
    }

    @Test
    void testCheck_threadBackedWatchDog_timeoutExceededIsNoOp() throws InterruptedException {
        final Thread t = new Thread( () -> {
            try { Thread.sleep( 2_000 ); } catch( final InterruptedException ignored ) {}
        } );
        t.setName( "check-threadwrapper-target" );
        t.start();

        try {
            // Exercises WatchDog(Engine, Thread) -> ThreadWrapper, whose
            // timeoutExceeded() is a documented no-op (TODO in source).
            final WatchDog wd = new WatchDog( engine, t );
            wd.disable();
            wd.enterState( "wrapped-thread-task", 0 );
            Thread.sleep( 20 );

            Assertions.assertDoesNotThrow( wd::check );
            // No-op timeoutExceeded() never pops the stack.
            Assertions.assertTrue( wd.isStateStackNotEmpty() );
            wd.exitState();
        } finally {
            t.interrupt();
            t.join( 500 );
        }
    }

    // -----------------------------------------------------------------------
    // WatchDogThread.backgroundTask() — direct invocation via the shared
    // watcher-thread accessor, bypassing the real 30s schedule entirely.
    // -----------------------------------------------------------------------

    @Test
    void testBackgroundTask_checksLiveRegisteredWatchdogWithPendingState() throws Exception {
        final WatchDog wd = WatchDog.getCurrentWatchDog( engine );
        // Defensive: drain any state left behind by another test sharing this thread's kennel entry.
        while ( wd.isStateStackNotEmpty() ) {
            wd.exitState();
        }
        // getCurrentWatchDog() may return a cached instance that a prior test's tearDown()
        // already disabled (the watcher thread is shared, static state) — (re)enable it so
        // WatchDog.currentWatcherThread() is guaranteed non-null regardless of test order
        // (surefire 3.6.0 runs JUnit 5 methods in random order within a class).
        wd.enable();

        wd.enterState( "pending-background-check", 60 ); // non-empty, not yet expired
        try {
            final WikiBackgroundThread bg = WatchDog.currentWatcherThread();
            Assertions.assertNotNull( bg, "watcher thread should exist once a WatchDog has been created" );

            // Synchronous call — this is the exact call the real background thread
            // makes every 30s from WatchDogThread.backgroundTask(); calling it directly
            // exercises that line without waiting on the real schedule.
            Assertions.assertDoesNotThrow( ( org.junit.jupiter.api.function.Executable ) bg::backgroundTask );

            Assertions.assertTrue( wd.isStateStackNotEmpty(), "check() only peeks; state must still be on the stack" );
        } finally {
            wd.exitState();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Watchable makeWatchable( final String name ) {
        return makeWatchable( name, true );
    }

    private Watchable makeWatchable( final String name, final boolean alive ) {
        final Watchable w = mock( Watchable.class );
        when( w.getName() ).thenReturn( name );
        when( w.isAlive() ).thenReturn( alive );
        return w;
    }
}
