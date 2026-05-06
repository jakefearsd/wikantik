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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class KgMaterializationServiceMaterializeTest {

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
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void materializeMachine_new_edge_inserts_two_nodes_and_one_edge_at_machine_tier() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Alpha", "target", "Beta", "relationship", "depends_on" ),
            0.8, "extractor reasoning" );

        svc.materializeMachine( p );

        final List< KgNode > all = kgNodes.getAllNodes( Tier.MACHINE );
        assertTrue( all.stream().anyMatch( n -> n.name().equals( "Alpha" ) && "machine".equals( n.tier() ) ) );
        assertTrue( all.stream().anyMatch( n -> n.name().equals( "Beta" ) && "machine".equals( n.tier() ) ) );

        final List< KgEdge > edges = kgEdges.getAllEdges( Tier.MACHINE );
        assertTrue( edges.stream().anyMatch( e -> "depends_on".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() )
            && "machine".equals( e.tier() ) ) );
    }

    @Test
    void materializeMachine_is_idempotent() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Gamma", "target", "Delta", "relationship", "uses" ),
            0.8, "" );

        svc.materializeMachine( p );
        svc.materializeMachine( p );

        final List< KgEdge > edges = kgEdges.getAllEdges( Tier.MACHINE );
        final long count = edges.stream()
            .filter( e -> "uses".equals( e.relationshipType() )
                && p.id().equals( e.provenanceProposalId() ) ).count();
        assertEquals( 1L, count, "edge must not be duplicated on retry" );
    }

    @Test
    void materializeMachine_skips_when_required_fields_missing() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Only" ), 0.8, "" );

        svc.materializeMachine( p ); // should NOT throw, just no-op

        assertEquals( 0, kgNodes.getAllNodes( Tier.MACHINE ).size() );
    }

    @Test
    void materializeMachine_skips_unsupported_proposal_type() {
        final KgProposal p = kgProposals.insertProposal( "new-node", "Page",
            Map.<String, Object>of( "name", "Solo" ), 0.8, "" );

        svc.materializeMachine( p );

        assertEquals( 0, kgNodes.getAllNodes( Tier.MACHINE ).size() );
    }
}
