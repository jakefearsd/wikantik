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
package com.wikantik.insights.runtime;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.insights.PageFacts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Wiki-side adapter for the {@link PageFacts} port (content-intelligence design D5). Backs the
 * {@code wikantik-insights} rule engine's page-state lookups ({@code VOCABULARY_GAP} and
 * {@code STALE_HIGH_TRAFFIC}, §7.3 rules 3 and 4) with the live structural index rather than a
 * database of its own -- title, summary, tags, and cluster already live in
 * {@link PageDescriptor}; verification age and confidence already live behind
 * {@link StructuralIndexService#verificationOf(String)} (the same rule-engine output
 * {@code ConfidenceComputer} feeds -- see its javadoc for the precedence rules). This class does
 * not recompute either; it only joins them by page path.
 *
 * <p><strong>Path convention.</strong> {@link PageFacts#lookup} takes the normalised
 * {@code pagePath} shape used throughout {@code search_visibility_snapshot} --
 * {@code "/wiki/PageName"} (see {@code SnapshotPayloadParser}). This adapter strips the
 * {@code "/wiki/"} prefix to recover the slug, then resolves it through the structural index the
 * same way {@code StructuralSpinePageFilter} does.</p>
 *
 * <p><strong>Fail-soft by contract.</strong> {@link #lookup} never throws. An unresolvable path,
 * an unknown slug, or an exception anywhere in the structural-index round trip all collapse to
 * {@link Optional#empty()} -- the rule engine (wikantik-insights) is a pure function with no
 * defenses of its own against a misbehaving {@link PageFacts}, so this adapter is the one place
 * that contract has to hold.</p>
 */
public final class WikiPageFacts implements PageFacts {

    private static final Logger LOG = LogManager.getLogger( WikiPageFacts.class );

    private static final String WIKI_PATH_PREFIX = "/wiki/";

    private final StructuralIndexService structuralIndex;

    /**
     * @param structuralIndex the live structural index; must not be {@code null}
     */
    public WikiPageFacts( final StructuralIndexService structuralIndex ) {
        if ( structuralIndex == null ) {
            throw new IllegalArgumentException( "structuralIndex required" );
        }
        this.structuralIndex = structuralIndex;
    }

    @Override
    public Optional<PageFact> lookup( final String pagePath ) {
        try {
            return lookupUnsafe( pagePath );
        } catch ( final RuntimeException e ) {
            LOG.warn( "PageFacts lookup failed for page path '{}': {}", pagePath, e.getMessage(), e );
            return Optional.empty();
        }
    }

    private Optional<PageFact> lookupUnsafe( final String pagePath ) {
        final String slug = slugOf( pagePath );
        if ( slug == null ) {
            return Optional.empty();
        }

        final Optional<String> canonicalId = structuralIndex.resolveCanonicalIdFromSlug( slug );
        if ( canonicalId.isEmpty() ) {
            return Optional.empty();
        }

        final Optional<PageDescriptor> descriptor = structuralIndex.getByCanonicalId( canonicalId.get() );
        if ( descriptor.isEmpty() ) {
            return Optional.empty();
        }

        final PageDescriptor pd = descriptor.get();
        final Verification verification = structuralIndex.verificationOf( canonicalId.get() )
                .orElseGet( Verification::unverified );

        final List<String> tags = pd.tags() == null ? List.of() : pd.tags();
        final String confidence = verification.confidence() == null
                ? null : verification.confidence().wireName();

        return Optional.of( new PageFact( pagePath, pd.title(), pd.summary(), tags, pd.cluster(),
                verification.verifiedAt(), confidence ) );
    }

    /**
     * @param pagePath the {@code "/wiki/PageName"}-shaped path
     * @return the bare slug, or {@code null} if {@code pagePath} doesn't carry the expected prefix
     */
    private static String slugOf( final String pagePath ) {
        if ( pagePath == null || !pagePath.startsWith( WIKI_PATH_PREFIX ) ) {
            return null;
        }
        final String slug = pagePath.substring( WIKI_PATH_PREFIX.length() );
        return slug.isBlank() ? null : slug;
    }
}
