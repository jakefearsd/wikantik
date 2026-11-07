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
package com.wikantik.collab.adapter;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.collab.CollabLogCapture;
import com.wikantik.collab.CollabMetrics;
import com.wikantik.collab.CollabSessionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CollabWiringTest {

    private TestEngine engine;
    private CollabWiring wiring;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        if( wiring != null ) {
            wiring.close();
        }
        engine.stop();
    }

    @Test
    void registryIsAbsentUntilWired() {
        assertEquals( Optional.empty(), CollabWiring.registryFor( engine ) );
    }

    @Test
    void registryForANonWikiEngineIsEmpty() {
        assertEquals( Optional.empty(), CollabWiring.registryFor( mock( Engine.class ) ) );
        assertEquals( Optional.empty(), CollabWiring.registryFor( null ) );
    }

    @Test
    void withoutADatasourceNothingIsWired() {
        assertTrue( CollabWiring.wire( engine, new Properties(), null, engine.getManager( PageManager.class ) ).isEmpty() );
        assertEquals( Optional.empty(), CollabWiring.registryFor( engine ) );
    }

    @Test
    void collaborationIsDisabledByDefault() {
        wiring = CollabWiring.wire( engine, new Properties(), mock( DataSource.class ),
                engine.getManager( PageManager.class ) ).orElseThrow();
        final CollabSessionRegistry registry = CollabWiring.registryFor( engine ).orElseThrow();
        assertFalse( registry.isEnabled() );
    }

    @Test
    void tickRunsEvenWhenCollaborationWasDisabledAtBootAndEnabledLater() {
        wiring = CollabWiring.wire( engine, new Properties(), mock( DataSource.class ),
                engine.getManager( PageManager.class ) ).orElseThrow();
        assertTrue( wiring.isTicking() );
        CollabWiring.registryFor( engine ).orElseThrow().setEnabled( true );
        assertTrue( wiring.isTicking() );
    }

    @Test
    void aWireFailureLeavesNoRegistryBehind() {
        assertThrows( RuntimeException.class,
                () -> CollabWiring.wire( engine, new Properties(), mock( DataSource.class ), null ) );
        assertEquals( Optional.empty(), CollabWiring.registryFor( engine ) );
    }

    @Test
    void enabledConfigTicksUntilClosed() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.enabled", "true" );
        wiring = CollabWiring.wire( engine, p, mock( DataSource.class ), engine.getManager( PageManager.class ) ).orElseThrow();
        assertTrue( CollabWiring.registryFor( engine ).orElseThrow().isEnabled() );
        assertTrue( wiring.isTicking() );
        wiring.close();
        assertFalse( wiring.isTicking() );
    }

    @Test
    void noMeterRegistryMeansNoOpMetricsAndOneWarn() {
        final MeterRegistry saved = MeterRegistryHolder.get();
        MeterRegistryHolder.clear();
        try {
            final List< LogEvent > warnings = CollabLogCapture.warnings( () -> wiring = CollabWiring.wire( engine,
                new Properties(), mock( DataSource.class ), engine.getManager( PageManager.class ) ).orElseThrow() );
            assertSame( CollabMetrics.NONE, wiring.registry().metrics() );
            assertEquals( 1, warnings.stream().filter( e -> e.getLevel() == Level.WARN ).filter( e -> e.getMessage()
                .getFormattedMessage().contains( "MeterRegistry" ) ).count(), warnings.toString() );
        } finally {
            MeterRegistryHolder.set( saved );
        }
    }

    @Test
    void aMeterRegistryGetsTheCollabMetersAndGauges() {
        final MeterRegistry saved = MeterRegistryHolder.get();
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MeterRegistryHolder.set( meters );
        try {
            wiring = CollabWiring.wire( engine, new Properties(), mock( DataSource.class ),
                engine.getManager( PageManager.class ) ).orElseThrow();
            wiring.registry().metrics().push( CollabMetrics.ACCEPTED );
            assertEquals( 1.0, meters.get( "wikantik_collab_push_total" ).tag( "result", "accepted" ).counter().count() );
            assertEquals( 0.0, meters.get( "wikantik_collab_sessions_active" ).gauge().value() );
            assertEquals( 0.0, meters.get( "wikantik_collab_participants_active" ).gauge().value() );
        } finally {
            MeterRegistryHolder.set( saved );
        }
    }

    @Test
    void anErrorEscapingATickDoesNotEscapeTheScheduledTask() {
        final Runnable task = CollabWiring.guarded( () -> { throw new StackOverflowError( "boom" ); } );
        assertDoesNotThrow( task::run );
    }

    @Test
    void closeStoresTheDraftMarkersOfTheLiveSessions() throws Exception {
        engine.stop();
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        final String page = "CollabWiringClose";
        engine.saveText( page, "hello\n" );
        final com.wikantik.collab.testing.InMemoryCollabStore store = new com.wikantik.collab.testing.InMemoryCollabStore();
        wiring = CollabTestWiring.wire( engine, store );
        final com.wikantik.api.core.Session admin = engine.adminSession();
        final String key = com.wikantik.auth.AuthorKeys.authorKeyFor( admin );
        final CollabSessionRegistry.Joined j = assertInstanceOf( CollabSessionRegistry.Joined.class,
            wiring.registry().join( page, new com.wikantik.collab.model.JoinRequest( key, null, admin, "Admin", null,
                null ) ) );
        final com.wikantik.collab.CollabSession.View v = j.session().view();
        assertEquals( 200, j.session().push( j.response().participantId(), key, v.head(), java.util.List.of(
            new com.wikantik.collab.model.Update( j.response().clientId(),
                com.wikantik.collab.text.ChangeSet.replace( v.doc().length(), 0, 0, "x" ) ) ) ).status() );
        assertFalse( store.session( j.response().sessionId() ).orElseThrow().hasUnpublished(), "not stored yet" );

        wiring.close();
        wiring = null;

        final com.wikantik.collab.model.SessionRecord stored = store.session( j.response().sessionId() ).orElseThrow();
        assertTrue( stored.hasUnpublished(), "a restart reads the draft as unpublished" );
        assertNotNull( stored.lastEditAt() );
        engine.deleteQuietly( page );
    }

    @Test
    void aFailingTickDoesNotEscapeEither() {
        final Runnable task = CollabWiring.guarded( () -> { throw new IllegalStateException( "boom" ); } );
        assertDoesNotThrow( task::run );
    }
}
