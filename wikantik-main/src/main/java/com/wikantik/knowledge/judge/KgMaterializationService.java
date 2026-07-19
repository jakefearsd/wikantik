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
import com.wikantik.event.KgChangeEvent;
import com.wikantik.event.WikiEventManager;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Single owner of proposal-driven writes to kg_nodes and kg_edges. Closes the
 * historical gap where approveProposal only flipped a status column.
 *
 * <p>Operations are idempotent: re-running on the same proposal is a no-op
 * thanks to ON CONFLICT in the underlying upserts.</p>
 */
public class KgMaterializationService {

    private static final Logger LOG = LogManager.getLogger( KgMaterializationService.class );

    private final KgNodeRepository      nodes;
    private final KgEdgeRepository      edges;
    private final KgProposalRepository  proposals;
    private final KgRejectionRepository rejections;
    /** Write-time ontology gate; null = no SHACL enforcement (degrade gracefully). */
    private final com.wikantik.ontology.OntologyShaclValidator ontologyValidator;
    /** Cumulative count of machine edges skipped by the ontology gate (observability). */
    private final java.util.concurrent.atomic.AtomicLong skippedNonConformant =
            new java.util.concurrent.atomic.AtomicLong();

    public KgMaterializationService( final KgNodeRepository nodes,
                                      final KgEdgeRepository edges,
                                      final KgProposalRepository proposals,
                                      final KgRejectionRepository rejections,
                                      final com.wikantik.ontology.OntologyShaclValidator ontologyValidator ) {
        this.nodes      = Objects.requireNonNull( nodes, "nodes" );
        this.edges      = Objects.requireNonNull( edges, "edges" );
        this.proposals  = Objects.requireNonNull( proposals, "proposals" );
        this.rejections = Objects.requireNonNull( rejections, "rejections" );
        this.ontologyValidator = ontologyValidator;
    }

    public KgMaterializationService( final KgNodeRepository nodes,
                                      final KgEdgeRepository edges,
                                      final KgProposalRepository proposals,
                                      final KgRejectionRepository rejections ) {
        this( nodes, edges, proposals, rejections, null );
    }

    /** Total machine edges skipped by the write-time ontology gate since construction. */
    public long skippedNonConformantCount() {
        return skippedNonConformant.get();
    }

    /** Materialise the proposal at tier='machine'. Currently handles proposalType='new-edge'. */
    public void materializeMachine( final KgProposal proposal ) {
        materialize( proposal, "machine" );
    }

    /**
     * Promote (or insert) the proposal at tier='human'. Cleans up any
     * negative-knowledge entry (kg_rejections) for the same triple — a human
     * override removes the previously-recorded reject.
     */
    public void promoteToHuman( final KgProposal proposal ) {
        if ( "new-edge".equals( proposal.proposalType() ) ) {
            final Map< String, Object > data = proposal.proposedData();
            final String src = Objects.toString( data.get( "source" ), null );
            final String tgt = Objects.toString( data.get( "target" ), null );
            final String rel = Objects.toString( data.get( "relationship" ), null );
            if ( src != null && tgt != null && rel != null ) {
                rejections.deleteRejection( src, tgt, rel );
            }
        }
        // Insert if absent (preserves existing tier on conflict).
        materialize( proposal, "human" );
        // Then promote any pre-existing machine-tier rows to human.
        proposals.updateTierByProvenance( proposal.id(), "human" );
    }

    /** Delete materialised rows for this proposal. Used when a human rejects a machine-approved edge. */
    public void retract( final KgProposal proposal ) {
        final List< KgEdge > doomedEdges = edges.findEdgesByProvenance( proposal.id() );
        final List< UUID > doomedNodeIds = nodes.findNodeIdsByProvenance( proposal.id() );
        edges.deleteEdgesByProvenance( proposal.id() );
        nodes.deleteNodesByProvenance( proposal.id() );
        final Set< UUID > removed = new HashSet<>( doomedNodeIds );
        final Set< UUID > touched = new HashSet<>();
        for ( final KgEdge edge : doomedEdges ) {
            if ( !removed.contains( edge.sourceId() ) ) {
                touched.add( edge.sourceId() );
            }
        }
        fireKgChange( touched, removed );
    }

    /** Fires a KgChangeEvent with this service as the event-bus client; no-ops on empty payloads. */
    private void fireKgChange( final Set< UUID > touched, final Set< UUID > removed ) {
        if ( ( touched == null || touched.isEmpty() ) && ( removed == null || removed.isEmpty() ) ) {
            return;
        }
        WikiEventManager.fireEvent( this, new KgChangeEvent( this, touched, removed ) );
    }

    void materialize( final KgProposal proposal, final String tier ) {
        if ( !"new-edge".equals( proposal.proposalType() ) ) {
            LOG.debug( "materialize: skipping unsupported proposalType={}", proposal.proposalType() );
            return;
        }
        final Map< String, Object > data = proposal.proposedData();
        final String source = Objects.toString( data.get( "source" ), null );
        final String target = Objects.toString( data.get( "target" ), null );
        final String rel = Objects.toString( data.get( "relationship" ), null );
        if ( source == null || target == null || rel == null ) {
            LOG.warn( "materialize: missing source/target/relationship on proposal {}", proposal.id() );
            return;
        }

        final KgNode src = nodes.upsertNodeWithProvenance( source, "concept", null,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        final KgNode tgt = nodes.upsertNodeWithProvenance( target, "concept", null,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        // upsertNodeWithProvenance returns null when the post-INSERT read-back
        // is filtered out by the KG inclusion policy (the row IS in kg_nodes,
        // but its source-page cluster is excluded). Materialisation is a no-op
        // in that case — leave the edge unwritten and surface the reason so
        // operators can audit policy decisions instead of seeing a raw NPE.
        if ( src == null || tgt == null ) {
            LOG.warn( "materialize: skipping edge for proposal {} — node excluded by KG inclusion policy "
                + "(source='{}' present={}; target='{}' present={})",
                proposal.id(), source, src != null, target, tgt != null );
            // Whichever node WAS written is a durable change — fire for it.
            final Set< UUID > written = new HashSet<>();
            if ( src != null ) { written.add( src.id() ); }
            if ( tgt != null ) { written.add( tgt.id() ); }
            fireKgChange( written, Set.of() );
            return;
        }
        if ( ontologyValidator != null && src.nodeType() != null && tgt.nodeType() != null ) {
            final var violations = ontologyValidator.validateEdge( src.nodeType(), rel, tgt.nodeType() );
            if ( !violations.isEmpty() ) {
                skippedNonConformant.incrementAndGet();
                LOG.info( "materialize: rejected ontology-non-conformant edge for proposal {} "
                    + "({} --{}--> {}): {} [skipped by SHACL gate, count={}]",
                    proposal.id(), src.nodeType(), rel, tgt.nodeType(),
                    violations.get( 0 ).message(), skippedNonConformant.get() );
                // Both node upserts already happened — the SHACL gate only skipped the edge.
                fireKgChange( Set.of( src.id(), tgt.id() ), Set.of() );
                return;
            }
        }
        edges.upsertEdgeWithProvenance( src.id(), tgt.id(), rel,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        fireKgChange( Set.of( src.id(), tgt.id() ), Set.of() );
    }
}
