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
 * wiring block previously lived inline in {@code WikiEngine.initKnowledgeGraph}.
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
     *         downstream helpers (KG policy, entity extraction, etc.)
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

        engine.registerPageVerificationDao( pageVerificationDao );
        engine.registerTrustedAuthorsDao( trustedAuthorsDao );
        engine.registerStructuralIndexService( structuralIndex );

        // WikiEventManager holds listeners as WeakReferences — keep a strong
        // reference in the managers map so the listener is not GC'd between events.
        final StructuralIndexEventListener structuralIndexListener =
            new StructuralIndexEventListener( structuralIndex );
        structuralIndexListener.register( pageManager, filterManager );
        engine.registerStructuralIndexEventListener( structuralIndexListener );

        new Thread( structuralIndex::rebuild, "structural-index-bootstrap" ).start();
        LOG.info( "StructuralIndexService registered; initial rebuild dispatched" );

        // Page Graph snapshot service — backs the /page-graph React route.
        final DefaultPageGraphService pageGraphService =
            new DefaultPageGraphService(
                structuralIndex, referenceManager, pageManager );
        pageGraphService.setEngine( engine );
        engine.registerPageGraphService( pageGraphService );
        LOG.info( "PageGraphService registered" );

        return structuralIndex;
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
                props ),
            -1003 );
        filterManager.addPageFilter(
            new com.wikantik.knowledge.agent.RunbookValidationPageFilter(
                structuralIndex, pageManager, props ),
            -1003 );
        filterManager.addPageFilter(
            new com.wikantik.frontmatter.schema.SchemaValidationPageFilter( props, pageManager ),
            -1006 );
        filterManager.addPageFilter(
            new com.wikantik.markdown.extensions.math.MathValidationPageFilter( props, pageManager ),
            -1007 );
        filterManager.addPageFilter(
            new CitationLinkRenderingFilter( structuralIndex ),
            100 );
    }
}
