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

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ClusterRenameServiceTest {

    private PageManager pageManager;
    private StructuralIndexService structural;
    private PageSaveHelper saveHelper;
    private ClusterRenameService service;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        structural = mock( StructuralIndexService.class );
        saveHelper = mock( PageSaveHelper.class );
        service = new ClusterRenameService( pageManager, structural, saveHelper );
        when( structural.getCluster( anyString() ) ).thenReturn( Optional.empty() );
    }

    private static PageDescriptor page( final String slug, final PageType type, final String cluster ) {
        return new PageDescriptor( "01HAA0000000000000000000" + slug.length(), slug, slug, type,
                cluster, List.of(), null, Instant.now(), Optional.empty(), false );
    }

    private void corpus( final String cluster, final PageDescriptor... pages ) {
        when( structural.listPagesByFilter( any( StructuralFilter.class ) ) ).thenReturn( List.of( pages ) );
        for ( final PageDescriptor p : pages ) {
            final Page mock = mock( Page.class );
            when( pageManager.getPage( p.slug() ) ).thenReturn( mock );
            when( pageManager.getPureText( mock ) ).thenReturn(
                    "---\ncanonical_id: " + p.canonicalId() + "\ntype: " + p.type().asFrontmatterValue()
                            + "\ncluster: " + p.cluster() + "\n---\nBody of " + p.slug() + "." );
        }
    }

    /** A rename must carry the whole subtree — that is the point of paths in the value. */
    @Test
    void plan_rewrites_the_cluster_and_every_sub_cluster_beneath_it() {
        corpus( "machine-learning",
                page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );

        final ClusterRenamePlan plan = service.plan( "machine-learning", "ml" );

        assertEquals( 2, plan.changes().size() );
        assertEquals( "ml", plan.changes().get( 0 ).toCluster() );
        assertEquals( "ml/mlops", plan.changes().get( 1 ).toCluster() );
        assertFalse( plan.hasConflicts() );
    }

    /**
     * Renaming into an already-declared cluster would leave two hubs declaring one path —
     * the defect the whole design forbids. Catch it in the plan, not halfway through the
     * writes, where it would leave the corpus split between two names.
     */
    @Test
    void plan_flags_a_target_cluster_that_another_hub_already_declares() {
        corpus( "american-coinage", page( "AmericanCoinageHub", PageType.HUB, "american-coinage" ) );
        final PageDescriptor incumbent = page( "CoinCollectingHub", PageType.HUB, "numismatics" );
        when( structural.getCluster( "numismatics" ) ).thenReturn( Optional.of(
                new ClusterDetails( "numismatics", incumbent, List.of( incumbent ), Map.of(), Instant.now() ) ) );

        final ClusterRenamePlan plan = service.plan( "american-coinage", "numismatics" );

        assertTrue( plan.hasConflicts() );
        assertTrue( plan.conflicts().get( 0 ).contains( "CoinCollectingHub" ),
                    "the conflict must name the incumbent hub: " + plan.conflicts() );
    }

    /** Moving a cluster under a hub that is itself being moved is not a conflict. */
    @Test
    void plan_does_not_flag_a_hub_that_is_itself_part_of_the_rename() {
        final PageDescriptor hub = page( "MLHub", PageType.HUB, "machine-learning" );
        corpus( "machine-learning", hub );
        when( structural.getCluster( "machine-learning" ) ).thenReturn( Optional.of(
                new ClusterDetails( "machine-learning", hub, List.of( hub ), Map.of(), Instant.now() ) ) );

        assertFalse( service.plan( "machine-learning", "ml" ).hasConflicts() );
    }

    @Test
    void plan_refuses_a_rename_to_the_same_path() {
        assertThrows( IllegalArgumentException.class, () -> service.plan( "a", "a" ) );
    }

    @Test
    void plan_refuses_a_blank_target() {
        assertThrows( IllegalArgumentException.class, () -> service.plan( "a", "  " ) );
    }

    @Test
    void apply_rewrites_the_cluster_frontmatter_of_every_member() throws Exception {
        corpus( "machine-learning",
                page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );

        final ClusterRenameResult result = service.apply( "machine-learning", "ml", "tester" );

        assertEquals( 2, result.renamed().size() );
        assertTrue( result.failures().isEmpty() );
        verify( saveHelper ).saveText( eq( "MLHub" ), contains( "cluster: ml" ), any() );
        verify( saveHelper ).saveText( eq( "InferenceServing" ), contains( "cluster: ml/mlops" ), any() );
    }

    /** A conflicting plan must not write anything — a half-applied rename splits the corpus. */
    @Test
    void apply_refuses_a_conflicting_plan_without_writing() {
        corpus( "american-coinage", page( "AmericanCoinageHub", PageType.HUB, "american-coinage" ) );
        final PageDescriptor incumbent = page( "CoinCollectingHub", PageType.HUB, "numismatics" );
        when( structural.getCluster( "numismatics" ) ).thenReturn( Optional.of(
                new ClusterDetails( "numismatics", incumbent, List.of( incumbent ), Map.of(), Instant.now() ) ) );

        assertThrows( IllegalStateException.class,
                      () -> service.apply( "american-coinage", "numismatics", "tester" ) );
        verifyNoInteractions( saveHelper );
    }

    /** One page failing must not abandon the rest — report it and carry on. */
    @Test
    void apply_reports_a_failed_page_and_continues() throws Exception {
        corpus( "machine-learning",
                page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );
        doThrow( new RuntimeException( "disk full" ) )
                .when( saveHelper ).saveText( eq( "MLHub" ), anyString(), any() );

        final ClusterRenameResult result = service.apply( "machine-learning", "ml", "tester" );

        assertEquals( List.of( "InferenceServing" ), result.renamed() );
        assertTrue( result.failures().get( "MLHub" ).contains( "disk full" ) );
    }
}
