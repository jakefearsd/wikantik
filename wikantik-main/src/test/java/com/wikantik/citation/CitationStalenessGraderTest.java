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
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CitationStalenessGraderTest {

    private static final String TARGET_BODY =
        "# Deploy\n## Rollback Steps\nAlways drain the queue before rollback.\n";

    private CitationStalenessGrader grader( final StructuralIndexService idx,
                                            final Function< String, Optional< String > > loader ) {
        return new CitationStalenessGrader( idx, loader, new MarkdownSectionExtractor() );
    }

    @Test
    void currentWhenSpanPresentInSection() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationStatus s = grader( idx, slug -> Optional.of( TARGET_BODY ) )
            .grade( "t1", "Deploy > Rollback Steps", "Always drain the queue before rollback" );
        assertEquals( CitationStatus.CURRENT, s );
    }

    @Test
    void staleWhenSpanGoneFromSection() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationStatus s = grader( idx, slug -> Optional.of( TARGET_BODY ) )
            .grade( "t1", "Deploy > Rollback Steps", "drain the pool" );
        assertEquals( CitationStatus.STALE, s );
    }

    @Test
    void staleWhenHeadingPathNoLongerResolves() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationStatus s = grader( idx, slug -> Optional.of( TARGET_BODY ) )
            .grade( "t1", "Deploy > Gone", "anything" );
        assertEquals( CitationStatus.STALE, s );
    }

    @Test
    void targetMissingWhenCanonicalIdDoesNotResolve() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.empty() );
        final CitationStatus s = grader( idx, slug -> Optional.empty() )
            .grade( "t1", "Deploy", "x" );
        assertEquals( CitationStatus.TARGET_MISSING, s );
    }

    @Test
    void emptySpanGradesCurrentWhenSectionResolves() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        assertEquals( CitationStatus.CURRENT,
            grader( idx, slug -> Optional.of( TARGET_BODY ) ).grade( "t1", "Deploy > Rollback Steps", "" ) );
    }
}
