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
import com.wikantik.api.kgpolicy.PolicyExplanation;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DefaultKgInclusionPolicyTest {

    private SystemPageRegistry sysReg;
    private StructuralIndexService structural;
    private KgClusterPolicyRepository repo;
    private FrontmatterOverrideReader overrides;

    @BeforeEach
    void setup() {
        sysReg     = mock( SystemPageRegistry.class );
        structural = mock( StructuralIndexService.class );
        repo       = mock( KgClusterPolicyRepository.class );
        overrides  = mock( FrontmatterOverrideReader.class );
        when( sysReg.isSystemPage( anyString() ) ).thenReturn( false );
        when( overrides.kgInclude( anyString() ) ).thenReturn( Optional.empty() );
        // Default: page resolves with no canonical id
        when( structural.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        when( structural.getByCanonicalId( anyString() ) ).thenReturn( Optional.empty() );
    }

    private DefaultKgInclusionPolicy newPolicy() {
        return new DefaultKgInclusionPolicy( sysReg, structural, repo, overrides );
    }

    private void wireSlug( final String pageName, final String cluster ) {
        final String id = "01HAA000000000000000000000";
        final PageDescriptor pd = new PageDescriptor( id, pageName, pageName,
                PageType.ARTICLE, cluster, List.of(), null, Instant.now(), Optional.empty(), false );
        when( structural.resolveCanonicalIdFromSlug( pageName ) ).thenReturn( Optional.of( id ) );
        when( structural.getByCanonicalId( id ) ).thenReturn( Optional.of( pd ) );
    }

    @Test
    void system_page_excluded() {
        when( sysReg.isSystemPage( "Sandbox" ) ).thenReturn( true );
        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "Sandbox" ) );
    }

    @Test
    void frontmatter_false_overrides_cluster_include() {
        wireSlug( "Foo", "java" );
        when( overrides.kgInclude( "Foo" ) ).thenReturn( Optional.of( false ) );
        when( repo.find( "java" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "java", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "Foo" ) );
    }

    @Test
    void frontmatter_true_overrides_cluster_exclude() {
        wireSlug( "Foo", "van-life" );
        when( overrides.kgInclude( "Foo" ) ).thenReturn( Optional.of( true ) );
        when( repo.find( "van-life" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "van-life", ClusterAction.EXCLUDE, "x", "a", Instant.now(), null ) ) );
        assertEquals( ClusterAction.INCLUDE, newPolicy().shouldInclude( "Foo" ) );
    }

    @Test
    void cluster_include_when_no_override() {
        wireSlug( "Foo", "java" );
        when( repo.find( "java" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "java", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        assertEquals( ClusterAction.INCLUDE, newPolicy().shouldInclude( "Foo" ) );
    }

    @Test
    void unset_cluster_defaults_to_exclude() {
        wireSlug( "Foo", "newcluster" );
        when( repo.find( "newcluster" ) ).thenReturn( Optional.empty() );
        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "Foo" ) );
    }

    @Test
    void unknown_page_defaults_to_exclude() {
        // No slug wiring; everything returns Optional.empty()
        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "Mystery" ) );
    }

    @Test
    void sub_cluster_inherits_parent_cluster_policy() {
        wireSlug( "InferenceServing", "machine-learning/mlops" );
        // No policy row for the sub-cluster; the parent is INCLUDE.
        when( repo.find( "machine-learning" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "machine-learning", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        assertEquals( ClusterAction.INCLUDE, newPolicy().shouldInclude( "InferenceServing" ) );
    }

    @Test
    void setting_a_parent_policy_invalidates_cached_sub_cluster_resolution() {
        wireSlug( "InferenceServing", "machine-learning/mlops" );
        final DefaultKgInclusionPolicy policy = newPolicy();
        // No policy anywhere yet: EXCLUDE, and the miss is memoised under the sub-cluster key.
        assertEquals( ClusterAction.EXCLUDE, policy.shouldInclude( "InferenceServing" ) );

        // Operator now sets the PARENT policy. The sub-cluster's cached miss must not survive.
        when( repo.find( "machine-learning" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "machine-learning", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        policy.setClusterPolicy( "machine-learning", ClusterAction.INCLUDE, "x", "a" );

        assertEquals( ClusterAction.INCLUDE, policy.shouldInclude( "InferenceServing" ) );
    }

    @Test
    void sub_cluster_own_policy_wins_over_parent() {
        wireSlug( "Secret", "machine-learning/private" );
        when( repo.find( "machine-learning" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "machine-learning", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        when( repo.find( "machine-learning/private" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "machine-learning/private", ClusterAction.EXCLUDE, "x", "a", Instant.now(), null ) ) );
        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "Secret" ) );
    }

    /**
     * Invariant guard: the ancestor walk must be segment-aware. A `startsWith`
     * implementation would wrongly make `machine-learning-ops` a descendant of
     * `machine-learning`. Passes before and after the inheritance change — it
     * exists to fail if someone rewrites the walk as string-prefix matching.
     */
    @Test
    void sibling_cluster_sharing_a_string_prefix_does_not_inherit() {
        wireSlug( "OpsPage", "machine-learning-ops" );
        when( repo.find( "machine-learning" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "machine-learning", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        assertEquals( ClusterAction.EXCLUDE, newPolicy().shouldInclude( "OpsPage" ) );
    }

    /**
     * Invariant guard: {@code getClusterPolicy} backs the admin detail view and the
     * clear/edit flow, so it reports only the row actually set on that cluster —
     * never one inherited from a parent, which the operator could not clear.
     */
    @Test
    void getClusterPolicy_reports_only_the_row_set_on_that_cluster() {
        when( repo.find( "machine-learning" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "machine-learning", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        assertTrue( newPolicy().getClusterPolicy( "machine-learning/mlops" ).isEmpty() );
    }

    @Test
    void explain_packs_full_state() {
        wireSlug( "Foo", "java" );
        when( repo.find( "java" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "java", ClusterAction.INCLUDE, "x", "a", Instant.now(), null ) ) );
        final PolicyExplanation x = newPolicy().explain( "Foo" );
        assertEquals( "java", x.cluster() );
        assertEquals( ClusterAction.INCLUDE, x.effectiveAction() );
        assertEquals( Optional.of( ClusterAction.INCLUDE ), x.clusterPolicy() );
        assertTrue( x.exclusionReason().isEmpty() );
    }
}
