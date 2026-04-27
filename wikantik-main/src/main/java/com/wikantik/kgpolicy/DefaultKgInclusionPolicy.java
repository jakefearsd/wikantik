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

import com.wikantik.api.core.Engine;
import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
import com.wikantik.api.kgpolicy.PolicyExplanation;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default implementation of {@link KgInclusionPolicy}.
 *
 * <p>Decision algorithm (first matching rule wins):
 * <ol>
 *   <li>System page → EXCLUDE</li>
 *   <li>Frontmatter {@code kg_include: false} → EXCLUDE</li>
 *   <li>Frontmatter {@code kg_include: true} → INCLUDE</li>
 *   <li>Cluster policy {@code include} → INCLUDE</li>
 *   <li>Cluster policy {@code exclude} → EXCLUDE</li>
 *   <li>No cluster / no policy row → EXCLUDE (system default)</li>
 * </ol>
 *
 * <p>Cluster policy lookups are memoised in a {@link ConcurrentHashMap} and
 * invalidated on every write.  The cache is intentionally not bounded — cluster
 * counts are in the tens, not millions.</p>
 */
public class DefaultKgInclusionPolicy implements KgInclusionPolicy {

    private static final Logger LOG = LogManager.getLogger( DefaultKgInclusionPolicy.class );

    private final SystemPageRegistry systemPages;
    private final StructuralIndexService structural;
    private final KgClusterPolicyRepository repo;
    private final FrontmatterOverrideReader overrides;

    private final ConcurrentMap< String, Optional< ClusterPolicy > > policyCache = new ConcurrentHashMap<>();

    public DefaultKgInclusionPolicy( final SystemPageRegistry systemPages,
                                       final StructuralIndexService structural,
                                       final KgClusterPolicyRepository repo,
                                       final FrontmatterOverrideReader overrides ) {
        this.systemPages = systemPages;
        this.structural  = structural;
        this.repo        = repo;
        this.overrides   = overrides;
    }

    @Override
    public void initialize( final Engine engine, final Properties props ) {
        // Lazy cache. Nothing to do at startup.
    }

    @Override
    public ClusterAction shouldInclude( final String pageName ) {
        if ( systemPages.isSystemPage( pageName ) ) return ClusterAction.EXCLUDE;
        final Optional< Boolean > override = overrides.kgInclude( pageName );
        if ( override.isPresent() ) {
            return override.get() ? ClusterAction.INCLUDE : ClusterAction.EXCLUDE;
        }
        final Optional< String > cluster = pageDescriptor( pageName ).map( PageDescriptor::cluster );
        if ( cluster.isEmpty() ) return ClusterAction.EXCLUDE;
        return lookupCluster( cluster.get() ).map( ClusterPolicy::action ).orElse( ClusterAction.EXCLUDE );
    }

    @Override
    public PolicyExplanation explain( final String canonicalIdOrPageName ) {
        // Try canonical_id first; fall back to slug/page-name lookup.
        Optional< PageDescriptor > pdOpt = structural.getByCanonicalId( canonicalIdOrPageName );
        if ( pdOpt.isEmpty() ) {
            pdOpt = pageDescriptor( canonicalIdOrPageName );
        }
        if ( pdOpt.isEmpty() ) {
            throw new IllegalArgumentException( "page not found: " + canonicalIdOrPageName );
        }
        final PageDescriptor pd = pdOpt.get();
        final boolean isSys = systemPages.isSystemPage( pd.slug() );
        final Optional< Boolean > override = overrides.kgInclude( pd.slug() );
        final Optional< ClusterPolicy > policy = pd.cluster() == null
                ? Optional.empty() : lookupCluster( pd.cluster() );

        final ClusterAction effective;
        final Optional< ExclusionReason > reason;
        if ( isSys ) {
            effective = ClusterAction.EXCLUDE; reason = Optional.of( ExclusionReason.SYSTEM_PAGE );
        } else if ( override.isPresent() && !override.get() ) {
            effective = ClusterAction.EXCLUDE; reason = Optional.of( ExclusionReason.PAGE_OVERRIDE );
        } else if ( override.isPresent() && override.get() ) {
            effective = ClusterAction.INCLUDE; reason = Optional.empty();
        } else if ( policy.isPresent() && policy.get().action() == ClusterAction.INCLUDE ) {
            effective = ClusterAction.INCLUDE; reason = Optional.empty();
        } else {
            effective = ClusterAction.EXCLUDE; reason = Optional.of( ExclusionReason.CLUSTER_POLICY );
        }

        return new PolicyExplanation(
                pd.canonicalId(),
                pd.slug(),
                pd.cluster(),
                isSys,
                override,
                policy.map( ClusterPolicy::action ),
                effective,
                reason,
                Optional.empty(),  // lastExtractedAt — wired in later
                0, 0
        );
    }

    @Override
    public List< ClusterPolicy > listClusterPolicies() {
        return repo.list();
    }

    @Override
    public Optional< ClusterPolicy > getClusterPolicy( final String cluster ) {
        return lookupCluster( cluster );
    }

    @Override
    public void setClusterPolicy( final String cluster, final ClusterAction action,
                                    final String reason, final String actor ) {
        final Optional< ClusterPolicy > prior = repo.find( cluster );
        repo.upsert( cluster, action, reason, actor );
        repo.appendAudit( cluster,
                prior.map( p -> p.action().wire() ).orElse( null ),
                action.wire(),
                reason, actor );
        policyCache.remove( cluster );
        ReconciliationHook.onClusterPolicyChange( cluster );
    }

    @Override
    public void clearClusterPolicy( final String cluster, final String actor ) {
        final Optional< ClusterPolicy > prior = repo.find( cluster );
        if ( prior.isEmpty() ) return;
        repo.delete( cluster );
        repo.appendAudit( cluster, prior.get().action().wire(), "cleared", null, actor );
        policyCache.remove( cluster );
        ReconciliationHook.onClusterPolicyChange( cluster );
    }

    @Override
    public void markReviewed( final String cluster, final String actor ) {
        repo.markReviewed( cluster );
        repo.appendAudit( cluster,
                repo.find( cluster ).map( p -> p.action().wire() ).orElse( null ),
                "reviewed", null, actor );
        policyCache.remove( cluster );
    }

    @Override
    public List< PolicyAuditEntry > listAudit( final Optional< String > cluster, final int limit ) {
        return repo.listAudit( cluster, Math.max( 1, Math.min( limit, 1000 ) ) );
    }

    @Override
    public void bootstrap( final List< String > includeClusters,
                            final List< String > excludeClusters,
                            final String reason, final String actor ) {
        if ( !repo.list().isEmpty() ) {
            throw new IllegalStateException(
                    "Bootstrap requires kg_cluster_policy to be empty (run wikantik kg-policy clear ...)." );
        }
        for ( final String c : includeClusters ) setClusterPolicy( c, ClusterAction.INCLUDE, reason, actor );
        for ( final String c : excludeClusters ) setClusterPolicy( c, ClusterAction.EXCLUDE, reason, actor );
    }

    /* ---- helpers ---- */

    private Optional< PageDescriptor > pageDescriptor( final String pageName ) {
        return structural.resolveCanonicalIdFromSlug( pageName ).flatMap( structural::getByCanonicalId );
    }

    private Optional< ClusterPolicy > lookupCluster( final String cluster ) {
        return policyCache.computeIfAbsent( cluster, repo::find );
    }
}
