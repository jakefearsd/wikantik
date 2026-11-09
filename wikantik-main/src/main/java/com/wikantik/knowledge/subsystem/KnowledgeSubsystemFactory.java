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
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.util.TextUtil;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the Knowledge subsystem's service graph from a
 * {@link KnowledgeSubsystem.Deps} bundle: the chunking pipeline and the
 * frontmatter-defaults page filter. The agent projection and retrieval-quality
 * runner are registered after this factory returns (see
 * {@code KnowledgeWiringHelper}); the retrieval trio is installed later still,
 * by {@code WikiEngine.patchContextRetrievalService}.
 *
 * <p>The factory deliberately produces the graph without mutating any engine
 * state, so a failed construction throws cleanly without leaving
 * half-registered managers behind.</p>
 */
public final class KnowledgeSubsystemFactory {

    private KnowledgeSubsystemFactory() {}

    /** Builds the Knowledge subsystem from its declared dependencies. */
    public static KnowledgeSubsystem.Services create( final KnowledgeSubsystem.Deps deps ) {
        final var persistence = deps.persistence();
        final var core        = deps.core();
        final var props       = core.properties().asProperties();
        final var spr         = core.systemPageRegistry();
        final var meterReg    = core.meterRegistry();

        final ContentChunkRepository contentChunkRepo = persistence.contentChunks();
        final ContentChunker chunker = new ContentChunker( new ContentChunker.Config(
            TextUtil.getIntegerProperty( props, "wikantik.chunker.max_tokens", 512 ),
            TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ),
            TextUtil.getIntegerProperty( props, "wikantik.chunker.fragment_floor_tokens", 24 ),
            TextUtil.getIntegerProperty( props, "wikantik.chunker.overlap_tokens", 40 ) ) );
        final ChunkProjector chunkProjector = meterReg != null
            ? new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ),
                meterReg )
            : new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ) );

        final FrontmatterDefaultsFilter fmDefaults = new FrontmatterDefaultsFilter(
            name -> spr != null && spr.isSystemPage( name ), props );

        // The post-construction services (ForAgentProjectionService, RetrievalQualityRunner)
        // are wired into the engine's manager registry AFTER this factory returns; WikiEngine
        // rebuilds the Services record with the live instances before stashing WikiSubsystems
        // on the ServletContext, while the retrieval trio (context retrieval + bundle +
        // briefing) is installed later into the shared `retrieval` holder by
        // WikiEngine.patchContextRetrievalService.
        return new KnowledgeSubsystem.Services(
            contentChunkRepo,
            chunkProjector,
            fmDefaults,
            /*forAgentProjectionService=*/ null,
            /*retrievalQualityRunner=*/    null,
            /*retrieval=*/                 new AtomicReference<>()
        );
    }

    // -------------------------------------------------------------------------
    // Side-effect-free rebuild adapter
    // -------------------------------------------------------------------------

    /**
     * Rebuilds a {@link KnowledgeSubsystem.Services} record from the engine's
     * manager registry, preferring live registry values over the existing snapshot
     * for every hot-swappable field.
     *
     * <p>This method is the side-effect-free complement to {@link #create}: it
     * never starts background work. For each field it delegates to
     * {@link #preferRegistry}, which simply reads the registry and falls back to
     * the existing instance — no new objects are constructed.</p>
     *
     * <p>If {@code existing} is {@code null} (first-init path, no prior snapshot),
     * delegates to {@link #readFromManagerRegistry}.</p>
     *
     * <p><b>ContextRetrievalService invariant:</b> the retrieval trio (context
     * retrieval plus the bundle and briefing assemblers derived from it) is
     * intentionally excluded from {@code setManager} rebuilds (see
     * {@code WikiEngine.SNAPSHOT_REBUILDERS}). The rebuild carries the existing
     * record's shared set-once {@code retrieval} holder forward by reference, so
     * an install performed before or after the rebuild is visible in both
     * snapshots. When {@code existing} is {@code null}, {@link #readFromManagerRegistry}
     * seeds the holder from the registry as a fallback (consistent with
     * {@code KnowledgeSubsystemBridge.rebuildFromManagers}).</p>
     */
    public static KnowledgeSubsystem.Services rebuildFromExisting(
            final WikiEngine engine,
            final KnowledgeSubsystem.Services existing ) {
        if ( existing == null ) {
            return readFromManagerRegistry( engine );
        }
        return new KnowledgeSubsystem.Services(
            preferRegistry( engine, ContentChunkRepository.class,     existing.contentChunkRepository() ),
            preferRegistry( engine, ChunkProjector.class,             existing.chunkProjector() ),
            preferRegistry( engine, FrontmatterDefaultsFilter.class,  existing.frontmatterDefaultsFilter() ),
            // memoized cache — preferRegistry reads the singleton, preserving cache state.
            preferRegistry( engine, ForAgentProjectionService.class,  existing.forAgentProjectionService() ),
            // nightly CI cron — preferRegistry reads the singleton, never re-schedules.
            preferRegistry( engine, RetrievalQualityRunner.class,     existing.retrievalQualityRunner() ),
            // retrieval — carry the SAME set-once holder forward (not a copy of its value):
            // the retrieval trio is excluded from setManager rebuilds by invariant
            // (WikiEngine.SNAPSHOT_REBUILDERS), and sharing the reference means a patch
            // installed before OR after this rebuild is visible in both snapshots.
            existing.retrieval()
        );
    }

    /**
     * Reads every {@link KnowledgeSubsystem.Services} field directly from the engine's
     * manager registry, with no existing snapshot to fall back on. No new objects
     * are constructed.
     */
    static KnowledgeSubsystem.Services readFromManagerRegistry( final WikiEngine engine ) {
        // Registry fallback for the retrieval service (test fixtures that setManager it);
        // bundle/briefing are never synthesized on this cold path — patch-seam only.
        final ContextRetrievalService ctxFromRegistry =
            engine.getManager( ContextRetrievalService.class );
        return new KnowledgeSubsystem.Services(
            engine.getManager( ContentChunkRepository.class ),
            engine.getManager( ChunkProjector.class ),
            engine.getManager( FrontmatterDefaultsFilter.class ),
            engine.getManager( ForAgentProjectionService.class ),
            engine.getManager( RetrievalQualityRunner.class ),
            // retrieval — seeded with the registry's ContextRetrievalService when present;
            // bundle/briefing stay null until the patch seam builds them. Null seed
            // collapses to an empty holder.
            new AtomicReference<>( ctxFromRegistry == null
                ? null
                : new KnowledgeSubsystem.RetrievalServices( ctxFromRegistry, null, null ) )
        );
    }

    /**
     * Returns the manager of type {@code klass} from the engine registry if
     * present, otherwise falls back to {@code existing}.
     */
    private static <T> T preferRegistry(
            final WikiEngine engine,
            final Class<T> klass,
            final T existing ) {
        final T fromRegistry = engine.getManager( klass );
        return fromRegistry != null ? fromRegistry : existing;
    }
}
