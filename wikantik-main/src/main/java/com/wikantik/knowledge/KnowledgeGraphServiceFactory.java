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
package com.wikantik.knowledge;

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.util.TextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Factory that constructs the full set of knowledge-graph services
 * (repository, graph service, embeddings, hub proposals, filters) for a given
 * {@link DataSource} and {@link Properties}.
 *
 * <p>Pulled out of {@code WikiEngine.initKnowledgeGraph} so the wiring can be
 * reasoned about (and eventually tested) in isolation from engine lifecycle
 * concerns. Callers are responsible for registering the returned services in
 * their manager map and attaching the filters to the {@code FilterManager} —
 * the factory deliberately produces the graph without mutating engine state so
 * that a failed construction throws cleanly without leaving half-registered
 * managers behind.
 */
public final class KnowledgeGraphServiceFactory {

    private static final Logger LOG = LogManager.getLogger( KnowledgeGraphServiceFactory.class );

    /**
     * Bundle of services produced by {@link #create}. Each field holds a
     * fully-constructed component that the caller should register with the
     * engine's manager map and/or filter pipeline as appropriate.
     */
    public record Services(
        KnowledgeGraphService kgService,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        NodeMentionSimilarity nodeMentionSimilarity,
        MentionIndex mentionIndex,
        HubProposalRepository hubProposalRepo,
        HubProposalService hubProposalService,
        HubDiscoveryRepository hubDiscoveryRepo,
        HubDiscoveryService hubDiscoveryService,
        HubOverviewService hubOverviewService,
        ChunkProjector chunkProjector,
        ContentChunkRepository contentChunkRepo
    ) {}

    private KnowledgeGraphServiceFactory() {}

    /** Backwards-compatible overload — passes a no-op Lucene MLT. */
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper ) {
        return create( dataSource, props, spr, pageManager, saveHelper, null, null );
    }

    /** Backwards-compatible overload — no explicit MeterRegistry. */
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper,
                                    final HubOverviewService.LuceneMlt luceneMlt ) {
        return create( dataSource, props, spr, pageManager, saveHelper, luceneMlt, null );
    }

    /**
     * Builds the complete knowledge-graph service graph.
     *
     * <p>The optional {@code luceneMlt} seam is used by {@link HubOverviewService}
     * for its MoreLikeThis drilldown section; pass {@code null} (or use the 5-arg
     * overload) when Lucene is unavailable, and the service will fall back to an
     * empty MLT list.
     *
     * @param dataSource JNDI-resolved wiki database
     * @param props      engine properties (used for review percentile, chunker tuning, etc.)
     * @param spr        system-page registry (may be null — HubSyncFilter tolerates it)
     * @param pageManager page manager used by HubSyncFilter to fetch current page text
     * @param saveHelper save helper used by HubSyncFilter to write updated hub pages
     * @param luceneMlt  optional Lucene MoreLikeThis seam for hub overview drilldown
     * @param meterRegistry optional Micrometer registry used by {@link ChunkProjector}
     *                     so chunker metrics flow to the Prometheus scrape
     *                     endpoint; when {@code null} the projector falls back
     *                     to an in-process {@code SimpleMeterRegistry}.
     */
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper,
                                    final HubOverviewService.LuceneMlt luceneMlt,
                                    final MeterRegistry meterRegistry ) {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        final MentionIndex mentionIndex = new MentionIndex( dataSource );
        final DefaultKnowledgeGraphService kgService = new DefaultKnowledgeGraphService( repo, null, mentionIndex );
        final FrontmatterDefaultsFilter fmDefaults = new FrontmatterDefaultsFilter(
            name -> spr != null && spr.isSystemPage( name ), props );

        final HubSyncFilter hubSync = new HubSyncFilter(
            name -> {
                try {
                    final Page p = pageManager.getPage( name );
                    return p != null ? pageManager.getPureText( p ) : null;
                } catch ( final Exception e ) {
                    LOG.warn( "HubSyncFilter: failed to read page '{}': {}", name, e.getMessage() );
                    return null;
                }
            },
            ( name, content ) -> {
                try {
                    saveHelper.saveText( name, content,
                        SaveOptions.builder().changeNote( "Hub membership sync" ).build() );
                } catch ( final Exception e ) {
                    LOG.warn( "HubSyncFilter: failed to save page '{}': {}", name, e.getMessage() );
                }
            }
        );

        // Share the search-side embedding model code so the mention-centroid
        // reader picks the same vectors the hybrid retriever already stores.
        final String modelCode = EmbeddingConfig.fromProperties( props ).model().code();
        final NodeMentionSimilarity similarity = new NodeMentionSimilarity( dataSource, modelCode );

        final HubProposalRepository hubProposalRepo = new HubProposalRepository( dataSource );
        final HubProposalService hubProposalService = HubProposalService.builder()
            .kgRepo( repo )
            .proposalRepo( hubProposalRepo )
            .similarity( similarity )
            .reviewPercentileFromProperties( props )
            .build();

        final HubDiscoveryRepository hubDiscoveryRepo = new HubDiscoveryRepository( dataSource );
        final HubDiscoveryService hubDiscoveryService = HubDiscoveryService.builder()
            .kgRepo( repo )
            .discoveryRepo( hubDiscoveryRepo )
            .similarity( similarity )
            .propsFrom( props )
            .pageWriter( ( name, content ) -> saveHelper.saveText( name, content,
                SaveOptions.builder().changeNote( "Hub discovery: stub created" ).build() ) )
            .pageExists( name -> {
                try {
                    final Page p = pageManager.getPage( name );
                    return p != null;
                } catch ( final Exception e ) {
                    LOG.warn( "HubDiscoveryService pageExists: failed to check '{}': {}",
                        name, e.getMessage() );
                    return false;
                }
            } )
            .build();

        final HubOverviewService hubOverviewService = HubOverviewService.builder()
            .kgRepo( repo )
            .similarity( similarity )
            .pageManager( pageManager )
            .pageWriter( ( name, content ) -> saveHelper.saveText( name, content,
                SaveOptions.builder().changeNote( "Hub overview: member removed" ).build() ) )
            .luceneMlt( luceneMlt ) // null → builder uses no-op default
            .propsFrom( props )
            .build();

        final ContentChunkRepository contentChunkRepo = new ContentChunkRepository( dataSource );
        final ContentChunker chunker = new ContentChunker( new ContentChunker.Config(
            TextUtil.getIntegerProperty( props, "wikantik.chunker.max_tokens", 512 ),
            TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ) ) );
        final ChunkProjector chunkProjector = meterRegistry != null
            ? new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ),
                meterRegistry )
            : new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ) );

        return new Services( kgService, fmDefaults, hubSync,
            similarity, mentionIndex, hubProposalRepo, hubProposalService,
            hubDiscoveryRepo, hubDiscoveryService, hubOverviewService,
            chunkProjector, contentChunkRepo );
    }
}
