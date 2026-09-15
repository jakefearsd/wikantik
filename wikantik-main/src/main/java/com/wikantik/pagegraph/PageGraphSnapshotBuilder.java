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
package com.wikantik.pagegraph;

import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageGraphEdge;
import com.wikantik.api.pagegraph.PageGraphNode;
import com.wikantik.api.pagegraph.PageGraphSnapshot;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles the unredacted Page Graph snapshot from {@link ReferenceManager} wikilink
 * data and {@link StructuralIndexService} per-page metadata (canonical_id, type,
 * cluster, tags). Extracted from {@link DefaultPageGraphService} so that class's
 * structural-complexity metrics (WMC/ATFD) stay within the design-quality gate — this
 * collaborator owns the maps/edges/nodes assembly pipeline exclusively.
 */
final class PageGraphSnapshotBuilder {

    private static final Logger LOG = LogManager.getLogger( PageGraphSnapshotBuilder.class );

    private final StructuralIndexService structural;
    private final ReferenceManager refMgr;

    PageGraphSnapshotBuilder( final StructuralIndexService structural, final ReferenceManager refMgr ) {
        this.structural = structural;
        this.refMgr = refMgr;
    }

    /** Builds a snapshot from the given set of known (existing) page names. */
    PageGraphSnapshot build( final Set< String > known ) {
        final NameMaps nameMaps = buildNameMaps( known );
        final EdgeCollection edgeCollection = collectEdgesAndDegrees( known, nameMaps.nameToId() );
        final int hubThreshold = computeHubThreshold(
                known.size(), nameMaps.nameToId().values(), edgeCollection.degrees() );
        final List< PageGraphNode > nodes = buildNodes( known, nameMaps, edgeCollection.degrees(), hubThreshold );

        return new PageGraphSnapshot(
                Instant.now().toString(),
                nodes.size(), edgeCollection.edges().size(),
                hubThreshold, nodes, edgeCollection.edges() );
    }

    /** Name -&gt; canonical id / descriptor lookups, resolved once per {@link #build} call. */
    private record NameMaps( Map< String, String > nameToId, Map< String, PageDescriptor > nameToDescriptor ) {}

    private NameMaps buildNameMaps( final Set< String > known ) {
        final Map< String, String > nameToId = new HashMap<>( known.size() * 2 );
        final Map< String, PageDescriptor > nameToDescriptor = new HashMap<>( known.size() * 2 );
        for ( final String name : known ) {
            final Optional< PageDescriptor > desc = lookupDescriptor( name );
            final String id = desc.map( PageDescriptor::canonicalId ).orElse( name );
            nameToId.put( name, id );
            desc.ifPresent( d -> nameToDescriptor.put( name, d ) );
        }
        return new NameMaps( nameToId, nameToDescriptor );
    }

    /** Page-link edges plus the per-node in/out degree tally accumulated alongside them. */
    private record EdgeCollection( List< PageGraphEdge > edges, Map< String, int[] > degrees ) {}

    /** Collects edges (only between known pages) and tallies degrees in one pass. */
    private EdgeCollection collectEdgesAndDegrees( final Set< String > known, final Map< String, String > nameToId ) {
        final Map< String, int[] > degrees = new HashMap<>( known.size() * 2 );
        final List< PageGraphEdge > edges = new ArrayList<>();
        final Set< String > seenEdgeKeys = new HashSet<>();
        for ( final String src : known ) {
            final Collection< String > outbound;
            try {
                outbound = refMgr.findRefersTo( src );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Page Graph: refersTo({}) failed; skipping its outbound edges",
                        src, e );
                continue;
            }
            if ( outbound == null ) continue;
            final String srcId = nameToId.get( src );
            for ( final String tgt : outbound ) {
                if ( tgt == null || tgt.equals( src ) || !nameToId.containsKey( tgt ) ) {
                    continue;
                }
                final String tgtId = nameToId.get( tgt );
                final String key = srcId + "->" + tgtId;
                if ( !seenEdgeKeys.add( key ) ) {
                    continue;
                }
                edges.add( new PageGraphEdge(
                        "pl-" + Integer.toHexString( key.hashCode() ),
                        srcId, tgtId, "page-link", "HUMAN_AUTHORED" ) );
                degrees.computeIfAbsent( srcId, k -> new int[2] )[1]++;
                degrees.computeIfAbsent( tgtId, k -> new int[2] )[0]++;
            }
        }
        return new EdgeCollection( edges, degrees );
    }

    /** Builds the node list in a stable (sorted) order so cached snapshots are reproducible. */
    private List< PageGraphNode > buildNodes( final Set< String > known, final NameMaps nameMaps,
                                               final Map< String, int[] > degrees, final int hubThreshold ) {
        final List< PageGraphNode > nodes = new ArrayList<>( known.size() );
        for ( final String name : new TreeSet<>( known ) ) {
            final String id = nameMaps.nameToId().get( name );
            final int[] deg = degrees.getOrDefault( id, new int[2] );
            final PageDescriptor desc = nameMaps.nameToDescriptor().get( name );
            final String type = ( desc != null && desc.type() != null )
                    ? desc.type().name().toLowerCase( Locale.ROOT )
                    : null;
            final String cluster = ( desc != null ) ? desc.cluster() : null;
            final List< String > tags = ( desc != null ) ? desc.tags() : List.of();
            final String role = classifyRole( deg[0], deg[1], hubThreshold );
            nodes.add( new PageGraphNode( id, name, type, role, name,
                    deg[0], deg[1], false, cluster, tags ) );
        }
        return nodes;
    }

    private Optional< PageDescriptor > lookupDescriptor( final String pageName ) {
        if ( structural == null ) return Optional.empty();
        try {
            // Pages are indexed by canonical_id, not slug. Resolve the slug ->
            // canonical_id via the structural index (which understands the
            // page name <-> slug mapping).
            return structural.resolveCanonicalIdFromSlug( pageName )
                    .flatMap( structural::getByCanonicalId );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Page Graph: structural lookup for '{}' failed: {}",
                    pageName, e.getMessage() );
            return Optional.empty();
        }
    }

    private static String classifyRole( final int degIn, final int degOut, final int hubThreshold ) {
        if ( degIn + degOut == 0 ) return "orphan";
        if ( degIn + degOut >= hubThreshold ) return "hub";
        return "normal";
    }

    private static int computeHubThreshold( final int nodeCount,
                                              final Collection< String > nodeIds,
                                              final Map< String, int[] > degrees ) {
        if ( nodeCount == 0 ) return 10;
        final int[] totals = nodeIds.stream()
                .mapToInt( id -> {
                    final int[] d = degrees.getOrDefault( id, new int[2] );
                    return d[0] + d[1];
                } )
                .sorted()
                .toArray();
        final int p95Index = (int) Math.ceil( totals.length * 0.95 ) - 1;
        final int p95 = totals[Math.max( 0, p95Index )];
        return Math.max( 10, p95 );
    }
}
