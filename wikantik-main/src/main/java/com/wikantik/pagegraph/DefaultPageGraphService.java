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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PageGraphSnapshotBuilder snapshotBuilder;

    private volatile Engine engine;
    private volatile PageGraphSnapshot cachedSnapshot;
    private volatile Instant cacheTimestamp;
    private volatile long cachedSalt;
    private volatile java.util.function.LongSupplier saltSource = new java.security.SecureRandom()::nextLong;

    public DefaultPageGraphService( final StructuralIndexService structural,
                                     final ReferenceManager refMgr,
                                     final PageManager pageMgr ) {
        this.structural = structural;
        this.refMgr = refMgr;
        this.pageMgr = pageMgr;
        this.snapshotBuilder = new PageGraphSnapshotBuilder( structural, refMgr );
    }

    /** Test seam: fixes the salt that orders redacted nodes (otherwise a fresh random one per cached snapshot). */
    void setRedactionSaltForTesting( final long salt ) {
        this.saltSource = () -> salt;
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
            cachedSalt = saltSource.getAsLong();
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
        return snapshotBuilder.build( known );
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

        final List< PageGraphNode > open = new ArrayList<>( base.nodes().size() );
        final List< PageGraphNode > hidden = new ArrayList<>();
        for ( final PageGraphNode node : base.nodes() ) {
            if ( node.sourcePage() != null && !isViewable( node.sourcePage(), viewer, authMgr ) ) {
                hidden.add( node );
            } else {
                open.add( node );
            }
        }
        if ( hidden.isEmpty() ) {
            return base;
        }
        // The base lists nodes in name order, so a redacted node left in place would bracket its name
        // alphabetically. Redacted nodes go last, in an order drawn from a per-snapshot salt, and every id
        // that could be derived from a name (the page's own name, an attachment's "Page/file", a ULID that
        // dates the page) is replaced by an opaque one. ".." is refused in page names and an attachment name
        // has exactly one period, so no real node id can equal an opaque one.
        final long salt = cachedSalt;
        hidden.sort( java.util.Comparator.comparingLong( n -> new java.util.SplittableRandom( salt ^ n.id().hashCode() ).nextLong() ) );
        final Map< String, String > opaqueIds = new HashMap<>();
        final List< PageGraphNode > nodes = new ArrayList<>( open );
        for ( final PageGraphNode node : hidden ) {
            final String id = "restricted.." + opaqueIds.size();
            opaqueIds.put( node.id(), id );
            nodes.add( new PageGraphNode( id, null, null, "restricted",
                    null, node.degreeIn(), node.degreeOut(), true,
                    null, List.of() ) );
        }
        return new PageGraphSnapshot( base.generatedAt(), base.nodeCount(), base.edgeCount(),
                base.hubDegreeThreshold(), nodes, remapped( base.edges(), opaqueIds ) );
    }

    /** Re-points edges at the opaque ids; an edge touching a redacted node gets a fresh id and is listed last. */
    private static List< PageGraphEdge > remapped( final List< PageGraphEdge > edges, final Map< String, String > ids ) {
        final List< PageGraphEdge > plain = new ArrayList<>( edges.size() );
        final List< PageGraphEdge > touching = new ArrayList<>();
        for ( final PageGraphEdge e : edges ) {
            final String source = ids.getOrDefault( e.source(), e.source() );
            final String target = ids.getOrDefault( e.target(), e.target() );
            if ( ids.containsKey( e.source() ) || ids.containsKey( e.target() ) ) {
                touching.add( new PageGraphEdge( "pl-" + Integer.toHexString( ( source + "->" + target ).hashCode() ),
                        source, target, e.relationshipType(), e.provenance() ) );
            } else {
                plain.add( e );
            }
        }
        touching.sort( java.util.Comparator.comparing( PageGraphEdge::source ).thenComparing( PageGraphEdge::target ) );
        plain.addAll( touching );
        return plain;
    }

    private boolean isViewable( final String pageName, final Session viewer,
                                  final AuthorizationManager authMgr ) {
        // Policy (D27): a null viewer
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
        return authMgr.isPermitted( viewer, perm );
    }
}
