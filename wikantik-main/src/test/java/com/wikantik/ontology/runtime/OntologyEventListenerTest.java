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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyEventListenerTest {

    private static final String CID = "01CANON0000000000000000020";

    @Mock OntologyPageSyncQueue queue;
    @Mock ScheduledExecutorService scheduler; // never executes — the test drains explicitly

    @Test
    void postSaveEndQueuesASave() {
        new OntologyEventListener( queue ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Foo" ) );
        verify( queue ).saved( "Foo" );
    }

    @Test
    void pageDeletedQueuesADelete() {
        new OntologyEventListener( queue ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Foo" ) );
        verify( queue ).deleted( "Foo" );
    }

    @Test
    void renameQueuesARename() {
        new OntologyEventListener( queue ).actionPerformed(
                new WikiPageRenameEvent( this, "Old", "New" ) );
        verify( queue ).renamed( "Old", "New" );
    }

    @Test
    void bareInPhasePostSaveIsIgnored_onlyPostSaveEndProjects() {
        new OntologyEventListener( queue ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "Foo" ) );
        verifyNoInteractions( queue );
    }

    @Test
    void savesAreNotWrittenToTheStoreOnTheSavingThreadAndCoalesceIntoOneProjection() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final PageCanonicalIdsDao dao = mock( PageCanonicalIdsDao.class );
        final PageManager pageManager = mock( PageManager.class );
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( new PageCanonicalIdsDao.Row(
                CID, "Solo", "T", "article", "ml", Instant.EPOCH, Instant.EPOCH ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        final OntologyPageSyncQueue realQueue = new OntologyPageSyncQueue(
                new OntologyPageSync( mgr, dao, pageManager, slug -> true ), 500L, scheduler );
        final OntologyEventListener listener = new OntologyEventListener( realQueue );
        final long before = mgr.committedWriteTransactions();

        for ( int i = 0; i < 3; i++ ) {
            listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Solo" ) );
        }

        assertEquals( 0L, mgr.committedWriteTransactions() - before,
                "the saving (HTTP) thread must not pay for TDB2 commits" );
        realQueue.drainNow();   // what the worker thread runs coalesceMs later
        assertEquals( 1L, mgr.committedWriteTransactions() - before, "three saves, one projection, one commit" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );
    }
}
