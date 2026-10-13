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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.jdbc.testing.MigrationApplier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.judge.KgMaterializationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code bin/db/kg-reset-machine-tier.sql} — the destructive core of
 * {@code bin/kg-rebuild.sh --reset-kg} — against a real PostgreSQL carrying every migration.
 *
 * <p>The defect: the reset used to delete {@code kg_nodes WHERE provenance = 'ai-inferred'}. But
 * approving a proposal flips a node's <b>tier</b> to {@code human} while its <b>provenance</b>
 * stays {@code ai-inferred}, so the reset also deleted every human-approved node and, through
 * {@code ON DELETE CASCADE}, the edges, chunk mentions and embeddings attached to it. The
 * fixture here is built through the real {@link KgMaterializationService} flow
 * (machine-materialise, then {@code promoteToHuman}) so it carries exactly the rows production does.</p>
 */
@RequiresPostgres
class KgResetMachineTierSqlTest {

    private DataSource ds;
    private KgNodeRepository nodes;
    private KgEdgeRepository edges;
    private KgProposalRepository proposals;
    private KgMaterializationService svc;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "kg_proposal_reviews", "kg_edges", "kg_proposals", "kg_nodes" );
        ds        = PostgresTestDb.createDataSource();
        nodes     = new KgNodeRepository( ds );
        edges     = new KgEdgeRepository( ds );
        proposals = new KgProposalRepository( ds );
        svc = new KgMaterializationService( nodes, edges, proposals, new KgRejectionRepository( ds ) );
    }

    private KgProposal edgeProposal( final String source, final String target ) {
        return proposals.insertProposal( "new-edge", "SomePage",
            Map.< String, Object >of( "source", source, "target", target, "relationship", "uses" ), 0.8, "" );
    }

    private KgProposal nodeProposal( final String name ) {
        return proposals.insertProposal( "new-node", "SomePage",
            Map.< String, Object >of( "name", name, "nodeType", "technology" ), 0.8, "" );
    }

    /** Machine judge approves + materialises, then a person approves: tier -> human, provenance stays ai-inferred. */
    private void machineThenHumanApprove( final KgProposal p ) {
        svc.materializeMachine( p );
        svc.promoteToHuman( p );
        proposals.applyHumanVerdict( p.id(), "approved", "reviewer" );
    }

    /** Builds a graph that mixes every kind of row the reset must (or must not) touch. */
    private void seedMixedGraph() {
        // Pure machine state: two ai-inferred machine nodes joined by a machine edge. MUST be deleted.
        svc.materializeMachine( edgeProposal( "MachA", "MachB" ) );

        // Human-approved edge proposal: both endpoints and the edge are promoted to tier human,
        // but their provenance stays 'ai-inferred'. MUST survive.
        machineThenHumanApprove( edgeProposal( "HumA", "HumB" ) );

        // Human-approved new-node proposal: a lone ai-inferred node at tier human. MUST survive.
        machineThenHumanApprove( nodeProposal( "HumNode" ) );

        // A human-authored node with a MACHINE edge to a machine node: the node survives, the
        // machine node and the machine edge go.
        nodes.upsertNode( "Curated", "technology", "CuratedPage", Provenance.HUMAN_AUTHORED, Map.of() );
        svc.materializeMachine( edgeProposal( "Curated", "MachC" ) );

        // A machine-tier node created by its own proposal, later joined to a human node by a
        // HUMAN-approved edge. Deleting the node would cascade-delete that human edge, so the node
        // must be kept.
        svc.materializeMachine( nodeProposal( "MachE" ) );
        machineThenHumanApprove( edgeProposal( "HumA", "MachE" ) );

        // A rejected proposal is a human verdict and must survive too.
        final KgProposal rejected = edgeProposal( "RejA", "RejB" );
        proposals.applyHumanVerdict( rejected.id(), "rejected", "reviewer" );
    }

    private void runResetSql() {
        final String sql;
        try {
            sql = Files.readString( MigrationApplier.findRepoRoot().resolve( "bin/db/kg-reset-machine-tier.sql" ) );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "cannot read kg-reset-machine-tier.sql", e );
        }
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "kg-reset-machine-tier.sql failed: " + e.getMessage(), e );
        }
    }

    private Set< String > nodeNames() {
        return nodes.getAllNodes( Tier.MACHINE ).stream().map( KgNode::name ).collect( Collectors.toCollection( TreeSet::new ) );
    }

    private Set< String > edgeKeys() {
        final Map< java.util.UUID, String > names = nodes.getAllNodes( Tier.MACHINE ).stream()
            .collect( Collectors.toMap( KgNode::id, KgNode::name ) );
        final List< KgEdge > all = edges.getAllEdges( Tier.MACHINE );
        return all.stream()
            .map( e -> names.get( e.sourceId() ) + "->" + names.get( e.targetId() ) + "@" + e.tier() )
            .collect( Collectors.toCollection( TreeSet::new ) );
    }

    @Test
    void reset_deletes_machine_tier_state_and_keeps_every_human_approved_row() {
        seedMixedGraph();
        assertEquals( Set.of( "Curated", "HumA", "HumB", "HumNode", "MachA", "MachB", "MachC", "MachE" ),
            nodeNames(), "fixture sanity" );
        final KgNode humA = nodes.getNodeByName( "HumA", true );
        assertEquals( Provenance.AI_INFERRED, humA.provenance(), "fixture sanity: approval keeps provenance" );
        assertEquals( "human", humA.tier(), "fixture sanity: approval flips the tier" );

        runResetSql();

        assertEquals( Set.of( "Curated", "HumA", "HumB", "HumNode", "MachE" ), nodeNames(),
            "only machine-tier ai-inferred nodes may go; human-approved ones keep their rows" );
        assertEquals( Set.of( "HumA->HumB@human", "HumA->MachE@human" ), edgeKeys(),
            "human-tier edges survive; every machine-tier edge is gone with its machine endpoint" );
        // The survivors are untouched.
        final KgNode after = nodes.getNodeByName( "HumA", true );
        assertEquals( Provenance.AI_INFERRED, after.provenance() );
        assertEquals( "human", after.tier() );
        assertEquals( humA.modified(), after.modified() );
    }

    @Test
    void reset_deletes_pending_proposals_but_keeps_human_verdicts() {
        seedMixedGraph();
        final long total = proposals.listProposals( null, null, 1000, 0 ).size();
        final long pending = proposals.listProposals( "pending", null, 1000, 0 ).size();
        assertTrue( pending > 0 && pending < total, "fixture sanity: a mix of pending and decided proposals" );

        runResetSql();

        assertEquals( 0, proposals.listProposals( "pending", null, 1000, 0 ).size(), "pending proposals go" );
        assertEquals( total - pending, proposals.listProposals( null, null, 1000, 0 ).size(),
            "approved and rejected proposals are human verdicts and stay" );
    }

    @Test
    void reset_is_idempotent() {
        seedMixedGraph();
        runResetSql();
        final Set< String > nodesAfterFirst = nodeNames();
        final Set< String > edgesAfterFirst = edgeKeys();

        runResetSql();

        assertEquals( nodesAfterFirst, nodeNames() );
        assertEquals( edgesAfterFirst, edgeKeys() );
    }
}
