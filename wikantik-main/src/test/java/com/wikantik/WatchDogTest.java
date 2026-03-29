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
