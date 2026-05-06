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
import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.judge.KgMaterializationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class DefaultKnowledgeGraphServiceJudgeNowTest {

    private DataSource ds;
    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgRejectionRepository kgRejections;
    private KgMaterializationService mat;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        kgNodes      = new KgNodeRepository( ds );
        kgEdges      = new KgEdgeRepository( ds );
        kgProposals  = new KgProposalRepository( ds );
        kgRejections = new KgRejectionRepository( ds );
        mat = new KgMaterializationService( kgNodes, kgEdges, kgProposals, kgRejections );
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
    void judgeNow_calls_judge_records_review_and_materialises_when_approved() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "approved", 0.9, "ok", "gemma4-assist:latest" ) );
        final var svc = new DefaultKnowledgeGraphService( kgNodes, kgEdges, kgProposals, kgRejections, ds, null, null, mat, judge );

        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "S", "target", "T", "relationship", "now" ),
            0.7, "" );

        final JudgeVerdict v = svc.judgeNow( p.id(), "alice" );

        assertEquals( "approved", v.verdict() );
        assertEquals( "machine", kgProposals.getProposal( p.id() ).tier() );
        assertFalse( kgProposals.listReviews( p.id() ).isEmpty() );
        assertTrue( kgEdges.getAllEdges( Tier.MACHINE ).stream()
            .anyMatch( e -> p.id().equals( e.provenanceProposalId() ) ),
            "approved verdict must materialise" );
    }

    @Test
    void judgeNow_writes_kg_rejections_on_hard_reject() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "rejected", 0.95, "no support", "gemma4-assist:latest" ) );
        final var svc = new DefaultKnowledgeGraphService( kgNodes, kgEdges, kgProposals, kgRejections, ds, null, null, mat, judge );

        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Q", "target", "R", "relationship", "bad" ), 0.7, "" );

        svc.judgeNow( p.id(), "alice" );

        assertTrue( kgRejections.isRejected( "Q", "R", "bad" ) );
    }

    @Test
    void judgeNow_throws_when_judge_service_not_configured() {
        final var svc = new DefaultKnowledgeGraphService( kgNodes, kgEdges, kgProposals, kgRejections, ds, null, null, mat, null );
        assertThrows( IllegalStateException.class,
            () -> svc.judgeNow( UUID.randomUUID(), "alice" ) );
    }

    @Test
    void judgeNow_throws_when_proposal_not_found() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        final var svc = new DefaultKnowledgeGraphService( kgNodes, kgEdges, kgProposals, kgRejections, ds, null, null, mat, judge );
        assertThrows( IllegalArgumentException.class,
            () -> svc.judgeNow( UUID.randomUUID(), "alice" ) );
    }
}
