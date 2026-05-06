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
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class KgMaterializationServicePromoteRetractTest {

    private DataSource ds;
    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgRejectionRepository kgRejections;
    private KgMaterializationService svc;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        kgNodes      = new KgNodeRepository( ds );
        kgEdges      = new KgEdgeRepository( ds );
        kgProposals  = new KgProposalRepository( ds );
        kgRejections = new KgRejectionRepository( ds );
        svc = new KgMaterializationService( kgNodes, kgEdges, kgProposals, kgRejections );
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
    void promoteToHuman_upgrades_existing_machine_rows_to_human() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "M1", "target", "M2", "relationship", "uses" ),
            0.8, "" );
        svc.materializeMachine( p );

        svc.promoteToHuman( p );

        final var humanEdges = kgEdges.getAllEdges( Tier.HUMAN );
        assertTrue( humanEdges.stream().anyMatch( e -> "uses".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() ) ),
            "machine row must promote to human tier" );
    }

    @Test
    void promoteToHuman_inserts_when_no_machine_rows_exist() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "H1", "target", "H2", "relationship", "owns" ),
            0.8, "" );

        svc.promoteToHuman( p );

        final var humanEdges = kgEdges.getAllEdges( Tier.HUMAN );
        assertTrue( humanEdges.stream().anyMatch( e -> "owns".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() ) ),
            "new row must land at human tier" );
    }

    @Test
    void retract_deletes_rows_for_provenance() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "R1", "target", "R2", "relationship", "delete_me" ),
            0.8, "" );
        svc.materializeMachine( p );

        svc.retract( p );

        final var allEdges = kgEdges.getAllEdges( Tier.MACHINE );
        assertFalse( allEdges.stream().anyMatch( e -> p.id().equals( e.provenanceProposalId() ) ),
            "edge must be deleted by provenance" );
    }

    @Test
    void promoteToHuman_clears_kg_rejections_for_triple() {
        // Simulate: judge previously rejected this triple → kg_rejections row exists.
        kgRejections.insertRejection( "Z1", "Z2", "judged_no", "gemma4-assist:latest", "low evidence" );
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Z1", "target", "Z2", "relationship", "judged_no" ),
            0.8, "" );

        svc.promoteToHuman( p );

        assertFalse( kgRejections.isRejected( "Z1", "Z2", "judged_no" ),
            "human override must remove the negative-knowledge entry" );
    }
}
