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
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Default implementation of {@link KgCurationOps}. Wraps {@link KnowledgeGraphService}
 * proposal primitives with {@code Optional<String>} error envelopes and owns any
 * required side effects (e.g. frontmatter write-back on edge-proposal approval).
 *
 * <p>Both the REST admin surface ({@code AdminKnowledgeResource}) and the
 * {@code /wikantik-admin-mcp} MCP tools MUST call into this facade to prevent
 * the two surfaces from drifting.
 */
public class DefaultKgCurationOps implements KgCurationOps {

    private static final Logger LOG = LogManager.getLogger( DefaultKgCurationOps.class );

    private final KnowledgeGraphService kg;
    private final PageManager pages;
    private final PageSaveHelper saver;
    private final KgExcludedPagesRepository excluded;
    /** Write-time ontology gate; null = no SHACL enforcement (degrade gracefully). */
    private final com.wikantik.ontology.OntologyShaclValidator ontologyValidator;

    public DefaultKgCurationOps( final KnowledgeGraphService kg,
                                 final PageManager pages,
                                 final PageSaveHelper saver,
                                 final KgExcludedPagesRepository excluded,
                                 final com.wikantik.ontology.OntologyShaclValidator ontologyValidator ) {
        this.kg = kg;
        this.pages = pages;
        this.saver = saver;
        this.excluded = excluded;
        this.ontologyValidator = ontologyValidator;
    }

    public DefaultKgCurationOps( final KnowledgeGraphService kg,
                                 final PageManager pages,
                                 final PageSaveHelper saver,
                                 final KgExcludedPagesRepository excluded ) {
        this( kg, pages, saver, excluded, null );
    }

    public DefaultKgCurationOps( final KnowledgeGraphService kg,
                                 final PageManager pages,
                                 final PageSaveHelper saver ) {
        this( kg, pages, saver, null, null );
    }

    @Override
    public KgCurationOps.ApproveOutcome tryApprove( final UUID proposalId, final String reviewedBy ) {
        return wrap( "tryApprove", "proposal=" + proposalId + " actor=" + reviewedBy,
                () -> {
                    final KgProposal approved = kg.approveProposal( proposalId, reviewedBy );
                    if ( approved == null ) return KgCurationOps.ApproveOutcome.fail( "Not found: " + proposalId );
                    writeFrontmatterIfEdge( approved );
                    final List< String > warnings = new ArrayList<>();
                    if ( excluded != null && approved.sourcePage() != null
                            && excluded.findReason( approved.sourcePage() ).isPresent() ) {
                        warnings.add( "source_page is in kg_excluded_pages list" );
                    }
                    return KgCurationOps.ApproveOutcome.ok( List.copyOf( warnings ) );
                },
                KgCurationOps.ApproveOutcome::fail );
    }

    @Override
    public Optional<String> tryRejectProposal( final UUID proposalId, final String reviewedBy, final String reason ) {
        return tryWithMessage( "tryRejectProposal", "proposal=" + proposalId + " actor=" + reviewedBy,
                () -> {
                    final KgProposal rejected = kg.rejectProposal( proposalId, reviewedBy, reason );
                    return rejected == null ? "Not found: " + proposalId : null;
                } );
    }

    @Override
    public Optional<String> tryJudgeProposal( final UUID proposalId, final String reviewedBy ) {
        return tryWithMessage( "tryJudgeProposal", "proposal=" + proposalId + " actor=" + reviewedBy,
                () -> { kg.judgeNow( proposalId, reviewedBy ); return null; } );
    }

    @Override
    public Optional<String> tryConfirmEdge( final UUID edgeId, final String actor ) {
        return tryWithMessage( "tryConfirmEdge", "edge=" + edgeId + " actor=" + actor,
                () -> {
                    final KgEdge after = kg.confirmEdge( edgeId, actor );
                    return after == null ? "Edge not found: " + edgeId : null;
                } );
    }

    @Override
    public Optional<String> tryDeleteEdge( final UUID edgeId, final String actor ) {
        return tryWithMessage( "tryDeleteEdge", "edge=" + edgeId + " actor=" + actor,
                () -> { kg.deleteEdge( edgeId ); return null; } );
    }

    @Override
    public Optional<String> tryDeleteAndRejectEdge( final UUID edgeId, final String actor, final String reason ) {
        return tryWithMessage( "tryDeleteAndRejectEdge", "edge=" + edgeId + " actor=" + actor,
                () -> { kg.deleteEdgeAndRecordRejection( edgeId, actor, reason ); return null; } );
    }

