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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class OntologyPageSyncQueueTest {

    private static final String CID = "01CANON0000000000000000040";
    private static final String CID_2 = "01CANON0000000000000000041";

    @Mock PageManager pageManager;
    @Mock PageCanonicalIdsDao dao;
    @Mock ScheduledExecutorService scheduler; // never executes — tests call drainNow() directly

    private OntologyModelManager mgr;
    private OntologyPageSyncQueue queue;

    private static Optional< PageCanonicalIdsDao.Row > row( final String cid, final String slug ) {
        return Optional.of( new PageCanonicalIdsDao.Row( cid, slug, "T", "article", "ml", Instant.EPOCH, Instant.EPOCH ) );
    }

    @BeforeEach
    void setUp() {
        mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( any() ) ).thenReturn( Optional.empty() );
        when( dao.findByCanonicalId( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), anyInt() ) )
                .thenReturn( "---\ntags: [graphs]\n---\nbody" );
        queue = new OntologyPageSyncQueue( new OntologyPageSync( mgr, dao, pageManager, slug -> true ), 500L, scheduler );
    }

    /** Projects {@code slug} the way an earlier JVM would have: the graph exists, this queue has no memo of it. */
    private void projectedBeforeThisRun( final String cid, final String slug ) {
        when( dao.findBySlug( slug ) ).thenReturn( row( cid, slug ) );
        new OntologyPageSync( mgr, dao, pageManager, s -> true ).onPageSaved( slug );
        assertTrue( mgr.namedGraphExists( Iris.page( cid ) ) );
    }

    private String projectedUrl( final String cid ) {
        final Model g = mgr.namedGraphSnapshot( Iris.page( cid ) );
        final List< String > urls = g.listObjectsOfProperty( g.createProperty( "https://schema.org/url" ) )
                .mapWith( n -> n.asResource().getURI() ).toList();
        return urls.isEmpty() ? null : urls.get( 0 );
    }

    @Test
    void rapidSavesOfOnePageScheduleOneDrainAndProjectOnce() {
        when( dao.findBySlug( "Solo" ) ).thenReturn( row( CID, "Solo" ) );
        final long before = mgr.committedWriteTransactions();

        for ( int i = 0; i < 5; i++ ) {
            queue.saved( "Solo" );
        }
        verify( scheduler, times( 1 ) ).schedule( any( Runnable.class ), anyLong(), any( TimeUnit.class ) );
        queue.drainNow();

        verify( dao, times( 1 ) ).findBySlug( "Solo" );
        assertEquals( 1L, mgr.committedWriteTransactions() - before );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );
    }

    @Test
    void aSaveFollowedByADeleteLeavesThePageGraphAbsent() {
        projectedBeforeThisRun( CID, "Solo" );   // the delete event can still resolve the id ...

        queue.saved( "Solo" );
        queue.deleted( "Solo" );
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.empty() );   // ... but by drain time the row is gone
        queue.drainNow();

        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ), "a stale queued save must not outlive the delete" );
    }

    @Test
    void aDeleteThenARecreateRemovesTheOldGraphAndProjectsTheNewOne() {
        projectedBeforeThisRun( CID, "Solo" );

        queue.deleted( "Solo" );
        when( dao.findBySlug( "Solo" ) ).thenReturn( row( CID_2, "Solo" ) );   // recreated under a new id
        queue.saved( "Solo" );
        queue.drainNow();

        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ), "the deleted page's graph is removed" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID_2 ) ), "the recreated page is projected" );
    }

    @Test
    void aDeleteOfARenamedPageKeepsItsGraph() {
        projectedBeforeThisRun( CID, "Old" );
        when( dao.findByCanonicalId( CID ) ).thenReturn( row( CID, "New" ) );   // the id still lives

        queue.deleted( "Old" );
        queue.drainNow();

        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );
    }

    @Test
    void aRenameCancelsTheOldSlugsQueuedSaveAndProjectsTheNewSlug() {
        projectedBeforeThisRun( CID, "Old" );
        queue.saved( "Old" );
        // The rename lands before the drain: the canonical-id row now carries the new slug.
        when( dao.findBySlug( "Old" ) ).thenReturn( row( CID, "Old" ) );
        when( dao.findBySlug( "New" ) ).thenReturn( row( CID, "New" ) );
        queue.renamed( "Old", "New" );
        queue.drainNow();

        verify( pageManager, times( 1 ) ).getPureText( "Old", -1 );   // only the projection before this run
        assertEquals( "https://wiki.wikantik.com/wiki/New", projectedUrl( CID ),
                "the page graph must end on the new slug, not be re-projected from the stale old one" );
    }

    @Test
    void aFailureOnOnePageDoesNotStopTheOthers() {
        when( dao.findBySlug( "Broken" ) ).thenThrow( new IllegalStateException( "db down" ) );
        when( dao.findBySlug( "Fine" ) ).thenReturn( row( CID, "Fine" ) );

        queue.saved( "Broken" );
        queue.saved( "Fine" );
        queue.drainNow();

        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );
    }

    @Test
    void aDeleteWhoseIdLookupFailsAtEventTimeIsStillQueuedAndApplied() {
        projectedBeforeThisRun( CID, "Solo" );
        when( dao.findBySlug( "Solo" ) )
                .thenThrow( new IllegalStateException( "db blip" ) )   // on the deleting request thread
                .thenReturn( row( CID, "Solo" ) );                     // at drain time

        queue.deleted( "Solo" );
        queue.drainNow();

        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ) );
    }

    @Test
    void aFailedDeleteStepDoesNotSkipTheSameSlugsLaterSave() {
        projectedBeforeThisRun( CID, "Solo" );
        queue.deleted( "Solo" );
        when( dao.findByCanonicalId( CID ) ).thenThrow( new IllegalStateException( "db blip" ) );
        when( dao.findBySlug( "Solo" ) ).thenReturn( row( CID_2, "Solo" ) );   // recreated
        queue.saved( "Solo" );

        queue.drainNow();

        assertTrue( mgr.namedGraphExists( Iris.page( CID_2 ) ), "the save still runs after the delete step failed" );
    }

    @Test
    void aDrainResetsTheScheduleSoTheNextSaveSchedulesAgain() {
        when( dao.findBySlug( "Solo" ) ).thenReturn( row( CID, "Solo" ) );
        queue.saved( "Solo" );
        queue.drainNow();
        queue.saved( "Solo" );
        verify( scheduler, times( 2 ) ).schedule( any( Runnable.class ), anyLong(), any( TimeUnit.class ) );
    }
}
