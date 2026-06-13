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
package com.wikantik.knowledge.bundle;

import com.wikantik.WikiEngine;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.function.Function;

/**
 * Single derivation point for the {@link BundleAssemblyService}, mirroring how
 * {@code kgCurationOps} is derived inside the knowledge-subsystem snapshot.
 *
 * <p>The bundle service is a thin assembly layer whose only post-startup
 * dependency is the {@link ContextRetrievalService} — which is wired into the
 * engine registry after boot by {@code ContextRetrievalServiceInitializer}.
 * So the bundle rides entirely on that service's lifecycle: it is (re)built
 * whenever the retrieval service becomes live (see
 * {@code WikiEngine.patchContextRetrievalService} for the REST stash and
 * {@code KnowledgeSubsystemFactory.readFromManagerRegistry} for the MCP bridge).
 * When retrieval is not yet wired, {@link #build} returns {@code null} and the
 * surfaces degrade to a 503 / unavailable response.</p>
 */
public final class BundleServiceWiring {

    private static final Logger LOG = LogManager.getLogger( BundleServiceWiring.class );

    /** Total ranked, cited sections returned in a bundle — the spike's top-N. */
    public static final int MAX_SECTIONS = 12;
    /** Per-page section shortlist depth — the per-page shortlist the spike sweep validated. */
    public static final int SECTIONS_PER_PAGE = 5;

    private BundleServiceWiring() {}

    /**
     * Builds a {@link DefaultBundleAssemblyService} from the engine's live
     * managers, or {@code null} when the retrieval service is not yet wired.
     * Never throws — a missing dependency degrades to {@code null}.
     */
    public static BundleAssemblyService build( final WikiEngine engine ) {
        if ( engine == null ) return null;
        final ContextRetrievalService retrieval = engine.getManager( ContextRetrievalService.class );
        if ( retrieval == null ) {
            LOG.debug( "ContextRetrievalService not yet wired — bundle assembly service unavailable" );
            return null;
        }
        final PageCanonicalIdsDao dao = engine.pageCanonicalIdsDao();
        final PageManager pageManager = engine.getManager( PageManager.class );
        final RerankerConfig cfg = RerankerConfig.fromProperties( engine.getWikiProperties() );
        final SectionReranker reranker = new LlmSectionReranker( cfg );

        final Function< String, Optional< String > > canonicalIdOf = slug ->
            dao == null ? Optional.empty()
                        : dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId );
        final Function< String, Integer > versionOf = slug -> {
            if ( pageManager == null ) return 0;
            final Page p = pageManager.getPage( slug );
            return p == null ? 0 : p.getVersion();
        };

        LOG.info( "Bundle assembly service wired (reranker model={}, maxSections={}, sectionsPerPage={})",
            cfg.model(), MAX_SECTIONS, SECTIONS_PER_PAGE );
        return new DefaultBundleAssemblyService(
            retrieval, reranker, canonicalIdOf, versionOf, MAX_SECTIONS, SECTIONS_PER_PAGE );
    }
}
