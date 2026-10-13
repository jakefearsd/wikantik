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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.ontology.OntologyShaclValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-integrity guard: materialising a {@code new-edge} proposal must never rewrite an
 * endpoint node that already exists.
 *
 * <p>The edge path used to call {@code upsertNodeWithProvenance( name, "concept", ... )} for
 * both endpoints. That statement is {@code ON CONFLICT ( name ) DO UPDATE SET node_type =
 * EXCLUDED.node_type, provenance = EXCLUDED.provenance, properties = EXCLUDED.properties},
 * so approving an edge between two <em>already-known</em> entities silently reset each one to
 * {@code concept} / {@code ai-inferred} / {@code {}} — destroying extracted and human-curated
 * type data, and (because {@code implements} is SHACL-shaped to a {@code wk:Technology}
 * subject) turning a legitimate edge into one the write-time gate then refuses.</p>
 */
@RequiresPostgres
class KgMaterializationServiceExistingEndpointTest {

    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgMaterializationService svc;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "kg_proposal_reviews", "kg_edges", "kg_proposals", "kg_nodes" );
        final DataSource ds = PostgresTestDb.createDataSource();
        kgNodes     = new KgNodeRepository( ds );
        kgEdges     = new KgEdgeRepository( ds );
        kgProposals = new KgProposalRepository( ds );
        svc = new KgMaterializationService( kgNodes, kgEdges, kgProposals,
            new KgRejectionRepository( ds ), new OntologyShaclValidator() );
    }

    private KgProposal newEdge( final String sourcePage, final String source, final String target,
                                final String relationship ) {
        return kgProposals.insertProposal( "new-edge", sourcePage,
            Map.< String, Object >of( "source", source, "target", target, "relationship", relationship ),
            0.8, "" );
    }

    /** Reads a node regardless of tier / inclusion policy so assertions cannot be hidden by a filter. */
    private KgNode read( final String name ) {
        return kgNodes.getNodeByName( name, true );
    }

    private static void assertUnchanged( final KgNode before, final KgNode after ) {
        assertNotNull( after, "existing node must still be there" );
        assertEquals( before.id(), after.id() );
        assertEquals( before.nodeType(), after.nodeType(), "node_type must be preserved" );
        assertEquals( before.provenance(), after.provenance(), "provenance must be preserved" );
        assertEquals( before.properties(), after.properties(), "properties must be preserved" );
        assertEquals( before.tier(), after.tier(), "tier must be preserved" );
        assertEquals( before.sourcePage(), after.sourcePage(), "source_page must be preserved" );
        assertEquals( before.provenanceProposalId(), after.provenanceProposalId(),
            "provenance_proposal_id must be preserved" );
        assertEquals( before.modified(), after.modified(), "the row must not even be touched" );
    }

    @Test
    void new_edge_leaves_a_curated_existing_source_untouched_and_creates_only_the_missing_target() {
        kgNodes.upsertNode( "PostgreSQL", "technology", "DatabasesPage", Provenance.HUMAN_CURATED,
            Map.< String, Object >of( "aliases", "pg", "curatedBy", "jake" ) );
        final KgNode before = read( "PostgreSQL" );
        assertEquals( "technology", before.nodeType(), "fixture sanity" );

        final KgProposal p = newEdge( "SomeOtherPage", "PostgreSQL", "RelationalModel", "requires" );
        svc.materializeMachine( p );

        assertUnchanged( before, read( "PostgreSQL" ) );

        final KgNode created = read( "RelationalModel" );
        assertNotNull( created, "the missing endpoint must be created" );
        assertEquals( "concept", created.nodeType() );
        assertEquals( Provenance.AI_INFERRED, created.provenance() );
        assertEquals( "SomeOtherPage", created.sourcePage() );
        assertEquals( "machine", created.tier() );
        assertEquals( p.id(), created.provenanceProposalId() );
        assertTrue( created.properties().isEmpty() );

        final List< KgEdge > edges = kgEdges.getAllEdges( Tier.MACHINE );
        assertTrue( edges.stream().anyMatch( e -> "requires".equals( e.relationshipType() )
            && before.id().equals( e.sourceId() ) && created.id().equals( e.targetId() ) ),
            "the edge must connect the existing source to the newly created target" );
    }

    @Test
    void new_edge_leaves_an_existing_target_untouched_too() {
        kgNodes.upsertNode( "Kubernetes", "technology", "ContainersPage", Provenance.HUMAN_AUTHORED,
            Map.< String, Object >of( "aliases", "k8s" ) );
        final KgNode before = read( "Kubernetes" );

        svc.materializeMachine( newEdge( "SomePage", "Orchestration", "Kubernetes", "uses" ) );

        assertUnchanged( before, read( "Kubernetes" ) );
        assertEquals( "concept", read( "Orchestration" ).nodeType() );
    }

    @Test
    void existing_technology_node_still_satisfies_the_implements_shape() {
        // wk:ImplementsShape requires a wk:Technology subject. Before the fix the existing
        // technology node was reset to 'concept' by the upsert, so the SHACL gate then
        // (wrongly) refused this perfectly valid edge.
        kgNodes.upsertNode( "PostgreSQL", "technology", "DatabasesPage", Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode pg = read( "PostgreSQL" );

        svc.materializeMachine( newEdge( "SomePage", "PostgreSQL", "RelationalModel", "implements" ) );

        assertEquals( 0L, svc.skippedNonConformantCount(),
            "a technology subject must not be downgraded into a SHACL violation" );
        assertTrue( kgEdges.getAllEdges( Tier.MACHINE ).stream()
                .anyMatch( e -> "implements".equals( e.relationshipType() ) && pg.id().equals( e.sourceId() ) ),
            "the conformant implements edge must be materialised" );
        assertEquals( "technology", read( "PostgreSQL" ).nodeType() );
    }

    @Test
    void new_edge_leaves_a_machine_tier_node_from_another_proposal_untouched() {
        final KgProposal nodeProposal = kgProposals.insertProposal( "new-node", "ToolsPage",
            Map.< String, Object >of( "name", "Terraform", "nodeType", "technology",
                "properties", Map.< String, Object >of( "aliases", "tf" ) ),
            0.8, "" );
        svc.materializeMachine( nodeProposal );
        final KgNode before = read( "Terraform" );
        assertEquals( "technology", before.nodeType(), "fixture sanity" );

        svc.materializeMachine( newEdge( "UnrelatedPage", "Terraform", "InfrastructureAsCode", "implements" ) );

        assertUnchanged( before, read( "Terraform" ) );
        assertEquals( nodeProposal.id(), read( "Terraform" ).provenanceProposalId(),
            "retracting the new-edge proposal must not delete a node it did not create" );
    }

    @Test
    void replaying_an_edge_proposal_does_not_reset_its_own_endpoints() {
        final KgProposal p = newEdge( "SomePage", "ReplayA", "ReplayB", "uses" );
        svc.materializeMachine( p );
        // A later, richer enrichment of an endpoint (e.g. a re-typing by a curator) must survive a replay.
        kgNodes.upsertNode( "ReplayA", "technology", "SomePage", Provenance.HUMAN_CURATED,
            Map.< String, Object >of( "note", "curated" ) );
        final KgNode curated = read( "ReplayA" );

        svc.materializeMachine( p );

        assertUnchanged( curated, read( "ReplayA" ) );
    }
}
