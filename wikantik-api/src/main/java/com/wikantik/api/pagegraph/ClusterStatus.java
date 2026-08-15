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
 *  Derived taxonomy context for a single page, shipped on the page payload so the
 *  reader can render the declaration without a second request.
 *
 *  <p>A cluster is declared by the unique hub page carrying {@code type: hub} plus that
 *  cluster path. That pairing is implicit in frontmatter, so the reader would otherwise
 *  have to infer it — this record makes it explicit. A {@code null} {@link #hubSlug()} is
 *  the signal that nothing declares the cluster yet.</p>
 *
 *  <p>Computed server-side on purpose: whether a cluster has a hub is <b>not</b> in the
 *  page's own frontmatter, and the reader-banner contract requires a pure function of
 *  props with no client-side fetching.</p>
 *
 *  @param path        the cluster path the page declares or names
 *  @param parent      the parent cluster path, or {@code null} for a top-level cluster
 *  @param hubSlug     the page declaring this cluster, or {@code null} when undeclared
 *  @param memberCount pages in the cluster, including its sub-clusters
 */
public record ClusterStatus(
        String path,
        String parent,
        String hubSlug,
        int memberCount
) {

    /**
     *  Builds the status for a page's cluster.
     *
     *  @param cluster the page's declared cluster path; {@code null}/blank yields {@code null}
     *  @param details the resolved cluster, or {@code null} when the structural index is
     *                 unavailable or holds no entry — the path is still reported
     *  @return the derived status, or {@code null} when the page names no cluster
     */
    public static ClusterStatus of( final String cluster, final ClusterDetails details ) {
        if ( cluster == null || cluster.isBlank() ) {
            return null;
        }
        final String hubSlug = details == null || details.hubPage() == null
                ? null : details.hubPage().slug();
        final int memberCount = details == null ? 0 : details.articles().size();
        return new ClusterStatus( cluster, ClusterPath.parent( cluster ), hubSlug, memberCount );
    }
}
