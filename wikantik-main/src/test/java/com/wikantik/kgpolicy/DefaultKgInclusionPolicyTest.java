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
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralIndexService;
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
                PageType.ARTICLE, cluster, List.of(), null, Instant.now(), Optional.empty() );
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
