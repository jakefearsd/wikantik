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
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.knowledge.ContextRetrievalService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Orchestrates retrieve → per-page shortlist → rerank → dedup → top-N → cite (ADR-0001/0003/0005). */
public final class DefaultBundleAssemblyService implements BundleAssemblyService {

    private static final Logger LOG = LogManager.getLogger( DefaultBundleAssemblyService.class );

    private final Map< RetrievalMode, SectionCandidateSource > sources;
    private final RetrievalMode defaultMode;
    private final SectionReranker reranker;
    private final Function< String, Optional< String > > canonicalIdOf;  // slug -> canonical_id
    private final Function< String, Integer > versionOf;                  // slug -> page version
    private final int maxSections;
    private final BundleCoverageCalculator coverageCalc;
    private final KneeCutoff knee;
    private final QueryPlanner planner;
    private final SubQueryFusion fusion;
    private final boolean decompositionEnabled;

    /**
     * Page-gated constructor (retained for back-compat): wraps hybrid retrieval +
     * per-page section shortlist in a {@link RetrievalSectionSource}.
     */
    public DefaultBundleAssemblyService( final ContextRetrievalService retrieval,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final int sectionsPerPage ) {
        this( new RetrievalSectionSource( retrieval, sectionsPerPage ),
              reranker, canonicalIdOf, versionOf, maxSections );
    }

    /**
     * Source-based constructor — the {@link SectionCandidateSource} decides page-gated
     * ({@link RetrievalSectionSource}) vs global dense-chunk ({@link DenseChunkSectionSource}).
     * Registers the source under {@link RetrievalMode#HYBRID} with HYBRID as the default mode.
     * Uses {@link BundleCoverageCalculator#defaults()} for the coverage thresholds.
     */
    public DefaultBundleAssemblyService( final SectionCandidateSource source,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections ) {
        this( source, reranker, canonicalIdOf, versionOf, maxSections,
              BundleCoverageCalculator.defaults() );
    }

    /**
     * Source-based constructor with explicit coverage thresholds.
     * Registers the source under {@link RetrievalMode#HYBRID} with HYBRID as the default mode.
     */
    public DefaultBundleAssemblyService( final SectionCandidateSource source,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final BundleCoverageCalculator coverageCalc ) {
        this( Map.of( RetrievalMode.HYBRID, source ), RetrievalMode.HYBRID,
              reranker, canonicalIdOf, versionOf, maxSections, coverageCalc );
    }

    /**
     * Map-based constructor — each {@link RetrievalMode} may have its own {@link SectionCandidateSource}.
     * Requests for a mode with no wired source degrade to the default mode's source with a single warn log.
     * Delegates to the knee-aware canonical constructor with {@link KneeCutoff#disabled()} (fixed
     * top-N — byte-identical to pre-knee behaviour).
     */
    public DefaultBundleAssemblyService( final Map< RetrievalMode, SectionCandidateSource > sources,
                                         final RetrievalMode defaultMode,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final BundleCoverageCalculator coverageCalc ) {
        this( sources, defaultMode, reranker, canonicalIdOf, versionOf, maxSections, coverageCalc,
              KneeCutoff.disabled() );
    }

    /**
     * Canonical constructor — each {@link RetrievalMode} may have its own {@link SectionCandidateSource},
     * and a {@link KneeCutoff} dynamically shortens the top-N output loop when relevance falls off a
     * cliff. Requests for a mode with no wired source degrade to the default mode's source with a
     * single warn log.
     */
    public DefaultBundleAssemblyService( final Map< RetrievalMode, SectionCandidateSource > sources,
                                         final RetrievalMode defaultMode,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final BundleCoverageCalculator coverageCalc,
                                         final KneeCutoff knee ) {
        this( new RetrievalRouting( sources, defaultMode ), reranker,
              new CitationResolvers( canonicalIdOf, versionOf ), maxSections, coverageCalc, knee,
              QueryDecomposition.disabled() );
    }

