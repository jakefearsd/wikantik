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
import com.wikantik.work.NotifySettings;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotificationsService;
import com.wikantik.work.WorkService;
import com.wikantik.work.runtime.WorkNotifyDispatcher;
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
 * The work notification dispatcher as the engine runs it: started when work management is on, absent when it is off,
 * closed on engine shutdown, and visible on {@code /metrics} as {@code wikantik_work_dispatcher_alive}.
 */
class WikiEngineWorkDispatcherTest {

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

    private static boolean waitUntil( final java.util.function.BooleanSupplier condition ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 15 );
        while ( !condition.getAsBoolean() && System.nanoTime() < deadline ) {
            Thread.sleep( 10 );
        }
        return condition.getAsBoolean();
    }

    private double alive() {
        return meters.get( WorkNotifyDispatcher.ALIVE_GAUGE ).gauge().value();
    }

    /** Work wired to a mocked service whose notifications do nothing and are due once an hour. */
    private void workOn() {
        final WorkService service = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        final WorkNotificationsService notifications = Mockito.mock( WorkNotificationsService.class );
        Mockito.when( service.features() ).thenReturn( features );
        Mockito.when( features.notifications() ).thenReturn( notifications );
        Mockito.when( notifications.settings() ).thenReturn( new NotifySettings( java.time.Duration.ofHours( 1 ), false,
            com.wikantik.work.EmailDetail.SUMMARY, java.time.Duration.ofHours( 1 ), 8, "", false ) );
        engine.setWorkService( service );
        engine.startWorkNotifyDispatcher();
    }

    @Test
    void startsNothingWhenWorkIsOff() {
        assertNull( engine.workService() );
        assertNull( engine.getManager( WorkNotifyDispatcher.class ) );
        assertNull( meters.find( WorkNotifyDispatcher.ALIVE_GAUGE ).gauge(), "no gauge when work is off" );
    }

    @Test
    void startsWhenWorkIsWired() {
        workOn();
        final WorkNotifyDispatcher dispatcher = engine.getManager( WorkNotifyDispatcher.class );
        assertNotNull( dispatcher );
        assertTrue( dispatcher.alive() );
        assertEquals( 1.0, alive() );
    }

    @Test
    void shutdownClosesIt() throws Exception {
        workOn();
        final WorkNotifyDispatcher dispatcher = engine.getManager( WorkNotifyDispatcher.class );
        engine.shutdown();
        assertFalse( dispatcher.alive() );
        assertTrue( waitUntil( () -> !dispatcher.alive() ), "this engine's dispatcher thread is gone after shutdown" );
        engine = null;
    }

    @Test
    void theGaugeReadsZeroAfterClose() {
        workOn();
        engine.getManager( WorkNotifyDispatcher.class ).close();
        assertEquals( 0.0, alive() );
    }

    @Test
    void aStartFailureDoesNotStopTheWiki() {
        engine.setWorkService( Mockito.mock( WorkService.class ) );
        engine.startWorkNotifyDispatcher( service -> {
            throw new IllegalStateException( "test: no threads left" );
        } );
        assertNull( engine.getManager( WorkNotifyDispatcher.class ) );
        assertNotNull( meters.find( WorkNotifyDispatcher.ALIVE_GAUGE ).gauge(), "the outage is visible on /metrics" );
        assertEquals( 0.0, alive() );
    }
}
