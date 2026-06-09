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
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class OntologyRebuildCoordinatorTest {

    private static final UUID N1 = UUID.fromString( "00000000-0000-0000-0000-0000000000e1" );

    private OntologyRebuildCoordinator coordinator( final OntologyModelManager mgr, final boolean enabled ) {
        final List< KgNode > nodes = List.of(
                new KgNode( N1, "X", "concept", null, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) );
        return new OntologyRebuildCoordinator( mgr, () -> nodes, List::of, List::of, enabled );
    }

    @Test
    void rebuildRunsAsyncAndMaterializesGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator svc = coordinator( mgr, true );
        final OntologyRebuildStatus started = svc.triggerRebuild();
        assertEquals( "STARTING", started.state() );

        Awaitility.await().atMost( 5, TimeUnit.SECONDS )
                .until( () -> "IDLE".equals( svc.status().state() ) );
        assertTrue( mgr.namedGraphExists( Iris.entity( N1 ) ), "entity graph materialized after async rebuild" );
        assertEquals( 1L, svc.status().graphCount() );
    }

    @Test
    void secondTriggerWhileRunningConflicts() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        // A slow source so the first rebuild is still running when we trigger again.
        final OntologyRebuildCoordinator svc = new OntologyRebuildCoordinator( mgr,
                () -> {
                    try {
                        Thread.sleep( 300 );
                    } catch ( final InterruptedException ignored ) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                },
                List::of, List::of, true );
        svc.triggerRebuild();
        assertThrows( OntologyRebuildCoordinator.ConflictException.class, svc::triggerRebuild );
        Awaitility.await().atMost( 5, TimeUnit.SECONDS ).until( () -> "IDLE".equals( svc.status().state() ) );
    }

    @Test
    void disabledCoordinatorRefusesAndReportsDisabled() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator svc = coordinator( mgr, false );
        assertThrows( OntologyRebuildCoordinator.DisabledException.class, svc::triggerRebuild );
        assertEquals( false, svc.status().enabled() );
    }

    @Test
    void postRebuildHookRunsAfterSuccessfulRebuild() throws InterruptedException {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator svc = coordinator( mgr, true );
        final CountDownLatch hookRan = new CountDownLatch( 1 );
        svc.onRebuildComplete( hookRan::countDown );
        svc.triggerRebuild();
        assertTrue( hookRan.await( 10, TimeUnit.SECONDS ), "hook should have fired within 10 s" );
    }

    @Test
    void throwingHookDoesNotPoisonRebuildOrOtherHooks() throws InterruptedException {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator svc = coordinator( mgr, true );
        final CountDownLatch secondHookRan = new CountDownLatch( 1 );
        svc.onRebuildComplete( () -> { throw new IllegalStateException( "boom" ); } );
        svc.onRebuildComplete( secondHookRan::countDown );
        svc.triggerRebuild();
        assertTrue( secondHookRan.await( 10, TimeUnit.SECONDS ), "second hook should fire despite first hook throwing" );
        Awaitility.await().atMost( 10, TimeUnit.SECONDS ).until( () -> "IDLE".equals( svc.status().state() ) );
        assertNull( svc.status().lastError(), "rebuild must not record an error when only a hook throws" );
    }
}
