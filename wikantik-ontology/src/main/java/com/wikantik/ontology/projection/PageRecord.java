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
package com.wikantik.ontology.projection;

import java.util.List;

import com.wikantik.api.pagegraph.ClusterPath;

/**
 * Flattened page input for the Page/Concept projectors. Built in Phase 1b from
 * PageCanonicalIdsDao.Row + FrontmatterParser; kept api-free so this module
 * has no wikantik-main dependency (wikantik-api, which owns {@link ClusterPath},
 * is a normal compile dependency of this module already).
 *
 * <p><b>Cluster membership (ClusterDeclarationDesign Phase 5).</b> A page may belong to
 * several clusters, so {@code clusters} holds the full membership set while {@code cluster}
 * remains <b>the primary</b> — the first membership. The two can never disagree: the canonical
 * constructor re-derives {@code cluster} from {@code clusters}, mirroring
 * {@code com.wikantik.api.pagegraph.PageDescriptor}. Projectors that mint one concept per
 * membership (SKOS {@code dct:subject} is genuinely multi-valued) iterate {@code clusters()};
 * projectors needing a single answer keep using {@code cluster()}.</p>
 */
public record PageRecord(
        String canonicalId,
        String slug,
        String title,
        String type,             // frontmatter type: hub/article/reference/runbook/design
        String cluster,          // primary cluster; may be null; "parent/sub" for sub-clusters
        List< String > clusters, // full membership set; never null, cluster() == clusters().get(0)
        List< String > tags,
        String summary,
        String isoDate,          // frontmatter date as ISO string, may be null
        String author            // may be null
) {
    public PageRecord {
        // An explicit membership list wins; otherwise the scalar is the sole membership. The
        // primary is then re-derived from the list so cluster() is always clusters().get(0) and
        // the two views of the same fact cannot drift apart.
        clusters = clusters == null || clusters.isEmpty()
                ? ClusterPath.memberships( cluster )
                : ClusterPath.memberships( clusters );
        cluster = clusters.isEmpty() ? null : clusters.get( 0 );
        tags = tags == null ? List.of() : List.copyOf( tags );
    }

    /**
     *  Single-cluster convenience form — the shape every caller used before multi-membership.
     *
     *  @param canonicalId stable ULID
     *  @param slug        page name
     *  @param title       display title
     *  @param type        frontmatter type
     *  @param cluster     the page's only cluster, or {@code null}
     *  @param tags        topic tags
     *  @param summary     frontmatter summary
     *  @param isoDate     frontmatter date as ISO string, may be null
     *  @param author      may be null
     */
    // The parameter list is the record's own component list minus `clusters`; PMD cannot see
    // that a record's canonical constructor already carries it. Dropping this overload would
    // force every single-cluster call site to spell out a redundant derived argument.
    @SuppressWarnings( "PMD.ExcessiveParameterList" )
    public PageRecord( final String canonicalId, final String slug, final String title, final String type,
            final String cluster, final List< String > tags, final String summary, final String isoDate,
            final String author ) {
        this( canonicalId, slug, title, type, cluster, ClusterPath.memberships( cluster ),
              tags, summary, isoDate, author );
    }
}
