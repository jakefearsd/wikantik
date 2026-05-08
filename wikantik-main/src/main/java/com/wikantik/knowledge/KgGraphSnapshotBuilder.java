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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.*;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.page.subsystem.PageSubsystemBridge;

import java.time.Instant;
import java.util.*;

/**
 * Builds and caches unredacted {@link GraphSnapshot} objects, then redacts nodes
 * for a given viewer based on per-page ACLs.
 *
 * <p>Factored out of {@link DefaultKnowledgeGraphService} as part of Phase 11
 * Ckpt 6 god-class decomposition. Holds the per-tier snapshot cache.
 */
class KgGraphSnapshotBuilder {

    private static final long CACHE_TTL_SECONDS = 60;

    private final KgNodeRepository nodes;
    private final KgEdgeRepository edges;
    private Engine engine;

    private final Map< Tier, GraphSnapshot > cachedByTier  = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map< Tier, Instant >       cacheTsByTier = new java.util.concurrent.ConcurrentHashMap<>();

    KgGraphSnapshotBuilder( final KgNodeRepository nodes,
                             final KgEdgeRepository edges,
                             final Engine engine ) {
        this.nodes  = nodes;
        this.edges  = edges;
        this.engine = engine;
    }

    void setEngine( final Engine engine ) {
        this.engine = engine;
    }

    void invalidateCache() {
        cachedByTier.clear();
        cacheTsByTier.clear();
    }

    GraphSnapshot snapshotFor( final Session viewer, final Tier minTier ) {
        GraphSnapshot base = cachedByTier.get( minTier );
        final Instant ts = cacheTsByTier.get( minTier );
        final Instant now = Instant.now();
        if ( base == null || ts == null || now.isAfter( ts.plusSeconds( CACHE_TTL_SECONDS ) ) ) {
            base = buildUnredactedSnapshot( minTier );
            cachedByTier.put( minTier, base );
            cacheTsByTier.put( minTier, now );
        }
        return redactForViewer( base, viewer );
    }

    private GraphSnapshot buildUnredactedSnapshot( final Tier minTier ) {
        final List< KgNode > allNodes = nodes.getAllNodes( minTier );
        final List< KgEdge > allEdges = edges.getAllEdges( minTier );

        final Map< UUID, int[] > degrees = new HashMap<>();
        for ( final KgEdge edge : allEdges ) {
            degrees.computeIfAbsent( edge.sourceId(), k -> new int[2] )[1]++;
            degrees.computeIfAbsent( edge.targetId(), k -> new int[2] )[0]++;
        }

        final int hubThreshold = computeHubThreshold( allNodes, degrees );

        final List< SnapshotNode > snapshotNodes = new ArrayList<>( allNodes.size() );
        for ( final KgNode node : allNodes ) {
            final int[] deg = degrees.getOrDefault( node.id(), new int[2] );
            final String role = GraphRoleClassifier.classify( node, deg[0], deg[1], hubThreshold, false );
            snapshotNodes.add( new SnapshotNode(
                    node.id(), node.name(), node.nodeType(), role,
                    node.provenance(), node.sourcePage(), deg[0], deg[1], false,
                    propString( node, "cluster" ),
                    propStringList( node, "tags" ),
                    propString( node, "status" ),
                    node.tier() ) );
        }

        final List< SnapshotEdge > snapshotEdges = allEdges.stream()
                .map( e -> new SnapshotEdge( e.id(), e.sourceId(), e.targetId(),
                        e.relationshipType(), e.provenance() ) )
                .toList();

        return new GraphSnapshot( Instant.now().toString(), snapshotNodes.size(), snapshotEdges.size(),
                hubThreshold, snapshotNodes, snapshotEdges );
    }

    private int computeHubThreshold( final List< KgNode > nodeList,
                                      final Map< UUID, int[] > degrees ) {
        if ( nodeList.isEmpty() ) return 10;
        final int[] totals = nodeList.stream()
                .mapToInt( n -> {
                    final int[] d = degrees.getOrDefault( n.id(), new int[2] );
                    return d[0] + d[1];
                } )
                .sorted()
                .toArray();
        final int p95Index = (int) Math.ceil( totals.length * 0.95 ) - 1;
        final int p95 = totals[Math.max( 0, p95Index )];
        return Math.max( 10, p95 );
    }

    private GraphSnapshot redactForViewer( final GraphSnapshot base, final Session viewer ) {
        if ( engine == null ) {
            return base;
        }
        final AuthorizationManager authMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        final PageManager pageMgr = PageSubsystemBridge.fromLegacyEngine( engine ).pages();

        final List< SnapshotNode > redacted = new ArrayList<>( base.nodes().size() );
        for ( final SnapshotNode node : base.nodes() ) {
            if ( node.sourcePage() != null && !isViewable( node.sourcePage(), viewer, authMgr, pageMgr ) ) {
                redacted.add( new SnapshotNode(
                        node.id(), null, null, "restricted", null, null,
                        node.degreeIn(), node.degreeOut(), true,
                        null, List.of(), null, null ) );
            } else {
                redacted.add( node );
            }
        }
        return new GraphSnapshot( base.generatedAt(), base.nodeCount(), base.edgeCount(),
                base.hubDegreeThreshold(), redacted, base.edges() );
    }

    private boolean isViewable( final String pageName, final Session viewer,
                                  final AuthorizationManager authMgr, final PageManager pageMgr ) {
        if ( viewer == null || authMgr == null || pageMgr == null ) {
            return false;
        }
        final Page page = pageMgr.getPage( pageName );
        final java.security.Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, "view" )
                : new PagePermission( engine.getApplicationName() + ":" + pageName, "view" );
        return authMgr.checkPermission( viewer, perm );
    }

    private static String propString( final KgNode node, final String key ) {
        if ( node.properties() == null ) return null;
        final Object v = node.properties().get( key );
        return ( v == null ) ? null : v.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > propStringList( final KgNode node, final String key ) {
        if ( node.properties() == null ) return List.of();
        final Object v = node.properties().get( key );
        if ( v instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object item : list ) {
                if ( item instanceof String s ) out.add( s );
            }
            return List.copyOf( out );
        }
        return List.of();
    }
}
