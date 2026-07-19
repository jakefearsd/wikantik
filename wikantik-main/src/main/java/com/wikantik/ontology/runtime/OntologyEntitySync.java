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
package com.wikantik.ontology.runtime;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.EntityProjector;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.jena.rdf.model.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Event-incremental ENTITY sync — the Knowledge Graph analog of {@link OntologyPageSync}.
 *
 * <p>KG writes mark entity ids dirty via {@link #mark}; a single daemon thread drains the
 * pending sets {@code coalesceMs} after the first mark, so a judge batch touching one
 * entity N times re-projects it once. The drain reads CURRENT database state (never
 * event-time snapshots — that is what makes coalescing safe), applies the same
 * public/restricted ACL gate as full rebuilds (node public iff its source page is
 * anonymously viewable or absent; an edge additionally needs a public target), and writes
 * through {@link OntologyModelManager#replaceNamedGraph}/{@code removeNamedGraph} to keep
 * the snapshot-invalidation contract. Per-entity failures are logged and skipped — the
 * nightly full rebuild reconciles misses. See
 * {@code docs/superpowers/specs/2026-07-19-kg-change-events-design.md}.</p>
 */
public final class OntologyEntitySync implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( OntologyEntitySync.class );

    private final OntologyModelManager manager;
    private final KgNodeRepository nodes;
    private final KgEdgeRepository edges;
    private final PageCanonicalIdsDao pageDao;
    private final Predicate< String > isPublic;
    private final long coalesceMs;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final Set< UUID > pendingTouched = new HashSet<>();
    private final Set< UUID > pendingRemoved = new HashSet<>();
    private boolean drainScheduled;

    public OntologyEntitySync( final OntologyModelManager manager, final KgNodeRepository nodes,
                               final KgEdgeRepository edges, final PageCanonicalIdsDao pageDao,
                               final Predicate< String > isPublic, final long coalesceMs ) {
        this( manager, nodes, edges, pageDao, isPublic, coalesceMs, defaultScheduler() );
    }

    OntologyEntitySync( final OntologyModelManager manager, final KgNodeRepository nodes,
                        final KgEdgeRepository edges, final PageCanonicalIdsDao pageDao,
                        final Predicate< String > isPublic, final long coalesceMs,
                        final ScheduledExecutorService scheduler ) {
        this.manager = manager;
        this.nodes = nodes;
        this.edges = edges;
        this.pageDao = pageDao;
        this.isPublic = isPublic;
        this.coalesceMs = coalesceMs;
        this.scheduler = scheduler;
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-ontology-entity-sync" );
            t.setDaemon( true );
            return t;
        } );
    }

    /** Marks entities dirty; O(1) on the caller's thread. Null sets are treated as empty. */
    public void mark( final Set< UUID > touched, final Set< UUID > removed ) {
        synchronized ( lock ) {
            if ( touched != null ) {
                pendingTouched.addAll( touched );
            }
            if ( removed != null ) {
                pendingRemoved.addAll( removed );
            }
            if ( !drainScheduled && !( pendingTouched.isEmpty() && pendingRemoved.isEmpty() ) ) {
                try {
                    scheduler.schedule( this::drainNow, coalesceMs, TimeUnit.MILLISECONDS );
                    drainScheduled = true;
                } catch ( final RejectedExecutionException e ) {
                    LOG.warn( "entity-sync: drain rejected (executor shutting down?): {}", e.getMessage() );
                }
            }
        }
    }

    /** One drain, synchronously. Scheduled-task body; package-private for deterministic tests. */
    void drainNow() {
        final Set< UUID > touched;
        final Set< UUID > removed;
        synchronized ( lock ) {
            touched = new HashSet<>( pendingTouched );
            removed = new HashSet<>( pendingRemoved );
            pendingTouched.clear();
            pendingRemoved.clear();
            drainScheduled = false;
        }
        for ( final UUID id : removed ) {
            try {
                manager.removeNamedGraph( Iris.entity( id ) );
            } catch ( final RuntimeException e ) {
                LOG.warn( "entity-sync: failed to remove graph for {} (nightly rebuild reconciles): {}",
                    id, e.getMessage() );
            }
        }
        touched.removeAll( removed );
        for ( final UUID id : touched ) {
            try {
                projectOne( id );
            } catch ( final RuntimeException e ) {
                LOG.warn( "entity-sync: failed to re-project {} (nightly rebuild reconciles): {}",
                    id, e.getMessage() );
            }
        }
    }

    /** Projects one entity from current DB state, or removes its graph if gone/restricted. */
    private void projectOne( final UUID id ) {
        final KgNode node = nodes.getNode( id );
        if ( node == null || ( node.sourcePage() != null && !isPublic.test( node.sourcePage() ) ) ) {
            manager.removeNamedGraph( Iris.entity( id ) );
            return;
        }
        final List< KgEdge > outgoing = edges.getEdgesForNode( id, "outbound" );
        final List< KgEdge > publicEdges = new ArrayList<>( outgoing.size() );
        for ( final KgEdge edge : outgoing ) {
            final KgNode target = nodes.getNode( edge.targetId() );
            if ( target != null
                    && ( target.sourcePage() == null || isPublic.test( target.sourcePage() ) ) ) {
                publicEdges.add( edge );
            }
        }
        // The resolver below is only ever invoked for slugs already filtered to ACL-public
        // nodes/targets by the isPublic checks above — there is no separate public-only
        // restriction on pageDao itself (unlike the rebuild's resolver map, which is built
        // from a public-only page set); this method relies on that upstream gate instead.
        final Model graph = EntityProjector.project( node, publicEdges,
            slug -> pageDao.findBySlug( slug )
                .map( PageCanonicalIdsDao.Row::canonicalId )
                .orElse( null ) );
        manager.replaceNamedGraph( Iris.entity( id ), graph );
    }

    /** Drain-and-stop, mirroring AsyncEmbeddingIndexListener.close(): 5s await then force. */
    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if ( !scheduler.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "entity-sync scheduler did not drain within 5s; forcing shutdown" );
                scheduler.shutdownNow();
            }
        } catch ( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
