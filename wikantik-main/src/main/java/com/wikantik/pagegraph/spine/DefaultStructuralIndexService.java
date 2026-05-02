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

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import com.wikantik.api.pagegraph.Verification;
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
    private final PageVerificationDao verificationDao;
    private final ConfidenceComputer confidenceComputer;
    private final StructuralIndexMetrics metrics;

    private final AtomicReference< StructuralProjection > current =
            new AtomicReference<>( new StructuralProjectionBuilder().build() );
    private volatile IndexHealth health = new IndexHealth(
            IndexHealth.Status.DOWN, 0, 0, null, null, 0L, 0L );
    private volatile int unclaimed = 0;
    private volatile List< StructuralConflict > conflicts = List.of();

    /** Full-fidelity ctor — used by the production WikiEngine bootstrap. */
    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao,
                                          final PageVerificationDao verificationDao,
                                          final ConfidenceComputer confidenceComputer,
                                          final StructuralIndexMetrics metrics ) {
        this.pageManager        = pageManager;
        this.dao                = dao;
        this.verificationDao    = verificationDao;
        this.confidenceComputer = confidenceComputer == null
                ? new ConfidenceComputer( name -> false ) : confidenceComputer;
        this.metrics            = metrics == null ? new StructuralIndexMetrics() : metrics;
    }

    /** Three-arg ctor without explicit metrics. */
    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao,
                                          final StructuralIndexMetrics metrics ) {
        this( pageManager, dao, /* verificationDao */ null, /* confidenceComputer */ null, metrics );
    }

    /** Convenience constructor for tests — no verification, no-op metrics. */
    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao ) {
        this( pageManager, dao, /* verificationDao */ null, /* confidenceComputer */ null,
                new StructuralIndexMetrics() );
    }

    @Override
    public synchronized void rebuild() {
        final Instant start = Instant.now();
        this.health = new IndexHealth( IndexHealth.Status.REBUILDING,
                current.get().pageCount(), unclaimed, start, null, 0L, 0L );

        final StructuralProjectionBuilder builder = new StructuralProjectionBuilder();
        Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            LOG.warn( "rebuild() could not enumerate pages: {}", e.getMessage(), e );
            this.health = new IndexHealth( IndexHealth.Status.DEGRADED, 0, 0, start, Instant.now(), 0L, 0L );
            return;
        }

        // Parse pages, record canonical_ids.
        final List< StructuralConflict > foundConflicts = new ArrayList<>();
        int missing = 0;
        int indexed = 0;

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
                    foundConflicts.add( new StructuralConflict(
                            p.getName(), null, StructuralConflict.Kind.MISSING_CANONICAL_ID,
                            "page indexed under synthesised id " + canonicalId
                              + " — add canonical_id to frontmatter to make this stable" ) );
                }

                final PageType type = PageType.fromFrontmatter( fm.get( "type" ) );
                final String cluster = asString( fm.get( "cluster" ) );
                final String title = firstNonBlank( asString( fm.get( "title" ) ), p.getName() );
                final String summary = asString( fm.get( "summary" ) );
                final List< String > tags = stringList( fm.get( "tags" ) );
                final Instant updated = p.getLastModified() == null ? null : p.getLastModified().toInstant();
                final Optional< Boolean > kgInclude = parseKgInclude( fm.get( "kg_include" ) );

                builder.addPage( new PageDescriptor(
                        canonicalId, p.getName(), title, type, cluster, tags, summary, updated, kgInclude ) );

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
                    persistVerification( canonicalId, fm );
                }

                indexed++;
            } catch ( final Exception e ) {
                LOG.warn( "rebuild(): failed to index page {}: {}", p.getName(), e.getMessage() );
            }
        }

        current.set( builder.build() );
        this.unclaimed = missing;
        this.conflicts = List.copyOf( foundConflicts );

        final Instant finish = Instant.now();
        final long durationMs = finish.toEpochMilli() - start.toEpochMilli();
        this.health = new IndexHealth( IndexHealth.Status.UP, indexed, missing,
                start, finish, durationMs, 0L );
        metrics.update( snapshot(), health );
        metrics.recordRebuildMillis( durationMs );
        LOG.info( "Structural index rebuilt: {} pages indexed ({} without canonical_id), in {} ms",
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
    public IndexHealth health() { return health; }

    @Override
    public List< StructuralConflict > conflicts() { return conflicts; }

    @Override
    public Optional< Verification > verificationOf( final String canonicalId ) {
        if ( verificationDao == null || canonicalId == null || canonicalId.isBlank() ) {
            return Optional.empty();
        }
        return verificationDao.findByCanonicalId( canonicalId );
    }

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
            applyIncrementalUpdate( page );
        } catch ( final Exception e ) {
            LOG.warn( "onPageSaved({}) failed: {}", pageName, e.getMessage() );
        }
    }

    /**
     * Re-read the saved page in isolation and splice its descriptor + relations into
     * a fresh projection — no full corpus re-scan. Authored canonical_ids are
     * persisted; synthesised ones live in memory only and surface in {@code conflicts}.
     */
    private void applyIncrementalUpdate( final Page page ) {
        final String slug = page.getName();
        final String raw = pageManager.getPureText( page );
        final ParsedPage parsed = FrontmatterParser.parse( raw );
        final Map< String, Object > fm = parsed.metadata();

        String canonicalId = asString( fm.get( "canonical_id" ) );
        final boolean authored = canonicalId != null && !canonicalId.isBlank();
        if ( !authored ) {
            canonicalId = UlidCreator.getUlid().toString();
        }

        final PageType type = PageType.fromFrontmatter( fm.get( "type" ) );
        final String cluster = asString( fm.get( "cluster" ) );
        final String title = firstNonBlank( asString( fm.get( "title" ) ), slug );
        final String summary = asString( fm.get( "summary" ) );
        final List< String > tags = stringList( fm.get( "tags" ) );
        final Instant updated = page.getLastModified() == null
                ? null : page.getLastModified().toInstant();
        final Optional< Boolean > kgInclude = parseKgInclude( fm.get( "kg_include" ) );
        final PageDescriptor next = new PageDescriptor(
                canonicalId, slug, title, type, cluster, tags, summary, updated, kgInclude );

        if ( authored ) {
            try {
                dao.upsert( canonicalId, slug, title, type.asFrontmatterValue(), cluster );
            } catch ( final RuntimeException dbx ) {
                LOG.warn( "DAO upsert failed for {}: {}", slug, dbx.getMessage() );
            }
            persistVerification( canonicalId, fm );
        }

        final StructuralProjection proj = current.get();

        final StructuralProjectionBuilder builder = new StructuralProjectionBuilder();
        for ( final PageDescriptor existing : proj.allPages() ) {
            if ( existing.slug().equals( slug ) ) continue;
            if ( existing.canonicalId().equals( canonicalId ) ) continue;
            builder.addPage( existing );
        }
        builder.addPage( next );
        current.set( builder.build() );

        final List< StructuralConflict > nextConflicts = new ArrayList<>( conflicts );
        nextConflicts.removeIf( c -> slug.equals( c.slug() ) );
        if ( !authored ) {
            nextConflicts.add( new StructuralConflict(
                    slug, null, StructuralConflict.Kind.MISSING_CANONICAL_ID,
                    "page indexed under synthesised id " + canonicalId
                      + " — add canonical_id to frontmatter to make this stable" ) );
        }
        this.conflicts = List.copyOf( nextConflicts );
        this.unclaimed = (int) nextConflicts.stream()
                .filter( c -> c.kind() == StructuralConflict.Kind.MISSING_CANONICAL_ID ).count();
        this.health = new IndexHealth( IndexHealth.Status.UP,
                current.get().pageCount(), unclaimed,
                health.lastRebuildStartedAt(), health.lastRebuildFinishedAt(),
                health.lastRebuildDurationMillis(), health.lagSeconds() );
        metrics.update( snapshot(), health );
    }

    synchronized void onPageDeleted( final String pageName ) {
        try {
            final StructuralProjection proj = current.get();
            final Optional< String > canonicalId = proj.resolveCanonicalIdFromSlug( pageName );
            if ( canonicalId.isEmpty() ) {
                return;
            }
            applyIncrementalDelete( pageName, canonicalId.get() );
        } catch ( final Exception e ) {
            LOG.warn( "onPageDeleted({}) failed: {}", pageName, e.getMessage() );
        }
    }

    /**
     * Drop the descriptor for {@code slug}.
     */
    private void applyIncrementalDelete( final String slug, final String canonicalId ) {
        try {
            dao.delete( canonicalId );
        } catch ( final RuntimeException dbx ) {
            LOG.warn( "dao.delete({}) failed: {}", canonicalId, dbx.getMessage() );
        }

        final StructuralProjection proj = current.get();
        final StructuralProjectionBuilder builder = new StructuralProjectionBuilder();
        for ( final PageDescriptor existing : proj.allPages() ) {
            if ( existing.canonicalId().equals( canonicalId ) ) continue;
            builder.addPage( existing );
        }
        current.set( builder.build() );

        this.conflicts = conflicts.stream()
                .filter( c -> !slug.equals( c.slug() ) )
                .toList();
        this.unclaimed = (int) conflicts.stream()
                .filter( c -> c.kind() == StructuralConflict.Kind.MISSING_CANONICAL_ID ).count();
        this.health = new IndexHealth( IndexHealth.Status.UP,
                current.get().pageCount(), unclaimed,
                health.lastRebuildStartedAt(), health.lastRebuildFinishedAt(),
                health.lastRebuildDurationMillis(), health.lagSeconds() );
        metrics.update( snapshot(), health );
    }

    /**
     * Project frontmatter verification fields into {@code page_verification}.
     * No-op when the verification DAO isn't wired (older constructors). The
     * confidence stored is the rule-engine's output — author overrides win,
     * but otherwise it's a function of verified_at + trusted-authors.
     */
    private void persistVerification( final String canonicalId, final Map< String, Object > fm ) {
        if ( verificationDao == null ) {
            return;
        }
        try {
            final Instant verifiedAt = parseInstant( fm.get( "verified_at" ) );
            final String verifiedBy  = asString( fm.get( "verified_by" ) );
            final var explicitOverride = com.wikantik.api.pagegraph.Confidence
                    .fromWire( fm.get( "confidence" ) );
            final com.wikantik.api.pagegraph.Audience audience =
                    com.wikantik.api.pagegraph.Audience.fromFrontmatter( fm.get( "audience" ) );
            final com.wikantik.api.pagegraph.Confidence confidence =
                    confidenceComputer.compute( verifiedAt, verifiedBy, explicitOverride, Instant.now() );
            verificationDao.upsert( canonicalId, new Verification( verifiedAt, verifiedBy, confidence, audience ) );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "verification persistence failed for {}: {}", canonicalId, ex.getMessage() );
        }
    }

    private static Instant parseInstant( final Object raw ) {
        if ( raw == null ) {
            return null;
        }
        if ( raw instanceof java.util.Date d ) {
            // SnakeYAML parses ISO-8601 timestamps directly into java.util.Date.
            return d.toInstant();
        }
        if ( raw instanceof Instant i ) {
            return i;
        }
        final String s = raw.toString().trim();
        if ( s.isEmpty() ) {
            return null;
        }
        try {
            return Instant.parse( s );
        } catch ( final java.time.format.DateTimeParseException ex ) {
            // Fall back to ISO_LOCAL_DATE_TIME or ISO_DATE — authors sometimes omit the trailing Z.
            try {
                return java.time.LocalDateTime.parse( s ).atZone( java.time.ZoneOffset.UTC ).toInstant();
            } catch ( final java.time.format.DateTimeParseException ignored ) {
                LOG.warn( "verified_at could not be parsed: '{}'", s );
                return null;
            }
        }
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

    /**
     * Parses the {@code kg_include} frontmatter field. SnakeYAML auto-types
     * {@code true}/{@code false} literals to {@link Boolean}, but authors may
     * also write the value as a string. Any unrecognised value yields
     * {@link Optional#empty()}.
     */
    private static Optional< Boolean > parseKgInclude( final Object raw ) {
        if ( raw == null ) {
            return Optional.empty();
        }
        if ( raw instanceof Boolean b ) {
            return Optional.of( b );
        }
        final String s = raw.toString().trim().toLowerCase( java.util.Locale.ROOT );
        if ( "true".equals( s ) )  return Optional.of( true );
        if ( "false".equals( s ) ) return Optional.of( false );
        return Optional.empty();
    }
}
