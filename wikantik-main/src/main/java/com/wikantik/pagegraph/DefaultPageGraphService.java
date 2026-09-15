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

    public DefaultPageGraphService( final StructuralIndexService structural,
                                     final ReferenceManager refMgr,
                                     final PageManager pageMgr ) {
        this.structural = structural;
        this.refMgr = refMgr;
        this.pageMgr = pageMgr;
        this.snapshotBuilder = new PageGraphSnapshotBuilder( structural, refMgr );
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
        return authMgr.isPermitted( viewer, perm );
    }
}
