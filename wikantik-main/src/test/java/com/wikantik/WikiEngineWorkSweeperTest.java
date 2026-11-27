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

import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkService;
import com.wikantik.work.runtime.WorkClaimSweeper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The work claim sweeper as the engine runs it: started when work management is on, absent when it is off, closed on
 * engine shutdown, and visible on {@code /metrics} as {@code wikantik_work_sweeper_alive}, which drops to 0 when the
 * thread dies.
 */
class WikiEngineWorkSweeperTest {

    private MeterRegistry previous;
    private SimpleMeterRegistry meters;
    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        previous = MeterRegistryHolder.get();
        meters = new SimpleMeterRegistry();
        MeterRegistryHolder.set( meters );
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
        if ( previous != null ) {
            MeterRegistryHolder.set( previous );
        } else {
            MeterRegistryHolder.clear();
        }
    }

    private static boolean sweeperThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch( t -> "work-claim-sweeper".equals( t.getName() ) && t.isAlive() );
    }

    private static boolean waitUntil( final java.util.function.BooleanSupplier condition ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 15 );
        while ( !condition.getAsBoolean() && System.nanoTime() < deadline ) {
            Thread.sleep( 10 );
        }
        return condition.getAsBoolean();
    }

    private double alive() {
        return meters.get( WorkClaimSweeper.ALIVE_GAUGE ).gauge().value();
    }

    /** Work wired to a mocked service whose sweep does what {@code claims} says. */
    private void workOn( final WorkClaimsService claims ) {
        final WorkService service = Mockito.mock( WorkService.class );
        Mockito.when( service.claims() ).thenReturn( claims );
        Mockito.when( claims.claimExpiry() ).thenReturn( java.time.Duration.ofMinutes( 240 ) );
        engine.setWorkService( service );
        engine.startWorkClaimSweeper();
    }

    @Test
    void workOffStartsNoSweeper() {
        assertNull( engine.workService() );
        assertNull( engine.getManager( WorkClaimSweeper.class ) );
        assertNull( meters.find( WorkClaimSweeper.ALIVE_GAUGE ).gauge(), "no gauge when work is off" );
    }

    @Test
    void workOnStartsTheSweeperAndShutdownClosesIt() throws Exception {
        workOn( Mockito.mock( WorkClaimsService.class ) );
        final WorkClaimSweeper sweeper = engine.getManager( WorkClaimSweeper.class );
        assertNotNull( sweeper );
        assertTrue( sweeper.alive() );
        assertEquals( 1.0, alive() );

        engine.shutdown();

        assertFalse( sweeper.alive() );
        assertTrue( waitUntil( () -> !sweeperThreadAlive() ), "no live work-claim-sweeper thread after shutdown" );
        engine = null;
    }

    @Test
    void theGaugeDropsToZeroWhenTheThreadDies() throws Exception {
        final WorkClaimsService claims = Mockito.mock( WorkClaimsService.class );
        Mockito.when( claims.sweepExpiredClaims() ).thenThrow( new StackOverflowError( "simulated" ) );
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler( ( t, e ) -> { } );
        try {
            workOn( claims );
            assertTrue( waitUntil( () -> alive() == 0.0 ), "the gauge reads 0 once the thread has died" );
            assertFalse( engine.getManager( WorkClaimSweeper.class ).alive() );
        } finally {
            Thread.setDefaultUncaughtExceptionHandler( before );
        }
    }

    @Test
    void aSweeperThatFailedToStartReadsZeroRatherThanMissing() {
        engine.setWorkService( Mockito.mock( WorkService.class ) );
        engine.startWorkClaimSweeper( service -> {
            throw new IllegalStateException( "test: no threads left" );
        } );
        assertNull( engine.getManager( WorkClaimSweeper.class ) );
        assertNotNull( meters.find( WorkClaimSweeper.ALIVE_GAUGE ).gauge(), "the outage is visible on /metrics" );
        assertEquals( 0.0, alive() );
    }
}
