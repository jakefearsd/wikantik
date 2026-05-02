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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeRepositoryVerdictTest {

    private DataSource ds;
    private JdbcKnowledgeRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        repo = new JdbcKnowledgeRepository( ds );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
        }
    }

    @Test
    void applyMachineVerdict_approved_sets_machine_status_and_tier_machine() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "B", "relationship", "r" ), 0.7, "" );

        repo.applyMachineVerdict( p.id(), "approved", 0.9, "gemma4-assist:latest" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "approved", updated.machineStatus() );
        assertEquals( "machine", updated.tier() );
        assertEquals( 0.9, updated.machineConfidence() );
        assertNotNull( updated.machineJudgedAt() );
        assertEquals( "gemma4-assist:latest", updated.machineModel() );
    }

    @Test
    void applyMachineVerdict_rejected_sets_status_rejected_tier_none() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "C", "relationship", "r" ), 0.7, "" );

        repo.applyMachineVerdict( p.id(), "rejected", 0.95, "gemma4-assist:latest" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "rejected", updated.status(),
            "human status flips to rejected on hard auto-reject" );
        assertEquals( "rejected", updated.machineStatus() );
        assertEquals( "none", updated.tier(), "rejected proposals are not materialised" );
    }

    @Test
    void applyMachineVerdict_abstain_keeps_tier_none_and_status_pending() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "Z", "relationship", "r" ), 0.7, "" );

        repo.applyMachineVerdict( p.id(), "abstain", 0.0, "gemma4-assist:latest" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "abstain", updated.machineStatus() );
        assertEquals( "none", updated.tier() );
        assertEquals( "pending", updated.status() );
    }

    @Test
    void applyHumanVerdict_approved_sets_tier_human() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "D", "relationship", "r" ), 0.7, "" );
        repo.applyMachineVerdict( p.id(), "approved", 0.8, "gemma4-assist:latest" );

        repo.applyHumanVerdict( p.id(), "approved", "alice" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "approved", updated.status() );
        assertEquals( "human", updated.tier() );
        assertEquals( "alice", updated.reviewedBy() );
    }

    @Test
    void applyHumanVerdict_rejected_sets_tier_none() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "E", "relationship", "r" ), 0.7, "" );

        repo.applyHumanVerdict( p.id(), "rejected", "alice" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "rejected", updated.status() );
        assertEquals( "none", updated.tier() );
    }

    @Test
    void clearAll_truncates_kg_proposal_reviews() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "F", "relationship", "r" ), 0.7, "" );
        repo.recordReview( p.id(), "machine", "gemma", "approved", 0.8, "ok" );

        repo.clearAll();

        // Must not throw FK violation; reviews must be gone.
        try ( Connection c = ds.getConnection() ) {
            try ( var rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM kg_proposal_reviews" ) ) {
                rs.next();
                assertEquals( 0, rs.getInt( 1 ) );
            }
        } catch ( Exception e ) {
            fail( "clearAll caused exception: " + e.getMessage() );
        }
    }
}
