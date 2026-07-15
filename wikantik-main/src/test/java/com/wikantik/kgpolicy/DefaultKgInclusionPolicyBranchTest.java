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
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
import com.wikantik.api.kgpolicy.PolicyExplanation;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Targets previously-uncovered branches in {@link DefaultKgInclusionPolicy}:
 * <ul>
 *   <li>explain() via canonical-id lookup (first getByCanonicalId hit, lines 94-95)</li>
 *   <li>explain() page-not-found throws (98-99)</li>
 *   <li>explain() system-page branch (109-110)</li>
 *   <li>explain() override=false branch (111-112)</li>
 *   <li>explain() override=true branch (113-114)</li>
 *   <li>explain() cluster-exclude fallback (115-118)</li>
 *   <li>explain() null cluster (104-105)</li>
 *   <li>setClusterPolicy / clearClusterPolicy (prior absent early-return) / markReviewed / listAudit / bootstrap</li>
 *   <li>ReconciliationHook.install + onClusterPolicyChange routing</li>
 * </ul>
 */
class DefaultKgInclusionPolicyBranchTest {

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
        when( structural.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        when( structural.getByCanonicalId( anyString() ) ).thenReturn( Optional.empty() );
    }

    private DefaultKgInclusionPolicy policy() {
        return new DefaultKgInclusionPolicy( sysReg, structural, repo, overrides );
    }

    private PageDescriptor descriptor( final String id, final String slug, final String cluster ) {
        return new PageDescriptor( id, slug, slug, PageType.ARTICLE, cluster,
                List.of(), null, Instant.now(), Optional.empty(), false );
    }

    // -----------------------------------------------------------------------
    // explain() — canonical-id first-hit path (line 94-95 in the source)
    // -----------------------------------------------------------------------

    @Test
    void explain_uses_canonical_id_when_direct_lookup_succeeds() {
        // When getByCanonicalId("cid-1") succeeds directly, the slug lookup is skipped.
        final PageDescriptor pd = descriptor( "cid-1", "MyPage", "java" );
        when( structural.getByCanonicalId( "cid-1" ) ).thenReturn( Optional.of( pd ) );
        when( repo.find( "java" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null ) ) );

