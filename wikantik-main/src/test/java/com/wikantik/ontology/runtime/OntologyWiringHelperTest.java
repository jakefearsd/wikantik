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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.wikantik.WikiEngine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystem;
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
    void aRebuildProjectsOnlyAnonymouslyViewablePages() {
        final com.wikantik.ontology.projection.PageRecord pub = new com.wikantik.ontology.projection.PageRecord(
                "01CANON0000000000000000A1", "Open", "Open", "article", null, List.of(), null, null, null );
        final com.wikantik.ontology.projection.PageRecord secret = new com.wikantik.ontology.projection.PageRecord(
                "01CANON0000000000000000A2", "Secret", "Secret", "article", null, List.of(), null, null, null );
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();

        final OntologyRebuildCoordinator coordinator = OntologyWiringHelper.newCoordinator(
                mgr, () -> List.of( pub, secret ), slug -> "Open".equals( slug ) );
        coordinator.triggerRebuild();
        Awaitility.await().atMost( 10, TimeUnit.SECONDS ).until( () -> "IDLE".equals( coordinator.status().state() ) );

        assertNull( coordinator.status().lastError() );
        assertTrue( mgr.namedGraphExists( Iris.page( pub.canonicalId() ) ) );
        assertFalse( mgr.namedGraphExists( Iris.page( secret.canonicalId() ) ), "restricted page never materialized" );
    }

    @Test
    void aRebuildPurgesEntityGraphsLeftBehindByTheRetiredKnowledgeGraph() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String legacy = Iris.ID + "entity/00000000-0000-0000-0000-0000000000f3";
        mgr.replaceNamedGraph( legacy, org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(
                ResourceFactory.createResource( legacy ), org.apache.jena.vocabulary.RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "Thing" ) ) );
        assertEquals( 1L, mgr.namedGraphCount() );

        final OntologyRebuildCoordinator coordinator =
                OntologyWiringHelper.newCoordinator( mgr, List::of, slug -> true );
        coordinator.triggerRebuild();
        Awaitility.await().atMost( 10, TimeUnit.SECONDS ).until( () -> "IDLE".equals( coordinator.status().state() ) );

        assertNull( coordinator.status().lastError() );
        assertEquals( 0L, mgr.namedGraphCount(), "legacy entity graphs purged by the next rebuild" );
    }

    @Test
    void rebuildIfEmptyStartsARebuild() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator coordinator =
                OntologyWiringHelper.newCoordinator( mgr, List::of, slug -> true );

        assertTrue( coordinator.rebuildIfEmpty() );
        Awaitility.await().atMost( 10, TimeUnit.SECONDS ).until( () -> "IDLE".equals( coordinator.status().state() ) );
        assertNull( coordinator.status().lastError() );
    }

    @Test
    void anAuthSubsystemWithoutAnIdentityProjectsAuthorsAsWritten() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getAuthSubsystem() ).thenReturn( new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null, null ) );

        assertNull( OntologyWiringHelper.publicIdentityOf( engine ) );
    }
}
