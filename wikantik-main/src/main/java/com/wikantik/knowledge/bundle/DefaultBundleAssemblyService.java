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
import com.wikantik.api.knowledge.ContextRetrievalService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Orchestrates retrieve → per-page shortlist → rerank → dedup → top-N → cite (ADR-0001/0003/0005). */
public final class DefaultBundleAssemblyService implements BundleAssemblyService {

    private final SectionCandidateSource source;
    private final SectionReranker reranker;
    private final Function< String, Optional< String > > canonicalIdOf;  // slug -> canonical_id
    private final Function< String, Integer > versionOf;                  // slug -> page version
    private final int maxSections;

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
     */
    public DefaultBundleAssemblyService( final SectionCandidateSource source,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections ) {
        this.source = source;
        this.reranker = reranker;
        this.canonicalIdOf = canonicalIdOf;
        this.versionOf = versionOf;
        this.maxSections = maxSections;
    }

    @Override
    public ContextBundle assemble( final String query ) {
        final List< CandidateSection > ranked = reranker.rerank( query, source.candidates( query ) );

        final Set< SectionKey > seen = new LinkedHashSet<>();
        final List< BundleSection > out = new ArrayList<>();
        for ( final CandidateSection cs : ranked ) {
            if ( !seen.add( new SectionKey( cs.slug(), cs.headingPath() ) ) ) continue;  // dedup by (slug, heading-path)
            final String canonical = canonicalIdOf.apply( cs.slug() ).orElse( null );
            if ( canonical == null ) continue;         // can't cite an un-versioned page; skip
            final CitationHandle cite = new CitationHandle(
                canonical, versionOf.apply( cs.slug() ), cs.headingPath(), cs.text(), sha256( cs.text() ) );
            out.add( new BundleSection( canonical, cs.slug(), cs.headingPath(), cs.text(), cs.denseScore(), cite ) );
            if ( out.size() >= maxSections ) break;     // top-N
        }
        return new ContextBundle( query, out );
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
