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
import com.wikantik.collab.CollabSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
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
    void anErrorEscapingATickDoesNotEscapeTheScheduledTask() {
        final Runnable task = CollabWiring.guarded( () -> { throw new StackOverflowError( "boom" ); } );
        assertDoesNotThrow( task::run );
    }

    @Test
    void aFailingTickDoesNotEscapeEither() {
        final Runnable task = CollabWiring.guarded( () -> { throw new IllegalStateException( "boom" ); } );
        assertDoesNotThrow( task::run );
    }
}
