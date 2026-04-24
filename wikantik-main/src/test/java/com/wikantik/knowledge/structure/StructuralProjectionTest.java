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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StructuralProjectionTest {

    private static PageDescriptor page( final String id, final String slug, final PageType type,
                                         final String cluster, final List< String > tags ) {
        return new PageDescriptor( id, slug, slug, type, cluster, tags,
                                    slug + " summary", Instant.parse( "2026-04-01T00:00:00Z" ) );
    }

    @Test
    void build_returns_cluster_summaries() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "HybridRetrieval", PageType.ARTICLE, "wikantik-development",
                                List.of( "retrieval" ) ) )
                .addPage( page( "B", "WikantikDevelopment", PageType.HUB, "wikantik-development",
                                List.of() ) )
                .addPage( page( "C", "IndexFunds",         PageType.ARTICLE, "investing",
                                List.of( "investing" ) ) )
                .build();

        final var clusters = proj.listClusters();
        assertEquals( 2, clusters.size() );
        final var dev = clusters.stream().filter( c -> "wikantik-development".equals( c.name() ) ).findFirst().orElseThrow();
        assertEquals( 2, dev.articleCount() );
        assertEquals( "WikantikDevelopment", dev.hubPage().slug() );
    }

    @Test
    void listTags_excludes_tags_under_min_pages() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of( "alpha", "beta" ) ) )
                .addPage( page( "B", "Y", PageType.ARTICLE, null, List.of( "alpha" ) ) )
                .addPage( page( "C", "Z", PageType.ARTICLE, null, List.of( "beta" ) ) )
                .build();
        final var tags2 = proj.listTags( 2 );
        assertEquals( 2, tags2.size() );
        final var tags1 = proj.listTags( 1 );
        assertEquals( 2, tags1.size() );
    }

    @Test
    void listPagesByFilter_by_type_and_cluster() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, "c1", List.of() ) )
                .addPage( page( "B", "Y", PageType.HUB,     "c1", List.of() ) )
                .addPage( page( "C", "Z", PageType.ARTICLE, "c2", List.of() ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                Optional.of( PageType.ARTICLE ), Optional.of( "c1" ), null, null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "X", result.get( 0 ).slug() );
    }

    @Test
    void listPagesByFilter_by_all_tags_AND() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of( "alpha", "beta" ) ) )
                .addPage( page( "B", "Y", PageType.ARTICLE, null, List.of( "alpha" ) ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                null, null, List.of( "alpha", "beta" ), null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "X", result.get( 0 ).slug() );
    }

    @Test
    void getByCanonicalId_and_resolveSlug_round_trip() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of() ) )
                .build();
        assertEquals( Optional.of( "X" ),  proj.resolveSlugFromCanonicalId( "A" ) );
        assertEquals( Optional.of( "A" ),  proj.resolveCanonicalIdFromSlug( "X" ) );
        assertTrue( proj.getByCanonicalId( "A" ).isPresent() );
        assertTrue( proj.getByCanonicalId( "Z" ).isEmpty() );
    }
}
