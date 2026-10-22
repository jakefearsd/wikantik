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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.wikantik.WikiEngine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import org.apache.jena.rdf.model.ResourceFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class OntologyWiringHelperTest {

    @Test
    void theEnginesPublicIdentityIsHandedToTheProjection() {
        final WikiEngine engine = mock( WikiEngine.class );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( engine.getAuthSubsystem() ).thenReturn( new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null, identity ) );

        assertSame( identity, OntologyWiringHelper.publicIdentityOf( engine ) );
    }

    @Test
    void anEngineWithoutAnAuthSubsystemProjectsAuthorsAsWritten() {
        assertNull( OntologyWiringHelper.publicIdentityOf( mock( WikiEngine.class ) ) );
    }

    @Test
    void aRebuildLoadsTheNodeTableOnceNotOncePerSource() {
        final UUID a = UUID.fromString( "00000000-0000-0000-0000-0000000000f1" );
        final UUID b = UUID.fromString( "00000000-0000-0000-0000-0000000000f2" );
        final KgNodeRepository nodeRepo = mock( KgNodeRepository.class );
        final KgEdgeRepository edgeRepo = mock( KgEdgeRepository.class );
        when( nodeRepo.getAllNodes( Tier.MACHINE ) ).thenReturn( List.of(
                new KgNode( a, "A", "concept", null, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ),
                new KgNode( b, "B", "concept", null, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) ) );
        when( edgeRepo.getAllEdges( Tier.MACHINE ) ).thenReturn( List.of(
                new KgEdge( UUID.randomUUID(), a, b, "uses", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) ) );
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();

        final OntologyRebuildCoordinator coordinator =
                OntologyWiringHelper.newCoordinator( mgr, nodeRepo, edgeRepo, List::of, slug -> true );
        coordinator.triggerRebuild();
        Awaitility.await().atMost( 10, TimeUnit.SECONDS ).until( () -> "IDLE".equals( coordinator.status().state() ) );

        assertNull( coordinator.status().lastError() );
        assertEquals( 2L, coordinator.status().graphCount() );
        assertTrue( mgr.namedGraphSnapshot( Iris.entity( a ) ).contains(
                null, null, ResourceFactory.createResource( Iris.entity( b ) ) ), "edge between public nodes projected" );
        // Each call is a full SELECT over kg_nodes; the edge filter must reuse the nodes already loaded.
        verify( nodeRepo, times( 1 ) ).getAllNodes( Tier.MACHINE );
    }

    @Test
    void anAuthSubsystemWithoutAnIdentityProjectsAuthorsAsWritten() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getAuthSubsystem() ).thenReturn( new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null, null ) );

        assertNull( OntologyWiringHelper.publicIdentityOf( engine ) );
    }
}
