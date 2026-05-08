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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attributes chunk-entity mentions for accepted proposals that already resolve to
 * existing {@code kg_nodes} rows.
 *
 * <p>Factored out of {@link BootstrapEntityExtractionIndexer} as part of Phase 11
 * Ckpt 6 god-class decomposition. Names without an existing node are silently
 * skipped — mentions light up after admin approval creates the node.
 */
class MentionAttributionRunner {

    private static final Logger LOG = LogManager.getLogger( MentionAttributionRunner.class );

    private final KgNodeRepository               kgNodes;
    private final ChunkEntityMentionRepository   mentionRepo;
    private final MentionAttributor              mentionAttributor;
    private final String                         extractorCode;

    MentionAttributionRunner( final KgNodeRepository kgNodes,
                               final ChunkEntityMentionRepository mentionRepo,
                               final MentionAttributor mentionAttributor,
                               final String extractorCode ) {
        this.kgNodes          = kgNodes;
        this.mentionRepo      = mentionRepo;
        this.mentionAttributor = mentionAttributor;
        this.extractorCode    = extractorCode;
    }

    /**
     * Attributes mentions and returns the count of rows written, or 0 if nothing to do.
     *
     * @param outcomes    per-page extraction outcomes carrying the raw chunks
     * @param accepted    consolidated proposals that passed the judge
     * @param overwrite   when {@code true}, clear existing mentions for each chunk first
     * @return count of mention rows upserted
     */
    int attribute( final List< ExtractionBatchRunner.PageOutcome > outcomes,
                   final List< ConsolidatedProposal > accepted,
                   final boolean overwrite ) {
        if ( accepted.isEmpty() || outcomes.isEmpty() ) {
            return 0;
        }

        // Build (name -> nodeId) from accepted node-kind proposals that already have a row.
        final Map< String, UUID > nameToNodeId = new LinkedHashMap<>();
        for ( final ConsolidatedProposal cp : accepted ) {
            if ( cp.kind() != ConsolidatedProposal.Kind.NEW_NODE ) continue;
            final String name = cp.displayName();
            if ( name == null || nameToNodeId.containsKey( name ) ) continue;
            try {
                final KgNode existing = kgNodes.getNodeByName( name );
                if ( existing != null ) {
                    nameToNodeId.put( name, existing.id() );
                }
            } catch ( final RuntimeException e ) {
                LOG.debug( "getNodeByName failed for '{}': {}", name, e.getMessage() );
            }
        }
        if ( nameToNodeId.isEmpty() ) {
            LOG.info( "Bootstrap extraction: no accepted entity names map to existing kg_nodes — "
                + "mention attribution skipped (admin approval will materialize nodes)" );
            return 0;
        }

        final List< MentionAttributor.NameMapping > mappings = new ArrayList<>( nameToNodeId.size() );
        for ( final Map.Entry< String, UUID > e : nameToNodeId.entrySet() ) {
            mappings.add( new MentionAttributor.NameMapping( e.getValue(), e.getKey() ) );
        }

        final List< ChunkEntityMentionRepository.Row > rows = new ArrayList<>();
        for ( final ExtractionBatchRunner.PageOutcome po : outcomes ) {
            for ( final ContentChunkRepository.MentionableChunk c : po.chunks() ) {
                if ( overwrite ) {
                    try {
                        mentionRepo.deleteByChunkId( c.id() );
                    } catch ( final RuntimeException e ) {
                        LOG.warn( "Bootstrap extraction: clear mentions failed for chunk {}: {}",
                            c.id(), e.getMessage() );
                    }
                }
                final List< MentionAttributor.ChunkMention > attributed =
                    mentionAttributor.attribute( c.id(), c.text(), mappings );
                for ( final MentionAttributor.ChunkMention m : attributed ) {
                    rows.add( new ChunkEntityMentionRepository.Row(
                        m.chunkId(), m.nodeId(), /*confidence*/ 1.0, extractorCode ) );
                }
            }
        }

        if ( rows.isEmpty() ) {
            return 0;
        }
        try {
            final int written = mentionRepo.upsertAll( rows );
            LOG.info( "Bootstrap extraction: attributed {} mention rows across {} pages",
                written, outcomes.size() );
            return written;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: mention upsert failed ({}): {}",
                rows.size(), e.getMessage() );
            return 0;
        }
    }
}
