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

import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class DefaultBundleAssemblyServiceTest {

    private static RetrievedChunk ch( String head, String text, double s ) {
        return new RetrievedChunk( List.of( head ), text, s, List.of() );
    }

    @Test
    void assembles_dedups_cites_and_caps_topN() {
        final RetrievedPage page = new RetrievedPage( "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops",
            List.of(), List.of( ch("Setup","setup",0.9), ch("Usage","usage",0.7) ), List.of(), "a", null );
        final ContextRetrievalService retrieval = new StubRetrieval(
            new RetrievalResult( "deploy", List.of( page ), 1 ) );
        final SectionReranker identity = ( q, secs ) -> secs;             // keep dense order
        final Function<String,Optional<String>> canon = slug -> Optional.of( "01DEP" );
        final Function<String,Integer> version = slug -> 7;

        final ContextBundle b = new DefaultBundleAssemblyService( retrieval, identity, canon, version, 3, 1 )
            .assemble( "deploy" );

        assertEquals( "deploy", b.query() );
        assertFalse( b.sections().isEmpty() );
        final var top = b.sections().get( 0 );
        assertEquals( "01DEP", top.canonicalId() );
        assertEquals( 7, top.citation().version() );
        assertEquals( top.text(), top.citation().span() );
        assertFalse( top.citation().spanSha256().isBlank() );
        // dedup: no two sections share (slug, headingPath)
        assertEquals( b.sections().stream().map( s -> s.slug()+s.headingPath() ).distinct().count(),
                      b.sections().size() );
    }

    @Test
    void skipsSectionWhenCanonicalIdMissing() {
        // canonicalIdOf returns empty → the section is un-citable and must be dropped, not emitted.
        final SectionCandidateSource source = q -> List.of( new CandidateSection( "PageA", List.of( "H" ), "a", 0.9 ) );
        final ContextBundle b = new DefaultBundleAssemblyService(
            source, ( q, s ) -> s, slug -> Optional.empty(), slug -> 1, 5 ).assemble( "q" );
        assertTrue( b.sections().isEmpty(), "section without a canonical_id is skipped" );
    }

    @Test
    void capsAtMaxSections() {
        // 3 pages x 2 distinct sections = 6 citable candidates; maxSections=3 must break after the third.
        final List< CandidateSection > six = new ArrayList<>();
        for ( int p = 0; p < 3; p++ ) {
            for ( int s = 0; s < 2; s++ ) {
                six.add( new CandidateSection( "Page" + p, List.of( "H" + s ), "t" + p + s, 1.0 - 0.01 * ( p * 2 + s ) ) );
            }
        }
        final SectionCandidateSource source = q -> six;
        final ContextBundle b = new DefaultBundleAssemblyService(
            source, ( q, s ) -> s, slug -> Optional.of( "c-" + slug ), slug -> 1, 3 ).assemble( "q" );
        assertEquals( 3, b.sections().size(), "output capped at maxSections" );
    }

    /** Helper matching the shape used elsewhere in this test class. */
    private static CandidateSection candidate( final String slug, final String text ) {
        return new CandidateSection( slug, List.of( "H" ), text, 1.0 );
    }

    @Test
    void assemble_selects_source_by_mode_and_degrades_to_default_when_missing() {
        final SectionCandidateSource denseSrc   = q -> List.of( candidate( "DensePage", "d" ) );
        final SectionCandidateSource lexicalSrc = q -> List.of( candidate( "LexPage", "l" ) );
        final java.util.Map< RetrievalMode, SectionCandidateSource > sources =
            java.util.Map.of( RetrievalMode.HYBRID, denseSrc, RetrievalMode.LEXICAL, lexicalSrc );
        final DefaultBundleAssemblyService svc = new DefaultBundleAssemblyService(
            sources, RetrievalMode.HYBRID, (q, s) -> s, slug -> Optional.of( slug ),
            slug -> 0, 12 );

        // explicit mode routes to its source
        assertEquals( "LexPage", svc.assemble( "x", RetrievalMode.LEXICAL ).sections().get( 0 ).slug() );
        // DENSE has no source → degrade to default (HYBRID) source
        assertEquals( "DensePage", svc.assemble( "x", RetrievalMode.DENSE ).sections().get( 0 ).slug() );
        // no-mode call uses default
        assertEquals( "DensePage", svc.assemble( "x" ).sections().get( 0 ).slug() );
    }

    @Test
    void mapCtor_defaultModeAbsent_throwsNullPointer() {
        // defaultMode not in sources → fail fast rather than NPE at first assemble() call
        final java.util.Map< RetrievalMode, SectionCandidateSource > empty = java.util.Map.of();
        assertThrows( NullPointerException.class, () ->
            new DefaultBundleAssemblyService( empty, RetrievalMode.HYBRID, (q, s) -> s,
                slug -> Optional.empty(), slug -> 0, 5 ) );
    }

    private record StubRetrieval( RetrievalResult fixed ) implements ContextRetrievalService {
        public RetrievalResult retrieve( ContextQuery q ) { return fixed; }
        public RetrievedPage getPage( String n ) { return null; }
        public PageList listPages( PageListFilter f ) { return null; }
        public List<MetadataValue> listMetadataValues( String field ) { return List.of(); }
    }
}
