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
        GraphProjector graphProjector,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        EmbeddingService embeddingService,
        HubProposalRepository hubProposalRepo,
        HubProposalService hubProposalService
    ) {}

    private KnowledgeGraphServiceFactory() {}

    /**
     * Builds the complete knowledge-graph service graph.
     *
     * @param dataSource JNDI-resolved wiki database
     * @param props      engine properties (used for retrain interval, review percentile, etc.)
     * @param spr        system-page registry (may be null — HubSyncFilter tolerates it)
     * @param pageManager page manager used by HubSyncFilter to fetch current page text
     * @param saveHelper save helper used by HubSyncFilter to write updated hub pages
     */
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper ) {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        final DefaultKnowledgeGraphService kgService = new DefaultKnowledgeGraphService( repo );
        final GraphProjector projector = new GraphProjector( kgService, spr );

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

        final EmbeddingRepository embeddingRepo = new EmbeddingRepository( dataSource );
        final ContentEmbeddingRepository contentEmbeddingRepo = new ContentEmbeddingRepository( dataSource );
        final EmbeddingService embeddingService = new EmbeddingService(
            repo, embeddingRepo, contentEmbeddingRepo, pageManager, spr );
        embeddingService.configure( props );

        final HubProposalRepository hubProposalRepo = new HubProposalRepository( dataSource );

        // Supplier indirection lets the service always see the latest trained content
        // model after EmbeddingService retrains, rather than capturing a stale reference.
        final HubProposalService hubProposalService = HubProposalService.builder()
            .kgRepo( repo )
            .proposalRepo( hubProposalRepo )
            .contentRepo( contentEmbeddingRepo )
            .reviewPercentileFromProperties( props )
            .contentModelSupplier( embeddingService::getCurrentContentModel )
            .build();

        return new Services( kgService, projector, fmDefaults, hubSync,
            embeddingService, hubProposalRepo, hubProposalService );
    }
}
