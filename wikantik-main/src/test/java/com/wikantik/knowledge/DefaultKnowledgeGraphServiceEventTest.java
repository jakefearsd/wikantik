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
package com.wikantik.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.PostgresTestContainer;
import com.wikantik.TestEngine;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.event.KgChangeEvent;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers( disabledWithoutDocker = true )
class DefaultKnowledgeGraphServiceEventTest {

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

    private static DataSource dataSource;
    private static TestEngine engine;
    private DefaultKnowledgeGraphService service;
    private RecordingListener listener;

    @BeforeAll
    static void initDataSource() throws Exception {
        dataSource = PostgresTestContainer.createDataSource();
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        service = new DefaultKnowledgeGraphService(
            new KgNodeRepository( dataSource ),
            new KgEdgeRepository( dataSource ),
            new KgProposalRepository( dataSource ),
            new KgRejectionRepository( dataSource ),
            dataSource, engine );
        // Attached here (not a second @BeforeEach) — JUnit 5 does not guarantee
        // ordering across multiple @BeforeEach methods in the same class, and
        // this listener attach depends on `service` already being constructed.
        listener = new RecordingListener();
        WikiEventManager.addWikiEventListener( service, listener );
    }

    @AfterEach
    void detachListener() {
        WikiEventManager.removeWikiEventListener( listener );
    }

    private KgChangeEvent only() {
        assertEquals( 1, listener.events.size(), "expected exactly one KgChangeEvent" );
        return listener.events.get( 0 );
    }

    @Test
    void upsertNodeFiresTouchedNodeId() {
        final KgNode node = service.upsertNode( "EventNode", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        assertEquals( Set.of( node.id() ), only().touchedEntityIds() );
        assertTrue( only().removedEntityIds().isEmpty() );
    }

    @Test
    void upsertEdgeFiresTouchedSourceId() {
        final KgNode a = service.upsertNode( "EvA", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode b = service.upsertNode( "EvB", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.upsertEdge( a.id(), b.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
    }

    @Test
    void upsertEdgeAcrossPageEntityBoundaryReturnsNullWithoutNpeOrEvent() {
        // KgEdgeRepository.upsertEdge refuses (returns null) when exactly one endpoint is a
        // known entity node_type and the other is page-like (null node_type) — the mixed
        // page/entity boundary guard. DefaultKnowledgeGraphService.upsertEdge must not NPE
        // dereferencing that null result, and must not fire a KgChangeEvent for a no-op write.
        final KgNode pageLike = service.upsertNode( "EvPageLike", null, null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode entityLike = service.upsertNode( "EvEntityLike", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        final KgEdge result = service.upsertEdge(
            pageLike.id(), entityLike.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        assertNull( result, "mixed page/entity edge must be refused (null), not thrown" );
        assertTrue( listener.events.isEmpty(), "a refused (no-op) edge write must not fire a KgChangeEvent" );
    }

    @Test
    void deleteEdgeFiresTouchedSourceIdViaPreDeleteLookup() {
        final KgNode a = service.upsertNode( "EvC", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode b = service.upsertNode( "EvD", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgEdge edge = service.upsertEdge( a.id(), b.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.deleteEdge( edge.id() );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
    }

    @Test
    void deleteEdgeAndRecordRejectionFiresTouchedSourceIdViaPreDeleteLookup() {
        final KgNode a = service.upsertNode( "EvRejA", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode b = service.upsertNode( "EvRejB", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgEdge edge = service.upsertEdge( a.id(), b.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.deleteEdgeAndRecordRejection( edge.id(), "tester", "review-gap test" );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
        assertTrue( only().removedEntityIds().isEmpty() );
    }

    @Test
    void deleteNodeFiresRemovedIdAndTouchedInEdgeSources() {
        final KgNode victim = service.upsertNode( "EvVictim", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode pointer = service.upsertNode( "EvPointer", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        service.upsertEdge( pointer.id(), victim.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.deleteNode( victim.id() );
        assertEquals( Set.of( victim.id() ), only().removedEntityIds() );
        assertEquals( Set.of( pointer.id() ), only().touchedEntityIds() );
    }

    @Test
    void mergeNodesFiresRemovedSourceAndTouchedTargetPlusInboundSources() {
        final KgNode src = service.upsertNode( "EvMergeSrc", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode tgt = service.upsertNode( "EvMergeTgt", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode inboundSrc = service.upsertNode( "EvInbound", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        service.upsertEdge( inboundSrc.id(), src.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.mergeNodes( src.id(), tgt.id() );
        assertEquals( Set.of( src.id() ), only().removedEntityIds() );
        assertEquals( Set.of( tgt.id(), inboundSrc.id() ), only().touchedEntityIds() );
    }

    @Test
    void confirmEdgeFiresTouchedSourceId() {
        final KgNode a = service.upsertNode( "EvConfA", "concept", null, Provenance.AI_INFERRED, Map.of() );
        final KgNode b = service.upsertNode( "EvConfB", "concept", null, Provenance.AI_INFERRED, Map.of() );
        final KgEdge edge = service.upsertEdge( a.id(), b.id(), "related_to", Provenance.AI_INFERRED, Map.of() );
        listener.events.clear();
        service.confirmEdge( edge.id(), "tester" );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
    }

    @Test
    void bulkDeleteEdgesFiresTouchedSourceIdsForBothSources() {
        final KgNode a = service.upsertNode( "EvBulkA", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode b = service.upsertNode( "EvBulkB", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode c = service.upsertNode( "EvBulkC", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode d = service.upsertNode( "EvBulkD", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "bulk_test_rel", Provenance.HUMAN_CURATED, Map.of() );
        service.upsertEdge( c.id(), d.id(), "bulk_test_rel", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.bulkDeleteEdges( "bulk_test_rel", null, 2 );
        assertEquals( Set.of( a.id(), c.id() ), only().touchedEntityIds() );
        assertTrue( only().removedEntityIds().isEmpty() );
    }

    @Test
    void findByProvenanceReadsReturnPendingRows() {
        // Exercises the two NEW repository read methods Task 3 depends on.
        final UUID proposalId = UUID.randomUUID();
        final KgNodeRepository nodeRepo = new KgNodeRepository( dataSource );
        final KgEdgeRepository edgeRepo = new KgEdgeRepository( dataSource );
        final KgNode s = nodeRepo.upsertNodeWithProvenance( "EvProvS", "concept", null,
            Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
        final KgNode t = nodeRepo.upsertNodeWithProvenance( "EvProvT", "concept", null,
            Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
        edgeRepo.upsertEdgeWithProvenance( s.id(), t.id(), "related_to",
            Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
        assertEquals( Set.of( s.id(), t.id() ),
            Set.copyOf( nodeRepo.findNodeIdsByProvenance( proposalId ) ) );
        final List< KgEdge > provEdges = edgeRepo.findEdgesByProvenance( proposalId );
        assertEquals( 1, provEdges.size() );
        assertEquals( s.id(), provEdges.get( 0 ).sourceId() );
    }
}
