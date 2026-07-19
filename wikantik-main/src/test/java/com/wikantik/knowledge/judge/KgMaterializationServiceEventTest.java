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
package com.wikantik.knowledge.judge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.event.KgChangeEvent;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.ontology.OntologyShaclValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class KgMaterializationServiceEventTest {

    /** Records every KgChangeEvent fired with the service under test as client. */
    private static final class RecordingListener implements WikiEventListener {
        final List< KgChangeEvent > events = new CopyOnWriteArrayList<>();
        @Override
        public void actionPerformed( final WikiEvent event ) {
            if ( event instanceof KgChangeEvent kce ) {
                events.add( kce );
            }
        }
    }

    private DataSource ds;
    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgRejectionRepository kgRejections;
    private KgMaterializationService materialization;
    private RecordingListener listener;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        kgNodes      = new KgNodeRepository( ds );
        kgEdges      = new KgEdgeRepository( ds );
        kgProposals  = new KgProposalRepository( ds );
        kgRejections = new KgRejectionRepository( ds );
        materialization = new KgMaterializationService( kgNodes, kgEdges, kgProposals, kgRejections );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
        }
        listener = new RecordingListener();
        WikiEventManager.addWikiEventListener( materialization, listener );
    }

    @AfterEach
    void tearDown() throws Exception {
        WikiEventManager.removeWikiEventListener( listener );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
        }
    }

    private KgProposal persistNewEdgeProposal( final String source, final String target, final String relationship ) {
        return kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", source, "target", target, "relationship", relationship ),
            0.8, "" );
    }

    private KgProposal persistProposalOfType( final String proposalType ) {
        return kgProposals.insertProposal( proposalType, "Page", Map.<String, Object>of(), 0.8, "" );
    }

    @Test
    void materializeMachineFiresBothNodeIdsOnce() {
        final KgProposal proposal = persistNewEdgeProposal( "MatSrc", "MatTgt", "related_to" );
        materialization.materializeMachine( proposal );
        assertEquals( 1, listener.events.size(), "exactly one event per materialize call (no double-fire)" );
        final KgChangeEvent event = listener.events.get( 0 );
        assertEquals( 2, event.touchedEntityIds().size(), "both endpoint node ids touched" );
        assertTrue( event.removedEntityIds().isEmpty() );
    }

    @Test
    void retractFiresRemovedNodesAndNoLongerExistingRowsAreGone() {
        final KgProposal proposal = persistNewEdgeProposal( "RetSrc", "RetTgt", "related_to" );
        materialization.materializeMachine( proposal );
        listener.events.clear();
        materialization.retract( proposal );
        assertEquals( 1, listener.events.size() );
        final KgChangeEvent event = listener.events.get( 0 );
        assertEquals( 2, event.removedEntityIds().size(),
            "both provenance-created nodes removed" );
        assertTrue( event.touchedEntityIds().isEmpty(),
            "edge sources that are themselves removed must not also be touched" );
    }

    @Test
    void unsupportedProposalTypeFiresNothing() {
        final KgProposal proposal = persistProposalOfType( "new-node" );
        materialization.materializeMachine( proposal );
        assertTrue( listener.events.isEmpty() );
    }

    @Test
    void shaclSkipFiresBothNodeIds() {
        final OntologyShaclValidator validator = mock( OntologyShaclValidator.class );
        when( validator.validateEdge( any(), any(), any() ) )
            .thenReturn( List.of( new OntologyShaclValidator.Violation( "focus", "path", "violation message" ) ) );
        final KgMaterializationService svc = new KgMaterializationService(
            kgNodes, kgEdges, kgProposals, kgRejections, validator );
        final RecordingListener localListener = new RecordingListener();
        WikiEventManager.addWikiEventListener( svc, localListener );
        try {
            final KgProposal proposal = persistNewEdgeProposal( "ShaclSrc", "ShaclTgt", "implements" );
            svc.materializeMachine( proposal );
            assertEquals( 1, localListener.events.size(), "exactly one event on the SHACL-skip exit" );
            final KgChangeEvent event = localListener.events.get( 0 );
            assertEquals( 2, event.touchedEntityIds().size(),
                "both nodes were durably upserted even though the edge itself was skipped" );
            assertTrue( event.removedEntityIds().isEmpty() );
            assertEquals( 1L, svc.skippedNonConformantCount(), "SHACL gate must record the skip" );
        } finally {
            WikiEventManager.removeWikiEventListener( localListener );
        }
    }

    @Test
    void policyExclusionFiresOnlyWrittenNodeId() {
        // Mirrors KgMaterializationServiceNullGuardTest's exclusion setup exactly: mocked repos
        // where upsertNodeWithProvenance's post-write read-back returns null for the excluded
        // node only (the other endpoint is genuinely written and must still be reported).
        final KgNodeRepository mockedNodes = mock( KgNodeRepository.class );
        final KgEdgeRepository mockedEdges = mock( KgEdgeRepository.class );
        final KgNode src = new KgNode( UUID.randomUUID(), "Alpha", "concept", null,
            Provenance.AI_INFERRED, Map.of(), java.time.Instant.now(), java.time.Instant.now(), "machine", null );
        when( mockedNodes.upsertNodeWithProvenance( eq( "Alpha" ), anyString(), any(), any(), any(), anyString(), any() ) )
            .thenReturn( src );
        when( mockedNodes.upsertNodeWithProvenance( eq( "Beta" ),  anyString(), any(), any(), any(), anyString(), any() ) )
            .thenReturn( null );

        final KgProposal proposal = new KgProposal(
            UUID.randomUUID(),                  // id
            "new-edge",                         // proposalType
            "Page",                             // sourcePage
            Map.of( "source", "Alpha", "target", "Beta", "relationship", "depends_on" ), // proposedData
            0.8,                                // confidence
            "reason",                           // reasoning
            "pending",                          // status
            null,                               // reviewedBy
            java.time.Instant.now(),            // created
            null,                               // reviewedAt
            "none",                             // tier
            null,                               // machineStatus
            null,                               // machineConfidence
            null,                               // machineJudgedAt
            null );                             // machineModel

        final KgMaterializationService svc = new KgMaterializationService(
            mockedNodes, mockedEdges, mock( KgProposalRepository.class ), mock( KgRejectionRepository.class ) );
        final RecordingListener localListener = new RecordingListener();
        WikiEventManager.addWikiEventListener( svc, localListener );
        try {
            svc.materializeMachine( proposal );
            assertEquals( 1, localListener.events.size() );
            final KgChangeEvent event = localListener.events.get( 0 );
            assertEquals( Set.of( src.id() ), event.touchedEntityIds(),
                "only the non-excluded node's id is touched" );
            assertTrue( event.removedEntityIds().isEmpty() );
        } finally {
            WikiEventManager.removeWikiEventListener( localListener );
        }
    }

    @Test
    void retractTouchesExternalEdgeSourcesButLeavesThemUnremoved() {
        final KgProposal proposal = persistNewEdgeProposal( "ExtSrc", "ExtTgt", "related_to" );
        materialization.materializeMachine( proposal );

        final List< UUID > provenanceNodeIds = kgNodes.findNodeIdsByProvenance( proposal.id() );
        assertEquals( 2, provenanceNodeIds.size() );
        final List< KgEdge > provenanceEdges = kgEdges.findEdgesByProvenance( proposal.id() );
        assertEquals( 1, provenanceEdges.size() );
        final UUID xId = provenanceEdges.get( 0 ).sourceId(); // ExtSrc's node id

        // External node E, created under a DIFFERENT proposal id — it must survive retract(proposal).
        final KgNode external = kgNodes.upsertNodeWithProvenance( "ExtNode", "concept", null,
            Provenance.AI_INFERRED, Map.of(), "machine", UUID.randomUUID() );
        // An edge E -> X that (unusually) carries THIS proposal's provenance, e.g. co-created by
        // the same extraction pass. It is deleted by retract(proposal), so E's touched (not removed).
        kgEdges.upsertEdgeWithProvenance( external.id(), xId, "related_to",
            Provenance.AI_INFERRED, Map.of(), "machine", proposal.id() );

        listener.events.clear();
        materialization.retract( proposal );

        assertEquals( 1, listener.events.size() );
        final KgChangeEvent event = listener.events.get( 0 );
        assertEquals( Set.copyOf( provenanceNodeIds ), event.removedEntityIds() );
        assertEquals( Set.of( external.id() ), event.touchedEntityIds(),
            "external node whose outgoing edge was deleted must be touched, not removed" );
    }
}
