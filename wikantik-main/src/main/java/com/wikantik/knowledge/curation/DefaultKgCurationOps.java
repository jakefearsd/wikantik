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
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

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

    public DefaultKgCurationOps( final KnowledgeGraphService kg,
                                 final PageManager pages,
                                 final PageSaveHelper saver ) {
        this.kg = kg;
        this.pages = pages;
        this.saver = saver;
    }

    @Override
    public Optional<String> tryApproveProposal( final UUID proposalId, final String reviewedBy ) {
        try {
            final KgProposal approved = kg.approveProposal( proposalId, reviewedBy );
            if ( approved == null ) {
                return Optional.of( "Not found: " + proposalId );
            }
            writeFrontmatterIfEdge( approved );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryApproveProposal: proposal={} actor={}: {}",
                    proposalId, reviewedBy, e.getMessage() );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
        }
    }

    @Override
    public Optional<String> tryRejectProposal( final UUID proposalId, final String reviewedBy, final String reason ) {
        try {
            final KgProposal rejected = kg.rejectProposal( proposalId, reviewedBy, reason );
            if ( rejected == null ) {
                return Optional.of( "Not found: " + proposalId );
            }
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryRejectProposal: proposal={} actor={}: {}",
                    proposalId, reviewedBy, e.getMessage() );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
        }
    }

    @Override
    public Optional<String> tryJudgeProposal( final UUID proposalId, final String reviewedBy ) {
        try {
            kg.judgeNow( proposalId, reviewedBy );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryJudgeProposal: proposal={} actor={}: {}",
                    proposalId, reviewedBy, e.getMessage() );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Judge error" );
        }
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
            LOG.error( "Failed to write-back frontmatter for proposal {}: {}",
                    proposal.id(), e.getMessage(), e );
        }
    }
}
