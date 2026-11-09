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

import com.wikantik.api.markdown.InlineTagSyntax;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight descriptor of a wiki page for structural queries — enough to render
 * a compact listing without the full page body. All fields are non-null except
 * {@code cluster}, {@code summary}, and {@code updated}; prefer empty collections
 * over null for list fields.
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
 *
 * <p><b>Tags (Obsidian phase 4).</b> {@code tags} is the frontmatter {@code tags} <em>as written</em> and every
 * display, ontology, hint and SEO consumer keeps reading it. The body's inline {@code #tags} (as written) ride along
 * in {@code inlineTags}. Tag <em>comparison and aggregation</em> (filters, the tag view, counts) go through
 * {@link #findableTags()}, the normalised union.</p>
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
        boolean derived,
        List< String > inlineTags
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
        inlineTags = inlineTags == null ? List.of() : List.copyOf( inlineTags );
    }

    /** Form without inline tags — every caller that predates Obsidian phase 4. */
    @SuppressWarnings( "PMD.ExcessiveParameterList" )
    public PageDescriptor( final String canonicalId, final String slug, final String title,
                           final PageType type, final String cluster, final List< String > clusters,
                           final List< String > tags, final String summary, final Instant updated,
                           final boolean derived ) {
        this( canonicalId, slug, title, type, cluster, clusters, tags, summary, updated, derived, List.of() );
    }

    /**
     * The tags this page is <em>found</em> by: frontmatter tags then inline tags, each normalised
     * ({@link InlineTagSyntax#normalise}), duplicates removed. Compare and count with this, never with {@link #tags()}.
     */
    public Set< String > findableTags() {
        final Set< String > out = new LinkedHashSet<>();
        for ( final String t : tags ) {
            addNormalised( out, t );
        }
        for ( final String t : inlineTags ) {
            addNormalised( out, t );
        }
        return out;
    }

    /** Every tag as written, frontmatter first, one entry per normalised identity (the first written form wins). */
    public List< String > writtenTags() {
        final java.util.Map< String, String > byKey = new java.util.LinkedHashMap<>();
        for ( final String t : tags ) {
            putWritten( byKey, t );
        }
        for ( final String t : inlineTags ) {
            putWritten( byKey, t );
        }
        return List.copyOf( byKey.values() );
    }

    private static void addNormalised( final Set< String > out, final String raw ) {
        final String n = raw == null ? "" : InlineTagSyntax.normalise( raw );
        if ( !n.isEmpty() ) {
            out.add( n );
        }
    }

    private static void putWritten( final java.util.Map< String, String > byKey, final String raw ) {
        final String n = raw == null ? "" : InlineTagSyntax.normalise( raw );
        if ( !n.isEmpty() ) {
            byKey.putIfAbsent( n, raw );
        }
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
     *  @param derived     whether the page was ingested from an external source
     */
    // The parameter list is the record's own component list minus `clusters`; PMD cannot see
    // that a record's canonical constructor already carries it. Dropping this overload would
    // force every single-cluster call site to spell out a redundant derived argument.
    // (Targets the -Pcomplexity-gate ruleset; the default bug ruleset reports it as unnecessary — keep it.)
    @SuppressWarnings( "PMD.ExcessiveParameterList" )
    public PageDescriptor( final String canonicalId, final String slug, final String title,
                           final PageType type, final String cluster, final List< String > tags,
                           final String summary, final Instant updated,
                           final boolean derived ) {
        this( canonicalId, slug, title, type, cluster, ClusterPath.memberships( cluster ),
              tags, summary, updated, derived );
    }
}
