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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class KgMaterializationServiceMaterializeTest {

    private DataSource ds;
    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgRejectionRepository kgRejections;
    private KgMaterializationService svc;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestDb.createDataSource();
        kgNodes      = new KgNodeRepository( ds );
        kgEdges      = new KgEdgeRepository( ds );
        kgProposals  = new KgProposalRepository( ds );
        kgRejections = new KgRejectionRepository( ds );
        svc = new KgMaterializationService( kgNodes, kgEdges, kgProposals, kgRejections,
            new com.wikantik.ontology.OntologyShaclValidator() );
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
            Map.<String, Object>of( "source", "Alpha", "target", "Beta", "relationship", "requires" ),
            0.8, "extractor reasoning" );

        svc.materializeMachine( p );

        final List< KgNode > all = kgNodes.getAllNodes( Tier.MACHINE );
        assertTrue( all.stream().anyMatch( n -> n.name().equals( "Alpha" ) && "machine".equals( n.tier() ) ) );
        assertTrue( all.stream().anyMatch( n -> n.name().equals( "Beta" ) && "machine".equals( n.tier() ) ) );

        final List< KgEdge > edges = kgEdges.getAllEdges( Tier.MACHINE );
        assertTrue( edges.stream().anyMatch( e -> "requires".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() )
            && "machine".equals( e.tier() ) ) );
    }

    /**
     * Regression guard from the 2026-09-26 Knowledge Graph audit: a {@code new-node}
     * proposal must create a {@code kg_nodes} row.
     *
     * <p>{@code materialize} hard-returned unless the proposal type was
     * {@code new-edge}, discarding every {@code new-node} proposal with only a DEBUG
     * line, so nodes existed solely as a side effect of edge materialisation. In
     * production that left <b>30,121 {@code new-node} proposals — 73% of the entire
     * proposal corpus, 19,603 of them machine-approved — producing exactly 0 nodes</b>,
     * including all 148 that a human had explicitly approved.</p>
     *
     * <p>{@code sourcePage} and {@code provenanceProposalId} are asserted deliberately,
     * not incidentally: {@code source_page} is null on 6,949 of 6,952 machine-tier
     * production nodes, and {@code provenance_proposal_id} is the only page-to-node
     * attribution path that actually works.</p>
     *
     * <p>Reads back through {@code getAllNodes( Tier.MACHINE )} rather than
     * {@code getNodeByName}, because the by-name read-back is subject to the
     * (default-exclude) KG inclusion policy and would report null for a row that was
     * in fact written.</p>
     */
    @Test
    void materializeMachine_new_node_inserts_the_entity_at_machine_tier() {
        final KgProposal p = kgProposals.insertProposal( "new-node", "Kubernetes101",
            Map.< String, Object >of(
                "name",       "Kubernetes",
                "nodeType",   "technology",
                "properties", Map.< String, Object >of( "aliases", "k8s" ),
                "extractor",  "ollama" ),
            0.8, "extractor reasoning" );

        svc.materializeMachine( p );

        final KgNode node = kgNodes.getAllNodes( Tier.MACHINE ).stream()
            .filter( n -> "Kubernetes".equals( n.name() ) )
            .findFirst().orElse( null );

        assertNotNull( node, "a new-node proposal must create a kg_nodes row" );
        assertEquals( "technology", node.nodeType(),
            "nodeType must come from the proposal, not fall back to 'concept'" );
        assertEquals( "Kubernetes101", node.sourcePage(),
            "the node must be attributed to the proposal's source page" );
        assertEquals( "machine", node.tier() );
        assertEquals( p.id(), node.provenanceProposalId(),
            "provenance must link the node back to its proposal" );
        assertEquals( "k8s", node.properties().get( "aliases" ),
            "proposed properties must be persisted, not dropped" );
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

    /**
     * Replaying a {@code new-node} proposal must not duplicate the node row.
     *
     * <p><b>Characterization test, not a red-green cycle.</b>
     * {@code upsertNodeWithProvenance} uses {@code ON CONFLICT ( name )}, so this passed
     * the first time it was run. It earns its place because the planned backfill replays
     * 19,603 already machine-approved proposals that were never materialised, and any of
     * them may be materialised more than once — idempotency is precisely the property
     * that makes that replay safe to re-run, so it is pinned rather than assumed.</p>
     *
     * <p>Asserts row count rather than event count, mirroring
     * {@link #materializeMachine_is_idempotent}. {@code fireKgChange} is unconditional on
     * the node path exactly as it is on the edge path, and {@code OntologyEntitySync}
     * coalesces then re-projects from current database state, so a repeat event is
     * harmless by design and pinning "one event on retry" would invent a requirement the
     * edge path does not hold itself to either.</p>
     */
    @Test
    void materializeMachine_new_node_is_idempotent() {
        final KgProposal p = kgProposals.insertProposal( "new-node", "Page",
            Map.<String, Object>of( "name", "Istio", "nodeType", "technology" ), 0.8, "" );

        svc.materializeMachine( p );
        svc.materializeMachine( p );

        final long count = kgNodes.getAllNodes( Tier.MACHINE ).stream()
            .filter( n -> "Istio".equals( n.name() )
                && p.id().equals( n.provenanceProposalId() ) ).count();
        assertEquals( 1L, count, "node must not be duplicated on replay" );
    }

    @Test
    void materializeMachine_skips_when_required_fields_missing() {
        final KgProposal p = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Only" ), 0.8, "" );

        svc.materializeMachine( p ); // should NOT throw, just no-op

        assertEquals( 0, kgNodes.getAllNodes( Tier.MACHINE ).size() );
    }

    @Test
    void materializeMachine_skips_ontology_nonconformant_edge_but_keeps_conformant() {
        // 'implements' between two freshly-materialized concept nodes violates wk:ImplementsShape
        // (subject must be a wk:Technology) — the write-time SHACL gate must skip it. 'requires'
        // has no shape and must still materialize. Proves the machine path enforces the ontology.
        final KgProposal bad = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "ConcA", "target", "ConcB", "relationship", "implements" ),
            0.8, "" );
        final KgProposal good = kgProposals.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "ConcC", "target", "ConcD", "relationship", "requires" ),
            0.8, "" );

        svc.materializeMachine( bad );
        svc.materializeMachine( good );

        final List< KgEdge > edges = kgEdges.getAllEdges( Tier.MACHINE );
        assertFalse( edges.stream().anyMatch( e -> "implements".equals( e.relationshipType() ) ),
            "non-conformant 'implements' edge must be skipped by the SHACL gate" );
        assertTrue( edges.stream().anyMatch( e -> "requires".equals( e.relationshipType() ) ),
            "conformant 'requires' edge must still be materialized" );
        assertEquals( 1L, svc.skippedNonConformantCount(),
            "exactly one edge should have been skipped by the ontology gate" );
    }

    /**
     * The default-skip branch must still hold for a proposal type nothing handles.
     *
     * <p>This previously used {@code new-node} as its example, which encoded the defect
     * fixed on 2026-09-26 — {@code materialize} discarding every {@code new-node}
     * proposal — as expected behaviour. {@code new-node} is now materialised (see
     * {@link #materializeMachine_new_node_inserts_the_entity_at_machine_tier}), so the
     * branch is pinned with a type no producer emits. {@code proposal_type} carries no
     * CHECK constraint, so a synthetic value persists cleanly.</p>
     */
    @Test
    void materializeMachine_skips_unsupported_proposal_type() {
        final KgProposal p = kgProposals.insertProposal( "new-attribute", "Page",
            Map.<String, Object>of( "name", "Solo" ), 0.8, "" );

        svc.materializeMachine( p );

        assertEquals( 0, kgNodes.getAllNodes( Tier.MACHINE ).size() );
    }

    @Test
    void materializeMachine_carries_the_proposal_source_page_onto_both_nodes() {
        // Provenance is what PublicProjectionFilter tests to keep entities extracted
        // from ACL-restricted pages out of the anonymous ontology. A node written
        // with a null source page is indistinguishable from a stub, so it leaks.
        final KgProposal p = kgProposals.insertProposal( "new-edge", "RestrictedPage",
            Map.<String, Object>of( "source", "Epsilon", "target", "Zeta", "relationship", "requires" ),
            0.8, "" );

        svc.materializeMachine( p );

        final List< KgNode > all = kgNodes.getAllNodes( Tier.MACHINE );
        assertEquals( "RestrictedPage",
            all.stream().filter( n -> "Epsilon".equals( n.name() ) ).findFirst().orElseThrow().sourcePage(),
            "materialized source node must carry the proposal's source page" );
        assertEquals( "RestrictedPage",
            all.stream().filter( n -> "Zeta".equals( n.name() ) ).findFirst().orElseThrow().sourcePage(),
            "materialized target node must carry the proposal's source page" );
    }
}
