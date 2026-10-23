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
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;
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
import java.util.HashMap;
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
    private final ConfidenceComputer confidenceComputer;
    private final StructuralIndexMetrics metrics;

    private final AtomicReference< StructuralProjection > current =
            new AtomicReference<>( new StructuralProjectionBuilder().build() );
    private volatile IndexHealth health = new IndexHealth(
            IndexHealth.Status.DOWN, 0, 0, null, null, 0L, 0L );
    private volatile int unclaimed = 0;
    private volatile List< StructuralConflict > conflicts = List.of();
    /** {@code page_verification} rows in memory, loaded at rebuild (DB fallback until then). */
    private final VerificationStore verifications;

    /** Full-fidelity ctor — used by the production WikiEngine bootstrap. */
    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao,
                                          final PageVerificationDao verificationDao,
                                          final ConfidenceComputer confidenceComputer,
                                          final StructuralIndexMetrics metrics ) {
        this.pageManager        = pageManager;
        this.dao                = dao;
        this.verifications      = new VerificationStore( verificationDao );
        this.confidenceComputer = confidenceComputer == null
                ? new ConfidenceComputer( name -> false ) : confidenceComputer;
        this.metrics            = metrics == null ? new StructuralIndexMetrics() : metrics;
        this.sharers            = new CanonicalIdSharers( pageManager, dao );
    }

    /**
     * The confidence computer this index scores verification with — carrying the operator's
     * {@code wikantik.verification.stale_days} window and trusted-author predicate. Never null.
     * Exposed so the subsystem factory can publish it on {@code PageGraphSubsystem.Services}
     * without a manager-registry lookup.
     */
    public ConfidenceComputer confidenceComputer() {
        return confidenceComputer;
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

    /** Title/alias bookkeeping for {@link #titleLookup()}. */
    private final TitleIndexState titles = new TitleIndexState();

    /** Finds other live pages carrying a deleted page's canonical_id. */
    private final CanonicalIdSharers sharers;

    /** Told when a true delete retires a canonical_id. */
    private final PageRetirementListeners retirementListeners = new PageRetirementListeners();

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
        final Map< String, List< String > > nextAliases = new HashMap<>();
        int missing = 0;
        int indexed = 0;

        for ( final Page p : pages ) {
            try {
                final String raw = pageManager.getPureText( p );
                final ParsedPage parsed = FrontmatterParser.parse( raw );
                final Map< String, Object > fm = parsed.metadata();

                String canonicalId = SpineFrontmatter.asString( fm.get( "canonical_id" ) );
                final boolean authored = canonicalId != null && !canonicalId.isBlank();
                if ( !authored ) {
                    canonicalId = UlidCreator.getUlid().toString();
                    missing++;
                    foundConflicts.add( new StructuralConflict(
                            p.getName(), null, StructuralConflict.Kind.MISSING_CANONICAL_ID,
                            "page indexed under synthesised id " + canonicalId
                              + " — add canonical_id to frontmatter to make this stable" ) );
                }

                final PageDescriptor descriptor = toDescriptor( canonicalId, p, fm );
                builder.addPage( descriptor );
                nextAliases.put( canonicalId, TitleIndexState.aliasesOf( fm ) );

                // Only persist canonical_ids authored in frontmatter. Synthesised IDs live
                // in memory until an author (or the save-time validator) writes them to disk —
                // otherwise every restart would churn fresh rows into the DB.
                if ( authored ) {
                    persistCanonicalId( descriptor, fm );
                }

                indexed++;
            } catch ( final Exception e ) {
                LOG.warn( "rebuild(): failed to index page {}: {}", p.getName(), e.getMessage() );
            }
        }

        verifications.reload();
        final StructuralProjection projection = builder.build();
        current.set( projection );
        titles.onRebuilt( nextAliases );
        this.unclaimed = missing;
        // Per-page defects found while parsing, plus the taxonomy defects only visible
        // once every page has been indexed (duplicate/headless/orphan clusters).
        foundConflicts.addAll( projection.structuralConflicts() );
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
        return verifications.get( canonicalId );
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
        indexPage( pageName );
    }

    /**
     * Re-index one page from its current text. Never throws.
     *
     * @return {@code true} when the page was found and spliced into the projection
     */
    private boolean indexPage( final String pageName ) {
        try {
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                LOG.warn( "indexPage({}): page not found — projection left unchanged", pageName );
                return false;
            }
            applyIncrementalUpdate( page );
            return true;
        } catch ( final Exception e ) {
            LOG.warn( "indexPage({}) failed: {}", pageName, e.getMessage() );
            return false;
        }
    }

    /**
     *  Maps a page's frontmatter to its structural descriptor.
     *
     *  <p>Shared by the full rebuild and the incremental update so the two can never disagree
     *  about how a page is read — they previously carried two copies of this mapping.</p>
     *
     *  @param canonicalId the page's canonical id, authored or synthesised
     *  @param page        the page being indexed
     *  @param fm          its parsed frontmatter
     *  @return the descriptor to place in the projection
     */
    private PageDescriptor toDescriptor( final String canonicalId, final Page page,
                                          final Map< String, Object > fm ) {
        // Phase 5: `cluster:` may be a scalar or a list. ClusterPath.memberships is the one
        // place that difference is resolved; the DAO column keeps the primary.
        final List< String > clusters = ClusterPath.memberships( fm.get( "cluster" ) );
        return new PageDescriptor(
                canonicalId,
                page.getName(),
                SpineFrontmatter.firstNonBlank( SpineFrontmatter.asString( fm.get( "title" ) ), page.getName() ),
                PageType.fromFrontmatter( fm.get( "type" ) ),
                clusters.isEmpty() ? null : clusters.get( 0 ),
                clusters,
                SpineFrontmatter.stringList( fm.get( "tags" ) ),
                SpineFrontmatter.asString( fm.get( "summary" ) ),
                page.getLastModified() == null ? null : page.getLastModified().toInstant(),
                SpineFrontmatter.parseKgInclude( fm.get( "kg_include" ) ),
                fm.get( "derived_from" ) != null );
    }

    /**
     *  Writes the canonical-id row and, unless that row was skipped, the page's verification
     *  stamp.
     *
     *  <p>Never throws: a DAO failure leaves the in-memory projection authoritative for this
     *  run. Verification is skipped only when we EXPLICITLY know the canonical_id was not
     *  written (stale-slug owner) — otherwise every boot logs a noisy FK violation against a
     *  row that does not exist.</p>
     *
     *  @param pd the descriptor just indexed
     *  @param fm its parsed frontmatter, read for the verification stamp
     */
    private void persistCanonicalId( final PageDescriptor pd, final Map< String, Object > fm ) {
        PageCanonicalIdsDao.UpsertResult upsertResult = null;
        try {
            upsertResult = dao.upsert( pd.canonicalId(), pd.slug(), pd.title(),
                                       pd.type().asFrontmatterValue(), pd.cluster() );
        } catch ( final RuntimeException dbx ) {
            LOG.warn( "DAO upsert failed for {} — in-memory projection will continue: {}",
                      pd.slug(), dbx.getMessage() );
            upsertResult = PageCanonicalIdsDao.UpsertResult.SKIPPED_STALE_SLUG_OWNER;
        }
        if ( upsertResult != PageCanonicalIdsDao.UpsertResult.SKIPPED_STALE_SLUG_OWNER ) {
            persistVerification( pd.canonicalId(), fm );
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

        String canonicalId = SpineFrontmatter.asString( fm.get( "canonical_id" ) );
        final boolean authored = canonicalId != null && !canonicalId.isBlank();
        if ( !authored ) {
            canonicalId = UlidCreator.getUlid().toString();
        }

        final PageDescriptor next = toDescriptor( canonicalId, page, fm );

        if ( authored ) {
            persistCanonicalId( next, fm );
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
        titles.onSaved( canonicalId, fm );

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

    /**
     * Registers a listener told when a deleted page's canonical_id is retired — the hook the
     * subsystems keyed on canonical_id (ownership, comments, citations) clean up through.
     * A {@code null} listener is ignored.
     */
    public void addPageRetirementListener( final PageRetirementListener listener ) {
        retirementListeners.add( listener );
    }

    /**
     * Called by {@link StructuralIndexEventListener} when a page is renamed. The renamer moves
     * the file without running save filters, so this is the only signal the index gets: re-index
     * the page under its new slug (its authored canonical_id moved with it, so the id row is
     * re-pointed rather than retired), then drop whatever still sits under the old slug — a page
     * without an authored id was re-indexed under a fresh synthesised one. Nothing is retired.
     * When the new slug fails to index the old entry is kept: dropping it would leave the page
     * invisible to the index until its next save.
     */
    synchronized void onPageRenamed( final String oldName, final String newName ) {
        if ( !indexPage( newName ) ) {
            LOG.warn( "onPageRenamed({} -> {}): new slug did not index — keeping the old entry", oldName, newName );
            return;
        }
        current.get().resolveCanonicalIdFromSlug( oldName )
                .ifPresent( staleId -> applyIncrementalDelete( oldName, staleId, false ) );
    }

    /**
     * A page was deleted. When its slug still maps to a canonical_id here, that id is retired —
     * a rename has already re-pointed it at the new slug, so the old slug no longer resolves —
     * and, after the index's own update, the retirement listeners run. They run under the index
     * lock, like the {@code page_canonical_ids} delete before them, so a save of the same page
     * cannot interleave with its cleanup; each is a handful of keyed DELETE/UPDATE statements.
     */
    synchronized void onPageDeleted( final String pageName ) {
        try {
            if ( sharers.mayStillExist( pageName ) ) {
                LOG.warn( "onPageDeleted({}): the page still exists (its delete failed?) — retiring nothing",
                          pageName );
                return;
            }
            final Optional< String > canonicalId = current.get().resolveCanonicalIdFromSlug( pageName );
            if ( canonicalId.isEmpty() ) {
                return;
            }
            final String id = canonicalId.get();
            final List< String > others = sharers.liveSharersOf( current.get(), id, pageName );
            if ( !others.isEmpty() ) {
                // Another live page carries the same canonical_id (an authoring defect). Its owners,
                // comments, citations and id row belong to it too: drop only this slug, then
                // re-index the sharer so the projection and the id row point at it.
                LOG.warn( "onPageDeleted({}): canonical_id {} is also carried by {} — not retiring it",
                          pageName, id, others );
                applyIncrementalDelete( pageName, id, false );
                others.forEach( this::indexPage );
                return;
            }
            applyIncrementalDelete( pageName, id, true );
            retirementListeners.fire( pageName, id );
        } catch ( final Exception e ) {
            LOG.warn( "onPageDeleted({}) failed: {}", pageName, e.getMessage() );
        }
    }

    /**
     * Drop the descriptor for {@code slug}, with its title + conflict bookkeeping. With
     * {@code retireRow} the canonical_id's {@code page_canonical_ids} row (and through it the
     * verification + slug-history rows) goes too; without it — a rename's stale old-slug entry —
     * only the in-memory projection changes.
     */
    private void applyIncrementalDelete( final String slug, final String canonicalId, final boolean retireRow ) {
        if ( retireRow ) {
            try {
                dao.delete( canonicalId );
            } catch ( final RuntimeException dbx ) {
                LOG.warn( "dao.delete({}) failed: {}", canonicalId, dbx.getMessage() );
            }
            verifications.remove( canonicalId );
        }

        final StructuralProjection proj = current.get();
        final StructuralProjectionBuilder builder = new StructuralProjectionBuilder();
        for ( final PageDescriptor existing : proj.allPages() ) {
            if ( existing.canonicalId().equals( canonicalId ) ) continue;
            builder.addPage( existing );
        }
        current.set( builder.build() );
        titles.onDeleted( canonicalId );

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
        if ( !verifications.wired() ) {
            return;
        }
        try {
            final Instant verifiedAt = SpineFrontmatter.parseInstant( fm.get( "verified_at" ) );
            final String verifiedBy  = SpineFrontmatter.asString( fm.get( "verified_by" ) );
            final var explicitOverride = com.wikantik.api.pagegraph.Confidence
                    .fromWire( fm.get( "confidence" ) );
            final com.wikantik.api.pagegraph.Audience audience =
                    com.wikantik.api.pagegraph.Audience.fromFrontmatter( fm.get( "audience" ) );
            final com.wikantik.api.pagegraph.Confidence confidence =
                    confidenceComputer.compute( verifiedAt, verifiedBy, explicitOverride, Instant.now() );
            final Verification verification = new Verification( verifiedAt, verifiedBy, confidence, audience );
            verifications.save( canonicalId, verification );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "verification persistence failed for {}: {}", canonicalId, ex.getMessage() );
        }
    }

    @Override
    public Optional< PageTitleLookup > titleLookup() {
        return titles.lookup( current.get() );
    }
}
