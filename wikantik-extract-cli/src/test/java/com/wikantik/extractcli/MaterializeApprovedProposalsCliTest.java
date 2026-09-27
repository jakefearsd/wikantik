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
package com.wikantik.extractcli;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Drives the replay through the public {@code runWithDataSource} seam against a real
 * migrated schema, so it exercises the actual query + materialisation rather than
 * argument parsing.
 */
@RequiresPostgres
class MaterializeApprovedProposalsCliTest {

    private DataSource ds;
    private KgProposalRepository kgProposals;
    private KgNodeRepository kgNodes;
    private MaterializeApprovedProposalsCli cli;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "kg_proposal_reviews", "kg_edges", "kg_proposals",
            "kg_nodes", "kg_rejections" );
        kgProposals = new KgProposalRepository( ds );
        kgNodes = new KgNodeRepository( ds );
        cli = new MaterializeApprovedProposalsCli(
            new PrintStream( new ByteArrayOutputStream() ),
            new PrintStream( new ByteArrayOutputStream() ) );
    }

    /** A machine-approved, still-pending new-node proposal, exactly as prod holds 19,225 of. */
    private KgProposal approvedPendingNewNode( final String name, final String nodeType ) {
        final KgProposal p = kgProposals.insertProposal( "new-node", "ServiceMeshPage",
            Map.< String, Object >of( "name", name, "nodeType", nodeType ), 0.9, "extracted" );
        // Via the real API, not a hand-patched row: this sets machine_status='approved'
        // and tier='machine' while deliberately leaving status='pending'.
        kgProposals.applyMachineVerdict( p.id(), JudgeVerdict.APPROVED, 0.9, "test-model" );
        return p;
    }

    /**
     * The replay must turn a machine-approved {@code new-node} proposal into a
     * {@code kg_nodes} row.
     *
     * <p>Production holds 19,225 proposals in exactly this state — approved by the judge,
     * still {@code pending} for a human, and never materialised because
     * {@code materialize} handled only {@code new-edge}. Fixing the materialiser does not
     * create the missing rows retroactively, which is the entire reason this replay
     * exists, so this is the behaviour that matters.</p>
     */
    @Test
    void replay_materializes_a_machine_approved_new_node_proposal() {
        final KgProposal p = approvedPendingNewNode( "Envoy", "technology" );

        final int rc = cli.runWithDataSource( ds, new String[]{} );

        assertEquals( 0, rc, "a clean replay must exit 0" );
        final KgNode node = kgNodes.getAllNodes( Tier.MACHINE ).stream()
            .filter( n -> "Envoy".equals( n.name() ) )
            .findFirst().orElse( null );
        assertNotNull( node, "the approved new-node proposal must have been materialized" );
        assertEquals( "technology", node.nodeType(), "the declared nodeType must survive" );
        assertEquals( "ServiceMeshPage", node.sourcePage(),
            "the node must be attributed to the proposal's source page" );
        assertEquals( p.id(), node.provenanceProposalId(),
            "provenance must link the node back to the replayed proposal" );
    }

    /**
     * {@code --dry-run} must write nothing.
     *
     * <p><b>Characterization test.</b> The dry-run branch was written as part of the
     * implementation rather than driven by this test first, so it passed immediately. It is
     * kept because the intended use is a 19,225-row production replay, where a dry run that
     * silently wrote rows would be actively harmful — the guarantee is worth pinning however
     * it arrived.</p>
     */
    @Test
    void dry_run_reports_but_writes_no_nodes() {
        approvedPendingNewNode( "Linkerd", "technology" );

        final int rc = cli.runWithDataSource( ds, new String[]{ "--dry-run" } );

        assertEquals( 0, rc, "a dry run must exit 0" );
        assertEquals( 0, kgNodes.getAllNodes( Tier.MACHINE ).size(),
            "--dry-run must not write any kg_nodes row" );
    }

    /**
     * {@code --limit N} must stop after N proposals.
     *
     * <p><b>Characterization test</b>, same provenance as the dry-run one above. It matters
     * operationally: the limit is how a first production run is kept small enough to inspect
     * before replaying all 19,225.</p>
     */
    @Test
    void limit_stops_after_the_requested_number_of_proposals() {
        approvedPendingNewNode( "Envoy", "technology" );
        approvedPendingNewNode( "Istio", "technology" );
        approvedPendingNewNode( "Consul", "technology" );

        final int rc = cli.runWithDataSource( ds, new String[]{ "--limit", "1" } );

        assertEquals( 0, rc );
        assertEquals( 1, kgNodes.getAllNodes( Tier.MACHINE ).size(),
            "--limit 1 must materialize exactly one proposal, not the whole page" );
    }

    /**
     * Machine-approved {@code new-edge} proposals must be left untouched.
     *
     * <p>They were never the gap: edge materialisation always worked, and it is where every
     * one of the existing nodes came from. Re-running the 7,644 machine-approved edge
     * proposals would be idempotent but would cost ~23k redundant upserts and fire 7,644
     * needless {@code KgChangeEvent}s, each triggering an ontology re-projection. Since
     * {@code listProposalsFiltered} has no proposalType parameter, that restriction lives in
     * Java — so it needs a test, or a later refactor could silently widen the scope.</p>
     */
    @Test
    void machine_approved_new_edge_proposals_are_left_alone() {
        final KgProposal edge = kgProposals.insertProposal( "new-edge", "ServiceMeshPage",
            Map.< String, Object >of( "source", "Alpha", "target", "Beta",
                "relationship", "requires" ), 0.9, "extracted" );
        kgProposals.applyMachineVerdict( edge.id(), JudgeVerdict.APPROVED, 0.9, "test-model" );

        final int rc = cli.runWithDataSource( ds, new String[]{} );

        assertEquals( 0, rc );
        assertEquals( 0, kgNodes.getAllNodes( Tier.MACHINE ).size(),
            "a new-edge proposal must not be materialized by this replay" );
    }
}
