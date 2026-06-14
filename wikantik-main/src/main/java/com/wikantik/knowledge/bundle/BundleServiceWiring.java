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

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Single derivation point for the {@link BundleAssemblyService}, mirroring how
 * {@code kgCurationOps} is derived inside the knowledge-subsystem snapshot.
 *
 * <p>The bundle service is a thin assembly layer whose only post-startup
 * dependency is the {@link ContextRetrievalService} — which is wired into the
 * engine registry after boot by {@code ContextRetrievalServiceInitializer}.
 * So the bundle rides entirely on that service's lifecycle: it is built
 * whenever the retrieval service becomes live (see
 * {@code WikiEngine.patchContextRetrievalService}). Collaborators are passed in
 * (not resolved via {@code getManager}) so this stays a plain assembly helper
 * outside the service-locator allow-list. When retrieval is null, {@link #build}
 * returns {@code null} and the surfaces degrade to a 503 / unavailable response.</p>
 */
public final class BundleServiceWiring {

    private static final Logger LOG = LogManager.getLogger( BundleServiceWiring.class );

    /** Total ranked, cited sections returned in a bundle — the spike's top-N. */
    public static final int MAX_SECTIONS = 12;
    /** Per-page section shortlist depth — the per-page shortlist the spike sweep validated. */
    public static final int SECTIONS_PER_PAGE = 5;

    private BundleServiceWiring() {}

    /**
     * Builds a {@link DefaultBundleAssemblyService} from the supplied collaborators,
     * or {@code null} when {@code retrieval} is not yet wired. Never throws — a
     * missing collaborator degrades the relevant lookup to empty rather than failing.
     *
     * @param retrieval   the live context-retrieval service (null → returns null)
     * @param dao         slug → canonical_id source (null tolerated → empty)
     * @param pageManager slug → page version source (null tolerated → version 0)
     * @param props       reranker configuration source (null tolerated → defaults)
     */
    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props ) {
        if ( retrieval == null ) {
            LOG.debug( "ContextRetrievalService not yet wired — bundle assembly service unavailable" );
            return null;
        }
        final SectionReranker reranker = rerankerFor( props );

        final Function< String, Optional< String > > canonicalIdOf = slug ->
            dao == null ? Optional.empty()
                        : dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId );
        final Function< String, Integer > versionOf = slug -> {
            if ( pageManager == null ) return 0;
            final Page p = pageManager.getPage( slug );
            return p == null ? 0 : p.getVersion();
        };

        LOG.info( "Bundle assembly service wired (reranker={}, maxSections={}, sectionsPerPage={})",
            reranker instanceof LlmSectionReranker ? "on" : "off", MAX_SECTIONS, SECTIONS_PER_PAGE );
        return new DefaultBundleAssemblyService(
            retrieval, reranker, canonicalIdOf, versionOf, MAX_SECTIONS, SECTIONS_PER_PAGE );
    }

    /** Identity reranker: returns the (already relevance-sorted) input untouched. */
    private static final SectionReranker IDENTITY = ( query, sections ) -> sections;

    /**
     * Selects the section reranker from config. {@code wikantik.bundle.reranker.enabled}
     * defaults to {@code false}: the 2026-06-13 live measurement showed the listwise LLM
     * reranker is an ordering lever, not a recall lever (rerank == dense recall), at ~1.5s
     * per request — so the bundle ships dense-ordered by default and opts in to reranking.
     */
    static SectionReranker rerankerFor( final Properties props ) {
        final boolean enabled = props != null && Boolean.parseBoolean(
            props.getProperty( RerankerConfig.PREFIX + "enabled", "false" ) );
        return enabled ? new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) : IDENTITY;
    }
}
