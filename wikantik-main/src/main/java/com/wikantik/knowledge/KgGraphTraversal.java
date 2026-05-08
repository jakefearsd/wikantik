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
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.*;

/**
 * BFS graph-traversal strategies over the Knowledge Graph.
 *
 * <p>Factored out of {@link DefaultKnowledgeGraphService} as part of Phase 11
 * Ckpt 6 god-class decomposition. Owns typed-edge BFS ({@link #traverse}) and
 * co-mention BFS ({@link #traverseByCoMention}).
 */
class KgGraphTraversal {

    private static final Logger LOG = LogManager.getLogger( KgGraphTraversal.class );

    /** Default provenance filter when none is provided. */
    private static final Set< Provenance > DEFAULT_PROVENANCE_FILTER =
            Set.of( Provenance.HUMAN_AUTHORED, Provenance.AI_REVIEWED );

    private final KgNodeRepository nodes;
    private final KgEdgeRepository edges;
    private final MentionIndex     mentionIndex;

    KgGraphTraversal( final KgNodeRepository nodes,
                       final KgEdgeRepository edges,
                       final MentionIndex mentionIndex ) {
        this.nodes        = nodes;
        this.edges        = edges;
        this.mentionIndex = mentionIndex;
    }

    /**
     * BFS traversal following typed edges in the given direction up to
     * {@code maxDepth} hops.
     */
    TraversalResult traverse( final String startNodeName, final String direction,
                               final Set< String > relationshipTypes, final int maxDepth,
                               final Set< Provenance > provenanceFilter ) {
        final KgNode startNode = nodes.getNodeByName( startNodeName );
        if ( startNode == null ) {
            return new TraversalResult( List.of(), List.of() );
        }

        final Set< Provenance > effectiveProvenance =
                ( provenanceFilter == null || provenanceFilter.isEmpty() )
                        ? DEFAULT_PROVENANCE_FILTER
                        : provenanceFilter;

        final Map< UUID, KgNode > visited = new LinkedHashMap<>();
        final List< KgEdge > collectedEdges = new ArrayList<>();
        final Queue< UUID > queue = new ArrayDeque<>();
        final Map< UUID, Integer > depthMap = new HashMap<>();

        visited.put( startNode.id(), startNode );
        queue.add( startNode.id() );
        depthMap.put( startNode.id(), 0 );

        while ( !queue.isEmpty() ) {
            final UUID currentId = queue.poll();
            final int currentDepth = depthMap.get( currentId );

            if ( currentDepth >= maxDepth ) {
                continue;
            }

            final List< KgEdge > nodeEdges = edges.getEdgesForNode( currentId, direction );
            for ( final KgEdge edge : nodeEdges ) {
                if ( !effectiveProvenance.contains( edge.provenance() ) ) {
                    continue;
                }
                if ( relationshipTypes != null && !relationshipTypes.isEmpty()
                        && !relationshipTypes.contains( edge.relationshipType() ) ) {
                    continue;
                }

                collectedEdges.add( edge );

                final UUID neighborId = edge.sourceId().equals( currentId )
                        ? edge.targetId()
                        : edge.sourceId();

                if ( !visited.containsKey( neighborId ) ) {
                    final KgNode neighbor = nodes.getNode( neighborId );
                    if ( neighbor != null ) {
                        visited.put( neighborId, neighbor );
                        queue.add( neighborId );
                        depthMap.put( neighborId, currentDepth + 1 );
                    }
                }
            }
        }

        return new TraversalResult( new ArrayList<>( visited.values() ), collectedEdges );
    }

    /**
     * BFS traversal following co-mention edges synthesised from the
     * {@link MentionIndex}, tier-filtered by {@code minTier}.
     */
    TraversalResult traverseByCoMention( final String startNodeName, final int maxDepth,
                                          final int minSharedChunks, final Tier minTier ) {
        if ( mentionIndex == null ) {
            LOG.warn( "traverseByCoMention called but MentionIndex is not configured" );
            return new TraversalResult( List.of(), List.of() );
        }
        final KgNode startNode = nodes.getNodeByName( startNodeName );
        if ( startNode == null || !minTier.includes( startNode.tier() ) ) {
            return new TraversalResult( List.of(), List.of() );
        }
        final int effectiveMin = Math.max( 1, minSharedChunks );

        final Map< UUID, KgNode > visited = new LinkedHashMap<>();
        final List< KgEdge > collectedEdges = new ArrayList<>();
        final Queue< UUID > queue = new ArrayDeque<>();
        final Map< UUID, Integer > depthMap = new HashMap<>();

        visited.put( startNode.id(), startNode );
        queue.add( startNode.id() );
        depthMap.put( startNode.id(), 0 );

        while ( !queue.isEmpty() ) {
            final UUID currentId = queue.poll();
            final int currentDepth = depthMap.get( currentId );
            if ( currentDepth >= maxDepth ) continue;

            final Map< UUID, Integer > neighbors = mentionIndex.getCoMentionCounts( currentId );
            for ( final Map.Entry< UUID, Integer > e : neighbors.entrySet() ) {
                if ( e.getValue() < effectiveMin ) continue;
                final UUID neighborId = e.getKey();
                final int shared = e.getValue();

                if ( !visited.containsKey( neighborId ) ) {
                    final KgNode neighbor = nodes.getNode( neighborId );
                    if ( neighbor == null || !minTier.includes( neighbor.tier() ) ) {
                        continue;
                    }
                    visited.put( neighborId, neighbor );
                    queue.add( neighborId );
                    depthMap.put( neighborId, currentDepth + 1 );
                }

                collectedEdges.add( new KgEdge(
                    UUID.randomUUID(),
                    currentId, neighborId,
                    "co-mentions",
                    Provenance.AI_INFERRED,
                    Map.of( "sharedChunks", shared ),
                    Instant.now(),
                    Instant.now(),
                    "human",
                    null ) );
            }
        }

        return new TraversalResult( new ArrayList<>( visited.values() ), collectedEdges );
    }
}