    @Override
    public EdgeResult tryUpsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                                     final Map<String, Object> properties, final String actor ) {
        return wrap( "tryUpsertEdge",
                "src=" + sourceId + " tgt=" + targetId + " rel=" + relationshipType + " actor=" + actor,
                () -> {
                    final Optional< String > shaclRefusal = ontologyRefusal( sourceId, targetId, relationshipType );
                    if ( shaclRefusal.isPresent() ) {
                        return EdgeResult.fail( shaclRefusal.get() );
                    }
                    final KgEdge edge = kg.upsertEdge( sourceId, targetId, relationshipType,
                            Provenance.HUMAN_CURATED,
                            properties == null ? Map.of() : properties );
                    if ( edge == null ) {
                        // KgEdgeRepository returns null when the mixed page/entity guard fires (or
                        // a similar fail-closed policy rejects the write). Surface an explicit
                        // refusal so the calling agent can self-correct instead of seeing an NPE.
                        return EdgeResult.fail( "edge rejected: endpoints cross the page/entity boundary "
                                + "(mixed page<->entity edges disallowed since 2026-05-11). "
                                + "Use homogeneous endpoints — page->page or entity->entity." );
                    }
                    return EdgeResult.ok( edge.id() );
                },
                EdgeResult::fail );
    }

    /**
     * Write-time ontology gate: builds a typed mini-graph for the candidate edge and runs it through
     * the bundled SHACL shapes. Returns an explicit refusal string when a shape is violated, empty
     * otherwise. Degrades to "no opinion" (empty) when the validator is absent or either endpoint
     * cannot be resolved — the gate only refuses on a positive, fully-typed violation.
     */
    private Optional< String > ontologyRefusal( final UUID sourceId, final UUID targetId,
                                                final String relationshipType ) {
        if ( ontologyValidator == null ) return Optional.empty();
        final KgNode src = kg.getNode( sourceId );
        final KgNode tgt = kg.getNode( targetId );
        if ( src == null || tgt == null || src.nodeType() == null || tgt.nodeType() == null ) {
            return Optional.empty();
        }
        final List< com.wikantik.ontology.OntologyShaclValidator.Violation > violations =
                ontologyValidator.validateEdge( src.nodeType(), relationshipType, tgt.nodeType() );
        if ( violations.isEmpty() ) return Optional.empty();
        final String reason = violations.get( 0 ).message();
        LOG.warn( "Ontology gate refused edge {} --{}--> {} ({} --> {}): {}",
                sourceId, relationshipType, targetId, src.nodeType(), tgt.nodeType(), reason );
        return Optional.of( "edge rejected: violates the wk: ontology SHACL shape for '"
                + relationshipType + "' — a '" + src.nodeType() + "' subject is not permitted. "
                + "Detail: " + reason );
    }

    @Override
    public Optional<String> tryDeleteNode( final UUID nodeId, final String actor ) {
        return tryWithMessage( "tryDeleteNode", "node=" + nodeId + " actor=" + actor,
                () -> { kg.deleteNode( nodeId ); return null; } );
    }

    @Override
    public Optional<String> tryMergeNodes( final UUID sourceId, final UUID targetId, final String actor ) {
        if ( sourceId == null || targetId == null ) return Optional.of( "source_id and target_id are required" );
        if ( sourceId.equals( targetId ) ) return Optional.of( "source_id and target_id are the same" );
        return tryWithMessage( "tryMergeNodes",
                "src=" + sourceId + " tgt=" + targetId + " actor=" + actor,
                () -> { kg.mergeNodes( sourceId, targetId ); return null; } );
    }

    @Override
    public NodeResult tryUpsertNode( final String name, final String nodeType, final String sourcePage,
                                     final Map<String, Object> properties, final String actor ) {
        return wrap( "tryUpsertNode", "name=" + name + " actor=" + actor,
                () -> {
                    final com.wikantik.api.knowledge.KgNode node = kg.upsertNode( name, nodeType, sourcePage,
                            Provenance.HUMAN_AUTHORED,
                            properties == null ? Map.of() : properties );
                    if ( node == null ) {
                        return NodeResult.fail( "node not visible after insert (excluded source page or other policy filter)" );
                    }
                    return NodeResult.ok( node.id() );
                },
                NodeResult::fail );
    }

    /**
     * Runs {@code body}; on any exception, logs at warn with {@code opName}+{@code ctx} and
     * wraps the deepest cause-chain message via {@code failBuilder}. Single place where
     * curation primitives unwrap JDBC duplicate-key text so admin surfaces never lose it.
     */
    private <T> T wrap( final String opName, final Object ctx,
                        final ThrowingSupplier< T > body,
                        final Function< String, T > failBuilder ) {
        try {
            return body.get();
        } catch ( final Exception e ) {
            LOG.warn( "{}: ctx={}: {}", opName, ctx, e.getMessage() );
            return failBuilder.apply( causeChainMessage( e ) );
        }
    }

    /**
     * Wraps a body whose return contract is {@code null}-on-success, {@code "error-text"}-on-logical-failure.
     * Translates to {@code Optional.empty()} / {@code Optional.of(...)} and routes thrown
     * exceptions through {@link #causeChainMessage}.
     */
    private Optional< String > tryWithMessage( final String opName, final Object ctx,
                                                final ThrowingSupplier< String > body ) {
        return wrap( opName, ctx, () -> {
            final String err = body.get();
            return err == null ? Optional.< String >empty() : Optional.of( err );
        }, Optional::of );
    }

    /**
     * Walks the exception cause chain and returns the deepest (most-specific) non-blank
     * message found, or {@code "Internal error"} if none is available. Returning the
     * deepest message ensures that JDBC-level details (e.g. "duplicate key value violates
     * unique constraint") are not hidden by generic wrapper messages produced by the
     * persistence layer.
     */
    private static String causeChainMessage( final Throwable t ) {
        String result = "Internal error";
        Throwable current = t;
        while ( current != null ) {
            if ( current.getMessage() != null && !current.getMessage().isBlank() ) {
                result = current.getMessage();
            }
            current = current.getCause();
        }
        return result;
    }

    @FunctionalInterface
    private interface ThrowingSupplier< T > {
        T get() throws Exception;
    }

    /**
     * After approving a {@code new-edge} proposal, writes the approved relationship
     * back into the source page's frontmatter. Body lifted from {@code AdminKnowledgeResource}.
     *
     * <p>package-private: invoked from tryApproveProposal; visibility kept narrow to discourage direct callers.
     */
    @SuppressWarnings( "unchecked" )
    void writeFrontmatterIfEdge( final KgProposal proposal ) {
        if ( !"new-edge".equals( proposal.proposalType() ) || proposal.sourcePage() == null ) return;

        final java.util.Map< String, Object > data = proposal.proposedData();
        if ( data == null ) return;

        final String target = ( String ) data.get( "target" );
        final String relationship = ( String ) data.get( "relationship" );
        if ( target == null || relationship == null ) return;

        try {
            final String pageName = proposal.sourcePage().replace( ".md", "" );
            final String pageText = pages.getPureText( pageName,
                    com.wikantik.api.providers.PageProvider.LATEST_VERSION );
            if ( pageText == null ) {
                LOG.warn( "Cannot write-back to page '{}': page not found", pageName );
                return;
            }

            final com.wikantik.api.frontmatter.ParsedPage parsed =
                    com.wikantik.api.frontmatter.FrontmatterParser.parse( pageText );
            final java.util.Map< String, Object > metadata =
                    new java.util.LinkedHashMap<>( parsed.metadata() );

            // Add the target to the relationship key (create if needed); deduplicate
            final Object existing = metadata.get( relationship );
            if ( existing instanceof java.util.List ) {
                final java.util.List< String > list =
                        new java.util.ArrayList<>( ( java.util.List< String > ) existing );
                if ( !list.contains( target ) ) list.add( target );
                metadata.put( relationship, list );
            } else {
                metadata.put( relationship, new java.util.ArrayList<>( java.util.List.of( target ) ) );
            }

            final String updated = com.wikantik.api.frontmatter.FrontmatterWriter.write(
                    metadata, parsed.body() );
            final com.wikantik.api.pages.SaveOptions opts =
                    com.wikantik.api.pages.SaveOptions.builder()
                            .author( "Knowledge Admin" )
                            .changeNote( "Approved knowledge proposal: " + relationship + " → " + target )
                            .build();
            saver.saveText( pageName, updated, opts );
            LOG.info( "Frontmatter write-back: added {} → {} to page '{}'",
                    relationship, target, pageName );
        } catch ( final com.wikantik.api.exceptions.WikiException e ) {
            // LOG.error justified: frontmatter write-back failure indicates data inconsistency between the approved proposal and the wiki page store — operator must investigate.
            LOG.error( "Failed to write-back frontmatter for proposal {}: {}",
                    proposal.id(), e.getMessage(), e );
        }
    }
}
