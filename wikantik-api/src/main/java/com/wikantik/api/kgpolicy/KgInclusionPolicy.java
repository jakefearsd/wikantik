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
package com.wikantik.api.kgpolicy;

import com.wikantik.api.engine.Initializable;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether a wiki page contributes to the knowledge graph. The
 * algorithm is: system page → exclude; explicit frontmatter false → exclude;
 * explicit frontmatter true → include; else cluster policy; else default
 * exclude. See docs/superpowers/specs/2026-04-27-kg-inclusion-policy-design.md.
 *
 * <p>Reads cluster policy from {@code kg_cluster_policy} via a small
 * write-through cache. Reads frontmatter overrides from the structural
 * index cache.</p>
 *
 * <p>Search remains governed by the existing {@code SystemPageRegistry}
 * predicate; this service is orthogonal and only affects the KG.</p>
 */
public interface KgInclusionPolicy extends Initializable {

    /* --------------------------- decision --------------------------- */

    /** Effective KG action for the page identified by {@code pageName}. */
    ClusterAction shouldInclude( String pageName );

    /** Detailed explanation suitable for the admin "why is this page in/out?" view. */
    PolicyExplanation explain( String canonicalIdOrPageName );

    /* --------------------------- policy reads ----------------------- */

    /** All clusters with an explicit policy row. */
    List< ClusterPolicy > listClusterPolicies();

    Optional< ClusterPolicy > getClusterPolicy( String cluster );

    /* --------------------------- policy writes ---------------------- */

    /**
     * Set or change a cluster's policy. Writes {@code kg_cluster_policy} and
     * {@code kg_policy_audit} in one transaction; invalidates the cache;
     * enqueues an eager reconciliation job.
     */
    void setClusterPolicy( String cluster, ClusterAction action, String reason, String actor );

    /** Remove the cluster's policy row (returns to the unset/default-exclude state). */
    void clearClusterPolicy( String cluster, String actor );

    /** Bumps {@code reviewed_at} to NOW without changing the action. */
    void markReviewed( String cluster, String actor );

    /* --------------------------- audit ------------------------------ */

    /** Reverse-chronological audit entries, optionally filtered by cluster. */
    List< PolicyAuditEntry > listAudit( Optional< String > cluster, int limit );

    /* --------------------------- bootstrap -------------------------- */

    /**
     * One-time wizard commit: insert policy rows for the supplied include/
     * exclude lists. Idempotent only when the table is empty; otherwise
     * throws to avoid clobbering admin-set rows.
     */
    void bootstrap( List< String > includeClusters,
                     List< String > excludeClusters,
                     String reason,
                     String actor );
}
