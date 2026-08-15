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
package com.wikantik.kgpolicy;

import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *  ClusterDeclarationDesign Phase 5 — Knowledge Graph inclusion across several memberships.
 *
 *  <p>The rule is <b>fail-closed</b>: an explicit EXCLUDE on any membership wins overall.
 *  Adding a second cluster to a page must never be able to quietly pull it into the
 *  Knowledge Graph, because inclusion is the one place cluster is authoritative rather
 *  than advisory.</p>
 */
class KgInclusionMultiMembershipTest {

    private SystemPageRegistry sysReg;
    private StructuralIndexService structural;
    private KgClusterPolicyRepository repo;
    private FrontmatterOverrideReader overrides;

    @BeforeEach
    void setup() {
        sysReg = mock( SystemPageRegistry.class );
        structural = mock( StructuralIndexService.class );
        repo = mock( KgClusterPolicyRepository.class );
        overrides = mock( FrontmatterOverrideReader.class );
        when( sysReg.isSystemPage( anyString() ) ).thenReturn( false );
        when( overrides.kgInclude( anyString() ) ).thenReturn( Optional.empty() );
        when( structural.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        when( structural.getByCanonicalId( anyString() ) ).thenReturn( Optional.empty() );
        when( repo.find( anyString() ) ).thenReturn( Optional.empty() );
    }

    private DefaultKgInclusionPolicy newPolicy() {
        return new DefaultKgInclusionPolicy( sysReg, structural, repo, overrides );
    }

    private void wirePage( final String pageName, final List< String > clusters ) {
        final String id = "01HAA000000000000000000000";
        final PageDescriptor pd = new PageDescriptor( id, pageName, pageName, PageType.ARTICLE,
                null, clusters, List.of(), null, Instant.now(), Optional.empty(), false );
        when( structural.resolveCanonicalIdFromSlug( pageName ) ).thenReturn( Optional.of( id ) );
        when( structural.getByCanonicalId( id ) ).thenReturn( Optional.of( pd ) );
    }

    private void policy( final String cluster, final ClusterAction action ) {
        when( repo.find( cluster ) ).thenReturn( Optional.of(
                new ClusterPolicy( cluster, action, "test", null, null, null ) ) );
    }

    /** An INCLUDE on any membership is enough — that is what multi-membership is for. */
    @Test
    void an_include_on_a_secondary_membership_includes_the_page() {
        wirePage( "PortfolioOptimization", List.of( "quantitative-finance", "machine-learning" ) );
        policy( "machine-learning", ClusterAction.INCLUDE );

        assertEquals( ClusterAction.INCLUDE, newPolicy().shouldInclude( "PortfolioOptimization" ) );
    }

    /**
     * The fail-closed rule, and the reason this test exists: a curator who deliberately
     * excluded a cluster must not have that decision undone by someone adding a second
     * membership to one of its pages.
     */
    @Test
    void an_explicit_exclude_on_any_membership_beats_an_include_on_another() {
        wirePage( "PortfolioOptimization", List.of( "machine-learning", "private-notes" ) );
        policy( "machine-learning", ClusterAction.INCLUDE );
        policy( "private-notes", ClusterAction.EXCLUDE );

        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "PortfolioOptimization" ) );
    }

    /** Order must not matter: EXCLUDE wins whether it is primary or secondary. */
    @Test
    void the_exclude_wins_regardless_of_membership_order() {
        wirePage( "PortfolioOptimization", List.of( "private-notes", "machine-learning" ) );
        policy( "machine-learning", ClusterAction.INCLUDE );
        policy( "private-notes", ClusterAction.EXCLUDE );

        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "PortfolioOptimization" ) );
    }

    /** No policy anywhere is not an EXCLUDE decision, just an absent one — still default-exclude. */
    @Test
    void memberships_with_no_policy_at_all_stay_excluded() {
        wirePage( "PortfolioOptimization", List.of( "a", "b" ) );

        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "PortfolioOptimization" ) );
    }

    /** An explicit EXCLUDE inherited from an ancestor still fails the page closed. */
    @Test
    void an_inherited_exclude_on_a_secondary_membership_still_wins() {
        wirePage( "RiskModels", List.of( "machine-learning", "private-notes/drafts" ) );
        policy( "machine-learning", ClusterAction.INCLUDE );
        policy( "private-notes", ClusterAction.EXCLUDE );

        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "RiskModels" ) );
    }
}
