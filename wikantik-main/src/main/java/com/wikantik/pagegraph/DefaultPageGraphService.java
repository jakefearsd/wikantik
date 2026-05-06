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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageGraphEdge;
import com.wikantik.api.pagegraph.PageGraphNode;
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.PageGraphSnapshot;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Permission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default {@link PageGraphService} — assembles a snapshot from
 * {@link ReferenceManager} (wikilink edges) and {@link StructuralIndexService}
 * (per-page metadata: canonical_id, type, cluster, tags). One node per real
 * wiki page; one edge per outbound wikilink whose target also exists.
 *
 * <p>Snapshots are cached for {@value #CACHE_TTL_SECONDS}s — the underlying
 * link maps are updated every time a page is saved, but rebuilding the full
 * snapshot on every request is wasteful at corpus scale. ACL redaction is
 * applied per-request so different viewers see different visible names.</p>
 */
public class DefaultPageGraphService implements PageGraphService {

    private static final Logger LOG = LogManager.getLogger( DefaultPageGraphService.class );
    private static final long CACHE_TTL_SECONDS = 60;

    private final StructuralIndexService structural;
    private final ReferenceManager refMgr;
    private final PageManager pageMgr;

    private volatile Engine engine;
    private volatile PageGraphSnapshot cachedSnapshot;
    private volatile Instant cacheTimestamp;

    public DefaultPageGraphService( final StructuralIndexService structural,
                                     final ReferenceManager refMgr,
                                     final PageManager pageMgr ) {
        this.structural = structural;
        this.refMgr = refMgr;
        this.pageMgr = pageMgr;
    }

    /** Inject the engine for ACL checks. Set after construction so the engine
     *  doesn't need to be ready when the factory wires this service. */
    public void setEngine( final Engine engine ) {
        this.engine = engine;
    }

    /** Drop the cached snapshot. Called by the engine when wikilink edges
     *  may have changed (page saved, deleted, references rebuilt). */
    public void invalidateCache() {
        this.cachedSnapshot = null;
        this.cacheTimestamp = null;
    }

    @Override
    public PageGraphSnapshot snapshot( final Session viewer ) {
        PageGraphSnapshot base = cachedSnapshot;
        final Instant now = Instant.now();
        if ( base == null || cacheTimestamp == null
                || now.isAfter( cacheTimestamp.plusSeconds( CACHE_TTL_SECONDS ) ) ) {
            base = buildUnredacted();
            cachedSnapshot = base;
            cacheTimestamp = now;
        }
        return redactForViewer( base, viewer );
    }

    private PageGraphSnapshot buildUnredacted() {
        final Set< String > known;
        try {
            known = refMgr.findCreated();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Page Graph: ReferenceManager.findCreated() failed; returning empty snapshot",
                    e );
            return emptySnapshot();
        }
        if ( known == null || known.isEmpty() ) {
            return emptySnapshot();
        }

        final Map< String, String > nameToId = new HashMap<>( known.size() * 2 );
        final Map< String, PageDescriptor > nameToDescriptor = new HashMap<>( known.size() * 2 );
        for ( final String name : known ) {
            final Optional< PageDescriptor > desc = lookupDescriptor( name );
            final String id = desc.map( PageDescriptor::canonicalId ).orElse( name );
            nameToId.put( name, id );
            desc.ifPresent( d -> nameToDescriptor.put( name, d ) );
        }

        // Collect edges (only between known pages) and tally degrees in one pass.
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

        final int hubThreshold = computeHubThreshold( known.size(), nameToId.values(), degrees );

        final List< PageGraphNode > nodes = new ArrayList<>( known.size() );
        // Iterate in a stable order so cached snapshots are reproducible.
        for ( final String name : new TreeSet<>( known ) ) {
            final String id = nameToId.get( name );
            final int[] deg = degrees.getOrDefault( id, new int[2] );
            final PageDescriptor desc = nameToDescriptor.get( name );
            final String type = ( desc != null && desc.type() != null )
                    ? desc.type().name().toLowerCase()
                    : null;
            final String cluster = ( desc != null ) ? desc.cluster() : null;
            final List< String > tags = ( desc != null ) ? desc.tags() : List.of();
            final String role = classifyRole( deg[0], deg[1], hubThreshold );
            nodes.add( new PageGraphNode( id, name, type, role, name,
                    deg[0], deg[1], false, cluster, tags ) );
        }

        return new PageGraphSnapshot(
                Instant.now().toString(),
                nodes.size(), edges.size(),
                hubThreshold, nodes, edges );
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

    private PageGraphSnapshot emptySnapshot() {
        return new PageGraphSnapshot( Instant.now().toString(), 0, 0, 10,
                List.of(), List.of() );
    }

    private PageGraphSnapshot redactForViewer( final PageGraphSnapshot base, final Session viewer ) {
        if ( engine == null ) {
            return base;
        }
        final AuthorizationManager authMgr;
        try {
            authMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Page Graph: AuthorizationManager unavailable; skipping ACL redaction: {}",
                    e.getMessage() );
            return base;
        }

        final List< PageGraphNode > redacted = new ArrayList<>( base.nodes().size() );
        for ( final PageGraphNode node : base.nodes() ) {
            if ( node.sourcePage() != null && !isViewable( node.sourcePage(), viewer, authMgr ) ) {
                redacted.add( new PageGraphNode( node.id(), null, null, "restricted",
                        null, node.degreeIn(), node.degreeOut(), true,
                        null, List.of() ) );
            } else {
                redacted.add( node );
            }
        }
        return new PageGraphSnapshot( base.generatedAt(), base.nodeCount(), base.edgeCount(),
                base.hubDegreeThreshold(), redacted, base.edges() );
    }

    private boolean isViewable( final String pageName, final Session viewer,
                                  final AuthorizationManager authMgr ) {
        // Match the Knowledge Graph snapshot's policy (D27): a null viewer
        // means anonymous public access; the ACL check below decides whether
        // the page is visible to the unauthenticated principal.
        if ( authMgr == null ) {
            return true;
        }
        final Page page;
        try {
            page = pageMgr != null ? pageMgr.getPage( pageName ) : null;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Page Graph: PageManager.getPage({}) failed: {}", pageName, e.getMessage() );
            return false;
        }
        final Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, "view" )
                : new PagePermission( engine.getApplicationName() + ":" + pageName, "view" );
        return authMgr.checkPermission( viewer, perm );
    }
}
