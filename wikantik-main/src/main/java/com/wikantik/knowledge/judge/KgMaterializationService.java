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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Single owner of proposal-driven writes to kg_nodes and kg_edges. Closes the
 * historical gap where approveProposal only flipped a status column.
 *
 * <p>Operations are idempotent: re-running on the same proposal is a no-op
 * thanks to ON CONFLICT in the underlying upserts.</p>
 */
public class KgMaterializationService {

    private static final Logger LOG = LogManager.getLogger( KgMaterializationService.class );

    private final JdbcKnowledgeRepository repo;

    public KgMaterializationService( final JdbcKnowledgeRepository repo ) {
        this.repo = Objects.requireNonNull( repo, "repo" );
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
                repo.deleteRejection( src, tgt, rel );
            }
        }
        // Insert if absent (preserves existing tier on conflict).
        materialize( proposal, "human" );
        // Then promote any pre-existing machine-tier rows to human.
        repo.updateTierByProvenance( proposal.id(), "human" );
    }

    /** Delete materialised rows for this proposal. Used when a human rejects a machine-approved edge. */
    public void retract( final KgProposal proposal ) {
        repo.deleteEdgesByProvenance( proposal.id() );
        repo.deleteNodesByProvenance( proposal.id() );
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

        final KgNode src = repo.upsertNodeWithProvenance( source, "concept", null,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        final KgNode tgt = repo.upsertNodeWithProvenance( target, "concept", null,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        repo.upsertEdgeWithProvenance( src.id(), tgt.id(), rel,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
    }
}
