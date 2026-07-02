/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.retrieval;

import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.knowledge.MentionIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelatedPagesFinderTest {

    @Test
    void fetchRelatedPages_describesTopSharedEntitiesUpToLimit() {
        final MentionIndex index = mock( MentionIndex.class );
        // Five shared names but REASON_ENTITY_LIMIT (3) truncates the reason text.
        final MentionIndex.RelatedByMention match = new MentionIndex.RelatedByMention(
            "OtherPage", List.of( "BM25", "Qwen3", "HNSW", "RRF", "pgvector" ), 5 );
        when( index.findRelatedPages( "SourcePage", 5 ) ).thenReturn( List.of( match ) );

        final RelatedPagesFinder finder = new RelatedPagesFinder( index );
        final List< RelatedPage > related = finder.fetchRelatedPages( "SourcePage" );

        assertEquals( 1, related.size() );
        assertEquals( "OtherPage", related.get( 0 ).name() );
        assertEquals( "shared entities: BM25, Qwen3, HNSW (+2 more)", related.get( 0 ).reason() );
    }

    @Test
    void fetchRelatedPages_fallsBackToCountWhenNoEntityNames() {
        final MentionIndex index = mock( MentionIndex.class );
        final MentionIndex.RelatedByMention match =
            new MentionIndex.RelatedByMention( "OtherPage", List.of(), 7 );
        when( index.findRelatedPages( "SourcePage", 5 ) ).thenReturn( List.of( match ) );

        final List< RelatedPage > related = new RelatedPagesFinder( index ).fetchRelatedPages( "SourcePage" );

        assertEquals( "shared entities: 7", related.get( 0 ).reason() );
    }

    @Test
    void fetchRelatedPages_nullMentionIndex_returnsEmptyList() {
        final RelatedPagesFinder finder = new RelatedPagesFinder( null );
        assertEquals( List.of(), finder.fetchRelatedPages( "AnyPage" ) );
    }

    @Test
    void fetchRelatedPages_indexThrows_degradesToEmptyRatherThanPropagating() {
        final MentionIndex index = mock( MentionIndex.class );
        when( index.findRelatedPages( anyString(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );

        final List< RelatedPage > related = new RelatedPagesFinder( index ).fetchRelatedPages( "SourcePage" );

        assertEquals( List.of(), related );
    }

    @Test
    void fetchRelatedPagesBatch_shapesEachInputNameIndependently() {
        final MentionIndex index = mock( MentionIndex.class );
        final MentionIndex.RelatedByMention matchForA =
            new MentionIndex.RelatedByMention( "Neighbor", List.of( "Foo" ), 1 );
        when( index.findRelatedPagesBatch( List.of( "PageA", "PageB" ), 5 ) )
            .thenReturn( Map.of( "PageA", List.of( matchForA ), "PageB", List.of() ) );

        final Map< String, List< RelatedPage > > batched =
            new RelatedPagesFinder( index ).fetchRelatedPagesBatch( List.of( "PageA", "PageB" ) );

        assertEquals( 1, batched.get( "PageA" ).size() );
        assertEquals( "Neighbor", batched.get( "PageA" ).get( 0 ).name() );
        assertEquals( List.of(), batched.get( "PageB" ) );
    }

    @Test
    void fetchRelatedPagesBatch_emptyInput_returnsEmptyMapWithoutCallingIndex() {
        final MentionIndex index = mock( MentionIndex.class );
        final Map< String, List< RelatedPage > > batched =
            new RelatedPagesFinder( index ).fetchRelatedPagesBatch( List.of() );

        assertEquals( Map.of(), batched );
        verifyNoInteractions( index );
    }
}
