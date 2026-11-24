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
package com.wikantik.pagegraph.subsystem;

import com.wikantik.filters.SaveFilterPriority;
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.pagegraph.DefaultPageGraphService;
import com.wikantik.pagegraph.spine.ConfidenceComputer;
import com.wikantik.pagegraph.spine.DefaultStructuralIndexService;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import com.wikantik.pagegraph.spine.PageVerificationDao;
import com.wikantik.pagegraph.spine.StructuralIndexEventListener;
import com.wikantik.pagegraph.spine.StructuralIndexMetrics;
import com.wikantik.pagegraph.spine.StructuralSpinePageFilter;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.citation.CitationLinkRenderingFilter;
import com.wikantik.filters.FilterManager;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Wiring helper for Page Graph structural-spine services.
 *
 * <p>Phase 9 Ckpt 4c of the wikantik-main decomposition. The structural-spine
 * wiring block previously lived inline in {@code WikiEngine.initDataSourceSubsystems}.
 * Moving it here reduces {@code WikiEngine.java} toward the &lt;1500 LOC target
 * and collocates the construction logic with the Page Graph subsystem.</p>
 *
 * <p>Registers services via {@code engine.setManager(X.class, foo)}.
 * The registry is not deleted in this checkpoint (that is Ckpt 4d).</p>
 */
public final class PageGraphWiringHelper {

    private static final Logger LOG = LogManager.getLogger( PageGraphWiringHelper.class );

    private PageGraphWiringHelper() {}

    /**
     * Builds and registers the structural-spine and page-graph snapshot services.
     *
     * @return the {@link DefaultStructuralIndexService} so callers can pass it to
     *         downstream helpers (agent projection, citations, etc.)
     */
    public static DefaultStructuralIndexService wireStructuralSpine(
            final Properties props,
            final PersistenceSubsystem.Services persistenceSubsystem,
            final CoreSubsystem.Services coreSubsystem,
            final PageManager pageManager,
            final FilterManager filterManager,
            final ReferenceManager referenceManager,
            final WikiEngine engine ) {

        final PageCanonicalIdsDao canonicalIdsDao = persistenceSubsystem.pageCanonicalIds();
        final PageVerificationDao pageVerificationDao = persistenceSubsystem.pageVerification();
        final TrustedAuthorsDao trustedAuthorsDao = persistenceSubsystem.trustedAuthors();
        final int staleDays = TextUtil.getIntegerProperty( props,
            "wikantik.verification.stale_days", ConfidenceComputer.DEFAULT_STALE_DAYS );
        final ConfidenceComputer confidenceComputer =
            new ConfidenceComputer( trustedAuthorsDao::contains, staleDays );
        final StructuralIndexMetrics structuralMetrics = StructuralIndexMetrics.resolveAndBind();
        final DefaultStructuralIndexService structuralIndex =
            new DefaultStructuralIndexService(
                pageManager, canonicalIdsDao,
                pageVerificationDao, confidenceComputer, structuralMetrics );

        engine.setManager( PageVerificationDao.class, pageVerificationDao );
        engine.setManager( TrustedAuthorsDao.class, trustedAuthorsDao );
        engine.setManager( com.wikantik.api.pagegraph.StructuralIndexService.class, structuralIndex );

        wireLiveValues( props, coreSubsystem, structuralIndex, engine );

        // WikiEventManager holds listeners as WeakReferences — keep a strong
        // reference in the managers map so the listener is not GC'd between events.
        final StructuralIndexEventListener structuralIndexListener =
            new StructuralIndexEventListener( structuralIndex );
        final com.wikantik.page.subsystem.PageSubsystem.Services pages = engine.getPageSubsystem();
        structuralIndexListener.register( pageManager, filterManager, pages == null ? null : pages.pageRenamer() );
        engine.setManager( StructuralIndexEventListener.class, structuralIndexListener );
        wireRetirementCleanups( structuralIndex, persistenceSubsystem );

        // Native [[ ]] links resolve through the title index. Once the initial rebuild has finished, refresh the
        // references of pages that were scanned while it was warming (or before native links existed). This runs on
        // the bootstrap thread after rebuild() has returned, so it never holds the index monitor that save events need.
        final com.wikantik.pagegraph.references.DefaultReferenceManager nativeRescan =
            referenceManager instanceof com.wikantik.pagegraph.references.DefaultReferenceManager d ? d : null;
        if ( nativeRescan == null ) {
            LOG.info( "ReferenceManager is {}; native wikilink rescan on index readiness is skipped",
                      referenceManager == null ? "absent" : referenceManager.getClass().getName() );
        }
        new Thread( () -> {
            structuralIndex.rebuild();
            if ( nativeRescan != null && structuralIndex.titleLookup().isPresent() ) {
                nativeRescan.rescanNativeWikiLinks();
            }
        }, "structural-index-bootstrap" ).start();
        LOG.info( "StructuralIndexService registered; initial rebuild dispatched" );

        // Page Graph snapshot service — backs the /page-graph React route.
        final DefaultPageGraphService pageGraphService =
            new DefaultPageGraphService(
                structuralIndex, referenceManager, pageManager );
        pageGraphService.setEngine( engine );
        engine.setManager( com.wikantik.api.pagegraph.PageGraphService.class, pageGraphService );
        LOG.info( "PageGraphService registered" );

        return structuralIndex;
    }

