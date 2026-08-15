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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralConflict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable builder for a {@link StructuralProjection}. Populated during rebuild
 * by {@link DefaultStructuralIndexService}, then frozen into an immutable snapshot
 * via {@link #build()}.
 */
public final class StructuralProjectionBuilder {

    private final Map< String, PageDescriptor > byCanonicalId = new LinkedHashMap<>();
    private final Map< String, String >         slugToCanonicalId = new LinkedHashMap<>();
    private final Map< String, List< PageDescriptor > > byCluster = new LinkedHashMap<>();
    private final Map< String, PageDescriptor > hubByCluster = new HashMap<>();
    private final Map< String, List< PageDescriptor > > byTag = new LinkedHashMap<>();
    private final Map< PageType, List< PageDescriptor > > byType = new EnumMap<>( PageType.class );
    /** Recorded as hubs collide during {@link #addPage}; the projection cannot recover them later. */
    private final List< StructuralConflict > duplicateDeclarations = new ArrayList<>();

    public StructuralProjectionBuilder addPage( final PageDescriptor page ) {
        byCanonicalId.put( page.canonicalId(), page );
        slugToCanonicalId.put( page.slug(), page.canonicalId() );

        // Phase 5: a page is indexed under every cluster it names. Declaration stays singular —
        // only the primary can declare — so a hub that also joins another cluster is a member
        // there, never its hub.
        for ( final String membership : page.clusters() ) {
            byCluster.computeIfAbsent( membership, k -> new ArrayList<>() ).add( page );
        }

        if ( page.cluster() != null ) {
            if ( page.type() == PageType.HUB ) {
                // Two hubs declaring one cluster is a defect (Phase 2 blocks it at save
                // time, and the rebuild reports it as DUPLICATE_CLUSTER_DECLARATION). Until
                // then the winner must at least be STABLE: pages arrive in unsorted
                // filesystem order from listFiles(), so a plain put() made list_clusters
                // report a different hub run to run. Lowest slug wins — deterministic and
                // predictable to a human reading the conflict report.
                hubByCluster.merge( page.cluster(), page, ( existing, candidate ) -> {
                    final PageDescriptor winner =
                            candidate.slug().compareTo( existing.slug() ) < 0 ? candidate : existing;
                    final PageDescriptor loser = winner == candidate ? existing : candidate;
                    duplicateDeclarations.add( new StructuralConflict(
                            loser.slug(), loser.canonicalId(),
                            StructuralConflict.Kind.DUPLICATE_CLUSTER_DECLARATION,
                            "cluster '" + page.cluster() + "' is already declared by '" + winner.slug()
                                    + "'; exactly one hub may declare a cluster" ) );
                    return winner;
                } );
            }
        }

        for ( final String tag : page.tags() ) {
            byTag.computeIfAbsent( tag, k -> new ArrayList<>() ).add( page );
        }

        byType.computeIfAbsent( page.type(), k -> new ArrayList<>() ).add( page );
        return this;
    }

    public StructuralProjection build() {
        return new StructuralProjection(
                byCanonicalId,
                slugToCanonicalId,
                byCluster,
                hubByCluster,
                byTag,
                byType,
                duplicateDeclarations,
                Instant.now() );
    }
}
