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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StructuralProjectionTest {

    private static PageDescriptor page( final String id, final String slug, final PageType type,
                                         final String cluster, final List< String > tags ) {
        return new PageDescriptor( id, slug, slug, type, cluster, tags,
                                    slug + " summary", Instant.parse( "2026-04-01T00:00:00Z" ), Optional.empty(), false );
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

    private static List< StructuralConflict.Kind > kinds( final StructuralProjection proj ) {
        return proj.structuralConflicts().stream().map( StructuralConflict::kind ).toList();
    }

    @Test
    void reports_a_duplicate_declaration_naming_the_hub_that_lost() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "AlphaHub", PageType.HUB, "c1", List.of() ) )
                .addPage( page( "B", "BetaHub",  PageType.HUB, "c1", List.of() ) )
                .build();
        final var dup = proj.structuralConflicts().stream()
                .filter( c -> c.kind() == StructuralConflict.Kind.DUPLICATE_CLUSTER_DECLARATION )
                .findFirst().orElseThrow();
        assertEquals( "BetaHub", dup.slug() );
        assertTrue( dup.detail().contains( "c1" ) );
    }

    @Test
    void reports_a_cluster_that_has_members_but_no_hub() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "Orphan", PageType.ARTICLE, "van-life", List.of() ) )
                .build();
        assertTrue( kinds( proj ).contains( StructuralConflict.Kind.HEADLESS_CLUSTER ) );
    }

    @Test
    void reports_a_hub_page_that_declares_no_cluster() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "StrayHub", PageType.HUB, null, List.of() ) )
                .build();
        final var stray = proj.structuralConflicts().stream()
                .filter( c -> c.kind() == StructuralConflict.Kind.CLUSTERLESS_HUB )
                .findFirst().orElseThrow();
        assertEquals( "StrayHub", stray.slug() );
    }

    @Test
    void reports_a_sub_cluster_whose_parent_nobody_declares() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MlopsHub", PageType.HUB, "machine-learning/mlops", List.of() ) )
                .build();
        final var orphan = proj.structuralConflicts().stream()
                .filter( c -> c.kind() == StructuralConflict.Kind.UNDECLARED_CLUSTER )
                .findFirst().orElseThrow();
        assertTrue( orphan.detail().contains( "machine-learning" ) );
    }

    @Test
    void a_healthy_taxonomy_reports_no_structural_conflicts() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MLHub",     PageType.HUB,     "machine-learning",       List.of() ) )
                .addPage( page( "B", "MlopsHub",  PageType.HUB,     "machine-learning/mlops", List.of() ) )
                .addPage( page( "C", "Inference", PageType.ARTICLE, "machine-learning/mlops", List.of() ) )
                .build();
        assertEquals( List.of(), proj.structuralConflicts() );
    }

    /**
     * Two hubs declaring one cluster is a defect Phase 2 blocks at save time, but the
     * index must never report a different winner depending on filesystem enumeration
     * order — `listFiles()` is unsorted, so last-writer-wins was non-deterministic.
     */
    @Test
    void hub_selection_is_deterministic_regardless_of_page_order() {
        final var alpha = page( "A", "AlphaHub", PageType.HUB, "c1", List.of() );
        final var beta  = page( "B", "BetaHub",  PageType.HUB, "c1", List.of() );

        final var forward = new StructuralProjectionBuilder().addPage( alpha ).addPage( beta ).build();
        final var reverse = new StructuralProjectionBuilder().addPage( beta ).addPage( alpha ).build();

        assertEquals( "AlphaHub", forward.getCluster( "c1" ).orElseThrow().hubPage().slug() );
        assertEquals( "AlphaHub", reverse.getCluster( "c1" ).orElseThrow().hubPage().slug() );
    }

    /** A sub-cluster's pages are members of its parent too — resolved at query time. */
    @Test
    void getCluster_of_a_parent_includes_sub_cluster_pages() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MLHub",            PageType.HUB,     "machine-learning",       List.of() ) )
                .addPage( page( "B", "InferenceServing", PageType.ARTICLE, "machine-learning/mlops", List.of() ) )
                .build();
        final var details = proj.getCluster( "machine-learning" ).orElseThrow();
        assertEquals( 2, details.articles().size() );
        assertTrue( details.articles().stream().anyMatch( p -> "InferenceServing".equals( p.slug() ) ) );
    }

    @Test
    void getCluster_of_a_sub_cluster_does_not_include_its_parents_pages() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MLHub",            PageType.HUB,     "machine-learning",       List.of() ) )
                .addPage( page( "B", "InferenceServing", PageType.ARTICLE, "machine-learning/mlops", List.of() ) )
                .build();
        final var details = proj.getCluster( "machine-learning/mlops" ).orElseThrow();
        assertEquals( 1, details.articles().size() );
        assertEquals( "InferenceServing", details.articles().get( 0 ).slug() );
    }

    @Test
    void listClusters_counts_sub_cluster_pages_under_the_parent() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MLHub",            PageType.HUB,     "machine-learning",       List.of() ) )
                .addPage( page( "B", "InferenceServing", PageType.ARTICLE, "machine-learning/mlops", List.of() ) )
                .build();
        final var parent = proj.listClusters().stream()
                .filter( c -> "machine-learning".equals( c.name() ) ).findFirst().orElseThrow();
        assertEquals( 2, parent.articleCount() );
    }

    @Test
    void listPagesByFilter_by_cluster_includes_sub_cluster_pages() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MLHub",            PageType.HUB,     "machine-learning",       List.of() ) )
                .addPage( page( "B", "InferenceServing", PageType.ARTICLE, "machine-learning/mlops", List.of() ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                Optional.empty(), Optional.of( "machine-learning" ), null, null, 100, null ) );
        assertEquals( 2, result.size() );
    }

    /**
     * Invariant guard: cluster matching is segment-aware. A `startsWith` filter would
     * pull `machine-learning-ops` into `machine-learning`.
     */
    @Test
    void listPagesByFilter_by_cluster_excludes_a_sibling_sharing_a_string_prefix() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "MLHub",   PageType.HUB,     "machine-learning",     List.of() ) )
                .addPage( page( "B", "OpsPage", PageType.ARTICLE, "machine-learning-ops", List.of() ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                Optional.empty(), Optional.of( "machine-learning" ), null, null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "MLHub", result.get( 0 ).slug() );
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
