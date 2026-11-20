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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.ClusterStatus;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the derived {@code cluster_status} block of the page payload ({@link PageReadHandler}): whether a
 * page's cluster has a declaring hub and how many members the caller may see. Split out of the read handler;
 * behaviour unchanged.
 */
final class ClusterStatusResolver {

    private static final Logger LOG = LogManager.getLogger( ClusterStatusResolver.class );

    private final RestServletBase servlet;

    ClusterStatusResolver( final RestServletBase servlet ) {
        this.servlet = servlet;
    }

    /**
     *  Builds the derived {@code cluster_status} block for a page, or {@code null} when the
     *  page names no cluster.
     *
     *  <p>Whether a cluster has a declaring hub is not in the page's own frontmatter, so the
     *  reader cannot compute it — the server resolves it here.</p>
     *
     *  <p>{@code hub_slug} is omitted when no hub declares the cluster (Gson drops nulls), so
     *  the boolean {@code hub_declared} carries that signal instead: it is always present and
     *  cannot vanish, and the client keys its "cluster not yet defined" badge off it rather
     *  than inferring meaning from an absent key.</p>
     *
     *  <p>Never throws: a structural index that is absent or still warming degrades to the
     *  declared path with no hub, which the UI renders as an undeclared cluster.</p>
     */
    JsonObject resolve( final HttpServletRequest request, final Map< String, Object > metadata ) {
        // Phase 5: `cluster:` is a scalar or a list. The FIRST entry is the primary and keeps
        // driving placement — breadcrumbs, sidebar, JSON-LD — so `path` never changes meaning.
        final List< String > memberships =
                ClusterPath.memberships( metadata == null ? null : metadata.get( "cluster" ) );
        if ( memberships.isEmpty() ) {
            return null;
        }
        final ClusterStatus status = resolveCluster( request, memberships.get( 0 ), true );

        final JsonObject out = new JsonObject();
        out.addProperty( "path", status.path() );
        out.addProperty( "parent", status.parent() );
        out.addProperty( "hub_slug", status.hubSlug() );
        out.addProperty( "hub_declared", status.hubSlug() != null );
        out.addProperty( "member_count", status.memberCount() );

        // Emitted only when there is genuinely more than one home, so a single-cluster page's
        // payload is byte-identical to what pre-Phase-5 clients already handle.
        if ( memberships.size() > 1 ) {
            final JsonArray all = new JsonArray();
            for ( final String path : memberships ) {
                final ClusterStatus each = resolveCluster( request, path, false );
                final JsonObject entry = new JsonObject();
                entry.addProperty( "path", each.path() );
                entry.addProperty( "hub_slug", each.hubSlug() );
                entry.addProperty( "hub_declared", each.hubSlug() != null );
                all.add( entry );
            }
            out.add( "memberships", all );
        }
        return out;
    }

    /**
     *  Resolves one cluster path against the structural index, degrading to an undeclared
     *  status when the index is unavailable — a warming or absent index must leave the page
     *  renderable rather than failing the whole payload.
     */
    private ClusterStatus resolveCluster( final HttpServletRequest request, final String cluster,
                                          final boolean countMembers ) {
        try {
            final StructuralIndexService idx = servlet.getSubsystems().pageGraph().structuralIndexService();
            final ClusterDetails details = idx == null ? null : idx.getCluster( cluster ).orElse( null );
            return ClusterStatus.of( cluster, details == null ? null : asSeenBy( request, details, countMembers ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "cluster_status: structural index unavailable for cluster '{}': {}",
                      cluster, e.getMessage() );
            return ClusterStatus.of( cluster, null );
        }
    }

    /**
     *  The cluster as the caller sees it: a hub they may not view is absent and, when {@code countMembers}, only the
     *  members they may view are counted (as on every other cluster count). Secondary memberships report no count,
     *  so they cost one check, for the hub.
     */
    private ClusterDetails asSeenBy( final HttpServletRequest request, final ClusterDetails details,
                                     final boolean countMembers ) {
        final List< String > names = new ArrayList<>();
        if ( details.hubPage() != null ) {
            names.add( details.hubPage().slug() );
        }
        if ( countMembers ) {
            details.articles().forEach( a -> names.add( a.slug() ) );
        }
        final java.util.Set< String > viewable = servlet.filterViewable( request, names );
        final PageDescriptor hub = details.hubPage() != null && viewable.contains( details.hubPage().slug() )
                ? details.hubPage() : null;
        final List< PageDescriptor > articles = countMembers
                ? details.articles().stream().filter( a -> viewable.contains( a.slug() ) ).toList()
                : details.articles();
        return new ClusterDetails( details.name(), hub, articles, details.tagDistribution(), details.updatedAt() );
    }
}
