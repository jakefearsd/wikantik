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
import com.wikantik.search.embedding.PageEmbeddingSimilarity;
import com.wikantik.search.embedding.PageEmbeddingSimilarity.ScoredPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelatedPagesFinderTest {

    private static final Predicate< String > ALL = n -> true;

    @Test
    void fetchRelatedPages_describesSimilarityScore() {
        final PageEmbeddingSimilarity sim = mock( PageEmbeddingSimilarity.class );
        when( sim.similarTo( eq( "SourcePage" ), eq( 5 ), any() ) )
            .thenReturn( List.of( new ScoredPage( "OtherPage", 0.834 ) ) );

        final List< RelatedPage > related = new RelatedPagesFinder( sim, ALL ).fetchRelatedPages( "SourcePage" );

        assertEquals( 1, related.size() );
        assertEquals( "OtherPage", related.get( 0 ).name() );
        assertEquals( "similar content (0.83)", related.get( 0 ).reason() );
    }

    @Test
    void fetchRelatedPages_passesAcceptFilterToSimilarity() {
        final PageEmbeddingSimilarity sim = mock( PageEmbeddingSimilarity.class );
        when( sim.similarTo( anyString(), anyInt(), any() ) ).thenAnswer( inv -> {
            final Predicate< String > accept = inv.getArgument( 2 );
            return List.of( new ScoredPage( "Gone", 0.9 ), new ScoredPage( "Here", 0.8 ) ).stream()
                .filter( sp -> accept.test( sp.name() ) ).toList();
        } );

        final List< RelatedPage > related =
            new RelatedPagesFinder( sim, n -> !"Gone".equals( n ) ).fetchRelatedPages( "SourcePage" );

        assertEquals( List.of( "Here" ), related.stream().map( RelatedPage::name ).toList() );
    }

    @Test
    void fetchRelatedPages_nullSimilarity_returnsEmptyList() {
        assertEquals( List.of(), new RelatedPagesFinder( null, ALL ).fetchRelatedPages( "AnyPage" ) );
    }

    @Test
    void fetchRelatedPages_similarityThrows_degradesToEmptyRatherThanPropagating() {
        final PageEmbeddingSimilarity sim = mock( PageEmbeddingSimilarity.class );
        when( sim.similarTo( anyString(), anyInt(), any() ) ).thenThrow( new RuntimeException( "DB offline" ) );

        assertEquals( List.of(), new RelatedPagesFinder( sim, ALL ).fetchRelatedPages( "SourcePage" ) );
    }

    @Test
    void fetchRelatedPagesBatch_shapesEachInputNameIndependently() {
        final PageEmbeddingSimilarity sim = mock( PageEmbeddingSimilarity.class );
        when( sim.similarTo( eq( "PageA" ), eq( 5 ), any() ) )
            .thenReturn( List.of( new ScoredPage( "Neighbor", 0.5 ) ) );
        when( sim.similarTo( eq( "PageB" ), eq( 5 ), any() ) ).thenReturn( List.of() );

        final Map< String, List< RelatedPage > > batched =
            new RelatedPagesFinder( sim, ALL ).fetchRelatedPagesBatch( List.of( "PageA", "PageB" ) );

        assertEquals( 1, batched.get( "PageA" ).size() );
        assertEquals( "Neighbor", batched.get( "PageA" ).get( 0 ).name() );
        assertEquals( List.of(), batched.get( "PageB" ) );
    }

    @Test
    void fetchRelatedPagesBatch_emptyInput_returnsEmptyMapWithoutCallingSimilarity() {
        final PageEmbeddingSimilarity sim = mock( PageEmbeddingSimilarity.class );
        assertEquals( Map.of(), new RelatedPagesFinder( sim, ALL ).fetchRelatedPagesBatch( List.of() ) );
        verifyNoInteractions( sim );
    }
}