    /**
     * Registers the live-values service ({@code {{wiki:…}}} tokens). "Public" means a guest may view the page, which
     * applies the private-cluster gate and ACLs; the authorization manager is resolved lazily on first use because
     * it is not guaranteed to be in the registry when this step runs. Silent {@code isPermitted} is used rather than
     * {@code checkPermission} so a background recompute does not write an access-denied audit entry per private page.
     */
    static void wireLiveValues( final Properties props, final CoreSubsystem.Services coreSubsystem,
                                final DefaultStructuralIndexService structuralIndex, final WikiEngine engine ) {
        final boolean enabled = TextUtil.getBooleanProperty( props, "wikantik.liveValues.enabled", true );
        final com.wikantik.livevalues.DefaultLiveValues liveValues =
            new com.wikantik.livevalues.DefaultLiveValues( structuralIndex, enabled );
        final java.util.concurrent.atomic.AtomicReference< com.wikantik.api.core.Session > guest =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.function.Predicate< String > isPublic = name -> {
            final com.wikantik.auth.AuthorizationManager auth =
                engine.serviceRegistry().get( com.wikantik.auth.AuthorizationManager.class );
            if ( auth == null ) {
                // not ready (boot): signal it so DefaultLiveValues retries instead of freezing counts at zero
                throw new IllegalStateException( "AuthorizationManager not yet available" );
            }
            try {
                guest.compareAndSet( null, com.wikantik.WikiSession.guestSession( engine ) );
                return auth.isPermitted( guest.get(),
                    new com.wikantik.auth.permissions.PagePermission( name, com.wikantik.auth.permissions.PagePermission.VIEW_ACTION ) );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Live values: could not check guest view of '{}'; treating as not public: {}", name, e.getMessage(), e );
                return false;
            }
        };
        liveValues.register( new com.wikantik.livevalues.CorpusLiveValueSource(
            structuralIndex, coreSubsystem.systemPageRegistry(), isPublic ) );
        engine.serviceRegistry().put( com.wikantik.api.livevalues.LiveValues.class, liveValues );
        LOG.info( "LiveValues registered (enabled={})", enabled );
    }

    /**
     * Rows keyed on a page's canonical_id with no foreign key to {@code page_canonical_ids}
     * (V033/V034 — the index synthesises ids it never persists) are dropped when a true delete
     * retires the id. Each is its own listener, so one failing does not stop the other; a rename
     * retires nothing, so ownership and comment threads follow the page to its new name.
     * Citations register their own cleanup in {@code CitationWiringHelper}.
     */
    static void wireRetirementCleanups( final DefaultStructuralIndexService structuralIndex,
                                        final PersistenceSubsystem.Services persistenceSubsystem ) {
        final com.wikantik.comments.PageOwnerService owners = persistenceSubsystem.pageOwners();
        if ( owners != null ) {
            structuralIndex.addPageRetirementListener( ( slug, canonicalId ) -> owners.deleteForPage( canonicalId ) );
        }
        final com.wikantik.comments.CommentStore comments = persistenceSubsystem.comments();
        if ( comments != null ) {
            structuralIndex.addPageRetirementListener(
                ( slug, canonicalId ) -> comments.deleteThreadsForPage( canonicalId ) );
        }
    }

    /**
     * Registers the save-time filters that depend on the structural index:
     * {@link StructuralSpinePageFilter} (canonical_id assignment),
     * {@link com.wikantik.knowledge.agent.RunbookValidationPageFilter}, and
     * {@link com.wikantik.frontmatter.schema.SchemaValidationPageFilter} (schema-driven
     * frontmatter validation, which subsumes the old strict-YAML-only filter).
     *
     * <p>Called after all three validation components are instantiated so
     * they see a fully-built structural index.</p>
     */
    public static void wireSpineFilters(
            final Properties props,
            final DefaultStructuralIndexService structuralIndex,
            final CoreSubsystem.Services coreSubsystem,
            final FilterManager filterManager,
            final PageManager pageManager,
            final WikiEngine engine ) {

        filterManager.addPageFilter(
            new StructuralSpinePageFilter(
                structuralIndex,
                name -> {
                    final SystemPageRegistry sys = coreSubsystem.systemPageRegistry();
                    return sys != null && sys.isSystemPage( name );
                },
                props,
                engine.pageCanonicalIdsDao() ),
            SaveFilterPriority.STRUCTURAL_SPINE );
        filterManager.addPageFilter(
            new com.wikantik.knowledge.agent.RunbookValidationPageFilter(
                structuralIndex, pageManager, props ),
            SaveFilterPriority.RUNBOOK_VALIDATION );
        filterManager.addPageFilter(
            new com.wikantik.frontmatter.schema.SchemaValidationPageFilter( props, pageManager ),
            SaveFilterPriority.SCHEMA_VALIDATION );
        filterManager.addPageFilter(
            new com.wikantik.markdown.extensions.math.MathValidationPageFilter( props, pageManager ),
            SaveFilterPriority.MATH_VALIDATION );
        filterManager.addPageFilter(
            new CitationLinkRenderingFilter( structuralIndex ),
            SaveFilterPriority.CITATION_LINK_RENDERING );
    }
}
