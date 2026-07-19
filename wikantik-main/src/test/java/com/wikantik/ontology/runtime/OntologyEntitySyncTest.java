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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class OntologyEntitySyncTest {

    @Mock private KgNodeRepository nodes;
    @Mock private KgEdgeRepository edges;
    @Mock private PageCanonicalIdsDao pageDao;
    @Mock private ScheduledExecutorService scheduler; // never executes — tests call drainNow() directly

    private OntologyModelManager manager;
    private OntologyEntitySync sync;

    private static KgNode node( final UUID id, final String name, final String sourcePage ) {
        return new KgNode( id, name, "concept", sourcePage, Provenance.HUMAN_CURATED,
            Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    private static KgEdge edge( final UUID source, final UUID target ) {
        return new KgEdge( UUID.randomUUID(), source, target, "related_to", Provenance.HUMAN_CURATED,
            Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    @BeforeEach
    void setUp() {
        manager = OntologyModelManager.inMemory();
        manager.loadTBox();
        when( pageDao.findBySlug( any() ) ).thenReturn( Optional.empty() );
        sync = new OntologyEntitySync( manager, nodes, edges, pageDao, slug -> true, 500L, scheduler );
    }

    @Test
    void touchedEntityGetsProjectedIntoItsNamedGraph() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( node( id, "Alpha", null ) );
        when( edges.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertTrue( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void coalescingProjectsOncePerDrainAndSchedulesOnce() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( node( id, "Beta", null ) );
        when( edges.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.mark( Set.of( id ), Set.of() );
        verify( scheduler, times( 1 ) ).schedule( any( Runnable.class ), anyLong(), any( TimeUnit.class ) );
        sync.drainNow();
        verify( nodes, times( 1 ) ).getNode( id );
    }

    @Test
    void removedEntityGraphIsDropped() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( node( id, "Gamma", null ) );
        when( edges.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertTrue( manager.namedGraphExists( Iris.entity( id ) ) );
        sync.mark( Set.of(), Set.of( id ) );
        sync.drainNow();
        assertFalse( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void nodeGoneAtDrainTimeRemovesTheGraph() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( null );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertFalse( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void restrictedSourcePageRemovesTheGraph() {
        final UUID id = UUID.randomUUID();
        sync = new OntologyEntitySync( manager, nodes, edges, pageDao,
            slug -> false, 500L, scheduler ); // nothing is public
        when( nodes.getNode( id ) ).thenReturn( node( id, "Delta", "SecretPage" ) );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertFalse( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void edgeToRestrictedTargetIsFilteredOut() {
        final UUID src = UUID.randomUUID();
        final UUID pubTgt = UUID.randomUUID();
        final UUID privTgt = UUID.randomUUID();
        sync = new OntologyEntitySync( manager, nodes, edges, pageDao,
            "PublicPage"::equals, 500L, scheduler );
        when( nodes.getNode( src ) ).thenReturn( node( src, "Src", null ) );
        when( nodes.getNode( pubTgt ) ).thenReturn( node( pubTgt, "PubTgt", "PublicPage" ) );
        when( nodes.getNode( privTgt ) ).thenReturn( node( privTgt, "PrivTgt", "SecretPage" ) );
        when( edges.getEdgesForNode( src, "outbound" ) )
            .thenReturn( List.of( edge( src, pubTgt ), edge( src, privTgt ) ) );
        sync.mark( Set.of( src ), Set.of() );
        sync.drainNow();
        final String graph = manager.unionSnapshot().toString(); // any read that exposes triples
        assertTrue( manager.namedGraphExists( Iris.entity( src ) ) );
        // The projected statements must reference the public target IRI but never the private one.
        assertTrue( graph.contains( privTgt.toString() ) == false,
            "restricted-target edge must not be projected" );
    }

    @Test
    void drainContinuesPastAPerEntityFailure() {
        final UUID bad = UUID.randomUUID();
        final UUID good = UUID.randomUUID();
        when( nodes.getNode( bad ) ).thenThrow( new RuntimeException( "boom" ) );
        when( nodes.getNode( good ) ).thenReturn( node( good, "Good", null ) );
        when( edges.getEdgesForNode( good, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( bad, good ), Set.of() );
        sync.drainNow(); // must not throw
        assertTrue( manager.namedGraphExists( Iris.entity( good ) ),
            "the good entity must still be projected after the bad one failed" );
    }
}
