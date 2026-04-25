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
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent-facing, machine-queryable projection of wiki structure. Maintained
 * incrementally via {@code WikiPageEvent} subscriptions and rebuilt from
 * frontmatter on startup. Callers should treat every result as potentially
 * stale by {@link IndexHealth#lagSeconds} seconds — the service will surface
 * lag rather than return wrong data.
 *
 * <p>Phase 1: observe-only. Pages without {@code canonical_id} frontmatter are
 * still indexed (their ID is synthesised and flagged in {@code unclaimedCanonicalIds}).
 * Phase 4 tightens this into a hard save-time requirement — see
 * {@code docs/wikantik-pages/StructuralSpineDesign.md}.</p>
 */
public interface StructuralIndexService {

    List< ClusterSummary > listClusters();

    Optional< ClusterDetails > getCluster( String name );

    List< TagSummary > listTags( int minPages );

    List< PageDescriptor > listPagesByType( PageType type );

    List< PageDescriptor > listPagesByFilter( StructuralFilter filter );

    Sitemap sitemap();

    Optional< PageDescriptor > getByCanonicalId( String canonicalId );

    Optional< String > resolveSlugFromCanonicalId( String canonicalId );

    Optional< String > resolveCanonicalIdFromSlug( String slug );

    /* --------------------------------------------------- Relation graph (Phase 2) */

    /** Outgoing relations from {@code canonicalId}, optionally filtered by type. */
    List< RelationEdge > outgoingRelations( String canonicalId, Optional< RelationType > typeFilter );

    /** Incoming relations into {@code canonicalId}, optionally filtered by type. */
    List< RelationEdge > incomingRelations( String canonicalId, Optional< RelationType > typeFilter );

    /**
     * Bounded BFS over the typed-relation graph rooted at {@code canonicalId}.
     * Direction, type filter, and depth cap come from the {@link TraversalSpec}.
     * Returns edges in BFS order; depth field on each edge reflects how many
     * hops from the root it was discovered.
     */
    List< RelationEdge > traverse( String canonicalId, TraversalSpec spec );

    /* --------------------------------------------------- Lifecycle */

    /** Rebuilds the projection from the authoritative frontmatter source. Blocks until complete. */
    void rebuild();

    IndexHealth health();

    /** Snapshot of the current projection used by metrics and admin UIs. Stable for the duration of the call. */
    StructuralProjectionSnapshot snapshot();

    /** Immutable snapshot — exposed for observability but not for mutation. */
    interface StructuralProjectionSnapshot {
        int pageCount();
        int clusterCount();
        int tagCount();
        Instant generatedAt();
    }
}
