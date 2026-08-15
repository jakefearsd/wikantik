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
package com.wikantik.api.pagegraph;

/**
 * A finding from the rebuild pass that an admin should see and resolve. Phase 4
 * surfaces these via {@code /admin/page-graph/conflicts}; Phase 1 produced
 * them silently via the {@code unclaimed_canonical_ids} health gauge.
 *
 * @param slug         current slug of the affected page (always set)
 * @param canonicalId  authored canonical_id when present; {@code null} when missing
 * @param kind         what's wrong — see {@link Kind}
 * @param detail       human-readable explanation (for logs and admin UIs)
 */
public record StructuralConflict(
        String slug,
        String canonicalId,
        Kind kind,
        String detail
) {
    /**
     *  What kind of structural defect this conflict records.
     *
     *  <p>For most kinds {@code slug} identifies the offending <b>page</b>. The two
     *  cluster-scoped kinds ({@link #HEADLESS_CLUSTER}, {@link #UNDECLARED_CLUSTER}) are
     *  reported once per cluster and carry the <b>cluster path</b> in {@code slug} with a
     *  {@code null} canonicalId — no single page is at fault.</p>
     */
    public enum Kind {
        /** Page lacks a {@code canonical_id} in frontmatter and was indexed under a synthesised ID. */
        MISSING_CANONICAL_ID,
        /** Page declares a relation that the validator rejected. */
        RELATION_ISSUE,
        /**
         *  Two or more hub pages declare the same cluster. Reported once per losing hub;
         *  {@code slug} is the hub that did not win the tie-break.
         */
        DUPLICATE_CLUSTER_DECLARATION,
        /** A cluster has member pages but no hub declares it. Subject: the cluster path. */
        HEADLESS_CLUSTER,
        /**
         *  A sub-cluster names a parent that no hub declares — an orphaned branch of the
         *  taxonomy. Subject: the sub-cluster path.
         */
        UNDECLARED_CLUSTER,
        /** A {@code type: hub} page carries no cluster, so it declares nothing. Subject: the page. */
        CLUSTERLESS_HUB,
        /**
         *  A {@code type: hub} page names more than one cluster. A declaration is singular by
         *  definition, so only the first is treated as declared and the rest are plain
         *  memberships. Subject: the hub page.
         */
        MULTI_CLUSTER_HUB
    }

    public StructuralConflict {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        if ( kind == null ) {
            throw new IllegalArgumentException( "kind required" );
        }
        detail = detail == null ? "" : detail;
    }
}
