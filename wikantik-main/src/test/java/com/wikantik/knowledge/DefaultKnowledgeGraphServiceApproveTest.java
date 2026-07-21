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

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.judge.KgMaterializationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class DefaultKnowledgeGraphServiceApproveTest {

    private DataSource ds;
    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgRejectionRepository kgRejections;
    private KgMaterializationService mat;
    private DefaultKnowledgeGraphService svc;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        kgNodes      = new KgNodeRepository( ds );
        kgEdges      = new KgEdgeRepository( ds );
        kgProposals  = new KgProposalRepository( ds );
        kgRejections = new KgRejectionRepository( ds );
        mat = new KgMaterializationService( kgNodes, kgEdges, kgProposals, kgRejections );
        svc = DefaultKnowledgeGraphService.builder( kgNodes, kgEdges, kgProposals, kgRejections, ds )
                                           .materialization( mat ).build();
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
        }
    }

    @Test
    void approveProposal_records_audit_and_promotes_to_human() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "U", "target", "V", "relationship", "owns" ),
            0.7, "" );

        svc.approveProposal( p.id(), "alice" );

        final KgProposal updated = kgProposals.getProposal( p.id() );
        assertEquals( "approved", updated.status() );
        assertEquals( "human", updated.tier() );
        assertEquals( "alice", updated.reviewedBy() );
        assertTrue( kgProposals.listReviews( p.id() ).stream()
            .anyMatch( r -> "human".equals( r.reviewerKind() ) && "approved".equals( r.verdict() ) ) );
        assertTrue( kgEdges.getAllEdges( Tier.HUMAN ).stream()
            .anyMatch( e -> "owns".equals( e.relationshipType() )
                && p.id().equals( e.provenanceProposalId() ) ),
            "human approval must materialise at human tier" );
    }

    @Test
    void rejectProposal_retracts_materialised_rows_and_writes_kg_rejections() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Q", "target", "R", "relationship", "delete_me" ),
            0.7, "" );
        mat.materializeMachine( p );

        svc.rejectProposal( p.id(), "alice", "wrong" );

        final KgProposal updated = kgProposals.getProposal( p.id() );
        assertEquals( "rejected", updated.status() );
        assertFalse( kgEdges.getAllEdges( Tier.MACHINE ).stream()
            .anyMatch( e -> p.id().equals( e.provenanceProposalId() ) ),
            "rejection must retract machine-tier materialisation" );
        assertTrue( kgRejections.isRejected( "Q", "R", "delete_me" ),
            "kg_rejections must record the negative-knowledge entry" );
        assertTrue( kgProposals.listReviews( p.id() ).stream()
            .anyMatch( r -> "human".equals( r.reviewerKind() ) && "rejected".equals( r.verdict() ) ) );
    }

    @Test
    void approveProposal_works_when_materialization_null_does_not_throw() {
        // Service constructed without materialization — should still flip status + audit, just no kg_* writes.
        final var svcDegraded = new DefaultKnowledgeGraphService(
            kgNodes, kgEdges, kgProposals, kgRejections, ds );
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "S1", "target", "S2", "relationship", "rel" ),
            0.7, "" );

        svcDegraded.approveProposal( p.id(), "bob" );

        final KgProposal updated = kgProposals.getProposal( p.id() );
        assertEquals( "approved", updated.status() );
        assertEquals( "human", updated.tier() );
        assertTrue( kgEdges.getAllEdges( Tier.HUMAN ).isEmpty(), "no materialisation when service has no mat" );
    }
}