        final PolicyExplanation ex = policy().explain( "cid-1" );
        assertEquals( "cid-1", ex.canonicalId() );
        assertEquals( "MyPage", ex.pageName() );
        assertEquals( ClusterAction.INCLUDE, ex.effectiveAction() );
        assertTrue( ex.exclusionReason().isEmpty() );
    }

    // -----------------------------------------------------------------------
    // explain() — page-not-found throws IllegalArgumentException (line 98-99)
    // -----------------------------------------------------------------------

    @Test
    void explain_throws_when_page_not_found() {
        // Both getByCanonicalId and pageDescriptor return empty → IAE
        when( structural.getByCanonicalId( "ghost" ) ).thenReturn( Optional.empty() );
        when( structural.resolveCanonicalIdFromSlug( "ghost" ) ).thenReturn( Optional.empty() );

        assertThrows( IllegalArgumentException.class, () -> policy().explain( "ghost" ) );
    }

    // -----------------------------------------------------------------------
    // explain() — system-page branch (109-110)
    // -----------------------------------------------------------------------

    @Test
    void explain_system_page_branch_sets_system_page_exclusion_reason() {
        final PageDescriptor pd = descriptor( "cid-sys", "Sandbox", null );
        when( structural.getByCanonicalId( "cid-sys" ) ).thenReturn( Optional.of( pd ) );
        when( sysReg.isSystemPage( "Sandbox" ) ).thenReturn( true );

        final PolicyExplanation ex = policy().explain( "cid-sys" );
        assertEquals( ClusterAction.EXCLUDE, ex.effectiveAction() );
        assertEquals( Optional.of( ExclusionReason.SYSTEM_PAGE ), ex.exclusionReason() );
        assertTrue( ex.systemPage() );
    }

    // -----------------------------------------------------------------------
    // explain() — override=false branch (111-112)
    // -----------------------------------------------------------------------

    @Test
    void explain_override_false_gives_page_override_reason() {
        final PageDescriptor pd = descriptor( "cid-no", "NoisyPage", "java" );
        when( structural.resolveCanonicalIdFromSlug( "NoisyPage" ) ).thenReturn( Optional.of( "cid-no" ) );
        when( structural.getByCanonicalId( "cid-no" ) ).thenReturn( Optional.of( pd ) );
        when( overrides.kgInclude( "NoisyPage" ) ).thenReturn( Optional.of( false ) );
        // cluster include would win without the override
        when( repo.find( "java" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null ) ) );

        final PolicyExplanation ex = policy().explain( "NoisyPage" );
        assertEquals( ClusterAction.EXCLUDE, ex.effectiveAction() );
        assertEquals( Optional.of( ExclusionReason.PAGE_OVERRIDE ), ex.exclusionReason() );
    }

    // -----------------------------------------------------------------------
    // explain() — override=true branch (113-114)
    // -----------------------------------------------------------------------

    @Test
    void explain_override_true_gives_include_with_no_reason() {
        final PageDescriptor pd = descriptor( "cid-yes", "StarPage", "van-life" );
        when( structural.resolveCanonicalIdFromSlug( "StarPage" ) ).thenReturn( Optional.of( "cid-yes" ) );
        when( structural.getByCanonicalId( "cid-yes" ) ).thenReturn( Optional.of( pd ) );
        when( overrides.kgInclude( "StarPage" ) ).thenReturn( Optional.of( true ) );
        // cluster would exclude, but override wins
        when( repo.find( "van-life" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "van-life", ClusterAction.EXCLUDE, "r", "a", Instant.now(), null ) ) );

        final PolicyExplanation ex = policy().explain( "StarPage" );
        assertEquals( ClusterAction.INCLUDE, ex.effectiveAction() );
        assertTrue( ex.exclusionReason().isEmpty() );
    }

    // -----------------------------------------------------------------------
    // explain() — cluster-exclude fallback (else branch, lines 117-118)
    // -----------------------------------------------------------------------

    @Test
    void explain_cluster_exclude_policy_gives_cluster_policy_reason() {
        final PageDescriptor pd = descriptor( "cid-ex", "ExPage", "noisy-cluster" );
        when( structural.resolveCanonicalIdFromSlug( "ExPage" ) ).thenReturn( Optional.of( "cid-ex" ) );
        when( structural.getByCanonicalId( "cid-ex" ) ).thenReturn( Optional.of( pd ) );
        when( repo.find( "noisy-cluster" ) ).thenReturn(
                Optional.of( new ClusterPolicy( "noisy-cluster", ClusterAction.EXCLUDE, "r", "a", Instant.now(), null ) ) );

        final PolicyExplanation ex = policy().explain( "ExPage" );
        assertEquals( ClusterAction.EXCLUDE, ex.effectiveAction() );
        assertEquals( Optional.of( ExclusionReason.CLUSTER_POLICY ), ex.exclusionReason() );
    }

    // -----------------------------------------------------------------------
    // explain() — null cluster (104-105) — no cluster → always EXCLUDE
    // -----------------------------------------------------------------------

    @Test
    void explain_null_cluster_gives_cluster_policy_reason() {
        // cluster field is null in PageDescriptor
        final PageDescriptor pd = descriptor( "cid-nc", "NoClusterPage", null );
        when( structural.resolveCanonicalIdFromSlug( "NoClusterPage" ) ).thenReturn( Optional.of( "cid-nc" ) );
        when( structural.getByCanonicalId( "cid-nc" ) ).thenReturn( Optional.of( pd ) );

        final PolicyExplanation ex = policy().explain( "NoClusterPage" );
        assertEquals( ClusterAction.EXCLUDE, ex.effectiveAction() );
        assertNull( ex.cluster() );
    }

    // -----------------------------------------------------------------------
    // setClusterPolicy — wires repo + audit + cache invalidation (148-155)
    // -----------------------------------------------------------------------

    @Test
    void setClusterPolicy_upserts_and_records_audit() {
        when( repo.find( "java" ) ).thenReturn( Optional.empty() );  // no prior
        when( repo.list() ).thenReturn( List.of() );

        policy().setClusterPolicy( "java", ClusterAction.INCLUDE, "bootstrap", "admin" );

        verify( repo ).upsert( "java", ClusterAction.INCLUDE, "bootstrap", "admin" );
        // appendAudit with null old action (first set)
        verify( repo ).appendAudit( "java", null, "include", "bootstrap", "admin" );
    }

    @Test
    void setClusterPolicy_with_prior_records_old_action_in_audit() {
        final ClusterPolicy prior = new ClusterPolicy( "java", ClusterAction.EXCLUDE, "noisy", "a", Instant.now(), null );
        when( repo.find( "java" ) ).thenReturn( Optional.of( prior ) );

        policy().setClusterPolicy( "java", ClusterAction.INCLUDE, "promote", "admin" );

        verify( repo ).appendAudit( "java", "exclude", "include", "promote", "admin" );
    }

    // -----------------------------------------------------------------------
    // clearClusterPolicy — early-return when no prior (161), actual delete (162-165)
    // -----------------------------------------------------------------------

    @Test
    void clearClusterPolicy_is_noop_when_no_prior_exists() {
        when( repo.find( "ghost-cluster" ) ).thenReturn( Optional.empty() );

        policy().clearClusterPolicy( "ghost-cluster", "admin" );

        verify( repo, never() ).delete( anyString() );
        verify( repo, never() ).appendAudit( anyString(), any(), anyString(), any(), anyString() );
    }

    @Test
    void clearClusterPolicy_deletes_and_audits_when_prior_exists() {
        final ClusterPolicy prior = new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null );
        when( repo.find( "java" ) ).thenReturn( Optional.of( prior ) );

        policy().clearClusterPolicy( "java", "admin" );

        verify( repo ).delete( "java" );
        verify( repo ).appendAudit( "java", "include", "cleared", null, "admin" );
    }

    // -----------------------------------------------------------------------
    // markReviewed — marks and audits (169-174)
    // -----------------------------------------------------------------------

    @Test
    void markReviewed_calls_repo_and_appends_audit() {
        // After markReviewed, repo.find returns the policy (for the audit entry)
        final ClusterPolicy existing = new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null );
        when( repo.find( "java" ) ).thenReturn( Optional.of( existing ) );

        policy().markReviewed( "java", "reviewer" );

        verify( repo ).markReviewed( "java" );
        verify( repo ).appendAudit( "java", "include", "reviewed", null, "reviewer" );
    }

    @Test
    void markReviewed_with_no_existing_policy_uses_null_old_action() {
        when( repo.find( "no-policy-cluster" ) ).thenReturn( Optional.empty() );

        policy().markReviewed( "no-policy-cluster", "reviewer" );

        verify( repo ).markReviewed( "no-policy-cluster" );
        verify( repo ).appendAudit( "no-policy-cluster", null, "reviewed", null, "reviewer" );
    }

    // -----------------------------------------------------------------------
    // listAudit — clamps limit to [1, 1000] (line 179)
    // -----------------------------------------------------------------------

    @Test
    void listAudit_delegates_with_clamped_limit() {
        final List<PolicyAuditEntry> empty = List.of();
        when( repo.listAudit( any(), anyInt() ) ).thenReturn( empty );

        // limit=0 → clamped to 1
        policy().listAudit( Optional.empty(), 0 );
        verify( repo ).listAudit( Optional.empty(), 1 );

        // limit=999999 → clamped to 1000
        policy().listAudit( Optional.of( "java" ), 999999 );
        verify( repo ).listAudit( Optional.of( "java" ), 1000 );

        // limit=50 → passes through unchanged
        policy().listAudit( Optional.empty(), 50 );
        verify( repo ).listAudit( Optional.empty(), 50 );
    }

    // -----------------------------------------------------------------------
    // bootstrap — throws when policies already exist, iterates both lists (185-191)
    // -----------------------------------------------------------------------

    @Test
    void bootstrap_throws_when_policies_already_exist() {
        final ClusterPolicy existing = new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null );
        when( repo.list() ).thenReturn( List.of( existing ) );

        assertThrows( IllegalStateException.class,
                () -> policy().bootstrap( List.of( "java" ), List.of(), "r", "admin" ) );
    }

    @Test
    void bootstrap_sets_include_and_exclude_clusters() {
        when( repo.list() ).thenReturn( List.of() );
        when( repo.find( anyString() ) ).thenReturn( Optional.empty() );

        policy().bootstrap( List.of( "java", "python" ), List.of( "spam", "ads" ), "boot", "admin" );

        verify( repo ).upsert( "java",   ClusterAction.INCLUDE, "boot", "admin" );
        verify( repo ).upsert( "python", ClusterAction.INCLUDE, "boot", "admin" );
        verify( repo ).upsert( "spam",   ClusterAction.EXCLUDE, "boot", "admin" );
        verify( repo ).upsert( "ads",    ClusterAction.EXCLUDE, "boot", "admin" );
    }

    // -----------------------------------------------------------------------
    // ReconciliationHook — install(null) installs no-op; onClusterPolicyChange routes
    // -----------------------------------------------------------------------

    @Test
    void reconciliationHook_install_null_becomes_noop() {
        ReconciliationHook.install( null );
        // Should not throw
        assertDoesNotThrow( () -> ReconciliationHook.onClusterPolicyChange( "any-cluster" ) );
    }

    @Test
    void reconciliationHook_routes_to_installed_consumer() {
        final List<String> received = new ArrayList<>();
        ReconciliationHook.install( received::add );

        ReconciliationHook.onClusterPolicyChange( "triggered-cluster" );

        assertEquals( 1, received.size() );
        assertEquals( "triggered-cluster", received.get( 0 ) );

        // Restore to no-op so we don't pollute other tests
        ReconciliationHook.install( null );
    }

    // -----------------------------------------------------------------------
    // getClusterPolicy — delegates to lookupCluster (line 142)
    // -----------------------------------------------------------------------

    @Test
    void getClusterPolicy_returns_empty_when_repo_returns_empty() {
        when( repo.find( "unknown" ) ).thenReturn( Optional.empty() );
        assertTrue( policy().getClusterPolicy( "unknown" ).isEmpty() );
    }

    @Test
    void getClusterPolicy_returns_policy_when_present() {
        final ClusterPolicy cp = new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null );
        when( repo.find( "java" ) ).thenReturn( Optional.of( cp ) );
        assertEquals( Optional.of( cp ), policy().getClusterPolicy( "java" ) );
    }

    // -----------------------------------------------------------------------
    // listClusterPolicies — delegates to repo.list() (line 137)
    // -----------------------------------------------------------------------

    @Test
    void listClusterPolicies_returns_repo_list() {
        final ClusterPolicy cp = new ClusterPolicy( "java", ClusterAction.INCLUDE, "r", "a", Instant.now(), null );
        when( repo.list() ).thenReturn( List.of( cp ) );
        assertEquals( List.of( cp ), policy().listClusterPolicies() );
    }
}
