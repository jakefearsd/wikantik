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
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.chunking.SectionAnchors;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import com.wikantik.util.WikiPageUrls;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
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
    /** Pages whose section anchors stay cached; above the corpus size, so steady state never re-parses. */
    static final int ANCHOR_CACHE_PAGES = 4096;
    /**
     * Default per-page section shortlist depth. Raised from 5 to 20 on 2026-06-14:
     * once contextual embeddings made section scores discriminative, the per-page
     * cap of 5 became the binding constraint and was discarding measured recall
     * (realized bundle recall@12 0.602 → 0.685 when loosened). Tunable via
     * {@code wikantik.bundle.sections_per_page}.
     */
    public static final int SECTIONS_PER_PAGE = 20;

    private BundleServiceWiring() {}

    /**
     * Builds a {@link DefaultBundleAssemblyService} from the supplied collaborators,
     * or {@code null} when {@code retrieval} is not yet wired. Never throws — a
     * missing collaborator degrades the relevant lookup to empty rather than failing.
     *
     * <p>When {@code sourceMap} is null or empty the service falls back to a page-gated
     * {@link RetrievalSectionSource} under {@link RetrievalMode#HYBRID}. Otherwise the
     * map is used as-is: each mode dispatches to its own {@link SectionCandidateSource},
     * with HYBRID as the default when a requested mode has no wired source.</p>
     *
     * @param retrieval  the live context-retrieval service — the build trigger (null → returns null)
     * @param sourceMap  per-mode candidate sources (null/empty → page-gated fallback)
     * @param dao        slug → canonical_id source (null tolerated → empty)
     * @param pageManager slug → page version source (null tolerated → version 0)
     * @param props      configuration source (null tolerated → defaults)
     */
    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final Map< RetrievalMode, SectionCandidateSource > sourceMap,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props ) {
        return build( retrieval, sourceMap, dao, pageManager, props, null );
    }

    /**
     * Overload accepting a {@code confidenceOf} lookup (slug → {@link Confidence}) so the
     * {@code metadata-boost} rerank stage can be requested via {@code wikantik.bundle.rerank.chain}.
     * The 5-arg {@link #build(ContextRetrievalService, Map, PageCanonicalIdsDao, PageManager, Properties)}
     * delegates here with {@code confidenceOf = null} (the stage then degrades to skipped).
     *
     * @param confidenceOf slug → {@link Confidence} lookup for the metadata-boost stage (null tolerated)
     */
    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final Map< RetrievalMode, SectionCandidateSource > sourceMap,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props,
                                               final Function< String, Confidence > confidenceOf ) {
        return build( retrieval, sourceMap, dao, pageManager, props, confidenceOf, null );
    }

    /**
     * Overload that also takes the structural index, so a slug is resolved to its canonical id from
     * memory instead of one {@code page_canonical_ids} query per section; the DAO is the fallback
     * for a slug the index does not hold.
     *
     * @param structuralIndex slug → canonical_id source tried before the DAO (null tolerated)
     */
    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final Map< RetrievalMode, SectionCandidateSource > sourceMap,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props,
                                               final Function< String, Confidence > confidenceOf,
                                               final StructuralIndexService structuralIndex ) {
        if ( retrieval == null ) {
            LOG.debug( "ContextRetrievalService not yet wired — bundle assembly service unavailable" );
            return null;
        }
        // dense.enabled=false → force page-gated path regardless of sourceMap content.
        // Null/empty map also falls back to page-gated (dense index unavailable or not configured).
        final boolean denseEnabled = props == null || Boolean.parseBoolean(
            props.getProperty( "wikantik.bundle.dense.enabled", "true" ) );
        final Map< RetrievalMode, SectionCandidateSource > sources =
            ( !denseEnabled || sourceMap == null || sourceMap.isEmpty() )
                ? Map.of( RetrievalMode.HYBRID, new RetrievalSectionSource( retrieval, sectionsPerPageFrom( props ) ) )
                : sourceMap;

        final SectionReranker reranker = BundleRerankers.rerankerFor( props, confidenceOf );
        final Function< String, Optional< String > > canonicalIdOf = canonicalIdResolver( dao, structuralIndex );
        final Function< String, Integer > versionOf = versionResolver( pageManager );

        final String rerankerLabel = BundleRerankers.label( reranker );
        final String kneeLabel = kneeEnabled( props ) ? ( "on/ratio=" + kneeRetainRatio( props ) ) : "off";
        final BundleDecompositionConfig decompositionConfig = BundleDecompositionConfig.fromProperties( props );
        final QueryPlanner planner = BundleRerankers.plannerFor( decompositionConfig );
        LOG.info( "Bundle assembly service wired (modes={}, reranker={}, maxSections={}, knee={}, decomposition={})",
            sources.keySet(), rerankerLabel, MAX_SECTIONS, kneeLabel, decompositionConfig.enabled() );

        // LLM-activity observability parity (Task 1.7): decorate whatever BundleRerankers.rerankerFor()/the
        // decomposition-config branch above already decided to construct — this happens AFTER
        // the wikantik.genai.mode ceiling logic has run, so it never influences that decision.
        // It only makes calls that DO happen (chat inference, when the ceiling allows it)
        // visible at /admin/llm-activity, mirroring RecordingEmbeddingClient/RecordingEntityExtractor/
        // RecordingKgProposalJudgeService at their own wiring points.
        final com.wikantik.llm.activity.LlmActivityLog activityLog =
            com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
        final SectionReranker recordedReranker =
            BundleRerankers.recordReranker( reranker, activityLog, RerankerConfig.fromProperties( props ).model() );
        final QueryPlanner recordedPlanner =
            BundleRerankers.recordPlanner( planner, activityLog, decompositionConfig.model() );

        return new DefaultBundleAssemblyService(
            new RetrievalRouting( sources, RetrievalMode.HYBRID ), recordedReranker,
            new CitationResolvers( canonicalIdOf, versionOf, sectionLinks( pageManager, props ) ), MAX_SECTIONS,
            coverageCalcFrom( props ), KneeCutoff.of( kneeEnabled( props ), kneeRetainRatio( props ) ),
            new QueryDecomposition( recordedPlanner,
                new SubQueryFusion( decompositionConfig.rrfK(),
                    "roundrobin".equalsIgnoreCase( decompositionConfig.fusion() )
                        ? SubQueryFusion.Mode.ROUND_ROBIN : SubQueryFusion.Mode.RRF ),
                decompositionConfig.enabled() ) );
    }


    /**
     * Slug → canonical id for citations: the structural index first (no query), then the DAO. A slug the
     * index holds only under an in-memory id (no authored canonical_id) goes to the DAO too, because that
     * id changes on every restart and a citation must not pin to it.
     */
    static Function< String, Optional< String > > canonicalIdResolver( final PageCanonicalIdsDao dao,
                                                                       final StructuralIndexService structuralIndex ) {
        return slug -> {
            final Optional< String > indexed = structuralIndex == null || hasSynthesisedId( structuralIndex, slug )
                ? Optional.empty() : structuralIndex.resolveCanonicalIdFromSlug( slug );
            if ( indexed.isPresent() || dao == null ) {
                return indexed;
            }
            return dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId );
        };
    }

    private static boolean hasSynthesisedId( final StructuralIndexService index, final String slug ) {
        for ( final StructuralConflict c : index.conflicts() ) {
            if ( c.kind() == StructuralConflict.Kind.MISSING_CANONICAL_ID && slug.equals( c.slug() ) ) {
                return true;
            }
        }
        return false;
    }

    /** Slug → current page version for citation pinning; 0 when the page manager is absent or the page is gone. */
    private static Function< String, Integer > versionResolver( final PageManager pageManager ) {
        return slug -> {
            if ( pageManager == null ) return 0;
            // Metadata-free: this lambda needs the version and nothing else, so it must not
            // trigger CachingProvider.refreshMetadata's flexmark parse (4.4% of the
            // refreshMetadata cost in the 2026-09-25 JFR, reached via citeAndDedup).
            final Page p = pageManager.getPageWithoutMetadata(
                slug, com.wikantik.api.providers.PageProvider.LATEST_VERSION );
            return p == null ? 0 : p.getVersion();
        };
    }

    /**
     * Slug → that page's heading-path → section-URL lookup: {@code wikantik.baseURL} (site-relative
     * when blank) + {@code /wiki/<slug>}, plus the page view's anchor for the section when the current
     * page still has that heading ({@link SectionAnchors}). A page that cannot be read still gets its
     * page link — a section link is a convenience and never fails a bundle.
     */
    static Function< String, Function< List< String >, String > > sectionLinks( final PageManager pageManager,
                                                                               final Properties props ) {
        final String base = WikiPageUrls.base( props, "" );
        final SectionAnchorCache anchorCache = new SectionAnchorCache( ANCHOR_CACHE_PAGES, SectionAnchors::byHeadingPath );
        return slug -> {
            final String pageUrl = WikiPageUrls.page( base, slug );
            final Map< List< String >, String > anchors = sectionAnchors( pageManager, anchorCache, slug );
            return headingPath -> {
                final String anchor = anchors.get( headingPath );
                return anchor == null ? pageUrl : pageUrl + "#" + anchor;
            };
        };
    }

    private static Map< List< String >, String > sectionAnchors( final PageManager pageManager,
                                                                final SectionAnchorCache anchorCache, final String slug ) {
        if ( pageManager == null ) {
            return Map.of();
        }
        try {
            return anchorCache.anchorsFor( slug, pageManager.getPureText( slug, PageProvider.LATEST_VERSION ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not read page '{}' to anchor its bundle sections; linking the page instead: {}",
                slug, e.getMessage() );
            return Map.of();
        }
    }

    /** Knee cutoff on/off, {@code wikantik.bundle.knee.enabled}, default false (fixed top-N). */
    static boolean kneeEnabled( final Properties props ) {
        return props != null && Boolean.parseBoolean( props.getProperty( "wikantik.bundle.knee.enabled", "false" ) );
    }

    /** Knee retain-ratio, {@code wikantik.bundle.knee.retain_ratio}, default 0.5. */
    static double kneeRetainRatio( final Properties props ) {
        if ( props == null ) return 0.5;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.knee.retain_ratio", 0.5 );
    }

    /** Coverage confidence thresholds from config (provisional defaults 0.55 / 0.40). */
    static BundleCoverageCalculator coverageCalcFrom( final Properties props ) {
        if ( props == null ) return BundleCoverageCalculator.defaults();
        final double strong = com.wikantik.util.TextUtil.getDoubleProperty(
            props, "wikantik.bundle.coverage.strong_similarity", BundleCoverageCalculator.DEFAULT_STRONG );
        final double partial = com.wikantik.util.TextUtil.getDoubleProperty(
            props, "wikantik.bundle.coverage.partial_similarity", BundleCoverageCalculator.DEFAULT_PARTIAL );
        return new BundleCoverageCalculator( strong, partial );
    }

    /** Per-page shortlist depth from {@code wikantik.bundle.sections_per_page}, default {@link #SECTIONS_PER_PAGE}. */
    static int sectionsPerPageFrom( final Properties props ) {
        if ( props == null ) return SECTIONS_PER_PAGE;
        final String raw = props.getProperty( "wikantik.bundle.sections_per_page" );
        if ( raw == null || raw.isBlank() ) return SECTIONS_PER_PAGE;
        try {
            final int v = Integer.parseInt( raw.trim() );
            return v > 0 ? v : SECTIONS_PER_PAGE;
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Invalid wikantik.bundle.sections_per_page '{}'; using {}", raw, SECTIONS_PER_PAGE );
            return SECTIONS_PER_PAGE;
        }
    }

}
