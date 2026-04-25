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
package com.wikantik.knowledge.structure;

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.ClusterDetails;
import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.IndexHealth;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.Sitemap;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
import com.wikantik.api.structure.TraversalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link StructuralIndexService}. Maintains an in-memory
 * {@link StructuralProjection} rebuilt on startup and updated incrementally via
 * {@link StructuralIndexEventListener}. Writes canonical-id state through
 * {@link PageCanonicalIdsDao} for durability and rename-stability.
 *
 * <p>Phase 1 is observe-only. Pages missing {@code canonical_id} frontmatter get a
 * synthesised ULID assigned in memory only (not written to disk); they surface in
 * {@link IndexHealth#unclaimedCanonicalIds} so authors can backfill.</p>
 */
public class DefaultStructuralIndexService implements StructuralIndexService {

    private static final Logger LOG = LogManager.getLogger( DefaultStructuralIndexService.class );

    private final PageManager pageManager;
    private final PageCanonicalIdsDao dao;
    private final StructuralIndexMetrics metrics;

    private final AtomicReference< StructuralProjection > current =
            new AtomicReference<>( new StructuralProjectionBuilder().build() );
    private volatile IndexHealth health = new IndexHealth(
            IndexHealth.Status.DOWN, 0, 0, null, null, 0L, 0L );
    private volatile int unclaimed = 0;

    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao,
                                          final StructuralIndexMetrics metrics ) {
        this.pageManager = pageManager;
        this.dao = dao;
        this.metrics = metrics == null ? new StructuralIndexMetrics() : metrics;
    }

    /** Convenience constructor for tests — uses a no-op metrics holder. */
    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao ) {
        this( pageManager, dao, new StructuralIndexMetrics() );
    }

    @Override
    public synchronized void rebuild() {
        final Instant start = Instant.now();
        this.health = new IndexHealth( IndexHealth.Status.REBUILDING,
                current.get().pageCount(), unclaimed, start, null, 0L, 0L );

        final StructuralProjectionBuilder builder = new StructuralProjectionBuilder();
        int missing = 0;
        int indexed = 0;
        Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            LOG.warn( "rebuild() could not enumerate pages: {}", e.getMessage(), e );
            this.health = new IndexHealth( IndexHealth.Status.DEGRADED, 0, 0, start, Instant.now(), 0L, 0L );
            return;
        }

        for ( final Page p : pages ) {
            try {
                final String raw = pageManager.getPureText( p );
                final ParsedPage parsed = FrontmatterParser.parse( raw );
                final Map< String, Object > fm = parsed.metadata();

                String canonicalId = asString( fm.get( "canonical_id" ) );
                final boolean authored = canonicalId != null && !canonicalId.isBlank();
                if ( !authored ) {
                    canonicalId = UlidCreator.getUlid().toString();
                    missing++;
                }

                final PageType type = PageType.fromFrontmatter( fm.get( "type" ) );
                final String cluster = asString( fm.get( "cluster" ) );
                final String title = firstNonBlank( asString( fm.get( "title" ) ), p.getName() );
                final String summary = asString( fm.get( "summary" ) );
                final List< String > tags = stringList( fm.get( "tags" ) );
                final Instant updated = p.getLastModified() == null ? null : p.getLastModified().toInstant();

                builder.addPage( new PageDescriptor(
                        canonicalId, p.getName(), title, type, cluster, tags, summary, updated ) );

                // Only persist canonical_ids authored in frontmatter. Synthesised IDs live
                // in memory until an author (or Phase 4's mandatory validator) writes them
                // to disk — otherwise every restart would churn fresh rows into the DB.
                if ( authored ) {
                    try {
                        dao.upsert( canonicalId, p.getName(), title, type.asFrontmatterValue(), cluster );
                    } catch ( final RuntimeException dbx ) {
                        LOG.warn( "DAO upsert failed for {} — in-memory projection will continue: {}",
                                  p.getName(), dbx.getMessage() );
                    }
                }

                indexed++;
            } catch ( final Exception e ) {
                LOG.warn( "rebuild(): failed to index page {}: {}", p.getName(), e.getMessage() );
            }
        }

        current.set( builder.build() );
        this.unclaimed = missing;

        final Instant finish = Instant.now();
        final long durationMs = finish.toEpochMilli() - start.toEpochMilli();
        this.health = new IndexHealth( IndexHealth.Status.UP, indexed, missing,
                start, finish, durationMs, 0L );
        metrics.update( snapshot(), health );
        metrics.recordRebuildMillis( durationMs );
        LOG.info( "Structural index rebuilt: {} pages indexed ({} without canonical_id) in {} ms",
                  indexed, missing, durationMs );
    }

    @Override
    public List< ClusterSummary > listClusters() { return current.get().listClusters(); }

    @Override
    public Optional< ClusterDetails > getCluster( final String name ) {
        return current.get().getCluster( name );
    }

    @Override
    public List< TagSummary > listTags( final int minPages ) { return current.get().listTags( minPages ); }

    @Override
    public List< PageDescriptor > listPagesByType( final PageType type ) {
        return current.get().listPagesByType( type );
    }

    @Override
    public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) {
        return current.get().listPagesByFilter( filter );
    }

    @Override
    public Sitemap sitemap() { return current.get().sitemap(); }

    @Override
    public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) {
        return current.get().getByCanonicalId( canonicalId );
    }

    @Override
    public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
        return current.get().resolveSlugFromCanonicalId( canonicalId );
    }

    @Override
    public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
        return current.get().resolveCanonicalIdFromSlug( slug );
    }

    @Override
    public List< RelationEdge > outgoingRelations( final String canonicalId,
                                                    final Optional< RelationType > typeFilter ) {
        return current.get().outgoingRelations( canonicalId, typeFilter );
    }

    @Override
    public List< RelationEdge > incomingRelations( final String canonicalId,
                                                    final Optional< RelationType > typeFilter ) {
        return current.get().incomingRelations( canonicalId, typeFilter );
    }

    @Override
    public List< RelationEdge > traverse( final String canonicalId, final TraversalSpec spec ) {
        return current.get().traverse( canonicalId, spec == null ? TraversalSpec.outOnce() : spec );
    }

    @Override
    public IndexHealth health() { return health; }

    @Override
    public StructuralProjectionSnapshot snapshot() {
        final StructuralProjection p = current.get();
        return new StructuralProjectionSnapshot() {
            @Override public int pageCount()       { return p.pageCount(); }
            @Override public int clusterCount()    { return p.clusterCount(); }
            @Override public int tagCount()        { return p.tagCount(); }
            @Override public Instant generatedAt() { return p.generatedAt(); }
        };
    }

    /** Called by {@link StructuralIndexEventListener} on save events. */
    synchronized void onPageSaved( final String pageName ) {
        try {
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                return;
            }
            // For Phase 1 we defer to a full rebuild on incremental events — the
            // projection of ~2000 pages rebuilds in well under a second. Phase 2
            // will make this properly incremental.
            rebuild();
        } catch ( final Exception e ) {
            LOG.warn( "onPageSaved({}) failed: {}", pageName, e.getMessage() );
        }
    }

    synchronized void onPageDeleted( final String pageName ) {
        rebuild();
    }

    private static String asString( final Object o ) {
        if ( o == null ) return null;
        final String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank( final String a, final String b ) {
        return ( a == null || a.isBlank() ) ? b : a;
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > stringList( final Object o ) {
        if ( o == null ) return List.of();
        if ( o instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object x : list ) {
                if ( x != null ) out.add( x.toString() );
            }
            return List.copyOf( out );
        }
        return List.of( o.toString() );
    }
}