    /**
     * Canonical constructor — routing to a per-{@link RetrievalMode} {@link SectionCandidateSource}
     * ({@link RetrievalRouting}), the slug→citation-identity resolvers ({@link CitationResolvers}),
     * and (default off) structure-conditional query decomposition ({@link QueryDecomposition}):
     * when {@code decomposition.enabled()} and {@link QueryStructureHeuristic#looksMultiPart(String)}
     * both hold, {@code decomposition.planner()} splits the query into sub-queries whose per-query
     * candidates are fused via {@code decomposition.fusion()} before reranking. Grouped into these
     * parameter objects to keep the parameter list manageable — see the convenience constructors
     * above for the common cases.
     */
    public DefaultBundleAssemblyService( final RetrievalRouting routing,
                                         final SectionReranker reranker,
                                         final CitationResolvers citations,
                                         final int maxSections,
                                         final BundleCoverageCalculator coverageCalc,
                                         final KneeCutoff knee,
                                         final QueryDecomposition decomposition ) {
        Objects.requireNonNull( routing.sources().get( routing.defaultMode() ),
            "defaultMode must be present in sources" );
        this.sources = Map.copyOf( routing.sources() );
        this.defaultMode = routing.defaultMode();
        this.reranker = reranker;
        this.canonicalIdOf = citations.canonicalIdOf();
        this.versionOf = citations.versionOf();
        this.maxSections = maxSections;
        this.coverageCalc = coverageCalc;
        this.knee = knee;
        this.planner = decomposition.planner();
        this.fusion = decomposition.fusion();
        this.decompositionEnabled = decomposition.enabled();
    }

    @Override
    public ContextBundle assemble( final String query ) {
        return assemble( query, defaultMode );
    }

    @Override
    public ContextBundle assemble( final String query, final RetrievalMode mode ) {
        SectionCandidateSource src = sources.get( mode );
        if ( src == null ) {
            LOG.warn( "Retrieval mode {} has no wired source; degrading to default {}", mode, defaultMode );
            src = sources.get( defaultMode );
        }
        SectionCandidates cand = src.candidates( query );
        if ( decompositionEnabled && QueryStructureHeuristic.looksMultiPart( query ) ) {
            cand = decompose( query, src, cand );
        }
        final List< CandidateSection > ranked = reranker.rerank( query, cand.sections() );
        // knee only valid on cosine-scale dense candidates; and it counts dense-ranked N applied
        // to the reranked output — rerank picks which/order, knee picks how many.
        final int cut = cand.denseCosineScale()
            ? knee.effectiveN( cand.sections(), cand.topSimilarity(), maxSections )
            : maxSections;
        final List< BundleSection > out = citeAndDedup( ranked, cut );
        return new ContextBundle( query, out, coverageCalc.compute( cand.topSimilarity(), out ) );
    }

    /**
     * Splits {@code query} into sub-queries via {@link #planner} and fuses their per-query
     * candidates (plus the already-retrieved {@code original} pass) via {@link #fusion}. Returns
     * {@code original} unchanged when the planner degenerates to a single (sub-)query.
     */
    private SectionCandidates decompose( final String query, final SectionCandidateSource src,
                                         final SectionCandidates original ) {
        final List< String > subs = planner.plan( query );     // fail-closed -> [query]
        if ( subs.size() <= 1 ) return original;
        final List< SectionCandidates > perQuery = new ArrayList<>();
        perQuery.add( original );                               // include the original pass
        for ( final String sq : subs ) {
            // Guard each sub-query retrieval: a transient embedder/index error on one sub-query
            // must not abort a bundle single-pass would have returned. Skip the failed sub-query
            // and fuse whatever succeeded (the original is already in perQuery, so worst case
            // degrades to single-pass — "helps-or-no-ops").
            try {
                perQuery.add( src.candidates( sq ) );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Sub-query retrieval failed for '{}' (skipping): {}", sq, e.getMessage() );
            }
        }
        final SectionCandidates fused = fusion.fuse( perQuery );
        LOG.info( "Bundle decomposition: '{}' -> {} sub-queries, fused {} sections",
            query, subs.size(), fused.sections().size() );
        return fused;
    }

    /** Dedup by (slug, heading-path), drop un-citable sections, cite, and cap at {@code cut}. */
    private List< BundleSection > citeAndDedup( final List< CandidateSection > ranked, final int cut ) {
        final Set< SectionKey > seen = new LinkedHashSet<>();
        final List< BundleSection > out = new ArrayList<>();
        for ( final CandidateSection cs : ranked ) {
            if ( !seen.add( new SectionKey( cs.slug(), cs.headingPath() ) ) ) continue;  // dedup by (slug, heading-path)
            final String canonical = canonicalIdOf.apply( cs.slug() ).orElse( null );
            if ( canonical == null ) continue;         // can't cite an un-versioned page; skip
            final CitationHandle cite = new CitationHandle(
                canonical, versionOf.apply( cs.slug() ), cs.headingPath(), cs.text(), sha256( cs.text() ) );
            out.add( new BundleSection( canonical, cs.slug(), cs.headingPath(), cs.text(), cs.denseScore(), cite ) );
            if ( out.size() >= cut ) break;             // top-N (dynamic when knee enabled)
        }
        return out;
    }

    static String sha256( final String text ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( text.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
