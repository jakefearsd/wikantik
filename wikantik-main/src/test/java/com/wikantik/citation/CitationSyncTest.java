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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CitationSyncTest {

    private CitationSync sync( final CitationRepository repo, final StructuralIndexService idx,
                               final Map< String, String > bodies, final Map< String, Integer > versions ) {
        final Function< String, Optional< String > > loader = slug -> Optional.ofNullable( bodies.get( slug ) );
        final Function< String, Optional< Integer > > ver = slug -> Optional.ofNullable( versions.get( slug ) );
        final CitationStalenessGrader grader = new CitationStalenessGrader( idx, loader, new MarkdownSectionExtractor() );
        return new CitationSync( repo, new CitationMarkupParser(), grader, idx, loader, ver );
    }

    @Test
    void onPageSavedReconcilesOutboundWithGradedStatusAndVersionPin() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "Source" ) ).thenReturn( Optional.of( "src1" ) );
        when( idx.resolveSlugFromCanonicalId( "tgt1" ) ).thenReturn( Optional.of( "Target" ) );
        when( idx.resolveSlugFromCanonicalId( "src1" ) ).thenReturn( Optional.of( "Source" ) );
        final String src = "Claim [c](cite://tgt1/H \"present span\")";
        final String tgt = "# H\npresent span here\n";
        when( repo.findByTarget( anyString() ) ).thenReturn( List.of() );

        sync( repo, idx, Map.of( "Source", src, "Target", tgt ), Map.of( "Target", 9 ) ).onPageSaved( "Source" );

        @SuppressWarnings( "unchecked" )
        final org.mockito.ArgumentCaptor< List< CitationRow > > cap = org.mockito.ArgumentCaptor.forClass( List.class );
        verify( repo ).replaceForSource( eq( "src1" ), cap.capture() );
        final CitationRow r = cap.getValue().get( 0 );
        assertEquals( "tgt1", r.targetCanonicalId() );
        assertEquals( CitationStatus.CURRENT, r.status() );
        assertEquals( 9, r.pinnedTargetVersion() );
    }

    @Test
    void onPageSavedRegradesInboundCitations() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "Target" ) ).thenReturn( Optional.of( "tgt1" ) );
        when( idx.resolveSlugFromCanonicalId( "tgt1" ) ).thenReturn( Optional.of( "Target" ) );
        final CitationRow inbound = new CitationRow( 5, "src1", "tgt1", "H", "old span",
            Spans.hash( Spans.normalize( "old span" ) ), "c", 0, 3, CitationStatus.CURRENT, null, null, null );
        when( repo.findByTarget( "tgt1" ) ).thenReturn( List.of( inbound ) );
        // Target no longer contains "old span" -> should flip to STALE
        sync( repo, idx, Map.of( "Target", "# H\ndifferent now\n" ), Map.of() ).onPageSaved( "Target" );
        verify( repo ).updateStatus( eq( 5L ), eq( CitationStatus.STALE ), any() );
    }

    @Test
    void onPageDeletedMarksInboundTargetMissingWhenCanonicalIdGone() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "Gone" ) ).thenReturn( Optional.of( "tgtGone" ) );
        when( idx.resolveSlugFromCanonicalId( "tgtGone" ) ).thenReturn( Optional.empty() );  // truly gone
        final CitationRow inbound = new CitationRow( 8, "s", "tgtGone", "", "x",
            Spans.hash( "x" ), "c", 0, 1, CitationStatus.CURRENT, null, null, null );
        when( repo.findByTarget( "tgtGone" ) ).thenReturn( List.of( inbound ) );
        sync( repo, idx, Map.of(), Map.of() ).onPageDeleted( "Gone" );
        verify( repo ).updateStatus( eq( 8L ), eq( CitationStatus.TARGET_MISSING ), any() );
    }

    @Test
    void onPageDeletedSkipsWhenCanonicalIdStillLivesRename() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "OldName" ) ).thenReturn( Optional.of( "tgt1" ) );
        when( idx.resolveSlugFromCanonicalId( "tgt1" ) ).thenReturn( Optional.of( "NewName" ) );  // rename
        sync( repo, idx, Map.of(), Map.of() ).onPageDeleted( "OldName" );
        verify( repo, never() ).updateStatus( anyLong(), any(), any() );
    }
}
