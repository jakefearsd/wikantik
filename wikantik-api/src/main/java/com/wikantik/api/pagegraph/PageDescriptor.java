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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight descriptor of a wiki page for structural queries — enough to render
 * a compact listing without the full page body. All fields are non-null except
 * {@code cluster}, {@code summary}, and {@code updated}; prefer empty collections
 * over null for list fields.
 *
 * <p>{@code kgInclude} carries the page-level {@code kg_include} frontmatter
 * override ({@code true}/{@code false}). {@link Optional#empty()} means the
 * frontmatter did not specify a value and the inclusion decision falls back to
 * the cluster / global policy.</p>
 *
 * <p>{@code derived} marks pages ingested from an external source (i.e. the
 * frontmatter carries a {@code derived_from} key) — see {@code DerivedPage}
 * in wikantik-main.</p>
 *
 * <p><b>Cluster membership (ClusterDeclarationDesign Phase 5).</b> A page may belong to
 * several clusters, so {@code clusters} holds the full membership set while {@code cluster}
 * remains <b>the primary</b> — the first membership, which drives breadcrumbs, JSON-LD
 * {@code articleSection}/{@code isPartOf}, the embedding prefix, and sidebar placement.
 * The two can never disagree: the canonical constructor re-derives {@code cluster} from
 * {@code clusters}. Consumers asking "where does this page live?" keep using
 * {@code cluster()}; only consumers asking "is this page a member of X?" need
 * {@code clusters()}.</p>
 */
public record PageDescriptor(
        String canonicalId,
        String slug,
        String title,
        PageType type,
        String cluster,
        List< String > clusters,
        List< String > tags,
        String summary,
        Instant updated,
        Optional< Boolean > kgInclude,
        boolean derived
) {
    public PageDescriptor {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId required" );
        }
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        if ( title == null ) {
            title = slug;
        }
        if ( type == null ) {
            type = PageType.UNKNOWN;
        }
        // An explicit membership list wins; otherwise the scalar is the sole membership. Then the
        // primary is re-derived from the list, so cluster() is always clusters().get(0) and the
        // two views of the same fact cannot drift apart.
        clusters = clusters == null || clusters.isEmpty()
                ? ClusterPath.memberships( cluster )
                : ClusterPath.memberships( clusters );
        cluster = clusters.isEmpty() ? null : clusters.get( 0 );
        tags = tags == null ? List.of() : List.copyOf( tags );
        kgInclude = kgInclude == null ? Optional.empty() : kgInclude;
    }

    /**
     *  Single-cluster convenience form — the shape every caller used before multi-membership.
     *
     *  @param canonicalId stable ULID
     *  @param slug        page name
     *  @param title       display title
     *  @param type        page type
     *  @param cluster     the page's only cluster, or {@code null}
     *  @param tags        topic tags
     *  @param summary     frontmatter summary
     *  @param updated     last modification
     *  @param kgInclude   page-level Knowledge Graph override
     *  @param derived     whether the page was ingested from an external source
     */
    public PageDescriptor( final String canonicalId, final String slug, final String title,
                           final PageType type, final String cluster, final List< String > tags,
                           final String summary, final Instant updated,
                           final Optional< Boolean > kgInclude, final boolean derived ) {
        this( canonicalId, slug, title, type, cluster, ClusterPath.memberships( cluster ),
              tags, summary, updated, kgInclude, derived );
    }
}
