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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OntologyRuntimeTest {

    private final OntologyRebuildScheduler scheduler = mock( OntologyRebuildScheduler.class );
    private final OntologyPageSyncQueue pageSync = mock( OntologyPageSyncQueue.class );
    private final OntologyEntitySync entitySync = mock( OntologyEntitySync.class );
    private final OntologyModelManager manager = mock( OntologyModelManager.class );

    @Test
    void closeStopsTheTimerAndDrainsTheWorkersBeforeClosingTheDataset() {
        new OntologyRuntime( scheduler, pageSync, entitySync, manager ).close();

        final InOrder order = inOrder( scheduler, pageSync, entitySync, manager );
        order.verify( scheduler ).stop();
        order.verify( pageSync ).close();
        order.verify( entitySync ).close();
        order.verify( manager ).close();
    }

    @Test
    void aFailingStepIsLoggedAndTheRestStillClose() {
        doThrow( new IllegalStateException( "drain boom" ) ).when( pageSync ).close();
        doThrow( new IllegalStateException( "tdb2 boom" ) ).when( manager ).close();

        assertDoesNotThrow( () -> new OntologyRuntime( scheduler, pageSync, entitySync, manager ).close() );

        verify( entitySync ).close();
        verify( manager ).close();
    }

    @Test
    void aRuntimeWithoutIncrementalEntitySyncStillClosesTheDataset() {
        assertDoesNotThrow( () -> new OntologyRuntime( scheduler, pageSync, null, manager ).close() );
        verify( manager ).close();
    }

    @Test
    void aSaveStillQueuedAtShutdownIsWrittenBeforeTheDatasetCloses() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final PageCanonicalIdsDao dao = mock( PageCanonicalIdsDao.class );
        final PageManager pageManager = mock( PageManager.class );
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000030", "Solo", "T", "article", "ml", Instant.EPOCH, Instant.EPOCH ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        final OntologyPageSyncQueue queue = new OntologyPageSyncQueue(
                new OntologyPageSync( mgr, dao, pageManager, slug -> true ), 60_000L );
        queue.saved( "Solo" );
        final long before = mgr.committedWriteTransactions();

        new OntologyRuntime( new OntologyRebuildScheduler( null, 0, 0 ), queue, null, mgr ).close();

        assertEquals( 1L, mgr.committedWriteTransactions() - before, "the queued save was drained, then the dataset closed" );
    }

    @Test
    void engineShutdownClosesTheOntologyRuntime() {
        final TestEngine engine = TestEngine.build();
        final OntologyRuntime runtime = mock( OntologyRuntime.class );
        doThrow( new IllegalStateException( "close boom" ) ).when( runtime ).close();
        engine.setManager( OntologyRuntime.class, runtime );

        assertDoesNotThrow( engine::shutdown, "a failing ontology close must not abort engine shutdown" );
        verify( runtime ).close();
    }
}
