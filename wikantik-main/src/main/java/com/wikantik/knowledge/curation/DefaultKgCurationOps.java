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
     */
    void writeFrontmatterIfEdge( final KgProposal approved ) {
        // Filled in by Task 2 — placeholder no-op for now.
    }
}
