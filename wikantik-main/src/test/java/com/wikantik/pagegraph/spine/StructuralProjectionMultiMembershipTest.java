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

import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  ClusterDeclarationDesign Phase 5 — a page belongs to every cluster it names.
 *
 *  <p>Membership is a set; placement (breadcrumbs, sidebar, SEO) is the primary.
 *  These tests pin the difference.</p>
 */
class StructuralProjectionMultiMembershipTest {

    private static PageDescriptor hub( final String slug, final String cluster ) {
        return new PageDescriptor( id( slug ), slug, slug, PageType.HUB, cluster, List.of(),
                null, Instant.EPOCH, Optional.empty(), false );
    }

    private static PageDescriptor member( final String slug, final List< String > clusters ) {
        return new PageDescriptor( id( slug ), slug, slug, PageType.ARTICLE, null, clusters,
                List.of(), null, Instant.EPOCH, Optional.empty(), false );
    }

    private static String id( final String slug ) {
        return ( "01HAA" + slug + "0000000000000000000000000" ).substring( 0, 26 ).toUpperCase( java.util.Locale.ROOT );
    }

    private static List< String > slugs( final List< PageDescriptor > pages ) {
        return pages.stream().map( PageDescriptor::slug ).sorted().toList();
    }

    private static StructuralProjection project( final PageDescriptor... pages ) {
        final StructuralProjectionBuilder b = new StructuralProjectionBuilder();
        for ( final PageDescriptor p : pages ) {
            b.addPage( p );
        }
        return b.build();
    }

    private List< PageDescriptor > byCluster( final StructuralProjection proj, final String cluster ) {
        return proj.listPagesByFilter(
                new StructuralFilter( Optional.empty(), Optional.of( cluster ), null, null, 100, null ) );
    }

    /** The whole point of the phase: one page, two homes. */
    @Test
    void a_page_is_returned_for_every_cluster_it_names() {
        final StructuralProjection proj = project(
                hub( "MLHub", "machine-learning" ),
                hub( "FinanceHub", "quantitative-finance" ),
                member( "PortfolioOptimization", List.of( "machine-learning", "quantitative-finance" ) ) );

        assertEquals( List.of( "MLHub", "PortfolioOptimization" ), slugs( byCluster( proj, "machine-learning" ) ) );
        assertEquals( List.of( "FinanceHub", "PortfolioOptimization" ),
                      slugs( byCluster( proj, "quantitative-finance" ) ) );
    }

    @Test
    void cluster_details_list_the_page_under_each_of_its_memberships() {
        final StructuralProjection proj = project(
                hub( "MLHub", "machine-learning" ),
                hub( "FinanceHub", "quantitative-finance" ),
                member( "PortfolioOptimization", List.of( "machine-learning", "quantitative-finance" ) ) );

        assertTrue( slugs( proj.getCluster( "machine-learning" ).map( ClusterDetails::articles ).orElseThrow() )
                            .contains( "PortfolioOptimization" ) );
        assertTrue( slugs( proj.getCluster( "quantitative-finance" ).map( ClusterDetails::articles ).orElseThrow() )
                            .contains( "PortfolioOptimization" ) );
    }

    /**
     * A page may legitimately name both a parent and its own sub-cluster. Transitive
     * membership would then find it twice — once directly, once through the descendant
     * walk — and inflate every member count that a curator reads.
     */
    @Test
    void a_page_naming_both_a_parent_and_its_sub_cluster_is_counted_once() {
        final StructuralProjection proj = project(
                hub( "MLHub", "machine-learning" ),
                member( "InferenceServing", List.of( "machine-learning", "machine-learning/mlops" ) ) );

        assertEquals( List.of( "InferenceServing", "MLHub" ), slugs( byCluster( proj, "machine-learning" ) ) );
        assertEquals( 2, proj.getCluster( "machine-learning" ).orElseThrow().articles().size() );
    }

    /** Membership is transitive on a secondary just as it is on a primary. */
    @Test
    void a_secondary_sub_cluster_membership_is_still_transitive() {
        final StructuralProjection proj = project(
                hub( "MLHub", "machine-learning" ),
                member( "RiskModels", List.of( "quantitative-finance", "machine-learning/mlops" ) ) );

        assertTrue( slugs( byCluster( proj, "machine-learning" ) ).contains( "RiskModels" ) );
    }

    /**
     * Declaration stays singular. A hub that also joins another cluster declares only its
     * own — otherwise one page could silently claim two clusters and re-introduce exactly
     * the ambiguity Phase 2 exists to forbid.
     */
    @Test
    void a_hub_declares_only_its_primary_cluster() {
        final PageDescriptor multiHub = new PageDescriptor( id( "MLHub" ), "MLHub", "MLHub", PageType.HUB,
                null, List.of( "machine-learning", "quantitative-finance" ), List.of(), null,
                Instant.EPOCH, Optional.empty(), false );
        final StructuralProjection proj = project( multiHub );

        assertEquals( "MLHub", proj.getCluster( "machine-learning" ).orElseThrow().hubPage().slug() );
        assertTrue( proj.getCluster( "quantitative-finance" ).orElseThrow().hubPage() == null,
                    "the hub's secondary membership must not declare that cluster" );
    }

    /** A hub with a list-valued cluster is a defect the drift burn-down must surface. */
    @Test
    void a_hub_with_multiple_memberships_is_reported_as_a_conflict() {
        final PageDescriptor multiHub = new PageDescriptor( id( "MLHub" ), "MLHub", "MLHub", PageType.HUB,
                null, List.of( "machine-learning", "quantitative-finance" ), List.of(), null,
                Instant.EPOCH, Optional.empty(), false );

        assertTrue( project( multiHub ).structuralConflicts().stream()
                            .anyMatch( c -> c.kind() == StructuralConflict.Kind.MULTI_CLUSTER_HUB
                                    && "MLHub".equals( c.slug() ) ),
                    "expected MULTI_CLUSTER_HUB for a hub naming two clusters" );
    }
}
