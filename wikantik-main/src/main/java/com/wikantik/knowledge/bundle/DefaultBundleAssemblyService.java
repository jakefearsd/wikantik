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
        Objects.requireNonNull( sources.get( defaultMode ), "defaultMode must be present in sources" );
        this.sources = Map.copyOf( sources );
        this.defaultMode = defaultMode;
        this.reranker = reranker;
        this.canonicalIdOf = canonicalIdOf;
        this.versionOf = versionOf;
        this.maxSections = maxSections;
        this.coverageCalc = coverageCalc;
        this.knee = knee;
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
        final SectionCandidates cand = src.candidates( query );
        final List< CandidateSection > ranked = reranker.rerank( query, cand.sections() );
        // knee only valid on cosine-scale dense candidates; and it counts dense-ranked N applied
        // to the reranked output — rerank picks which/order, knee picks how many.
        final int cut = cand.denseCosineScale()
            ? knee.effectiveN( cand.sections(), cand.topSimilarity(), maxSections )
            : maxSections;

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
        return new ContextBundle( query, out, coverageCalc.compute( cand.topSimilarity(), out ) );
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
