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
package com.wikantik.knowledge.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.admin.LuceneReindexQueue;
import com.wikantik.admin.LuceneSearchProviderAdapter;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.cache.CachingManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.agent.AgentHintsDeriver;
import com.wikantik.knowledge.agent.DefaultForAgentProjectionService;
import com.wikantik.knowledge.agent.ForAgentMetrics;
import com.wikantik.knowledge.agent.HubSummarySynthesizer;
import com.wikantik.knowledge.chunking.ChunkLifecycleListener;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.search.LuceneSearchProvider;
import com.wikantik.search.SearchManager;
import com.wikantik.util.TextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Wiring helpers for Knowledge-subsystem services that are built after
 * {@link KnowledgeSubsystemFactory#create} returns: {@link ForAgentProjectionService},
 * {@link ContentIndexRebuildService} and the chunk lifecycle listener.
 *
 * <p>Phase 9 Ckpt 4c of the wikantik-main decomposition. These methods were
 * previously private helpers on {@link WikiEngine}.</p>
 *
 * <p>Each method registers services via {@code engine.setManager(X.class, foo)}.</p>
 */
public final class KnowledgeWiringHelper {

    private static final Logger LOG = LogManager.getLogger( KnowledgeWiringHelper.class );

    private KnowledgeWiringHelper() {}

    // -----------------------------------------------------------------------
    // ForAgent + ContentIndexRebuild
    // -----------------------------------------------------------------------

    /**
     * Parameter object bundling the manager collaborators of
     * {@link #wireContentServices}.
     */
    public record ContentManagers( SearchManager searchMgr,
                                   MeterRegistry meterRegistry,
                                   PageManager pageManager,
                                   CachingManager cachingManager,
                                   ReferenceManager referenceManager ) {
    }

    /**
     * Wires {@link ForAgentProjectionService} and {@link ContentIndexRebuildService}.
     *
     * @return the {@link ContentIndexRebuildService} produced (may be {@code null}
     *         when Lucene is not the configured search provider)
     */
    public static ContentIndexRebuildService wireContentServices(
            final Properties props,
            final StructuralIndexService structuralIndex,
            final CoreSubsystem.Services coreSubsystem,
            final KnowledgeSubsystem.Services knowledgeSvcs,
            final ContentManagers managers,
            final WikiEngine engine ) {
        final SearchManager searchMgr = managers.searchMgr();
        final MeterRegistry meterRegistry = managers.meterRegistry();
        final PageManager pageManager = managers.pageManager();
        final CachingManager cachingManager = managers.cachingManager();
        final ReferenceManager referenceManager = managers.referenceManager();

        // ForAgentProjectionService
        final ForAgentMetrics forAgentMetrics = ForAgentMetrics.resolveAndBind();
        final AgentHintsDeriver hintsDeriver =
            new AgentHintsDeriver( structuralIndex, pageManager, referenceManager );
        final com.wikantik.citation.CitationRepository citationRepo =
            com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.fromLegacyEngine( engine )
                .citationRepository();
        final DefaultForAgentProjectionService forAgentService =
            new DefaultForAgentProjectionService(
                structuralIndex,
                pageManager,
                cachingManager,
                forAgentMetrics,
                hintsDeriver,
                new HubSummarySynthesizer(),
                citationRepo );
        engine.setManager( ForAgentProjectionService.class, forAgentService );
        LOG.info( "ForAgentProjectionService registered" );

        // ContentIndexRebuildService (Lucene-only)
        ContentIndexRebuildService rebuildService = null;
        if ( searchMgr != null && searchMgr.getSearchEngine() instanceof LuceneSearchProvider lsp ) {
            final LuceneReindexQueue queue = new LuceneSearchProviderAdapter( lsp );
            final ContentChunker rebuildChunker = new ContentChunker(
                new ContentChunker.Config(
                    TextUtil.getIntegerProperty( props, "wikantik.chunker.max_tokens", 512 ),
                    TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ),
                    TextUtil.getIntegerProperty( props, "wikantik.chunker.fragment_floor_tokens", 24 ),
                    TextUtil.getIntegerProperty( props, "wikantik.chunker.overlap_tokens", 40 ) ) );
            rebuildService = meterRegistry != null
                ? new ContentIndexRebuildService(
                    pageManager,
                    coreSubsystem.systemPageRegistry(),
                    queue,
                    knowledgeSvcs.contentChunkRepository(),
                    rebuildChunker,
                    () -> TextUtil.getBooleanProperty( props, "wikantik.rebuild.enabled", true ),
                    TextUtil.getIntegerProperty( props, "wikantik.rebuild.lucene_drain_poll_ms", 2000 ),
                    meterRegistry )
                : new ContentIndexRebuildService(
                    pageManager,
                    coreSubsystem.systemPageRegistry(),
                    queue,
                    knowledgeSvcs.contentChunkRepository(),
                    rebuildChunker,
                    () -> TextUtil.getBooleanProperty( props, "wikantik.rebuild.enabled", true ),
                    TextUtil.getIntegerProperty( props, "wikantik.rebuild.lucene_drain_poll_ms", 2000 ) );
            engine.setManager( ContentIndexRebuildService.class, rebuildService );
            LOG.info( "ContentIndexRebuildService registered" );
        } else {
            LOG.info( "ContentIndexRebuildService NOT registered — no LuceneSearchProvider in use" );
        }
        return rebuildService;
    }

    /**
     * Keeps {@code kg_content_chunks} in step with page deletes (chunks dropped) and renames
     * (chunks re-keyed). The listener is registered on the engine so it stays strongly reachable —
     * WikiEventManager holds listeners weakly.
     *
     * @return the registered listener, or {@code null} when there is no projector
     */
    public static ChunkLifecycleListener wireChunkLifecycle( final ChunkProjector chunkProjector,
                                                             final PageManager pageManager,
                                                             final com.wikantik.content.PageRenamer pageRenamer,
                                                             final WikiEngine engine ) {
        if ( chunkProjector == null ) {
            LOG.info( "Chunk lifecycle listener not wired: no ChunkProjector" );
            return null;
        }
        final ChunkLifecycleListener listener = new ChunkLifecycleListener( chunkProjector );
        listener.register( pageManager, pageRenamer );
        engine.setManager( ChunkLifecycleListener.class, listener );
        return listener;
    }
}
